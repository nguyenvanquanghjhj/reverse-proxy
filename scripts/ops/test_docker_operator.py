#!/usr/bin/env python3
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import docker_operator

class OperatorTest(unittest.TestCase):
    def test_internal_exec_and_no_control_plane_publication(self):
        with tempfile.TemporaryDirectory() as td:
            yml=Path(td)/'compose.yaml';yml.write_text('services: {}');(yml.parent/'.env').write_text('PBL4_DB_PASSWORD=QA_LOCAL')
            cmd=docker_operator.command(yml,'drain','backend1:9001')
            self.assertEqual(cmd[:3],['docker','compose','--env-file'])
            self.assertEqual(cmd[-4:],['java','com.example.proxy.server.OperatorCli','drain','backend1:9001'])
            self.assertIn('exec',cmd)
            self.assertIn('-T',cmd)
            self.assertNotIn('PBL4_CONTROL_TOKEN',cmd)
    def test_reject_injection(self):
        with self.assertRaises(ValueError):
            docker_operator.command(Path('x'),'drain','evil; rm -rf /')
        with self.assertRaises(ValueError):
            docker_operator.command(Path('x'),'nonsense',None)
    def test_internal_metrics_json(self):
        with tempfile.TemporaryDirectory() as td:
            yml=Path(td)/'compose.yaml';yml.write_text('services: {}');(yml.parent/'.env').write_text('PBL4_DB_PASSWORD=QA_LOCAL')
            result=subprocess.CompletedProcess([],0,'HTTP 200\n{"backends":[],"traffic":{"total":{}}}\n','')
            with patch.object(subprocess,'run',return_value=result):
                data=docker_operator.metrics(yml)
                self.assertEqual(data['backends'],[])
            with patch.object(subprocess,'run',return_value=subprocess.CompletedProcess([],0,'HTTP 503\n{}','')):
                with self.assertRaises(RuntimeError):docker_operator.metrics(yml)

    def test_exec_fail_closed(self):
        with tempfile.TemporaryDirectory() as td:
            yml=Path(td)/'compose.yaml';yml.write_text('services: {}');(yml.parent/'.env').write_text('PBL4_DB_PASSWORD=QA_LOCAL')
            with patch.object(subprocess,'run',return_value=subprocess.CompletedProcess([],1)):
                self.assertEqual(docker_operator.main(['metrics','--compose-file',str(yml)]),1)

if __name__=='__main__':unittest.main(verbosity=2)
