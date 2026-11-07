"""Windows/local store lifecycle. Explicit setup; ordinary start never migrates or seeds."""
import argparse
import getpass
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.request
import urllib.error
import time

import shop_demo as demo

ROOT=Path(__file__).resolve().parents[1]

def configure(path):
    if path.exists():
        allowed={'SHOP_DB_URL','SHOP_DB_USER','SHOP_DB_PASSWORD','SHOP_FILES_DIR','SHOP_DB_MAX_CONNECTIONS','SHOP_MAX_PDF_BYTES','STORE_ORIGIN','STORE_ENV','STORE_ADMIN_EMAIL'}
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            line=line.strip()
            if not line or line.startswith('#'):continue
            key,sep,value=line.partition('=')
            if not sep or key not in allowed:raise ValueError('Unsupported configuration key in env file')
            os.environ[key]=value.strip()
    os.environ['STORE_ENABLED']='true'
    os.environ['SHOP_ENABLED']='true'
    mode=os.environ.setdefault('STORE_ENV','development')
    if mode not in {'development','test','demonstration','deployment'}:raise ValueError('STORE_ENV invalid')
    if mode=='deployment' and not os.environ.get('STORE_ORIGIN','').startswith('https://'):raise ValueError('Deployment requires configured HTTPS STORE_ORIGIN')

def admin(command):
    subprocess.run([demo.java(),'-cp',demo.CP,'com.example.store.StoreAdmin',command],cwd=ROOT,check=True)

def readiness():
    admin('check')
    with urllib.request.urlopen('http://127.0.0.1:8080/api/store/products',timeout=8) as r:data=json.load(r)
    if not isinstance(data.get('items'),list):raise RuntimeError('Catalog readiness failed')
    if data['items']:
        image=data['items'][0].get('image_url')
        if not image:raise RuntimeError('Published product lacks an image')
        demo.wait_ready('http://127.0.0.1:8080'+image)
    demo.wait_ready('http://127.0.0.1:8080/shop')
    for port in (9001,9002,9003):
        if backend_readiness(port).get('status')!='READY':raise RuntimeError('Backend not ready: '+str(port))
    print('Store ready: http://127.0.0.1:8080/shop | Admin: /admin | Proxy: /__proxy/dashboard')

def backend_readiness(port):
    try:response=urllib.request.urlopen('http://127.0.0.1:'+str(port)+'/ready',timeout=8)
    except urllib.error.HTTPError as failure:
        if failure.code!=503:raise
        response=failure
    with response:return json.load(response)

def drain_backend(port,enabled,timeout):
    record=demo.state().get('backend-'+str(port))
    if not record or demo.creation(record['pid'])!=record['birth']:raise RuntimeError('No matching owned backend on '+str(port))
    deadline=time.monotonic()+timeout
    proxy=demo.state().get('proxy')
    proxy_live=proxy and demo.creation(proxy['pid'])==proxy['birth']
    def proxy_state():
        with urllib.request.urlopen('http://127.0.0.1:8080/__proxy/metrics',timeout=3) as response:
            return next(b for b in json.load(response)['backends'] if b['id']=='127.0.0.1:'+str(port))
    def proxy_drain(flag):
        req=urllib.request.Request('http://127.0.0.1:8080/__proxy/backend-drain',data=b'',method='POST',headers={
            'X-Proxy-Action':'set-backend-draining','X-Proxy-Target':'127.0.0.1:'+str(port),'X-Proxy-Draining':str(flag).lower()})
        with urllib.request.urlopen(req,timeout=3) as response:response.read()
    if enabled and proxy_live:
        # Exclude atomically at dispatch BEFORE closing backend admission. Leased requests may still arrive.
        proxy_drain(True)
        while proxy_state()['inFlight']:
            if time.monotonic()>=deadline:raise RuntimeError('Proxy drain timed out; admission stays open for existing leases, routing remains drained.')
            time.sleep(.05)
    url='http://127.0.0.1:'+str(port)+'/control?draining='+str(enabled).lower()
    with urllib.request.urlopen(url,timeout=8) as response:response.read()
    if not enabled and proxy_live:proxy_drain(False)
    while True:
        state=backend_readiness(port)
        proxy_live=proxy and demo.creation(proxy['pid'])==proxy['birth']
        excluded=not proxy_live
        if enabled and proxy_live:
            state_at_proxy=proxy_state()
            excluded=state_at_proxy['draining'] and state_at_proxy['inFlight']==0
        if not enabled or state['admitted']==0 and excluded:break
        if time.monotonic()>=deadline:raise RuntimeError('Drain timed out; backend remains draining. Inspect /ready before stopping.')
        time.sleep(.1)
    print(json.dumps({'backend':port,'proxy_excluded':bool(enabled and excluded),**state}))

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--env-file',type=Path,default=ROOT/'.env.store')
    p.add_argument('command',choices=['setup','migrate','seed','catalog','admin','start','stop','status','check','audit','dependencies','drain','resume'])
    p.add_argument('--backend',type=int,choices=(9001,9002,9003),default=9001)
    p.add_argument('--drain-timeout',type=int,choices=range(1,31),default=15,metavar='1..30')
    p.add_argument('--seed',action='store_true');p.add_argument('--admin',action='store_true')
    p.add_argument('--strategy',choices=demo.STRATEGIES,default='ADAPTIVE');args=p.parse_args();os.chdir(ROOT)
    configure(args.env_file)
    if args.command=='setup':
        admin('migrate')
        if args.seed:admin('seed')
        if args.admin:provision()
    elif args.command=='admin':provision()
    elif args.command=='catalog':
        if any(demo.creation(r['pid'])==r['birth'] for r in demo.state().values()):raise RuntimeError('Stop the application writers before converting the catalog; keep PostgreSQL running.')
        admin('catalog')
    elif args.command in {'migrate','seed','audit'}:admin(args.command)
    elif args.command=='start':
        admin('check');data=demo.state()
        live={name for name,r in data.items() if demo.creation(r['pid'])==r['birth']}
        if live=={'proxy','backend-9001','backend-9002','backend-9003'}:
            readiness();print('Existing owned store is already running.');return
        if live:raise RuntimeError('A partial owned session is running; inspect status and stop before restart.')
        try:demo.start(data,args.strategy);readiness()
        except BaseException:demo.stop(data);raise
    elif args.command=='stop':demo.stop(demo.state())
    elif args.command=='check':readiness()
    elif args.command=='dependencies':print(json.dumps({str(port):backend_readiness(port) for port in (9001,9002,9003)},indent=2))
    elif args.command in {'drain','resume'}:drain_backend(args.backend,args.command=='drain',args.drain_timeout)
    else:
        data=demo.state();print(json.dumps({name:('running' if demo.creation(r['pid'])==r['birth'] else 'stopped') for name,r in data.items()},indent=2))

def provision():
    if not os.environ.get('STORE_ADMIN_EMAIL'):os.environ['STORE_ADMIN_EMAIL']=input('Admin email: ').strip()
    if not os.environ.get('STORE_ADMIN_PASSWORD'):os.environ['STORE_ADMIN_PASSWORD']=getpass.getpass('Admin password (12..128 characters): ')
    try:admin('admin')
    finally:os.environ.pop('STORE_ADMIN_PASSWORD',None)

if __name__=='__main__':main()
