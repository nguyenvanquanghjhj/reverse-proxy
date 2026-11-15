#!/usr/bin/env python3
"""PBL4 live demo: bounded, read-only HTTP workload + safe local control-plane actions.

Creates raw CSV, metrics JSON, HTML and Markdown evidence. It never places an order,
changes stock, touches PostgreSQL or manipulates the existing test evidence.
Control-plane actions are deliberately restricted to 127.0.0.1.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import csv
from datetime import datetime, timezone
import html
import json
import math
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ORDER = ('ROUND_ROBIN', 'LEAST_CONNECTIONS', 'ADAPTIVE')
DEFAULT_BASE = 'http://127.0.0.1:8080'
HEADER_ACTION = 'X-Proxy-Action'


def api(base: str, path: str, *, method='GET', headers=None, body=None, timeout=6):
    req = urllib.request.Request(base + path, data=body, headers=headers or {}, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=timeout)
    except urllib.error.HTTPError as exc:
        response = exc
    with response:
        return response.status, response.read(), dict(response.headers)


def metrics(base: str):
    code, data, _ = api(base, '/__proxy/metrics')
    if code != 200:
        raise RuntimeError(f'Proxy metrics HTTP {code}; start the demo before running this script')
    doc = json.loads(data)
    if doc.get('strategy') not in ORDER or not isinstance(doc.get('backends'), list):
        raise RuntimeError('Unexpected metrics schema')
    return doc


def change_strategy(base: str, target: str):
    for _ in range(4):
        current = metrics(base)['strategy']
        if current == target:
            return
        code, data, _ = api(base, '/__proxy/strategy', method='POST', body=b'',
                            headers={HEADER_ACTION: 'cycle-strategy'})
        if code != 200:
            raise RuntimeError(f'Cannot cycle strategy: HTTP {code} {data[:150]!r}')
    raise RuntimeError(f'Could not reach strategy {target}')


def set_drain(base: str, backend: str, enabled: bool):
    code, data, _ = api(base, '/__proxy/backend-drain', method='POST', body=b'',
                        headers={HEADER_ACTION: 'set-backend-draining',
                                 'X-Proxy-Target': backend,
                                 'X-Proxy-Draining': str(enabled).lower()})
    if code != 200:
        raise RuntimeError(f'Drain operation HTTP {code}: {data[:200]!r}')


def percentile(values, fraction):
    if not values:
        return None
    sorted_values = sorted(values)
    return round(sorted_values[math.ceil(len(sorted_values) * fraction) - 1], 2)


def single_request(base: str, path: str, timeout: float, no: int, phase: str):
    start = time.perf_counter()
    request_id = f'pbl4-{uuid.uuid4().hex[:16]}'
    status = 0
    backend = ''
    strategy = ''
    error = ''
    try:
        status, _, headers = api(base, path, timeout=timeout,
                                 headers={'X-Request-Id': request_id})
        headers = {k.lower(): v for k, v in headers.items()}
        backend = headers.get('x-proxy-backend', '')
        strategy = headers.get('x-proxy-strategy', '')
    except Exception as exc:
        error = type(exc).__name__
    return {
        'phase': phase, 'index': no, 'utc': datetime.now(timezone.utc).isoformat(),
        'request_id': request_id, 'status': status, 'backend': backend,
        'strategy_header': strategy, 'latency_ms': round((time.perf_counter() - start) * 1000, 3),
        'client_error': error,
    }


def workload(base: str, path: str, timeout: float, n: int, concurrency: int, phase: str):
    if n < 1 or concurrency < 1:
        raise ValueError('n and concurrency must be positive')
    print(f'  Running {phase}: {n} requests, concurrency {concurrency}', flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(single_request, base, path, timeout, i, phase) for i in range(n)]
        rows = [future.result() for future in concurrent.futures.as_completed(futures)]
    return sorted(rows, key=lambda row: row['index'])


def summarize(rows):
    ok = [r for r in rows if 200 <= r['status'] < 400]
    counts = {}
    for row in rows:
        backend = row['backend'] or '(not dispatched)'
        counts[backend] = counts.get(backend, 0) + 1
    return {
        'attempted': len(rows), 'successful': len(ok),
        'http_errors': sum(r['status'] >= 400 for r in rows),
        'client_errors': sum(r['status'] == 0 for r in rows),
        'p50_ms_all': percentile([r['latency_ms'] for r in rows], .50),
        'p95_ms_all': percentile([r['latency_ms'] for r in rows], .95) if len(rows) >= 20 else None,
        'p95_ms_success': percentile([r['latency_ms'] for r in ok], .95) if len(ok) >= 20 else None,
        'distribution': dict(sorted(counts.items())),
    }


def write_results(out: Path, meta: dict, phases: list[dict], rows: list[dict]):
    with (out / 'raw-requests.csv').open('w', encoding='utf-8', newline='') as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    snapshot = {'meta': meta, 'phases': phases}
    (out / 'results.json').write_text(json.dumps(snapshot, indent=2, ensure_ascii=False), encoding='utf-8')
    md = ['# PBL4 – Bằng chứng trình diễn trực tiếp', '',
          '**Lưu ý:** Đây là phép đo mới trên môi trường tại thời điểm chạy script; '
          'không thay thế benchmark V7, không bảo đảm điều kiện thử giống nhau.', '',
          f"- Thời điểm (UTC): {meta['utc']}", f"- Proxy: {meta['base']}",
          f"- Request: `{meta['path']}`; số worker: {meta['workers']}",
          f"- Chế độ: {meta['mode']}", '',
          '| Giai đoạn | Thành công | Tổng | p50 (ms) | p95 all (ms) | p95 success (ms) | Backend |',
          '|---|---:|---:|---:|---:|---:|---|']
    for entry in phases:
        s = entry['stats']
        dist = ', '.join(f'{key}: {value}' for key, value in s['distribution'].items())
        md.append(f"| {entry['name']} | {s['successful']} | {s['attempted']} | {s['p50_ms_all']} | {s['p95_ms_all']} | {s['p95_ms_success']} | {dist} |")
    md += ['', '## Giới hạn',
           '- Client và backend có thể cùng ở một máy; đây không phải thí nghiệm LAN.',
           '- p95 dùng nearest-rank trong từng giai đoạn, tính trên mọi outcome.',
           '- Không tự kết luận một thuật toán luôn tốt hơn; đối chiếu các repeat và môi trường.',
           '- Kiểm tra raw-requests.csv và metrics-before/after JSON trước khi trích dẫn.', '']
    (out / 'summary.md').write_text('\n'.join(md), encoding='utf-8')

    cards = []
    longest = max((entry['stats']['p95_ms_all'] or 0 for entry in phases), default=1) or 1
    for entry in phases:
        s = entry['stats']
        p95 = s['p95_ms_all'] or 0
        bar = round(p95 / longest * 100, 2)
        name = html.escape(entry['name'])
        dist = ', '.join(f'{html.escape(k)}: {v}' for k, v in s['distribution'].items())
        cards.append(f'''<section class="card"><h2>{name}</h2><div class="kpis"><b>{s['successful']}/{s['attempted']}</b><span>HTTP 2xx/3xx</span><b>{p95:g} ms</b><span>p95, tất cả kết quả</span></div>
            <div class="track"><div class="fill" style="width:{bar}%"></div></div><p>Backend: {dist}</p></section>''')
    report = '''<!doctype html><html lang="vi"><meta charset="utf-8"><title>PBL4 | Live evidence</title>
<style>body{font:16px/1.55 system-ui,Segoe UI,sans-serif;background:#0c1220;color:#e7eefb;max-width:1100px;margin:35px auto;padding:0 20px}h1{font-size:32px;line-height:1.2}h2{font-size:18px;margin:0 0 16px}.muted,p{color:#c2cad8}.meta{padding:14px;border-left:3px solid #26d4ba;background:#142135}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:16px;margin-top:24px}.card{border:1px solid #304057;padding:20px;background:#152036;border-radius:12px}.kpis{display:grid;grid-template-columns:auto 1fr;align-items:baseline;gap:4px 16px}.kpis b{font-size:23px;color:#8ff2dd}.kpis span{font-size:12px}.track{background:#344257;height:10px;border-radius:10px;margin-top:20px}.fill{background:#27ceb2;height:10px;border-radius:10px}.note{margin-top:32px;font-size:14px}</style>
<h1>Reverse Proxy <span style="color:#27ceb2">/ Live Evidence</span></h1>
<div class="meta">''' + html.escape(f"{meta['utc']} | {meta['mode']} | {meta['path']} | concurrency {meta['workers']}") + '''</div><div class="grid">''' + ''.join(cards) + '''</div><p class="note">Số liệu từ phép đo trên hệ thống tại thời điểm chạy. Bản lưu local; không phải telemetry đang cập nhật hoặc bằng chứng LAN.</p></html>'''
    (out / 'report.html').write_text(report, encoding='utf-8')


def validate_base(base):
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost') or parsed.username or parsed.password or parsed.path not in ('', '/') or parsed.query or parsed.fragment:
        raise ValueError('Control-plane demonstration permits only http://127.0.0.1:<port>')
    return base.rstrip('/')


def run(args):
    base = validate_base(args.base)
    if args.path not in ('/work?cost=1', '/work?cost=2', '/slow', '/compute?cost=2'):
        raise ValueError('Use a bounded, non-mutating workload path; business APIs are prohibited')
    if not 20 <= args.requests <= 2000 or not 1 <= args.workers <= 48:
        raise ValueError('requests must be 20..2000 and workers 1..48')
    if not 1 <= args.timeout <= 20:
        raise ValueError('timeout must be 1..20 seconds')
    baseline = metrics(base)
    if not isinstance(baseline.get('backends'), list) or not baseline['backends']:
        raise RuntimeError('No backends announced; aborting')
    current = baseline['strategy']
    target_backend = args.backend
    if args.mode == 'drain':
        backend = next((b for b in baseline['backends'] if b.get('id') == target_backend), None)
        if backend is None:
            raise ValueError(f'Unknown backend {target_backend}; choose from metrics backends')
        if backend.get('draining'):
            raise RuntimeError('Backend already draining; refusing to change an existing operator state')
    out = Path(args.out).resolve()
    # Never overwrite an earlier benchmark or create files inside the project implicitly.
    out.mkdir(parents=True, exist_ok=False)
    meta = {'utc': datetime.now(timezone.utc).isoformat(), 'mode': args.mode,
            'base': base, 'path': args.path, 'workers': args.workers,
            'requests_per_phase': args.requests, 'original_strategy': current,
            'target_backend': target_backend if args.mode == 'drain' else None}
    (out / 'metrics-before.json').write_text(json.dumps(baseline, indent=2), encoding='utf-8')
    all_rows, phases = [], []
    modified = False
    try:
        if args.mode == 'compare':
            for strategy in ORDER:
                change_strategy(base, strategy)
                observed = metrics(base)['strategy']
                if observed != strategy:
                    raise RuntimeError('Strategy changed unexpectedly')
                # Warm-up not included in measured sample.
                workload(base, args.path, args.timeout, args.warmup, min(6, args.workers), 'warmup-' + strategy)
                name = strategy
                samples = workload(base, args.path, args.timeout, args.requests, args.workers, name)
                all_rows += samples
                phases.append({'name': name, 'stats': summarize(samples)})
        else:
            before = workload(base, args.path, args.timeout, args.requests, args.workers, 'before-drain')
            all_rows += before
            phases.append({'name': 'before-drain', 'stats': summarize(before)})
            set_drain(base, target_backend, True)
            modified = True
            time.sleep(.35)
            during = workload(base, args.path, args.timeout, args.requests, args.workers, 'during-drain')
            all_rows += during
            phases.append({'name': 'during-drain', 'stats': summarize(during)})
    finally:
        if modified:
            try:
                set_drain(base, target_backend, False)
                print('  Restored backend drain=false', flush=True)
            except Exception as exc:
                print(f'WARNING: Failed to resume backend {target_backend}: {exc}', file=sys.stderr, flush=True)
        if args.mode == 'compare':
            try:
                change_strategy(base, current)
                print('  Restored original strategy: ' + current, flush=True)
            except Exception as exc:
                print(f'WARNING: Restore original strategy manually: {exc}', file=sys.stderr, flush=True)
    if args.mode == 'drain':
        time.sleep(.35)
        after = workload(base, args.path, args.timeout, args.requests, args.workers, 'after-resume')
        all_rows += after
        phases.append({'name': 'after-resume', 'stats': summarize(after)})
    (out / 'metrics-after.json').write_text(json.dumps(metrics(base), indent=2), encoding='utf-8')
    write_results(out, meta, phases, all_rows)
    for entry in phases:
        print('  ', entry['name'], entry['stats'])
    print('Evidence saved to:', out, flush=True)
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument('mode', choices=('compare', 'drain'))
    parser.add_argument('--base', default=DEFAULT_BASE)
    parser.add_argument('--requests', type=int, default=90)
    parser.add_argument('--warmup', type=int, default=12)
    parser.add_argument('--workers', type=int, default=12)
    parser.add_argument('--timeout', type=float, default=6)
    parser.add_argument('--path', default='/work?cost=2')
    parser.add_argument('--backend', default='127.0.0.1:9001', help='Only relevant in drain mode')
    parser.add_argument('--out', required=True, help='A new, non-existing evidence directory')
    args = parser.parse_args()
    try:
        run(args)
    except Exception as exc:
        print(f'ERROR: {type(exc).__name__}: {exc}', file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
