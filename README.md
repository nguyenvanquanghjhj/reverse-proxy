# Reverse proxy và cân bằng tải thích nghi — PBL4

Reverse proxy HTTP viết bằng **Java 21, chỉ dùng thư viện JDK**. Thuật toán mặc định `ADAPTIVE` ước lượng backend có thể hoàn thành công việc mới nhanh hơn từ độ trễ thực tế, công việc đang xử lý, năng lực phục vụ, CPU, bộ nhớ và lỗi. `ROUND_ROBIN` và `LEAST_CONNECTIONS` được giữ làm đối chứng khi đo đạc.

Đây là thuật toán heuristic: không biết trước độ nặng của một request và không bảo đảm luôn chọn được server nhanh nhất. Kết luận phải dựa trên latency, throughput và tỷ lệ lỗi của cùng một workload; xem [kết quả benchmark](docs/BENCHMARK_RESULTS.md).

## Chạy nhanh

Cần JDK **21 trở lên**, có `java`, `javac`, `jar` trong `PATH`. Python 3 chỉ cần khi chạy integration/benchmark. Không cần Maven hoặc thư viện Java/Python bên ngoài.

### Windows PowerShell

Tại thư mục `reverse-proxy-demo`, build trước rồi mở hai terminal:

```powershell
java -version
javac -version
powershell -ExecutionPolicy Bypass -File scripts/build.ps1

# Terminal 1: ba backend, Ctrl+C để dừng cả ba
powershell -ExecutionPolicy Bypass -File scripts/run-backends.ps1 -NoBuild

# Terminal 2: reverse proxy, Ctrl+C để dừng
powershell -ExecutionPolicy Bypass -File scripts/run-proxy.ps1 -NoBuild
```

Backend mặc định ở `127.0.0.1:9001`, `9002`, `9003`, mỗi backend có capacity 8 và độ trễ mô phỏng 20 ms. Proxy ở `http://127.0.0.1:8080`. Log backend trên Windows nằm trong `target/logs/`; script chỉ dừng các tiến trình do chính nó khởi động.

### Linux / macOS / WSL / Git Bash

```bash
bash scripts/build.sh

# Terminal 1
bash scripts/run-backends.sh --no-build

# Terminal 2
bash scripts/run-proxy.sh --no-build
```

Các script `run-*` mặc định build lại nếu bỏ `-NoBuild` / `--no-build`. Sau khi sửa Java, dừng các dịch vụ rồi build lại. Build luôn xóa class cũ và dừng ngay nếu biên dịch lỗi; chỉ dùng `NoBuild` khi đã build thành công đúng phiên bản nguồn.

Có thể chạy trực tiếp để tự đặt năng lực/tải từng backend:

```text
java -cp target/classes com.example.backend.DemoBackendServer PORT [CAPACITY] [DELAY_MS] [CPU_WORKERS]
java -cp target/classes com.example.backend.DemoBackendServer 9001 8 20 0
java -jar target/reverse-proxy-demo.jar config/application.properties
```

`CAPACITY` là số công việc có thể phục vụ đồng thời, không phải trọng số chia request. `DELAY_MS` mô phỏng thời gian công việc; `CPU_WORKERS` tạo các luồng tính toán thật để gây tải CPU.

Maven là lựa chọn bổ sung: `mvn clean package` tạo cùng JAR. Maven có thể cần mạng để tải plugin lần đầu. Các bài test dùng Java `main`, hãy chạy script test bên dưới; `mvn package` không thay thế bước này.

## Quan sát và tạo tình huống demo

Trên PowerShell dùng `curl.exe`; trên Linux dùng `curl`:

```powershell
# Request bình thường và trạng thái quyết định của proxy
curl.exe http://127.0.0.1:8080/
curl.exe http://127.0.0.1:8080/__proxy/metrics

# Telemetry của riêng backend; dạng text properties
curl.exe http://127.0.0.1:9002/metrics

# Làm backend 9002 chậm rồi phục hồi
curl.exe "http://127.0.0.1:9002/control?delayMs=500"
curl.exe "http://127.0.0.1:9002/control?delayMs=20"

# CPU bận thật: tăng số worker để phù hợp số lõi của máy
curl.exe "http://127.0.0.1:9002/control?cpuWorkers=4"
curl.exe "http://127.0.0.1:9002/control?cpuWorkers=0"

# DOWN rồi hồi phục; đợi health check cập nhật
curl.exe "http://127.0.0.1:9002/control?healthy=false"
curl.exe "http://127.0.0.1:9002/control?healthy=true"
```

`/control` là công cụ demo chỉ nhận kết nối loopback và không được chuyển tiếp qua proxy. Khi backend hồi phục, proxy đợi đủ số lần health check thành công rồi tăng tải dần (slow start). Chạy nhiều request đồng thời để quan sát quyết định cân bằng tải; vài request nối tiếp không đủ để đánh giá thuật toán.

## Thuật toán chọn backend

Điểm càng thấp càng được ưu tiên:

```text
scoreMs = latencyEWMA × (1 + (outstanding + 1) / effectiveCapacity) × resourcePenalty
effectiveCapacity = configuredCapacity × warmup
```

- `latencyEWMA`: độ trễ request đo ở proxy, làm mượt để thích nghi mà giảm nhiễu.
- `outstanding`: công việc proxy đã giữ chỗ cộng ước lượng công việc ngoài proxy từ telemetry; tránh cộng hai lần cùng một request.
- `effectiveCapacity`: khả năng phục vụ song song, giảm trong giai đoạn hồi phục.
- `resourcePenalty`: tăng khi CPU gần bão hòa, bộ nhớ có áp lực hoặc lỗi tăng. Telemetry quá cũ không được coi là số liệu hiện tại.
- Thăm dò có giới hạn giúp học lại backend từng chậm. Giới hạn request và health check áp dụng cho cả ba thuật toán để so sánh công bằng.

CPU của tiến trình, CPU của host, heap JVM và RAM host là các đại lượng khác nhau. Nhiều backend chạy cùng máy dùng chung tài nguyên host; không diễn giải telemetry đó như ba máy vật lý độc lập. Bộ nhớ dùng nhiều cũng không tự động có nghĩa server xử lý chậm. Xem chi tiết công thức, đồng bộ và giới hạn trong [kiến trúc](docs/ARCHITECTURE.md).

## Cấu hình

Sửa [config/application.properties](config/application.properties), sau đó khởi động lại proxy:

```properties
proxy.bind.host=127.0.0.1
proxy.port=8080
backend.servers=127.0.0.1:9001:8,127.0.0.1:9002:8,127.0.0.1:9003:8
loadbalancer.strategy=ADAPTIVE
```

| Nhóm | Ý nghĩa |
|---|---|
| `backend.servers` | `host:port:capacity`; đặt capacity khớp năng lực thực tế/backend demo. |
| `loadbalancer.strategy` | `ADAPTIVE`, `ROUND_ROBIN`, `LEAST_CONNECTIONS`. |
| `healthcheck.*` | Đường dẫn, chu kỳ, timeout và số lần thành công trước khi hồi phục. |
| `metrics.path`, `metrics.stale.ms` | Endpoint telemetry và thời hạn số liệu còn hữu ích. |
| `adaptive.*` | Hệ số EWMA, độ trễ khởi tạo, thời gian warmup và khoảng thăm dò. |
| `proxy.max.inflight`, `backend.max.inflight` | Giới hạn request đang xử lý để tránh dồn việc vô hạn. |
| `proxy.max.body.bytes`, `proxy.max.header.bytes` | Giới hạn bộ đệm body và header upstream. |
| `proxy.*.timeout.ms` | Timeout kết nối, đọc và toàn bộ request upstream. |

Nếu backend thật chưa cung cấp telemetry theo contract, proxy vẫn có quan sát latency/inflight/lỗi của riêng nó; khả năng đánh giá tải bên ngoài proxy sẽ hạn chế. Không suy ra CPU/RAM chính xác chỉ bằng số kết nối TCP.

## Kiểm thử và benchmark

```powershell
# Build và chạy toàn bộ *Test.java (plain Java main, assertions bật)
powershell -ExecutionPolicy Bypass -File scripts/test.ps1

# HTTP thực: forwarding, lỗi, health, telemetry và các giới hạn
python scripts/integration.py

# So sánh ba thuật toán; mỗi lần chạy tự quản lý các tiến trình của nó
python scripts/benchmark.py --quick
```

Trên Linux thay dòng đầu bằng `bash scripts/test.sh`. Xem `python scripts/benchmark.py --help` để chọn phép đo đầy đủ. Không so kết quả giữa hai lượt có concurrency, số request, tải nền hoặc cấu hình máy khác nhau. Báo cáo cả p95/p99, throughput, lỗi và tỷ lệ request mỗi backend; trường hợp adaptive thua cũng cần giữ lại.

Build dùng `javac --release 21 -encoding UTF-8`, tạo `target/reverse-proxy-demo.jar`. Môi trường phát triển hiện có JDK 26; biên dịch cho Java 21 không tự thay thế việc chạy kiểm tra trên đúng runtime JDK 21. Kết quả kiểm tra thực tế và thông tin môi trường được ghi ở [báo cáo benchmark](docs/BENCHMARK_RESULTS.md).

## Phạm vi và tổ chức mã

Proxy nhận HTTP qua JDK `HttpServer`, chuyển tiếp HTTP/1.1 qua TCP `Socket`, dùng virtual threads và giới hạn request/body. Mỗi request upstream mở một kết nối với `Connection: close`; chưa có connection pooling. Phạm vi hiện tại chưa gồm TCP tunnel tùy ý, HTTPS termination, HTTP/2, WebSocket, cache hoặc dashboard. Endpoint JSON giúp quan sát thuật toán, không phải hệ thống giám sát production có xác thực.

```text
src/main/java/com/example/
  proxy/config/          Đọc và kiểm tra cấu hình
  proxy/backend/         Định danh backend, capacity
  proxy/metrics/         Latency EWMA, telemetry, snapshot
  proxy/loadbalancer/    Adaptive, RR, LC, giữ chỗ đồng bộ
  proxy/healthcheck/     Health check và lấy telemetry
  proxy/handler/         Chuyển tiếp HTTP và giới hạn tài nguyên
  proxy/server/          Lifecycle và monitoring
  backend/              Backend mô phỏng để thí nghiệm
src/test/java/           Test không cần dependency
scripts/                 Build, chạy, integration, benchmark
docs/                    Khảo sát, thiết kế, kết quả đo
```

[Khảo sát và kế hoạch sửa](docs/REFACTOR_PLAN.md) ghi các vấn đề của bản cũ và những phần đã giữ, sửa, bỏ, thêm. Khi bảo vệ PBL4, tách rõ proxy tự xây dựng và backend demo tạo tải; dùng benchmark để giải thích vì sao một quyết định phân tải tốt hơn trong từng tình huống.
