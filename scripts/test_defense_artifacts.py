"""The independent archive verifier must not trust paths supplied by an archive."""
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
import defense_artifacts as artifacts


class ArchiveTest(unittest.TestCase):
    def test_rejects_escape_absolute_windows_and_link_entries(self):
        for name in ('../outside','/absolute','C:/outside','folder\\outside','linked'):
            with self.subTest(name=name),tempfile.TemporaryDirectory() as temporary:
                root=Path(temporary); archive=root/'raw.zip'
                with zipfile.ZipFile(archive,'w') as zipped:
                    entry=zipfile.ZipInfo(name)
                    # ZipInfo normalizes Windows separators on Windows; retain the hostile
                    # member name as it would arrive from a ZIP produced on another OS.
                    entry.filename=name
                    if name=='linked': entry.external_attr=0o120777 << 16
                    zipped.writestr(entry,'data')
                with self.assertRaises(ValueError): artifacts.unpack(archive,root/'extracted')
                self.assertFalse((root/'extracted').exists())

    def test_valid_file_is_extracted_without_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary); archive=root/'raw.zip'
            with zipfile.ZipFile(archive,'w') as zipped: zipped.writestr('load-30/requests.csv',b'a,b\r\n1,2\r\n')
            artifacts.unpack(archive,root/'extracted')
            self.assertEqual((root/'extracted/load-30/requests.csv').read_bytes(),b'a,b\r\n1,2\r\n')


if __name__=='__main__': unittest.main()
