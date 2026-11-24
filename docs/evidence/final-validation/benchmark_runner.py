"""Sequential PBL4 benchmark on owned loopback JVMs; no PostgreSQL or live application."""
from pathlib import Path
import argparse, collections, concurrent.futures, csv, hashlib, importlib.util
import json, math, os, platform, random, statistics, subprocess, sys, threading, time
from datetime import datetime, timezone
import socket
import psutil

REPO = Path(r"D:\PBL4\reverse-proxy-demo")
JDK = Path(r"D:\PBL4\tools\jdk21\jdk-21.0.13.0.2+1\bin")
CLASSES = Path(r"D:\PBL4\evidence\final-validation-20261122\after\classes")
SPEC = importlib.util.spec_from_file_location("defense_live", REPO / "scripts/defense_live.py")
DEMO = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEMO)
STRATEGIES = ["ROUND_ROBIN", "LEAST_CONNECTIONS", "ADAPTIVE"]
JVM = ["-Xms16m", "-Xmx96m", "-XX:ActiveProcessorCount=4"]
CASES = [
    dict(name="A_light_c1", concurrency=1, delays=[10,10,10], requests=240),
    dict(name="B_load_c4", concurrency=4, delays=[10,10,10], requests=240),
    dict(name="B_load_c12", concurrency=12, delays=[10,10,10], requests=240),
    dict(name="B_load_c24", concurrency=24, delays=[10,10,10], requests=240),
    dict(name="C_unequal_c12", concurrency=12, delays=[5,30,120], requests=240),
    dict(name="D_failure_c12", concurrency=12, delays=[100,100,100], requests=360, offered_rps=30),
    dict(name="E_mixed_c12", concurrency=12, delays=[10,10,10], requests=240, mixed=True),
]
ENV = {k:v for k,v in os.environ.items()
       if not k.startswith(("SHOP_", "STORE_", "PBL4_"))
       and k not in ("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS")}
ENV.update(SHOP_ENABLED="false", STORE_ENABLED="false", PBL4_PDF_LAB_ENABLED="false",
           PBL4_BACKEND_BIND_HOST="127.0.0.1", PYTHONDONTWRITEBYTECODE="1")

def write(path, value):
    path.write_text(json.dumps(value, indent=2), encoding="utf-8")

def hashes():
    return {str(p.relative_to(REPO)):hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted((REPO/"src/main").rglob("*")) if p.is_file()}

def free_ports():
    held = []
    try:
        for _ in range(4):
            s=socket.socket();s.bind(("127.0.0.1",0));held.append(s)
        return [s.getsockname()[1] for s in held]
    finally:
        for s in held:s.close()

def wait(predicate, seconds=25):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            result=predicate()
            if result:return result
        except (OSError,KeyError,TypeError,ValueError):pass
        time.sleep(.1)
    raise TimeoutError("Owned QA startup/recovery condition")

def percentile(values, fraction):
    return DEMO.percentile(values, fraction)

def plan(count, mixed):
    paths=(["/work?cost=1"]*(count*3//4)+["/slow"]*(count//8)+["/products"]*(count//8)
           if mixed else ["/work?cost=1"]*count)
    random.Random(20261122).shuffle(paths)
    return paths

def trial(root, case, repeat, strategy):
    name=f"{case['name']}-r{repeat}-{strategy}"
    out=root/name;out.mkdir()
    ports=free_ports();bports=ports[:3];proxy_port=ports[3]
    base=f"http://127.0.0.1:{proxy_port}"
    ids={f"127.0.0.1:{p}":f"B{i+1}" for i,p in enumerate(bports)}
    children=[];logs=[];processes={};events=[];samples=[]
    monitor_stop=threading.Event();fault_cancel=threading.Event()
    phase_start=None;sampler=None;fault=None
    def event(kind, **details):
        events.append(dict(kind=kind,offset_s=None if phase_start is None else time.monotonic()-phase_start,**details))
    def launch(label, args):
        handle=(out/(label+".log")).open("wb");logs.append(handle)
        proc=subprocess.Popen([str(JDK/"java.exe"),*JVM,"-cp",str(CLASSES)+";"+str(REPO/"src/main/resources"),*map(str,args)],
                              cwd=REPO,env=ENV,stdin=subprocess.DEVNULL,stdout=handle,stderr=handle,
                              creationflags=subprocess.CREATE_NO_WINDOW)
        children.append(proc);processes[label.split("-restart")[0]]=proc
        return proc
    def backend(i, restart=False):
        return launch(f"B{i+1}"+("-restart" if restart else ""),
                      ["com.example.backend.BackendApplicationServer",bports[i],4,case["delays"][i],0,4])
    def request(index, path, origin, offered=None):
        scheduled=index/offered if offered else None
        if scheduled is not None:
            pause=origin+scheduled-time.monotonic()
            if pause>0:time.sleep(pause)
        started=time.monotonic()
        row=DEMO.single_request(base,path,8,index,name)
        row.update(path=path,started_s=started-origin,finished_s=time.monotonic()-origin,
                   scheduled_s=scheduled,scheduling_lag_ms=None if scheduled is None else max(0,(started-origin-scheduled)*1000))
        row["backend_label"]=ids.get(row["backend"],"(not selected)")
        return row
    def measure(count, concurrency, paths, origin, offered=None):
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
            jobs=[pool.submit(request,i,path,origin,offered) for i,path in enumerate(paths)]
            return sorted([f.result() for f in concurrent.futures.as_completed(jobs)],key=lambda r:r["index"])
    def collect():
        psutil.cpu_percent(None)
        while not monitor_stop.is_set():
            sample={"offset_s":time.monotonic()-phase_start,
                    "host_cpu_percent":psutil.cpu_percent(None),
                    "host_available_bytes":psutil.virtual_memory().available,"processes":{}}
            for label,p in list(processes.items()):
                try:
                    proc=psutil.Process(p.pid)
                    with proc.oneshot():
                        cpu=proc.cpu_times()
                        sample["processes"][label]={"pid":p.pid,"cpu_seconds":cpu.user+cpu.system,
                                                    "rss_bytes":proc.memory_info().rss,"threads":proc.num_threads()}
                except (psutil.NoSuchProcess,psutil.AccessDenied):pass
            try:
                m=DEMO.metrics(base)
                sample["backends"]=[{k:b.get(k) for k in ("id","state","alive","inFlight","dispatched","completed",
                                                         "latencyEwmaMs","cpuLoad","memoryLoad","circuitState")}
                                    for b in m["backends"]]
                sample["traffic"]=m.get("traffic",{}).get("total",{})
            except Exception as e:sample["metrics_error"]=type(e).__name__
            samples.append(sample)
            monitor_stop.wait(.2)
    try:
        bs=[backend(i) for i in range(3)]
        for p in bports:
            wait(lambda p=p:DEMO.api(f"http://127.0.0.1:{p}","/health")[0]==200)
        properties=(REPO/"config/application.properties").read_text(encoding="utf-8")
        properties+="\nproxy.port="+str(proxy_port)+"\nbackend.servers="+",".join(f"127.0.0.1:{p}:4" for p in bports)
        properties+="\nloadbalancer.strategy="+strategy+"\n"
        (out/"proxy.properties").write_text(properties,encoding="utf-8")
        proxy=launch("proxy",["com.example.proxy.Main",out/"proxy.properties"])
        wait(lambda:len(DEMO.metrics(base)["backends"])==3 and all(b["state"]=="UP" for b in DEMO.metrics(base)["backends"]))
        assert DEMO.metrics(base)["strategy"]==strategy
        warm=measure(60,min(12,case["concurrency"]),plan(60,False),time.monotonic())
        assert all(200<=r["status"]<400 for r in warm),"Warm-up failed; retain invalid trial"
        # Mixed warm-up includes the same fixed fractions as the measured workload.
        if case.get("mixed"):
            warm+=measure(32,12,plan(32,True),time.monotonic())
            assert all(200<=r["status"]<400 for r in warm),"Mixed warm-up failed"
        with (out/"warmup.csv").open("w",newline="",encoding="utf-8") as f:
            w=csv.DictWriter(f,fieldnames=list(warm[0]));w.writeheader();w.writerows(warm)
        before=DEMO.metrics(base);write(out/"metrics-before.json",before)
        phase_start=time.monotonic()
        sampler=threading.Thread(target=collect,daemon=True);sampler.start()
        if case.get("offered_rps"):
            def cause_failure():
                if fault_cancel.wait(max(0,phase_start+3-time.monotonic())):return
                event("kill_requested",backend="B1",pid=bs[0].pid)
                bs[0].terminate();bs[0].wait(timeout=10);event("process_exited",backend="B1")
                if fault_cancel.wait(max(0,phase_start+6-time.monotonic())):return
                event("restart_requested",backend="B1")
                bs[0]=backend(0,True);event("restart_spawned",backend="B1",pid=bs[0].pid)
            fault=threading.Thread(target=cause_failure,daemon=True);fault.start()
        rows=measure(case["requests"],case["concurrency"],plan(case["requests"],case.get("mixed",False)),
                     phase_start,case.get("offered_rps"))
        elapsed=time.monotonic()-phase_start
        if fault:
            fault.join(timeout=15)
            assert not fault.is_alive(),"Fault thread unfinished"
            wait(lambda:DEMO.metrics(base)["backends"][0]["state"]=="UP",15)
        monitor_stop.set();sampler.join(timeout=5)
        wait(lambda:DEMO.metrics(base)["traffic"]["total"]["active"]==0,3)
        after=DEMO.metrics(base);write(out/"metrics-after.json",after)
        samples.append({"offset_s":time.monotonic()-phase_start,"processes":{},"host_cpu_percent":psutil.cpu_percent(None),"backends":after["backends"]})
        assert after["strategy"]==strategy
        assert all(r["strategy_header"] in ("",strategy) for r in rows),"Unexpected strategy header"
        assert after["traffic"]["total"]["active"]==0
        assert sum(b["inFlight"] for b in after["backends"])==0
        with (out/"raw.csv").open("w",newline="",encoding="utf-8") as f:
            writer=csv.DictWriter(f,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
        with (out/"samples.jsonl").open("w",encoding="utf-8") as f:
            for sample in samples:f.write(json.dumps(sample)+"\n")
        write(out/"events.json",events)
        latencies=[r["latency_ms"] for r in rows]
        success=[r for r in rows if 200<=r["status"]<400 and not r["client_error"]]
        recovery={}
        if fault:
            kill=next(e["offset_s"] for e in events if e["kind"]=="kill_requested")
            restart=next(e["offset_s"] for e in events if e["kind"]=="restart_spawned")
            down=next((s["offset_s"] for s in samples if s["offset_s"]>=kill and s.get("backends",[{}])[0].get("state")=="DOWN"),None)
            alive=next((s["offset_s"] for s in samples if s["offset_s"]>=restart and s.get("backends",[{}])[0].get("alive")),None)
            up=next((s["offset_s"] for s in samples if s["offset_s"]>=restart and s.get("backends",[{}])[0].get("state")=="UP"),None)
            resumed=next((r["finished_s"] for r in sorted(rows,key=lambda r:r["finished_s"])
                          if r["started_s"]>=restart and r["backend_label"]=="B1" and 200<=r["status"]<400),None)
            recovery={"kill_s":kill,"restart_spawned_s":restart,"first_down_observed_s":down,
                      "detection_after_kill_s":None if down is None else down-kill,
                      "first_alive_observed_s":alive,"first_full_up_observed_s":up,
                      "alive_after_restart_s":None if alive is None else alive-restart,
                      "full_up_after_restart_s":None if up is None else up-restart,
                      "first_success_recovered_backend_s":resumed,
                      "success_after_restart_s":None if resumed is None else resumed-restart,
                      "sampling_interval_s":.2}
        cpu_seconds={}
        for label in ("proxy","B1","B2","B3"):
            by_pid=collections.defaultdict(list)
            for s in samples:
                if label in s["processes"]:by_pid[s["processes"][label]["pid"]].append(s["processes"][label]["cpu_seconds"])
            cpu_seconds[label]=sum(max(v)-min(v) for v in by_pid.values() if len(v)>1)
        summary={"name":name,"case":case["name"],"repeat":repeat,"strategy":strategy,
                 "requests":len(rows),"concurrency":case["concurrency"],"delays_ms":case["delays"],
                 "elapsed_s":elapsed,"successful":len(success),"failed":len(rows)-len(success),
                 "http_errors":sum(r["status"]>=400 for r in rows),"client_errors":sum(r["status"]==0 for r in rows),
                 "http_error_rate":sum(r["status"]>=400 for r in rows)/len(rows),
                 "failure_rate":(len(rows)-len(success))/len(rows),
                 "attempted_rps":len(rows)/elapsed,"successful_rps":len(success)/elapsed,
                 "mean_ms":statistics.mean(latencies),"p50_ms":percentile(latencies,.5),
                 "p95_ms":percentile(latencies,.95),"p99_ms":percentile(latencies,.99),
                 "p95_success_ms":percentile([r["latency_ms"] for r in success],.95),
                 "distribution":dict(collections.Counter(r["backend_label"] for r in rows)),
                 "statuses":dict(collections.Counter(r["status"] for r in rows)),
                 "recovery":recovery,"sampled_cpu_seconds":cpu_seconds,
                 "sampled_peak_rss_bytes":{label:max([s["processes"][label]["rss_bytes"] for s in samples if label in s["processes"]] or [0]) for label in ("proxy","B1","B2","B3")},
                 "host_cpu_mean_percent":statistics.mean(s["host_cpu_percent"] for s in samples),
                 "warmup_requests":len(warm),"ports":ports,
                 "generator_lag_p95_ms":percentile([r["scheduling_lag_ms"] for r in rows if r["scheduling_lag_ms"] is not None],.95)}
        write(out/"summary.json",summary)
        print(json.dumps({k:summary[k] for k in ("name","successful","failed","successful_rps","p95_ms","p99_ms","distribution")}),flush=True)
        return summary
    finally:
        fault_cancel.set();monitor_stop.set()
        if fault:fault.join(timeout=12)
        if sampler:sampler.join(timeout=5)
        for p in reversed(children):
            if p.poll() is None:
                p.terminate()
                try:p.wait(timeout=10)
                except subprocess.TimeoutExpired:p.kill();p.wait(timeout=5)
        for f in logs:f.close()
        write(out/"ownership.json",{"pids":[p.pid for p in children],"all_stopped":all(p.poll() is not None for p in children)})

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--out",type=Path,required=True)
    parser.add_argument("--cases",nargs="*",default=[c["name"] for c in CASES])
    args=parser.parse_args();root=args.out.resolve()
    assert root!=REPO and REPO not in root.parents
    root.mkdir(exist_ok=False,parents=True)
    (root/"RUN_LOCK").write_text(str(os.getpid()),encoding="ascii")
    source=hashes()
    metadata={"started_utc":datetime.now(timezone.utc).isoformat(),"base_commit":subprocess.check_output(["git","rev-parse","HEAD"],cwd=REPO,text=True).strip(),
              "branch":subprocess.check_output(["git","branch","--show-current"],cwd=REPO,text=True).strip(),
              "worktree_modified":True,"source":source,
              "runtime_source_sha256":hashlib.sha256(json.dumps(source,sort_keys=True).encode()).hexdigest(),
              "runner_sha256":hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "java":subprocess.check_output([str(JDK/"java.exe"),"--version"],text=True),
              "jvm":JVM,"platform":platform.platform(),"cpu_model":platform.processor(),
              "logical_cpus":psutil.cpu_count(),"physical_cpus":psutil.cpu_count(logical=False),
              "ram_bytes":psutil.virtual_memory().total,"available_ram_before":psutil.virtual_memory().available,
              "cases":CASES,"repeats":3,"warmup":60,"worker_capacity_per_backend":4,
              "timeout_client_s":8,"metrics_interval_s":.2,
              "order":"case then repeat; strategy order rotates RR/LC/Adaptive by repeat",
              "topology":"one Windows host, loopback, fresh three backend JVMs and fresh proxy per trial; no DB or Store",
              "limitations":["Background Windows/IDE/demo processes not stopped or controlled.",
                             "Short finite closed-loop trials except paced failure; no production capacity claim.",
                             "Mean and sample SD of per-trial quantiles, not a pooled percentile.",
                             "CPU sampling undercounts first/last intervals and exited processes; RSS is not heap.",
                             "No simultaneous benchmarks; management observer traffic is matched but adds overhead."]}
    write(root/"environment.json",metadata)
    patch=subprocess.check_output(["git","diff","--binary"],cwd=REPO)
    (root/"working-tree.patch").write_bytes(patch)
    results=[]
    try:
        for case in CASES:
            if case["name"] not in args.cases:continue
            for repeat in range(1,4):
                order=STRATEGIES[repeat-1:]+STRATEGIES[:repeat-1]
                for strategy in order:
                    if psutil.virtual_memory().available<600*1024*1024:raise RuntimeError("Less than 600 MiB available; stop before a new QA trial")
                    results.append(trial(root,case,repeat,strategy))
                    write(root/"results.json",results)
                    time.sleep(.25)
        assert hashes()==source,"Measured main source changed during experiment"
        write(root/"execution.json",{"completed":True,"trials":len(results),"all_owned_processes_stopped":True,
                                    "finished_utc":datetime.now(timezone.utc).isoformat()})
    except Exception as error:
        write(root/"execution.json",{"completed":False,"trials":len(results),"error":repr(error)})
        raise

if __name__=="__main__":main()

