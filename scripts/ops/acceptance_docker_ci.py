"""Real Compose acceptance on a fresh GitHub Actions runner; no existing .env or stack.

Creates a unique project and private credentials, never deletes volumes or databases.
Logs and a JSON receipt are uploaded by CI; credentials stay outside the artifact tree.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
COMPOSE=ROOT/'deploy/compose/compose.yaml'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args();out=args.out.resolve();out.mkdir(parents=True,exist_ok=False)
    if os.environ.get('GITHUB_ACTIONS')!='true':
        parser.error('This creates an isolated stack only on a fresh GitHub Actions runner')
    env_file=COMPOSE.parent/'.env'
    if env_file.exists():raise RuntimeError('Refuse an existing Compose .env or business stack')
    project='pbl4-acceptance-'+os.environ['GITHUB_RUN_ID']+'-'+os.environ['GITHUB_RUN_ATTEMPT']
    env=os.environ.copy();env['COMPOSE_PROJECT_NAME']=project
    values={'PBL4_DB_PASSWORD':secrets.token_hex(24),'PBL4_PROBE_TOKEN':secrets.token_hex(32),
            'PBL4_CONTROL_TOKEN':secrets.token_hex(32)}
    env.update(values)
    env_file.write_text(''.join(k+'='+v+'\n' for k,v in values.items()),encoding='utf-8');env_file.chmod(0o600)
    compose=['docker','compose','-p',project,'--env-file',str(env_file),'-f',str(COMPOSE)]
    checks=[];success=False
    def check(condition,label):
        checks.append({'check':label,'passed':bool(condition)})
        print(('PASS ' if condition else 'FAIL ')+label,flush=True)
        if not condition:raise AssertionError(label)
    def run(label,command,expected=0,timeout=240):
        result=subprocess.run(command,cwd=ROOT,env=env,capture_output=True,timeout=timeout)
        (out/(label+'.log')).write_bytes(result.stdout+result.stderr)
        if result.returncode!=expected:print((result.stdout+result.stderr).decode(errors='replace')[-10000:])
        check(result.returncode==expected,label+' exit='+str(result.returncode))
        return result.stdout.decode('utf-8',errors='replace')
    def http(path,body=None,headers=None,method=None):
        if isinstance(body,dict):body=json.dumps(body).encode()
        request=urllib.request.Request('http://127.0.0.1:8080'+path,body,headers or {},method=method)
        try:response=urllib.request.urlopen(request,timeout=8)
        except urllib.error.HTTPError as error:response=error
        with response:return response.status,response.read(),dict(response.headers)
    def wait_ready():
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            try:
                if http('/api/store/products')[0]==200:return
            except (OSError,TimeoutError):pass
            time.sleep(.5)
        raise AssertionError('Docker store readiness timeout')
    def operator(action,*args,expected=0):
        text=run('operator-'+action,[sys.executable,str(ROOT/'scripts/ops/docker_operator.py'),action,*args,
                 '--compose-file',str(COMPOSE)],expected=expected)
        marker,_,body=text.partition('\n')
        return json.loads(body) if marker.strip()=='HTTP 200' else None
    def sql_snapshot(label):
        query="SELECT json_build_object('orders',(SELECT count(*) FROM st_orders),'stock',(SELECT stock FROM st_variants WHERE id="+str(int(variant['id']))+"))"
        text=run(label,compose+['exec','-T','postgres','psql','-U','pbl4','-d','pbl4','-tAc',query])
        return json.loads(text.strip())
    def wait_backends():
        deadline=time.monotonic()+90
        while time.monotonic()<deadline:
            metrics=operator('metrics')
            if metrics and len(metrics['backends'])==3 and all(b['state']=='UP' and
                    b['capabilities'].get('order_create')=='AVAILABLE' for b in metrics['backends']):return metrics
            time.sleep(.5)
        raise AssertionError('All three Docker backends must recover with fresh capabilities')
    try:
        run('docker-version',['docker','version'])
        run('compose-version',['docker','compose','version'])
        run('compose-config',compose+['config','--quiet'])
        run('docker-build',compose+['build'],timeout=1200)
        run('compose-up',compose+['up','-d'],timeout=240)
        wait_ready();check(True,'real Docker store starts with PostgreSQL and shared storage')
        initial=wait_backends()
        check(isinstance(initial,dict),'operator metrics works through docker exec into proxy loopback')
        check(http('/__proxy/metrics')[0]==403,'host bridge cannot access private management HTTP endpoint')
        for name,value in [('missing',''),('wrong','wrong')]:
            text=run('operator-token-'+name,compose+['exec','-T','-e','PBL4_CONTROL_TOKEN='+value,'proxy',
                'java','com.example.proxy.server.OperatorCli','strategy'],expected=1)
            check(text.startswith('HTTP 403'),'container operator rejects '+name+' token')
        operator('strategy');operator('drain','backend1:9001')
        metrics=operator('metrics')
        check(next(b for b in metrics['backends'] if b['id']=='backend1:9001')['draining'],'Docker operator drain changes actual proxy state')
        check(all('backend1:9001' not in json.dumps(http('/api/store/products')[2]) for _ in range(12)),
              'Docker traffic avoids drained backend')
        operator('resume','backend1:9001')
        status,data,_=http('/api/store/products/1');product=json.loads(data)
        variant=next(v for v in product['variants'] if v['active'] and v['stock']>0)
        before=sql_snapshot('sql-before-order')
        status,data,h=http('/api/store/session');identity=json.loads(data)
        check(status==200,'Docker creates a real shared session')
        cookie=next(v for k,v in h.items() if k.lower()=='set-cookie').split(';',1)[0]
        guest=secrets.token_hex(32);key=secrets.token_hex(32)
        body={'recipient':'Docker acceptance','phone':'0900000000','address':'Isolated GitHub runner test address',
              'note':'Isolated Docker regression','payment_method':'COD','guest_token':guest,
              'items':[{'variant_id':variant['id'],'quantity':1}]}
        headers={'Content-Type':'application/json','Origin':'http://127.0.0.1:8080','X-CSRF-Token':identity['csrf'],
                 'Cookie':cookie,'Idempotency-Key':key}
        status,data,_=http('/api/store/orders',body,headers,'POST');order=json.loads(data)
        check(status==201,'Docker checkout commits a real PostgreSQL order')
        after=sql_snapshot('sql-after-order')
        check(after=={'orders':before['orders']+1,'stock':before['stock']-1},'Docker SQL confirms one order and one stock decrement')
        status,data,_=http('/api/store/products/1');product=json.loads(data)
        fingerprints={}
        for file in product['files']:
            status,data,_=http(file['url'])
            check(status==200 and hashlib.sha256(data).hexdigest()==file['sha256'],'Docker serves verified '+file['kind'])
            fingerprints[file['url']]=file['sha256']
        run('compose-restart',compose+['restart','postgres','backend1','backend2','backend3','proxy'],timeout=180)
        wait_ready();wait_backends()
        status,data,_=http('/api/store/orders',body,headers,'POST')
        check(status==200 and json.loads(data)['id']==order['id'],'same-key replay after Docker restart preserves the original order')
        check(sql_snapshot('sql-after-restart')==after,'order and stock persist across PostgreSQL/container restart')
        for path,digest in fingerprints.items():
            status,data,_=http(path)
            check(status==200 and hashlib.sha256(data).hexdigest()==digest,'shared file survives Docker volume restart')
        run('docker-health-snapshot',[sys.executable,str(ROOT/'scripts/ops/health_snapshot.py'),'--compose-file',str(COMPOSE),
                                    '--out',str(out/'health')])
        run('docker-watchdog',[sys.executable,str(ROOT/'scripts/ops/proxy_watchdog.py'),'--compose-file',str(COMPOSE),
                             '--samples','3','--interval','.1','--out',str(out/'watchdog')])
        success=True
    finally:
        for name,command in [('compose-ps',compose+['ps','--all']),('compose-runtime',compose+['logs','--no-color','--tail','500']),
                             ('compose-stop',compose+['stop'])]:
            try:
                result=subprocess.run(command,cwd=ROOT,env=env,capture_output=True,timeout=90)
                (out/(name+'.log')).write_bytes(result.stdout+result.stderr)
            except (OSError,subprocess.TimeoutExpired) as error:
                (out/(name+'.log')).write_text(type(error).__name__,encoding='utf-8')
        receipt={'passed':success,'project':project,'checks':checks,
            'scope':'fresh Linux GitHub runner; actual Docker Engine, PostgreSQL, files and operator exec',
            'volumes_deleted':False}
        (out/'result.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
        print('DOCKER_ACCEPTANCE_RECEIPT='+json.dumps(receipt),flush=True)
    print('Docker runtime acceptance PASS:',len(checks))


if __name__=='__main__':main()
