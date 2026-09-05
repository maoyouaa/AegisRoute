"""Preserve the existing <5s online rollback gate using saved server timestamps."""
import argparse
from datetime import datetime
import json
from pathlib import Path


def verify(root):
    observed = json.loads((root/'automatic-rollback.json').read_text())
    assert observed['decision']['kind'] == 'POLICY'
    decision_id = observed['decision']['id']
    database = root/'after-automatic'
    def rows(table):
        return [json.loads(line) for line in (database/(table+'.jsonl')).read_text().splitlines() if line]
    decision = next(d for d in rows('rollout_decisions') if d['decision_id'] == decision_id)
    target = next(t for t in rows('rollback_route_targets') if t['decision_id'] == decision_id)
    matches = [a for a in rows('gateway_ack_history') if a['route_id'] == target['route_id']
               and a['route_version'] == target['route_version'] and a['checksum'] == target['checksum']]
    assert {a['gateway_instance_id'] for a in matches} == {'gateway-a','gateway-b'}
    converged = next(c for c in rows('gateway_convergence_evidence')
                     if c['rollout_id'] == decision['rollout_id'] and c['target_route_version'] == target['route_version'])
    assert set(converged['required_instances']) == set(converged['converged_instances']) == {'gateway-a','gateway-b'}
    milliseconds = (datetime.fromisoformat(converged['converged_at']) - datetime.fromisoformat(decision['created_at'])).total_seconds()*1000
    result = {'status':'PASS' if 0 <= milliseconds < 5000 else 'FAIL',
              'decisionId':decision_id,'targetRouteVersion':target['route_version'],
              'convergenceMilliseconds':milliseconds,'strictUpperBoundMilliseconds':5000,
              'scope':'Selected online policy rollback only; offline manual rollback has a separate pending-state gate.'}
    (root/'online-convergence.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result))
    assert result['status'] == 'PASS', result


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario',type=Path,required=True)
    verify(parser.parse_args().scenario)
