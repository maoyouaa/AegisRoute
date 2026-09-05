"""Verify curated artifact hashes and recompute the saved synthetic evidence."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main(bundle):
    manifest = json.loads((bundle / 'provenance.json').read_text(encoding='utf-8'))
    source = json.loads((bundle / 'original-source-files.json').read_text(encoding='utf-8'))
    tree = digest(''.join(x['path']+'\0'+x['sha256']+'\n' for x in source['files']).encode())
    assert tree == source['sourceTreeSha256'] == manifest['originalSourceTreeSha256']
    (ROOT / 'build-evidence').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='bundle-verify-', dir=ROOT / 'build-evidence') as temp:
        output = Path(temp)
        for item in manifest['files']:
            packed = (bundle / item['path']).resolve()
            packed.relative_to(bundle.resolve())
            data = packed.read_bytes()
            assert digest(data) == item['sha256'] and len(data) == item['bytes'], item['path']
            raw = gzip.decompress(data) if item['encoding'] == 'gzip' else data
            assert digest(raw) == item['sourceSha256'] and len(raw) == item['sourceBytes'], item['path']
            destination = (output / item['sourcePath']).resolve()
            destination.relative_to(output)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(raw)
        for run in ('scenario-04', 'benchmark-01'):
            subprocess.run([sys.executable, str(ROOT/'scripts/reliability/reconcile.py'),
                            '--scenario', str(output/run)], check=True, stdout=subprocess.DEVNULL)
        subprocess.run([sys.executable, str(ROOT/'scripts/reliability/analyze_benchmark.py'),
                        '--benchmark', str(output/'benchmark-01')], check=True, stdout=subprocess.DEVNULL)
        snapshots = []
        for name in ('before-worker-kill', 'worker-restarted-control-down', 'worker-recovered', 'after-business-replay'):
            folder = output/'scenario-04'/name
            values = {table: json.loads((folder/(table+'.json')).read_text()) for table in ('samples','records','windows')}
            counts = {table: len(rows) for table, rows in values.items()}
            assert counts == {'samples':504,'records':1016,'windows':32}, counts
            snapshots.append(values)
        for table in ('samples','records'):
            # A receipt may change after Control returns; business facts may not.
            assert all(x[table] == snapshots[0][table] for x in snapshots)
        assert [sum(w['receipt'] is None for w in x['windows']) for x in snapshots] == [1,1,0,0]
        report = {'status':'PASS','artifacts':len(manifest['files']),
                  'originalSourceTreeSha256':tree,'recomputed':['scenario windows/receipts/ACKs',
                  'benchmark windows/receipts/ACKs','1440 observations and route/sample joins',
                  'SIGKILL, Control outage and replay business-fact equality']}
        print(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, default=ROOT/'docs/evidence/reliability-20260905')
    main(parser.parse_args().bundle)
