"""Explicit PostgreSQL/HTTP acceptance in a new Lab schema and storage tree.

Requires Java 21 regression PASS first. All faulted JVMs belong to this run.
No production data, existing runtime state, or default configuration is modified.
"""
import argparse
import concurrent.futures as futures
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
from urllib.parse import urlsplit,urlunsplit
from tcp_gate import TcpGate

HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE.parent))
from lab import Lab, BASE, PORTS, fetch, get, ROOT, runtime
import store
from qa_paths import evidence_path, new_evidence_path


class AcceptanceLab(Lab):
    def __init__(self,out):
        target=urlsplit(os.environ['SHOP_DB_URL'][5:])
        self.gates={port:TcpGate(target.hostname,target.port or 5432) for port in PORTS}
        super().__init__(out)

    def backend(self,port,delay=0):
        original=self.env['SHOP_DB_URL'];target=urlsplit(original[5:])
        self.env['SHOP_DB_URL']='jdbc:'+urlunsplit(target._replace(netloc='127.0.0.1:'+str(self.gates[port].port)))
        try:super().backend(port,delay)
        finally:self.env['SHOP_DB_URL']=original

    def close(self):
        if hasattr(self,'children'):super().close()
        for gate in getattr(self,'gates',{}).values():gate.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file',type=Path,required=True)
    parser.add_argument('--out',type=Path,default=None)
    args=parser.parse_args()
    args.out=evidence_path(args.out or new_evidence_path('operational'))
    os.chdir(ROOT)
    store.configure(args.env_file)
    os.environ['SHOP_DB_POOL_ENABLED']='true'
    lab=None;checks=[];evidence={};success=False
    def check(value,label):
        checks.append({'check':label,'passed':bool(value)})
        print(('PASS ' if value else 'FAIL ')+label,flush=True)
        if not value:raise AssertionError(label)
    def wait(predicate,label,seconds=15):
        deadline=time.monotonic()+seconds
        while not predicate():
            if time.monotonic()>deadline:raise AssertionError('Timed out: '+label)
            time.sleep(.08)
    def snapshots():return get(BASE+'/__proxy/metrics')['backends']
    def restart(port):
        lab.stop(str(port));lab.backend(port)
    def database_fault(port,broken):
        lab.gates[port].set_enabled(not broken)
    try:
        lab=AcceptanceLab.__new__(AcceptanceLab);lab.__init__(args.out.resolve())
        lab.proxy('ROUND_ROBIN','baseline')
        wait(lambda:all(b['capabilities'].get('order_create')=='AVAILABLE' for b in snapshots()),'initial capabilities')
        check(all(b['state']=='UP' for b in snapshots()),'three real PostgreSQL-backed JVMs ready')
        evidence['jdbc']=[]
        for flag in ('false','true'):
            env={**lab.env,'SHOP_DB_POOL_ENABLED':flag}
            result=subprocess.run([runtime.java(),'-cp',runtime.CP,str(HERE/'LiveJdbcProbe.java')],
                                  env=env,cwd=ROOT,capture_output=True,timeout=90)
            (lab.out/('jdbc-'+flag+'.log')).write_bytes(result.stdout+result.stderr)
            check(result.returncode==0,'real JDBC rollback/reset/concurrency/broken-connection recovery pool='+flag)
            evidence['jdbc'].append(json.loads(result.stdout))

        status,body,headers=fetch(BASE+'/store/assets/app.js')
        etag=next(v for k,v in headers.items() if k.lower()=='etag')
        status304,body304,headers304=fetch(BASE+'/store/assets/app.js',headers={'If-None-Match':etag})
        check(status==200 and status304==304 and not body304,'ETag conditional GET returns 304 without body through proxy')
        check(etag=='"'+hashlib.sha256(body).hexdigest()+'"','ETag equals actual JavaScript SHA-256')
        for path in ('/shop','/api/store/products','/api/store/session'):
            code,_,h=fetch(BASE+path)
            check(code==200 and next(v for k,v in h.items() if k.lower()=='cache-control')=='no-store'
                  and not any(k.lower()=='etag' for k in h),'private response remains no-store: '+path)
        from playwright.sync_api import sync_playwright
        with sync_playwright() as playwright:
            browser=playwright.chromium.launch(channel='msedge',headless=True)
            page=browser.new_page();page.goto(BASE+'/shop')
            observed=page.evaluate('''async (etag)=>{let r=await fetch('/store/assets/app.js',
                {cache:'no-store',headers:{'If-None-Match':etag}});return {status:r.status,bytes:(await r.arrayBuffer()).byteLength}}''',etag)
            check(observed=={'status':304,'bytes':0},'real Edge fetch observes conditional 304 without body')
            browser.close()
        evidence['etag']={'etag':etag,'sha256':hashlib.sha256(body).hexdigest(),'browser':observed}

        b2_pid=lab.children['19002'].pid
        database_fault(19002,True)
        wait(lambda:any(b['id'].endswith(':19002') and b['state']=='UP'
                        and b['capabilities'].get('order_create')=='UNAVAILABLE' for b in snapshots()),'B2 DB unavailable while liveness UP')
        evidence['b2_database_down']=snapshots()
        check(lab.children['19002'].pid==b2_pid and lab.children['19002'].poll() is None,'B2 JVM stays alive across its real PostgreSQL network cut')
        check(fetch('http://127.0.0.1:19002/store/assets/app.js')[0]==200,'B2 static asset survives real DB connection loss')
        orders=[lab.request('ORDER') for _ in range(18)]
        check(all(code==201 and '19002' not in json.dumps(h) for code,_,h in orders),'18 orders succeed and avoid B2 after its DB loss')
        statics=[fetch(BASE+'/store/assets/app.js') for _ in range(18)]
        check(all(code==200 for code,_,_ in statics) and any('19002' in json.dumps(h) for _,_,h in statics),'RR still routes DB-independent static assets to B2')
        database_fault(19002,False)
        wait(lambda:all(b['capabilities'].get('order_create')=='AVAILABLE' for b in snapshots()),'B2 recovery')

        lab.sql('UPDATE st_operations SET sales_enabled=false, version=version+1 WHERE id=1')
        wait(lambda:all(b['capabilities'].get('order_create')=='PAUSED' for b in snapshots()),'sales pause readiness')
        check(lab.request('ORDER')[0]==423,'business pause preserves HTTP 423 through capability routing')
        lab.sql('UPDATE st_operations SET sales_enabled=true, version=version+1 WHERE id=1')
        wait(lambda:all(b['capabilities'].get('order_create')=='AVAILABLE' for b in snapshots()),'sales resume')
        for port in PORTS:database_fault(port,True)
        wait(lambda:all(b['state']=='UP' and b['capabilities'].get('order_create')=='UNAVAILABLE' for b in snapshots()),'all DB dependencies unavailable')
        check(lab.request('ORDER')[0]==503,'all DB dependencies down fail closed before dispatch')
        check(fetch(BASE+'/store/assets/app.js')[0]==200,'all DB dependencies down still serve static assets')
        evidence['all_databases_down']=snapshots()
        for port in PORTS:database_fault(port,False)
        wait(lambda:all(b['capabilities'].get('order_create')=='AVAILABLE' for b in snapshots()),'all DB dependencies recovered')

        evidence['pool_ab']=[]
        for flag in ('false','true'):
            lab.env['SHOP_DB_POOL_ENABLED']=flag
            for port in PORTS:restart(port)
            lab.proxy('ROUND_ROBIN','jdbc-pool-'+flag)
            wait(lambda:all(b['capabilities'].get('order_create')=='AVAILABLE' for b in snapshots()),'pool A/B readiness')
            for repeat in range(3):
                row=lab.batch('jdbc-'+flag+'-'+str(repeat),['ORDER'],60,6,20261115+repeat)
                check(row['measured']['success']==60,'same-build HTTP JDBC A/B 60 orders pool='+flag+' repeat='+str(repeat))
                evidence['pool_ab'].append({'pool':flag,**row})

        lab.proxy('ROUND_ROBIN','qos-enabled',extra={'proxy.max.inflight':8,'proxy.qos.reserved.critical':2,
                                                   'proxy.qos.max.critical.per.peer':1})
        for port in PORTS:
            fetch(f'http://127.0.0.1:{port}/__test/store-fault?delay_ms=1000',b'',{'X-Test-Token':lab.fault},'POST')
        with futures.ThreadPoolExecutor(max_workers=12) as pool:
            normal=[pool.submit(fetch,BASE+'/api/store/products') for _ in range(6)]
            wait(lambda:get(BASE+'/__proxy/metrics')['qos']['activeNormal']==6,'six normal permits held')
            check(fetch(BASE+'/api/store/products')[0]==503,'QoS rejects excess normal traffic while reserved capacity remains')
            critical=pool.submit(lab.request,'ORDER')
            wait(lambda:get(BASE+'/__proxy/metrics')['qos']['activeCritical']==1,'critical permit held')
            check(lab.request('ORDER')[0]==503,'QoS real-peer cap bounds competing order POST')
            check(critical.result()[0]==201 and all(f.result()[0]==200 for f in normal),'reserved valid order and admitted normal requests complete')
        wait(lambda:get(BASE+'/__proxy/metrics')['qos']['active']==0,'QoS permit cleanup')
        evidence['qos']=get(BASE+'/__proxy/metrics')['qos']
        for port in PORTS:
            fetch(f'http://127.0.0.1:{port}/__test/store-fault?delay_ms=0',b'',{'X-Test-Token':lab.fault},'POST')
        lab.proxy('ROUND_ROBIN','default-off')
        metrics=get(BASE+'/__proxy/metrics')
        check(metrics['connectionPool']['enabled'] is False and metrics['qos']['reservedCritical']==0,'runtime defaults retain upstream pool OFF and QoS OFF')

        # Exercise the real token-protected control API; Docker transport is a separate environment gate.
        token=secrets.token_hex(32);lab.env['PBL4_CONTROL_TOKEN']=token
        lab.proxy('ROUND_ROBIN','operator-auth')
        action={'X-Proxy-Action':'cycle-strategy'}
        check(fetch(BASE+'/__proxy/strategy',b'',action,'POST')[0]==403,'operator missing token rejected')
        check(fetch(BASE+'/__proxy/strategy',b'',{**action,'X-PBL4-Control-Token':'wrong'},'POST')[0]==403,'operator wrong token rejected')
        check(fetch(BASE+'/__proxy/strategy',b'',{**action,'X-PBL4-Control-Token':token},'POST')[0]==200,'operator correct token accepted')
        check(fetch(BASE+'/__proxy/strategy',b'',{**action,'X-Forwarded-For':'127.0.0.1'},'POST')[0]==403,
              'forwarded identity never bypasses the operator token')
        lab.java('com.example.store.StoreAdmin','audit')
        success=True
    finally:
        if lab is not None:
            lab.close()
            if hasattr(lab,'env') and hasattr(lab,'schema'):
                # Separate private env for coherent offline backup; no production credentials copied.
                (lab.out/'private-backup.env').write_text(''.join(k+'='+lab.env[k]+'\n' for k in
                    ('SHOP_DB_URL','SHOP_DB_USER','SHOP_DB_PASSWORD','SHOP_FILES_DIR','SHOP_DB_MAX_CONNECTIONS')),encoding='utf-8')
                (lab.out/'result.json').write_text(json.dumps({'passed':success,'checks':checks,'evidence':evidence,'schema':lab.schema},indent=2),encoding='utf-8')
    print('Operational acceptance PASS:',len(checks))


if __name__=='__main__':main()
