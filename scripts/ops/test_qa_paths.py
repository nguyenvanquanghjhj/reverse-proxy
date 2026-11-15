import os
import hashlib
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
from qa_paths import ROOT, evidence_path, new_evidence_path, release_generated_inputs


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

    def test_release_keeps_other_data_and_writes_hash_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);(root/'input.bin').write_bytes(b'abc');(root/'database').write_bytes(b'preserve')
            result=release_generated_inputs(root,{'input.bin':{'bytes':3,'sha256':hashlib.sha256(b'abc').hexdigest()}},'test fixture recipe')
            self.assertFalse((root/'input.bin').exists())
            self.assertEqual((root/'database').read_bytes(),b'preserve')
            self.assertEqual(result['removed_logical_bytes'],3)
            self.assertTrue((root/'fixture-inputs.json').exists())

    def test_release_validates_all_hashes_before_any_deletion(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            for name in ('first','second'):(root/name).write_bytes(b'abc')
            artifacts={'first':{'bytes':3,'sha256':hashlib.sha256(b'abc').hexdigest()},'second':{'bytes':3,'sha256':'0'*64}}
            with self.assertRaises(ValueError):release_generated_inputs(root,artifacts,'recipe')
            self.assertTrue((root/'first').exists() and (root/'second').exists())

    def test_release_rejects_escape_before_touching_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            with self.assertRaises(ValueError):
                release_generated_inputs(root,{'../outside':{'bytes':0,'sha256':'0'*64}},'recipe')


if __name__ == '__main__':
    unittest.main()
