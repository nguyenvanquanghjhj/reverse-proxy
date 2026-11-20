# Vận hành Reverse Proxy

## Ưu tiên ổn định

- `proxy.qos.reserved.critical` mặc định là **0**. Điều chỉnh nhỏ (ví dụ 8 hoặc 16 cho capacity 128) khi có test. Khi lưu lượng thường chiếm `capacity-reserved`, proxy trả 503 cho chúng; order POST vẫn có suất tiếp nhận dành riêng nếu executor/listener còn nguồn lực.
- `/__proxy/metrics` có `qos`: active/rejected theo lớp. `/__proxy/prometheus` xuất thêm `proxy_qos_*`.
- Không tự gửi lại POST. Idempotency-Key + transaction PostgreSQL tiếp tục là lớp bảo vệ chính.
- Store assets `/store/assets/*` hiện trả 200 và không gọi `AssetCache.conditional`; không phát ETag/304. Lớp AssetCache vẫn có regression riêng nhưng không chứng minh route đang bật. `/shop`, `/admin` và Store API không dùng cache xác thực. Tuyến public streaming `/api/pbl4/files/*` có ETag/304 và Range khi được bật riêng.
- `pg_safeguard.py backup` sử dụng `pg_dump` dạng custom; file đích nằm ngoài checkout; xác minh `pg_restore --list` và SHA256. `restore-new` yêu cầu database mới, có chủ đích không xóa database phục hồi thất bại để có thể kiểm tra lỗi.
- `pg_safeguard.py` giữ chức năng backup PostgreSQL riêng. Để phục hồi đầy đủ **DB + tệp**, dùng `consistent_bundle.py backup-offline --ack STOPPED_ALL_WRITERS` sau khi dừng toàn bộ writer; PostgreSQL vẫn chạy. Tool kiểm checksum và restore vào một database cùng thư mục tệp **mới**. Chạy `StoreAdmin audit` trên cấu hình phục hồi trước khi công nhận thành công.
- Watchdog dùng trạng thái `backends` và `traffic.total` hiện có. Phân biệt failure sau khi backend xuống với yêu cầu đã in-flight bị lỗi. Error-rate tính chênh lệch counters, p95 đọc rolling ring. Hai đại lượng không dùng chung sample window: không nên gọi chúng là cùng một p95 cửa sổ.
- `maintenance.py` chỉ drain/resume route, không kill JVM và không làm thay đổi dữ liệu. Nếu kết thúc bất ngờ trong thời gian drain, operator tự resume.
- `health_snapshot.py` chụp source hashes, status endpoints và mã commit; không lưu .env/store database password. Không dùng nó như chứng minh mọi phụ thuộc vận hành lâu dài ổn định.
- `release_gate` mặc định từ chối release nếu Git worktree chưa sạch, để tránh công bố nhầm bản chưa commit. Chạy sau khi commit và trước khi push. `--run-tests` chỉ hỗ trợ Windows PowerShell cài JDK21.

## Tiêu chí nghiệm thu bổ sung

1. `PriorityAdmissionTest` PASS, request GET không chiếm slot reserved, `POST /api/store/orders` được nhận khi còn reserved; không âm active hoặc double release.
2. Thử HTTP thực với `proxy.qos.reserved.critical=8`, có 128 concurrent handler và xác minh p95 và tỷ lệ đặt hàng thành công so với mức nền, đồng thời kiểm tra 503 của request không ưu tiên. Đảm bảo không bóp nghẹt catalog dưới tải trung bình.
3. Kiểm tra `GET /store/assets/app.js`: hiện nhận 200, không có ETag; gửi `If-None-Match` không chuyển thành 304. Đối chiếu riêng ETag/304 của public streaming trong profile QA. Giữ nguyên bằng chứng 304 lịch sử của build trước.
4. `consistent_bundle.py backup-offline` -> `verify` -> `restore-new` -> `StoreAdmin check`/audit trên DB và shared storage phục hồi độc lập. Không dùng database hoặc thư mục đang có làm target.
5. Watchdog xác nhận sự kiện `BACKEND_DOWN` khi kill B1, sự kiện recovery khi backend thật sự trở lại. Đây là thí nghiệm lỗi có chủ đích, chỉ làm trong môi trường kiểm thử và không xóa data.
6. Draining B1 khi B2/B3 còn UP, chờ in-flight=0; resume sau bảo trì. Thử drain backend cuối: script phải từ chối.
7. Release gate không cho phép `.env.store` hoặc thư mục DB xuất hiện trong tracked files. Kiểm tra `git ls-files` trước khi push.

## Operating commands

Run from the checkout with an explicit private QA environment. Live QA output must be outside the checkout; use a new isolated database/files root. The default `.shop` is the real local store.

| Task | Entry point | Conditions / evidence |
|---|---|---|
| Storage inventory | `python scripts/ops/storage_audit.py --root .. --out ../maintenance/inventory.json --exclude ../maintenance` | Read-only; hidden files included; links listed without traversal; exit 2 means incomplete measurement. |
| Database + files backup / new-target restore | `python scripts/ops/consistent_bundle.py --help` | Stop all writers; retain PostgreSQL. Restore refuses existing targets. |
| Operator actions in Docker | `python scripts/ops/docker_operator.py --help` | Uses Compose exec; operator port remains private. |
| Health, draining, watchdog | `health_snapshot.py`, `maintenance.py`, `proxy_watchdog.py` | Use explicit QA ports during acceptance. |
| Live transactions / pooling / QoS | `acceptance_live.py --env-file <private-env> --out <new-directory>` | New QA schemas/files; never use the real store env. |
| Streaming / Range / resource bounds | `acceptance_streaming.py --env-file <private-env> --out <new-directory>` | Generates 32/100/256 MiB payloads and a resumed 100 MiB copy. Budget disk space before each run. |
| Docker persistence | `acceptance_docker_ci.py --out <new-directory>` | New project/volumes; retains volumes after tests. |
| LAN measurements | `lan_benchmark.py --help` | Requires real distinct hosts; local traffic is not LAN evidence. |
| Release checks | `release_gate.py --help` | Preflight/release checks do not replace live acceptance. |
| Preserved stopped QA datasets | `python scripts/ops/qa_snapshot.py --help` | List/verify/restore the workspace recovery package; restore requires a new destination and verifies every selected SHA before writing. |

## Docker Compose

Requires Docker Compose V2 and recent Docker Engine. This is **not** a production Internet-facing service: proxy exposes HTTP on 127.0.0.1:8080 only; PostgreSQL is internal. The DB and files are durable named volumes; `down -v` deletes them.

1. `cd deploy/compose`.
2. Copy `.env.example` to `.env`, generate independent random 32+-character password and 64-character tokens, edit `.env`. Do not commit it.
3. `docker compose config` (preflight), `docker compose build` (Java 21 compile), `docker compose up -d`.
4. `docker compose ps`; check `http://127.0.0.1:8080/shop`.
5. Once, optionally provision an admin via:
   `docker compose run --rm -e STORE_ADMIN_EMAIL=admin@example.test -e STORE_ADMIN_PASSWORD='<random password 12+ chars>' init java com.example.store.StoreAdmin admin`
   Note: this uses the init service image/environment but overrides its command; do not run it concurrently with database migrations.
6. `docker compose stop`; `docker compose start` retains data. Never run `down -v` unless you explicitly intend to delete persistent data.

For multi-machine network tests use `../lan`; Compose isolates the stack on a single host. To allow LAN clients use a VPN/reverse proxy providing TLS and authentication; do not publish PostgreSQL or operator routes.

`PBL4_CONTROL_TOKEN` enables operator authentication on loopback management POST routes; existing dashboard POST buttons will require operator-token support if the token is set. Use `java com.example.proxy.server.OperatorCli` **inside** the proxy container to operate it. Environment variables containing secrets must not appear in screenshots or report artifacts.

Docker health dependency ordering is not equivalent to a full distributed failover guarantee.

## Host-to-container operator control

The public data listener keeps /__proxy management loopback-only **inside the proxy container**. A host request to the published 8080 port gets 403 by design: Docker bridge source is not container loopback. Do **not** disable that restriction. Instead run: `python scripts/ops/docker_operator.py --compose-file deploy/compose/compose.yaml metrics` (or `strategy`, `drain backend1:9001`, `resume backend1:9001`). The script executes OperatorCli inside the proxy container without publishing any management port. Only trusted Docker operators can run it.

## Acceptance conditions

Required conditions, not claims that a test has passed. Run the complete current TestSuite and record PASS / FAIL / NOT RUN with raw output.

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

See [HTTP streaming](../architecture/HTTP_STREAMING.md), [LAN](../../deploy/lan/README.md) and [frozen evidence](../../../evidence/README.md).
