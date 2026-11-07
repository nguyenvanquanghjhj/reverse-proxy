"""Failure/recovery with continuous traffic and an observer independent of JVM startup blocking."""
import argparse,csv,hashlib,json,os,threading,time
from pathlib import Path
from lab import Lab,ROOT,BASE,PORTS,STRATEGIES,fetch,get,store

def exercise(lab,strategy,label):
    lab.proxy(strategy,label);lab.batch(label+'-warmup',['/work?cost=1'],30,6,55)
    stop=threading.Event();rows=[];timeline=[];epoch=time.perf_counter();failure_at=restart_at=ready_at=None
    def traffic(worker):
        while not stop.is_set():
            began=time.perf_counter();code=0;error='';backend=''
            try:
                code,_,headers=fetch(BASE+'/work?cost=1');backend=next((v for k,v in headers.items() if k.lower()=='x-proxy-backend'),'')
            except Exception as e:error=type(e).__name__
            finished=time.perf_counter()
            rows.append({'worker':worker,'at_s':began-epoch,'completed_at_s':finished-epoch,'latency_ms':(finished-began)*1000,'status':code,'backend':backend,'error':error})
    def observe():
        while not stop.is_set():
            try:timeline.append({'at_s':time.perf_counter()-epoch,'backends':get(BASE+'/__proxy/metrics')['backends']})
            except Exception as e:timeline.append({'at_s':time.perf_counter()-epoch,'error':type(e).__name__})
            stop.wait(.03)
    threads=[threading.Thread(target=traffic,args=(i,)) for i in range(6)]+[threading.Thread(target=observe)]
    for thread in threads:thread.start()
    try:
        time.sleep(.3);failure_at=time.perf_counter()-epoch;lab.stop(str(PORTS[0]))
        deadline=time.perf_counter()+5
        while not any(s['at_s']>=failure_at and s.get('backends',[{}])[0].get('state')=='DOWN' for s in timeline):
            if time.perf_counter()>deadline:raise AssertionError('DOWN not observed within 5 seconds')
            time.sleep(.02)
        time.sleep(.4);restart_at=time.perf_counter()-epoch;lab.backend(PORTS[0],delay=10);ready_at=time.perf_counter()-epoch
        deadline=time.perf_counter()+15
        while not any(s['at_s']>=restart_at and s.get('backends',[{}])[0].get('state')=='UP' for s in timeline):
            if time.perf_counter()>deadline:raise AssertionError('UP not observed after listener liveness')
            time.sleep(.02)
        time.sleep(.4)
    finally:
        stop.set()
        for thread in threads:thread.join(timeout=12)
        with (lab.out/(label+'.csv')).open('w',newline='',encoding='utf-8') as file:
            writer=csv.DictWriter(file,fieldnames=['worker','at_s','completed_at_s','latency_ms','status','backend','error']);writer.writeheader();writer.writerows(sorted(rows,key=lambda r:r['at_s']))
        (lab.out/(label+'-timeline.json')).write_text(json.dumps(timeline),encoding='utf-8')
    detected=next(s['at_s'] for s in timeline if s['at_s']>=failure_at and s.get('backends',[{}])[0].get('state')=='DOWN')
    up=next(s['at_s'] for s in timeline if s['at_s']>=restart_at and s.get('backends',[{}])[0].get('state')=='UP')
    warming=next((s['at_s'] for s in timeline if s['at_s']>=restart_at and s.get('backends',[{}])[0].get('state')=='WARMING'),None)
    until=warming if warming is not None else up
    stable_down=[r for r in rows if detected+.05<=r['at_s']<min(until,restart_at)]
    assert stable_down and all('19001' not in r['backend'] for r in stable_down),'New requests selected DOWN backend'
    metrics=get(BASE+'/__proxy/metrics');assert all(b['state']=='UP' and b['inFlight']==0 for b in metrics['backends'])
    return {'label':label,'strategy':strategy,'passed':True,'failure_at_s':failure_at,'restart_requested_at_s':restart_at,'listener_ready_at_s':ready_at,
            'detection_ms':(detected-failure_at)*1000,'listener_liveness_after_restart_ms':(ready_at-restart_at)*1000,
            'routing_up_after_restart_ms':(up-restart_at)*1000,'warming_observed_ms':(up-warming)*1000 if warming is not None else None,
            'requests':len(rows),'errors':sum(r['status']==0 or r['status']>=400 for r in rows),
            'failure_window_errors':sum((r['status']==0 or r['status']>=400) and r['completed_at_s']>=failure_at and r['at_s']<=up for r in rows),
            'failure_window_definition':'Request interval overlaps process stop through routing UP, including requests already in-flight.',
            'stable_down_requests':len(stable_down),'circuit_states':sorted({s['backends'][0]['circuitState'] for s in timeline if 'backends' in s})}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,required=True);p.add_argument('--repeats',type=int,default=3);args=p.parse_args()
    os.chdir(ROOT);store.configure(ROOT/'.env.store');lab=None;results=[]
    try:
        lab=Lab.__new__(Lab);lab.__init__(args.out.resolve());lab.profile((10,10,10))
        for repeat in range(args.repeats):
            if repeat:
                lab.stop('proxy')
                for port in PORTS:lab.stop(str(port))
                for port in PORTS:lab.backend(port)
                lab.profile((10,10,10))
            order=STRATEGIES[repeat%3:]+STRATEGIES[:repeat%3]
            for strategy in order:
                result=exercise(lab,strategy,f'failure-{repeat+1}-{strategy}');result['repeat']=repeat+1;results.append(result)
                (lab.out/'failures.json').write_text(json.dumps(results,indent=2),encoding='utf-8');print(json.dumps(result),flush=True)
        (lab.out/'runner.json').write_text(json.dumps({'sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'traffic':'6 closed-loop workers plus independent 30 ms observer; loopback only','limits':'Detection interval and OS scheduling bound observation precision; startup and routing recovery clocks are distinct.'},indent=2),encoding='utf-8')
    finally:
        if lab and hasattr(lab,'children'):lab.close()
if __name__=='__main__':main()
