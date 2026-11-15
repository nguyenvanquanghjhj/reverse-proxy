#!/usr/bin/env python3
"""Collect safe runtime diagnostics without user data, SQL rows or secrets."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import urllib.request
import urllib.error
import docker_operator

ROOT = Path(__file__).resolve().parents[2]

def fetch_json(url, limit=1024*1024):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"Accept": "application/json"}), timeout=2) as response:
            raw = response.read(limit + 1)
            if len(raw) > limit: raise RuntimeError("Too many response bytes")
            try: body = json.loads(raw)
            except ValueError: body = {"unparseable": True}
            return {"http": response.status, "body": body}
    except urllib.error.HTTPError as response:
        return {"http": response.code, "body": "HTTP error"}
    except Exception as exc:
        return {"http": None, "error_type": type(exc).__name__}

def hash_source(root):
    paths = [root / "pom.xml", root / "config/application.properties"]
    paths += sorted((root / "src/main/java").rglob("*.java"))
    paths += sorted((root / "src/test/java").rglob("*.java"))
    paths += sorted(p for p in (root / "src/main/resources").rglob("*") if p.is_file())
    paths += sorted(p for p in (root / "db/migrations").rglob("*") if p.is_file())
    hashes = {}
    for path in paths:
        if path.is_file():
            hashes[str(path.relative_to(root)).replace("\\", "/")] = hashlib.sha256(path.read_bytes()).hexdigest()
    return hashes

def git_head(root):
    try:
        result = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, text=True, capture_output=True, timeout=3)
        return result.stdout.strip() if result.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired): return None

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--out", type=Path, default=ROOT.parent / "pbl4-operations")
    parser.add_argument("--proxy-port", type=int, default=8080)
    parser.add_argument("--compose-file", type=Path,
                        help="Read protected metrics via docker compose exec")
    parser.add_argument("--backend-ports", nargs="*", type=int, default=[9001, 9002, 9003])
    a = parser.parse_args()
    if not 1 <= a.proxy_port <= 65535 or any(not 1 <= p <= 65535 for p in a.backend_ports):
        parser.error("Invalid port")
    root = a.root.resolve()
    a.out.mkdir(parents=True, exist_ok=True)
    origin = f"http://127.0.0.1:{a.proxy_port}"
    if a.compose_file:
        try:
            metrics = docker_operator.metrics(a.compose_file.resolve())
            runtime = {"proxy_metrics": {"http": 200, "body": metrics}}
            for backend in metrics.get("backends", []):
                state = backend.get("state", "UNKNOWN")
                runtime["backend_" + str(backend.get("id", "unknown"))] = {
                    "http": 503 if state == "DOWN" else 200,
                    "body": {"state": state, "capabilities": backend.get("capabilities", {})}}
        except (OSError, ValueError, RuntimeError) as exc:
            runtime = {"proxy_metrics": {"http": None, "error_type": type(exc).__name__}}
    else:
        runtime = {"proxy_health": fetch_json(origin + "/__proxy/health"),
                   "proxy_metrics": fetch_json(origin + "/__proxy/metrics")}
        for port in a.backend_ports:
            runtime["backend_" + str(port)] = fetch_json(f"http://127.0.0.1:{port}/ready")
    result = {"created_at_utc": datetime.now(timezone.utc).isoformat(),
              "scope": "only fixed loopback endpoints and tracked SOURCE hashes; no DB rows/credentials",
              "git_head": git_head(root), "source_sha256": hash_source(root), "runtime": runtime}
    filename = a.out / ("diagnostics-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + ".json")
    filename.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Wrote", filename)
    failed = [key for key, val in runtime.items() if val.get("http") != 200]
    if failed:
        print("NOT HEALTHY:", ", ".join(failed), file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__": raise SystemExit(main())
