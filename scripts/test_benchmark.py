"""Accounting and workload invariants; run: python -m unittest discover -s scripts -p test_benchmark.py."""
import unittest
import tempfile
import json
import subprocess
import sys
import time
from unittest.mock import patch
from pathlib import Path
import verify_benchmark as v
from collections import Counter
import benchmark as b


class BenchmarkTest(unittest.TestCase):
    def test_homogeneous_requests_really_identical(self):
        for name in ('homogeneous', 'heterogeneous'):
            rows = b.schedule(name, 100, 60, 123)
            self.assertEqual({r['path'] for r in rows}, {'/work?cost=1'})
            self.assertEqual(rows[60]['scheduled_ms'], 1000)

    def test_mix_reproducible_and_exact(self):
        rows = b.schedule('mixed', 100, 60, 123)
        self.assertEqual(Counter(r['kind'] for r in rows), {'light': 60, 'slow': 10, 'cpu': 30})
        self.assertEqual(rows, b.schedule('mixed', 100, 60, 123))
        self.assertNotEqual(rows, b.schedule('mixed', 100, 60, 124))

    def test_error_latency_not_hidden(self):
        rows = [dict(status=200, latency_ms=20, completed_ms=20, request_latency_ms=15, client_lag_ms=5),
                dict(status=503, latency_ms=900, completed_ms=1900, request_latency_ms=800, client_lag_ms=100)]
        s = b.summarize(rows, 1)
        self.assertEqual(s['error_rate'], 0.5)
        self.assertEqual(s['avg_ms'], 20)
        self.assertEqual(s['all_outcomes_p95_ms'], 900)
        self.assertEqual(s['success_rps'], 0.5)  # Uses full two-second arrival window.
        self.assertEqual(b.percentile([1, 2, 3, 4, 5], .5), 3)

    def test_all_failed_does_not_invent_latency(self):
        r = dict(status=0, latency_ms=8000, completed_ms=8000, request_latency_ms=8000, client_lag_ms=0)
        s = b.summarize([r], 10)
        self.assertEqual(s['success_rps'], 0)
        self.assertEqual(s['error_rate'], 1)
        self.assertIsNone(s['p99_ms'])

    def test_received_counts_include_failed_requests_and_exclude_warmup(self):
        before = [dict(backend='backend-1', backend_received=20, proxy_dispatched=20, proxy_completed=20, proxy_errors=0)]
        after = [dict(backend='backend-1', backend_received=30, proxy_dispatched=31, proxy_completed=31, proxy_errors=3)]
        rows = [dict(backend='backend-1', status=200)] * 8
        s = b.backend_summary(before, after, [], rows)[0]
        self.assertEqual(s['received_requests'], 10)
        self.assertEqual(s['dispatched_requests'], 11)
        self.assertEqual(s['successful_client_responses'], 8)
        self.assertIsNone(s['process_cpu_cores_mean'])

    def test_nonfinite_telemetry_is_unknown(self):
        self.assertIsNone(b.finite(float('nan')))
        self.assertIsNone(b.finite(float('inf')))

    def test_backend_metrics_are_available_on_each_summary_row(self):
        records = [dict(backend=f'backend-{i}', received_requests=i, backend_active_mean=i / 10,
                        proxy_inflight_max=i, process_cpu_cores_mean=None, heap_used_bytes_max=100 * i)
                   for i in range(1, 4)]
        row = b.flatten_backend_metrics({'strategy': 'ROUND_ROBIN'}, records)
        self.assertEqual(row['backend_1_received_requests'], 1)
        self.assertEqual(row['backend_3_proxy_inflight_max'], 3)
        self.assertEqual(row['backend_2_heap_used_bytes_max'], 200)

    def test_java_21_detection(self):
        self.assertEqual(b.java_major('openjdk version "21.0.8"'), 21)
        self.assertEqual(b.java_major('java version "1.8.0_401"'), 8)
        self.assertIsNone(b.java_major('unknown runtime'))


class HardeningBenchmarkTest(unittest.TestCase):
    def test_interrupt_wakes_future_scheduled_workers(self):
        class Cluster:
            ports = []
            port = 1
        arrivals = [dict(index=0, kind='light', path='/', scheduled_ms=0),
                    dict(index=1, kind='light', path='/', scheduled_ms=60000)]
        started = time.monotonic()
        with patch.object(b, 'request', side_effect=KeyboardInterrupt), self.assertRaises(KeyboardInterrupt):
            b.workload(Cluster(), arrivals, 10, 2, telemetry=False)
        self.assertLess(time.monotonic() - started, 2)

    def test_empty_and_invalid_inputs(self):
        result = b.summarize([], 10)
        self.assertEqual(result['requests'], 0)
        self.assertEqual(result['completed_rps'], 0)
        self.assertIsNone(result['avg_ms'])
        self.assertFalse(result['client_lag_warning'])
        for value in (0, -1, float('nan'), float('inf')):
            with self.subTest(value=value), self.assertRaises(ValueError):
                b.schedule('mixed', 10, value, 1)
            with self.assertRaises(ValueError):
                b.summarize([], value)
            with self.assertRaises(ValueError):
                b.workload(None, [], 10, value)
        row = dict(status=200, latency_ms=float('nan'), completed_ms=1, request_latency_ms=1, client_lag_ms=0)
        with self.assertRaises(ValueError):
            b.summarize([row], 10)

    def test_cli_rejects_invalid_before_java_or_output(self):
        for option, value in (('--requests', '0'), ('--warmup-requests', '0'), ('--repeats', '-1'),
                              ('--concurrency', '0'), ('--rate', 'NaN'), ('--rate', 'Infinity'),
                              ('--sample-interval', '0.001')):
            run = subprocess.run([sys.executable, str(Path(b.__file__)), option, value], capture_output=True, text=True)
            self.assertEqual(run.returncode, 2)
            self.assertIn('counts/rate must be finite and positive', run.stderr)

    def test_statistics_and_timeout_accounting(self):
        rows = [dict(status=status, latency_ms=value, completed_ms=value, request_latency_ms=value, client_lag_ms=0)
                for status, value in ((200, 10), (200, 30), (504, 90))]
        summary = b.summarize(rows, 10)
        self.assertEqual(summary['median_ms'], 20)
        self.assertAlmostEqual(summary['stddev_ms'], 2 ** .5 * 10)
        self.assertEqual(summary['min_ms'], 10)
        self.assertEqual(summary['max_ms'], 30)
        self.assertEqual(summary['timeout_count'], 1)
        self.assertEqual(summary['all_outcomes_p99_ms'], 90)

    def test_serializers_never_write_nonfinite(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for bad in (float('nan'), float('inf'), -float('inf')):
                with self.assertRaises(ValueError):
                    b.csv_write(root / 'bad.csv', [dict(value=bad)])
                with self.assertRaises(ValueError):
                    b.json_write(root / 'bad.json', dict(value=bad))
            b.json_write(root / 'good.json', b.summarize([], 10))
            self.assertNotIn('NaN', (root / 'good.json').read_text())
            self.assertNotIn('Infinity', (root / 'good.json').read_text())

    def test_fault_schedules_are_repeatable(self):
        for scenario in ('timeout', 'errors', 'recovery'):
            self.assertEqual(b.schedule(scenario, 30, 10, 1), b.schedule(scenario, 30, 10, 1))
        self.assertEqual({r['kind'] for r in b.schedule('errors', 30, 10, 1)}, {'light', '429', '503'})
        self.assertEqual({r['kind'] for r in b.schedule('timeout', 30, 10, 1)}, {'slow'})

    @staticmethod
    def manifest(root):
        b.json_write(root / 'complete.json', dict(runs=3, file_sha256={
            p.relative_to(root).as_posix(): v.digest(p) for p in root.rglob('*') if p.is_file() and p.name != 'complete.json'}))

    def artifact(self, root):
        # A small, internally consistent completed experiment, no JVM/network dependency.
        for folder, name in (('source', 'Fixture.java'), ('classes', 'Fixture.class')):
            (root / folder).mkdir()
            (root / folder / name).write_bytes(b'fixture')
        (root / 'application.properties').write_text('fixture=true')
        (root / 'benchmark.py').write_bytes(Path(b.__file__).read_bytes())
        (root / 'verify_benchmark.py').write_bytes(Path(v.__file__).read_bytes())
        meta = dict(schema_version=2, scenarios=['homogeneous'], repeats=1, requests=2, warmup_requests=2,
                    offered_rps=10, seed=1, source_sha256={'Fixture.java': v.digest(root / 'source/Fixture.java')},
                    class_sha256={'Fixture.class': v.digest(root / 'classes/Fixture.class')},
                    config_sha256=v.digest(root / 'application.properties'), script_sha256=v.digest(root / 'benchmark.py'),
                    verifier_sha256=v.digest(root / 'verify_benchmark.py'))
        b.json_write(root / 'metadata.json', meta)
        schedule = b.schedule('homogeneous', 2, 10, 1)
        rows = [dict(**item, status=200, sent_ms=item['scheduled_ms'], completed_ms=item['scheduled_ms']+10,
                     latency_ms=10, request_latency_ms=10, client_lag_ms=0, backend='backend-1', error='') for item in schedule]
        b.csv_write(root / 'schedule-homogeneous-1.csv', schedule)
        summaries, backends = [], []
        for strategy in b.STRATEGIES:
            identity = dict(scenario='homogeneous', repeat=1, strategy=strategy)
            run = root / ('1-homogeneous-' + strategy.lower())
            run.mkdir()
            for name in ('proxy.log', 'proxy.properties'):
                (run / name).write_text('fixture')
            before = [dict(phase='before', backend=f'backend-{i}', backend_received=0, proxy_dispatched=0,
                           proxy_completed=0, proxy_errors=0, backend_active=0, backend_queued=0, proxy_inflight=0,
                           backend_sample_error='', proxy_sample_error='') for i in range(1, 4)]
            after = [dict(r, phase='after', backend_received=2 if i == 0 else 0,
                          proxy_dispatched=2 if i == 0 else 0, proxy_completed=2 if i == 0 else 0) for i, r in enumerate(before)]
            samples = [dict(r, phase='measurement') for r in before]
            backend = [dict(**identity, **row) for row in b.backend_summary(before, after, samples, rows)]
            summary = b.flatten_backend_metrics(dict(**identity, **b.summarize(rows, 10), warmup_errors=0), backend)
            b.csv_write(run / 'requests.csv', rows)
            b.csv_write(run / 'warmup.csv', rows)  # Homogeneous schedule is independent of seed.
            b.csv_write(run / 'telemetry.csv', samples)
            b.csv_write(run / 'boundaries.csv', before+after)
            b.csv_write(run / 'summary.csv', [summary])
            b.csv_write(run / 'backends.csv', backend)
            summaries.append(summary)
            backends.extend(backend)
        b.csv_write(root / 'summary.csv', summaries)
        b.csv_write(root / 'backends.csv', backends)
        aggregates=[]
        for summary in summaries:
            row=dict(scenario='homogeneous',strategy=summary['strategy'],runs=1)
            for field in ('success_rps','avg_ms','p50_ms','p95_ms','p99_ms','error_rate','client_lag_p95_ms'):
                for suffix in ('mean','median','min','max'):row[field+'_'+suffix]=summary[field]
                row[field+'_stddev']=None
            aggregates.append(row)
        b.csv_write(root / 'aggregate.csv',aggregates)
        b.csv_write(root / 'request_types.csv',[dict(scenario='homogeneous',repeat=1,strategy=s,kind='light',
                    requests=2,successes=2,error_rate=0,avg_ms=10,p50_ms=10,p95_ms=10,p99_ms=10) for s in b.STRATEGIES])
        self.manifest(root)

    def test_verifier_good_output_and_corruptions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.artifact(root)
            v.verify(root)
            for name in ('source/Fixture.java', 'classes/Fixture.class', 'benchmark.py', 'application.properties',
                         '1-homogeneous-round_robin/requests.csv'):
                path = root / name
                original = path.read_bytes()
                path.write_bytes(original + b'changed')
                with self.assertRaisesRegex(ValueError, 'hash mismatch'):
                    v.verify(root)
                path.write_bytes(original)
            missing = root / '1-homogeneous-round_robin/telemetry.csv'
            original = missing.read_bytes()
            missing.unlink()
            with self.assertRaisesRegex(ValueError, 'Artifact set'):
                v.verify(root)
            missing.write_bytes(original)

    def test_verifier_recomputes_even_with_updated_manifest(self):
        for corruption in ('schedule', 'summary', 'counter', 'telemetry', 'missing_run','request_type','aggregate'):
            with self.subTest(corruption=corruption), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                self.artifact(root)
                run = root / '1-homogeneous-round_robin'
                if corruption == 'schedule':
                    path = root / 'schedule-homogeneous-1.csv'
                    rows = v.read(path); rows[0]['scheduled_ms'] = 99; b.csv_write(path, rows)
                elif corruption == 'summary':
                    rows = v.read(root / 'summary.csv'); rows[0]['avg_ms'] = 999
                    b.csv_write(root / 'summary.csv', rows); b.csv_write(run / 'summary.csv', rows[:1])
                elif corruption == 'counter':
                    rows = v.read(run / 'boundaries.csv'); rows[3]['backend_received'] = 999
                    b.csv_write(run / 'boundaries.csv', rows)
                elif corruption == 'telemetry':
                    (run / 'telemetry.csv').write_text('backend\n')
                elif corruption in ('request_type','aggregate'):
                    name='request_types.csv' if corruption=='request_type' else 'aggregate.csv'
                    values=v.read(root/name)
                    values[0]['avg_ms' if corruption=='request_type' else 'success_rps_median']=999
                    b.csv_write(root/name,values)
                else:
                    rows = v.read(root / 'summary.csv'); b.csv_write(root / 'summary.csv', rows[:-1])
                self.manifest(root)
                with self.assertRaises(ValueError):
                    v.verify(root)


if __name__ == '__main__':
    unittest.main()
