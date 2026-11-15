#!/usr/bin/env python3
"""Finite LAN HTTP workload with raw per-request evidence and source hashes.

This performs no business writes and never changes load-balancing strategies.
It is a measured external network run, not a loopback synthetic benchmark.
Results remain scoped to the actual client, network, load and timestamp.
"""
import argparse
import concurrent.futures
import csv
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
import math
from pathlib import Path
import time
import urllib.request
import urllib.error
from urllib.parse import urlparse

ROOT=Path(__file__).resolve().parents[2]

def endpoint(raw):
    parsed=urlparse(raw)
    if parsed.scheme!='http' or parsed.username or parsed.password or parsed.params or parsed.fragment:
        raise ValueError('Only plain HTTP to a private/loopback host is supported')
    if parsed.hostname is None:raise ValueError('Missing host')
    if parsed.hostname=='localhost':pass
    else:
        address=ipaddress.ip_address(parsed.hostname)
        if address.is_unspecified or address.is_multicast or address.is_global or address.is_link_local:
            raise ValueError('Target must be a private or loopback IP, not the public Internet')
    if parsed.path not in {'/work','/slow','/compute'} or (parsed.query and parsed.query!='cost=1'):
        raise ValueError('Only the read-only work endpoints are allowed')
    if parsed.port is None or parsed.port<1 or parsed.port>65535:
        raise ValueError('Explicit port required')
    return raw

def probe(url,timeout,i):
    start=time.perf_counter()
    try:
        request=urllib.request.Request(url,method='GET',headers={'Cache-Control':'no-store', 'User-Agent':'PBL4-LAN-Research/1'})
        try:response=urllib.request.urlopen(request,timeout=timeout)
        except urllib.error.HTTPError as err:response=err
        with response:
            status=response.status
            payload=response.read(1024*1024+1)
            if len(payload)>1024*1024:raise ValueError('Response body too large')
            backend=response.headers.get('X-Proxy-Backend','')
            request_id=response.headers.get('X-Request-Id','')
        return {'id':i,'status':status,'latency_ms':round((time.perf_counter()-start)*1000,4),
                'backend':backend,'request_id':request_id,'error_type':''}
    except (OSError,ValueError) as exc:
        return {'id':i,'status':0,'latency_ms':round((time.perf_counter()-start)*1000,4),
                'backend':'','request_id':'','error_type':type(exc).__name__}

def quantile(values,q,min_samples):
    if len(values)<min_samples:return None
    values=sorted(values)
    return round(values[math.ceil(len(values)*q)-1],3)

def run(url,requests,workers,timeout,out):
    endpoint(url)
    if out.exists():raise ValueError('Output folder already exists; no overwrite')
    if not 20<=requests<=100000 or not 1<=workers<=128 or not .1<=timeout<=60:
        raise ValueError('Invalid limits')
    out.mkdir(parents=True)
    before=datetime.now(timezone.utc).isoformat();started=time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        results=list(pool.map(lambda i:probe(url,timeout,i),range(1,requests+1)))
    elapsed=time.perf_counter()-started
    results.sort(key=lambda x:x['id'])
    success=[r for r in results if 200<=r['status']<400]
    times=[r['latency_ms'] for r in results]
    per_backend={b:sum(r['backend']==b for r in results) for b in sorted({r['backend'] for r in results if r['backend']})}
    summary={'utc_start':before,'utc_end':datetime.now(timezone.utc).isoformat(),
             'target':url,'scope':'client-observed end-to-end HTTP over the chosen LAN endpoint; not TCP RTT',
             'requests':requests,'workers':workers,'elapsed_s':elapsed,
             'successful':len(success),'failures':requests-len(success),'throughput_success_rps':len(success)/elapsed,
             'p50_all_ms':quantile(times,.50,2),'p95_all_ms':quantile(times,.95,20),
             'p99_all_ms':quantile(times,.99,100),'distribution':per_backend,
             'source_sha256':{p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in ['pom.xml','config/application.properties'] if (ROOT/p).is_file()}}
    csv_path=out/'requests.csv'
    with csv_path.open('w',newline='',encoding='utf-8') as stream:
        writer=csv.DictWriter(stream,fieldnames=list(results[0]));writer.writeheader();writer.writerows(results)
    (out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    checks={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [csv_path,out/'summary.json']}
    (out/'SHA256SUMS.json').write_text(json.dumps(checks,indent=2)+'\n',encoding='utf-8')
    return summary

def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url',required=True)
    parser.add_argument('--requests',type=int,default=240)
    parser.add_argument('--workers',type=int,default=12)
    parser.add_argument('--timeout',type=float,default=6)
    parser.add_argument('--out',required=True,type=Path)
    a=parser.parse_args(argv)
    s=run(a.url,a.requests,a.workers,a.timeout,a.out.resolve())
    print(json.dumps(s,ensure_ascii=False,indent=2))
    return 0 if s['failures']==0 else 1

if __name__=='__main__':
    try:raise SystemExit(main())
    except (ValueError,OSError) as exc:parser_msg=f'LAN MEASUREMENT BLOCKED: {type(exc).__name__}: {str(exc)}';print(parser_msg);raise SystemExit(2)
