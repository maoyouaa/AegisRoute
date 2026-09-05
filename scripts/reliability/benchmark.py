"""Matched, paced synthetic localhost measurements; raw client and Docker samples are retained."""
import argparse, concurrent.futures, http.client, json, math, random, statistics, subprocess, threading, time
from pathlib import Path
from scenario import Run
from transport import connection, configure

def percentile(values,p):
    ordered=sorted(values);index=(len(ordered)-1)*p;lo=math.floor(index);hi=math.ceil(index)
    return ordered[lo]+(ordered[hi]-ordered[lo])*(index-lo)

def metrics(url):
    c=connection(url);c.request("GET","/actuator/prometheus");r=c.getresponse();body=r.read().decode();c.close()
    assert r.status==200
    return body

def drops(text):return sum(float(line.split()[-1]) for line in text.splitlines() if line.startswith("aegis_shadow_dropped_total"))

class Benchmark(Run):
    def measure(self,repeat,mode):
        params={"seed":self.args.seed,"count":self.args.count,"concurrency":self.args.concurrency,"ratePerSecond":self.args.rate,
                "warmupRequests":20,"client":"Python http.client; new TCP connection per request; Nginx in both modes"}
        rng=random.Random(self.args.seed)
        requests=[{"model":"synthetic","messages":[{"role":"user","content":"Synthetic fixture "+str(rng.randrange(1000))}],"stream":False} for _ in range(self.args.count)]
        label=f"{repeat}-{mode}";folder=self.out/label;folder.mkdir();self.save(label+"/parameters.json",params)
        self.save(label+"/requests.json",requests)
        def send(i,body):
            c=connection(self.gateways[0]);started=time.perf_counter();result={"index":i,"startedEpoch":time.time()}
            try:
                c.request("POST","/v1/chat/completions",json.dumps(body),{"Content-Type":"application/json","X-Request-Id":f"bench-{self.args.seed}-{i}"})
                response=c.getresponse();data=response.read();result.update(status=response.status,responseBytes=len(data))
            except Exception as error:result.update(status=0,error=repr(error))
            finally:c.close();result["latencyMs"]=(time.perf_counter()-started)*1000
            return result
        for i in range(20):send(-i-1,requests[i%len(requests)])
        time.sleep(22) # Flush warmup buckets; mode and JIT are warm before measured load.
        before=metrics(self.gateways[0]);(folder/"metrics-before.txt").write_text(before)
        stop=threading.Event();resources=[]
        ids=self.docker("ps","-q").decode().split()
        def sample_resources():
            while not stop.is_set():
                sampled=time.time();r=subprocess.run(["docker","stats","--no-stream","--format","{{json .}}",*ids],capture_output=True,timeout=12)
                resources.append({"epoch":sampled,"exitCode":r.returncode,"containers":[json.loads(line) for line in r.stdout.splitlines() if line]})
                stop.wait(.5)
        sampler=threading.Thread(target=sample_resources);sampler.start()
        start=time.perf_counter();started_epoch=time.time()
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=self.args.concurrency) as pool:
                futures=[]
                for i,body in enumerate(requests):
                    time.sleep(max(0,start+i/self.args.rate-time.perf_counter()));futures.append(pool.submit(send,i,body))
                results=[f.result() for f in futures]
        finally:
            ended_epoch=time.time();elapsed=time.perf_counter()-start
            stop.set();sampler.join()
        after=metrics(self.gateways[0]);(folder/"metrics-after.txt").write_text(after)
        self.save(label+"/observations.json",results);self.save(label+"/docker-stats.json",resources)
        latencies=[r["latencyMs"] for r in results];errors=sum(r["status"]!=200 for r in results)
        summary={"repeat":repeat,"mode":mode,**params,"startedEpoch":started_epoch,"endedEpoch":ended_epoch,
                 "durationSeconds":elapsed,"p50ms":percentile(latencies,.5),"p95ms":percentile(latencies,.95),
                 "errors":errors,"errorRate":errors/len(results),"dropsDelta":drops(after)-drops(before),"resourceSamples":len(resources),"route":self.status()["route"]}
        self.save(label+"/summary.json",summary);self.event("measurement",**{k:v for k,v in summary.items() if k not in ("route","client")})
        assert errors==0,summary
        return summary
    def run(self):
        assert self.status()["phase"]=="ROLLED_BACK","Run the lifecycle scenario first or finish the current owner"
        configure(self.baseline);configure(self.candidate);summaries=[]
        for repeat in range(1,4):
            self.create();summaries.append(self.measure(repeat,"off"))
            self.mutate("shadow:start");summaries.append(self.measure(repeat,"on"))
            self.mutate("pause");self.mutate("rollback");self.wait(lambda:self.status()["phase"]=="ROLLED_BACK","measurement owner convergence")
            time.sleep(22)
        self.save("summaries.json",summaries)
        pairs=[]
        for i in range(0,6,2):
            off,on=summaries[i:i+2];pairs.append({"repeat":off["repeat"],"p50DeltaMs":on["p50ms"]-off["p50ms"],"p95DeltaMs":on["p95ms"]-off["p95ms"],"p95DeltaPercent":100*(on["p95ms"]/off["p95ms"]-1)})
        self.save("comparison.json",{"pairedDeltas":pairs,"offP95Range":[min(s["p95ms"] for s in summaries if s["mode"]=="off"),max(s["p95ms"] for s in summaries if s["mode"]=="off")],"limitations":["Same shared Windows/Docker host; other pre-existing containers remain running.","Fixed off-then-on ordering within each pair, warm process, no cold-start or saturation claim.","Client latency includes localhost Nginx, network and request serialization. Scheduler and Docker CPU contention remain noise.","Only synthetic responses at the saved offered rate; these samples do not establish zero overhead or production capacity."]})
        self.final_snapshot()
        self.save("PASS.json",{"status":"PASS","repetitionsPerMode":3,"countPerRun":self.args.count,"synthetic":True})

if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("--project",required=True);p.add_argument("--output",required=True)
    p.add_argument("--seed",type=int,default=20260905);p.add_argument("--count",type=int,default=240);p.add_argument("--concurrency",type=int,default=4);p.add_argument("--rate",type=int,default=20)
    run=Benchmark(p.parse_args())
    try:run.run()
    except Exception as error:run.event("FAIL",error=repr(error));raise
    finally:run.restore()
