# Đánh giá codebase — 21/10/2026

Đây là đánh giá lịch sử tại baseline ghi bên dưới. Một số hạn chế đã được xử lý
ở các commit tiếp theo; trạng thái đã thực thi mới nhất nằm trong
[FINAL_VERIFICATION](../FINAL_VERIFICATION.md), không suy ra từ danh sách kế hoạch này.

Baseline đọc: `aa7c176` trên `main`. Phạm vi gồm Java main/test, cấu hình,
scripts build/demo/benchmark/validator và các tài liệu hiện có. Đây là đồ án PBL4
HTTP/1.1 buffered; không đặt mục tiêu thay thế Nginx/HAProxy.

## Tổng quan kiến trúc

Client → JDK HttpServer → bounded ingress executor → ProxyHandler → Dispatcher
→ UpstreamTransport → backend. HealthChecker lấy health/telemetry độc lập từng
backend. Listener management tùy chọn có executor riêng, luôn bind loopback.

Dispatcher giữ một khóa cho lựa chọn và reservation, health generation, learning
và hoàn tất lease. Không giữ khóa khi I/O. Request deadline truyền từ ingress qua
upload, upstream và downstream; connect/read timeout bổ sung giới hạn từng I/O.
Mỗi upstream exchange dùng một socket mới, `Connection: close`; không retry.

## Thành phần chính

| Thành phần | Trách nhiệm thực tế |
|---|---|
| ProxyConfig / ConfigLoader | Đọc properties, kiểm tra giới hạn, backend tĩnh |
| BoundedRequestExecutor | Không queue, giới hạn cả parser ingress, deadline |
| ProxyHandler | Request ID, admission, quota peer IP, HTTP errors, forwarding |
| Dispatcher / Lease | Select + reserve nguyên tử, hoàn tất đúng một lần |
| EstimatedWork | 9 nhóm route cố định, EWMA/unit, mô phỏng slot FIFO |
| AdaptiveLoadBalancer | Min ECT × penalty; định kỳ khám phá backend ít được chọn |
| BackendMetrics | RTT/error EWMA, telemetry freshness, health/slow start |
| CircuitBreaker / ClientRateLimiter | Cô lập lỗi backend và quota token bucket có bound |
| UpstreamTransport | Parser HTTP framing, giới hạn buffer, deadline/socket cleanup |
| DemoBackendServer | Semaphore slot/queue, light/slow/CPU và các route demo UI |
| benchmark.py / verify_benchmark.py | Lịch seed chung, frozen compilation, raw CSV, checksum/validator |
| TestSuite | Mỗi test main trong JVM riêng, assertions, timeout 60 giây |

## Load balancing và metrics

RR xoay vòng trên tập đủ điều kiện; LC dùng lease in-flight, phá hòa theo thời điểm
được chọn. Adaptive còn áp dụng admission theo công việc dự kiến, rồi xếp hạng bằng
ECT theo URI × resource penalty. Vì vậy phép so sánh gồm cả **routing và admission**.

Latency/error được quan sát tại proxy; CPU/heap/remote active/queue được poll từ
backend. CPU là process CPU / CPU budget, heap không phải RAM host. Telemetry stale
vẫn có thể xuất hiện trong JSON nhưng không dùng làm tải hiện tại. `scoreMs` là
score RTT/count cũ, chỉ cung cấp penalty; không phải ECT của request vừa chọn.

JSON/Prometheus là snapshot; việc scrape cũng là management request và tham gia
handler counters. Không có histogram latency toàn proxy hoặc dashboard realtime.
`inFlight` là lease/request, không phải tổng TCP connection.

## Điểm mạnh hiện tại

- Có transport/framing, deadline và shutdown test bằng socket thật.
- Health generation và lease idempotency xử lý completion/probe đến muộn.
- Quota không tin header do client tự gửi; bảng client có giới hạn bộ nhớ.
- Circuit breaker và health độc lập; management có thể đọc khi data ingress đầy.
- Benchmark giữ lịch gửi, warmup, toàn bộ lỗi, client lag và per-backend boundaries.
- Có dữ liệu Mixed ghi nhận trade-off slow; tài liệu cũ không che regression đó.

## Điểm yếu hiện tại

- Không có giải thích từng lựa chọn: metrics `/` không cho biết route, admission
  hay exploration của request vừa dispatch. Khó trả lời câu hỏi “vì sao backend B?”.
- Dữ liệu benchmark nằm trong thư mục ignored. Một clone mới thiếu các CSV mà docs
  đang liên kết; chưa có bộ chart/report offline có thể tái tạo.
- README dài và pha nhiều lớp lịch sử; trọng tâm bài toán, kết quả và giới hạn khó thấy.
- ECT dùng FIFO/slot ước lượng và mẫu không queue; cold start/recovery có thể thiếu
  mẫu slow. Không có công suất riêng hay bảo đảm fairness theo route.
- CPU/heap trên nhiều JVM cùng host không phải thí nghiệm ba máy độc lập.
- Snapshot không phải giao dịch phân tán; counters khác lớp không luôn bằng nhau.

## Những chỗ code/docs không khớp

| Vị trí | Mô tả cũ | Code hiện tại / hướng sửa |
|---|---|---|
| README, mục hardening | Deadline riêng từng pha | RequestDeadline xuyên suốt; sửa mô tả |
| ARCHITECTURE, công thức | Chọn trực tiếp `scoreMs` RTT/count | Chọn ECT × penalty, có work admission |
| ADAPTIVE_COMPLETION | Chỉ bốn nhóm route | Có PRODUCTS, ORDERS và ba cỡ FILE, tổng 9 nhóm |
| ARCHITECTURE, ingress | Incomplete header không được giới hạn | Executor đã bound task và deadline parser |
| Docs lịch sử benchmark | Link tới results ignored | Cần bundle nhỏ có provenance và chart trong repo |

Validator hiện tại từ chối dữ liệu ECT 27/09 khi so runner hash với snapshot mới
(“Runner hash mismatch”). Không sửa artifact hoặc bỏ validation để dùng làm số liệu
hiện hành. Các con số trong báo cáo lịch sử được giữ dưới nhãn lịch sử; bộ trình bày
mới dùng một lượt benchmark mới cùng workload chuẩn và validator hiện tại.

## Cải tiến và ưu tiên

| Mức | Việc | Cách kiểm chứng |
|---|---|---|
| P0 | Snapshot giải thích Adaptive, opt-in, chỉ giữ quyết định mới nhất | Test score/exploration/rejection/privacy; không đổi lựa chọn |
| P0 | Bundle kết quả thật + bảng/biểu đồ RR/LC/Adaptive | Validator raw, hash bundle, thống kê tính lại từ CSV |
| P0 | README theo bài toán → chạy → đo → kết quả → giới hạn | Kiểm tra lệnh, link và file được nhúng |
| P1 | Metrics reference và benchmark/visualization guide | Phân biệt counter/gauge, EWMA/percentile, success/all outcomes |
| P1 | Report offline, biểu đồ SVG/PNG và breakdown route | Regenerate từ bundle, không cần JVM đang chạy |
| P2 | Fairness và sensitivity của penalty | Chỉ đề xuất; cần thí nghiệm độc lập trước khi đổi thuật toán |

Không thay trọng số Adaptive, timeout, lịch workload hay admission để cải thiện số
đo trong đợt này. Pooling, retry, HTTP/2, TLS termination và phân tán nằm ngoài phạm vi.
