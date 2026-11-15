#!/usr/bin/env python3
"""Operate the PBL4 Compose proxy through docker exec; never publish its management port.

Host access to /__proxy/* on :8080 intentionally gets HTTP 403 when the reverse
proxy is inside Docker. Use trusted local Docker access instead:
 python scripts/ops/docker_operator.py metrics
 python scripts/ops/docker_operator.py strategy
 python scripts/ops/docker_operator.py drain backend1:9001
 python scripts/ops/docker_operator.py resume backend1:9001
"""
from pathlib import Path
import argparse
import json
import re
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]

def command(compose_file:Path,action:str,backend:str|None):
    if action in {'drain','resume'} and not backend:
        raise ValueError('Backend ID is required')
    if backend is not None and not re.fullmatch(r'[A-Za-z0-9.-]+:[0-9]{1,5}', backend):
        raise ValueError('Invalid backend ID')
    if action not in {'metrics','strategy','drain','resume'}:
        raise ValueError('Unsupported operator action')
    return ['docker','compose','--env-file',str(compose_file.parent/'.env'),
            '-f',str(compose_file),'exec','-T','proxy',
            'java','com.example.proxy.server.OperatorCli',action,*([backend] if backend else [])]

def metrics(compose_file:Path,timeout=10):
    """Read one JSON metrics snapshot over exec, with no extra exposed HTTP port."""
    cmd=command(compose_file,'metrics',None)
    completed=subprocess.run(cmd,cwd=ROOT,capture_output=True,text=True,timeout=timeout,check=False)
    if completed.returncode!=0:
        raise RuntimeError('Docker operator metrics failed with exit '+str(completed.returncode))
    marker,sep,body=completed.stdout.partition('\n')
    if marker.strip()!='HTTP 200' or not sep or len(body)>2*1024*1024:
        raise RuntimeError('Invalid or oversized operator response')
    result=json.loads(body)
    if not isinstance(result,dict):raise RuntimeError('Metrics JSON is not an object')
    return result

def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['metrics','strategy','drain','resume'])
    parser.add_argument('backend',nargs='?')
    parser.add_argument('--compose-file',type=Path,default=ROOT/'deploy/compose/compose.yaml')
    opts=parser.parse_args(argv)
    if not opts.compose_file.is_file():parser.error('Missing Compose file')
    if not (opts.compose_file.parent/'.env').is_file():
        parser.error('Missing private deploy/compose/.env; copy .env.example and supply secrets')
    try:
        cmd=command(opts.compose_file.resolve(),opts.action,opts.backend)
        # Env token is forwarded to OperatorCli by its container; never copied into argv.
        result=subprocess.run(cmd,cwd=ROOT,check=False,timeout=30)
        return result.returncode
    except (ValueError,FileNotFoundError,subprocess.TimeoutExpired) as exc:
        print('OPERATOR BLOCKED:',type(exc).__name__,str(exc),file=sys.stderr)
        return 2

if __name__=='__main__':raise SystemExit(main())
