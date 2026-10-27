"""Ablation patch boundaries and statistical accounting; no JVMs or network required."""
import csv
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('ablation_tool', Path(__file__).resolve().parents[1] / 'tools/ablation.py')
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)


class AblationTest(unittest.TestCase):
    def test_only_requested_source_patch_and_ambiguous_anchor_rejected(self):
        sources = {relative: before.encode() for relative, before, _ in a.PATCHES.values()}
        sources['Other.java'] = b'unchanged'
        self.assertEqual(a.patch_source('full', sources), sources)
        for variant, (relative, before, after) in a.PATCHES.items():
            patched = a.patch_source(variant, sources)
            self.assertEqual([p for p in sources if sources[p] != patched[p]], [relative])
            self.assertEqual(patched[relative], after.encode())
            self.assertEqual(sources[relative], before.encode())
            for bad in (b'anchor absent', (before + before).encode()):
                with self.assertRaisesRegex(ValueError, 'anchor changed'):
                    a.patch_source(variant, {**sources, relative: bad})

    def test_failed_fast_requests_never_lower_success_p95_or_hide_fairness(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            plan = {'runs': []}
            for variant in a.VARIANTS:
                for repeat in (1,2,3):
                    relative = f'{variant}-{repeat}'
                    run = root / relative / '1-mixed-adaptive'
                    run.mkdir(parents=True)
                    a.write_csv(run / 'requests.csv', [
                        dict(kind='light',status=200,latency_ms=10), dict(kind='light',status=200,latency_ms=20),
                        dict(kind='slow',status=200,latency_ms=repeat*100), dict(kind='slow',status=503,latency_ms=1),
                        dict(kind='cpu',status=503,latency_ms=1)])
                    a.write_csv(run.parent / 'summary.csv', [dict(success_rps=repeat, estimated_work_rejections=repeat)])
                    plan['runs'].append(dict(variant=variant, path=relative))
            result = a.summarize(root, plan)
            overall = result[0]
            self.assertEqual((overall['requests'],overall['successes'],overall['errors']), (15,9,6))
            self.assertEqual(overall['success_rps_median'], 2)
            self.assertEqual(overall['work_rejections'], 6)
            self.assertEqual(overall['kind_success_rate_gap'], 1)
            self.assertEqual(result[2]['p95_success_ms_median'], 200)
            self.assertEqual(result[3]['p95_success_ms_median'], '')
            self.assertEqual(result[3]['success_rate'], 0)

    def test_corruption_is_rejected_before_loading_frozen_runner(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'plan.json').write_text(json.dumps(dict(repeats=3,runs=[])))
            raw = root / 'requests.csv'
            raw.write_text('status\n200\n')
            manifest = {p.name:a.digest(p) for p in root.iterdir()}
            (root / 'manifest.json').write_text(json.dumps(manifest))
            raw.write_text('status\n503\n')
            with self.assertRaisesRegex(ValueError, 'hash mismatch'):
                a.verify(root)


if __name__ == '__main__':
    unittest.main()
