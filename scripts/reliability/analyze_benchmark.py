"""Recompute measurements and correlate the measured requests with saved Worker facts."""
import argparse, hashlib, json, math, re, statistics
from pathlib import Path

def percentile(values, p):
    values=sorted(values);index=(len(values)-1)*p;lo=math.floor(index);hi=math.ceil(index)
    return values[lo]+(values[hi]-values[lo])*(index-lo)

def mib(value):
    number,unit=re.fullmatch(r'([\d.]+)\s*(\w+)', value.strip()).groups()
    return float(number)*{'B':1/1048576,'kB':1000/1048576,'KiB':1/1024,'MB':1000000/1048576,'MiB':1,'GB':1000000000/1048576,'GiB':1024}[unit]

def analyze(root):
    summaries=json.loads((root/'summaries.json').read_text())
    samples=json.loads((root/'final-worker/samples.json').read_text())
    assert len(summaries)==6 and sorted((s['repeat'],s['mode']) for s in summaries)==[(n,m) for n in range(1,4) for m in ['off','on']]
    rows=[];request_hashes=set()
    for s in summaries:
        folder=root/f"{s['repeat']}-{s['mode']}"
        observations=json.loads((folder/'observations.json').read_text())
        request_hashes.add(hashlib.sha256((folder/'requests.json').read_bytes()).hexdigest())
        assert len(observations)==s['count'] and all(o['status']==200 for o in observations)
        assert sorted(o['index'] for o in observations)==list(range(s['count']))
        before=(folder/'metrics-before.txt').read_text();after=(folder/'metrics-after.txt').read_text()
        metric_sum=lambda text,prefix:sum(float(line.split()[-1]) for line in text.splitlines() if line.startswith(prefix))
        assert metric_sum(after,'aegis_shadow_dropped_total')-metric_sum(before,'aegis_shadow_dropped_total')==s['dropsDelta']
        serving_delta=metric_sum(after,'aegis_serving_seconds_count')-metric_sum(before,'aegis_serving_seconds_count')
        assert serving_delta==s['count'], ('Serving metric delta',serving_delta,s['count'])
        for key,p in [('p50ms',.5),('p95ms',.95)]:
            assert abs(s[key]-percentile([o['latencyMs'] for o in observations],p))<1e-9
        expected={f"bench-{s['seed']}-{i}" for i in range(s['count'])}
        measured=[]
        for sample in samples:
            identity=json.loads(sample['identity'])
            if identity['route']['version']==s['route']['version'] and identity['requestId'] in expected:
                measured.append((sample,identity))
        assert len(measured)==s['count'],(s['repeat'],s['mode'],len(measured))
        assert {i['requestId'] for _,i in measured}==expected
        assert all(x['baseline'] and not x['canary'] and i['shadowSelected']==(s['mode']=='on') for x,i in measured)
        completed=sum(bool(x['shadow']) for x,_ in measured)
        assert completed==(s['count'] if s['mode']=='on' else 0),(s['repeat'],s['mode'],completed)
        resources=json.loads((folder/'docker-stats.json').read_text());by_service={}
        assert resources and all(r['exitCode']==0 and r['containers'] for r in resources)
        for capture in resources:
            for c in capture['containers']:
                service=re.search(r'-(gateway-a|gateway-b|control|worker|baseline|candidate|postgres|redpanda|inspector|grafana|prometheus)-\d+$',c['Name']).group(1)
                by_service.setdefault(service,[]).append({'cpu':float(c['CPUPerc'].removesuffix('%')),'memoryMiB':mib(c['MemUsage'].split('/')[0])})
        resources_summary={k:{'samples':len(v),'cpuMeanPercent':statistics.mean(x['cpu'] for x in v),'cpuPeakPercent':max(x['cpu'] for x in v),'memoryPeakMiB':max(x['memoryMiB'] for x in v)} for k,v in by_service.items()}
        rows.append({'repeat':s['repeat'],'mode':s['mode'],'routeVersion':s['route']['version'],'p50ms':s['p50ms'],'p95ms':s['p95ms'],'errors':s['errors'],'dropsDelta':s['dropsDelta'],'servingFacts':len(measured),'completeShadowFacts':completed,'resources':resources_summary})
    assert len(request_hashes)==1, 'Request bodies differ between repetitions'
    result={'status':'PASS','totalMeasuredRequests':sum(s['count'] for s in summaries),'sameRequestFixtureSha256':next(iter(request_hashes)),'rows':rows,
            'resourceMeaning':'Docker CPU percent: 100% is one logical CPU. Sparse samples per measured run; memory peak is only the largest observed sample, not a continuous maximum.'}
    (root/'analysis.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--benchmark',required=True);analyze(Path(p.parse_args().benchmark))
