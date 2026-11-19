import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import qa_snapshot


class SnapshotTest(unittest.TestCase):
    def archive(self, root, *, relative='base/1', payload=b'QA data', digest=None):
        path = root / 'snapshot.zip'
        digest = digest or hashlib.sha256(payload).hexdigest()
        rows = [{'path': 'old/cluster/base/1', 'relative': relative, 'dataset': 'cluster-abcdef123456',
                 'bytes': len(payload), 'sha256': digest}]
        with zipfile.ZipFile(path, 'x') as archive:
            archive.writestr('manifest.json', json.dumps({'format': 1, 'datasets': [], 'files': rows}))
            archive.writestr('objects/' + digest, payload)
        return path

    def test_verified_restore_keeps_original_bytes_and_path_map(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); path = self.archive(root); out = root / 'restored'
            qa_snapshot.restore(path, out, 'cluster-abcdef123456')
            self.assertEqual((out / 'base/1').read_bytes(), b'QA data')
            self.assertEqual(len(json.loads((out / 'RESTORED_PATHS.json').read_text())), 1)

    def test_existing_target_is_never_modified(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); path = self.archive(root); out = root / 'existing'; out.mkdir()
            (out / 'sentinel').write_bytes(b'keep')
            with self.assertRaises(ValueError): qa_snapshot.restore(path, out)
            self.assertEqual((out / 'sentinel').read_bytes(), b'keep')

    def test_traversal_is_rejected_before_creating_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); path = self.archive(root, relative='../outside'); out = root / 'out'
            with self.assertRaises(ValueError): qa_snapshot.restore(path, out)
            self.assertFalse(out.exists())

    def test_bad_hash_is_rejected_before_creating_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); path = self.archive(root, digest='0' * 64); out = root / 'out'
            with self.assertRaises(ValueError): qa_snapshot.restore(path, out)
            self.assertFalse(out.exists())

    def test_missing_dataset_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); path = self.archive(root)
            with self.assertRaises(ValueError): qa_snapshot.verify(path, 'cluster-000000000000')


if __name__ == '__main__': unittest.main()
