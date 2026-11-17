# PBL4 — Reverse Proxy & Load Balancer

Java 21 reverse proxy với Round Robin, Least Connections và Adaptive. Ba backend
chạy JVM riêng; PostgreSQL và shared storage cung cấp workload Mộc Store có
transaction, idempotency, ảnh và PDF thật. Workload tổng hợp được tách khỏi cửa hàng.

## Chạy trên Windows

Cần **JDK 21** trên PATH và Python 3.12. Kiểm tra `java --version` trước khi chạy.
Máy mới cần mạng ở lần tải JDBC/PostgreSQL đầu; dependency được pin checksum.
Không cần Maven cho launcher PowerShell; Maven dùng cho build/CI thay thế.

```powershell
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
| Vận hành / deployment | [scripts/ops](scripts/ops/README.md), [deploy](deploy/README.md) | Backup/restore, health, drain, Docker, LAN |

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

[HTTP streaming](docs/HTTP_STREAMING.md) tắt mặc định. Tuyến đọc
`/api/pbl4/files/<name>` hỗ trợ GET/HEAD, ETag/304 và single HTTP Range, buffer 32 KiB,
file tối đa 1 GiB. Không upload hoặc replay giao dịch. Public files root cấu hình
riêng và không chứa link/junction.

`proxy.streaming.enabled=false`, `proxy.connection-pool.enabled=false` và
`proxy.qos.reserved.critical=0` giữ mặc định. JDBC pool local opt-in qua
`SHOP_DB_POOL_ENABLED`; `.env.store` hiện có được bảo toàn. Chỉ bật trong môi trường
riêng sau regression. Proxy không tự retry POST; khi mất response sau commit,
kiểm tra lại cùng Idempotency-Key và body.

## Kiểm thử và dữ liệu phát sinh

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test.ps1
mvn -B --no-transfer-progress -Pshop test
python -m unittest discover -s scripts/ops -p "test_*.py" -v
python scripts/test_defense_live.py
python scripts/verify.py --env-file <private-test-env>
python scripts/ops/acceptance_live.py --env-file <private-test-env>
python scripts/ops/acceptance_streaming.py --env-file <private-test-env>
```

Đủ **30 Java test mains** chạy bằng TestSuite và xuất XML; Surefire không dùng vì
project dùng launcher mains. CI chạy Java Linux/Windows, Python/config và Docker
thực với order/stock/files sau restart. Log lỗi vẫn được giữ.

Live QA mặc định ghi vào `evidence/` cạnh checkout, mỗi run có tên riêng; đặt
`PBL4_EVIDENCE_ROOT` bằng đường dẫn tuyệt đối để đổi vùng lưu. `--out` vẫn hỗ trợ
nhưng bị từ chối nếu ở trong checkout/runtime/shared storage. QA cần private env
rõ ràng, không mặc định dùng `.env.store` thật. Streaming acceptance tạo file
32/100/256 MiB và resume 100 MiB: cần khoảng 489 MiB cho input. Sau PASS và dừng
JVM, script kiểm SHA rồi xóa đúng năm input vừa tạo, giữ recipe/checksum trong
`fixture-inputs.json`. `--retain-fixtures` giữ input; FAIL luôn giữ để điều tra.
Raw CSV/JSON, log, database, backup và ảnh/PDF không thuộc danh sách xóa.

## Docker, backup và tài liệu

Xem [deployment](deploy/README.md), [operations](docs/OPERATIONS_UPGRADE.md).
Compose có PostgreSQL, ba backend, shared volume và proxy. Management port riêng
thao tác bằng operator qua `docker exec` với token. Kiểm syntax bằng
`docker compose -f deploy/compose/compose.yaml config --quiet` sau khi cung cấp env.
Giữ volume dữ liệu; không prune volume khi dọn cache.

Backup đồng bộ DB/files dùng `scripts/ops/consistent_bundle.py` sau khi dừng mọi
writer. Tool kiểm hash và chỉ restore sang database/files root **mới**.

- [Kiến trúc, dependency và nghiệm thu](docs/maintenance/RESTRUCTURE_REPORT.md)
- [Bằng chứng đã chọn](docs/evidence/)
- [Lịch sử nâng cấp](docs/history/README.md)
- [Audit dung lượng trước đợt tái cấu trúc](docs/maintenance/STORAGE_AUDIT.md)

Chỉ gọi phép đo nhiều máy là LAN khi có host thứ hai thật. Loopback, Docker bridge
và Windows thiếu Engine được báo riêng. Bind/auth/TLS triển khai Internet cần
cấu hình phù hợp; nghiệm thu không phải cam kết production.
