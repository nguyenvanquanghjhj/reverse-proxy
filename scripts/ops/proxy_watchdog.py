#!/usr/bin/env python3
"""Read-only continuous proxy health and SLO monitor.

Uses existing /__proxy/metrics. Detects peer DOWN, sustained error-rate spikes,
missing proxy and latency p95 regression. Emits timestamped JSONL events, CSV
snapshots and a current status file for operator review; NEVER auto-restarts.
"""
import argparse
import csv
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import time
import urllib.request
import urllib.error
import docker_operator
from urllib.parse import urlsplit

FIELDS = ["utc", "state", "completed", "failed", "interval_error_rate", "rolling_p95_ms", "rolling_sample_count", "backends_down", "detail"]

def utc(): return datetime.now(timezone.utc).isoformat()

def get_metrics(url, timeout):
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "PBL4-Watchdog/1"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 200: raise RuntimeError("Metrics HTTP " + str(response.status))
        raw = response.read(2 * 1024 * 1024 + 1)
        if len(raw) > 2 * 1024 * 1024: raise RuntimeError("Metrics exceeds 2 MiB")
        value = json.loads(raw)
        if not isinstance(value, dict): raise ValueError("Metrics JSON must be an object")
        return value

def summarize(snapshot, previous, max_error_rate, max_p95, fail_on_backend_down):
    traffic = snapshot.get("traffic", {}).get("total", {})
    completed = int(traffic.get("completed", 0))
    failed = int(traffic.get("failed", 0))
    latency = traffic.get("latencyMs", {})
    p95 = latency.get("p95")
    samples = int(latency.get("samples", 0))
    down = [str(b.get("id", "?")) for b in snapshot.get("backends", [])
            if b.get("state") == "DOWN"]
    if previous is None:
        window_rate = None
        total_diff = 0
    else:
        total_diff = completed - previous[0]
        error_diff = failed - previous[1]
        # JVM restart or counter anomaly: wait for a new interval, no negative-rate fiction.
        window_rate = (error_diff / total_diff
                       if total_diff > 0 and 0 <= error_diff <= total_diff else None)
    reasons = []
    if window_rate is not None and window_rate > max_error_rate: reasons.append("ERROR_RATE")
    if total_diff > 0 and samples >= 20 and p95 is not None and float(p95) > max_p95:
        reasons.append("P95")
    if fail_on_backend_down and down: reasons.append("BACKEND_DOWN")
    return {"completed": completed, "failed": failed, "window_error_rate": window_rate,
            "interval_error_rate": window_rate, "interval_completed": max(0,total_diff),
            "p95_ms": p95, "p95_scope": "rolling last <=2048 completed exchanges",
            "samples": samples, "backends_down": down,
            "reasons": reasons}, (completed, failed)

class StateMachine:
    def __init__(self, consecutive):
        self.consecutive = consecutive
        self.streak = 0
        self.state = "UNKNOWN"

    def update(self, reasons):
        unhealthy = bool(reasons)
        self.streak = self.streak + 1 if unhealthy else 0
        wanted = "ALERT" if self.streak >= self.consecutive else "OK"
        if self.state == wanted: return None
        before = self.state
        self.state = wanted
        return {"event": "STATUS_CHANGED", "previous": before, "current": wanted, "reasons": reasons}

def write_latest(path, value):
    tmp = path.with_name("." + path.name + ".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)

def monitor(url, interval, timeout, count, out, max_error_rate, max_p95, consecutive, fail_on_backend_down, compose_file=None):
    out.mkdir(parents=True, exist_ok=True)
    csv_path = out / "watchdog.csv"
    event_path = out / "watchdog-events.jsonl"
    prev = None
    machine = StateMachine(consecutive)
    was_new = not csv_path.exists() or csv_path.stat().st_size == 0
    with csv_path.open("a", newline="", encoding="utf-8") as csv_file, event_path.open("a", encoding="utf-8") as events:
        writer = csv.DictWriter(csv_file, fieldnames=FIELDS)
        if was_new: writer.writeheader()
        iteration = 0
        try:
            while count == 0 or iteration < count:
                iteration += 1
                now = utc()
                try:
                    metrics = (docker_operator.metrics(compose_file, timeout) if compose_file
                               else get_metrics(url, timeout))
                    summary, prev = summarize(metrics, prev, max_error_rate, max_p95, fail_on_backend_down)
                    reasons = summary["reasons"]
                except (OSError, ValueError, RuntimeError, KeyError, TypeError) as exc:
                    reasons = ["METRICS_UNREACHABLE"]
                    summary = {"completed": None, "failed": None, "window_error_rate": None,
                               "p95_ms": None, "samples": 0, "backends_down": [],
                               "reasons": reasons, "detail": type(exc).__name__}
                    prev = None
                changed = machine.update(reasons)
                record = {"utc": now, "state": machine.state, **summary}
                write_latest(out / "watchdog-latest.json", record)
                writer.writerow({"utc": now, "state": machine.state,
                                 "completed": summary["completed"], "failed": summary["failed"],
                                 "interval_error_rate": summary["window_error_rate"],
                                 "rolling_p95_ms": summary["p95_ms"], "rolling_sample_count": summary["samples"],
                                 "backends_down": ";".join(summary["backends_down"]),
                                 "detail": ";".join(reasons)})
                csv_file.flush()
                if changed is not None:
                    event = {"utc": now, **changed}
                    events.write(json.dumps(event, ensure_ascii=False) + "\n")
                    events.flush()
                    print(json.dumps(event, ensure_ascii=False), flush=True)
                if count == 0 or iteration < count: time.sleep(interval)
        except KeyboardInterrupt:
            print("Stopped by operator", flush=True)
    return 1 if machine.state == "ALERT" else 0

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8080/__proxy/metrics")
    parser.add_argument("--compose-file", type=Path,
                        help="Read metrics by docker compose exec inside the proxy container")
    parser.add_argument("--interval", type=float, default=2.0)
    parser.add_argument("--timeout", type=float, default=1.0)
    parser.add_argument("--samples", type=int, default=15, help="0 = continuously until Ctrl+C")
    parser.add_argument("--out", type=Path, default=Path.cwd().parent / "pbl4-operations")
    parser.add_argument("--max-error-rate", type=float, default=0.10)
    parser.add_argument("--max-p95-ms", type=float, default=1500.0)
    parser.add_argument("--consecutive", type=int, default=3)
    parser.add_argument("--alert-on-backend-down", action="store_true")
    a = parser.parse_args(argv)
    parsed = urlsplit(a.url)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"} \
        or parsed.username or parsed.password or parsed.path != "/__proxy/metrics":
        parser.error("Only a local HTTP /__proxy/metrics endpoint is supported (no credentials)")
    if a.interval < .1 or a.timeout < .1 or a.samples < 0 or a.consecutive < 1 or not 0 <= a.max_error_rate <= 1 or a.max_p95_ms <= 0:
        parser.error("Invalid numeric limits")
    return monitor(a.url, a.interval, a.timeout, a.samples, a.out.resolve(),
                   a.max_error_rate, a.max_p95_ms, a.consecutive, a.alert_on_backend_down,
                   a.compose_file.resolve() if a.compose_file else None)

if __name__ == "__main__": raise SystemExit(main())
