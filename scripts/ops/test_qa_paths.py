import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import app_runtime
import shop_demo
import prepare_runtime
import shop_prepare
from qa_paths import ROOT, evidence_path, new_evidence_path


class QaPathsTest(unittest.TestCase):
    def test_reject_checkout_and_protected_subtrees_before_creating(self):
        for path in (ROOT, ROOT / '.shop/qa', ROOT / '.runtime/qa', ROOT / 'shared-files/qa', ROOT / 'target/qa'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                evidence_path(path)

    def test_new_run_is_outside_checkout_unique_and_not_created(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {'PBL4_EVIDENCE_ROOT': temporary}):
            first = new_evidence_path('store'); second = new_evidence_path('store')
            self.assertNotEqual(first, second)
            self.assertEqual(first.parent, Path(temporary).resolve())
            self.assertFalse(first.exists())

    def test_bad_evidence_roots_and_labels_fail_closed(self):
        for parent in ('relative', str(ROOT / 'target')):
            with patch.dict(os.environ, {'PBL4_EVIDENCE_ROOT': parent}), self.assertRaises(ValueError):
                new_evidence_path('store')
        with self.assertRaises(ValueError):
            new_evidence_path('../outside')

    def test_legacy_imports_share_identity_and_tracking_functions(self):
        self.assertIs(shop_demo, app_runtime)
        self.assertIs(shop_prepare, prepare_runtime)
        self.assertIs(shop_demo.terminate, app_runtime.terminate)
        self.assertIs(shop_demo.STATE, app_runtime.STATE)


if __name__ == '__main__':
    unittest.main()
