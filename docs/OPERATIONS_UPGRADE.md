# Hardening và vận hành sau nâng cấp

## Ưu tiên ổn định

- `proxy.qos.reserved.critical` mặc định là **0**. Điều chỉnh nhỏ (ví dụ 8 hoặc 16 cho capacity 128) khi có test. Khi noncritical traffic chiếm `capacity-reserved`, proxy trả 503 cho chúng; order POST vẫn có reserved admission nếu executor/listener còn nguồn lực.
- `/__proxy/metrics` có `qos`: active/rejected theo lớp. `/__proxy/prometheus` xuất thêm `proxy_qos_*`.
- Không retry POST tự động. Idempotency-Key + transaction PostgreSQL tiếp tục là lớp bảo vệ chính.
- Cache ETag chỉ hợp lệ với static assets đọc từ `src/main/resources`, SHA-256 xác định theo nội dung, `If-None-Match` hỗ trợ weak comparison. Không áp dụng cho `/shop`, `/admin` hay bất cứ `/api/*` nào.
- `pg_safeguard.py backup` sử dụng `pg_dump` dạng custom; file đích nằm ngoài checkout; xác minh `pg_restore --list` và SHA256. `restore-new` yêu cầu database mới, có chủ đích không xóa database phục hồi thất bại để có thể kiểm tra lỗi.
- `pg_safeguard.py` giữ chức năng backup PostgreSQL riêng. Để phục hồi đầy đủ **DB + tệp**, dùng `consistent_bundle.py backup-offline --ack STOPPED_ALL_WRITERS` sau khi dừng toàn bộ writer; PostgreSQL vẫn chạy. Tool kiểm checksum và restore vào một database cùng thư mục tệp **mới**. Chạy `StoreAdmin audit` trên cấu hình phục hồi trước khi công nhận thành công.
- Watchdog dùng trạng thái `backends` và `traffic.total` hiện có. Phân biệt failure sau khi backend xuống với yêu cầu đã in-flight bị lỗi. Error-rate tính chênh lệch counters, p95 đọc rolling ring. Hai đại lượng không dùng chung sample window: không nên gọi chúng là cùng một p95 cửa sổ.
- `maintenance.py` chỉ drain/resume route, không kill JVM và không làm thay đổi dữ liệu. Nếu kết thúc bất ngờ trong thời gian drain, operator tự resume.
- `health_snapshot.py` chụp source hashes, status endpoints và mã commit; không lưu .env/store database password. Không dùng nó như chứng minh mọi phụ thuộc vận hành lâu dài ổn định.
- `release_gate` mặc định từ chối release nếu Git worktree chưa sạch, để tránh công bố nhầm bản chưa commit. Chạy sau khi commit và trước khi push. `--run-tests` chỉ hỗ trợ Windows PowerShell cài JDK21.

## Tiêu chí nghiệm thu bổ sung

1. `PriorityAdmissionTest` PASS, request GET không chiếm slot reserved, `POST /api/store/orders` được nhận khi còn reserved; không âm active hoặc double release.
2. Thử HTTP thực với `proxy.qos.reserved.critical=8`, có 128 concurrent handler và xác minh p95/order success vs baseline, đồng thời kiểm tra 503 của request không ưu tiên. Đảm bảo không bóp nghẹt catalog dưới tải trung bình.
3. Kiểm tra asset `GET /store/assets/app.js`: ETag ổn định, request `If-None-Match` nhận 304 không body; sau build mới đổi file thì ETag đổi.
4. `consistent_bundle.py backup-offline` -> `verify` -> `restore-new` -> `StoreAdmin check`/audit trên DB và shared storage phục hồi độc lập. Không dùng database hoặc thư mục đang có làm target.
5. Watchdog xác nhận sự kiện `BACKEND_DOWN` khi kill B1, sự kiện recovery khi backend thật sự trở lại. Đây là thí nghiệm lỗi có chủ đích, chỉ làm trong môi trường kiểm thử và không xóa data.
6. Draining B1 khi B2/B3 còn UP, chờ in-flight=0; resume sau bảo trì. Thử drain backend cuối: script phải từ chối.
7. Release gate không cho phép `.env.store` hoặc thư mục DB xuất hiện trong tracked files. Kiểm tra `git ls-files` trước khi push.

## Quan hệ với gói tám hạng mục

Bundle mở rộng không thay thế hoặc viết lại 8 mục trước. Nó thay đổi `ProxyConfig`, `ProxyHandler`, `ReverseProxyServer`, `StoreApplication`, `config/application.properties` và tạo file mới không trùng tên. Nếu Codex đã refactor bất kỳ anchor nào, `apply.py` phải từ chối và chuyển sang manual review; không được sửa anchor một cách mù quáng.
