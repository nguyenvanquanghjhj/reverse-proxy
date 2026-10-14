#!/usr/bin/env python3
"""Small fault experiment: identical requests with protection on/off, all outcomes retained.

This is a behavior experiment, not a throughput/algorithm ranking benchmark.
"""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import platform
import random
import shutil
import subprocess
import sys
import time

from benchmark import Cluster, ROOT, java_major, request, until


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(args, output, classes, config_text):
    rows, summaries = [], []
    rng = random.Random(args.seed)
    fault_paths = ["/status?code=503", "/status?code=429"]
    rng.shuffle(fault_paths)
    for repeat in range(args.repeats):
        for feature in ("circuit", "rate"):
            for enabled in (False, True):
                name = f"{repeat}-{feature}-{'on' if enabled else 'off'}"
                overrides = {"adaptive.warmup.ms": 0, "healthcheck.recovery.successes": 1,
                             "proxy.circuit-breaker.enabled": str(enabled and feature == "circuit").lower(),
                             "proxy.circuit-breaker.failure-threshold": 2, "proxy.circuit-breaker.open-duration.ms": 1000,
                             "proxy.rate-limit.enabled": str(enabled and feature == "rate").lower(),
                             "proxy.rate-limit.requests-per-second": .1, "proxy.rate-limit.burst": 3,
                             "proxy.rate-limit.idle.ms": 30000}
                with Cluster(output / name, specs=((4, 0),), overrides=overrides, java=args.java,
                             classes=classes, config_text=config_text) as cluster:
                    before = cluster.monitor()
                    def send(path, expected_status, dispatched):
                        start = time.perf_counter()
                        error = None
                        try:
                            status, headers, body = request(cluster.port, path)
                        except Exception as exc:
                            status, headers, body, error = 0, {}, b'', exc
                        row = dict(run=name, sequence=sum(r['run'] == name for r in rows), path=path,
                                   status=status, latency_ms=(time.perf_counter()-start)*1000,
                                   backend=headers.get("x-proxy-backend", ""), retry_after=headers.get("retry-after", ""),
                                   body_sha256=hashlib.sha256(body).hexdigest(), error=str(error) if error else '')
                        rows.append(row)
                        # Persist before assertions, including the first failing request.
                        with (output / 'requests.csv').open('w', newline='', encoding='utf-8') as handle:
                            writer = csv.DictWriter(handle, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
                        if error:
                            raise error
                        if status != expected_status or bool(row['backend']) != dispatched:
                            raise AssertionError(f"Unexpected outcome: {row}")
                    if feature == "circuit":
                        for path in fault_paths:
                            send(path, int(path.rsplit('=', 1)[1]), True)
                        opened = cluster.monitor()
                        if enabled and opened['backends'][0]['circuitState'] != 'OPEN':
                            raise AssertionError("Circuit did not open")
                        for _ in range(6):
                            send("/work?cost=1", 503 if enabled else 200, not enabled)
                        # Both arms use the same cooldown barrier before recovery request.
                        time.sleep(1.05)
                        send("/work?cost=1", 200, True)
                    else:
                        for index in range(9):
                            send("/work?cost=1", 429 if enabled and index >= 3 else 200, not enabled or index < 3)
                        opened = None
                    def idle_snapshot():
                        metrics = cluster.monitor()
                        # The monitoring request itself is an active handler.
                        idle = metrics['in_flight'] == 1 and all(b['inFlight'] == 0 for b in metrics['backends'])
                        return metrics if idle else None
                    after = until(idle_snapshot, message="handler cleanup")
                    backend = after['backends'][0]
                    expected_dispatches = 3 if enabled else 9
                    if backend['dispatched'] - before['backends'][0]['dispatched'] != expected_dispatches:
                        raise AssertionError("Dispatch count does not match expected protection")
                    if backend['inFlight'] != 0 or backend['circuitState'] != 'CLOSED':
                        raise AssertionError("Lease leak or circuit did not recover")
                    summaries.append(dict(run=name, before=before, opened=opened, after=after))
                (output / 'snapshots.json').write_text(json.dumps(summaries, indent=2, allow_nan=False), encoding='utf-8')
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seed', type=int, default=2026)
    parser.add_argument('--repeats', type=int, default=2)
    args = parser.parse_args()
    if args.repeats < 1 or not args.java or not Path(args.java).is_file():
        parser.error('Positive repeats and an existing --java executable are required')
    version = subprocess.run([args.java, '-version'], capture_output=True, text=True, check=True)
    version_text = version.stdout + version.stderr
    if java_major(version_text) != 21:
        parser.error('Use JDK 21; runtime is part of the experiment')
    compiler = Path(args.java).with_name('javac.exe' if os.name == 'nt' else 'javac')
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=False)
    sources = []
    for source in sorted((ROOT / 'src/main/java').rglob('*.java')):
        copied = output / 'source' / source.relative_to(ROOT / 'src/main/java')
        copied.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(source, copied); sources.append(copied)
    classes = output / 'classes'; classes.mkdir()
    source_list = output / 'sources.txt'
    source_list.write_text('\n'.join('"' + p.as_posix() + '"' for p in sources), encoding='utf-8')
    build = subprocess.run([str(compiler), '--release', '21', '-encoding', 'UTF-8', '-d', str(classes), '@'+str(source_list)],
                           capture_output=True, text=True)
    (output / 'build.log').write_text(build.stdout + build.stderr, encoding='utf-8'); build.check_returncode()
    config_text = (ROOT / 'config/application.properties').read_text(encoding='utf-8')
    (output / 'application.properties').write_text(config_text, encoding='utf-8')
    for script in (Path(__file__), ROOT / 'scripts/benchmark.py'):
        shutil.copyfile(script, output / script.name)
    metadata = dict(seed=args.seed, repeats=args.repeats, java=version_text, python=sys.version, platform=platform.platform(),
                    command=sys.argv, kind='behavior experiment; not a performance ranking',
                    sha256={str(p.relative_to(output)): digest(p) for p in output.rglob('*') if p.is_file()})
    (output / 'metadata.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    try:
        rows = run(args, output, classes, config_text)
    except Exception as exc:
        (output / 'validation.json').write_text(json.dumps(dict(passed=False, error=str(exc))), encoding='utf-8')
        raise
    (output / 'validation.json').write_text(json.dumps(dict(passed=True, requests=len(rows),
        sha256={name: digest(output / name) for name in ('requests.csv', 'snapshots.json', 'metadata.json')}), indent=2), encoding='utf-8')
    print(f'PASS: {len(rows)} requests, all outcomes retained: {output}')


if __name__ == '__main__':
    main()
