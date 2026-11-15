"""Real bounded-heap streaming acceptance in a new PostgreSQL Lab and new files root.

No existing files/databases/processes are modified. Evidence and QA data are retained.
Requires psutil, the normal Lab dependencies, a private env, and a Java 21 build.
"""
import argparse
import concurrent.futures
import hashlib
import http.client
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import threading
import time

import psutil

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from lab import Lab,BASE,PORTS,ROOT,fetch,get,runtime,tcp_snapshot
import store
from qa_paths import evidence_path, new_evidence_path


class StreamLab(Lab):
    def launch(self,name,args,jfr=None):
        log=(self.out/(name+'.log')).open('ab');self.logs.append(log)
        flags=['-Xms16m','-Xmx64m' if name=='proxy' else '-Xmx128m']
        child=subprocess.Popen([runtime.java(),*flags,'-cp',runtime.CP,*args],cwd=ROOT,env=self.env,
            stdin=subprocess.DEVNULL,stdout=log,stderr=log,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        self.children[name]=child;return child


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file',type=Path,required=True)
    parser.add_argument('--out',type=Path,default=None)
    args=parser.parse_args();out=evidence_path(args.out or new_evidence_path('streaming'));out.mkdir(parents=True,exist_ok=False)
    store.configure(args.env_file);os.chdir(ROOT)
    public=out/'public-downloads';public.mkdir()
    os.environ['PBL4_LARGE_FILES_ROOT']=str(public)
    checks=[];transfers=[];lab=None;passed=False
    def check(condition,label):
        checks.append({'check':label,'passed':bool(condition)})
        print(('PASS ' if condition else 'FAIL ')+label,flush=True)
        if not condition:raise AssertionError(label)
    def wait(predicate,label,seconds=15):
        until=time.monotonic()+seconds
        while not predicate():
            if time.monotonic()>until:raise AssertionError('Timeout: '+label)
            time.sleep(.05)
    def metrics():return get(BASE+'/__proxy/metrics')
    def clean():
        def zero():
            m=metrics()
            return m['streaming']['active']==m['streaming']['openBodies']==m['qos']['active']==m['downloadBulkhead']['active']==m['downloadBulkhead']['waiting']==0 and all(b['inFlight']==0 for b in m['backends'])
        wait(zero,'all streaming/admission/scheduler resources return to zero')
        for port in PORTS:
            raw=fetch(f'http://127.0.0.1:{port}/metrics')[1].decode()
            values=dict(line.split('=',1) for line in raw.splitlines() if '=' in line)
            wait(lambda port=port:all(('activeRequests=0' in fetch(f'http://127.0.0.1:{port}/metrics')[1].decode(),
                                     'admittedRequests=0' in fetch(f'http://127.0.0.1:{port}/metrics')[1].decode())), 'backend workers release')
        m=metrics();check(True,'zero stream bodies, leases, QoS and bulkhead tickets, backend workers')
        return m
    def transfer(name,method='GET',headers=None,delay=0,destination=None):
        samples=[];stop=threading.Event();pid=lab.children['proxy'].pid
        def sample():
            process=psutil.Process(pid)
            while not stop.is_set():
                try:samples.append({'at':time.time(),'rss':process.memory_info().rss,'cpu':sum(process.cpu_times()[:2]),
                                    'threads':process.num_threads(),'handles':process.num_handles() if os.name=='nt' else process.num_fds()})
                except psutil.Error:break
                stop.wait(.02)
        observer=threading.Thread(target=sample);observer.start();start=time.monotonic()
        connection=http.client.HTTPConnection('127.0.0.1',18080,timeout=125)
        digest=hashlib.sha256();count=0
        try:
            connection.request(method,'/api/pbl4/files/'+name,headers=headers or {})
            response=connection.getresponse();h={k.lower():v for k,v in response.getheaders()}
            file=destination.open('ab') if destination else None
            try:
                while block:=response.read(32768):
                    count+=len(block);digest.update(block)
                    if file:file.write(block)
                    if delay:time.sleep(delay)
            finally:
                if file:file.close()
            result={'file':name,'method':method,'request_headers':headers,'status':response.status,'headers':h,
                    'bytes':count,'sha256':digest.hexdigest(),'seconds':time.monotonic()-start,'samples':samples}
            transfers.append(result);return result
        finally:
            connection.close();stop.set();observer.join(3)
    def open_download(name='file-256.bin'):
        s=socket.create_connection(('127.0.0.1',18080),timeout=5);s.setsockopt(socket.SOL_SOCKET,socket.SO_RCVBUF,4096)
        s.sendall(('GET /api/pbl4/files/'+name+' HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n').encode())
        data=b''
        while b'\r\n\r\n' not in data:data+=s.recv(4096)
        if not data.startswith(b'HTTP/1.1 200 '):
            s.close();raise AssertionError('Held stream did not receive 200')
        return s,data
    def abort(s):
        s.setsockopt(socket.SOL_SOCKET,socket.SO_LINGER,struct.pack('hh' if os.name=='nt' else 'ii',1,0));s.close()
    try:
        payload=bytes(range(251))*4177 # ~1 MiB; never construct a full large payload in Python/Java heap
        expected={}
        for size in (32,100,256):
            path=public/f'file-{size}.bin';left=size*1024*1024;digest=hashlib.sha256()
            with path.open('xb') as file:
                while left:
                    block=payload[:min(left,len(payload))];file.write(block);digest.update(block);left-=len(block)
            expected[path.name]={'bytes':path.stat().st_size,'sha256':digest.hexdigest()}
        (out/'files.json').write_text(json.dumps(expected,indent=2))
        (public/'bin').write_bytes(b'extensionless file must never be served')
        lab=StreamLab.__new__(StreamLab);lab.__init__(out/'lab')
        settings={'proxy.streaming.enabled':'true','proxy.streaming.timeout.ms':120000,
                  'proxy.connection-pool.enabled':'true',
                  'proxy.pdf.bulkhead.max.inflight':2,'proxy.pdf.bulkhead.queue.capacity':2,'proxy.pdf.bulkhead.wait.ms':100,
                  'proxy.max.inflight':12,'proxy.qos.reserved.critical':2,'proxy.qos.max.critical.per.peer':2}
        lab.proxy('ROUND_ROBIN','streaming-enabled',extra=settings)
        for size in (32,100,256):
            name=f'file-{size}.bin';full=transfer(name)
            check(full['status']==200 and full['bytes']==expected[name]['bytes'] and full['sha256']==expected[name]['sha256'],f'{size} MiB GET through real proxy SHA-256 byte equal')
            check(full['headers']['content-length']==str(expected[name]['bytes']),f'{size} MiB exact fixed-length framing')
            check('1.1 pbl4' in full['headers'].get('via',''),f'{size} MiB proxy Via response header')
            head=transfer(name,'HEAD')
            check(head['status']==200 and head['bytes']==0 and head['headers']['content-length']==str(expected[name]['bytes']),f'{size} MiB HEAD representation length without body')
            for value,start,length in [('bytes=0-4095',0,4096),('bytes=100000-104095',100000,4096),('bytes=-4096',expected[name]['bytes']-4096,4096)]:
                part=transfer(name,headers={'Range':value})
                with (public/name).open('rb') as f:f.seek(start);sha=hashlib.sha256(f.read(length)).hexdigest()
                check(part['status']==206 and part['bytes']==length and part['sha256']==sha and part['headers']['content-range']==f'bytes {start}-{start+length-1}/{expected[name]["bytes"]}',f'{size} MiB {value} range bytes and Content-Range')
        name='file-100.bin';resumed=out/'resumed-100.bin'
        first=transfer(name,headers={'Range':'bytes=0-1048575'},destination=resumed)
        rest=transfer(name,headers={'Range':'bytes=1048576-','If-Range':first['headers']['etag']},destination=resumed)
        check(rest['status']==206 and hashlib.file_digest(resumed.open('rb'),'sha256').hexdigest()==expected[name]['sha256'],'100 MiB Range + strong If-Range resume reconstructs identical real file')
        check(transfer('file-32.bin','HEAD',{'Range':'bytes=0-4'})['status']==200,'HEAD ignores Range')
        tag='"'+expected['file-32.bin']['sha256']+'"'
        for headers,code in [({'If-None-Match':tag,'Range':'bytes=0-4'},304),({'If-None-Match':'W/'+tag},304),({'If-Match':'"wrong"'},412),({'Range':'bytes=0-4','If-Range':'"stale"'},200),({'Range':'bytes=999999999999-'},416),({'Range':'bytes=0-4,7-9'},416),({'Range':'bytes=bad'},416)]:
            result=transfer('file-32.bin',headers=headers)
            check(result['status']==code,'conditional/range semantics '+str(headers))
            if code in (304,412,416):check(result['bytes']==0,'conditional/refusal has no body')
        for name in ('../secret.bin','%2e%2e%2fsecret.bin','%66ile-32.bin','a.txt','bin'):
            check(transfer(name)['status'] in (400,403,404),'path/extension refusal '+name)
        check(transfer('file-32.bin','POST')['status']==405,'large-file endpoint is read only')
        check(lab.request('ORDER')[0]==201,'ordering remains transactional with streaming enabled and bounded heap')
        # Slow transfer exceeds the ordinary 5s ingress deadline without changing it.
        slow=transfer('file-32.bin',delay=.006)
        check(slow['seconds']>5 and slow['sha256']==expected['file-32.bin']['sha256'],'slow client completes beyond short ingress deadline using route-only bounded budget')
        m=clean();check(all(b['circuitState']=='CLOSED' for b in m['backends']),'healthy streams retain closed backend circuits')
        # RST and bulkhead pressure must not poison a healthy upstream or starve orders.
        sockets=[]
        before=m['streaming']['downstreamFailures']
        try:
            for _ in range(2):s,_=open_download();sockets.append(s)
            wait(lambda:metrics()['downloadBulkhead']['active']==2,'two held stream slots')
            rejected=transfer('file-32.bin');check(rejected['status']==503,'streaming bulkhead bounds long transfers')
            check(lab.request('ORDER')[0]==201,'reserved checkout completes while all download slots held')
        finally:
            for s in sockets:abort(s)
        m=clean();check(m['streaming']['downstreamFailures']>=before+2 and all(b['circuitState']=='CLOSED' for b in m['backends']),'client RST classified downstream; healthy backend circuits unchanged')
        # Kill only the selected QA backend after headers; client must see truncation.
        connection=http.client.HTTPConnection('127.0.0.1',18080,timeout=10)
        connection.request('GET','/api/pbl4/files/file-256.bin');response=connection.getresponse()
        selected=response.getheader('X-Proxy-Backend');response.read(4096)
        lab.stop(selected.rsplit(':',1)[-1]);broken=False;received=4096
        try:
            while block:=response.read(32768):received+=len(block)
        except (OSError,http.client.IncompleteRead):broken=True
        finally:connection.close()
        check((broken or response.length and response.length>0) and received<expected['file-256.bin']['bytes'],'backend death midstream yields incomplete client response, never successful truncated file')
        lab.backend(int(selected.rsplit(':',1)[-1]));wait(lambda:all(b['state']=='UP' for b in metrics()['backends']),'backend recovery')
        clean()
        # Missing storage is an explicit service refusal, not process death.
        hidden=out/'temporarily-unavailable-files';public.rename(hidden)
        try:check(transfer('file-32.bin')['status']==503,'unavailable file root fails closed')
        finally:hidden.rename(public)
        check(transfer('file-32.bin',headers={'Range':'bytes=0-15'})['status']==206,'file service recovers after storage returns')
        # Atomic replacement must invalidate stale ETag and preserve an in-flight descriptor.
        original=public/'replace.bin';original.write_bytes(payload*8)
        old_digest=hashlib.file_digest(original.open('rb'),'sha256').hexdigest()
        connection=http.client.HTTPConnection('127.0.0.1',18080,timeout=15);connection.request('GET','/api/pbl4/files/replace.bin')
        response=connection.getresponse();old_tag=response.getheader('ETag');digest=hashlib.sha256(response.read(4096))
        staged=out/'replacement.bin';staged.write_bytes(b'r'*1048576)
        replacement_deferred=False
        try:os.replace(staged,original)
        except PermissionError:
            # Windows can refuse replacement while a no-follow descriptor is open.
            # This is safe exclusion: prove the in-flight bytes, then publish after close.
            if os.name!='nt':raise
            replacement_deferred=True
        while block:=response.read(32768):digest.update(block)
        connection.close();check(digest.hexdigest()==old_digest,'atomic file replacement cannot change bytes under an in-flight ETag')
        if replacement_deferred:
            clean();os.replace(staged,original)
            check(True,'Windows refused publication during an open reader; atomic replacement succeeds after descriptor closes')
        replacement=transfer('replace.bin',headers={'Range':'bytes=100-','If-Range':old_tag})
        check(replacement['status']==200 and replacement['bytes']==1048576 and replacement['sha256']==hashlib.sha256(b'r'*1048576).hexdigest() and replacement['headers']['etag']!=old_tag,'stale If-Range after replacement returns new complete representation')
        # Windows junction / Linux symlink root refusal in the actual backend process.
        linked=out/'linked-root'
        if os.name=='nt':subprocess.run(['cmd','/c','mklink','/J',str(linked),str(public)],check=True,capture_output=True)
        else:linked.symlink_to(public,target_is_directory=True)
        lab.env['PBL4_LARGE_FILES_ROOT']=str(linked)
        lab.stop('19001');lab.backend(19001)
        check(fetch('http://127.0.0.1:19001/api/pbl4/files/file-32.bin')[0]==503,'actual linked/junction root rejected')
        lab.env['PBL4_LARGE_FILES_ROOT']=str(public);lab.stop('19001');lab.backend(19001)
        lab.proxy('ROUND_ROBIN','bounded-deadline',extra={**settings,'proxy.streaming.timeout.ms':700})
        s,_=open_download();started=time.monotonic()
        wait(lambda:metrics()['streaming']['active']==0,'slow blocked writer deadline',seconds=5)
        abort(s);m=clean()
        check(time.monotonic()-started<5 and m['streaming']['timeouts']>=1,'blocked slow client bounded by streaming deadline and all resources released')
        check(all(b['circuitState']=='CLOSED' for b in m['backends']),'local route deadline does not condemn healthy backends')
        # Repeated cancellation should stabilize OS sockets/handles, not just counters.
        lab.proxy('ROUND_ROBIN','cancellation-soak',extra=settings)
        for _ in range(3):
            wait(lambda:metrics()['streaming']['active']<2,'warm cancellation capacity')
            s,_=open_download();abort(s)
        clean();time.sleep(.5);process=psutil.Process(lab.children['proxy'].pid)
        before_handles=process.num_handles() if os.name=='nt' else process.num_fds()
        before_sockets=len(process.net_connections(kind='tcp'))
        for _ in range(20):
            wait(lambda:metrics()['streaming']['active']<2,'cancellation capacity')
            s,_=open_download();abort(s)
        m=clean();time.sleep(1)
        after_handles=process.num_handles() if os.name=='nt' else process.num_fds()
        after_sockets=len(process.net_connections(kind='tcp'))
        check(after_handles<=before_handles+12 and after_sockets<=before_sockets+6,'20 cancellation soak: bounded actual OS handles and sockets')
        (out/'socket-soak.json').write_text(json.dumps({'before_handles':before_handles,'after_handles':after_handles,'before_sockets':before_sockets,'after_sockets':after_sockets,'tcp':tcp_snapshot()},indent=2))
        large=[t for t in transfers if t['method']=='GET' and t['request_headers'] is None and t['bytes'] in (32*1048576,100*1048576,256*1048576)]
        peaks={str(t['bytes']//1048576):max(s['rss'] for s in t['samples']) for t in large}
        check(len(peaks)==3 and max(peaks.values())-min(peaks.values())<100*1048576,'32/100/256 MiB peak RSS growth below 100 MiB under a fixed 64 MiB proxy heap')
        heap=subprocess.run([str(Path(runtime.java()).with_name('jcmd.exe' if os.name=='nt' else 'jcmd')),str(process.pid),'GC.heap_info'],capture_output=True,check=True)
        (out/'heap.log').write_bytes(heap.stdout+heap.stderr)
        (out/'metrics-final.json').write_text(json.dumps(m,indent=2))
        (out/'memory.json').write_text(json.dumps({'proxy_max_heap_bytes':64*1048576,'peak_rss_by_file_MiB':peaks,'note':'RSS includes JVM native memory; heap hard bounded by -Xmx64m, smaller than 100/256MiB payloads'},indent=2))
        check(m['streaming']['started']==m['streaming']['completed']+m['streaming']['failed'],'streaming started = completed + failed with zero active')
        check(all(fetch(BASE+'/api/store/products')[0]==200 for _ in range(18)),'18 ordinary catalog GETs succeed with explicit upstream pooling ON')
        m=metrics()
        check(m['connectionPool']['enabled'] and m['connectionPool']['hits']>0,'ordinary upstream keep-alive ON reuses real connections alongside streaming and successful checkout')
        lab.proxy('ROUND_ROBIN','deployment-defaults')
        defaults=metrics()
        check(defaults['connectionPool']['enabled'] is False and defaults['qos']['reservedCritical']==0,'deployment upstream pool and QoS defaults remain OFF after isolated ON testing')
        (out/'default-metrics.json').write_text(json.dumps(defaults,indent=2))
        passed=True
    finally:
        if lab and hasattr(lab,'children'):lab.close()
        (out/'transfers.json').write_text(json.dumps(transfers,indent=2))
        (out/'result.json').write_text(json.dumps({'passed':passed,'checks':checks,'scope':'three real JVMs, real PostgreSQL, Windows/Linux loopback; not multi-host LAN','data_retained':True},indent=2))
    print('Streaming live acceptance PASS:',len(checks),flush=True)


if __name__=='__main__':main()
