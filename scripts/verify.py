"""Store/proxy acceptance in a new private PostgreSQL schema, separate from the local store."""
import argparse, concurrent.futures as futures, hashlib, json, os, secrets, sys, time
from pathlib import Path
from qa_paths import new_evidence_path
from lab import Lab, BASE, PORTS, fetch, get, ROOT
import store

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--out',type=Path,default=None)
    parser.add_argument('--env-file',type=Path,required=True);args=parser.parse_args()
    args.out=args.out or new_evidence_path('store')
    os.chdir(ROOT);store.configure(args.env_file);lab=None;checks=[]
    def check(value,label):
        checks.append({'check':label,'passed':bool(value)})
        if not value:raise AssertionError(label)
    try:
        lab=Lab.__new__(Lab);lab.__init__(args.out.resolve());lab.proxy('ROUND_ROBIN','acceptance')
        catalog=json.loads((ROOT/'src/main/resources/web/store/images/real/catalog.json').read_text(encoding='utf-8'))['items']
        actual=get(BASE+'/api/store/products?limit=24')
        check(actual['total']==12,'12 real phone models in catalog')
        products=[]
        for index,expected in enumerate(catalog,1):
            product=get(BASE+'/api/store/products/'+str(index));products.append(product)
            check(product['name']==expected['name'],'verified model name '+str(index))
            images=[f for f in product['files'] if f['kind']=='IMAGE']
            check([f['sha256'] for f in images]==[f['sha256'] for f in expected['images']],'photographs match source manifest '+str(index))
            for image in images:
                for port in PORTS:
                    code,data,_=fetch(f'http://127.0.0.1:{port}'+image['url'])
                    check(code==200 and hashlib.sha256(data).hexdigest()==image['sha256'],'shared image integrity '+str(index)+' backend '+str(port))
        headers={'Content-Type':'application/json','Origin':BASE,'X-CSRF-Token':lab.session['csrf'],'Cookie':lab.cookie}
        body={'recipient':'QA buyer','phone':'0900000000','address':'Private acceptance test address','note':'V6 isolated test',
              'payment_method':'COD','guest_token':secrets.token_hex(32),'items':[{'variant_id':1,'quantity':1}]}
        key=secrets.token_hex(32);h={**headers,'Idempotency-Key':key}
        before=lab.sql('SELECT (SELECT count(*) FROM st_orders) orders,(SELECT stock FROM st_variants WHERE id=1) stock')[0]
        def submit(index):return fetch(f'http://127.0.0.1:{PORTS[index%3]}/api/store/orders',body,h,'POST')
        with futures.ThreadPoolExecutor(max_workers=12) as pool:replies=list(pool.map(submit,range(24)))
        ids={json.loads(data).get('id') for code,data,_ in replies}
        check(len(ids)==1 and all(code in (200,201) for code,_,_ in replies),'24 same-key requests across 3 JVMs create one order')
        after=lab.sql('SELECT (SELECT count(*) FROM st_orders) orders,(SELECT stock FROM st_variants WHERE id=1) stock')[0]
        check(after['orders']==before['orders']+1 and after['stock']==before['stock']-1,'SQL order count and stock decrement exactly once')
        order=next(iter(ids));check(fetch(BASE+'/api/store/orders/'+order)[0] in (401,403,404),'order ownership enforced')
        check(fetch(BASE+'/api/store/orders/'+order,headers={'X-Order-Token':body['guest_token']})[0]==200,'guest capability retrieves own order')
        lab.sql('UPDATE st_variants SET stock=1 WHERE id=1')
        def last_unit(i):return fetch(f'http://127.0.0.1:{PORTS[i%3]}/api/store/orders',body,{**h,'Idempotency-Key':secrets.token_hex(32)},'POST')[0]
        with futures.ThreadPoolExecutor(max_workers=12) as pool:codes=list(pool.map(last_unit,range(24)))
        check(codes.count(201)==1 and codes.count(409)==23,'24 concurrent checkouts cannot oversell final unit')
        check(lab.sql('SELECT stock FROM st_variants WHERE id=1')[0]['stock']==0,'SQL stock never negative')
        lab.sql('UPDATE st_variants SET stock=100000 WHERE id=1')
        check(fetch(BASE+'/api/store/admin/dashboard',headers={'Cookie':lab.cookie})[0] in (401,403),'admin server-side authorization')
        _,admin_guest,admin_headers=fetch(BASE+'/api/store/session');admin_guest=json.loads(admin_guest)
        admin_cookie=next(v for k,v in admin_headers.items() if k.lower()=='set-cookie').split(';',1)[0]
        code,data,rh=fetch(BASE+'/api/store/auth/login',{'email':lab.env['STORE_ADMIN_EMAIL'],'password':lab.env['STORE_ADMIN_PASSWORD']},{'Content-Type':'application/json','Origin':BASE,'X-CSRF-Token':admin_guest['csrf'],'Cookie':admin_cookie},'POST')
        identity=json.loads(data);check(code==200 and identity['user']['role']=='ADMIN','admin login')
        cookie=next(v for k,v in rh.items() if k.lower()=='set-cookie').split(';',1)[0]
        ah={'Content-Type':'application/json','Origin':BASE,'X-CSRF-Token':identity['csrf'],'Cookie':cookie}
        for port in PORTS:check(fetch(f'http://127.0.0.1:{port}/api/store/admin/dashboard',headers={'Cookie':cookie})[0]==200,'session continuity on '+str(port))
        version=get(BASE+'/api/store/availability')['version']
        disabled={'sales_enabled':False,'downloads_enabled':False,'uploads_enabled':False,'reason':'Private test pause','version':version}
        code,data,_=fetch(BASE+'/api/store/admin/operations',disabled,ah,'PUT');check(code==200,'shared service controls update')
        check(fetch(BASE+'/api/store/orders',body,{**headers,'Idempotency-Key':secrets.token_hex(32)},'POST')[0]==423,'sales pause rejects NEW order')
        check(fetch(BASE+'/api/store/orders',body,h,'POST')[0]==200,'committed idempotent replay survives sales pause')
        check(fetch(BASE+lab.pdf)[0]==423,'PDF pause enforced')
        check(fetch(BASE+lab.product['files'][0]['url'])[0]==200,'image remains available during PDF pause')
        check(fetch(BASE+'/api/store/admin/products/1/files?kind=PDF',b'%PDF-1.4\n%%EOF',{'Origin':BASE,'X-CSRF-Token':identity['csrf'],'Cookie':cookie,'Content-Type':'application/pdf','X-File-Name':'test.pdf'},'POST')[0]==423,'PDF upload pause enforced')
        enabled={**disabled,'sales_enabled':True,'downloads_enabled':True,'uploads_enabled':True,'version':json.loads(data)['version']}
        check(fetch(BASE+'/api/store/admin/operations',enabled,ah,'PUT')[0]==200,'service controls resume')
        for file in lab.product['files']:
            status,data,_=fetch(BASE+file['url']);check(status==200 and len(data)==file['size_bytes'] and hashlib.sha256(data).hexdigest()==file['sha256'],'file byte integrity '+file['kind'])
        # A real dependency fault on just B2 must not open its global circuit.
        fetch('http://127.0.0.1:19002/__test/store-fault?pdf_failure=true',b'',{'X-Test-Token':lab.fault},'POST')
        for _ in range(36):fetch(BASE+lab.pdf)
        metrics=get(BASE+'/__proxy/metrics')
        check(all(b['circuitState']=='CLOSED' and b['state']=='UP' for b in metrics['backends']),'PDF-only dependency failure does not poison global backend health')
        check(fetch(BASE+'/api/store/products')[0]==200 and lab.request('ORDER')[0]==201,'catalog and orders work while B2 PDF fails')
        fetch('http://127.0.0.1:19002/__test/store-fault?pdf_failure=false',b'',{'X-Test-Token':lab.fault},'POST')
        check(fetch(BASE+'/__test/store-fault',b'',{'X-Test-Token':lab.fault},'POST')[0]==403,'fault controls blocked through proxy')
        # Drain exclusion is atomic; let a slow, already leased PDF finish.
        fetch('http://127.0.0.1:19001/__test/store-fault?scope=PDF&delay_ms=1200',b'',{'X-Test-Token':lab.fault},'POST')
        with futures.ThreadPoolExecutor(max_workers=3) as pool:
            pending=[pool.submit(fetch,BASE+lab.pdf) for _ in range(3)]
            deadline=time.monotonic()+3
            while get(BASE+'/__proxy/metrics')['backends'][0]['inFlight']==0:
                if time.monotonic()>deadline:raise AssertionError('held PDF lease not observed')
                time.sleep(.02)
            check(fetch(BASE+'/__proxy/backend-drain',b'',{'X-Proxy-Action':'set-backend-draining','X-Proxy-Target':'127.0.0.1:19001','X-Proxy-Draining':'true'},'POST')[0]==200,'operator drain marks backend ineligible')
            selected=[fetch(BASE+'/work?cost=1')[2] for _ in range(12)]
            check(all('19001' not in json.dumps(h) for h in selected),'new requests avoid draining backend')
            check(all(f.result()[0]==200 for f in pending),'existing PDF leases finish during drain')
        fetch('http://127.0.0.1:19001/control?draining=true')
        check(get(BASE+'/__proxy/metrics')['backends'][0]['draining'],'draining remains observable')
        fetch('http://127.0.0.1:19001/control?draining=false')
        fetch(BASE+'/__proxy/backend-drain',b'',{'X-Proxy-Action':'set-backend-draining','X-Proxy-Target':'127.0.0.1:19001','X-Proxy-Draining':'false'},'POST')
        fetch('http://127.0.0.1:19001/__test/store-fault?delay_ms=0',b'',{'X-Test-Token':lab.fault},'POST')
        time.sleep(.6)
        # Real browser journey and responsive/print evidence from the private dataset.
        from verify_site import verify_browser
        verify_browser(lab,check,products,ah)
        metrics=get(BASE+'/__proxy/metrics');check(metrics['transport']['activeSockets']<=3,'bounded socket count after requests')
        check(metrics['transport']['connected']>0 and metrics['transport']['bytesRead']>0,'real transport instrumentation recorded')
        lab.java('com.example.store.StoreAdmin','audit')
        (lab.out/'acceptance.json').write_text(json.dumps({'passed':True,'checks':checks,'source':__import__('lab').fingerprint()},indent=2),encoding='utf-8')
    except BaseException:
        if lab and hasattr(lab,'out'):(lab.out/'acceptance.json').write_text(json.dumps({'passed':False,'checks':checks},indent=2))
        raise
    finally:
        if lab and hasattr(lab,'children'):lab.close()
    print('Acceptance passed:',len(checks))
if __name__=='__main__':main()
