# Reverse proxy và cân bằng tải thích nghi — PBL4

Reverse proxy HTTP viết bằng **Java 21, chỉ dùng thư viện JDK**. Thuật toán mặc định `ADAPTIVE` ước lượng backend có thể hoàn thành công việc mới nhanh hơn từ độ trễ thực tế, công việc đang xử lý, năng lực phục vụ, CPU, bộ nhớ và lỗi. `ROUND_ROBIN` và `LEAST_CONNECTIONS` được giữ làm đối chứng khi đo đạc.

Đây là thuật toán heuristic: học chi phí gần đây theo nhóm route, không biết chính xác chi phí request mới và không bảo đảm luôn chọn được server nhanh nhất. Thiết kế, giả thuyết và kết quả Mixed trước/sau được ghi trong [Estimated Completion Time](docs/ADAPTIVE_COMPLETION.md).

## Chạy nhanh

Demo trực tiếp Windows PowerShell: xem [hướng dẫn bốn demo A/B/C/D](docs/DEMO.md). Sau khi đặt JDK 21, chạy `scripts/demo.ps1 start`, rồi `basic`, `rr`, `adaptive`, `health`; kết thúc bằng `stop` để dừng đúng các process đã tạo.

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

Maven là lựa chọn bổ sung: `mvn clean verify` chạy các test Java `main` rồi tạo cùng JAR. Maven dùng plugin AntRun để fork từng chương trình test với assertions bật; test lỗi hoặc quá 60 giây làm build thất bại. Surefire được bỏ qua vì đây không phải test JUnit. Maven có thể cần mạng để tải build plugin lần đầu; ứng dụng và test vẫn không có dependency ngoài JDK. Các script test bên dưới dùng được khi không có Maven.

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
serviceEstimate = routeServiceEWMA × requestCostUnits
ECT = estimatedNextSlotWait + serviceEstimate
ranking = ECT × resourcePenalty
admissionBudget = min(readTimeout, requestTimeout)
```

- `routeServiceEWMA`: học riêng `/work`, `/slow`, `/compute` và nhóm OTHER; chỉ học từ response thành công được cấp khi chưa phải chờ slot. `cost` có sẵn của demo được dùng cho `/work` và `/compute`.
- `estimatedNextSlotWait`: mô phỏng các slot và công việc đã giữ chỗ, có tính phần việc còn lại và tải ngoài proxy từ telemetry mới.
- Capacity phục vụ song song giảm trong giai đoạn hồi phục. Adaptive chỉ cấp lease nếu ECT và tổng work/capacity còn trong ngân sách timeout hiện tại; quá tải trả 503 sớm.
- `resourcePenalty`: tăng khi CPU gần bão hòa, bộ nhớ có áp lực hoặc lỗi tăng. Telemetry quá cũ không được coi là số liệu hiện tại.
- Thăm dò có giới hạn giúp học lại backend từng chậm. Health/count admission áp dụng cho cả ba thuật toán; admission theo estimated work chỉ thuộc Adaptive. Vì vậy benchmark mới so sánh cả chính sách admission cùng routing, phải báo cả lỗi và tỷ lệ phục vụ từng loại request.

`scoreMs` trong monitoring giữ score tổng hợp cũ để đối chiếu; quyết định mới dùng ECT theo request. Các cột `estimated*WorkMs` cho biết work dự kiến còn lại; xem [công thức, concurrency và hạn chế](docs/ADAPTIVE_COMPLETION.md).

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

# Kiểm tra lịch gửi và cách tính thống kê benchmark
python -m unittest discover -s scripts -p test_benchmark.py

# So sánh ba thuật toán; mỗi lần chạy tự quản lý các tiến trình của nó
python scripts/benchmark.py --quick

# Phép đo có lặp: A homogeneous, B heterogeneous, C mixed
python scripts/benchmark.py --requests 1200 --warmup-requests 300 --rate 60 --repeats 3
```

Trên Linux thay dòng đầu bằng `bash scripts/test.sh`. Benchmark tự biên dịch source snapshot bằng `javac` cạnh executable `java`; dùng `--java "C:/path/to/jdk-21/bin/java.exe"` nếu PATH chưa trỏ tới JDK 21. Không dùng class do IDE tự tạo trong `target/classes`. Output nằm ngoài `target`, mặc định `benchmarks/results/<timestamp>`, gồm `summary.csv`, `aggregate.csv`, `backends.csv`, `request_types.csv`, raw request và telemetry CSV. `--quick` chỉ kiểm tra pipeline, không dùng kết luận thắng/thua.

Xem [phương pháp và ý nghĩa từng cột](docs/BENCHMARK.md) và [kết quả thực tế](docs/BENCHMARK_RESULTS.md). Cả ba chiến lược dùng cùng lịch gửi, cùng timeout/giới hạn và warm-up; thứ tự chạy cân bằng qua ba lần lặp. Báo cáo cả lỗi, client lag và p95/p99. Các test Java hiện có đã chứa kiểm thử HTTP thực; chưa có script `integration.py` riêng.

Build dùng `javac --release 21 -encoding UTF-8`, tạo `target/reverse-proxy-demo.jar`. Lượt audit ngày 27/09/2026 đã chạy thành công `mvn clean verify` trên Microsoft JDK 21.0.12.1, gồm cả test HTTP/socket thực. Xem [báo cáo audit và lệnh kiểm chứng](docs/AUDIT.md); đây là kiểm tra correctness, chưa phải kết luận hiệu năng từ benchmark.

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
scripts/                 Build, chạy, test, benchmark
docs/                    Khảo sát, thiết kế, kết quả đo
```

[Khảo sát và kế hoạch sửa](docs/REFACTOR_PLAN.md) ghi các vấn đề của bản cũ và những phần đã giữ, sửa, bỏ, thêm. Khi bảo vệ PBL4, tách rõ proxy tự xây dựng và backend demo tạo tải; dùng benchmark để giải thích vì sao một quyết định phân tải tốt hơn trong từng tình huống.
