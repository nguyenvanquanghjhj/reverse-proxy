#!/usr/bin/env python3
"""Export verified benchmark results, then render an offline PBL4 report (Matplotlib only)."""
import argparse
import csv
import hashlib
import html
import json
import math
import os
from pathlib import Path
import statistics
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import verify_benchmark

STRATEGIES = ('ROUND_ROBIN', 'LEAST_CONNECTIONS', 'ADAPTIVE')
LABELS = ('Luân phiên', 'Ít kết nối nhất', 'Thích nghi')
COLORS = ('#475569', '#2563eb', '#0f766e')
RUN_FIELDS = ('scenario', 'repeat', 'strategy', 'requests', 'successes', 'errors', 'error_rate',
              'offered_rps', 'measurement_seconds', 'success_rps', 'avg_ms', 'p50_ms', 'p95_ms', 'p99_ms',
              'all_outcomes_p95_ms', 'all_outcomes_p99_ms', 'client_lag_p95_ms', 'client_lag_warning')
BACKEND_FIELDS = ('scenario', 'repeat', 'strategy', 'backend', 'received_requests', 'dispatched_requests',
                  'completed_upstream', 'upstream_errors', 'successful_client_responses')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_csv(path):
    with path.open(newline='', encoding='utf-8') as stream:
        return list(csv.DictReader(stream))


def write_csv(path, rows):
    require(bool(rows), f'No rows for {path.name}')
    with path.open('w', newline='', encoding='utf-8') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + '\n', encoding='utf-8')


def numeric(row, key):
    value = row.get(key, '')
    if value == '':
        return None
    result = float(value)
    require(math.isfinite(result) and result >= 0, f'Invalid {key}: {value}')
    return result


def export_bundle(source, destination, scenario):
    # Never waive failed validation or mutate the raw experiment to make a chart.
    frozen=source/'verify_benchmark.py'
    if frozen.is_file():
        # A different checkout/OS may have different runner bytes (even CRLF).
        # Use the actual captured runner+validator pair, not today's imported runner.
        subprocess.run([sys.executable,str(frozen.resolve()),str(source.resolve())],check=True)
        validation='Frozen verify_benchmark.py passed before export'
    else:
        verify_benchmark.verify(source)
        validation='verify_benchmark.verify passed before export'
    metadata = json.loads((source / 'metadata.json').read_text(encoding='utf-8'))
    rows = [r for r in read_csv(source / 'summary.csv') if r['scenario'] == scenario]
    require(bool(rows), f'Scenario absent: {scenario}')
    destination.mkdir(parents=True, exist_ok=False)
    runs = [{k: r.get(k, '') for k in RUN_FIELDS} for r in rows]
    types = [r for r in read_csv(source / 'request_types.csv') if r['scenario'] == scenario]
    backends = [{k: r[k] for k in BACKEND_FIELDS} for r in read_csv(source / 'backends.csv') if r['scenario'] == scenario]
    for name, table in (('runs.csv', runs), ('request_types.csv', types), ('backends.csv', backends)):
        write_csv(destination / name, table)
    write_json(destination / 'provenance.json', {
        'schema_version': 1, 'scenario': scenario, 'source_directory': source.name,
        'source_validation': validation,
        'metadata': {k: metadata.get(k) for k in ('timestamp_utc', 'git_commit', 'git_dirty', 'java', 'platform',
                     'logical_cpus', 'requests', 'warmup_requests', 'offered_rps', 'concurrency', 'seed', 'repeats', 'quick')},
        'source_sha256': {name: digest(source / name) for name in ('metadata.json', 'complete.json', 'summary.csv', 'request_types.csv', 'backends.csv')},
        'bundle_sha256': {p.name: digest(p) for p in sorted(destination.glob('*.csv'))},
        'limitation': 'Bundle supports offline presentation; full raw/snapshot validation needs the original result directory.'})


def load_bundle(directory):
    provenance = json.loads((directory / 'provenance.json').read_text(encoding='utf-8'))
    for name in ('runs.csv', 'request_types.csv', 'backends.csv'):
        require(digest(directory / name) == provenance['bundle_sha256'][name], f'Bundle checksum mismatch: {name}')
    runs, types, backends = (read_csv(directory / name) for name in ('runs.csv', 'request_types.csv', 'backends.csv'))
    require(bool(runs), 'Empty run table')
    identities = {(r['repeat'], r['strategy']) for r in runs}
    require(len(identities) == len(runs), 'Duplicate run')
    for repeat in {r['repeat'] for r in runs}:
        group = [r for r in runs if r['repeat'] == repeat]
        require({r['strategy'] for r in group} == set(STRATEGIES), 'Incomplete strategy comparison')
        require(len({r['requests'] for r in group}) == len({r['offered_rps'] for r in group}) == 1, 'Unmatched workload')
    for r in runs:
        for key in RUN_FIELDS[3:]:
            if key != 'client_lag_warning':
                numeric(r, key)
        total, success, failed = (numeric(r, k) for k in ('requests', 'successes', 'errors'))
        require(total > 0 and total == success + failed, 'Outcome counts differ')
        require(math.isclose(numeric(r, 'error_rate'), failed / total), 'Error rate differs')
        selected = lambda table: [v for v in table if (v['repeat'], v['strategy']) == (r['repeat'], r['strategy'])]
        request_types = selected(types)
        require(sum(numeric(t, 'requests') for t in request_types) == total, 'Type counts differ')
        require(sum(numeric(t, 'successes') for t in request_types) == success, 'Type successes differ')
        require(sum(numeric(b, 'dispatched_requests') for b in selected(backends)) <= total, 'Too many backend dispatches')
        require(sum(numeric(b, 'successful_client_responses') for b in selected(backends)) == success, 'Backend successes differ')
    return provenance, runs, types, backends


def summarize(runs):
    output = []
    for strategy in STRATEGIES:
        selected = [r for r in runs if r['strategy'] == strategy]
        row = {'strategy': strategy, 'runs': len(selected)}
        for field in ('requests', 'successes', 'errors'):
            row[field] = int(sum(numeric(r, field) for r in selected))
        row['error_percent'] = 100 * row['errors'] / row['requests']
        for field in ('success_rps', 'avg_ms', 'p50_ms', 'p95_ms', 'p99_ms', 'all_outcomes_p95_ms', 'client_lag_p95_ms'):
            values = [numeric(r, field) for r in selected if numeric(r, field) is not None]
            for suffix, function in (('median', statistics.median), ('min', min), ('max', max)):
                row[field + '_' + suffix] = function(values) if values else None
        output.append(row)
    return output


def display(value):
    return 'Không có' if value is None else f'{value:,.2f}' if isinstance(value, float) else str(value)


def table(headers, rows):
    cells = lambda values, tag: ''.join(f'<{tag}>{html.escape(str(v))}</{tag}>' for v in values)
    return '<table><thead><tr>' + cells(headers, 'th') + '</tr></thead><tbody>' + ''.join('<tr>' + cells(r, 'td') + '</tr>' for r in rows) + '</tbody></table>'


def render(directory, output):
    provenance, runs, types, backends = load_bundle(directory)
    summary = summarize(runs)
    output.mkdir(parents=True, exist_ok=False)
    os.environ.setdefault('MPLCONFIGDIR', str(ROOT / '.demo/matplotlib'))
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 11, 'axes.spines.top': False,
                         'axes.spines.right': False, 'svg.fonttype': 'none', 'svg.hashsalt': 'pbl4'})
    figures = []
    def save(fig, name, title):
        fig.tight_layout()
        for ext in ('svg', 'png'):
            fig.savefig(output / f'{name}.{ext}', dpi=160, metadata={'Date': None} if ext == 'svg' else None)
        plt.close(fig); figures.append((name, title))
    def bars(name, title, field, ylabel):
        fig, ax = plt.subplots(figsize=(8, 4))
        values = [r[field] for r in summary]
        columns = ax.bar(LABELS, [v if v is not None else float('nan') for v in values], color=COLORS, width=.55)
        ax.set(title=title, ylabel=ylabel); ax.set_ylim(bottom=0)
        if field == 'success_rps_median':
            lower = [r['success_rps_min'] for r in summary]; upper = [r['success_rps_max'] for r in summary]
            ax.errorbar(range(3), values, yerr=[[v-lo for v,lo in zip(values,lower)], [hi-v for v,hi in zip(values,upper)]],
                        fmt='none', color='#172033', capsize=6)
            for i, (value, hi) in enumerate(zip(values, upper)):
                ax.annotate(display(value), (i, hi), xytext=(0,7), textcoords='offset points', ha='center')
        else:
            ax.bar_label(columns, labels=[display(v) for v in values], padding=5)
        # Explicit headroom must include error-bar maxima, not only bar heights.
        ceiling = max(upper) if field == 'success_rps_median' else max((v for v in values if v is not None), default=0)
        ax.set_ylim(0, max(1, ceiling * 1.18))
        save(fig, name, title)
    fig, ax = plt.subplots(figsize=(8, 4))
    successes = [r['successes'] for r in summary]; errors = [r['errors'] for r in summary]
    a = ax.bar(LABELS, successes, color='#0f766e', label='Thành công')
    b = ax.bar(LABELS, errors, bottom=successes, color='#dc2626', label='Thất bại')
    ax.bar_label(a, label_type='center'); ax.bar_label(b, labels=[str(v) if v else '' for v in errors], label_type='center')
    ax.set(title='Kết quả yêu cầu — cộng các lần lặp', ylabel='Số yêu cầu'); ax.legend(loc='upper center', bbox_to_anchor=(.5,-.12), ncol=2)
    save(fig, 'outcomes', 'Kết quả yêu cầu')
    bars('throughput', 'Tốc độ thành công — trung vị và khoảng nhỏ nhất–lớn nhất', 'success_rps_median', 'Yêu cầu thành công / giây')
    bars('errors', 'Tỷ lệ lỗi — gồm mọi phản hồi ngoài 2xx', 'error_percent', 'Yêu cầu thất bại (%)')
    fig, ax = plt.subplots(figsize=(8, 4))
    for i, (field, label) in enumerate((('avg_ms_median','Trung bình'),('p95_ms_median','p95'),('p99_ms_median','p99'))):
        ax.bar([x + (i-1)*.23 for x in range(3)], [r[field] if r[field] is not None else float('nan') for r in summary], width=.23, label=label)
    ax.set_xticks(range(3), LABELS); ax.set(title='Độ trễ thành công — trung vị thống kê từng lần chạy', ylabel='Độ trễ từ lịch gửi đến hoàn tất (ms)'); ax.legend()
    save(fig, 'latency', 'Độ trễ thành công')
    breakdown = []
    kinds = sorted({r['kind'] for r in types})
    fig, ax = plt.subplots(figsize=(9, 4))
    for index, strategy in enumerate(STRATEGIES):
        percentages = []
        for kind in kinds:
            group = [r for r in types if r['strategy'] == strategy and r['kind'] == kind]
            count = int(sum(numeric(r,'requests') for r in group)); success = int(sum(numeric(r,'successes') for r in group))
            failure = 100 * (count-success) / count
            type_row = dict(strategy=strategy, kind=kind, requests=count, successes=success, errors=count-success, error_percent=failure)
            for metric in ('avg_ms', 'p95_ms', 'p99_ms'):
                values = [numeric(r,metric) for r in group if numeric(r,metric) is not None]
                type_row[metric+'_median'] = statistics.median(values) if values else None
            breakdown.append(type_row)
            percentages.append(failure)
        ax.bar([x + (index-1)*.23 for x in range(len(kinds))], percentages, width=.23, color=COLORS[index], label=LABELS[index])
    ax.set_xticks(range(len(kinds)), [{'light':'Nhẹ','slow':'Chậm','cpu':'CPU'}.get(k,k) for k in kinds]); ax.set(title='Tỷ lệ lỗi theo loại yêu cầu', ylabel='Yêu cầu thất bại (%)'); ax.legend()
    save(fig, 'request-types', 'Công bằng theo loại yêu cầu')
    fig, ax = plt.subplots(figsize=(8, 4)); bottoms = [0]*3
    for backend in sorted({r['backend'] for r in backends}):
        values = [int(sum(numeric(r,'dispatched_requests') for r in backends if r['strategy']==s and r['backend']==backend)) for s in STRATEGIES]
        ax.bar(LABELS, values, bottom=bottoms, label=backend.replace('backend-','Máy chủ ')); bottoms = [a+b for a,b in zip(bottoms,values)]
    ax.set(title='Phân phối tới máy chủ — cộng các lần lặp', ylabel='Số yêu cầu được cấp giữ chỗ'); ax.legend()
    save(fig, 'backends', 'Phân phối theo máy chủ')
    write_csv(output / 'overall-summary.csv', summary); write_csv(output / 'request-type-summary.csv', breakdown)
    headers = ('Thuật toán','Tổng','Thành công','Thất bại','Thành công/giây','Lỗi %','TB ms','p50 ms','p95 ms','p99 ms')
    rows = [[LABELS[i], r['requests'],r['successes'],r['errors'],display(r['success_rps_median']),display(r['error_percent']),
             display(r['avg_ms_median']),display(r['p50_ms_median']),display(r['p95_ms_median']),display(r['p99_ms_median'])] for i,r in enumerate(summary)]
    markdown = '| ' + ' | '.join(headers) + ' |\n|' + '|'.join(['---']*len(headers)) + '|\n'
    markdown += ''.join('| ' + ' | '.join(map(str,row)) + ' |\n' for row in rows)
    (output / 'overall-summary.md').write_text(markdown, encoding='utf-8')
    warning = 'Chỉ là phép chạy thử; chưa đủ để kết luận hiệu năng.' if provenance['metadata'].get('quick') else 'Thí nghiệm trên một máy; đọc khoảng dao động và từng loại yêu cầu trước khi kết luận.'
    sections = ''.join(f'<section><h2>{title}</h2><img src="{name}.svg" alt="{title}" loading="lazy"></section>' for name,title in figures)
    content = '<!doctype html><html lang="vi"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Báo cáo hiệu năng PBL4</title>'
    content += '<style>body{font:16px system-ui;color:#172033;max-width:1120px;margin:32px auto;padding:0 20px}h1,h2{font-weight:600}table{border-collapse:collapse;width:100%;font-size:14px}td,th{padding:10px;text-align:right;border-bottom:1px solid #ddd}td:first-child,th:first-child{text-align:left}img{width:100%;max-width:900px}section{margin:40px 0}p{line-height:1.6}</style>'
    scenario_label={'mixed':'Hỗn hợp','heterogeneous':'Máy chủ khác năng lực','homogeneous':'Máy chủ đồng nhất'}.get(provenance['scenario'],provenance['scenario'])
    content += '<h1>Proxy ngược — so sánh hiệu năng</h1><p>' + html.escape(provenance['source_directory']) + ' · ' + html.escape(scenario_label) + '</p><p>' + warning + '</p>'
    meta = provenance['metadata']
    content += '<p>' + html.escape(f"Yêu cầu đo/lần: {meta.get('requests')}; làm nóng/lần: {meta.get('warmup_requests')}; tải gửi/giây: {meta.get('offered_rps')}; số lần lặp: {meta.get('repeats')}; hạt giống lịch gửi: {meta.get('seed')}; UTC: {meta.get('timestamp_utc')}") + '</p>'
    content += '<p>Số lượng cộng các lần lặp. Tốc độ và độ trễ thành công là trung vị của thống kê từng lần chạy, không phải phân vị gộp. Độ trễ gồm trễ lịch gửi ở máy khách; giữ toàn bộ lỗi. Báo cáo không cập nhật trực tiếp.</p>'
    content += table(headers,rows) + sections + '<h2>Kết quả và độ trễ thành công theo loại yêu cầu</h2>' + table(('Thuật toán','Loại','Tổng','Thành công','Thất bại','Lỗi %','TB ms','p95 ms','p99 ms'),
              [[LABELS[STRATEGIES.index(r['strategy'])],{'light':'Nhẹ','slow':'Chậm','cpu':'CPU'}.get(r['kind'],r['kind']),r['requests'],r['successes'],r['errors'],display(r['error_percent']),
                display(r['avg_ms_median']),display(r['p95_ms_median']),display(r['p99_ms_median'])] for r in breakdown])
    content += '<h2>Dao động và kết quả gồm cả lỗi</h2>' + table(('Thuật toán','Tốc độ nhỏ nhất–lớn nhất','p95 mọi kết quả (ms)','p95 trễ lịch gửi (ms)'),
              [[LABELS[STRATEGIES.index(r['strategy'])],display(r['success_rps_min'])+'–'+display(r['success_rps_max']),display(r['all_outcomes_p95_ms_median']),display(r['client_lag_p95_ms_median'])] for r in summary])
    content += '<p>Thống kê từng lần lặp: overall-summary.csv. Nguồn gốc và mã kiểm tra: bundle dữ liệu thí nghiệm. Có thể dùng SVG/PNG trực tiếp trong bài trình bày.</p></html>'
    (output / 'index.html').write_text(content,encoding='utf-8')
    write_json(output / 'manifest.json', {'bundle_provenance_sha256':digest(directory/'provenance.json'),
        'generator_sha256':digest(Path(__file__)), 'matplotlib_version':matplotlib.__version__,
        'files':{p.name:digest(p) for p in sorted(output.iterdir()) if p.is_file()}})
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument('--source', type=Path, help='Full result directory; must pass the existing validator')
    inputs.add_argument('--bundle', type=Path, help='Previously exported, checksum-checked presentation data')
    parser.add_argument('--bundle-output', type=Path)
    parser.add_argument('--scenario', default='mixed')
    parser.add_argument('--output', type=Path, required=True, help='New chart/report directory; never overwrite old results')
    args = parser.parse_args()
    if args.source:
        if args.bundle_output is None:
            parser.error('--source requires --bundle-output')
        export_bundle(args.source, args.bundle_output, args.scenario)
    directory = args.bundle if args.bundle else args.bundle_output
    summary = render(directory, args.output)
    print(f'Saved {len(summary)} strategy summaries, SVG/PNG charts and offline report to {args.output}')


if __name__ == '__main__':
    main()
