#!/usr/bin/env python3
import json
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import tempfile
import threading
import unittest
import lan_benchmark as lab

class TestHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path!='/work?cost=1':self.send_error(404);return
        body=b'PBL4 LAN probe OK'
        self.send_response(200)
        self.send_header('Content-Length',str(len(body)))
        self.send_header('X-Proxy-Backend','127.0.0.1:9001')
        self.end_headers();self.wfile.write(body)
    def log_message(self,*args):pass

class LanTest(unittest.TestCase):
    def test_safe_urls(self):
        for url in ['http://8.8.8.8:8080/work','http://169.254.0.1:8080/work','https://127.0.0.1:8080/work','http://127.0.0.1:8080/api/store/orders']:
            with self.assertRaises(ValueError):lab.endpoint(url)
        self.assertEqual(lab.endpoint('http://192.168.1.10:8080/work?cost=1'),
                         'http://192.168.1.10:8080/work?cost=1')
    def test_evidence_is_finite_and_grounded(self):
        server=ThreadingHTTPServer(('127.0.0.1',0),TestHandler)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                out=Path(tmp)/'evidence';url=f'http://127.0.0.1:{server.server_port}/work?cost=1'
                summary=lab.run(url,24,4,3,out)
                self.assertEqual(summary['successful'],24)
                self.assertIsNotNone(summary['p95_all_ms'])
                self.assertEqual(summary['distribution']['127.0.0.1:9001'],24)
                self.assertEqual((out/'requests.csv').read_text().count('\n'),25)
                self.assertIn('requests.csv',json.loads((out/'SHA256SUMS.json').read_text()))
                with self.assertRaises(ValueError):lab.run(url,24,4,3,out)
        finally:server.shutdown();server.server_close();thread.join(2)

if __name__=='__main__':unittest.main(verbosity=2)
