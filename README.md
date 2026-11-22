# PBL4 — Reverse Proxy & Load Balancer

Java 21 reverse proxy với Round Robin, Least Connections và Adaptive. Ba backend
chạy JVM riêng; PostgreSQL và shared storage cung cấp workload Mộc Store có
transaction, idempotency, ảnh và PDF thật. Workload tổng hợp được tách khỏi cửa hàng.

## Chạy trên Windows

Cần **JDK 21** trên PATH và Python 3.12. Kiểm tra `java --version` trước khi chạy.
Máy mới cần mạng ở lần tải JDBC/PostgreSQL đầu; dependency được pin checksum.
Không cần Maven cho launcher PowerShell; Maven dùng cho build/CI thay thế.

```powershell
# From this checkout; changes only the current terminal environment.
$env:JAVA_HOME = (Resolve-Path "../tools/jdk21/jdk-21.0.13.0.2+1").Path
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run.ps1 start
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run.ps1 status
```

Lần đầu, launcher chuẩn bị JDBC/PostgreSQL portable, tạo database riêng ở
`127.0.0.1:55434`, migrate, seed và tạo mật khẩu admin ngẫu nhiên. Thông tin admin:
`.shop/admin-access.txt`; cấu hình riêng: `.env.store`. Các lần sau giữ database,
files và đơn hàng; `start` không migrate/seed/reset ngầm.

| Giao diện | URL |
|---|---|
| Mộc Store | http://127.0.0.1:8080/shop |
| Quản trị cửa hàng | http://127.0.0.1:8080/admin |
| Dashboard / control plane | http://127.0.0.1:8080/__proxy/dashboard |
| Thử tải HTTP (Workload Console) | http://127.0.0.1:8080/workload |

Cửa hàng yêu cầu đăng nhập và đặt hàng tại trang chi tiết sản phẩm. `/shop`
chuyển sang `/admin/shop` trong trình duyệt; `/admin` yêu cầu quyền quản trị.

```powershell
python scripts/store.py stop                  # dừng JVM, giữ PostgreSQL
powershell -ExecutionPolicy Bypass -File scripts/test.ps1
python scripts/store.py start --strategy ADAPTIVE
python scripts/store.py dependencies
python scripts/store.py drain --backend 9001
python scripts/store.py resume --backend 9001
python scripts/store.py audit
powershell -ExecutionPolicy Bypass -File scripts/run.ps1 stop
```

`run.ps1 stop` mặc định dừng cả cluster local do launcher quản lý, giữ mọi dữ liệu.
Build từ chối thay classes khi còn JVM ứng dụng thuộc phiên đang chạy. Đừng build
song song với acceptance/benchmark. Giữ `.shop`, `.runtime`, `shared-files` và
`.env.store` ngoài Git; không dùng reset/clean để xử lý lỗi khởi chạy.

Với DB được quản lý riêng: build trước, dùng
`scripts/run.ps1 -Action start -EnvFile <private-env>` hoặc
`python scripts/store.py --env-file <private-env> start`. Nhánh `-EnvFile` quản lý
JVM, không khởi tạo/dừng database mặc định. Setup schema/seed/admin là lệnh riêng
của `store.py`, chỉ chạy trên DB được chọn.

## Source và entrypoints

Xem [mục lục tài liệu chung](docs/README.md),
[bản đồ code và luồng request](docs/architecture/CODEBASE_MAP.md) và [bản đồ script](docs/deployment/OPERATIONS_UPGRADE.md).

| Thành phần | Vị trí / entrypoint | Trách nhiệm |
|---|---|---|
| Reverse proxy | `com.example.proxy.Main`, `com.example.proxy.*` | HTTP transport, routing, admission, health, metrics, dashboard |
| Backend HTTP host | `com.example.backend.BackendApplicationServer` | Listener, health/control, worker/admission, dispatch ứng dụng |
| Workload nghiên cứu | `WorkloadHandler`, `WorkloadConsole`, `/workload` | Synthetic HTTP/CPU/download, quan sát request |
| Mộc Store | `com.example.store.*`, `src/main/resources/web/store` | Giao diện khách hàng và quản trị, phiên đăng nhập, sản phẩm, đơn hàng, ảnh/PDF |
| Shared DB / compatibility API | `com.example.shop.*`, `LegacyShopApi` | JDBC/config/JSON, hợp đồng product/order/document lịch sử |
| Khởi chạy | `scripts/store.py`, `scripts/run.ps1` | Setup rõ ràng, start/stop/status, bảo vệ process identity |
| Runtime nội bộ | `scripts/app_runtime.py`, `scripts/prepare_runtime.py` | Theo dõi JVM, chuẩn bị dependency |
| QA / nghiên cứu | `scripts/lab.py`, `verify.py`, `research.py`, `defense_live.py` | Schema/files riêng, traffic, raw evidence |
| Vận hành / deployment | [scripts/ops](docs/deployment/OPERATIONS_UPGRADE.md), [deploy](docs/deployment/OPERATIONS_UPGRADE.md#docker-compose) | Backup/restore, health, drain, Docker, LAN |

`DemoBackendServer`, `shop_demo.py`, `shop_prepare.py` và `/demo` là adapter tương
thích cho runner đã lưu; không chứa implementation thứ hai. Các launcher dùng
entrypoint trong bảng. `/products`, `/orders`, `/download` là workload tổng hợp;
`/api/store/*` là Mộc Store. API `/api/products`, `/api/orders` và document cũ vẫn
giữ cùng schema/migration/dữ liệu. `SHOP_ENABLED=true` bật API tương thích;
`STORE_ENABLED=true` bật Mộc Store và sở hữu `/shop`. Khi chỉ bật API cũ, giao diện
cũ trả `410 LEGACY_UI_RETIRED` với hướng dẫn chuyển sang Mộc Store.

Giá/tồn kho seed là dữ liệu mẫu, không phải báo giá thị trường. Đơn thử tổng hợp
ở `/orders` không phải đơn PostgreSQL của Mộc Store. Ảnh/nguồn catalog và PDF mẫu
giữ provenance hiện có; không mở rộng nghiệp vụ bán hàng.

## HTTP streaming và opt-in

[HTTP streaming](docs/architecture/HTTP_STREAMING.md) tắt mặc định. Tuyến đọc
`/api/pbl4/files/<name>` hỗ trợ GET/HEAD, ETag/304 và single HTTP Range, buffer 32 KiB,
file tối đa 1 GiB. Không upload hoặc replay giao dịch. Public files root cấu hình
riêng và không chứa link/junction.

`proxy.streaming.enabled=false`, `proxy.connection-pool.enabled=false` và
`proxy.qos.reserved.critical=0` giữ mặc định. JDBC pool local opt-in qua
`SHOP_DB_POOL_ENABLED`; `.env.store` hiện có được bảo toàn. Chỉ bật trong môi trường
riêng sau regression. Proxy không tự retry POST; khi mất response sau commit,
kiểm tra lại cùng Idempotency-Key và body.

## Quản trị tài liệu và PDF thử tải

Thanh quản trị có nút thu gọn; màn hình nhỏ mở menu bằng drawer. Hình ảnh và tài liệu
tách riêng trong `/admin/documents` và trang sửa sản phẩm. Dùng Lên trước/Xuống sau
hoặc Làm đại diện để sắp xếp ảnh; đổi tên không đổi file ID/storage key. Giới hạn
upload lấy từ `/api/store/policy`, mặc định ảnh 512 KiB và PDF 256 KiB.

Workload Console có PDF lab mặc định tắt, tách khỏi tài liệu sản phẩm. Xem
[cấu hình và nguồn PDF](docs/architecture/HTTP_STREAMING.md#pdf-lab-từ-nguồn-https) và
[triển khai ba máy Windows](deploy/lan/README.md). Store assets không phát ETag;
GET/HEAD asset tồn tại với `If-None-Match: *` trả 304. ETag/Range của tuyến
streaming được kiểm riêng, không suy thành entity-tag validation cho Store assets.

## Kiểm thử và dữ liệu phát sinh

Lệnh build Java 21, Python regression, live QA và cách theo dõi CI nằm trong
[quy trình kiểm thử](docs/testing/VALIDATION.md). TestSuite chạy mọi `*Test.java`
và xuất XML; CI kiểm Linux/Windows, tools/config và Docker live.

QA dùng env/cổng/output riêng ở `D:\PBL4\evidence\`; không stress database thật.
[Báo cáo nghiệm thu](docs/reports/PBL4_FINAL_VALIDATION.md) ghi rõ số đo, fingerprint,
giới hạn và kết quả CI theo từng snapshot. Không dùng kết quả lịch sử thay cho
kiểm thử HEAD hiện tại.

## Docker, backup và tài liệu

Xem [deployment](docs/deployment/OPERATIONS_UPGRADE.md#docker-compose), [operations](docs/deployment/OPERATIONS_UPGRADE.md).
Compose có PostgreSQL, ba backend, shared volume và proxy. Management port riêng
thao tác bằng operator qua `docker exec` với token. Kiểm syntax bằng
`docker compose -f deploy/compose/compose.yaml config --quiet` sau khi cung cấp env.
Giữ volume dữ liệu; không prune volume khi dọn cache.

Backup đồng bộ DB/files dùng `scripts/ops/consistent_bundle.py` sau khi dừng mọi
writer. Tool kiểm hash và chỉ restore sang database/files root **mới**.

- [Evidence hiện hành, lịch sử và fingerprint](docs/README.md#benchmark-và-nguồn-gốc-bằng-chứng)
- [Cách tìm dữ liệu khi bảo vệ](docs/README.md#tìm-bằng-chứng-khi-bảo-vệ)

Chỉ gọi phép đo nhiều máy là LAN khi có host thứ hai thật. Loopback, Docker bridge
và Windows thiếu Engine được báo riêng. Bind/auth/TLS triển khai Internet cần
cấu hình phù hợp; nghiệm thu không phải cam kết production.
