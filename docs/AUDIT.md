# Audit repository thực tế — 27/09/2026

Audit tại commit nền `1fa3491`, trước khi sửa mã trong lượt này. Đã đọc toàn bộ 17 file Java chính, 3 file Java test, README, ARCHITECTURE, REFACTOR_PLAN, POM, cấu hình, .gitignore và toàn bộ 9 script. Không coi nội dung kế hoạch cũ là bằng chứng đã triển khai.

## 1. Kiến trúc đang chạy

```mermaid
flowchart LR
  Main[Main + ConfigLoader] --> Server[ReverseProxyServer / JDK HttpServer]
  Client[Client HTTP] --> Server
  Server --> Handler[ProxyHandler / virtual thread / admission semaphore]
  Handler --> Dispatcher[Dispatcher / ReentrantLock / select + reserve]
  Dispatcher --> Strategy[RR hoặc LC hoặc Adaptive]
  Handler --> Transport[UpstreamTransport / Socket HTTP/1.1]
  Transport --> Backend[DemoBackendServer hoặc ứng dụng HTTP]
  Transport --> Completion[Lease.complete / latency và lỗi]
  Completion --> Dispatcher
  Poll[HealthChecker / một vòng virtual thread mỗi backend] --> Backend
  Poll --> Dispatcher
  Dispatcher --> Monitor[/__proxy/metrics JSON]
```

Ingress dùng JDK HttpServer; upstream dùng Socket tự phân tích HTTP/1.1. Một socket cho mỗi request, Connection: close, chưa pooling; body có bộ đệm giới hạn. Cả proxy và demo backend dùng virtual-thread executor; backend dùng semaphore khác để giới hạn số việc thực thi và tổng số đang thực thi/chờ. Proxy trả lease sau khi nhận hết response upstream, trước khi ghi về client, nên lỗi tải xuống của client không làm backend DOWN.

Monitoring JSON và readiness nằm trong ReverseProxyServer. Health/metrics polling dùng HttpClient HTTP/1.1, timeout tổng và subscriber giới hạn 16 KiB; đây là đường quản lý, không phải đường chuyển tiếp request. Backend demo có workload delay/compute, CPU worker thật, /metrics và control chỉ dùng trực tiếp trên loopback.

Chưa có: TCP tunnel tổng quát, TLS termination, HTTP/2/WebSocket, cache, dashboard. `scripts/benchmark.py` đã tồn tại; `scripts/integration.py` và `docs/BENCHMARK_RESULTS.md` mà README dẫn tới chưa tồn tại. REFACTOR_PLAN mô tả bản gốc 15 lớp và ý định refactor, không phải sơ đồ hiện tại.

## 2. Build/test trước sửa

- Máy có JDK 26 trong PATH, không có Maven. Đã tải Maven 3.9.16 và Microsoft JDK 21.0.12.1 vào `%TEMP%/pbl4-audit-tools`, kiểm tra SHA512/SHA256 từ nhà phát hành; không đổi PATH toàn hệ thống.
- `mvn -B -ntp clean test package`: **FAIL** tại clean vì thư mục `target` mang thuộc tính ReadOnly. Đây là lỗi môi trường generated output, chưa phải lỗi compile.
- `mvn -B -ntp test package` trên JDK 26, release 21: **BUILD SUCCESS**, biên dịch 17 source + 3 test; Surefire báo **Tests run: 0**. Main-method tests không tự được Surefire thực thi.
- Chạy trực tiếp `java -ea -cp "target/classes;target/test-classes" com.example.proxy.<Test>` trên **JDK 21.0.12.1**: DispatcherTest đạt 13 scenario; HealthCheckerTest đạt; HttpTransportTest đạt 51 check.
- Tái hiện bằng HTTP/socket thật trên JDK 21: `/work#fragment` trả 502 và biến backend khỏe thành DOWN/errors=1. Request POST với Content-Length 1048577 nhưng không gửi body trả 413; sau 800 ms với timeout cấu hình 200 ms, request mới vẫn trả 503 khi max.inflight=1. Client đóng socket thì suất xử lý mới được trả lại.

## 3. Concurrency và shared state

| Thành phần | Cách đồng bộ thực tế | Nhận xét |
|---|---|---|
| BackendServer, ProxyConfig | record bất biến, copy Properties/List | Không công bố cấu hình mutable cho caller. Getter còn parse Properties nhiều lần, là chi phí thừa nhỏ, không lỗi correctness. |
| Dispatcher, BackendMetrics | Một ReentrantLock; LinkedHashMap chỉ truy cập dưới khóa | Chọn + reserve atomic; completion, health, telemetry và snapshot cùng khóa; không I/O trong khóa. Không cần đổi sang ConcurrentHashMap. |
| RR/Adaptive sequence | long thường | An toàn trong đường chạy hiện tại vì chỉ gọi select dưới khóa Dispatcher; bản thân strategy không là API thread-safe độc lập. |
| Lease.released | boolean thường dưới khóa Dispatcher | Complete/close idempotent, không trừ hai lần; các test đồng thời đã kiểm tra. |
| Backend health | generation + cùng khóa | Probe cũ không ghi đè transport failure mới. Khi DOWN không cấp lease mới; request đã reserve trước lúc DOWN vẫn có thể được gửi/đang chạy. Không có bảo đảm nguyên tử với trạng thái máy từ xa. |
| Proxy admission | Semaphore không đợi, reject 503 | Bảo vệ request handler, kể cả upload/download. Dispatcher còn giới hạn riêng upstream/global. Lỗi drain sau 413 có thể giữ permit mãi: Critical. |
| UpstreamTransport | ConcurrentHashMap.newKeySet + AtomicBoolean closed | Có theo dõi/đóng socket đang chạy và scheduler deadline. Còn cửa sổ shutdown giữa kiểm tra closed và schedule: có thể RejectedExecutionException; cần đóng cửa sổ này trong sửa lifecycle. |
| Demo backend | AtomicInteger active/queued, AtomicLong completed, semaphore, volatile health/delay/CPU | Tăng giảm riêng lẻ không mất update; snapshot nhiều trường không phải giao dịch nguyên tử. completedRequests chỉ tăng ở nhánh JSON thông thường, không phải mọi response. |
| Demo CPU workers | synchronized danh sách, volatile running | Thoát worker bằng cờ, không join; control cập nhật nhiều giá trị riêng lẻ có thể ghi đè cấu hình của một control khác. Cần lưu ý khi làm benchmark thay đổi tải đồng thời. |
| HealthChecker | AtomicBoolean + executor virtual threads, một poll tuần tự/backend | Backend chậm không chặn poll backend khác. Health lỗi làm DOWN; metrics lỗi để stale rồi fallback. start/stop công khai chưa thiết kế restart hoặc concurrent start/stop độc lập. Server hiện gọi lifecycle tuần tự. |
| Deadlines | ScheduledThreadPoolExecutor, cancel + remove-on-cancel | Deadline upstream đóng Socket. Deadline client gọi exchange.close từ thread khác; drain response lỗi có thể vẫn kẹt trong I/O và giữ admission. |
| Virtual threads | Chờ Socket/HttpClient, không biến CPU thành vô hạn | Vẫn cần semaphore. Trên JDK 21, blocking trong synchronized ở thư viện HTTP có thể pin carrier; cần đo JFR trước khi kết luận khả năng chịu nhiều client chậm. |

Không thấy deadlock kiểu lấy nhiều khóa trái thứ tự trong Dispatcher. Không thay counter dưới khóa bằng AtomicInteger một cách máy móc: atomics riêng lẻ không thay được giao dịch select-and-reserve.

## 4. Adaptive thực tế

Proxy tự đo: inflight lease, dispatched/completed/errors, thời gian monotonic từ trước connect tới hết response, EWMA latency thành công và EWMA lỗi. HTTP 429/5xx tính là lỗi; lỗi transport còn đưa backend DOWN. Độ trễ không bao gồm client upload/download. Không dùng latency health endpoint thay latency công việc.

Backend cung cấp: cpuLoad, memoryLoad, activeRequests, queuedRequests. CPU demo tính delta CPU time / delta wall time / CPU budget, clamp [0,1]; memory là **heap JVM used/max**, không phải toàn RAM server. Host CPU/RAM, processCpuCores và serviceTimeMs chỉ để quan sát; scheduler không dùng. Capacity là giá trị cấu hình, không lấy tự động từ telemetry.

```text
alpha = 0.2 mặc định; EWMA = old + alpha * (sample - old)
w = clamp(timeSinceRecovery / warmupMs, 0.1, 1); w=1 nếu warmupMs=0
c = configuredCapacity * w
external = max(0, remoteActive + remoteQueued - inFlightAtProbeStart)
q = inFlightNow + (fresh ? external : 0)
cpuPressure = cpu / max(0.05, 1-cpu)
memoryPressure = max(0, (memory-0.75)/0.25)
U = 0.25 nếu stale/thiếu telemetry; 0.125 nếu thiếu CPU hoặc memory; 0 nếu đủ
P = 1 + 0.35*cpuPressure + 0.5*memoryPressure + 2*errorEWMA + U
scoreMs = latencyEWMA * (1 + (q+1)/c) * P
```

Metric CPU/memory/error là ratio không đơn vị, q/c chuẩn hóa lượng việc bằng capacity, latency giữ thang ms. EWMA áp dụng cho latency, lỗi, CPU và heap ratio; inflight không làm mượt để phản ứng ngay. CPU/memory/remote queue bị bỏ khi stale, không coi giá trị cũ là hiện tại.

Adaptive chọn score nhỏ nhất, phá hòa bằng lần chọn xa nhất; mỗi 50 dispatch thăm dò backend eligible lâu nhất chưa dùng. Chống herd **trong một proxy** bằng chọn và reserve dưới cùng khóa, chứ không dựa riêng polling CPU. DOWN ban đầu; mặc định 2 health 2xx liên tiếp để hồi phục. Warm-up 5 giây giảm cả effective capacity lẫn inflight limit. RR/LC cũng chịu health/admission/warm-up limit.

Giới hạn: q ngoài proxy chỉ là ước lượng lệch thời điểm; latency đã chứa thời gian chờ nên có thể bị phạt thêm một lần; metric stale trở lại vẫn được EWMA với mẫu cũ; hệ số penalty chưa hiệu chuẩn; không dự báo độ nặng request sắp tới. CPU demo hiện khởi tạo 0 và biến hostCpu=-1 thành 0, trái mô tả unavailable trong tài liệu. Đó là vấn đề tin cậy telemetry cần sửa trước benchmark nghiêm túc.

## 5. So sánh implementation

| | Round Robin | Least Connections | Adaptive |
|---|---|---|---|
| Quyết định | sequence modulo số candidate eligible | Min inflight, phá hòa lâu chưa chọn | Min score, phá hòa; thăm dò định kỳ |
| Latency/CPU/heap/remote queue/lỗi ảnh hưởng thứ tự? | Không | Không | Có |
| Capacity khác nhau ảnh hưởng thứ tự? | Không | Không | Có, mẫu số effective capacity |
| Health, atomic reservation, cap, recovery | Chung Dispatcher | Chung Dispatcher | Chung Dispatcher |
| Chi phí code hiện tại | Cùng tạo snapshot/score trước khi chọn | Cùng tạo snapshot/score trước khi chọn | Dùng score đó |

LC thực chất đếm request upstream đang outstanding; một socket/request nên gần active connections, không đếm socket idle/keep-alive. RR xoay trên danh sách eligible thay đổi theo cap/health, không bảo đảm tỉ lệ hoàn thành chia đều dưới tải. Chưa có benchmark đủ điều kiện để kết luận thuật toán nào tốt hơn.

## 6. Phần có thể đơn giản hóa — chỉ liệt kê, chưa xóa

- Getter `BackendServer` lặp lại record accessor; vài hàm AppLogger chưa có caller.
- ProxyConfig parse chuỗi Properties trong mỗi getter; có thể chuyển field đã parse một lần nếu profiling cho thấy cần.
- Dispatcher tính toàn bộ adaptive score ngay cả khi chạy RR/LC. Giữ như hiện tại thuận tiện so sánh; nên công bố overhead chung.
- Hai cơ chế HTTP (Socket dữ liệu, HttpClient quản lý) làm code dài hơn nhưng đang phục vụ hai vai trò khác nhau, không nên xóa vội.
- Parser HTTP thủ công là phần có rủi ro bảo trì lớn nhất, nhưng trực tiếp phục vụ kiến thức mạng. Chỉ sửa bug xác minh được; chưa mở rộng protocol.
- Hai bộ script PowerShell/Bash cần thiết cho nhóm dùng nhiều OS, nhưng tăng phần phải bảo trì. Không thêm dashboard, cache, ML hoặc framework để giải quyết đợt audit này.

## 7. Kế hoạch ưu tiên trước khi sửa

**Critical — xử lý trong lượt này:**

1. Sửa môi trường generated output ReadOnly để `mvn clean` chạy được; giới hạn tác động đúng thư mục target đã xác minh.
2. Nối ba main-method test vào Maven test lifecycle, chạy JVM fork và fail build nếu test lỗi/timeout. Không thêm dependency vào ứng dụng hay test framework.
3. Phân loại request client sai trước khi reserve: fragment/method/target không hợp lệ trả 400, không phạt backend. Thêm regression HTTP thật.
4. Sửa deadline/cleanup để client bị reject giữ body chưa gửi không giữ permit vô hạn; bảo vệ thao tác schedule đồng thời shutdown. Thêm regression giữ socket mở và shutdown.

**Important — ghi nhận, chưa triển khai thêm trong lượt này:**

- CPU unavailable phải là unknown; đọc telemetry nhất quán hơn; công bố CPU budget không phải quota CPU thật và heap không phải RAM host.
- Benchmark cần kiểm tra tham số NaN/Infinity/0, không silent default khi requests=0/rate=0; xác minh scheduling lag, mẫu tail và độ dài warmup; distribution hiện chỉ tính success; snapshot threads join(timeout) có thể còn sống nếu monitoring treo. Raw JSON có thể chứa NaN khi telemetry chuyển sang unknown.
- Thực hiện lặp nhiều lần homogeneous/heterogeneous/CPU/slowdown, lưu cả failure và tail latency; thí nghiệm kill/restart tiến trình chưa có script integration hiện thực. Không đổi workload để ép adaptive thắng.
- Sửa README đang hứa file integration và báo cáo chưa tồn tại; không tạo file giả chứa kết quả chưa đo.
- Làm rõ readiness hiện không xét tổng inflight toàn proxy; cap admission không là giới hạn tất cả TCP connection/JDK parser/monitoring. Cân nhắc timeout cho monitoring và client chưa gửi hết header.
- Kiểm tra CPU-worker stop/control concurrent và pinning trên đúng JDK 21 trước khi dùng làm số liệu bảo vệ.

**Optional — chưa làm:** đơn giản hóa getter/logger/config parsing, JFR profiling, sensitivity hệ số score, cân nhắc pool sau khi có số liệu. Không đổi công thức Adaptive ở lượt audit này.

## 8. Kết quả sau sửa

Đã sửa đúng các mục Critical, không thay công thức/chính sách chọn backend, không thêm feature, không xóa các phần được liệt kê ở mục 6:

- Bỏ ReadOnly chỉ trên đúng thư mục generated `D:\PBL4\reverse-proxy-demo\target` sau khi xác minh đường dẫn và không phải reparse point. Maven clean sau đó thành công. Không đổi thuộc tính source hoặc thư mục ngoài target.
- POM dùng AntRun 3.2.0 ở phase test để chạy ba main test trong JVM riêng, `-ea`, `failonerror=true`, timeout 60 giây mỗi chương trình. Đây là build plugin, không là dependency ứng dụng/test framework. Surefire được bỏ qua có chủ ý; bằng chứng chạy test nằm ở execution `plain-java-tests`.
- ProxyHandler kiểm tra method/target/fragment trước acquire; trả 400, không tạo lỗi backend.
- Client deadline interrupt thread đang làm I/O SocketChannel của JDK HttpServer thay vì đóng exchange từ thread khác. Deadline vẫn còn hiệu lực trong lúc close/drain, kể cả request bị reject bởi admission; permit được release trong finally. Cancel và callback interrupt được đồng bộ để tránh interrupt muộn sau khi scope đã xong.
- Chuẩn hóa race schedule-vs-shutdown thành IOException; socket/permit vẫn được cleanup.
- Thêm 8 check hồi quy vào HttpTransportTest: fragment/method không làm backend DOWN; body quá lớn giữ socket mở không giữ permit; đóng transport hủy socket chờ và từ chối request sau close.
- README chỉ cập nhật mô tả Maven test và kết quả JDK 21 để khớp thay đổi. Các phần Important khác, bao gồm đường dẫn integration/benchmark chưa có file, được giữ nguyên để xử lý ở bước kế tiếp.

Lệnh build/test sau sửa thực sự đã chạy (PowerShell, tại repository):

```powershell
$auditToolDir = Join-Path $env:TEMP 'pbl4-audit-tools'
$env:JAVA_HOME = Join-Path $auditToolDir 'jdk21/jdk-21.0.12.1+1'
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
& (Join-Path $auditToolDir 'apache-maven-3.9.16/bin/mvn.cmd') -B -ntp clean verify
```

**BUILD SUCCESS**, exit 0, tổng 18.713 giây. Maven 3.9.16; Microsoft OpenJDK 21.0.12.1+1-LTS; Windows 11 amd64. Cả compiler và các JVM test đều dùng JDK 21.

| Test chạy trong Maven | Kết quả |
|---|---|
| DispatcherTest | 13 scenario PASS; gồm burst 64 client cho cả ba chiến lược, cap global/backend, release đúng một lần, stale probe, recovery/warmup và các quyết định metric |
| HealthCheckerTest | PASS: poll độc lập, metrics sai/stale không làm ứng dụng DOWN, health lỗi và hồi phục |
| HttpTransportTest | 59 check PASS, tăng từ 51; gồm HTTP framing/body/headers, timeout, admission và các regression nói trên |

JAR: `target/reverse-proxy-demo.jar`. Log trước/sau được lưu ở `target/audit/` (generated, không commit): `maven-before.log`, `maven-before-no-clean.log`, `maven-after.log`. Maven/JDK tải tạm nằm ngoài repository; PATH/JAVA_HOME chỉ đổi cho process kiểm tra, không thay môi trường toàn hệ thống.

`git diff --check` không có lỗi whitespace; Git chỉ cảnh báo chuẩn hóa LF/CRLF. Chưa chạy benchmark hiệu năng trong lượt audit này. Không tuyên bố Adaptive thắng RR hoặc LC; các test quyết định tổng hợp chỉ chứng minh implementation phản ứng theo metric và giữ bất biến đồng thời, không chứng minh hiệu năng thực tế.

Nguồn công cụ: [Apache Maven](https://maven.apache.org/download.cgi), [Microsoft OpenJDK](https://learn.microsoft.com/en-us/java/openjdk/download), [Maven AntRun](https://maven.apache.org/plugins/maven-antrun-plugin/usage.html). Tài liệu chỉ dùng để chọn/chạy công cụ; các kết luận runtime ở trên lấy từ code và thử nghiệm repository.
