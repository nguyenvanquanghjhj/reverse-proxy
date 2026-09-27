"""Read-only BEFORE/AFTER inputs; write comparison artifacts next to this file.
Run from repository root: python benchmarks/results/ect-mixed-official-20260927/compare.py
"""
import csv
import hashlib
import json
import statistics
from collections import Counter
from pathlib import Path

AFTER = Path(__file__).resolve().parent
BEFORE = AFTER.parent / 'official-20260927'
STRATEGIES = ('ROUND_ROBIN', 'LEAST_CONNECTIONS', 'ADAPTIVE')


def read(path):
    with path.open(encoding='utf-8', newline='') as stream:
        return list(csv.DictReader(stream))


def write(name, rows):
    with (AFTER / name).open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def median(rows, field):
    return statistics.median(float(r[field]) for r in rows)


def main():
    metadata = [json.loads((root / 'metadata.json').read_text()) for root in (BEFORE, AFTER)]
    for root, m in zip((BEFORE, AFTER), metadata):
        assert (root / 'complete.json').is_file()
        for name, checksum in m['source_sha256'].items():
            assert hashlib.sha256((root / 'source' / name).read_bytes()).hexdigest() == checksum
    for field in ('java', 'requests', 'warmup_requests', 'offered_rps', 'concurrency', 'seed', 'repeats', 'sample_interval'):
        assert metadata[0][field] == metadata[1][field], field
    assert (BEFORE / 'application.properties').read_text() == (AFTER / 'application.properties').read_text()
    for repeat in range(1, 4):
        name = f'schedule-mixed-{repeat}.csv'
        assert read(BEFORE / name) == read(AFTER / name), name
        for strategy in STRATEGIES:
            run = f'{repeat}-mixed-{strategy.lower()}'
            for name in ('warmup.csv', 'requests.csv'):
                old, new = read(BEFORE / run / name), read(AFTER / run / name)
                assert len(old) == len(new)
                assert all(all(a[k] == b[k] for k in ('index', 'kind', 'path', 'scheduled_ms')) for a, b in zip(old, new))
    summaries, types, per_run = [], [], []
    for phase, root in (('before', BEFORE), ('after', AFTER)):
        all_summary = read(root / 'summary.csv')
        all_backends = read(root / 'backends.csv')
        for strategy in STRATEGIES:
            runs = [r for r in all_summary if r['scenario'] == 'mixed' and r['strategy'] == strategy]
            assert len(runs) == 3
            backend = [r for r in all_backends if r['scenario'] == 'mixed' and r['strategy'] == strategy]
            requests, telemetry = [], []
            for run in runs:
                folder = root / f"{run['repeat']}-mixed-{strategy.lower()}"
                requests.extend(read(folder / 'requests.csv'))
                telemetry.extend(read(folder / 'telemetry.csv'))
                per_run.append({'phase': phase, **{k: run[k] for k in (
                    'strategy', 'repeat', 'order_position', 'requests', 'successes', 'errors',
                    'success_rps', 'error_rate', 'avg_ms', 'p95_ms', 'p99_ms', 'client_lag_p95_ms')}})
            status = Counter(r['status'] for r in requests)
            row = dict(phase=phase, strategy=strategy, requests=len(requests),
                       successes=sum(int(r['successes']) for r in runs),
                       errors=sum(int(r['errors']) for r in runs), status_503=status['503'], status_504=status['504'],
                       work_rejections=sum(int(r['estimated_work_rejections']) for r in runs) if phase == 'after' else '')
            for field in ('success_rps', 'error_rate', 'avg_ms', 'p95_ms', 'p99_ms', 'client_lag_p95_ms'):
                row[field + '_median'] = median(runs, field)
                row[field + '_min'] = min(float(r[field]) for r in runs)
                row[field + '_max'] = max(float(r[field]) for r in runs)
            for field in ('backend_active', 'backend_queued', 'proxy_inflight'):
                row[field + '_mean'] = statistics.fmean(float(r[field + '_mean']) for r in backend)
                row[field + '_max'] = max(float(r[field + '_max']) for r in backend)
            for number in (1, 2, 3):
                selected = [r for r in backend if r['backend'] == f'backend-{number}']
                counts = [float(r['received_requests']) for r in selected]
                assert all(value.is_integer() for value in counts)
                row[f'backend_{number}_received'] = int(sum(counts))
                row[f'backend_{number}_queue_mean'] = statistics.fmean(float(r['backend_queued_mean']) for r in selected)
                row[f'backend_{number}_inflight_mean'] = statistics.fmean(float(r['proxy_inflight_mean']) for r in selected)
            row['telemetry_samples'] = len(telemetry)
            row['telemetry_failed_samples'] = sum(bool(r['backend_sample_error'] or r['proxy_sample_error']) for r in telemetry)
            row['warming_samples'] = sum(r['state'] == 'WARMING' for r in telemetry)
            row['down_samples'] = sum(r['state'] == 'DOWN' for r in telemetry)
            summaries.append(row)
            for kind in ('light', 'slow', 'cpu'):
                selected = [r for r in requests if r['kind'] == kind]
                successful = [r for r in selected if 200 <= int(r['status']) < 300]
                types.append(dict(phase=phase, strategy=strategy, kind=kind, requests=len(selected), successes=len(successful),
                                  error_rate=1-len(successful)/len(selected),
                                  avg_ms=statistics.fmean(float(r['latency_ms']) for r in successful) if successful else '',
                                  status_503=sum(r['status'] == '503' for r in selected), status_504=sum(r['status'] == '504' for r in selected)))
    for strategy in STRATEGIES:
        for repeat in ('1', '2', '3'):
            pair = [r for r in per_run if r['strategy'] == strategy and r['repeat'] == repeat]
            assert pair[0]['order_position'] == pair[1]['order_position']
    write('before-after.csv', summaries)
    write('before-after-runs.csv', per_run)
    write('before-after-types.csv', types)
    report = ['# Mixed BEFORE / AFTER', '',
              'Source: `official-20260927` -> `ect-mixed-official-20260927`.', '',
              'Same JDK, seed, request/warm-up schedules, rate, clients, config and order within each repetition: verified.',
              'Primary statistics below are medians of 3 run statistics, not pooled percentiles. Queue/inflight are means of the 9 backend/run means; peaks are sampled maxima. Counts are totals over 3 repetitions.', '',
              '| Phase | Strategy | Success RPS | Error % | Avg ms | p95 ms | p99 ms | Queue mean / max | Inflight mean / max |',
              '|---|---|---:|---:|---:|---:|---:|---:|---:|']
    for r in summaries:
        report.append(f"| {r['phase']} | {r['strategy']} | {r['success_rps_median']:.2f} | {r['error_rate_median']*100:.2f} | {r['avg_ms_median']:.2f} | {r['p95_ms_median']:.2f} | {r['p99_ms_median']:.2f} | {r['backend_queued_mean']:.2f} / {r['backend_queued_max']:.0f} | {r['proxy_inflight_mean']:.2f} / {r['proxy_inflight_max']:.0f} |")
    report += ['', '## Adaptive distribution and failures', '', '| Phase | Received B1 / B2 / B3 | 503 | 504 | Work admission rejections | Lag p95 median ms |', '|---|---|---:|---:|---:|---:|']
    for r in summaries:
        if r['strategy'] == 'ADAPTIVE':
            report.append(f"| {r['phase']} | {r['backend_1_received']} / {r['backend_2_received']} / {r['backend_3_received']} | {r['status_503']} | {r['status_504']} | {r['work_rejections']} | {r['client_lag_p95_ms_median']:.2f} |")
    report += ['', '| Phase | Kind | Successful / offered | Error % | Avg successful ms | 503 | 504 |', '|---|---|---:|---:|---:|---:|---:|']
    for r in types:
        if r['strategy'] == 'ADAPTIVE':
            report.append(f"| {r['phase']} | {r['kind']} | {r['successes']} / {r['requests']} | {r['error_rate']*100:.2f} | {r['avg_ms']:.2f} | {r['status_503']} | {r['status_504']} |")
    report += ['', 'See before-after-runs.csv for paired repetitions and before-after-types.csv for all strategies/classes. Blank BEFORE work-rejection count means this counter did not exist in the original implementation.',
               'Telemetry proxy_work_rejected_total is a proxy-wide counter repeated on each backend row; do not sum those three copies. summary.estimated_work_rejections is its boundary delta.']
    (AFTER / 'COMPARISON.md').write_text('\n'.join(report) + '\n', encoding='utf-8')
    print('\n'.join(report))


if __name__ == '__main__':
    main()
