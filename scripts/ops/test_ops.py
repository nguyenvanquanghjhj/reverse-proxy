#!/usr/bin/env python3
"""Self-contained operation tests. No database connections or production modifications."""
import hashlib
import json
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest
from unittest import mock
import importlib.util

DIRECTORY = Path(__file__).resolve().parent

def load(name):
    spec = importlib.util.spec_from_file_location(name, DIRECTORY / (name+".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

pg = load("pg_safeguard")
watchdog = load("proxy_watchdog")

class OpsTest(unittest.TestCase):
    def test_env_parser(self):
        with tempfile.TemporaryDirectory() as d:
            file=Path(d)/".env.store"
            file.write_text("SHOP_DB_URL=jdbc:postgresql://127.0.0.1:55434/postgres\n"
                            "SHOP_DB_USER=pbl4_store\nSHOP_DB_PASSWORD=SAMPLE_NOT_SECRET\n", encoding="utf-8")
            values=pg.connect_info(file)
            self.assertEqual(values["port"],"55434")
            self.assertEqual(values["database"],"postgres")
            self.assertEqual(values["password"],"SAMPLE_NOT_SECRET")
    def test_hash(self):
        with tempfile.TemporaryDirectory() as d:
            file=Path(d)/"x.dump";file.write_bytes(b"abc")
            self.assertEqual(pg.sha256(file),hashlib.sha256(b"abc").hexdigest())
    def test_snapshot(self):
        first={"traffic":{"total":{"completed":40,"failed":2,
                 "latencyMs":{"samples":40,"p95":100}}},
                 "backends":[{"id":"B1","state":"UP"}]}
        parsed,prev=watchdog.summarize(first,None,.2,300,True)
        self.assertIsNone(parsed["window_error_rate"])
        second={"traffic":{"total":{"completed":60,"failed":10,
                 "latencyMs":{"samples":60,"p95":500}}},
                 "backends":[{"id":"B1","state":"DOWN"}]}
        parsed,prev=watchdog.summarize(second,prev,.2,300,True)
        self.assertAlmostEqual(parsed["window_error_rate"],.4)
        self.assertEqual(parsed["reasons"],["ERROR_RATE","P95","BACKEND_DOWN"])
        self.assertEqual(prev,(60,10))
    def test_watchdog_debounce(self):
        machine=watchdog.StateMachine(2)
        self.assertIsNotNone(machine.update([]))
        self.assertIsNone(machine.update(["P95"]))
        self.assertEqual(machine.update(["P95"])["current"],"ALERT")
        self.assertEqual(machine.update([])["current"],"OK")
    def test_pg_dump_archive_manifest_and_verify(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            fake=root/'fakepg.py'
            fake.write_text("#!/usr/bin/env python3\nimport sys\nfrom pathlib import Path\nargs=sys.argv[1:]\nif '--file' in args:\n    Path(args[args.index('--file')+1]).write_bytes(b'PGDMP fake archive; synthetic test only')\nelif '--list' in args:\n    print('synthetic TOC; not a real archive')\n", encoding="utf-8")
            fake.chmod(0o700)
            con={"host":"127.0.0.1", "port":"55434", "database":"postgres",
                 "user":"pbl4_store", "password":"not-a-real-password"}
            run_process = subprocess.run
            # Invoke the Python fixture explicitly: Windows does not execute shebangs.
            def run_fixture(args, **kwargs):
                return run_process([sys.executable, *args], **kwargs)
            with mock.patch.object(pg,'tool', return_value=str(fake)), \
                    mock.patch.object(pg.subprocess, 'run', side_effect=run_fixture):
                pg.backup(con,root/'out')
                manifests=list((root/'out').glob('*.json'))
                self.assertEqual(len(manifests),1)
                archive,meta=pg.validated_archive(manifests[0],con)
                self.assertEqual(archive.stat().st_size,meta['size_bytes'])
                archive.write_bytes(b'corrupted archive')
                with self.assertRaises(RuntimeError): pg.validated_archive(manifests[0],con)
    def test_manifest_refuses_path_traversal(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            manifest=root/"bad.json"
            manifest.write_text(json.dumps({"filename":"../../secrets.dump"}),encoding="utf-8")
            with self.assertRaises(ValueError):
                pg.validated_archive(manifest,{})

if __name__ == "__main__": unittest.main(verbosity=2)
