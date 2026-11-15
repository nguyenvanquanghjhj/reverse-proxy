#!/usr/bin/env python3
"""Reproducible isolated measurement runner; no live store data changes.

Delegates to existing scripts/lab.py and scripts/research.py, retaining full raw evidence.
"""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, sys, datetime, platform
ROOT=Path(__file__).resolve().parents[1]
def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda:f.read(65536),b''):h.update(chunk)
    return h.hexdigest()
def run(args,log,out):
    with log.open('wb') as stream:
        p=subprocess.run(args,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,check=False)
    print(' '.join(args),'->',p.returncode,'log',log)
    return p.returncode

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--out',required=True,type=Path)
    ap.add_argument('--repeats',type=int,default=3)
    ap.add_argument('--requests',type=int,default=240)
    ap.add_argument('--comparisons-only',action='store_true')
    a=ap.parse_args();out=a.out.resolve()
    if a.repeats<1 or a.requests<20:ap.error('Need repeats>=1 and >=20 requests for p95')
    if out.exists():ap.error('Evidence directory must not exist; data is never overwritten')
    out.mkdir(parents=True)
    evidence=out/'measurements'
    command=[sys.executable,'scripts/lab.py','--out',str(evidence),'--repeats',str(a.repeats),'--requests',str(a.requests)]
    if a.comparisons_only:command.append('--comparisons-only')
    measurement_code=run(command,out/'measurement.log',out)
    report_code=None
    if measurement_code==0 and evidence.exists():
        report_code=run([sys.executable,'scripts/research.py',str(evidence)],out/'analysis.log',out)
    meta={'created_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'python':sys.version,'platform':platform.platform(),'measurement_exit':measurement_code,
          'analysis_exit':report_code,'scope':'loopback only; not network-layer RTT'}
    (out/'status.json').write_text(json.dumps(meta,indent=2),encoding='utf-8')
    report=['# Experiment release evidence','',f'- Measurement exit: {measurement_code}',f'- Analysis exit: {report_code}',
            f'- Runs: {a.repeats}; requests/condition: {a.requests}',
            '- Results and raw per-request evidence: `measurements/`',
            '- SHA-256 manifest: `manifest.json`',
            '- This runner does not convert a failed run to PASS.',
            '- Loopback experiments do not establish LAN fault tolerance.',
            '- Dependencies, environment, cache and statistical variability must be documented before generalizing.', '']
    (out/'REPORT.md').write_text('\n'.join(report),encoding='utf-8')
    manifest={str(p.relative_to(out)):sha(p) for p in sorted(out.rglob('*')) if p.is_file() and p.name!='manifest.json'}
    (out/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    sys.exit(0 if measurement_code==0 and report_code==0 else 1)
if __name__=='__main__':main()
