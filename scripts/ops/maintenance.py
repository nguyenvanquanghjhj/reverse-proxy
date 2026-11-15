#!/usr/bin/env python3
"""Gracefully drain or resume one backend without terminating user data processes.

Requires a healthy alternative before drain; waits for leases to reach zero.
Never kills a process. Operator does any actual maintenance separately.
"""
import argparse
import json
from pathlib import Path
import os
import sys
import time
import urllib.request

URL = "http://127.0.0.1:8080/__proxy/"

def operator_headers():
    headers = {"X-Proxy-Action": "set-backend-draining"}
    token = os.environ.get("PBL4_CONTROL_TOKEN")
    if token: headers["X-PBL4-Control-Token"] = token
    return headers

def get_status():
    with urllib.request.urlopen(URL + "metrics", timeout=3) as stream:
        body = json.load(stream)
    values = body.get("backends")
    if not isinstance(values, list): raise RuntimeError("No backends list in proxy metrics")
    return values

def change(backend, draining):
    headers = operator_headers()
    headers.update({"X-Proxy-Target": backend, "X-Proxy-Draining": str(draining).lower()})
    request = urllib.request.Request(URL + "backend-drain", data=b"", method="POST", headers=headers)
    with urllib.request.urlopen(request, timeout=3) as stream: return json.load(stream)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("action", choices=["status", "drain", "resume"])
    p.add_argument("--backend", default="127.0.0.1:9001")
    p.add_argument("--timeout", type=float, default=20)
    a = p.parse_args()
    if a.timeout < .5 or a.timeout > 120: p.error("Timeout must be 0.5..120")
    if a.action != "status" and a.backend not in [b.get("id") for b in get_status()]:
        p.error("Backend is not in current proxy configuration")
    if a.action == "status":
        print(json.dumps(get_status(), indent=2));return 0
    before = get_status()
    if a.action == "drain":
        alternatives = [b for b in before if b.get("id") != a.backend and b.get("state") in {"UP", "WARMING"}
                        and not b.get("draining")]
        if not alternatives:
            print("REFUSED: draining would leave no healthy alternative", file=sys.stderr)
            return 2
    change(a.backend, a.action == "drain")
    target = "DRAINING" if a.action == "drain" else "UP"
    deadline = time.monotonic() + a.timeout
    while time.monotonic() < deadline:
        matches = [b for b in get_status() if b.get("id") == a.backend]
        if not matches: raise RuntimeError("Backend removed from configuration")
        state = matches[0]
        if a.action == "drain" and state.get("draining") and int(state.get("inFlight", -1)) == 0:
            print("DRAIN COMPLETE: no new leases and in-flight = 0", a.backend);return 0
        if a.action == "resume" and not state.get("draining") and state.get("state") in {"UP", "WARMING"}:
            print("RESUMED", a.backend);return 0
        time.sleep(.25)
    print("TIMEOUT: inspect proxy metrics and backend status before other operations", file=sys.stderr)
    return 1

if __name__ == "__main__":
    try: raise SystemExit(main())
    except (OSError, RuntimeError, ValueError) as ex:
        print("ERROR:", type(ex).__name__, str(ex), file=sys.stderr);raise SystemExit(2)
