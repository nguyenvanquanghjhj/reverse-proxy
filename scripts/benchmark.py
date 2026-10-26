#!/usr/bin/env python3
"""Reproducible, dependency-free HTTP benchmark with isolated backend JVMs.

This is a local experiment, not a claim of optimal scheduling. The clients, proxy,
and backends share one host; process CPU and heap differ from host CPU and memory.
"""
import argparse
import hashlib
import platform
import statistics
import sys
from datetime import datetime, timezone
import concurrent.futures
import csv
import http.client
import json
import math
import os
from pathlib import Path
import random
import re
import shutil
import socket
import subprocess
import threading
import time
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]
STRATEGIES = ("ROUND_ROBIN", "LEAST_CONNECTIONS", "ADAPTIVE")


def request(port, path="/work", method="GET", body=None, headers=None, timeout=8):
    """One connection per request, deliberately identical for every strategy."""
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        conn.request(method, path, body=body, headers=headers or {})
        response = conn.getresponse()
        return response.status, dict((k.lower(), v) for k, v in response.getheaders()), response.read()
    finally:
        conn.close()


def properties(body):
    result = {}
    for line in body.decode("utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            try:
                result[key] = float(value)
            except ValueError:
                result[key] = value
    return result


def until(predicate, timeout=12, message="condition"):
    end, error = time.monotonic() + timeout, None
    while time.monotonic() < end:
        try:
            result = predicate()
            if result:
                return result
        except (OSError, ValueError, http.client.HTTPException) as exc:
            error = exc
        time.sleep(0.05)
    raise AssertionError(f"Timed out waiting for {message}; last error={error}")


class Cluster:
    """Own only the processes created here. Logs and config stay in the run folder."""
    def __init__(self, directory, strategy="ADAPTIVE", specs=((4, 20),) * 3, overrides=None, java=None, classes=None, config_text=None):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.specs, self.strategy = tuple(specs), strategy
        self.java = java or shutil.which('java') or 'java'
        self.classes = Path(classes) if classes else ROOT / "target/reverse-proxy-demo.jar"
        self.config_text = config_text
        self.processes, self.logs, self.backends = [], [], []
        self.ports = []
        for _ in range(len(specs) + 1):
            while True:
                with socket.socket() as sock:
                    sock.bind(("127.0.0.1", 0))
                    port = sock.getsockname()[1]
                if port not in self.ports:
                    self.ports.append(port)
                    break
        self.port = self.ports.pop()
        self.overrides = overrides or {}

    def spawn(self, args, name):
        log = (self.directory / f"{name}.log").open("wb")
        self.logs.append(log)
        proc = subprocess.Popen(
            [self.java, "-Xms32m", "-Xmx128m", "-cp", str(self.classes), *args],
            cwd=ROOT, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
        self.processes.append(proc)
        return proc

    def start_backend(self, index):
        capacity, delay = self.specs[index]
        proc = self.spawn(["com.example.backend.DemoBackendServer", str(self.ports[index]), str(capacity), str(delay), "0"],
                          f"backend-{index}-{len(self.processes)}")
        if index < len(self.backends):
            self.backends[index] = proc
        else:
            self.backends.append(proc)
        until(lambda: self.ready(proc, self.ports[index], "/health"), message=f"backend {index}")

    @staticmethod
    def ready(proc, port, path):
        if proc.poll() is not None:
            raise RuntimeError(f"Java process exited with {proc.returncode}; inspect run logs")
        return request(port, path, timeout=1)[0] == 200

    def __enter__(self):
        try:
            if not (self.classes.is_file() or (self.classes / "com/example/proxy/Main.class").exists()):
                raise RuntimeError("Build first: scripts/build.ps1 or scripts/build.sh")
            for index in range(len(self.specs)):
                self.start_backend(index)
            config = {}
            config_text = self.config_text if self.config_text is not None else (ROOT / "config/application.properties").read_text(encoding="utf-8")
            for line in config_text.splitlines():
                if "=" in line and not line.lstrip().startswith("#"):
                    key, value = line.split("=", 1)
                    config[key.strip()] = value.strip()
            config.update({"proxy.bind.host": "127.0.0.1", "proxy.port": self.port,
                           "backend.servers": ",".join(f"127.0.0.1:{p}:{s[0]}" for p, s in zip(self.ports, self.specs)),
                           "loadbalancer.strategy": self.strategy})
            config.update(self.overrides)
            config_path = self.directory / "proxy.properties"
            config_path.write_text("".join(f"{k}={v}\n" for k, v in config.items()), encoding="utf-8")
            proxy = self.spawn(["com.example.proxy.Main", str(config_path.resolve())], "proxy")
            until(lambda: self.ready(proxy, self.port, "/__proxy/metrics"), message="proxy monitoring")
            until(lambda: all(b["state"] == "UP" for b in self.monitor()["backends"]), message="healthy warmup")
            return self
        except BaseException:
            self.close()
            raise

    def control(self, index, **values):
        status, _, body = request(self.ports[index], "/control?" + urllib.parse.urlencode(values))
        if status != 200:
            raise RuntimeError(f"Backend control failed: {status} {body!r}")

    def monitor(self, timeout=2):
        status, _, body = request(self.port, "/__proxy/metrics", timeout=timeout)
        if status != 200:
            raise RuntimeError(f"Monitoring failed: {status}")
        return json.loads(body)

    def snapshot(self):
        result = {"monotonic": time.monotonic(), "backends": []}
        for port in self.ports:
            try:
                status, _, body = request(port, "/metrics", timeout=1)
                result["backends"].append({"port": port, "status": status, **properties(body)})
            except (OSError, http.client.HTTPException) as exc:
                result["backends"].append({"port": port, "error": str(exc)})
        result["proxy"] = self.monitor()
        return result

    def kill_backend(self, index):
        self.backends[index].kill()
        self.backends[index].wait(timeout=5)

    def close(self):
        errors = []
        for proc in reversed(self.processes):
            if proc.poll() is None:
                try:
                    proc.terminate()
                except OSError as exc:
                    if proc.poll() is None:
                        errors.append(exc)
        for proc in reversed(self.processes):
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                try:
                    proc.kill()
                    proc.wait(timeout=5)
                except (OSError, subprocess.TimeoutExpired) as exc:
                    errors.append(exc)
            except OSError as exc:
                errors.append(exc)
        for log in self.logs:
            try:
                log.close()
            except OSError as exc:
                errors.append(exc)
        if errors:
            raise ExceptionGroup("Benchmark cleanup failed", errors)

    def __exit__(self, *_):
        self.close()


class Recovery:
    """Fixed-time health outage of backend 1; no counter reset or adaptive tuning."""
    def __init__(self, cluster, duration, directory):
        self.cluster, self.duration, self.directory = cluster, duration, directory
        self.stop = threading.Event()
        self.events = []
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)

    def __enter__(self):
        self.future = self.pool.submit(self.run)
        return self

    def run(self):
        start = time.perf_counter()
        for fraction, healthy in ((.25, False), (.65, True)):
            if self.stop.wait(max(0, .25 + fraction * self.duration - (time.perf_counter() - start))):
                return
            self.cluster.control(0, healthy=str(healthy).lower())
            self.events.append(dict(healthy=healthy, elapsed_ms=(time.perf_counter() - start) * 1000))

    def __exit__(self, kind, *_):
        # Normal completion waits for the recovery event even if all requests were rejected early.
        if kind is not None:
            self.stop.set()
        try:
            self.future.result()
        finally:
            self.pool.shutdown(wait=True, cancel_futures=True)
            self.cluster.control(0, healthy="true")
            csv_write(self.directory / "events.csv", self.events)


# Fixed before inspecting any algorithm's result. Capacity is concurrency, not CPU quota.
SCENARIOS = {
    "homogeneous": ((4, 30), (4, 30), (4, 30)),
    "heterogeneous": ((8, 20), (4, 60), (2, 160)),
    "mixed": ((4, 20), (4, 20), (4, 20)),
    "timeout": ((4, 20), (4, 20), (4, 20)),
    "recovery": ((4, 20), (4, 20), (4, 20)),
    "errors": ((4, 20), (4, 20), (4, 20)),
}


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def schedule(scenario, count, rate, seed):
    """Exact 60/10/30 mix for multiples of ten; shuffled deterministically."""
    positive(rate, "rate")
    positive(count, "count", integer=True)
    if scenario not in SCENARIOS:
        raise ValueError("Unknown scenario")
    types = ["light", "slow", "cpu", "light", "cpu", "light", "light", "cpu", "light", "light"]
    rows = [types[i % 10] if scenario == "mixed" else "light" for i in range(count)]
    if scenario == "timeout":
        rows = ["slow"] * count
    elif scenario == "errors":
        rows = [("light", "429", "503")[i % 3] for i in range(count)]
    random.Random(seed).shuffle(rows)
    paths = {"light": "/work?cost=1", "slow": "/slow?cost=1", "cpu": "/compute?cost=8", "429": "/status?code=429", "503": "/status?code=503"}
    return [{"index": i, "kind": kind, "path": paths[kind], "scheduled_ms": 1000 * i / rate}
            for i, kind in enumerate(rows)]


def positive(value, name, integer=False):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
        raise ValueError(f"{name} must be finite and positive")
    if integer and not isinstance(value, int):
        raise ValueError(f"{name} must be an integer")


def finite(value):
    return value if isinstance(value, (int, float)) and math.isfinite(value) else None


def csv_write(path, rows):
    if not rows:
        return
    for row in rows:
        for key, value in row.items():
            if isinstance(value, float) and not math.isfinite(value):
                raise ValueError(f"Nonfinite CSV field: {key}")
    fields = list(dict.fromkeys(key for row in rows for key in row))
    with Path(path).open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def json_write(path, value):
    Path(path).write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False), encoding="utf-8")


def sample_cluster(cluster, start, phase):
    # Sequential HTTP samples are timestamped individually; not an atomic distributed snapshot.
    output = []
    proxy_error = ""
    work_rejected = None
    try:
        monitor = cluster.monitor(timeout=1)
        snapshots = {b["port"]: b for b in monitor["backends"]}
        work_rejected = monitor.get("estimatedWorkRejected")
    except (OSError, ValueError, RuntimeError, http.client.HTTPException) as exc:
        snapshots = {}
        proxy_error = str(exc)
    for index, port in enumerate(cluster.ports):
        row = {"phase": phase, "backend": f"backend-{index + 1}", "port": port,
               "proxy_work_rejected_total": work_rejected,
               "sample_start_ms": (time.perf_counter() - start) * 1000,
               "proxy_sample_error": proxy_error, "backend_sample_error": ""}
        p = snapshots.get(port, {})
        for source, dest in (("inFlight", "proxy_inflight"), ("dispatched", "proxy_dispatched"),
                             ("completed", "proxy_completed"), ("errors", "proxy_errors"),
                             ("latencyEwmaMs", "proxy_latency_ewma_ms"), ("scoreMs", "proxy_score_ms"),
                             ("cpuLoad", "proxy_cpu_ewma"), ("telemetryAgeMs", "telemetry_age_ms"),
                             ("estimatedOutstandingWorkMs", "estimated_outstanding_work_ms"),
                             ("estimatedQueuedWorkMs", "estimated_queued_work_ms"),
                             ("estimatedNextSlotMs", "estimated_next_slot_ms"),
                             ("completionBudgetMs", "completion_budget_ms"),
                             ("warmup", "warmup"), ("state", "state")):
            row[dest] = p.get(source)
        try:
            status, _, body = request(port, "/metrics", timeout=1)
            if status != 200:
                raise ValueError(f"metrics status {status}")
            metrics = properties(body)
            for source, dest in (("activeRequests", "backend_active"), ("queuedRequests", "backend_queued"),
                                 ("receivedRequests", "backend_received"), ("cpuSamples", "cpu_samples"),
                                 ("cpuBudget", "cpu_budget_cores"), ("heapUsedBytes", "heap_used_bytes"),
                                 ("heapMaxBytes", "heap_max_bytes"), ("memoryLoad", "heap_ratio"),
                                 ("capacity", "capacity")):
                row[dest] = finite(metrics.get(source))
            cpu_available = (metrics.get("cpuSamples", 0) > 0
                             and finite(metrics.get("processCpuCores")) is not None
                             and metrics["processCpuCores"] >= 0
                             and finite(metrics.get("cpuLoad")) is not None and metrics["cpuLoad"] >= 0)
            row["cpu_available"] = cpu_available
            row["process_cpu_cores"] = finite(metrics.get("processCpuCores")) if cpu_available else None
            row["cpu_budget_ratio"] = finite(metrics.get("cpuLoad")) if cpu_available else None
            # Host CPU is omitted: current demo masks the OS unavailable value as zero.
            row["host_memory_ratio"] = (finite(metrics.get("hostMemoryLoad"))
                                        if metrics.get("hostMemoryTotalBytes", 0) > 0 else None)
        except (OSError, ValueError, http.client.HTTPException) as exc:
            row["backend_sample_error"] = str(exc)
        row["sample_end_ms"] = (time.perf_counter() - start) * 1000
        output.append(row)
    return output


def drain(cluster):
    def idle():
        if any(b["inFlight"] for b in cluster.monitor()["backends"]):
            return False
        for port in cluster.ports:
            status, _, body = request(port, "/metrics", timeout=1)
            if status != 200:
                return False
            m = properties(body)
            if m.get("activeRequests", 1) or m.get("queuedRequests", 1):
                return False
        return True
    until(idle, timeout=15, message="all proxy/backend work drained")


def workload(cluster, arrivals, rate, concurrency, sample_interval=0.5, telemetry=True):
    positive(rate, "rate")
    positive(concurrency, "concurrency", integer=True)
    positive(sample_interval, "sample interval")
    start = time.perf_counter() + 0.25
    stop = threading.Event()
    samples = []
    port_names = {f"backend-{p}": f"backend-{i+1}" for i, p in enumerate(cluster.ports)}

    def sampler():
        while not stop.is_set():
            samples.extend(sample_cluster(cluster, start, "measurement"))
            stop.wait(sample_interval)

    def one(item):
        due = start + item["scheduled_ms"] / 1000
        if stop.wait(max(0, due - time.perf_counter())):
            raise concurrent.futures.CancelledError("Benchmark interrupted")
        sent = time.perf_counter()
        status, backend, error = 0, "", ""
        try:
            status, headers, _ = request(cluster.port, item["path"], timeout=8)
            backend = port_names.get(headers.get("x-backend", ""), "")
        except (OSError, http.client.HTTPException) as exc:
            error = f"{type(exc).__name__}: {exc}"
        end = time.perf_counter()
        return {**item, "sent_ms": (sent - start) * 1000, "completed_ms": (end - start) * 1000,
                "client_lag_ms": max(0, sent - due) * 1000,
                "request_latency_ms": (end - sent) * 1000, "latency_ms": (end - due) * 1000,
                "status": status, "backend": backend, "error": error}

    thread = threading.Thread(target=sampler, name="benchmark-sampler") if telemetry else None
    if thread:
        thread.start()
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=concurrency)
    try:
        rows = list(pool.map(one, arrivals))
    finally:
        # Wake scheduled workers before shutdown waits, cancel jobs not yet running.
        stop.set()
        pool.shutdown(wait=True, cancel_futures=True)
        if thread:
            # Each HTTP read has a timeout; join completely so no old sampler affects another run.
            thread.join()
    return rows, samples


def summarize(rows, rate):
    positive(rate, "rate")
    for row in rows:
        for field in ("latency_ms", "completed_ms", "request_latency_ms", "client_lag_ms"):
            if finite(row[field]) is None or row[field] < 0:
                raise ValueError(f"Invalid request timing: {field}")
    success = [r for r in rows if 200 <= r["status"] < 300]
    latencies = [r["latency_ms"] for r in success]
    all_latencies = [r["latency_ms"] for r in rows]
    # Denominator includes intended arrival window AND drain time, even when errors return instantly.
    window = len(rows) / rate
    elapsed = max(window, max((r["completed_ms"] for r in rows), default=0) / 1000)
    result = {"requests": len(rows), "successes": len(success), "errors": len(rows) - len(success),
              "error_rate": (len(rows) - len(success)) / len(rows) if rows else 0, "offered_rps": rate,
              "measurement_seconds": elapsed, "success_rps": len(success) / elapsed if elapsed else 0,
              "completed_rps": len(rows) / elapsed if elapsed else 0,
              "avg_ms": statistics.fmean(latencies) if latencies else None,
              "p50_ms": percentile(latencies, .5), "p95_ms": percentile(latencies, .95),
              "p99_ms": percentile(latencies, .99), "all_outcomes_p95_ms": percentile(all_latencies, .95),
              "all_outcomes_p99_ms": percentile(all_latencies, .99),
              "request_latency_p95_ms": percentile([r["request_latency_ms"] for r in success], .95),
              "client_lag_p95_ms": percentile([r["client_lag_ms"] for r in rows], .95),
              "client_lag_max_ms": max((r["client_lag_ms"] for r in rows), default=0)}
    result["client_lag_warning"] = bool(rows) and result["client_lag_p95_ms"] > max(20, 1000 / rate)
    result.update(min_ms=min(latencies) if latencies else None,
                  max_ms=max(latencies) if latencies else None,
                  median_ms=statistics.median(latencies) if latencies else None,
                  stddev_ms=statistics.stdev(latencies) if len(latencies) > 1 else None,
                  all_outcomes_avg_ms=statistics.fmean(all_latencies) if rows else None,
                  timeout_count=sum(r["status"] == 504 or r.get("error", "").startswith(("TimeoutError:", "SocketTimeout:")) for r in rows))
    result["cv"] = result["stddev_ms"] / result["avg_ms"] if result["stddev_ms"] is not None and result["avg_ms"] else None
    return result


def backend_summary(before, after, samples, rows):
    output = []
    for first, last in zip(before, after):
        name = first["backend"]
        selected = [s for s in samples if s["backend"] == name]
        result = {"backend": name}
        for field, label in (("backend_received", "received_requests"), ("proxy_dispatched", "dispatched_requests"),
                             ("proxy_completed", "completed_upstream"), ("proxy_errors", "upstream_errors")):
            initial, final = first.get(field), last.get(field)
            result[label] = final - initial if initial is not None and final is not None else None
        result["successful_client_responses"] = sum(r["backend"] == name and 200 <= r["status"] < 300 for r in rows)
        result["telemetry_samples"] = len(selected)
        result["failed_samples"] = sum(bool(s["backend_sample_error"] or s["proxy_sample_error"]) for s in selected)
        for field in ("backend_active", "backend_queued", "proxy_inflight", "process_cpu_cores",
                      "cpu_budget_ratio", "heap_used_bytes", "heap_ratio", "proxy_latency_ewma_ms", "proxy_score_ms",
                      "estimated_outstanding_work_ms", "estimated_queued_work_ms", "estimated_next_slot_ms"):
            values = [s[field] for s in selected if finite(s.get(field)) is not None]
            result[field + "_mean"] = statistics.fmean(values) if values else None
            result[field + "_max"] = max(values) if values else None
        output.append(result)
    return output


def flatten_backend_metrics(summary, records):
    """Put the three backend summaries on the run row used for comparison/plotting."""
    result = dict(summary)
    for index, record in enumerate(records, 1):
        prefix = f"backend_{index}_"
        result[prefix + "name"] = record["backend"]
        for field, value in record.items():
            if field != "backend":
                result[prefix + field] = value
    return result


def java_major(version_text):
    match = re.search(r'version\s+"(?:1\.)?(\d+)', version_text)
    return int(match.group(1)) if match else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quick", action="store_true", help="smoke only: 120 requests, 30 warmup, one repeat")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--requests", type=int)
    parser.add_argument("--warmup-requests", type=int)
    parser.add_argument("--rate", type=float, default=60)
    parser.add_argument("--concurrency", type=int, default=64)
    parser.add_argument("--seed", type=int, default=20260927)
    parser.add_argument("--repeats", type=int)
    parser.add_argument("--sample-interval", type=float, default=0.5)
    parser.add_argument("--scenarios", nargs="+", choices=tuple(SCENARIOS), default=["homogeneous", "heterogeneous", "mixed"])
    parser.add_argument("--java", default=shutil.which("java"), help="JDK21 java executable; compiler must be adjacent")
    args = parser.parse_args()
    args.requests = args.requests if args.requests is not None else (120 if args.quick else 1200)
    args.warmup_requests = args.warmup_requests if args.warmup_requests is not None else (30 if args.quick else 300)
    args.repeats = args.repeats if args.repeats is not None else (1 if args.quick else 3)
    if (args.requests < 1 or args.warmup_requests < 1 or args.repeats < 1 or args.concurrency < 1
            or not math.isfinite(args.rate) or args.rate <= 0
            or not math.isfinite(args.sample_interval) or args.sample_interval < 0.05):
        parser.error("counts/rate must be finite and positive; sample interval >= 0.05")
    if len(set(args.scenarios)) != len(args.scenarios):
        parser.error("Duplicate scenarios are not allowed")
    if not args.java or not Path(args.java).is_file():
        parser.error("--java must name an existing Java executable")
    compiler = Path(args.java).with_name("javac.exe" if os.name == "nt" else "javac")
    if not compiler.is_file():
        parser.error("--java must belong to a JDK with javac beside it")
    version = subprocess.run([args.java, "-version"], capture_output=True, text=True, check=True)
    version_text = version.stdout + version.stderr
    if java_major(version_text) != 21:
        parser.error("--java must be from JDK 21 (the runtime is part of the fixed benchmark configuration)")
    source_files = sorted((ROOT / "src/main/java").rglob("*.java"))
    args.output = (args.output or ROOT / "benchmarks/results" / datetime.now().strftime("%Y%m%d-%H%M%S")).resolve()
    try:
        args.output.mkdir(parents=True, exist_ok=False)
    except OSError as exc:
        parser.error(f"Output must be a new writable directory: {exc}")
    # Compile a frozen source snapshot outside target/classes: IDE auto-builds cannot change this run.
    frozen_sources = []
    for source in source_files:
        copied = args.output / "source" / source.relative_to(ROOT / "src/main/java")
        copied.parent.mkdir(parents=True, exist_ok=True)
        copied.write_bytes(source.read_bytes())
        frozen_sources.append(copied)
    config_text = (ROOT / "config/application.properties").read_text(encoding="utf-8")
    (args.output / "application.properties").write_text(config_text, encoding="utf-8")
    classes = args.output / "classes"
    classes.mkdir()
    source_list = args.output / "sources.txt"
    source_list.write_text("\n".join('"' + p.as_posix() + '"' for p in frozen_sources), encoding="utf-8")
    build = subprocess.run([str(compiler), "--release", "21", "-encoding", "UTF-8", "-d", str(classes),
                            "@" + str(source_list)], capture_output=True, text=True)
    (args.output / "build.log").write_text(build.stdout + build.stderr, encoding="utf-8")
    build.check_returncode()
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    shutil.copyfile(Path(__file__), args.output / "benchmark.py")
    shutil.copyfile(ROOT / "scripts/verify_benchmark.py", args.output / "verify_benchmark.py")
    git_commit = None
    git_dirty = None
    if shutil.which("git"):
        commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True)
        if commit.returncode == 0:
            git_commit = commit.stdout.strip()
            dirty = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT, capture_output=True, text=True, check=True)
            git_dirty = bool(dirty.stdout)
        else:
            print("Git commit unavailable: not a Git checkout", file=sys.stderr)
    metadata = {"schema_version": 2, "git_commit": git_commit, "git_dirty": git_dirty,
                "limitation": "All JVMs share one physical host; CPU budget is not a quota, heap is not RSS",
                "timestamp_utc": datetime.now(timezone.utc).isoformat(), "command": sys.argv,
                "java": version_text, "java_executable": str(Path(args.java).resolve()),
                "python": sys.version, "platform": platform.platform(), "logical_cpus": os.cpu_count(),
                "requests": args.requests, "warmup_requests": args.warmup_requests, "offered_rps": args.rate,
                "concurrency": args.concurrency, "seed": args.seed, "repeats": args.repeats,
                "sample_interval": args.sample_interval, "quick": args.quick, "scenarios": args.scenarios,
                "source_sha256": {str(p.relative_to(args.output / 'source')): digest(p) for p in frozen_sources},
                "class_sha256": {str(p.relative_to(classes)): digest(p) for p in sorted(classes.rglob("*.class"))},
                "script_sha256": digest(Path(__file__)),
                "config_sha256": digest(args.output / "application.properties"),
                "verifier_sha256": digest(args.output / "verify_benchmark.py"),
                "protocol": "HTTP/1.1, one TCP connection per request, no retry",
                "latency": "scheduled arrival to response completion; includes client lag; success-only primary percentiles",
                "percentile": "nearest-rank", "cpu": "process CPU cores and CPU/budget; budget is not a quota",
                "memory": "JVM heap used/max, not process RSS; host RAM shared across all backends"}
    json_write(args.output / "metadata.json", metadata)
    summaries, backend_rows, type_rows = [], [], []
    base_order = list(STRATEGIES)
    random.Random(args.seed).shuffle(base_order)
    for repeat in range(args.repeats):
        order = base_order[repeat % 3:] + base_order[:repeat % 3]
        for scenario in args.scenarios:
            arrivals = schedule(scenario, args.requests, args.rate, args.seed + repeat)
            csv_write(args.output / f"schedule-{scenario}-{repeat+1}.csv", arrivals)
            for position, strategy in enumerate(order):
                identity = {"scenario": scenario, "repeat": repeat + 1, "strategy": strategy,
                            "order_position": position + 1}
                run = args.output / f"{repeat+1}-{scenario}-{strategy.lower()}"
                print(f"START {identity}", flush=True)
                overrides = {"proxy.read.timeout.ms": 300, "proxy.request.timeout.ms": 500} if scenario == "timeout" else None
                with Cluster(run, strategy, SCENARIOS[scenario], overrides=overrides, java=args.java, classes=classes, config_text=config_text) as cluster:
                    warm_rows, _ = workload(cluster, schedule(scenario, args.warmup_requests, args.rate,
                                                              args.seed + repeat + 10000),
                                            args.rate, args.concurrency, telemetry=False)
                    csv_write(run / "warmup.csv", warm_rows)
                    drain(cluster)
                    before = sample_cluster(cluster, time.perf_counter(), "before")
                    if scenario == "recovery":
                        with Recovery(cluster, args.requests / args.rate, run):
                            rows, samples = workload(cluster, arrivals, args.rate, args.concurrency, args.sample_interval)
                    else:
                        rows, samples = workload(cluster, arrivals, args.rate, args.concurrency, args.sample_interval)
                    csv_write(run / "requests.csv", rows)
                    csv_write(run / "telemetry.csv", samples)
                    drain(cluster)
                    after = sample_cluster(cluster, time.perf_counter(), "after")
                    csv_write(run / "boundaries.csv", before + after)
                    summary = {**identity, **summarize(rows, args.rate),
                               "warmup_errors": sum(not 200 <= r["status"] < 300 for r in warm_rows)}
                    rejected_before, rejected_after = before[0].get("proxy_work_rejected_total"), after[0].get("proxy_work_rejected_total")
                    summary["estimated_work_rejections"] = (rejected_after - rejected_before
                        if rejected_before is not None and rejected_after is not None else None)
                    run_backends = [{**identity, **row} for row in backend_summary(before, after, samples, rows)]
                    summary = flatten_backend_metrics(summary, run_backends)
                    summaries.append(summary)
                    backend_rows.extend(run_backends)
                    for kind in sorted({r["kind"] for r in rows}):
                        subset = [r for r in rows if r["kind"] == kind]
                        good = [r["latency_ms"] for r in subset if 200 <= r["status"] < 300]
                        type_rows.append({**identity, "kind": kind, "requests": len(subset), "successes": len(good),
                                          "error_rate": 1-len(good)/len(subset),
                                          "avg_ms": statistics.fmean(good) if good else None,
                                          "p50_ms": percentile(good,.5), "p95_ms": percentile(good,.95),
                                          "p99_ms": percentile(good,.99)})
                    csv_write(run / "summary.csv", [summary])
                    csv_write(run / "backends.csv", run_backends)
                    csv_write(args.output / "summary.csv", summaries)
                    csv_write(args.output / "backends.csv", backend_rows)
                    csv_write(args.output / "request_types.csv", type_rows)
                    print(f"DONE {scenario} {strategy}: {summary['success_rps']:.2f} RPS, "
                          f"p95={summary['p95_ms']}, errors={summary['errors']}, "
                          f"lag95={summary['client_lag_p95_ms']:.2f} ms", flush=True)
    aggregates = []
    for scenario in args.scenarios:
        for strategy in STRATEGIES:
            group = [r for r in summaries if r["scenario"] == scenario and r["strategy"] == strategy]
            row = {"scenario": scenario, "strategy": strategy, "runs": len(group)}
            for metric in ("success_rps", "avg_ms", "p50_ms", "p95_ms", "p99_ms", "error_rate", "client_lag_p95_ms"):
                values = [r[metric] for r in group if r[metric] is not None]
                row[metric + "_mean"] = statistics.fmean(values) if values else None
                row[metric + "_stddev"] = statistics.stdev(values) if len(values) > 1 else None
                row[metric + "_median"] = statistics.median(values) if values else None
                row[metric + "_min"] = min(values) if values else None
                row[metric + "_max"] = max(values) if values else None
            aggregates.append(row)
    csv_write(args.output / "aggregate.csv", aggregates)
    json_write(args.output / "complete.json", {"file_sha256": {
        p.relative_to(args.output).as_posix(): digest(p) for p in sorted(args.output.rglob("*")) if p.is_file()},
        "runs": len(summaries), "completed_utc": datetime.now(timezone.utc).isoformat()})
    print(f"Saved {len(summaries)} runs to {args.output}", flush=True)


if __name__ == "__main__":
    main()
