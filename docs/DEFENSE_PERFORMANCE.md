# Đo hiệu năng phục vụ bảo vệ — 21/10/2026

## Phiên bản và phương pháp

Nguồn đo **`c2d5f20335ecef34ad10c5ff5c2c8a48b7601e8d`**, metadata ba mức tải mới ghi
`git_dirty=false`. Worktree detached riêng giữ sạch trong khi hoàn thiện tài liệu.
Build/test Java 21 và Python đã chạy trước khi bắt đầu; không thay thuật toán,
trọng số, lịch gửi, timeout hoặc bỏ lần chạy xấu.

| Điều kiện | Giá trị chốt trước khi đo |
|---|---|
| Hai kịch bản | Hỗn hợp (Mixed); máy chủ khác năng lực (Heterogeneous) |
| Tải gửi | 30, 60, 90 yêu cầu/giây; chạy tuần tự |
| Mỗi lần chạy | 300 đo + 100 làm nóng; 64 luồng phát; seed 20260927 |
| Lặp | 3 cho mỗi thuật toán/kịch bản/tải; xoay thứ tự thuật toán |
| Hỗn hợp | 60% Nhẹ / 10% Chậm / 30% CPU; ba máy chủ 4 slot, 20ms |
| Khác năng lực | 100% Nhẹ; capacity/delay 8/20ms, 4/60ms, 2/160ms |
| Timeout | Connect 500ms; read 3000ms; toàn yêu cầu 5000ms |
| Bảo vệ | Circuit bật; giới hạn tốc độ tắt; ghi quyết định tắt, giống nhau |
| Nền tảng | Windows 11, 16 CPU logic, Microsoft JDK 21.0.12.1+1; các JVM cùng host |
| JVM | Mỗi tiến trình `-Xms32m -Xmx128m`; một socket HTTP/1.1/yêu cầu; không retry |

Ma trận gồm **54 lần chạy, 16.200 yêu cầu đo và 5.400 làm nóng**. Mỗi ô
thuật toán/kịch bản/tải có 900 yêu cầu đo. Lịch được tạo lại từ seed và ghép cặp
cho cả ba thuật toán. Mẫu làm nóng và lỗi làm nóng được giữ riêng. Bộ ngắn hơn
lịch sử 1200 mẫu/lần nhằm mở rộng hai kịch bản và ba tải trong tài nguyên máy cục bộ;
không xem chênh lệch hai đợt là cải thiện do mã nguồn.

```powershell
# Chạy trên checkout/worktree sạch của commit đã chọn. Output phải chưa tồn tại.
python tools/run_defense_matrix.py --java "$env:JAVA_HOME/bin/java.exe" --output benchmarks/results/my-defense --loads 30 60 90 --requests 300 --warmup-requests 100 --repeats 3
python tools/defense_artifacts.py --source benchmarks/results/my-defense --output benchmarks/defense/my-defense
python tools/defense_artifacts.py --verify benchmarks/defense/my-defense
```

Đã dùng output riêng `benchmarks/results/defense-clean-20261021`, không ghi đè bộ
cũ. Source/classes/config/runner/verifier, từng request, telemetry, log và toàn bộ
kết quả được giữ trong [raw-artifacts.zip](../benchmarks/defense/20261021/raw-artifacts.zip).
[Manifest](../benchmarks/defense/20261021/manifest.json) ghi commit, kế hoạch và
SHA256. ZIP còn giữ bản sao nguyên byte bộ lịch sử dưới `history/` (metadata dirty
vẫn giữ nguyên) và thí nghiệm sạch `slow-probe/` gồm quan sát, cấu hình, log và
script. Không trộn 10.800 mẫu lịch sử vào 16.200 mẫu mới. Công cụ verify giải nén vào thư mục tạm, chạy từng frozen validator,
kiểm tra nguồn sạch và tính lại các bảng công bố. Hash chứng minh toàn vẹn, không
phải chữ ký về điều kiện vật lý của máy khi đo.

## Cách đọc thống kê

Counts cộng cả ba lần lặp. RPS/avg/p50/p95/p99 là **trung vị của thống kê từng
lần chạy**, không phải phân vị gộp. Độ trễ chính tính từ lịch gửi đến nhận đủ phản
hồi, gồm client scheduling lag. Success latency chỉ gồm HTTP 2xx; lỗi 503 nhanh
vẫn là thất bại. Tốc độ thành công dùng số 2xx chia
`max(cửa sổ lịch gửi, completion cuối)`, gồm thời gian drain.

Khoảng nhỏ nhất–lớn nhất cho thấy biến động, không phải confidence interval.
Mỗi lần chạy có khoảng 30 Slow; p99 từng loại gần mẫu lớn nhất, chưa đủ để suy ra
SLO. Client lag p95 và số lần có cảnh báo có trong
[matrix.csv](../benchmarks/defense/20261021/matrix.csv); không bỏ lượt phát tải bị trễ.

## Bảng kết quả chính

### Hỗn hợp ở 60 yêu cầu/giây

| Thuật toán | Tổng | Thành công | Thất bại | Thành công/giây | Lỗi % | TB ms | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|---|---|---|---|---|
| Luân phiên | 900 | 860 | 40 | 36.35 | 4.44 | 653.79 | 309.72 | 2,038.19 | 2,692.02 |
| Ít kết nối nhất | 900 | 889 | 11 | 38.26 | 1.22 | 514.00 | 100.12 | 2,033.25 | 2,654.08 |
| Thích nghi | 900 | 849 | 51 | 40.39 | 5.67 | 520.10 | 172.13 | 2,050.76 | 2,584.86 |

### Ma trận đầy đủ

Mỗi dòng cộng 900 mẫu. Khoảng RPS là nhỏ nhất–lớn nhất trong ba lần lặp.
Các cột avg/p50/p99 và khoảng độ trễ đầy đủ giữ nguyên trong CSV đi kèm.

| Kịch bản | Tải gửi/s | Thuật toán | Thành công | Thất bại | RPS thành công | Khoảng RPS | Lỗi % | p95 ms | p99 ms | p95 trễ lịch ms |
|---|---:|---|---:|---:|---:|---|---:|---:|---:|---:|
| Hỗn hợp | 30 | Luân phiên | 898 | 2 | 25.26 | 23.03–25.40 | 0.22 | 2021.33 | 2035.69 | 14.80 |
| Hỗn hợp | 30 | Ít kết nối nhất | 900 | 0 | 25.25 | 25.04–25.40 | 0.00 | 2028.94 | 2042.86 | 14.70 |
| Hỗn hợp | 30 | Thích nghi | 900 | 0 | 25.22 | 25.03–25.43 | 0.00 | 2026.35 | 2042.13 | 14.84 |
| Khác năng lực | 30 | Luân phiên | 900 | 0 | 29.65 | 29.62–29.67 | 0.00 | 198.91 | 204.09 | 14.89 |
| Khác năng lực | 30 | Ít kết nối nhất | 900 | 0 | 29.51 | 29.51–29.81 | 0.00 | 190.88 | 203.32 | 14.39 |
| Khác năng lực | 30 | Thích nghi | 900 | 0 | 29.57 | 29.56–29.76 | 0.00 | 63.67 | 108.03 | 14.94 |
| Hỗn hợp | 60 | Luân phiên | 860 | 40 | 36.35 | 35.60–39.56 | 4.44 | 2038.19 | 2692.02 | 15.95 |
| Hỗn hợp | 60 | Ít kết nối nhất | 889 | 11 | 38.26 | 37.67–39.96 | 1.22 | 2033.25 | 2654.08 | 15.15 |
| Hỗn hợp | 60 | Thích nghi | 849 | 51 | 40.39 | 39.12–42.43 | 5.67 | 2050.76 | 2584.86 | 14.85 |
| Khác năng lực | 60 | Luân phiên | 820 | 80 | 43.34 | 43.30–43.81 | 8.89 | 1349.26 | 1374.25 | 14.27 |
| Khác năng lực | 60 | Ít kết nối nhất | 900 | 0 | 58.14 | 58.02–59.15 | 0.00 | 189.25 | 199.39 | 14.62 |
| Khác năng lực | 60 | Thích nghi | 900 | 0 | 59.18 | 58.10–59.25 | 0.00 | 63.49 | 90.22 | 14.94 |
| Hỗn hợp | 90 | Luân phiên | 779 | 121 | 37.91 | 37.67–42.62 | 13.44 | 2568.38 | 3016.50 | 1091.81 |
| Hỗn hợp | 90 | Ít kết nối nhất | 846 | 54 | 39.79 | 35.87–40.23 | 6.00 | 2507.49 | 3295.49 | 1423.40 |
| Hỗn hợp | 90 | Thích nghi | 846 | 54 | 50.21 | 41.95–52.76 | 6.00 | 2079.24 | 2857.01 | 390.05 |
| Khác năng lực | 90 | Luân phiên | 762 | 138 | 54.80 | 54.73–55.48 | 15.33 | 1344.78 | 1361.74 | 14.80 |
| Khác năng lực | 90 | Ít kết nối nhất | 900 | 0 | 85.60 | 83.98–85.82 | 0.00 | 226.71 | 272.06 | 14.74 |
| Khác năng lực | 90 | Thích nghi | 900 | 0 | 87.99 | 85.20–88.21 | 0.00 | 59.76 | 98.17 | 14.87 |

### Điều dữ liệu hỗ trợ

- **Hỗn hợp 30:** LC/Adaptive đều 900/900 thành công; RPS khoảng 25,2 và các khoảng dao động chồng nhau. Chưa có lợi thế tốc độ đáng tin cậy cho Adaptive. RR mất 2 Slow, giữ nguyên trong số liệu.
- **Hỗn hợp 60:** Adaptive có trung vị RPS 40,39 so với LC 38,26, nhưng chỉ 849 thành công so với 889 và 51 lỗi so với 11. Avg/p95 của LC cũng thấp hơn; p99 của Adaptive thấp hơn. Không có thuật toán thắng mọi chỉ số. Run Adaptive đầu có 51 lỗi, hai run sau không lỗi; không loại run đầu.
- **Hỗn hợp 90:** LC/Adaptive cùng 846/900 thành công; Adaptive có RPS trung vị 50,21 so với 39,79, p95 2079 so với 2507ms. Tuy nhiên cả ba run mỗi thuật toán đều có cảnh báo trễ lịch; p95 lag của LC trung vị 1423ms, Adaptive 390ms. Kết quả gồm giới hạn của cả bộ phát tải, không khẳng định proxy duy trì đúng 90 yêu cầu/s thực tế hoặc suy ra công suất tối đa.
- **Khác năng lực:** Adaptive giảm p95 rõ trong ba tải (khoảng 60–64ms so với LC 189–227ms); LC và Adaptive đều không lỗi. RR có 80 lỗi ở 60 và 138 ở 90. RPS Adaptive/LC có khoảng dao động chồng nhau ở cả ba tải; ba lần lặp chưa đủ cho tuyên bố thắng thống kê hoặc phổ quát.

### Công bằng với tác vụ chậm trong bộ mới

| Tải | Luân phiên lỗi Slow | LC lỗi Slow | Adaptive lỗi Slow |
|---|---:|---:|---:|
| 30 | 2/90 (2,22%) | 0/90 | 0/90 |
| 60 | 18/90 (20,00%) | 11/90 (12,22%) | 10/90 (11,11%) |
| 90 | 38/90 (42,22%) | 25/90 (27,78%) | 24/90 (26,67%) |

Ở 60 và 90, chênh lệch Slow giữa LC/Adaptive chỉ **một phản hồi** trong 90;
chưa đủ kết luận đã chữa fairness. Bộ ngắn này không tái hiện thứ hạng Slow của
bộ lịch sử; thời lượng và lịch theo số mẫu khác, không phải thí nghiệm trước/sau.
[request-types.csv](../benchmarks/defense/20261021/request-types.csv) giữ đủ từng loại;
[status-breakdown.csv](../benchmarks/defense/20261021/status-breakdown.csv) tách
HTTP status/lỗi client theo tải, kịch bản, lần lặp, thuật toán và loại yêu cầu.



## Vì sao Slow lịch sử lỗi nhiều?

[Bộ lịch sử](history/PERFORMANCE_ANALYSIS.md) có 360 Slow/thuật toán. Tính lại raw cho kết
quả:

| Thuật toán | HTTP 200 | HTTP 503 | HTTP 504 | Lỗi Slow |
|---|---:|---:|---:|---:|
| Luân phiên | 162 | 125 | 73 | 55,00% |
| Ít kết nối nhất | 188 | 89 | 83 | 47,78% |
| Thích nghi | 159 | 121 | 80 | 55,83% |

Điều đã quan sát/chứng minh:

- 504 Slow có thời gian từ lúc gửi thực khoảng 3003–3048ms, phù hợp read timeout
  3000ms, khác deadline toàn yêu cầu 5000ms. Queue được lấy mẫu đến 28, active đến
  4 trên một backend. Có cả trạng thái DOWN/WARMING trong đo và ngay trước đo,
  dù runner đã chờ UP/slow-start trước khi làm nóng: tải làm nóng có thể gây lỗi
  và phục hồi lại. Không giả định backend luôn khỏe suốt cửa sổ đo.
- Tổng delta `work_rejected_total` của Adaptive là **94** (59/24/11), tính một lần
  cho counter toàn cục, không cộng ba bản sao trong telemetry. Đây là mọi loại
  yêu cầu; không thể gán toàn bộ **121 Slow 503** cho admission công việc. RR/LC có
  delta 0 và vẫn nhiều lỗi Slow: admission Adaptive không phải lời giải thích duy nhất.
- 60 RPS × 10% × 2 giây = **12 slot** chỉ cho Slow, bằng cả cụm 3×4 trước khi
  tính Nhẹ/CPU và overhead. Ở 90 RPS, nhu cầu riêng Slow là 18 slot. Định tuyến
  không tạo thêm năng lực; đây là giới hạn công suất ổn định, không dự báo chính
  xác số lỗi trong một lần đo ngắn có drain.
- Thí nghiệm kiểm soát bằng `tools/probe_slow.py`: một backend 1 slot, 20ms;
  prior ban đầu **50ms** nhưng `/slow` thực khoảng **2 giây**. Sau một mẫu thành
  công để học, khi đang có Slow, Slow tiếp theo dự báo khoảng 4 giây > ngân sách
  3 giây nên bị 503 `WORK_BUDGET`; Nhẹ dự báo khoảng 2,05 giây vẫn nhận và thành
  công. Trace giữ nguyên số giữ chỗ, không dùng dữ liệu giả. Cấu hình này khác
  ma trận hiệu năng, chỉ dùng chứng minh cơ chế.

Điều **chưa chứng minh**: bao nhiêu lỗi lịch sử do circuit, health, giới hạn giữ
chỗ hay work budget ở từng request; số mẫu dịch vụ Slow bị thiếu bao nhiêu; tác
động riêng của recovery/cold start/CPU/JIT. Trace lịch sử tắt, telemetry chỉ lấy mẫu
và không có transaction phân tán. Không gắn ảnh chụp mới vào request cũ để suy diễn.
Chưa có thử nghiệm fairness trước/sau ghép cặp, nên không đổi admission/penalty.

## Phạm vi sử dụng khi bảo vệ

Dùng một bộ sáu biểu đồ chính cho Mixed 60; bảng ma trận chứa đầy đủ hai kịch bản
và ba tải, tránh nhân bản hàng chục biểu đồ giống nhau. Bộ lịch sử vẫn giữ dữ liệu,
chỉ Việt hóa nhãn trình bày. Không tuyên bố Adaptive thắng mọi tải, mọi loại công
việc hoặc công suất tối đa. [Hướng dẫn demo](DEFENSE_GUIDE.md) gắn số liệu với thao
tác thực tế; [kiểm định cuối](FINAL_VERIFICATION.md) ghi PASS/FAIL/NOT TESTED.
