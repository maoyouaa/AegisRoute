"""Independently reconcile saved Worker facts with immutable Control evidence and exact ACKs."""
import argparse, hashlib, json, sqlite3, sys
from datetime import datetime
from pathlib import Path

def instant(value):return datetime.fromisoformat(value.replace("Z","+00:00"))

def run(args):
    root=Path(args.scenario);worker=root/"final-worker";db=root/"final-postgres"
    samples=json.loads((worker/"samples.json").read_text());windows=json.loads((worker/"windows.json").read_text())
    control=[json.loads(line) for line in (db/"evidence_windows_v2.jsonl").read_text().splitlines() if line]
    results=[json.loads(line) for line in (db/"evidence_window_results.jsonl").read_text().splitlines() if line]
    decisions=[json.loads(line) for line in (db/"rollout_decisions.jsonl").read_text().splitlines() if line]
    targets=[json.loads(line) for line in (db/"rollback_route_targets.jsonl").read_text().splitlines() if line]
    acks=[json.loads(line) for line in (db/"gateway_ack_history.jsonl").read_text().splitlines() if line]
    assert windows, "No sealed Worker windows saved"
    assert control, "No Control evidence windows saved"
    assert decisions and targets and acks, "Missing rollback/target/ACK evidence"
    reports=[]
    for w in windows:
        evidence=json.loads(w['payload']);group=[s for s in samples if s['window_id']==w['window_id']]
        calculated={k:0 for k in ['servingObserved','candidateRequests','candidateErrors','completePairs','baselineErrors','shadowErrors','unpairedSamples','pendingSamples']}
        for s in group:
            identity=json.loads(s['identity']);baseline=json.loads(s['baseline']) if s['baseline'] else None
            canary=json.loads(s['canary']) if s['canary'] else None;shadow=json.loads(s['shadow']) if s['shadow'] else None
            result=json.loads(s['result']) if s['result'] else None
            failed=lambda o:o and (o['outcome']!='SUCCESS' or o['statusCode']>=400)
            calculated['servingObserved']+=bool(baseline or canary);calculated['candidateRequests']+=bool(canary)
            calculated['candidateErrors']+=bool(failed(canary));calculated['baselineErrors']+=bool(failed(baseline));calculated['shadowErrors']+=bool(failed(shadow))
            if identity['shadowSelected']:
                calculated['completePairs']+=bool(baseline and shadow);calculated['unpairedSamples']+=not(baseline and shadow)
                calculated['pendingSamples']+=bool(s['requested'] and (not result or instant(result['observedAt'])>instant(w['sealed_at'])))
        for k,v in calculated.items():assert evidence[k]==v,(w['window_id'],k,evidence[k],v)
        digest=hashlib.sha256((''.join(s['identity']+'\n' for s in sorted(group,key=lambda s:s['sample_key']))).encode()).hexdigest()
        assert evidence['sampleDigest']==digest,w['window_id']
        published=next((c for c in control if c['window_id']==w['window_id']),None)
        assert published is not None, ('Missing Control window',w['window_id'])
        assert published['payload']==evidence,w['window_id']
        assert w['receipt'] is not None, ('Missing Worker receipt',w['window_id'])
        saved_result=next((r['result'] for r in results if r['window_id']==w['window_id']),None)
        assert saved_result is not None and json.loads(w['receipt'])==saved_result, ('Receipt differs from original Control result',w['window_id'])
        reports.append({'windowId':w['window_id'],'routeVersion':evidence['route']['version'],'calculated':calculated,'controlMatched':bool(published),'receiptPresent':w['receipt'] is not None})
    for d in decisions:
        target=next(t for t in targets if t['decision_id']==d['decision_id'])
        assert target['route_version']==d['to_route_version']
        confirmed=[a['gateway_instance_id'] for a in acks if a['route_id']==target['route_id'] and a['route_version']==target['route_version'] and a['checksum']==target['checksum']]
        assert {'gateway-a','gateway-b'}.issubset(confirmed),(d,confirmed)
    result={'status':'PASS','source':'actual saved SQLite facts and PostgreSQL rows','sealedWindows':len(windows),'matchedControlWindows':sum(w['controlMatched'] for w in reports),'decisionsWithTwoExactAcks':len(decisions),'windows':reports}
    (root/'reconciliation.json').write_text(json.dumps(result,indent=2));print(json.dumps({k:v for k,v in result.items() if k!='windows'}))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--scenario',required=True);run(p.parse_args())
