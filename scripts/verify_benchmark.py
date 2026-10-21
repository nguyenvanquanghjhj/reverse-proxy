"""Verify complete benchmark artifacts; raises ValueError even when Python uses -O."""
import csv
import hashlib
import json
import math
import statistics
from pathlib import Path
import sys
import benchmark


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    with path.open(newline='', encoding='utf-8') as stream:
        rows = list(csv.DictReader(stream))
    for row in rows:
        for value in row.values():
            require(value is not None, f'Malformed CSV: {path}')
            try:
                numeric = float(value)
            except ValueError:
                continue
            require(math.isfinite(numeric), f'Nonfinite CSV: {path}')
    return rows


def read_json(path):
    def invalid(token):
        raise ValueError(f'Nonfinite JSON: {path}: {token}')
    return json.loads(path.read_text(encoding='utf-8'), parse_constant=invalid)


def checked_path(root, relative):
    path = root / relative.replace('\\', '/')
    require(path.resolve().is_relative_to(root.resolve()), 'Artifact path escapes result folder')
    require(path.is_file() and not path.is_symlink(), f'Missing/linked artifact: {relative}')
    return path


def match(actual, expected, label):
    for field, value in expected.items():
        require(field in actual, f'Missing {label}.{field}')
        if value is None:
            equal = actual[field] == ''
        elif isinstance(value, bool):
            equal = actual[field] == str(value)
        elif isinstance(value, (int, float)):
            equal = math.isfinite(float(actual[field])) and math.isclose(float(actual[field]), value, rel_tol=1e-10, abs_tol=1e-8)
        else:
            equal = actual[field] == str(value)
        require(equal, f'Mismatch {label}.{field}')


def numbers(row):
    result = {}
    for key, value in row.items():
        if value == '':
            result[key] = None
        else:
            try:
                result[key] = float(value)
            except ValueError:
                result[key] = value
    return result


def verify(root):
    root = Path(root)
    metadata = read_json(root / 'metadata.json')
    complete = read_json(root / 'complete.json')
    schema = metadata.get('schema_version', 1)
    if schema >= 2:
        manifest = complete.get('file_sha256', {})
        require(manifest, 'Missing artifact manifest')
        actual_files = {p.relative_to(root).as_posix() for p in root.rglob('*')
                        if p.is_file() and p.name != 'complete.json' and '__pycache__' not in p.parts}
        require(actual_files == set(manifest), 'Artifact set differs from completion manifest')
        for relative, expected in manifest.items():
            require(digest(checked_path(root, relative)) == expected, f'Artifact hash mismatch: {relative}')
    for directory, key in (('source', 'source_sha256'), ('classes', 'class_sha256')):
        require(metadata.get(key), f'Missing {key}')
        for relative, expected in metadata[key].items():
            require(digest(checked_path(root, directory + '/' + relative)) == expected, f'{directory} hash mismatch: {relative}')
    require(digest(root / 'application.properties') == metadata['config_sha256'], 'Config hash mismatch')
    script = root / 'benchmark.py' if schema >= 2 else Path(__file__).with_name('benchmark.py')
    require(digest(script) == metadata['script_sha256'], 'Runner hash mismatch')
    if schema >= 2:
        require(digest(root / 'verify_benchmark.py') == metadata['verifier_sha256'], 'Verifier hash mismatch')
        require(digest(Path(benchmark.__file__)) == metadata['script_sha256'],
                'Run the frozen verify_benchmark.py in the result directory to use its matching runner')
    summaries = read(root / 'summary.csv')
    backends = read(root / 'backends.csv')
    expected_keys = {(scenario, str(repeat), strategy) for scenario in metadata['scenarios']
                     for repeat in range(1, metadata['repeats'] + 1) for strategy in benchmark.STRATEGIES}
    identities = [(r['scenario'], r['repeat'], r['strategy']) for r in summaries]
    require(len(summaries) == complete['runs'] == len(expected_keys) and set(identities) == expected_keys,
            'Missing/duplicate run')
    require(len(backends) == len(summaries) * 3, 'Wrong backend summary count')
    require((root / 'aggregate.csv').is_file() and (root / 'request_types.csv').is_file(), 'Incomplete aggregate outputs')
    require(len(read(root / 'aggregate.csv')) == len(metadata['scenarios']) * len(benchmark.STRATEGIES), 'Incomplete aggregates')
    type_rows = read(root / 'request_types.csv')
    expected_type_keys = set()
    total = 0
    for summary in summaries:
        run = root / f"{summary['repeat']}-{summary['scenario']}-{summary['strategy'].lower()}"
        for name in ('requests.csv', 'telemetry.csv', 'summary.csv', 'warmup.csv', 'boundaries.csv', 'backends.csv', 'proxy.properties', 'proxy.log'):
            require((run / name).is_file(), f'Missing {run.name}/{name}')
        require(read(run / 'summary.csv') == [summary], f'Per-run summary differs: {run.name}')
        rows = read(run / 'requests.csv')
        telemetry = read(run / 'telemetry.csv')
        require(telemetry, f'Empty telemetry: {run.name}')
        expected = read(root / f"schedule-{summary['scenario']}-{summary['repeat']}.csv")
        planned = benchmark.schedule(summary['scenario'], metadata['requests'], metadata['offered_rps'], metadata['seed'] + int(summary['repeat']) - 1)
        require(len(rows) == len(expected) == len(planned), 'Request count mismatch')
        for actual, saved, generated in zip(rows, expected, planned):
            match(saved, generated, 'schedule')
            match(actual, generated, 'request schedule')
            actual['status'] = int(actual['status'])
            require(actual['status'] == 0 or 100 <= actual['status'] <= 599, 'Invalid HTTP status')
            for field in ('latency_ms', 'completed_ms', 'request_latency_ms', 'client_lag_ms'):
                actual[field] = float(actual[field])
            if 'sent_ms' in actual:
                sent, due = float(actual['sent_ms']), generated['scheduled_ms']
                require(sent >= due - 1e-6, 'Request sent before scheduled arrival')
                require(math.isclose(actual['completed_ms'] - due, actual['latency_ms'], abs_tol=1e-5), 'Latency accounting mismatch')
                require(math.isclose(actual['completed_ms'] - sent, actual['request_latency_ms'], abs_tol=1e-5), 'Request latency mismatch')
                require(math.isclose(max(0, sent-due), actual['client_lag_ms'], abs_tol=1e-5), 'Client lag mismatch')
        recomputed = benchmark.summarize(rows, metadata['offered_rps'])
        match(summary, {k: v for k, v in recomputed.items() if schema >= 2 or k in summary}, 'summary')
        selected_types = [r for r in type_rows if all(r[k] == summary[k] for k in ('scenario', 'repeat', 'strategy'))]
        kinds = {r['kind'] for r in rows}
        require(len(selected_types) == len(kinds) and {r['kind'] for r in selected_types} == kinds,
                'Missing/duplicate request type')
        for kind in kinds:
            expected_type_keys.add((summary['scenario'], summary['repeat'], summary['strategy'], kind))
            subset = [r for r in rows if r['kind'] == kind]
            good = [r['latency_ms'] for r in subset if 200 <= r['status'] < 300]
            stored = next(r for r in selected_types if r['kind'] == kind)
            match(stored, {'requests': len(subset), 'successes': len(good), 'error_rate': 1-len(good)/len(subset),
                          'avg_ms': statistics.fmean(good) if good else None,
                          'p50_ms': benchmark.percentile(good,.5), 'p95_ms': benchmark.percentile(good,.95),
                          'p99_ms': benchmark.percentile(good,.99)}, 'request_type')
        warmup = read(run / 'warmup.csv')
        warm_plan = benchmark.schedule(summary['scenario'], metadata['warmup_requests'], metadata['offered_rps'], metadata['seed'] + int(summary['repeat']) - 1 + 10000)
        require(len(warmup) == len(warm_plan), 'Warmup count mismatch')
        for actual, expected_warm in zip(warmup, warm_plan):
            match(actual, expected_warm, 'warmup schedule')
        match(summary, {'warmup_errors': sum(not 200 <= int(r['status']) < 300 for r in warmup)}, 'warmup')
        selected = [r for r in backends if all(r[k] == summary[k] for k in ('scenario', 'repeat', 'strategy'))]
        require(len(selected) == 3 and read(run / 'backends.csv') == selected, 'Backend summary mismatch')
        boundaries = [numbers(r) for r in read(run / 'boundaries.csv')]
        before = [r for r in boundaries if r['phase'] == 'before']
        after = [r for r in boundaries if r['phase'] == 'after']
        names = {f'backend-{i}' for i in range(1, 4)}
        require(len(before) == len(after) == 3 and {r['backend'] for r in before} == {r['backend'] for r in after} == names, 'Invalid boundaries')
        before.sort(key=lambda r: r['backend'])
        after.sort(key=lambda r: r['backend'])
        require({r['backend'] for r in telemetry} == names, 'Missing backend telemetry')
        for row in boundaries:
            for field in ('backend_active', 'backend_queued', 'proxy_inflight'):
                require(row.get(field) == 0, f'Boundary not drained: {field}')
        computed_backends = benchmark.backend_summary(before, after, [numbers(r) for r in telemetry], rows)
        for index, (stored, computed) in enumerate(zip(selected, computed_backends), 1):
            match(stored, computed, 'backend')
            received, dispatched, completed = (computed[k] for k in ('received_requests', 'dispatched_requests', 'completed_upstream'))
            require(received is not None and 0 <= received <= dispatched == completed, 'Backend counter mismatch')
            require(0 <= computed['upstream_errors'] <= completed, 'Invalid backend error count')
            match(summary, {f'backend_{index}_{k if k != "backend" else "name"}': v for k, v in computed.items()}, 'flattened backend')
        require(sum(float(b['dispatched_requests']) for b in selected) <= len(rows), 'Dispatch count exceeds arrivals')
        require(sum(int(b['successful_client_responses']) for b in selected) == recomputed['successes'], 'Success count mismatch')
        if summary['scenario'] == 'recovery' and schema >= 2:
            events = read(run / 'events.csv')
            require([e['healthy'] for e in events] == ['False', 'True'], 'Missing outage/recovery events')
        total += len(rows)
    require(len(type_rows) == len(expected_type_keys) and
            {(r['scenario'],r['repeat'],r['strategy'],r['kind']) for r in type_rows} == expected_type_keys,
            'Unexpected request type rows')
    aggregates = read(root / 'aggregate.csv')
    for scenario in metadata['scenarios']:
        for strategy in benchmark.STRATEGIES:
            selected = [r for r in aggregates if r['scenario'] == scenario and r['strategy'] == strategy]
            require(len(selected) == 1, 'Missing/duplicate aggregate')
            group = [r for r in summaries if r['scenario'] == scenario and r['strategy'] == strategy]
            expected = {'runs': len(group)}
            for field in ('success_rps','avg_ms','p50_ms','p95_ms','p99_ms','error_rate','client_lag_p95_ms'):
                values = [float(r[field]) for r in group if r[field] != '']
                for suffix, operation in (('mean',statistics.fmean),('stddev',statistics.stdev),
                                          ('median',statistics.median),('min',min),('max',max)):
                    expected[field+'_'+suffix] = operation(values) if len(values) >= (2 if suffix == 'stddev' else 1) else None
            match(selected[0], {k:v for k,v in expected.items() if schema >= 2 or k in selected[0]}, 'aggregate')
    print(f'PASS: {len(summaries)} matched runs, {total} requests; hashes, schedules, counters and statistics agree.')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: python scripts/verify_benchmark.py PATH_TO_RESULT_FOLDER')
    verify(Path(sys.argv[1]))
