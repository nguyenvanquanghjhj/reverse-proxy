# Tiêu chí nghiệm thu độc lập cho 8 hạng mục

**Không đánh dấu PASS chỉ dựa vào mã nguồn hiện diện.** Ghi ngày, commit hash, Java version, hệ điều hành, lệnh, exit status, log và ảnh/CSV trước khi kết luận.

| Gate | Điều kiện đạt | Nên ghi nhận gì |
|---|---|---|
| G1 — Capability routing | Khi B2 mất DB nhưng process vẫn sống, order API KHÔNG được chọn B2; static từ B2 còn dùng được khi hợp lệ. All-DB-down phải fail closed. Trạng thái PAUSED giữ được HTTP 423. | `CapabilityRoutingTest`, kiểm thử lỗi phụ thuộc thực tế, số lần chọn từng backend |
| G2 — LAN | Proxy trên máy A; B1/B2/B3 trên ít nhất một máy khác; remote readiness được bảo vệ; tắt mạng một backend và đối chiếu lỗi trước/sau detect; DB/files còn đúng. | Sơ đồ IP thực, đường truyền, firewall, tcpdump/Wireshark, raw timings |
| G3 — CI | Push/PR chạy Java21 compile + toàn bộ *Test.java; nếu test lỗi CI FAIL, giữ XML/log. | URL Action run và commit |
| G4 — Compose | Database migrate/seed lần đầu; restart giữ order, stock, images và file; no ports for DB on public interface; no `down -v`. | `docker compose config`, `ps`, logs, SQL snapshot trước/sau |
| G5 — Evidence | Thực nghiệm tạo raw CSV/JSON, báo cáo so sánh, manifest SHA; exit code bất kỳ lỗi phải giữ là FAIL. | `scripts/research_release.py` output với manifest + logs |
| G6 — JDBC pool | Concurrent transactions không sai stock/idempotency; connection count bounded; transaction reset khi tái sử dụng; giảm/không giảm latency đều phải ghi trung thực. | `DatabasePoolTest`, runtime SQL data, p95 before/after |
| G7 — Operator plane | Remote bị từ chối; loopback action cần đúng token khi flag bật; sai/missing token 403, không log secret. | Negative tests, auth logs chỉ chứa status/code |
| G8 — Upstream keep-alive | GET trả đúng bytes qua socket tái sử dụng; POST không reuse/retry; malformed/chunk/EOF/timeout/client disconnect không rò connection; TTL đóng idle; disabled mode giữ hành vi cũ. | `UpstreamPoolingTest`, `HttpTransportTest`, `HttpIntermediaryTest`, load/failure sweep |

## Trình tự cổng kiểm tra

### Cổng A — Nguyên trạng

- Kiểm tra `git status`, tạo nhánh riêng và bảo lưu `.shop/`, `.runtime/`, `shared-files/`, `.env.store`.
- Dùng duy nhất `install.py` của unified bundle: `--verify-bundle`, `--repo . --check`, `--repo . --diff <outside-checkout-path>`, rồi `--repo . --apply` sau khi review.
- Không chạy riêng patcher trong `layers` hoặc giải nén ZIP trong `provenance`. Ghi lại mọi sửa lỗi installer và giữ kiểm tra SHA baseline.

### Cổng B — Java và tính đúng đắn

- Dừng JVM trước build để tránh thay class đang chạy.
- Trên Windows: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test.ps1`.
- Maven: `mvn -B --no-transfer-progress -Pshop test`.
- `TestSuite` tự phát hiện toàn bộ `*Test.java`; chạy mọi test main có trong checkout. Không dùng số suite lịch sử để bỏ qua test mới.
- Nếu bất kỳ test nào FAIL, chưa bật pooling hoặc chưa nhập nhánh chính.

### Cổng C — Database và vận hành

- Chụp danh sách order, tổng stock và SHA file trước/sau theo chức năng `scripts/store.py audit`.
- Chạy lỗi JDBC trên một replica và ghi lại kết quả từng lớp request.
- Không tự đổi hệ số Adaptive hay xóa bảng để làm đẹp số liệu.

### Cổng D — Hiệu năng và mạng

- So sánh cùng workload, cùng phiên bản code, cùng timeout/heap, ít nhất 3 repeat.
- Ghi p50, p95, p99, throughput, HTTP 4xx/5xx, timeout, scheduling lag; phân biệt proxy rejection với backend rejection.
- Chỉ gọi số liệu LAN khi phép đo đi qua ít nhất hai máy thật. Loopback, Docker bridge hoặc network namespace trên cùng máy phải được báo riêng.

### Cổng E — Đóng băng

- `git diff --check`, `git status`, commit mã nguồn.
- Đính kèm build/test logs, research manifest; đánh dấu PASS/FAIL/NOT RUN từng gate.
- Chỉ bật `proxy.connection-pool.enabled=true` trong cấu hình cuối sau khi G8 PASS.
