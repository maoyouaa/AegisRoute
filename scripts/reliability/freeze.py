"""Bind a local acceptance run to exact files, built jars and running task-only images."""
import argparse, hashlib, json, subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def command(*args):return subprocess.check_output(args,cwd=ROOT,stderr=subprocess.PIPE)
def save(path,value):path.write_text(json.dumps(value,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
def inventory(paths,base):return [{'path':p.relative_to(base).as_posix(),'bytes':p.stat().st_size,'sha256':sha(p)} for p in sorted(set(paths)) if p.is_file()]
def code_files():
    names=command('git','ls-files','-z','--cached','--others','--exclude-standard').decode().split('\0')
    return [ROOT/n for n in names if n and not n.startswith('docs/overnight/')]
def verify(manifest,base):
    for item in manifest:
        assert sha(base/item['path'])==item['sha256'], ('Artifact differs',item['path'])

def run(args):
    out=(ROOT/args.output).resolve();out.relative_to(ROOT)
    if args.verify:
        source=json.loads((out/'code-manifest.json').read_text(encoding='utf-8'))
        verify(source['files'],ROOT)
        assert inventory(code_files(),ROOT)==source['files'], 'Source file set changed'
        artifacts=json.loads((out/'artifact-manifest.json').read_text())
        verify(artifacts['files'],out)
        print(json.dumps({'status':'PASS','sourceTreeSha256':source['sourceTreeSha256'],'sourceFiles':len(source['files']),'artifacts':len(artifacts['files'])}))
        return
    if not args.artifacts_only:
        assert args.project.startswith('aegis-') and args.project!='aegisroute'
        baseline=json.loads((out/'baseline-manifest.json').read_text(encoding='utf-8-sig'))
        original=Path(baseline['source']);original_head=subprocess.check_output(['git','-C',str(original),'rev-parse','HEAD']).decode().strip()
        original_status=subprocess.check_output(['git','-C',str(original),'status','--short']).decode().splitlines()
        assert original_head==baseline['sourceHead'] and original_status==baseline['sourceStatus']
        preserved=[{'path':x['path'],'sha256':sha(original/x['path']),'unchanged':sha(original/x['path']).upper()==x['sha256']} for x in baseline['copied']]
        assert all(x['unchanged'] for x in preserved)
        assert not command('git','diff','--cached','--name-only'), 'Index contains staged work'
        files=inventory(code_files(),ROOT)
        tree=hashlib.sha256(''.join(x['path']+'\0'+x['sha256']+'\n' for x in files).encode()).hexdigest()
        save(out/'code-manifest.json',{'capturedUtc':datetime.now(timezone.utc).isoformat(),'sourceHead':baseline['sourceHead'],'worktree':str(ROOT),'branch':command('git','branch','--show-current').decode().strip(),'sourceTreeSha256':tree,'files':files})
        (out/'final-tracked.diff').write_bytes(command('git','diff','--binary','HEAD','--','.',':!docs/overnight'))
        (out/'final-status.txt').write_bytes(command('git','status','--short'))
        jars={app:ROOT/f'apps/{app}/build/libs/application.jar' for app in ['gateway','control','worker','mock-provider']}
        main_sources=[p for p in ROOT.glob('**/src/main/**/*') if p.is_file() and p.suffix in ['.java','.yml','.sql','.json']]
        newest=max(p.stat().st_mtime for p in main_sources)
        assert all(p.stat().st_mtime>=newest for p in jars.values()), 'An application source changed after the final jar build'
        ids=command('docker','compose','-p',args.project,'-f','compose.reliability.yml','ps','-q').decode().split()
        containers=[]
        for cid in ids:
            c=json.loads(command('docker','inspect',cid))[0];service=c['Config']['Labels']['com.docker.compose.service']
            assert c['Config']['Labels']['com.docker.compose.project']==args.project
            item={'service':service,'id':cid,'imageId':c['Image'],'imageReference':c['Config']['Image'],'status':c['State']['Status'],'startedAt':c['State']['StartedAt']}
            if service in ['gateway-a','gateway-b','control','worker','baseline','candidate']:
                app='gateway' if service.startswith('gateway-') else 'mock-provider' if service in ['baseline','candidate'] else service
                deployed=command('docker','exec',cid,'sha256sum','/app/application.jar').decode().split()[0]
                assert deployed==sha(jars[app]), ('Deployed jar differs',service)
                item.update(jarSha256=deployed,app=app)
            containers.append(item)
        save(out/'runtime-code-binding.json',{'sourceTreeSha256':tree,'latestMainSourceMtime':newest,'jars':inventory(jars.values(),ROOT),'containers':containers,'baseJreImage':(out/'runtime-base-image.txt').read_text().strip(),'publication':'Local image build only; no image published'})
        old_ids=command('docker','ps','-aq','--filter','name=^aegisroute-').decode().split()
        original_containers=[]
        for cid in old_ids:
            c=json.loads(command('docker','inspect',cid))[0]
            assert c['State']['StartedAt']<baseline['startedUtc'], ('Original container restarted',c['Name'])
            original_containers.append({'name':c['Name'],'startedAt':c['State']['StartedAt']})
        save(out/'source-preservation-final.json',{'status':'PASS','head':original_head,'statusLines':original_status,'copiedFiles':preserved,'originalContainers':original_containers,'indexEmpty':True})
    excluded={'artifact-manifest.json','manifest-verification.json'}
    artifacts=inventory([p for p in out.rglob('*') if p.is_file() and p.name not in excluded],out)
    save(out/'artifact-manifest.json',{'capturedUtc':datetime.now(timezone.utc).isoformat(),'includes':'All run files, including ignored logs and raw database snapshots; excludes this manifest and its verification output to avoid self-reference. Local artifacts, not committed.','files':artifacts})
    print(json.dumps({'status':'FROZEN','artifacts':len(artifacts)}))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--project',default='aegis-20260905-002645');p.add_argument('--output',required=True);p.add_argument('--artifacts-only',action='store_true');p.add_argument('--verify',action='store_true');run(p.parse_args())
