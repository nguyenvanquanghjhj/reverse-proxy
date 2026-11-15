import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from storage_audit import inventory


class StorageAuditTest(unittest.TestCase):
    def test_hidden_files_and_exclusions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / '.hidden').mkdir()
            (root / '.hidden' / 'payload').write_bytes(b'abc')
            (root / 'report.json').write_bytes(b'exclude me')
            result = inventory(root, [root / 'report.json'])
            self.assertTrue(result['complete'])
            self.assertEqual(result['totals']['logical_bytes_per_path'], 3)
            self.assertEqual(result['totals']['files'], 1)

    def test_hardlinks_count_physical_storage_once(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'first').write_bytes(b'a' * 8192)
            os.link(root / 'first', root / 'second')
            result = inventory(root)
            self.assertTrue(result['complete'])
            self.assertEqual(result['totals']['logical_bytes_per_path'], 16384)
            self.assertEqual(result['totals']['allocated_bytes_per_path'],
                             2 * result['totals']['unique_allocated_bytes'])

    def test_unreadable_directory_is_explicitly_incomplete(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            blocked = root / 'blocked'; blocked.mkdir()
            scan = os.scandir
            def denied(path):
                if Path(path) == blocked:
                    raise PermissionError('QA enumeration refused')
                return scan(path)
            with patch('storage_audit.os.scandir', side_effect=denied):
                result = inventory(root)
            self.assertFalse(result['complete'])
            self.assertEqual(result['errors'][0]['path'], 'blocked')

    def test_junction_or_symlink_does_not_escape_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'scan'; root.mkdir()
            outside = Path(temporary) / 'outside'; outside.mkdir()
            (outside / 'private').write_bytes(b'outside boundary')
            link = root / 'linked'
            if os.name == 'nt':
                import subprocess
                subprocess.run(['cmd', '/c', 'mklink', '/J', str(link), str(outside)],
                               capture_output=True, check=True)
            else:
                link.symlink_to(outside, target_is_directory=True)
            try:
                result = inventory(root)
                self.assertTrue(result['complete'])
                self.assertEqual(result['totals']['files'], 0)
                self.assertEqual(result['links_not_followed'], ['linked'])
                with self.assertRaises(ValueError):
                    inventory(link)
            finally:
                if os.name == 'nt': link.rmdir()
                else: link.unlink()


if __name__ == '__main__':
    unittest.main()
