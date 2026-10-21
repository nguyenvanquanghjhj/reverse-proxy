# Hướng dẫn biểu đồ và báo cáo

Bộ trình bày ở [assets/charts/defense-20261021/index.html](assets/charts/defense-20261021/index.html).
Mở file bằng browser ngay trên máy; không cần server, JavaScript, CDN hay tài khoản.
GitHub không render HTML như một website, nhưng xem SVG và CSV trực tiếp được.
Trang là báo cáo đo ngoại tuyến. Giám sát trực tiếp là trang local riêng, xem [DEFENSE_GUIDE](DEFENSE_GUIDE.md).

## Dữ liệu và tái tạo

Bundle nhỏ nằm ở `benchmarks/presentation/defense-20261021/`: runs, request_types,
backends CSV và provenance chứa runtime/tham số/hash. Nó được export sau khi full
raw experiment pass `scripts/verify_benchmark.py`. Bundle tái tạo biểu đồ sau khi clone. Bộ đầy đủ có trong
`benchmarks/defense/20261021/raw-artifacts.zip`, gồm raw requests, telemetry,
frozen source/classes/config/runner/verifier và cả bộ lịch sử có nhãn riêng.
Chạy `python tools/defense_artifacts.py --verify benchmarks/defense/20261021`
để giải nén tạm, kiểm tra hash và tính lại bảng; không cần chạy Java.

Python 3.11+ và Matplotlib (chỉ phần chart):

```powershell
python -m pip install -r tools/requirements-charts.txt
python tools/generate_charts.py --bundle benchmarks/presentation/defense-20261021 --output target/presentation-preview
```

Output phải là thư mục mới. Muốn vẽ dữ liệu mới và export bundle mới:

```powershell
python tools/generate_charts.py --source benchmarks/results/my-mixed --scenario mixed --bundle-output benchmarks/presentation/my-mixed --output docs/assets/charts/my-mixed
```

Lệnh không sửa raw benchmark, không thay workload và không tự chọn run tốt nhất.
File `manifest.json` ghi hash output, mã generator, phiên bản Matplotlib và provenance đầu vào. Hash giúp phát hiện
thay đổi file, không phải chứng minh độc lập kết quả đo là thật.

`.gitattributes` giữ nguyên byte của bundle/assets và LF cho chart generator để
checksum vẫn khớp khi clone giữa Windows/Linux, kể cả khi bật `core.autocrlf`.

## Dùng biểu đồ nào khi bảo vệ?

Chỉ giữ một bộ chính Mixed 60. Bảng ma trận đầy đủ nằm ở
[DEFENSE_PERFORMANCE](DEFENSE_PERFORMANCE.md); bộ hình lịch sử vẫn ở
[mixed-20261021](assets/charts/mixed-20261021/index.html), chỉ đổi nhãn sang tiếng Việt,
không đổi CSV đo. Không so checksum pixel giữa các hệ điều hành.
`python tools/check_reports.py --include-defense` đối chiếu các bảng, manifest,
SVG/PNG/HTML và liên kết; test renderer thực sự tạo sáu hình từ fixture.

| File SVG/PNG | Nội dung | Lưu ý khi trình bày |
|---|---|---|
| outcomes | Success/failed, tổng height là total | Sum cả ba repeats; không bỏ request lỗi |
| throughput | Success RPS | Median từng run, không đếm reject nhanh thành thành công |
| latency | Avg/p95/p99 successful | Median của thống kê từng run; chưa phải pooled percentile |
| errors | Failed/total (%) | Có cả proxy rejection, upstream error và timeout |
| request-types | Error % theo light/slow/CPU | Mở cùng throughput để thấy fairness/trade-off |
| backends | Dispatch theo backend | Không đồng nghĩa tất cả dispatch đã nhận/thành công |
| ../adaptive-flow.svg | Quy trình ECT và admission | Sơ đồ logic; score thật ở `/__proxy/decision` |

`overall-summary.md/csv` có total, success, failed, success RPS, error rate,
avg/p50/p95/p99. `request-type-summary.csv` có count/error và median successful
avg/p95/p99 từng loại. HTML bổ sung
RPS min–max, all-outcomes p95 và client lag để không chỉ cho thấy successful latency.
Mẫu không có success dùng `Không có`, không thay bằng latency 0.

Đề xuất 4 slide: kiến trúc + flow; outcomes/throughput; latency + error;
request-types + giới hạn. Dùng SVG khi slide hỗ trợ vector hoặc PNG 160 dpi.
Giữ captions về repeats, success-only latency và môi trường một host khi chụp chart.
