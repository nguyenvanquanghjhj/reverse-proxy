"""Check completed benchmark CSVs without running servers or changing the results."""
import csv
import hashlib
import json
import math
from pathlib import Path
import sys
import benchmark


def read(path):
    with path.open(newline='', encoding='utf-8') as stream:
        return list(csv.DictReader(stream))


def verify(root):
    metadata = json.loads((root / 'metadata.json').read_text(encoding='utf-8'))
    complete = json.loads((root / 'complete.json').read_text(encoding='utf-8'))
    summaries = read(root / 'summary.csv')
    backends = read(root / 'backends.csv')
    assert len(summaries) == complete['runs'], 'Incomplete summary'
    assert complete['runs'] == len(metadata['scenarios']) * metadata['repeats'] * len(benchmark.STRATEGIES), 'Wrong run count'
    required_summary = {'success_rps', 'avg_ms', 'p50_ms', 'p95_ms', 'p99_ms', 'error_rate'}
    assert all(required_summary <= set(row) for row in summaries), 'Missing overall summary metrics'
    groups = {}
    total = 0
    for summary in summaries:
        key = summary['scenario'], summary['repeat']
        groups.setdefault(key, []).append(summary['strategy'])
        run = root / f"{summary['repeat']}-{summary['scenario']}-{summary['strategy'].lower()}"
        required_files = tuple(run / name for name in ('requests.csv', 'telemetry.csv', 'summary.csv'))
        assert all(path.is_file() for path in required_files), f'Missing required run CSV in {run.name}'
        run_summaries = read(run / 'summary.csv')
        assert run_summaries == [summary], f'Per-run and root summary differ in {run.name}'
        rows = read(run / 'requests.csv')
        telemetry = read(run / 'telemetry.csv')
        assert telemetry, f'No telemetry samples in {run.name}'
        expected = read(root / f"schedule-{summary['scenario']}-{summary['repeat']}.csv")
        assert len(rows) == len(expected) == metadata['requests']
        for actual, planned in zip(rows, expected):
            assert all(actual[k] == planned[k] for k in ('index', 'kind', 'path', 'scheduled_ms')), 'Different arrival schedule'
            actual['status'] = int(actual['status'])
            for field in ('latency_ms', 'completed_ms', 'request_latency_ms', 'client_lag_ms'):
                actual[field] = float(actual[field])
        recomputed = benchmark.summarize(rows, metadata['offered_rps'])
        for field in ('successes', 'errors', 'error_rate', 'success_rps', 'avg_ms', 'p50_ms', 'p95_ms', 'p99_ms'):
            if recomputed[field] is None:
                assert summary[field] == ''
            else:
                assert math.isclose(float(summary[field]), recomputed[field], rel_tol=1e-10, abs_tol=1e-8), field
        selected = [r for r in backends if all(r[k] == summary[k] for k in ('scenario', 'repeat', 'strategy'))]
        assert len(selected) == 3
        for index, b in enumerate(selected, 1):
            received, dispatched = float(b['received_requests']), float(b['dispatched_requests'])
            assert 0 <= received <= dispatched, 'Counter boundary mismatch'
            prefix = f'backend_{index}_'
            for field in ('received_requests', 'backend_active_mean', 'backend_active_max',
                          'proxy_inflight_mean', 'proxy_inflight_max', 'process_cpu_cores_mean',
                          'process_cpu_cores_max', 'heap_used_bytes_mean', 'heap_used_bytes_max'):
                assert prefix + field in summary, f'Missing {prefix + field}'
                assert summary[prefix + field] == b[field], f'Flattened backend metric differs: {prefix + field}'
        assert sum(float(b['dispatched_requests']) for b in selected) <= len(rows)
        assert sum(int(b['successful_client_responses']) for b in selected) == recomputed['successes']
        total += len(rows)
    for group in groups.values():
        assert sorted(group) == sorted(benchmark.STRATEGIES), 'Missing/duplicate strategy in a matched repeat'
    script_hash = hashlib.sha256((Path(__file__).parent / 'benchmark.py').read_bytes()).hexdigest()
    assert script_hash == metadata['script_sha256'], 'Runner differs from recorded version; review before recomputing'
    print(f'PASS: {len(summaries)} matched runs, {total} measured requests; schedules, counters and summary statistics agree.')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: python scripts/verify_benchmark.py PATH_TO_RESULT_FOLDER')
    verify(Path(sys.argv[1]))
