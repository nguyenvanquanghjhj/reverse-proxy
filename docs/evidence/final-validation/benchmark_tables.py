"""Render every aggregate row and every fault trial as reviewable Markdown."""
from pathlib import Path
import csv,json,math,statistics
qa=Path(__file__).parent
root=qa/"benchmark"
groups=json.loads((root/"aggregate.json").read_text(encoding="utf-8"))
runs=json.loads((root/"results.json").read_text(encoding="utf-8"))
names={"ROUND_ROBIN":"RR","LEAST_CONNECTIONS":"LC","ADAPTIVE":"Adaptive"}
def msd(value,d=2):return f"{value['mean']:.{d}f} ± {value['sd']:.{d}f}"
def number(value):return "CHƯA ĐO" if value is None else f"{value:.3f}"
latency=["### 5.1. Latency (ms, mean ± sample SD của ba trial)","",
    "| Case | Thuật toán | Mean | p50 | p95 | p99 |","|---|---|---:|---:|---:|---:|"]
outcomes=["### 5.2. Outcome và throughput (trung bình ± sample SD)","",
    "| Case | Thuật toán | Tổng | Thành công / thất bại | Attempts/s | Successes/s | HTTP lỗi | Client lỗi | HTTP lỗi (%) |",
    "|---|---|---:|---:|---:|---:|---:|---:|---:|"]
distribution=["### 5.3. Phân phối request trong cửa sổ đo", "",
    "| Case | Thuật toán | Dispatched B1 | B2 | B3 | Response thiếu backend ID |",
    "|---|---|---:|---:|---:|---:|"]
resources=["### 5.4. Tài nguyên quan sát", "",
    "Thứ tự trong mỗi bộ bốn số là **proxy / B1 / B2 / B3**. CPU time là trung bình tổng số giây CPU lấy được trong cửa sổ sampling của mỗi trial, không phải phần trăm CPU. RSS là peak quan sát lớn nhất trong ba trial (MiB), không phải heap. Host CPU là trung bình ± SD của giá trị host mean mỗi trial.", "",
    "| Case | Thuật toán | Process CPU time (s) | Peak RSS (MiB) | Host CPU (%) | Elapsed/trial min–max (s) |",
    "|---|---|---|---|---:|---:|"]
for g in groups:
    prefix=f"| {g['case']} | {names[g['strategy']]} |"
    latency.append(prefix+" "+" | ".join(msd(g[k]) for k in ("mean_ms","p50_ms","p95_ms","p99_ms"))+" |")
    er=g['http_error_rate']
    outcomes.append(prefix+f" {g['total']} | {g['success']} / {g['failed']} | {msd(g['attempted_rps'])} | {msd(g['successful_rps'])} | {g['http_errors']} | {g['client_errors']} | {er['mean']*100:.3f} ± {er['sd']*100:.3f} |")
    d=g['dispatched_metrics']
    distribution.append(prefix+f" {d['B1']} | {d['B2']} | {d['B3']} | {g['distribution']['(not selected)']} |")
    cpu=" / ".join(f"{g['sampled_cpu_seconds_mean'][k]:.2f}" for k in ("proxy","B1","B2","B3"))
    rss=" / ".join(f"{g['rss_peak_mib'][k]:.1f}" for k in ("proxy","B1","B2","B3"))
    trials=[r for r in runs if r['case']==g['case'] and r['strategy']==g['strategy']]
    elapsed=[r['elapsed_s'] for r in trials]
    resources.append(prefix+f" {cpu} | {rss} | {msd(g['host_cpu_mean_percent'])} | {min(elapsed):.3f}–{max(elapsed):.3f} |")
distribution += ["", "Dispatched là hiệu số counter từng backend giữa metrics-before và metrics-after, loại warm-up và health/management polling. Số ID trong raw.csv lấy từ X-Proxy-Backend của response; nhãn gốc '(not selected)' chỉ có nghĩa **không quan sát được ID**, không đủ chứng minh proxy chưa chọn backend. aggregate.json giữ riêng cả hai phép đếm để đối chiếu."]
resources += ["", "Sampling 200 ms có ít mẫu ở các trial dưới một giây, bỏ sót CPU trước mẫu đầu/sau mẫu cuối. Process khởi động lại được cộng theo PID riêng. Case D giữ observer qua đuôi chờ UP, nên tài nguyên D không có cùng cửa sổ với throughput. Không dùng bảng này để xếp hạng overhead CPU thuần của ba thuật toán."]
(qa/"benchmark-tables.md").write_text("\n\n".join("\n".join(x) for x in (latency,outcomes,distribution,resources)),encoding="utf-8")
failure=["| Thuật toán / repeat | HTTP 200 / 502 | DOWN sau kill (s) | Alive sau restart (s) | Request B1 thành công sau restart (s) | Full UP sau restart (s) | Generator lag p95 (ms) |",
    "|---|---:|---:|---:|---:|---:|---:|"]
for r in runs:
    if not r['case'].startswith('D_'):continue
    rec=r['recovery'];status=r['statuses']
    failure.append(f"| {names[r['strategy']]} / {r['repeat']} | {status.get('200',0)} / {status.get('502',0)} | {number(rec['detection_after_kill_s'])} | {number(rec['alive_after_restart_s'])} | {number(rec['success_after_restart_s'])} | {number(rec['full_up_after_restart_s'])} | {r['generator_lag_p95_ms']:.2f} |")
(qa/"failure-table.md").write_text("\n".join(failure),encoding="utf-8")
print("Rendered 21 aggregate rows in four metric tables and 9 fault trials.")
mixed=[]
mixed_md=["| Thuật toán | Path | Tổng | HTTP lỗi | Mean latency (ms) | p95 latency (ms) |",
          "|---|---|---:|---:|---:|---:|"]
for strategy in names:
    selected=[r for r in runs if r['case']=='E_mixed_c12' and r['strategy']==strategy]
    for path in ("/work?cost=1","/slow","/products"):
        per_trial=[]
        for trial in selected:
            with (root/trial['name']/"raw.csv").open(encoding="utf-8",newline="") as f:
                rows=[r for r in csv.DictReader(f) if r['path']==path]
            latency=sorted(float(r['latency_ms']) for r in rows)
            per_trial.append({"total":len(rows),"http_errors":sum(int(r['status'])>=400 for r in rows),
                              "mean_ms":statistics.mean(latency),
                              "p95_ms":round(latency[math.ceil(len(latency)*.95)-1],2)})
        item={"strategy":strategy,"path":path,"trials":per_trial,
              "total":sum(r['total'] for r in per_trial),"http_errors":sum(r['http_errors'] for r in per_trial)}
        for key in ("mean_ms","p95_ms"):
            item[key]={"mean":statistics.mean(r[key] for r in per_trial),
                       "sd":statistics.stdev(r[key] for r in per_trial)}
        mixed.append(item)
        mixed_md.append(f"| {names[strategy]} | {path} | {item['total']} | {item['http_errors']} | {msd(item['mean_ms'])} | {msd(item['p95_ms'])} |")
(qa/"mixed-by-path.json").write_text(json.dumps(mixed,indent=2),encoding="utf-8")
(qa/"mixed-table.md").write_text("\n".join(mixed_md),encoding="utf-8")
