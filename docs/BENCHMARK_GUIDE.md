# Benchmark: đo đúng, đọc đủ

Đây là thí nghiệm các JVM trên cùng host. Chạy đúng JDK 21; không cần Maven cho
benchmark. Các Python script đo/verify chỉ dùng standard library. Matplotlib chỉ
cần lúc tạo chart, không phải dependency của proxy.

## Chạy nhanh và chạy có lặp

```powershell
python scripts/benchmark.py --java "$env:JAVA_HOME/bin/java.exe" --quick --scenarios mixed --output benchmarks/results/my-smoke
python scripts/verify_benchmark.py benchmarks/results/my-smoke

python scripts/benchmark.py --java "$env:JAVA_HOME/bin/java.exe" --requests 1200 --warmup-requests 300 --rate 60 --concurrency 64 --seed 20260927 --repeats 3 --sample-interval 0.5 --scenarios mixed --output benchmarks/results/my-mixed
python scripts/verify_benchmark.py benchmarks/results/my-mixed
```

Output phải là thư mục mới. Runner tự mở port tạm, biên dịch frozen snapshot ngoài
target/classes và quản lý các JVM nó tạo. Không chạy workload khác đồng thời. Giữ
trace tắt khi đo; rate limiter mặc định tắt. Circuit mặc định bật cho cả ba strategy.
Không thay timeout/seed/capacity/admission giữa các strategy để tìm kết quả đẹp.

| Scenario | Backend và request |
|---|---|
| homogeneous | 3×(4 slot, 30ms), chỉ `/work?cost=1` |
| heterogeneous | 8/4/2 slot, delay 20/60/160ms, cùng light request |
| mixed | 3×(4 slot, 20ms), 60% light / 10% slow / 30% CPU |
| timeout | Chỉ slow; read=300ms, request=500ms cho mọi strategy |
| errors | light / upstream 429 / upstream 503 cố định |
| recovery | Health outage rồi recovery ở thời điểm cố định |

`--quick` = 120 đo + 30 warmup, một repeat: smoke pipeline, không làm bằng chứng
thắng/thua. Chạy tối thiểu ba repeat để thấy dao động và quay vòng thứ tự strategy.
Mixed chuẩn dùng 1200 đo + 300 warmup mỗi run; 9 run = 10.800 measured requests,
không cộng 2.700 warmup vào counts.

## Kết quả và định nghĩa

| Chỉ số | Định nghĩa |
|---|---|
| Total / success / failed | Request đo / HTTP 2xx / mọi non-2xx hoặc lỗi client |
| Error rate | failed / total; không loại lỗi 503 nhanh |
| Success RPS | success / max(cửa sổ gửi dự kiến, completion cuối) |
| Avg/p50/p95/p99 | Latency successful từ thời điểm gửi dự kiến; nearest rank |
| All-outcomes latency | Gồm lỗi/timeout; đọc cùng successful latency |
| Client lag | Thời điểm gửi thực − thời điểm dự kiến; phản ánh bottleneck load generator |
| Per-type | Count/error/latency từng light/slow/CPU; phải xem cả fairness |
| Per-backend | Delta dispatched/received/completed giữa boundaries đã drain |

`summary.csv` giữ từng run; `request_types.csv` giữ từng route/run;
`backends.csv` giữ từng backend/run. `aggregate.csv` giữ mean/stddev/median/min/max
các run. Median của p95 từng run **không phải p95 gộp**. Bộ chart dùng total/success/
failed **sum qua repeats**, success RPS và latency **median từng run**; error rate là
tỷ lệ từ summed counts. Không gọi tổng 3600 là 1200 request một run.

Verifier kiểm tra manifest/hash, schedule seed, đủ run, warmup, thống kê tính lại
từ raw rows, telemetry/boundaries và counter. Không thay artifact để chữa hash lỗi.
Nếu snapshot cũ cần verifier khác, giữ dưới nhãn lịch sử hoặc chạy lại; không waive
validator rồi trình bày như dữ liệu hiện tại. Hash là toàn vẹn, không là chữ ký.

## Tạo report và chart

```powershell
python tools/generate_charts.py --source benchmarks/results/my-mixed --scenario mixed --bundle-output benchmarks/presentation/my-mixed --output docs/assets/charts/my-mixed
```

Lệnh verify trước khi export. Bundle nhỏ (CSV/provenance) có thể giữ trong Git để
clone mới vẫn regenerate được chart. Full raw/frozen source/classes vẫn ở results
ignored, cần lưu riêng nếu nộp toàn bộ artifact. Xem [VISUALIZATION_GUIDE](VISUALIZATION_GUIDE.md).

## Đọc nhận xét khi bảo vệ

1. Bắt đầu từ total, success, failed và success RPS; không chỉ latency successful.
2. Xem p95/p99, latency gồm lỗi và client lag; ghi cả range qua repeats.
3. Mở breakdown slow. RPS tổng tốt hơn không có nghĩa mọi loại request tốt hơn.
4. Giải thích backend distribution là kết quả routing; chia đều số request không
   phải mục tiêu bắt buộc của Adaptive.
5. So sánh trong cùng experiment/config; thay version circuit/timeout hoặc môi
   trường làm mất cơ sở cho tuyên bố nhân quả giữa các đợt đo.

Chi tiết schema và phương pháp gốc: [BENCHMARK](BENCHMARK.md). Resilience là một
thí nghiệm behavior/failure isolation riêng, không dùng để xếp hạng throughput.
