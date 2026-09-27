"""Accounting and workload invariants; run: python -m unittest discover -s scripts -p test_benchmark.py."""
import unittest
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


if __name__ == '__main__':
    unittest.main()
