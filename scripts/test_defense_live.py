"""Dependency-free integration check of defense_live against a mock local proxy.
Run: python scripts/test_defense_live.py. Does not access the real project or database.
"""
import http.server
import json
import tempfile
from pathlib import Path
import threading
from types import SimpleNamespace
from urllib.parse import urlsplit
import defense_live as demo


class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.strategy = 'ADAPTIVE'
        self.backends = [f'127.0.0.1:{port}' for port in (9001, 9002, 9003)]
        self.drain = set()
        self.cursor = 0


class MockProxy(http.server.BaseHTTPRequestHandler):
    state = State()

    def log_message(self, *_):
        pass

    def respond(self, code, result, headers=None):
        data = json.dumps(result).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        s = self.state
        with s.lock:
            if urlsplit(self.path).path == '/__proxy/metrics':
                self.respond(200, {'strategy': s.strategy,
                    'backends': [{'id': key, 'draining': key in s.drain} for key in s.backends]})
                return
            eligible = [b for b in s.backends if b not in s.drain]
            chosen = eligible[s.cursor % len(eligible)]
            s.cursor += 1
            strategy = s.strategy
        if urlsplit(self.path).path in ('/work', '/slow', '/compute'):
            self.respond(200, {'ok': True}, {'X-Proxy-Backend': chosen,
                                             'X-Proxy-Strategy': strategy})
        else:
            self.respond(404, {'error': 'not-found'})

    def do_POST(self):
        s = self.state
        with s.lock:
            if self.path == '/__proxy/strategy' and self.headers.get('X-Proxy-Action') == 'cycle-strategy':
                s.strategy = demo.ORDER[(demo.ORDER.index(s.strategy) + 1) % len(demo.ORDER)] if s.strategy != 'ADAPTIVE' else 'ROUND_ROBIN'
                self.respond(200, {'strategy': s.strategy})
            elif self.path == '/__proxy/backend-drain' and self.headers.get('X-Proxy-Action') == 'set-backend-draining':
                backend = self.headers.get('X-Proxy-Target')
                if backend not in s.backends:
                    return self.respond(400, {'error': 'unknown backend'})
                if self.headers.get('X-Proxy-Draining') == 'true':
                    s.drain.add(backend)
                else:
                    s.drain.discard(backend)
                self.respond(200, {'success': True})
            else:
                self.respond(403, {'error': 'missing operator action'})


def check():
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), MockProxy)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f'http://127.0.0.1:{server.server_port}'
    try:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            options = dict(base=base, path='/work?cost=2', requests=21,
                           workers=4, warmup=2, timeout=4, backend='127.0.0.1:9001')
            one = root / 'comparison'
            demo.run(SimpleNamespace(mode='compare', out=str(one), **options))
            assert MockProxy.state.strategy == 'ADAPTIVE', 'original strategy not restored'
            rows = list(__import__('csv').DictReader((one / 'raw-requests.csv').open()))
            assert len(rows) == 63 and len(json.loads((one / 'results.json').read_text())['phases']) == 3
            assert (one / 'report.html').exists() and (one / 'summary.md').exists()
            two = root / 'drain'
            demo.run(SimpleNamespace(mode='drain', out=str(two), **options))
            assert not MockProxy.state.drain, 'backend not resumed'
            rows = list(__import__('csv').DictReader((two / 'raw-requests.csv').open()))
            assert len(rows) == 63
            during = [row for row in rows if row['phase'] == 'during-drain']
            assert len(during) == 21 and all(row['backend'] != options['backend'] for row in during)
        print('PASS: isolated mock compare/drain, raw evidence, report generation, state restoration')
    finally:
        server.shutdown()
        server.server_close()


if __name__ == '__main__':
    check()
