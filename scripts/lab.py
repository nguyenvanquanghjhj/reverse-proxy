"""Isolated loopback research. Writes raw evidence outside the checkout; never uses store tables in public schema."""
import argparse, concurrent.futures as futures, csv, hashlib, json, math, os
from pathlib import Path
import platform, random, secrets, socket, subprocess, sys, threading, time, urllib.request, urllib.error,io
from datetime import datetime, timezone
import shop_demo as runtime
import store
import xml.etree.ElementTree as ET
try:import psutil
except ImportError:psutil=None

ROOT=Path(__file__).resolve().parents[1]
BASE='http://127.0.0.1:18080'
PORTS=(19001,19002,19003)
STRATEGIES=runtime.STRATEGIES

def fetch(url,body=None,headers=None,method=None,timeout=8):
    if isinstance(body,dict):body=json.dumps(body).encode()
    req=urllib.request.Request(url,body,headers or {},method=method)
    try:r=urllib.request.urlopen(req,timeout=timeout)
    except urllib.error.HTTPError as error:r=error
    with r:return r.status,r.read(),dict(r.headers)

def get(url):
    status,data,_=fetch(url)
    if status!=200:raise RuntimeError(f'{url}: HTTP {status}')
    return json.loads(data)

def fingerprint():
    return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted((ROOT/'src/main').rglob('*')) if p.is_file()}

def tcp_snapshot():
    if psutil is None:return {'status':'NOT RUN: optional psutil unavailable'}
    try:
        rows=[]
        for connection in psutil.net_connections(kind='tcp'):
            local=connection.laddr;remote=connection.raddr
            if local and local.ip=='127.0.0.1' and (local.port in (18080,*PORTS) or remote and remote.ip=='127.0.0.1' and remote.port in (18080,*PORTS)):
                rows.append({'local_port':local.port,'remote_port':remote.port if remote else None,'state':connection.status,'pid':connection.pid})
        return {'states':{state:sum(r['state']==state for r in rows) for state in sorted({r['state'] for r in rows})},'connections':[r for r in rows if r['state']!='TIME_WAIT'],
                'time_wait_endpoint_rows_omitted':sum(r['state']=='TIME_WAIT' for r in rows),
                'scope':'OS snapshot for owned lab ports; includes health/telemetry and data, not only exchange sockets'}
    except psutil.Error as error:return {'status':'NOT RUN','reason':type(error).__name__}

class Lab:
    def __init__(self,out):
        self.out=out;out.mkdir(parents=True,exist_ok=False)
        self.children={};self.env=os.environ.copy();self.schema='v6_'+secrets.token_hex(6)
        self.fault=secrets.token_hex(32);self.logs=[];self.sequence=0
        for port in (18080,*PORTS):runtime.free_port(port)
        self.env.update(STORE_ENV='test',STORE_ORIGIN=BASE,STORE_ENABLED='true',SHOP_ENABLED='true',
                        STORE_FAULTS_ENABLED='true',STORE_TEST_FAULT_TOKEN=self.fault,
                        SHOP_FILES_DIR=str(out/'files'))
        self.sql_source=out/'LabSql.java'
        self.sql_source.write_text('''import java.nio.file.*;import java.sql.*;import java.util.*;import com.example.shop.JsonCodec;
class LabSql {public static void main(String[] a)throws Exception{try(var c=DriverManager.getConnection(System.getenv("SHOP_DB_URL"),System.getenv("SHOP_DB_USER"),System.getenv("SHOP_DB_PASSWORD"));var s=c.createStatement()){
boolean rows=s.execute(Files.readString(Path.of(a[0])));if(rows){try(var r=s.getResultSet()){var list=new ArrayList<Map<String,Object>>();while(r.next()){var m=new LinkedHashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)m.put(r.getMetaData().getColumnLabel(i),r.getObject(i));list.add(m);}System.out.println(JsonCodec.write(list));}}}}}''',encoding='utf-8')
        self.sql('CREATE SCHEMA '+self.schema)
        self.env['SHOP_DB_URL']+=('&' if '?' in self.env['SHOP_DB_URL'] else '?')+'currentSchema='+self.schema
        self.java('com.example.store.StoreAdmin','migrate');self.java('com.example.store.StoreAdmin','seed')
        self.sql('UPDATE st_variants SET stock=100000 WHERE stock>0')
        self.env['STORE_ADMIN_EMAIL']='lab@example.test';self.env['STORE_ADMIN_PASSWORD']=secrets.token_urlsafe(24)
        self.java('com.example.store.StoreAdmin','admin')
        for port in PORTS:self.backend(port)
        self.product=get('http://127.0.0.1:19001/api/store/products/1')
        self.pdf=next(f['url'] for f in self.product['files'] if f['kind']=='PDF')
        # Preserve the generated PDF's xref offsets and final startxref/EOF sequence.
        # A comment BEFORE startxref grows it to exactly 240 KiB without changing page objects.
        status,original,_=fetch('http://127.0.0.1:19001'+self.pdf)
        marker=original.rfind(b'startxref\n');offset=int(original[marker:].splitlines()[1])
        if status!=200 or marker<0 or original[offset:offset+4]!=b'xref':raise RuntimeError('Invalid source PDF fixture')
        padding=245760-len(original)
        if padding<2:raise RuntimeError('Source PDF exceeds fixture budget')
        document=original[:marker]+b'%'+b'x'*(padding-2)+b'\n'+original[marker:]
        assert len(document)==245760
        parser='structural xref/EOF validation'
        try:
            import pypdf
            assert len(pypdf.PdfReader(io.BytesIO(document),strict=True).pages)==1
            parser='pypdf '+pypdf.__version__+': PASS'
        except ImportError:pass
        fixture=out/'fixture.pdf';fixture.write_bytes(document)
        (out/'pdf-fixture.json').write_text(json.dumps({'size_bytes':len(document),'sha256':hashlib.sha256(document).hexdigest(),'validation':parser,'purpose':'Own generated specification PDF, padded with a comment before startxref; no manufacturer document copied.'}),encoding='utf-8')
        source=out/'LargePdf.java'
        source.write_text('''import java.nio.file.*;import com.example.shop.*;import com.example.store.service.*;import com.example.store.storage.*;
class LargePdf{public static void main(String[] a)throws Exception{var c=ShopConfig.environment();System.out.println(JsonCodec.write(new FileService(new Database(c),new FileStorage(c)).add(1,"PDF","lab-fixture.pdf",Files.readAllBytes(Path.of(a[0])),"application/pdf")));}}''',encoding='utf-8')
        result=subprocess.run([runtime.java(),'-cp',runtime.CP,str(source),str(fixture)],cwd=ROOT,env=self.env,capture_output=True,text=True,check=True)
        self.pdf=json.loads(result.stdout)['url']
        meta={'schema':self.schema,'started_utc':datetime.now(timezone.utc).isoformat(),'topology':'one Windows host, IPv4 loopback; no LAN/loss/jitter emulation',
              'platform':platform.platform(),'cpu_count':os.cpu_count(),'machine':platform.machine(),'psutil_version':getattr(psutil,'__version__',None),
              'java':subprocess.check_output([runtime.java(),'--version']).decode(),'jvm':['-Xms64m','-Xmx192m'],
              'source':fingerprint(),'runner_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'git_head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).decode().strip(),
              'runtime_sha256':{str(p.relative_to(ROOT/'target/standalone/classes')):hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'target/standalone/classes').rglob('*') if p.is_file()},
              'transport':'HTTP/1.1 buffered, one upstream TCP socket/exchange; health probes separate',
              'timeout_seconds':8,'backend_workers':4,'background':'Existing local review demo remains running but receives no scheduled benchmark load. Windows/IDE background not controlled.'}
        (out/'environment.json').write_text(json.dumps(meta,indent=2),encoding='utf-8')
    def java(self,*args):
        result=subprocess.run([runtime.java(),'-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-cp',runtime.CP,*args],cwd=ROOT,env=self.env,capture_output=True)
        (self.out/('command-'+args[0].rsplit('.',1)[-1]+'-'+args[1]+'.log')).write_bytes(result.stdout+result.stderr)
        result.check_returncode();return result
    def sql(self,text):
        path=self.out/'query.sql';path.write_text(text,encoding='utf-8')
        result=subprocess.run([runtime.java(),'-cp',runtime.CP,str(self.sql_source),str(path)],cwd=ROOT,env=self.env,capture_output=True,text=True,check=True)
        return json.loads(result.stdout) if result.stdout.strip() else None
    def launch(self,name,args,jfr=None):
        log=(self.out/(name+'.log')).open('ab');self.logs.append(log)
        flags=['-Xms64m','-Xmx192m']
        if jfr:
            (self.out/'jfr-repository').mkdir(exist_ok=True);(self.out/'tmp').mkdir(exist_ok=True)
            # JDK profile defaults record environment variables, including database credentials.
            recording=ET.parse(Path(runtime.java()).resolve().parents[1]/'lib/jfr/profile.jfc')
            for event_name in ('jdk.InitialEnvironmentVariable','jdk.InitialSystemProperty'):
                setting=recording.getroot().find(f"event[@name='{event_name}']/setting[@name='enabled']")
                if setting is None:raise RuntimeError('Cannot disable sensitive JFR startup event '+event_name)
                setting.text='false'
            settings=self.out/'safe-profile.jfc';recording.write(settings,encoding='utf-8',xml_declaration=True)
            flags+=['-Djava.io.tmpdir='+str(self.out/'tmp'),'-XX:FlightRecorderOptions=repository='+str(self.out/'jfr-repository'),
                    '-XX:StartFlightRecording=settings='+str(settings)+',maxsize=32m,filename='+str(jfr)+',dumponexit=true']
        proc=subprocess.Popen([runtime.java(),*flags,'-cp',runtime.CP,*args],cwd=ROOT,env=self.env,
                              stdin=subprocess.DEVNULL,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        self.children[name]=proc;return proc
    def stop(self,name):
        proc=self.children.pop(name,None)
        if proc and proc.poll() is None:proc.terminate();proc.wait(timeout=15)
    def backend(self,port,delay=0):
        self.launch(str(port),['com.example.backend.DemoBackendServer',str(port),'4',str(delay),'0'])
        runtime.wait_ready(f'http://127.0.0.1:{port}/health')
    def profile(self,delays):
        for port,delay in zip(PORTS,delays):
            fetch(f'http://127.0.0.1:{port}/control?delayMs={delay}')
            fetch(f'http://127.0.0.1:{port}/__test/store-fault?delay_ms=0',b'',{'X-Test-Token':self.fault},'POST')
    def proxy(self,strategy,label,overload=False,jfr=False,extra=None):
        self.stop('proxy');self.sequence+=1
        config=(ROOT/'config/application.properties').read_text(encoding='utf-8')
        config+='\n'+ '\n'.join(['proxy.port=18080','backend.servers='+','.join(f'127.0.0.1:{p}:4' for p in PORTS),
            'loadbalancer.strategy='+strategy,'adaptive.decision-trace.enabled=false','adaptive.warmup.ms=250',
            'healthcheck.interval.ms=100','healthcheck.recovery.successes=2','proxy.circuit-breaker.open-duration.ms=800',
            'proxy.max.inflight='+('8' if overload else '128'),'proxy.max.handler.tasks=160'])+'\n'
        if extra:
            config+=''.join(str(key)+'='+str(value)+'\n' for key,value in extra.items())
        path=self.out/(label+'.properties');path.write_text(config,encoding='utf-8')
        self.launch('proxy',['com.example.proxy.Main',str(path)],self.out/'transport.jfr' if jfr else None)
        runtime.wait_ready(BASE+'/__proxy/health')
        deadline=time.monotonic()+10
        while not all(b['state']=='UP' for b in get(BASE+'/__proxy/metrics')['backends']):
            if time.monotonic()>deadline:raise RuntimeError('warm-up readiness timeout')
            time.sleep(.05)
        status,data,h=fetch(BASE+'/api/store/session');self.session=json.loads(data)
        self.cookie=next(v for k,v in h.items() if k.lower()=='set-cookie').split(';',1)[0]
    def request(self,path,index=0):
        if path=='ORDER':
            return fetch(BASE+'/api/store/orders',{'recipient':'Lab buyer','phone':'0900000000','address':'Isolated loopback laboratory address',
                'note':'Private benchmark schema','payment_method':'COD','guest_token':secrets.token_hex(32),
                'items':[{'variant_id':1,'quantity':1}]},{'Content-Type':'application/json','Origin':BASE,
                'X-CSRF-Token':self.session['csrf'],'Cookie':self.cookie,'Idempotency-Key':secrets.token_hex(32)},'POST')
        return fetch(BASE+path)
    def batch(self,label,paths,n,concurrency,seed,background=0,offered_rps=None):
        rng=random.Random(seed);work=[rng.choice(paths) for _ in range(n)];rows=[];lock=threading.Lock();stop=threading.Event()
        def backend_raw():
            raw=[]
            for port in PORTS:
                _,body,_=fetch(f'http://127.0.0.1:{port}/metrics')
                raw.append(dict(line.split('=',1) for line in body.decode().splitlines() if '=' in line))
            return raw
        def processes():
            data={}
            for name,child in self.children.items():
                if psutil:
                    try:
                        process=psutil.Process(child.pid);cpu=process.cpu_times();memory=process.memory_info()
                        data[name]={'pid':child.pid,'cpu_seconds':cpu.user+cpu.system,'working_set_bytes':memory.rss,
                                    'private_bytes':getattr(memory,'private',None),'os_thread_count':process.num_threads()}
                    except psutil.Error as error:data[name]={'error':type(error).__name__}
                else:data[name]={'status':'NOT RUN: optional psutil unavailable'}
            return data
        before=get(BASE+'/__proxy/metrics');raw_before=backend_raw();process_before=processes();start=time.perf_counter()
        def one(i,path,kind,planned=None):
            began=time.perf_counter();code=0;size=0;backend='';error='';timeout=False
            try:code,body,h=self.request(path,i);size=len(body);backend=h.get('X-proxy-backend',h.get('X-Proxy-Backend',''))
            except Exception as e:
                error=type(e).__name__;timeout=isinstance(e,TimeoutError) or isinstance(e,urllib.error.URLError) and isinstance(e.reason,TimeoutError)
            timeout=timeout or code==504
            row={'index':i,'kind':kind,'path':path,'started_s':began-start,'schedule_lag_ms':max(0,(began-start-planned)*1000) if planned is not None else 0,'latency_ms':(time.perf_counter()-began)*1000,
                 'status':code,'bytes':size,'backend':backend,'error':error,'timeout':timeout}
            with lock:rows.append(row)
        def pressure(worker):
            i=worker
            while not stop.is_set():one(i,self.pdf,'background-pdf');i+=background
        threads=[threading.Thread(target=pressure,args=(i,)) for i in range(background)]
        for t in threads:t.start()
        samples=[];observation_errors=[]
        def observe():
            while not stop.wait(.1):
                try:
                    samples.append({'at':time.perf_counter()-start,'metrics':get(BASE+'/__proxy/metrics'),'backend_raw':backend_raw(),'processes':processes()})
                except Exception as error:observation_errors.append({'at':time.perf_counter()-start,'error':type(error).__name__})
        observer=threading.Thread(target=observe);observer.start()
        try:
            with futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
                if offered_rps:
                    pending=[]
                    for i,path in enumerate(work):
                        planned=i/offered_rps;remaining=start+planned-time.perf_counter()
                        if remaining>0:time.sleep(remaining)
                        pending.append(pool.submit(one,i,path,'measured',planned))
                    for task in pending:task.result()
                else:list(pool.map(lambda pair:one(*pair,'measured'),enumerate(work)))
            elapsed=time.perf_counter()-start
        finally:
            stop.set()
            for t in threads:t.join(timeout=12)
            observer.join(timeout=12)
        after=get(BASE+'/__proxy/metrics')
        with (self.out/(label+'.csv')).open('w',newline='') as f:
            writer=csv.DictWriter(f,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(sorted(rows,key=lambda r:(r['kind'],r['index'])))
        (self.out/(label+'-snapshots.json')).write_text(json.dumps({'before':before,'after':after,'backend_raw_before':raw_before,'backend_raw_after':backend_raw(),
            'processes_before':process_before,'processes_after':processes(),'samples':samples,'observation_errors':observation_errors}),encoding='utf-8')
        measured_starts=[row['started_s'] for row in rows if row['kind']=='measured']
        result={'label':label,'n':n,'concurrency':concurrency,'seed':seed,'load_model':'paced open-loop, bounded finite request set' if offered_rps else 'closed-loop fixed concurrency',
                'elapsed_s':elapsed,'attempted_rps':n/elapsed,'configured_offered_rps':offered_rps,'background_workers':background,
                'observed_start_rps':(n-1)/(max(measured_starts)-min(measured_starts)) if n>1 else None,
                'max_schedule_lag_ms':max(r['schedule_lag_ms'] for r in rows)}
        for kind in ('measured','background-pdf'):
            selected=[r for r in rows if r['kind']==kind]
            if not selected:continue
            lat=sorted(r['latency_ms'] for r in selected);ok=[r for r in selected if 200<=r['status']<400];success_lat=sorted(r['latency_ms'] for r in ok)
            window_ok=[r for r in ok if r['started_s']+r['latency_ms']/1000<=elapsed]
            result[kind]={'count':len(selected),'success':len(ok),'successful_rps':len(window_ok)/elapsed,
                          'window_success':len(window_ok),'success_drained_after_window':len(ok)-len(window_ok),
                          'http_errors':sum(r['status']>=400 for r in selected),'client_errors':sum(r['status']==0 for r in selected),
                          'timeouts':sum(r['timeout'] for r in selected),
                          'proxy_rejected_503':sum(r['status']==503 and not r['backend'] for r in selected),
                          'backend_http503':sum(r['status']==503 and bool(r['backend']) for r in selected),
                          'p50_ms':lat[math.ceil(len(lat)*.5)-1],'p95_ms':lat[math.ceil(len(lat)*.95)-1] if len(lat)>=20 else None,
                          'p99_ms':lat[math.ceil(len(lat)*.99)-1] if len(lat)>=100 else None,
                          'successful_p95_ms':success_lat[math.ceil(len(success_lat)*.95)-1] if len(success_lat)>=20 else None,
                          'successful_p99_ms':success_lat[math.ceil(len(success_lat)*.99)-1] if len(success_lat)>=100 else None,
                          'distribution':{b:sum(r['backend']==b for r in selected) for b in sorted({r['backend'] for r in selected})}}
        return result
    def close(self):
        for name in list(self.children):self.stop(name)
        for log in self.logs:log.close()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,required=True);p.add_argument('--repeats',type=int,default=3);p.add_argument('--requests',type=int,default=240)
    p.add_argument('--only',help='Comma-separated comparison conditions');p.add_argument('--comparisons-only',action='store_true')
    args=p.parse_args();os.chdir(ROOT);store.configure(ROOT/'.env.store');lab=None;results=[]
    try:
        lab=Lab.__new__(Lab);lab.__init__(args.out.resolve())
        (lab.out/'tcp-before.json').write_text(json.dumps(tcp_snapshot()),encoding='utf-8')
        profiles=[('homogeneous',(10,10,10),['/work?cost=1'],12,0,None),
                  ('heterogeneous',(0,45,100),['/work?cost=2'],12,0,None),
                  ('slow-backend',(0,0,200),['/work?cost=1'],12,0,None),
                  ('mixed',(0,30,70),['/api/store/products','ORDER',lab.pdf,'/compute?cost=4'],12,0,None),
                  *[(f'overload-rps{rate}',(80,80,80),['/work?cost=1'],96,0,rate) for rate in (30,100,300)],
                  ('connections',(0,0,0),['/work?cost=1'],12,0,None),
                  *[(f'interference-pdf{level}',(0,0,0),['ORDER'],12,level,None) for level in (0,4,8,16)]]
        if args.only:
            wanted=set(args.only.split(','));known={profile[0] for profile in profiles}
            if not wanted<=known:raise ValueError('Unknown conditions: '+str(wanted-known))
            profiles=[profile for profile in profiles if profile[0] in wanted]
        for repeat in range(args.repeats):
            # Fresh backend JVMs each repeat; fresh proxy for every strategy/condition.
            if repeat:
                lab.stop('proxy')
                for port in PORTS:lab.stop(str(port))
                for port in PORTS:lab.backend(port)
            order=STRATEGIES[repeat%3:]+STRATEGIES[:repeat%3]
            for experiment,delays,paths,concurrency,background,rate in profiles:
                lab.profile(delays)
                for strategy in order:
                    label=f'{experiment}-{repeat+1}-{strategy}';lab.proxy(strategy,label,experiment.startswith('overload'))
                    lab.batch(label+'-warmup',paths,30,6,100+repeat)
                    result=lab.batch(label,paths,args.requests,concurrency,20261107+repeat,background=background,offered_rps=rate)
                    result.update(experiment=experiment,repeat=repeat+1,strategy=strategy,backend_delays_ms=delays,warmup=30)
                    results.append(result)
                    (lab.out/'results.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
                    print(label,result['measured']['success'],'/',args.requests,'p95',round(result['measured']['p95_ms'],2),flush=True)
            if not args.comparisons_only:
                # Observer continues polling during blocking JVM startup; keep separate clocks.
                from failure import exercise
                lab.profile((10,10,10))
                for strategy in order:
                    label=f'failure-{repeat+1}-{strategy}'
                    result=exercise(lab,strategy,label);result.update(experiment='failure-recovery',repeat=repeat+1)
                    results.append(result);(lab.out/'results.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
                    print(label,'detection_ms',result['detection_ms'],'routing_recovery_ms',result['routing_up_after_restart_ms'],flush=True)
        if args.comparisons_only:
            (lab.out/'sql-summary.json').write_text(json.dumps(lab.sql('SELECT (SELECT count(*) FROM st_orders) orders,(SELECT sum(stock) FROM st_variants) stock,(SELECT count(*) FROM st_order_items) items')),encoding='utf-8')
            print('Comparisons complete:',len(results),flush=True)
            return
        lab.profile((0,0,0));setup=[]
        for i in range(300):
            started=time.perf_counter_ns();connection=socket.create_connection(('127.0.0.1',PORTS[i%3]),timeout=2)
            connected=time.perf_counter_ns();connection.close();closed=time.perf_counter_ns()
            setup.append({'connect_us':(connected-started)/1000,'close_call_us':(closed-connected)/1000})
        with (lab.out/'tcp-connect.csv').open('w',newline='') as f:
            writer=csv.DictWriter(f,fieldnames=list(setup[0]));writer.writeheader();writer.writerows(setup)
        pairs=[]
        for i in range(300):
            for mode in (('direct','proxy') if i%2==0 else ('proxy','direct')):
                started=time.perf_counter();code,_,_=fetch((f'http://127.0.0.1:{PORTS[i%3]}' if mode=='direct' else BASE)+'/work?cost=1')
                pairs.append({'pair':i,'mode':mode,'status':code,'latency_ms':(time.perf_counter()-started)*1000})
        with (lab.out/'direct-proxy.csv').open('w',newline='') as f:
            writer=csv.DictWriter(f,fieldnames=list(pairs[0]));writer.writeheader();writer.writerows(pairs)
        # JFR is separate from comparative runs to avoid unequal instrumentation cost.
        lab.profile((0,0,0));lab.proxy('ROUND_ROBIN','profile',jfr=True)
        lab.batch('profile',['/work?cost=1','/compute?cost=4',lab.pdf],300,12,99)
        subprocess.run([str(Path(runtime.java()).with_name('jcmd.exe')),str(lab.children['proxy'].pid),'JFR.dump','filename='+str(lab.out/'transport.jfr')],capture_output=True,check=True)
        (lab.out/'tcp-after-load.json').write_text(json.dumps(tcp_snapshot()),encoding='utf-8')
        (lab.out/'sql-summary.json').write_text(json.dumps(lab.sql('SELECT (SELECT count(*) FROM st_orders) orders,(SELECT sum(stock) FROM st_variants) stock,(SELECT count(*) FROM st_order_items) items')),encoding='utf-8')
        (lab.out/'results.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
    finally:
        if lab and hasattr(lab,'children'):
            lab.close();(lab.out/'cleanup.json').write_text(json.dumps({'owned_processes_stopped':True,'schema_retained_for_inspection':lab.schema,'tcp_after_stop':tcp_snapshot()}))
    print('Evidence',args.out,flush=True)
if __name__=='__main__':main()
