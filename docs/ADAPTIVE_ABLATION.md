# Ablation Adaptive ngày 26/10/2026

Thí nghiệm nhỏ trên **máy Windows dùng trình chiếu**, JDK 21, source sạch tại
`fbaf979b4338683ce4edbc350b319c956bae8d58`. Đây là dữ liệu mới, độc lập với
benchmark bảo vệ ngày 21/10; không dùng để khẳng định refactor làm tăng hiệu năng.

## Thiết kế và phạm vi

Mỗi biến thể chạy ba lần, mỗi lần **300 request đo + 100 warmup**: Mixed
60% Light, 10% Slow, 30% CPU, offered rate 60 RPS, concurrency 64.
Tổng cộng 2.700 request đo; 900 warmup không tính vào bảng kết quả.
Trong mỗi lần lặp, các biến thể dùng lịch gửi giống hệt nhau và cùng seed
(`20260927`, `20260928`, `20260929`). Thứ tự chạy xoay vòng giữa ba lần lặp.

| Biến thể | Thay đổi trong bản sao source thí nghiệm |
|---|---|
| Full | Adaptive đầy đủ, không sửa source. |
| No work admission | Bỏ đúng điều kiện loại ứng viên khi forecast vượt work budget trong `Dispatcher`. |
| No exploration | Không thực hiện lượt exploration trong `AdaptiveLoadBalancer`; luôn chọn minimum score. |

Health/circuit, giới hạn count/global in-flight, deadline, lease, service learning,
penalty và ngoại lệ relearning vẫn giữ nguyên. Không có cờ tắt guard mới trong
ứng dụng production. Mỗi bản sao có source, patch, classes, config, runner và
validator đóng băng; bộ kiểm định xác nhận chỉ có đúng thay đổi dự kiến.

## Kết quả đo

Tỷ lệ thành công và lỗi được cộng từ ba lượt (900 request/biến thể). p95 trong
bảng là **trung vị của ba p95 trên phản hồi thành công**, đơn vị ms; không phải
percentile của tập gộp. RPS cũng là trung vị theo lượt. Lỗi, kể cả 503 trả nhanh,
vẫn nằm trong mẫu số tỷ lệ thành công.

| Biến thể | Thành công | Lỗi | Tỷ lệ thành công | p95 thành công (ms) | RPS thành công | Work rejection |
|---|---:|---:|---:|---:|---:|---:|
| Full | 893/900 | 7 | 99,22% | 2.035,3 | 40,32 | 6 |
| No work admission | 820/900 | 80 | 91,11% | 2.040,5 | 37,30 | 0 |
| No exploration | 857/900 | 43 | 95,22% | 2.048,0 | 40,40 | 8 |

Work rejection là counter từ chối theo estimated work, không phải tổng mọi lỗi.
Các loại lỗi và counters đầy đủ nằm trong raw từng lượt.

| Workload | Full: thành công; p95 ms | No work admission: thành công; p95 ms | No exploration: thành công; p95 ms |
|---|---|---|---|
| Light (540/biến thể) | 539/540 (99,81%); 580,5 | 498/540 (92,22%); 791,6 | 519/540 (96,11%); 716,2 |
| Slow (90/biến thể) | 84/90 (93,33%); 2.405,7 | 78/90 (86,67%); 2.728,3 | 79/90 (87,78%); 2.556,2 |
| CPU (270/biến thể) | 270/270 (100%); 821,0 | 244/270 (90,37%); 847,0 | 259/270 (95,93%); 1.072,7 |

Khoảng cách max−min tỷ lệ thành công giữa ba loại lần lượt là **6,67; 5,56;
8,33 điểm phần trăm**. No work admission có gap nhỏ nhất nhưng mọi loại đều có
tỷ lệ thành công thấp hơn Full. Vì vậy gap nhỏ hơn tự nó không chứng minh chất
lượng phục vụ hay fairness tốt hơn. Full vẫn có bất lợi rõ cho Slow so với CPU.

## Biến động và giới hạn

| Lần lặp | Full: lỗi; client lag p95 ms | No work admission: lỗi; client lag p95 ms | No exploration: lỗi; client lag p95 ms |
|---|---|---|---|
| 1 | 6; 14,3 | 76; **343,7 (cảnh báo)** | 41; **37,2 (cảnh báo)** |
| 2 | 0; 13,3 | 1; 15,1 | 0; 14,5 |
| 3 | 1; 14,9 | 3; 14,2 | 2; 14,5 |

Trong phép đo này, Full có tổng tỷ lệ thành công cao nhất và p95 thành công
thấp nhất cho từng loại. Tuy nhiên, chênh lệch lỗi tập trung nhiều ở lượt đầu;
máy phát tải của hai biến thể lược bỏ đều có cảnh báo lag ở lượt đó. Lịch gửi dự
kiến được ghép cặp, nhưng thời điểm phát thực tế không hoàn toàn giống nhau.
Không loại bỏ lượt xấu hoặc chạy lại để chọn số đẹp.

p95 tổng thể chỉ chênh khoảng 13 ms, trong khi chênh lệch giữa các lượt lớn hơn.
Không có cơ sở từ mẫu này để tuyên bố cải thiện p95 tổng thể có ý nghĩa thống kê.
Chỉ 90 mẫu Slow/biến thể và ba lần lặp; client, proxy và ba backend cùng host.
Đây là bằng chứng minh họa đánh đổi admission/exploration và cách kiểm định,
chưa cô lập hoàn toàn tác động tài nguyên máy hay chứng minh hiệu quả đa máy.
Benchmark đa máy/VM vẫn là việc tiếp theo khi có môi trường độc lập.

## Bằng chứng và tái kiểm định

[summary.csv](../benchmarks/ablation/20261026/summary.csv),
[kế hoạch và source commit](../benchmarks/ablation/20261026/plan.json),
[raw ZIP](../benchmarks/ablation/20261026/raw-artifacts.zip) và
[SHA-256 manifest](../benchmarks/ablation/20261026/archive-manifest.json) được giữ
trong thư mục riêng. ZIP chứa raw requests, schedule, telemetry/counters,
metadata/config, source/class/patch và công cụ kiểm định đóng băng.

```powershell
python tools/ablation.py --verify-archive benchmarks/ablation/20261026
```

Lệnh kiểm SHA, giải nén an toàn, gọi verifier đóng băng, đối chiếu patch source,
chín lượt, seed/schedule ghép cặp và tính lại summary từ raw. CI Windows/Ubuntu
chạy lệnh này khi bundle hiện diện; CI không chạy lại phép đo hiệu năng để thay số.

Để đo một bộ mới, dùng JDK 21 và checkout đã commit, sạch:

```powershell
python tools/ablation.py --java "$env:JAVA_HOME/bin/java.exe" --output benchmarks/results/ablation-new
python tools/ablation.py --verify benchmarks/results/ablation-new --package benchmarks/ablation/new-run
```

Output phải là đường dẫn mới. Xem [build và tổng duyệt](BUILD_AND_DEFENSE_READINESS.md)
để chọn JDK và đọc trace thật trên Decision Explorer.
