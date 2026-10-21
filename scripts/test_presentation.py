"""Presentation statistics and provenance invariants; no running proxy or plotting dependency."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import generate_charts as charts


class PresentationTest(unittest.TestCase):
    def fixture(self, root):
        runs, types, backends = [], [], []
        for repeat in (1, 2):
            for strategy in charts.STRATEGIES:
                identity = dict(scenario='mixed', repeat=str(repeat), strategy=strategy)
                row = {k: '' for k in charts.RUN_FIELDS}
                row.update(identity, requests='10', successes='7', errors='3', error_rate='0.3', offered_rps='5',
                           success_rps=str(repeat * 2), avg_ms=str(repeat * 10), p50_ms='10', p95_ms='100', p99_ms='200',
                           all_outcomes_p95_ms='3000', client_lag_p95_ms='15', client_lag_warning='False')
                runs.append(row)
                types.extend([dict(**identity,kind='light',requests='6',successes='6'),dict(**identity,kind='slow',requests='4',successes='1')])
                backends.append(dict(**identity,backend='backend-1',dispatched_requests='8',successful_client_responses='7'))
        for name, rows in (('runs.csv',runs),('request_types.csv',types),('backends.csv',backends)):
            charts.write_csv(root/name,rows)
        self.manifest(root)
        return runs

    @staticmethod
    def manifest(root):
        charts.write_json(root/'provenance.json',dict(bundle_sha256={p.name:charts.digest(p) for p in root.glob('*.csv')}))

    def test_counts_sum_but_latency_and_rps_use_run_medians(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); self.fixture(root)
            _,runs,_,_=charts.load_bundle(root)
            for row in charts.summarize(runs):
                self.assertEqual((row['requests'],row['successes'],row['errors']),(20,14,6))
                self.assertEqual(row['error_percent'],30)
                self.assertEqual((row['success_rps_median'],row['success_rps_min'],row['success_rps_max']),(3,2,4))
                self.assertEqual(row['avg_ms_median'],15)
                self.assertEqual(row['all_outcomes_p95_ms_median'],3000)

    def test_bundle_rejects_modified_counts_or_missing_strategy(self):
        for corruption in ('checksum','counts','types','backend','missing'):
            with self.subTest(corruption=corruption),tempfile.TemporaryDirectory() as directory:
                root=Path(directory); self.fixture(root)
                name='request_types.csv' if corruption=='types' else 'backends.csv' if corruption=='backend' else 'runs.csv'
                rows=charts.read_csv(root/name)
                if corruption=='missing': rows=rows[:-1]
                elif corruption=='backend': rows[0]['dispatched_requests']='11'
                else: rows[0]['requests']='999'
                charts.write_csv(root/name,rows)
                if corruption!='checksum': self.manifest(root)
                with self.assertRaises(ValueError): charts.load_bundle(root)

    def test_no_success_is_missing_latency_not_zero(self):
        rows=[dict(strategy=s,requests='1',successes='0',errors='1',success_rps='0',avg_ms='',p50_ms='',p95_ms='',p99_ms='',
                   all_outcomes_p95_ms='3000',client_lag_p95_ms='1') for s in charts.STRATEGIES]
        for result in charts.summarize(rows):
            self.assertIsNone(result['p99_ms_median'])
            self.assertEqual(result['error_percent'],100)
        self.assertEqual(charts.display(None),'n/a')
        with self.assertRaises(ValueError): charts.numeric(dict(value='NaN'),'value')

    def test_export_does_not_waive_failed_raw_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with patch.object(charts.verify_benchmark,'verify',side_effect=ValueError('hash mismatch')):
                with self.assertRaisesRegex(ValueError,'hash mismatch'):
                    charts.export_bundle(root,root/'bundle','mixed')
            self.assertFalse((root/'bundle').exists())


if __name__=='__main__':
    unittest.main()
