"""Synthetic local process-reliability acceptance. No external endpoint or other project is touched."""
import argparse, concurrent.futures, hashlib, http.client, json, math, re, sqlite3, subprocess, time, uuid
from pathlib import Path
from email.utils import parsedate_to_datetime
from transport import api, call, configure, connection
from endpoints import endpoint

class Run:
    def __init__(self,args):
        self.args=args
        if not args.project.startswith("aegis-") or args.project=="aegisroute": raise ValueError("Task-specific Compose project required")
        self.out=Path(args.output).resolve(); self.out.mkdir(parents=True,exist_ok=False)
        self.prefix="scenario-"+uuid.uuid4().hex[:8]; self.timeline=[]; self.calls=[]; self.stopped=set()
        self.compose=["docker","compose","-p",args.project,"-f","compose.reliability.yml"]
        self.control=endpoint("CONTROL");self.baseline=endpoint("BASELINE");self.candidate=endpoint("CANDIDATE")
        self.gateways=[endpoint("GATEWAY_A"),endpoint("GATEWAY_B")]
    def save(self,name,value): (self.out/name).write_text(json.dumps(value,indent=2),encoding="utf-8")
    def event(self,name,**values):
        item={"time":time.time(),"event":name,**values};self.timeline.append(item);self.save("timeline.json",self.timeline)
        print(json.dumps(item),flush=True)
    def docker(self,*args,input=None,timeout=50,check=True):
        r=subprocess.run(self.compose+list(args),input=input,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
        if check and r.returncode: raise AssertionError((args,r.returncode,r.stderr.decode(errors="replace")))
        return r.stdout
    def stop(self,service): self.docker("stop","-t","1",service);self.stopped.add(service);self.event("service-stopped",service=service)
    def start(self,service): self.docker("up","-d","--no-deps",service);self.stopped.discard(service);self.event("service-started",service=service)
    def wait(self,predicate,description,timeout=100):
        end=time.monotonic()+timeout;last=None
        while time.monotonic()<end:
            try:
                last=predicate()
                if last:return last
            except (OSError,AssertionError,http.client.HTTPException): pass
            time.sleep(.5)
        raise AssertionError(("Timeout",description,last))
    def consumed(self,label):
        output=self.docker("exec","-T","redpanda","rpk","group","describe","aegis-worker-v2","aegis-pairing-v2","-c").decode()
        rows=[line.split() for line in output.splitlines() if line.startswith("aegis.")]
        if len(rows)==2 and all(row[5]=="0" and row[2]==row[4] for row in rows):
            (self.out/(label+"-offsets.txt")).write_text(output);return True
        return False
    def worker_pending(self):
        c=connection(endpoint("WORKER"));c.request("GET","/actuator/prometheus");r=c.getresponse();body=r.read().decode();c.close()
        assert r.status==200
        return next(float(line.split()[-1]) for line in body.splitlines() if line.startswith("aegis_worker_pending_windows "))
    def broker_drops(self,label):
        values=[]
        for i,gateway in enumerate(self.gateways):
            c=connection(gateway);c.request("GET","/actuator/prometheus");r=c.getresponse();body=r.read().decode();c.close()
            assert r.status==200
            (self.out/f"broker-{label}-{i}-metrics.txt").write_text(body)
            values.append(sum(float(line.split()[-1]) for line in body.splitlines()
                              if line.startswith("aegis_shadow_dropped_total") and re.search(r'reason="(publisher_timeout|broker_unavailable)"',line)))
        return sum(values)
    def status(self): return api(self.control,"/internal/v2/status")
    def post(self,path,body,headers=None):
        c=connection(self.control);c.request("POST",path,json.dumps(body),{"Content-Type":"application/json",**(headers or {})})
        r=c.getresponse();data=r.read();c.close()
        return r.status,json.loads(data) if data else None
    def mutate(self,action,ratio=None,expect=200):
        s=self.status();key=str(uuid.uuid4());headers={"Idempotency-Key":key,"If-Match":'"'+str(s["rolloutVersion"])+'"'}
        body={"actor":"synthetic-human-script","reason":"Explicit local acceptance command "+action,"candidateRatio":ratio}
        path="/api/v1/rollouts/"+s["route"]["rolloutId"]+"/"+action
        code,result=self.post(path,body,headers);self.event("human-api",action=action,ratio=ratio,status=code,result=result)
        assert code==expect,(code,result)
        if code==200:
            replay=self.post(path,body,headers);assert replay==(code,result)
            self.wait(lambda:self.applied(result["routeVersion"]),"both routes applied")
        return result
    def applied(self,version):
        for gateway in self.gateways:
            c=connection(gateway);c.request("GET","/actuator/prometheus");r=c.getresponse();body=r.read().decode();c.close()
            if r.status!=200 or not any(line.startswith("aegis_route_version ") and float(line.split()[-1])==version for line in body.splitlines()):return False
        return True
    def bucket(self):
        # Nginx and Java share the Docker clock; the Windows client need not.
        # HTTP Date is second-granular, so start safely inside the next bucket.
        c=connection(self.control);c.request("GET","/internal/v2/status");r=c.getresponse();r.read();c.close()
        assert r.status==200 and r.getheader("Date"), "Local runtime clock unavailable"
        server=parsedate_to_datetime(r.getheader("Date")).timestamp()
        start=(math.floor(server/5)+1)*5
        time.sleep(start+1-server)
        return start
    def ids(self,label,count,candidate=None,ratio=None):
        result=[];i=0
        while len(result)<count:
            value=f"{self.prefix}-{label}-{i}";i+=1
            selected=int.from_bytes(hashlib.sha256(value.encode()).digest()[:4],"big")%10000 < (ratio or 0)*100
            if candidate is None or selected==candidate: result.append(value)
        return result
    def load(self,label,n=20,candidate_n=0,stream=False,expected=200,one_gateway=False,align=True):
        s=self.status();route=s["route"]
        if align:start=self.bucket()
        else:start=math.floor(time.time()/5)*5
        ids=self.ids(label+"-b",n,False,route["candidateRatio"]) if n else []
        ids+=self.ids(label+"-c",candidate_n,True,route["candidateRatio"]) if candidate_n else []
        def send(item):
            i,rid=item;return call(self.gateways[0 if one_gateway else i%2],rid,stream,"text/event-stream" if stream else "application/json")
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: results=list(pool.map(send,enumerate(ids)))
        for r in results: assert r.get("status")==expected,r
        self.calls.extend(results);self.save("http-calls.json",self.calls)
        self.event("load",label=label,count=len(ids),bucket=start,routeVersion=route["version"],p95ms=sorted(r["elapsedMs"] for r in results)[math.ceil(.95*len(results))-1])
        return start,ids,results
    def qualify(self,label):
        s=self.status();ratio=s["route"]["candidateRatio"]
        start,ids,_=self.load(label,20,20 if ratio else 0)
        result=self.wait(lambda:self.match_window(start,eligible=True),"qualifying window "+label,70)
        self.save(label+"-qualification.json",result);return result
    def match_window(self,start,eligible=None):
        s=self.status();w=s.get("window")
        if not w:return False
        from datetime import datetime
        epoch=datetime.fromisoformat(w["evidence"]["windowStart"].replace("Z","+00:00")).timestamp()
        if epoch!=start:return False
        if eligible is not None and w["result"]["eligible"]!=eligible:return False
        return s
    def snapshot(self,label):
        folder=self.out/label;folder.mkdir()
        cid=self.docker("ps","-q","worker").decode().strip();assert cid
        subprocess.run(["docker","pause",cid],check=True,stdout=subprocess.DEVNULL)
        try:
            for name in ["evidence.sqlite","evidence.sqlite-wal","evidence.sqlite-shm"]:
                r=subprocess.run(["docker","cp",cid+":/var/lib/aegis/"+name,str(folder/name)],capture_output=True)
                if name=="evidence.sqlite":assert r.returncode==0,r.stderr
        finally:subprocess.run(["docker","unpause",cid],check=True,stdout=subprocess.DEVNULL)
        with sqlite3.connect(folder/"evidence.sqlite") as db,sqlite3.connect(folder/"snapshot.sqlite") as dest:db.backup(dest)
        with sqlite3.connect(folder/"snapshot.sqlite") as db:
            stats={t:db.execute("select count(*) from "+t).fetchone()[0] for t in ("samples","records","windows","quarantine")}
            stats["pending_windows"]=db.execute("select count(*) from windows where receipt is null").fetchone()[0]
            for table in ("records","windows","quarantine","samples"):
                cols=[col[0] for col in db.execute("select * from "+table+" limit 0").description]
                rows=[{k:(v.decode() if isinstance(v,bytes) else v) for k,v in zip(cols,row)} for row in db.execute("select * from "+table)]
                self.save(label+"/"+table+".json",rows)
        self.event("worker-snapshot",label=label,**stats);return folder,stats
    def db_evidence(self,label):
        tables=["rollouts","route_revisions","gateway_ack_history","gateway_window_reports","evidence_windows_v2","policy_evaluations_v2","evidence_window_results","promotion_evidence_uses","rollout_decisions","rollback_sources","rollback_route_targets","rollback_decision_targets","gateway_convergence_evidence","rollout_audit_events"]
        folder=self.out/label;folder.mkdir()
        result={}
        for table in tables:
            data=self.docker("exec","-T","postgres","psql","-U","aegis","-d","aegisroute","-At","-c","SELECT row_to_json(t) FROM "+table+" t")
            (folder/(table+".jsonl")).write_bytes(data);result[table]=[json.loads(x) for x in data.splitlines() if x]
        return result
    def final_snapshot(self):
        # Quiesce the client, pass the 5s bucket + 15s grace, then require receipts.
        # Capture Worker first: every saved receipt must predate the later PG read.
        time.sleep(22)
        self.wait(lambda:self.consumed("final"),"final committed consumer offsets",90)
        self.wait(lambda:self.worker_pending()==0,"all sealed windows receipted",90)
        folder,stats=self.snapshot("final-worker")
        assert stats["pending_windows"]==0
        samples=json.loads((folder/"samples.json").read_text());windows=json.loads((folder/"windows.json").read_text())
        assert {s["window_id"] for s in samples}.issubset({w["window_id"] for w in windows}), "Unsealed samples remain"
        return self.db_evidence("final-postgres")
    def run(self):
        self.event("start",project=self.args.project,scenarioId=self.prefix)
        configure(self.baseline);configure(self.candidate)
        self.wait(lambda:self.status(),"Control ready")
        s=self.status();self.save("initial.json",s)
        if s["phase"] not in ("DRAFT","ROLLED_BACK"): raise AssertionError("Rehearsal requires a fresh DRAFT or terminal owner")
        if s["phase"]=="ROLLED_BACK":self.create()
        self.load("draft",10)
        stats=api(self.candidate,"/internal/stats");assert not any(k.startswith(self.prefix) for k in stats["calls"])
        # Actual provider transport unavailability, followed by restoration.
        # Docker may blackhole the stopped peer (504) or refuse TCP (502); both must obey the fixed deadline.
        self.stop("baseline")
        failed=call(self.gateways[0],self.prefix+"-connection-failure",False,"application/json")
        self.save("connection-failure.json",failed);assert failed["status"] in (502,504) and failed["elapsedMs"]<3500,failed
        self.start("baseline");self.wait(lambda:api(self.baseline,"/internal/stats"),"baseline restored")
        self.mutate("shadow:start");self.qualify("initial-shadow")
        # Let a completed window persist while Control cannot acknowledge it.
        self.stop("control")
        ids=self.ids("control-outage",20)
        results=[call(self.gateways[i%2],rid,False,"application/json") for i,rid in enumerate(ids)]
        assert all(r.get("status")==200 for r in results);self.save("control-outage-http.json",results)
        time.sleep(22)
        before,counts=self.snapshot("before-worker-kill");assert counts["pending_windows"]>0,counts
        self.docker("kill","-s","SIGKILL","worker");self.event("worker-killed",signal="SIGKILL")
        self.start("worker");time.sleep(5)
        recovered,recovered_counts=self.snapshot("worker-restarted-control-down")
        assert recovered_counts["windows"]>=counts["windows"] and recovered_counts["pending_windows"]>=counts["pending_windows"]
        self.start("control");self.wait(lambda:self.status(),"Control restored")
        self.wait(lambda:self.worker_pending()==0,"durable receipts after Control recovery",100)
        after,after_counts=self.snapshot("worker-recovered")
        assert after_counts["pending_windows"]==0,after_counts
        # Replay real persisted business events with distinct event IDs through real Redpanda.
        events=json.loads((after/"records.json").read_text());selected=[e for e in events if self.prefix+"-initial-shadow" in e["payload"]]
        assert selected
        for topic in sorted({e["topic"] for e in selected}):
            payload=[]
            for e in reversed(selected):
                if e["topic"]==topic:
                    value=json.loads(e["payload"]);value["eventId"]=str(uuid.uuid4());payload.append(json.dumps(value))
            data=("\n".join(payload)+"\n").encode();(self.out/("replayed-"+topic+".jsonl")).write_bytes(data)
            self.docker("exec","-T","redpanda","rpk","topic","produce",topic,input=data)
        self.wait(lambda:self.consumed("business-replay"),"both consumer groups acknowledged replay offsets",90)
        duplicate,duplicate_counts=self.snapshot("after-business-replay")
        for key in ("samples","records","windows","quarantine"):assert duplicate_counts[key]==after_counts[key],(key,duplicate_counts,after_counts)
        self.event("business-replay-deduplicated",events=len(selected))
        # Broker outage cannot fail serving. Observe loss explicitly rather than assuming full coverage.
        drops_before=self.broker_drops("before")
        self.stop("redpanda")
        rid=self.ids("broker-outage",30)
        results=[call(self.gateways[i%2],v,i%2==0,"text/event-stream" if i%2==0 else "application/json") for i,v in enumerate(rid)]
        assert all(r.get("status")==200 and (not r["stream"] or r["frames"][-1]=="[DONE]") for r in results)
        self.save("broker-outage-http.json",results)
        assert all(r["elapsedMs"]<2000 for r in results), "Baseline waited two seconds during broker outage"
        self.wait(lambda:self.broker_drops("during")>drops_before,"explicit broker publisher drop",20)
        self.save("broker-isolation.json",{"status":"PASS","dropsBefore":drops_before,"dropsDuring":self.broker_drops("during"),
                                           "requests":len(results),"maxBaselineMs":max(r["elapsedMs"] for r in results)})
        self.start("redpanda");time.sleep(7)
        configure(self.candidate,"timeout")
        fault_bucket,ids,results=self.load("shadow-timeout",8,stream=True)
        assert all(r["frames"][-1]=="[DONE]" and r["elapsedMs"]<1500 for r in results)
        fault=self.wait(lambda:self.match_window(fault_bucket,eligible=False),"timeout produces insufficient/ineligible evidence",70)
        self.save("shadow-timeout-window.json",fault)
        assert fault["window"]["evidence"]["shadowErrors"]>0 or fault["window"]["evidence"]["pendingSamples"]>0
        counts=api(self.baseline,"/internal/stats");shadow_counts=api(self.candidate,"/internal/stats")
        assert all(counts["calls"].get(rid)==1 for rid in ids)
        assert any(shadow_counts["calls"].get(rid)==1 for rid in ids)
        self.save("shadow-timeout-provider-counts.json",{"baseline":counts,"candidate":shadow_counts})
        self.mutate("canary:approve",1,expect=409)
        configure(self.candidate,"http-500")
        fault_bucket,_,results=self.load("shadow-http500",20)
        fault=self.wait(lambda:self.match_window(fault_bucket,eligible=False),"candidate 500 evidence",70)
        self.save("shadow-http500-window.json",fault)
        assert fault["window"]["evidence"]["shadowErrors"]>0
        self.mutate("canary:approve",1,expect=409)
        configure(self.candidate)
        self.qualify("recovered-shadow")
        # Explicit synthetic human commands exercise all four public approval steps.
        for ratio in [1,10,50,100]:
            self.mutate("canary:approve",ratio)
            if ratio!=100:
                self.mutate("canary:approve",10 if ratio==1 else 50 if ratio==10 else 100,expect=409)
                self.qualify("canary-"+str(ratio))
        _,full_ids,_=self.load("full-once",0,10)
        time.sleep(3);candidate=api(self.candidate,"/internal/stats")
        assert all(candidate["calls"].get(rid)==1 for rid in full_ids)
        self.save("full-candidate-counts.json",{rid:candidate["calls"].get(rid) for rid in full_ids})
        configure(self.candidate,"http-500")
        starts=[]
        for i in range(3):starts.append(self.load("breach-"+str(i),0,20,expected=502,one_gateway=True)[0])
        assert starts[1]-starts[0]==5 and starts[2]-starts[1]==5,starts
        rolled=self.wait(lambda:self.status() if self.status()["phase"]=="ROLLED_BACK" else False,"deterministic rollback and two ACKs",90)
        assert rolled["decision"]["kind"]=="POLICY" and sorted(rolled["decision"]["confirmed"])==["gateway-a","gateway-b"]
        self.save("automatic-rollback.json",rolled);configure(self.candidate)
        # Policy window replay is identical after rollback and cannot create another decision.
        db=self.db_evidence("after-automatic")
        decision_windows=[w for w in db["evidence_window_results"] if w["result"].get("decisionId")==rolled["decision"]["id"]]
        assert len(decision_windows)==1
        wid=decision_windows[0]["window_id"];window=next(w["payload"] for w in db["evidence_windows_v2"] if w["window_id"]==wid)
        for _ in range(2):assert self.post("/internal/v2/evidence-windows",window)==(200,decision_windows[0]["result"])
        self.load("rolled-back",10)
        self.create();self.mutate("shadow:start");self.mutate("pause");self.load("paused",10)
        stats=api(self.candidate,"/internal/stats")
        assert not any(("-paused-" in rid or "-rolled-back-" in rid) and rid.startswith(self.prefix) for rid in stats["calls"])
        # Manual rollback deliberately keeps B offline: no approximate or empty-success ACK.
        self.stop("gateway-b");s=self.status()
        headers={"Idempotency-Key":str(uuid.uuid4()),"If-Match":'"'+str(s["rolloutVersion"])+'"'}
        code,result=self.post("/api/v1/rollouts/"+s["route"]["rolloutId"]+"/rollback",{"actor":"synthetic-human-script","reason":"offline target proof"},headers)
        assert code==200,(code,result);time.sleep(3)
        pending=self.status();self.save("offline-target.json",pending)
        assert pending["phase"]=="ROLLBACK_PROPAGATING" and pending["decision"]["required"] and pending["decision"]["confirmed"]==["gateway-a"]
        self.start("gateway-b")
        self.wait(lambda:self.status()["phase"]=="ROLLED_BACK","offline Gateway exact convergence",45)
        self.save("final-status.json",self.status())
        final=self.final_snapshot()
        owned={rolled["route"]["rolloutId"],self.status()["route"]["rolloutId"]}
        sources=[s for s in final["rollback_sources"] if s["rollout_id"] in owned];assert sum(s["kind"]=="POLICY" for s in sources)==1 and sum(s["kind"]=="MANUAL" for s in sources)==1
        assert len([u for u in final["promotion_evidence_uses"] if u["rollout_id"] in owned])==4
        for topic in ["aegis.shadow-requested.v2","aegis.observation.v2"]:
            (self.out/(topic+".jsonl")).write_bytes(self.docker("exec","-T","redpanda","rpk","topic","consume",topic,"-o",":end","--pretty-print=false",timeout=50))
        self.save("PASS.json",{"status":"PASS","scenarioId":self.prefix,"decisions":len(final["rollout_decisions"]),"humanPromotions":4,"synthetic":True})
        self.event("PASS")
    def create(self):
        code,result=self.post("/api/v1/rollouts",{"name":"synthetic-reliability","baselineDeploymentId":"baseline-v2","baselineBaseUrl":"http://baseline:8080","candidateDeploymentId":"candidate-v2","candidateBaseUrl":"http://candidate:8080","actor":"synthetic-human-script","reason":"Explicit local acceptance owner"},{"Idempotency-Key":str(uuid.uuid4())})
        assert code==200,(code,result);self.wait(lambda:self.applied(result["routeVersion"]),"created route")
    def restore(self):
        for service in list(self.stopped):self.start(service)
        for endpoint in [self.baseline,self.candidate]:
            try:configure(endpoint)
            except Exception:pass
        self.save("http-calls.json",self.calls)

if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--project",required=True);parser.add_argument("--output",required=True)
    run=Run(parser.parse_args())
    try:run.run()
    except Exception as error:run.event("FAIL",error=repr(error));raise
    finally:run.restore()
