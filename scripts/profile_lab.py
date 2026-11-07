"""Bounded diagnostic JFR on one backend and proxy, separate from comparative benchmarks."""
import argparse,json,os,subprocess,collections,threading,time,urllib.parse,xml.etree.ElementTree as ET
from pathlib import Path
from lab import Lab,ROOT,BASE,PORTS,runtime,store,psutil

EVENTS='jdk.SocketRead,jdk.SocketWrite,jdk.FileRead,jdk.FileWrite,jdk.JavaMonitorEnter,jdk.VirtualThreadPinned,jdk.ThreadPark,jdk.GarbageCollection,jdk.ObjectAllocationSample,jdk.ExecutionSample,jdk.InitialEnvironmentVariable,jdk.InitialSystemProperty'

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,required=True);args=p.parse_args()
    os.chdir(ROOT);store.configure(ROOT/'.env.store');lab=None
    try:
        lab=Lab.__new__(Lab);lab.__init__(args.out.resolve());lab.stop(str(PORTS[0]))
        lab.launch(str(PORTS[0]),['com.example.backend.DemoBackendServer',str(PORTS[0]),'4','0','0'],lab.out/'backend-startup.jfr')
        runtime.wait_ready(f'http://127.0.0.1:{PORTS[0]}/health');lab.proxy('ROUND_ROBIN','diagnostic',jfr=True)
        tree=ET.parse(lab.out/'safe-profile.jfc')
        for name in ('jdk.SocketRead','jdk.SocketWrite','jdk.FileRead','jdk.FileWrite','jdk.JavaMonitorEnter','jdk.VirtualThreadPinned'):
            event=tree.getroot().find(f"event[@name='{name}']")
            if event is None:raise RuntimeError('Missing event '+name)
            event.find("setting[@name='enabled']").text='true'
            threshold=event.find("setting[@name='threshold']")
            if threshold is not None:threshold.text='0 ms' if name.endswith(('Read','Write')) else '1 ms'
        settings=lab.out/'io-profile.jfc';tree.write(settings,encoding='utf-8',xml_declaration=True)
        jcmd=str(Path(runtime.java()).with_name('jcmd.exe'));jfr=str(Path(runtime.java()).with_name('jfr.exe'))
        def command(args,path,keep=True):
            result=subprocess.run(args,capture_output=True)
            if keep or result.returncode:path.write_bytes(result.stdout+result.stderr)
            result.check_returncode();return result.stdout
        for name in (str(PORTS[0]),'proxy'):
            command([jcmd,str(lab.children[name].pid),'JFR.start','name=diagnostic','settings='+str(settings),'maxsize=16m'],lab.out/(name+'-start.log'))
        connection_samples=[];stop=threading.Event();db_port=urllib.parse.urlsplit(lab.env['SHOP_DB_URL'].removeprefix('jdbc:')).port
        def observe_connections():
            while not stop.is_set():
                sample={'monotonic_s':time.perf_counter(),'processes':{}}
                if psutil:
                    for name,child in lab.children.items():
                        try:
                            process=psutil.Process(child.pid);connections=process.net_connections(kind='tcp');io=process.io_counters()
                            sample['processes'][name]={'postgres_tcp_connections':sum(bool(c.raddr) and c.raddr.port==db_port for c in connections),
                                'io_read_bytes':io.read_bytes,'io_write_bytes':io.write_bytes,'io_read_operations':io.read_count,'io_write_operations':io.write_count}
                        except psutil.Error as error:sample['processes'][name]={'status':type(error).__name__}
                else:sample['status']='NOT RUN: psutil unavailable'
                connection_samples.append(sample);stop.wait(.2)
        observer=threading.Thread(target=observe_connections);observer.start()
        try:run=lab.batch('diagnostic',['ORDER','/api/store/products',lab.pdf,'/compute?cost=4'],600,12,20261107,background=8)
        finally:stop.set();observer.join(timeout=15)
        (lab.out/'connection-io-samples.json').write_text(json.dumps({'samples':connection_samples,'scope':'Owned lab JVMs only. PostgreSQL TCP sockets, not active SQL queries. OS I/O includes logs and class loading; JFR provides per-file events.'}),encoding='utf-8')
        reports={}
        for name in (str(PORTS[0]),'proxy'):
            recording=lab.out/(name+'.jfr')
            command([jcmd,str(lab.children[name].pid),'JFR.dump','name=diagnostic','filename='+str(recording)],lab.out/(name+'-dump.log'))
            command([jfr,'summary',str(recording)],lab.out/(name+'-summary.txt'))
            data=command([jfr,'print','--json','--stack-depth','3','--events',EVENTS,str(recording)],lab.out/(name+'-events.json'),keep=False)
            events=json.loads(data)['recording']['events'];counts=collections.Counter(e['type'] for e in events)
            assert counts['jdk.InitialEnvironmentVariable']==0 and counts['jdk.InitialSystemProperty']==0
            summary={}
            for kind in sorted(counts):
                selected=[e['values'] for e in events if e['type']==kind];durations=[]
                for value in selected:
                    duration=value.get('duration','')
                    if isinstance(duration,str) and duration.startswith('PT') and duration.endswith('S'):
                        try:durations.append(float(duration[2:-1])*1000)
                        except ValueError:pass
                summary[kind]={'events':len(selected),'duration_total_ms':sum(durations) if durations else None,
                               'bytes':sum(v.get('bytesRead',v.get('bytesWritten',0)) for v in selected),
                               'allocation_sample_weight_bytes':sum(v.get('weight',0) for v in selected) if kind=='jdk.ObjectAllocationSample' else None}
            reports[name]={'recording_bytes':recording.stat().st_size,'events':summary}
        (lab.out/'profile.json').write_text(json.dumps({'purpose':'Diagnostic only, instrumentation differs from comparative benchmark. One backend sampled, all three serve requests.',
            'jfr_limits':'16 MiB diagnostic recording per process; initial environment/system-property events disabled; I/O thresholds zero, monitor/pinning thresholds 1 ms.',
            'run':run,'processes':reports,'limits':'Counts and sampled stacks describe this recording only; no event does not prove absence in all executions. Allocation weights are samples, not exact allocation totals.'},indent=2),encoding='utf-8')
        print('Diagnostic JFR complete:',lab.out)
    finally:
        if lab and hasattr(lab,'children'):lab.close()
if __name__=='__main__':main()
