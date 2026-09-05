"""Render the saved Markdown evidence report as a static, network-free HTML document."""
import argparse
import shutil
from pathlib import Path
import markdown

p=argparse.ArgumentParser();p.add_argument('--run',required=True);root=Path(p.parse_args().run)
body=markdown.markdown((root/'REPORT.md').read_text(encoding='utf-8'),extensions=['tables','fenced_code','toc'])
# Keep the exported report directory usable even when served without the whole repo.
for target in ['../../en/adr/0003-recoverable-route-bound-evidence.md','../../../scripts/reliability/README.md']:
    source=(root/target).resolve();destination=root/'reference'/source.name
    destination.parent.mkdir(exist_ok=True);shutil.copy2(source,destination)
    body=body.replace('href="'+target+'"','href="reference/'+source.name+'"')
video='<figure><video controls preload="metadata" aria-label="Actual Grafana convergence recording" src="grafana-live-final.mp4"></video><figcaption>Actual browser screencast during scenario-04. Original timestamps are retained; final presentation changes are documented above.</figcaption></figure>'
body=body.replace('<h2 id="reproduce-and-inspect-the-exact-baseline">',video+'<h2 id="reproduce-and-inspect-the-exact-baseline">')
page='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src 'self'; media-src 'self'; style-src 'unsafe-inline'; base-uri 'none'">
<title>AegisRoute — synthetic reliability acceptance</title>
<style>
:root{color-scheme:light;font-family:system-ui,Segoe UI,sans-serif;color:#1e293b;background:#f6f7f9;font-size:16px;line-height:1.65}
body{margin:0}main{max-width:1080px;margin:0 auto;padding:3rem 2rem 6rem;background:#fff}
h1{font-size:2.3rem;line-height:1.16;margin:0 0 1.2rem;letter-spacing:-.025em}h2{font-size:1.55rem;line-height:1.25;margin:3.5rem 0 1.3rem}
p,li{max-width:82ch}a{color:#1753a6;text-decoration-thickness:1px;text-underline-offset:3px}a:focus-visible{outline:3px solid #4475c6;outline-offset:4px}
table{border-collapse:collapse;width:100%;font-size:.9rem;font-variant-numeric:tabular-nums;display:block;overflow:auto;margin:1.5rem 0}
th,td{text-align:left;padding:.65rem .85rem;border-bottom:1px solid #dce1e7;vertical-align:top}th{font-weight:650;background:#eef2f7;white-space:nowrap}
pre{background:#eef2f7;padding:1.2rem;overflow:auto;font-size:.86rem;line-height:1.65}code{font-family:Consolas,monospace;font-size:.92em;overflow-wrap:anywhere}
img,video{display:block;width:100%;height:auto;margin:1.5rem 0;border:1px solid #dce1e7;box-sizing:border-box}figure{margin:2rem 0}figcaption{font-size:.9rem;color:#45536a}
li{margin:.6rem 0}::selection{background:#d6e5fa}@media(max-width:650px){main{padding:1.5rem 1rem}h1{font-size:1.8rem}table{font-size:.85rem}}
@media print{main{max-width:none;padding:0}h2{break-after:avoid}pre,table,img{break-inside:avoid}video{display:none}}
</style><main>'''+body+'</main></html>'
(root/'REPORT.html').write_text(page,encoding='utf-8')
print(root/'REPORT.html')
