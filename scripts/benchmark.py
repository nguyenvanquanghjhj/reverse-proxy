#!/usr/bin/env python3
"""Reproducible, dependency-free HTTP benchmark with isolated backend JVMs.

This is a local experiment, not a claim of optimal scheduling. The clients, proxy,
and backends share one host; process CPU and heap differ from host CPU and memory.
"""
import argparse
import concurrent.futures
import csv
import http.client
import json
import math
import os
from pathlib import Path
import random
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
    def __init__(self, directory, strategy="ADAPTIVE", specs=((4, 20),) * 3, overrides=None):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.specs, self.strategy = tuple(specs), strategy
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
            [shutil.which("java") or "java", "-Xms32m", "-Xmx128m", "-cp", str(ROOT / "target/classes"), *args],
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
            if not (ROOT / "target/classes/com/example/proxy/Main.class").exists():
                raise RuntimeError("Build first: scripts/build.ps1 or scripts/build.sh")
            for index in range(len(self.specs)):
                self.start_backend(index)
            config = {
                "proxy.bind.host": "127.0.0.1", "proxy.port": self.port,
                "backend.servers": ",".join(f"127.0.0.1:{p}:{s[0]}" for p, s in zip(self.ports, self.specs)),
                "loadbalancer.strategy": self.strategy,
                "healthcheck.interval.ms": 250, "healthcheck.timeout.ms": 500,
                "healthcheck.recovery.successes": 2, "metrics.path": "/metrics", "metrics.stale.ms": 2000,
                "adaptive.warmup.ms": 750, "proxy.max.inflight": 256, "backend.max.inflight": 64,
                "proxy.connect.timeout.ms": 500, "proxy.read.timeout.ms": 2000, "proxy.request.timeout.ms": 4000,
            }
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

    def monitor(self):
        status, _, body = request(self.port, "/__proxy/metrics")
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
        for proc in reversed(self.processes):
            if proc.poll() is None:
                proc.terminate()
        for proc in reversed(self.processes):
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
        for log in self.logs:
            log.close()

    def __exit__(self, *_):
        self.close()


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, math.ceil(len(ordered) * fraction) - 1)]


def workload(cluster, count, rate, concurrency, seed, path="/work", mutation=None):
    rng = random.Random(seed)
    # Identical cost sequence and scheduled arrivals for all strategies.
    costs = [rng.choice((1, 1, 1, 2, 4)) for _ in range(count)]
    start = time.perf_counter() + 0.1
    rows, snapshots = [], []
    stop = threading.Event()
    control_error = []

    def sample():
        while not stop.is_set():
            try:
                snap = cluster.snapshot()
                snap["elapsed_seconds"] = time.perf_counter() - start
                snapshots.append(snap)
            except (OSError, ValueError, RuntimeError, http.client.HTTPException) as exc:
                snapshots.append({"error": str(exc), "elapsed_seconds": time.perf_counter() - start})
            stop.wait(0.25)

    def change():
        wait = start + (count / rate) / 2 - time.perf_counter()
        if not stop.wait(max(0, wait)):
            try:
                mutation()
            except Exception as exc:
                control_error.append(str(exc))

    def one(index):
        scheduled = start + index / rate
        time.sleep(max(0, scheduled - time.perf_counter()))
        sent = time.perf_counter()
        status, backend, error = 0, "", ""
        try:
            status, headers, _ = request(cluster.port, f"{path}?cost={costs[index]}")
            backend = headers.get("x-backend", "")
        except (OSError, http.client.HTTPException) as exc:
            error = type(exc).__name__ + ": " + str(exc)
        end = time.perf_counter()
        return {"index": index, "cost": costs[index], "scheduled_ms": index / rate * 1000,
                "client_lag_ms": (sent - scheduled) * 1000, "request_latency_ms": (end - sent) * 1000,
                "latency_ms": (end - scheduled) * 1000, "status": status, "backend": backend, "error": error}

    sampler = threading.Thread(target=sample, daemon=True)
    mutator = threading.Thread(target=change, daemon=True) if mutation else None
    sampler.start()
    if mutator:
        mutator.start()
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
            rows = list(pool.map(one, range(count)))
        elapsed = time.perf_counter() - start
    finally:
        stop.set()
        sampler.join(timeout=5)
        if mutator:
            mutator.join(timeout=5)
    if control_error:
        raise RuntimeError("Scenario mutation failed: " + "; ".join(control_error))
    successes = [r for r in rows if 200 <= r["status"] < 300]
    distribution = {}
    for row in successes:
        distribution[row["backend"]] = distribution.get(row["backend"], 0) + 1
    latencies = [r["latency_ms"] for r in successes]
    summary = {"requests": count, "successes": len(successes), "failures": count - len(successes),
               "elapsed_seconds": elapsed, "throughput_rps": len(successes) / elapsed,
               "avg_ms": sum(latencies) / len(latencies) if latencies else None,
               "p50_ms": percentile(latencies, .5), "p95_ms": percentile(latencies, .95), "p99_ms": percentile(latencies, .99),
               "all_outcomes_p95_ms": percentile([r["latency_ms"] for r in rows], .95),
               "client_lag_p95_ms": percentile([r["client_lag_ms"] for r in rows], .95),
               "backend_distribution": distribution,
               "status_counts": {str(s): sum(r["status"] == s for r in rows) for s in sorted({r["status"] for r in rows})}}
    return summary, rows, snapshots


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quick", action="store_true", help="short smoke experiment; too brief for reliable conclusions")
    parser.add_argument("--output", type=Path, default=ROOT / "target/benchmark-results")
    parser.add_argument("--requests", type=int)
    parser.add_argument("--rate", type=float, help="scheduled requests/second; same for each strategy")
    parser.add_argument("--concurrency", type=int, default=64)
    parser.add_argument("--seed", type=int, default=20260926)
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--scenarios", nargs="+", choices=("homogeneous", "heterogeneous", "slowdown", "cpu"),
                        default=["homogeneous", "heterogeneous", "slowdown", "cpu"])
    args = parser.parse_args()
    count, rate = args.requests or (180 if args.quick else 1200), args.rate or (90 if args.quick else 120)
    if count < 1 or rate <= 0 or args.concurrency < 1 or args.repeats < 1:
        parser.error("requests, rate, concurrency, repeats must be positive")
    args.output.mkdir(parents=True, exist_ok=True)
    summaries = []
    experiment = {"seed": args.seed, "requests": count, "offered_rps": rate, "concurrency": args.concurrency,
                  "repeats": args.repeats, "quick": args.quick,
                  "latency_definition": "scheduled arrival to complete response; includes client scheduling lag",
                  "successful_latency_only": True, "connection_reuse": False,
                  "cpu_definition": "delta process CPU seconds / delta wall seconds / capacity; clamped [0,1]",
                  "memory_definition": "JVM heap used / JVM heap max; host memory reported separately",
                  "limitations": "Single host; virtual-thread runtime/JIT/OS noise; repeat and inspect client lag; no universal winner."}
    for repeat in range(args.repeats):
        # Rotate strategy order between repeats to reduce systematic host/order effects.
        strategies = STRATEGIES[repeat % 3:] + STRATEGIES[:repeat % 3]
        for scenario in args.scenarios:
            specs = ((4, 30),) * 3 if scenario == "homogeneous" else (
                ((2, 15), (4, 45), (8, 100)) if scenario == "heterogeneous" else ((2, 20),) * 3)
            for strategy in strategies:
                run = args.output / f"{repeat + 1}-{scenario}-{strategy.lower()}"
                with Cluster(run, strategy, specs) as cluster:
                    # Identical warmup; excluded from measured rows/summary. Startup health warmup already completed.
                    workload(cluster, 45 if args.quick else 180, rate, args.concurrency, args.seed - 1,
                             path="/compute" if scenario == "cpu" else "/work")
                    mutation = None
                    if scenario == "slowdown":
                        mutation = lambda: cluster.control(0, delayMs=250)
                    elif scenario == "cpu":
                        # At most two busy cores, with capacity=2 as the explicit process CPU budget.
                        mutation = lambda: cluster.control(0, cpuWorkers=2)
                    summary, rows, snapshots = workload(cluster, count, rate, args.concurrency, args.seed,
                                                       path="/compute" if scenario == "cpu" else "/work", mutation=mutation)
                    summary.update(scenario=scenario, strategy=strategy, repeat=repeat + 1,
                                   specs=[{"capacity": s[0], "delayMs": s[1]} for s in specs])
                    with (run / "requests.csv").open("w", newline="", encoding="utf-8") as stream:
                        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
                        writer.writeheader(); writer.writerows(rows)
                    (run / "telemetry.json").write_text(json.dumps(snapshots, indent=2), encoding="utf-8")
                    (run / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
                    summaries.append(summary)
                    print(f"{scenario:13} {strategy:18} {summary['throughput_rps']:7.1f} req/s "
                          f"p95={summary['p95_ms'] or 0:8.1f} ms p99={summary['p99_ms'] or 0:8.1f} ms "
                          f"fail={summary['failures']} clientLag95={summary['client_lag_p95_ms']:.1f} ms", flush=True)
    (args.output / "results.json").write_text(json.dumps({"experiment": experiment, "runs": summaries}, indent=2), encoding="utf-8")
    print(f"Raw request CSV, telemetry, process logs, and results: {args.output.resolve()}")
    print("Compare failures and client lag alongside successful latency; quick runs are smoke checks, not proof of superiority.")


if __name__ == "__main__":
    main()
