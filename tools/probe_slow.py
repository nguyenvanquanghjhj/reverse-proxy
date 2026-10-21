"""Controlled mechanism experiment; not a throughput benchmark or historical-cause proof."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[1]; sys.path.insert(0,str(ROOT/'scripts'))
from benchmark import Cluster, request, until


def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--java',required=True)
    parser.add_argument('--output',type=Path,required=True); args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=False); steps=[]
    with Cluster(args.output/'run',specs=((1,20),),java=args.java,
                 overrides={'adaptive.decision-trace.enabled':'true','adaptive.warmup.ms':'0'}) as cluster:
        def snapshot(label,status,elapsed):
            trace=json.loads(request(cluster.port,'/__proxy/decision')[2])
            steps.append(dict(label=label,status=status,elapsed_ms=elapsed,trace=trace))
            return trace['lastDecision']
        start=time.perf_counter(); status=request(cluster.port,'/slow')[0]
        cold=snapshot('cold_slow',status,(time.perf_counter()-start)*1000)
        assert status==200 and cold['candidates'][0]['serviceMs']==50,cold
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            held=pool.submit(request,cluster.port,'/slow')
            until(lambda:cluster.monitor()['backends'][0]['inFlight']==1,message='one slow lease')
            start=time.perf_counter(); status=request(cluster.port,'/slow')[0]
            rejection=snapshot('slow_while_slow_running',status,(time.perf_counter()-start)*1000)
            assert status==503 and rejection['candidates'][0]['eligibility']=='WORK_BUDGET',rejection
            start=time.perf_counter(); status=request(cluster.port,'/work?cost=1')[0]
            light=snapshot('light_while_slow_running',status,(time.perf_counter()-start)*1000)
            assert status==200 and light['selectedBackend'] is not None,light
            assert held.result(timeout=8)[0]==200
        assert cluster.monitor()['backends'][0]['inFlight']==0
    result=dict(purpose=__doc__,commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
                git_dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT)),
                conditions='One backend, capacity=1, delay=20ms; normal timeouts; tracing on; warmup=0; default 50ms prior',steps=steps)
    (args.output/'observations.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('PASS: observed 50ms cold prior vs ~2s slow, work-budget 503 for queued slow, admitted light; no causal claim about historical run')


if __name__=='__main__': main()
