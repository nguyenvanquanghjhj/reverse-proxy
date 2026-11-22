"""LAN configuration and process safety without opening real firewall/SMB/DB services."""
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from contextlib import redirect_stdout

PATH=Path(__file__).resolve().parents[2]/'deploy/lan/windows_nodes.py'
spec=importlib.util.spec_from_file_location('windows_nodes',PATH);lan=importlib.util.module_from_spec(spec);spec.loader.exec_module(lan)

class WindowsNodesTest(unittest.TestCase):
    def test_backend_status_does_not_probe_other_backend_hosts(self):
        class Runtime:
            @staticmethod
            def state():return {}
        with patch.object(lan,'request',return_value=(200,{'status':'READY'})) as request,patch.object(lan,'build_id',return_value='fixture-build'),patch('builtins.print'):
            lan.status(Path('fixture'),{'role':'BACKEND2','hosts':['10.1.1.1','10.1.1.2','10.1.1.3']},{},Runtime())
            request.assert_called_once_with('http://10.1.1.2:9002/ready',{})
    def test_main_configuration_is_separate_and_secret_free_properties(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);storage=base/'files';storage.mkdir();env=base/'private.env'
            env.write_text('SHOP_DB_URL=jdbc:postgresql://10.1.1.1:5432/qa\nSHOP_DB_USER=qa\nSHOP_DB_PASSWORD=private-fixture-password\nPBL4_PROBE_TOKEN='+'a'*64+'\n')
            args=SimpleNamespace(hosts=['10.1.1.1','10.1.1.2','10.1.1.3'],directory=base/'node',env_file=env,db_url=None,db_user=None,files=str(storage),role='MAIN',probe_token_file=None,pg_data=None)
            # Exercise the Windows CI console encoding even when this test runs on Linux.
            output=io.BytesIO()
            console=io.TextIOWrapper(output,encoding='cp1252',errors='strict')
            with patch.dict(os.environ,{},clear=True),redirect_stdout(console):lan.configure(args)
            console.flush()
            self.assertIn(b'Created configuration MAIN:',output.getvalue())
            properties=(base/'node/proxy.properties').read_text()
            self.assertIn('backend.servers=10.1.1.1:9001:8,10.1.1.2:9002:8,10.1.1.3:9003:8',properties)
            self.assertNotIn('private-fixture-password',properties)
            configured=lan.read_env(base/'node/node.env')
            self.assertEqual(configured['STORE_ORIGIN'],'http://10.1.1.1:8080')
            self.assertEqual(configured['SHOP_FILES_DIR'],str(storage))
            self.assertEqual(json.loads((base/'node/node.json').read_text())['role'],'MAIN')
    def test_three_distinct_private_hosts(self):
        self.assertEqual(lan.validate_hosts(['10.1.1.1','10.1.1.2','10.1.1.3']),['10.1.1.1','10.1.1.2','10.1.1.3'])
        for hosts in [['127.0.0.1']*3,['10.1.1.1']*3,['8.8.8.8','10.1.1.2','10.1.1.3'],['169.254.1.1','10.1.1.2','10.1.1.3']]:
            with self.assertRaises(ValueError):lan.validate_hosts(hosts)
    def test_private_configuration_outside_checkout(self):
        with self.assertRaises(ValueError):lan.directory(lan.ROOT/'.shop/unsafe')
    def test_existing_configuration_never_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            out=Path(tmp);(out/'node.json').write_text('original')
            class Args:hosts=['10.1.1.1','10.1.1.2','10.1.1.3'];directory=out
            with self.assertRaises(ValueError):lan.configure(Args())
            self.assertEqual((out/'node.json').read_text(),'original')
    def test_storage_probe_requires_shared_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'.pbl4-lan-storage-id').write_text('expected')
            (root/'business.txt').write_bytes(b'untouched')
            lan.storage_probe({'storage_id':'expected'},{'SHOP_FILES_DIR':str(root)})
            self.assertEqual((root/'business.txt').read_bytes(),b'untouched')
            self.assertEqual(len(list(root.iterdir())),2)
            with self.assertRaises(ValueError):lan.storage_probe({'storage_id':'other'},{'SHOP_FILES_DIR':str(root)})
    def test_missing_ownership_cannot_stop_postgres(self):
        with tempfile.TemporaryDirectory() as tmp:
            class Runtime:
                @staticmethod
                def state():return {}
                @staticmethod
                def stop(data):pass
            with patch.object(lan,'postgres') as pg:
                with self.assertRaises(ValueError):lan.stop(Path(tmp),{'role':'MAIN','pg_data':'unowned'},{},Runtime(),True)
                pg.assert_not_called()

if __name__=='__main__':unittest.main()
