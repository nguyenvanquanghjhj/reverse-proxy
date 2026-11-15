#!/usr/bin/env python3
"""Read-only heterogeneous HTTP benchmark with raw latency/backend/HTTP evidence.
Execute separately for each scheduler on the same hardware and baseline.
No order mutations; no automatic strategy switching; no fake PASS on missing backend IDs.
"""
import argparse, concurrent.futures, csv, json, math, pathlib, random, statistics, time
import urllib.error, urllib.parse, urllib.request
from datetime import datetime, timezone

PATHS={'light':'/work?cost=1','cpu':'/compute?cost=4','slow':'/slow?cost=1'}
def pct(values,p):
    if not values:return None
    s=sorted(values); index=(len(s)-1)*p/100;lo=int(index);hi=math.ceil(index)
    return round(s[lo]+(s[hi]-s[lo])*(index-lo),4)

def request(base,path,timeout):
    start=time.perf_counter();code=None;backend='UNKNOWN';status='transport_error';size=0
    try:
        req=urllib.request.Request(base+path,method='GET',headers={'Cache-Control':'no-cache','User-Agent':'pbl4-heterogeneous-lab/1.0'})
        try:response=urllib.request.urlopen(req,timeout=timeout)
        except urllib.error.HTTPError as e:response=e
        with response:
            code=response.status;backend=response.headers.get('X-Proxy-Backend','UNKNOWN');status='http_response'
            while True:
                data=response.read(32768)
                if not data:break
                size+=len(data)
            declared=response.headers.get('Content-Length')
            if declared is not None and size!=int(declared):status='incomplete_body'
    except Exception as exc:status=type(exc).__name__
    return {'utc':datetime.now(timezone.utc).isoformat(),'path':path,'http_status':code,
            'outcome':status,'backend':backend,'bytes':size,'latency_ms':round((time.perf_counter()-start)*1000,4)}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base',default='http://127.0.0.1:8080')
    p.add_argument('--strategy-label',required=True,choices=['ROUND_ROBIN','LEAST_CONNECTIONS','ADAPTIVE'])
    p.add_argument('--out',type=pathlib.Path,required=True)
    p.add_argument('--requests',type=int,default=300)
    p.add_argument('--concurrency',type=int,default=12)
    p.add_argument('--timeout',type=float,default=7)
    p.add_argument('--seed',type=int,default=1415)
    p.add_argument('--allow-remote',action='store_true')
    a=p.parse_args();target=urllib.parse.urlparse(a.base)
    if target.scheme!='http' or not target.netloc or target.username or target.password or target.query or target.path not in ('','/'):
        p.error('Require a plain http://host:port origin with no credentials or path')
    if not a.allow_remote and target.hostname not in ('127.0.0.1','localhost','::1'):
        p.error('Remote testing requires explicit --allow-remote and operator approval')
    if not 10<=a.requests<=50000 or not 1<=a.concurrency<=128 or not 1<=a.timeout<=120:
        p.error('Requests/concurrency/timeout out of safe bounds')
    if a.out.exists():p.error('Refuse to overwrite existing evidence directory')
    a.out.mkdir(parents=True)
    rng=random.Random(a.seed)
    plan=rng.choices(list(PATHS),weights=[6,3,1],k=a.requests)
    raw=[];started=time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=a.concurrency) as pool:
        futures=[pool.submit(request,a.base.rstrip('/'),PATHS[x],a.timeout) for x in plan]
        for f in concurrent.futures.as_completed(futures):raw.append(f.result())
    elapsed=time.monotonic()-started
    success=[v['latency_ms'] for v in raw if v['http_status']==200 and v['outcome']=='http_response']
    all_latency=[v['latency_ms'] for v in raw]
    backends={}
    for v in raw:
        if v['backend']!='UNKNOWN':backends[v['backend']]=backends.get(v['backend'],0)+1
    report={'schema':1,'utc':datetime.now(timezone.utc).isoformat(),'strategy_label_unverified':a.strategy_label,
            'base':a.base,'requests':a.requests,'concurrency':a.concurrency,'seed':a.seed,'duration_seconds':round(elapsed,3),
            'successful':len(success),'http_failures':sum(v['http_status'] is not None and v['http_status']>=400 for v in raw),
            'transport_failures':sum(v['outcome']!='http_response' for v in raw),
            'throughput_rps':round(len(success)/max(elapsed,.001),3),'p50_success_ms':pct(success,50),
            'p95_success_ms':pct(success,95),'p99_success_ms':pct(success,99),
            'p50_all_ms':pct(all_latency,50),'p95_all_ms':pct(all_latency,95),'p99_all_ms':pct(all_latency,99),'backend_distribution':backends,
            'limitations':['No CPU/heap monitoring included','Label does not verify active strategy','One process/host is not multi-machine LAN']}
    with (a.out/'raw.csv').open('w',newline='',encoding='utf-8') as f:
        writer=csv.DictWriter(f,fieldnames=['utc','path','http_status','outcome','backend','bytes','latency_ms']);writer.writeheader();writer.writerows(raw)
    (a.out/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    if not success or not backends:raise SystemExit('FAIL: no successful routed requests; raw evidence retained')
if __name__=='__main__':main()
