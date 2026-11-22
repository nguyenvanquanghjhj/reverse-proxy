# KIỂM TOÁN VÀ NGHIỆM THU DỰ ÁN PBL4 — REVERSE PROXY & LOAD BALANCER

Ngày kiểm toán: **22/11/2026**, múi giờ Asia/Bangkok.  
Repository: https://github.com/nguyenvanquanghjhj/reverse-proxy  
Checkout: `D:\PBL4\reverse-proxy-demo`. Commit: **32cbd9f879770269783ea69a2687d054636fd861**.  
Loại công việc: **AUDIT & ACCEPTANCE**, không phát triển hoặc sửa lỗi.

## 1. Tóm tắt điều hành

Dự án là một **reverse proxy HTTP/1.1 kiêm bộ cân bằng tải viết bằng Java 21**, đặt trước các backend ứng dụng. Phần có giá trị nghiên cứu là tiếp nhận và chuyển tiếp HTTP, chọn backend, quản lý tài nguyên, phát hiện lỗi, phục hồi và quan sát kết quả. **Mộc Store là ứng dụng thử nghiệm có trạng thái**, giúp kiểm tra request đọc/ghi, phiên người dùng, PostgreSQL và ảnh/PDF qua nhiều backend. Nó không phải điều kiện để thuật toán cân bằng tải tồn tại.

**Kết luận tổng thể: ĐẠT MỘT PHẦN đối với toàn bộ phạm vi yêu cầu; ĐÃ ĐẠT đối với lõi proxy/cân bằng tải trong môi trường kiểm thử hữu hạn đã nêu.** Không có cơ sở tuyên bố triển khai production, không gián đoạn, hiệu năng vượt trội hoặc phân tán ba máy vật lý đã nghiệm thu.

Các kết quả quan trọng:

- Biên dịch lại source hiện tại bằng JDK 21 trong thư mục thử riêng; **32/32 test main Java PASS**. Đây là số chương trình kiểm thử có hàm main, không phải 32 assertion hoặc 32 ca nghiệp vụ.
- Chạy độc lập **ba JVM backend và một JVM proxy** trên các cổng loopback riêng, không dùng phiên ứng dụng thật. Đổi được cả ba chiến lược. RR và LC mỗi loại chia 12 request thành 4–4–4; Adaptive chọn cùng một node cho 12 request trong lượt ngắn đó, phù hợp cách chọn theo điểm.
- **96 request, concurrency 16, đều HTTP 200** trong workload nhẹ. Dừng một backend thử nghiệm: 15 request tiếp theo đều 200 qua node khác. Khởi động lại node: được phục hồi. Backend chậm: nhận **504 sau khoảng 3,000 giây**. Dừng cả ba: nhận **503**. Không suy ra ngưỡng công suất hệ thống từ các lượt ngắn này.
- Đọc trực tiếp CI **đúng SHA hiện tại**, run **44666334377**: Java Linux PASS; Java Windows PASS ở bước Java; tools/config Linux PASS; **Docker live PASS 68 kiểm tra**. Toàn workflow FAIL do **một lỗi Python Windows**, tái hiện tại máy kiểm toán.
- Docker CI hiện tại có kiểm tra **đặt một đơn PostgreSQL thật, giảm stock đúng một đơn vị, replay cùng key sau restart**, ảnh/PDF đúng SHA, file 32/100/256 MiB, resume và persistence. Đây là bằng chứng thực nghiệm hiện tại từ runner Linux, không phải chạy Docker mới tại máy Windows này.
- Tuyến quyền Store được thử với HTTP thật và JDBC giả lập: anonymous bị chặn API admin bằng 401; CUSTOMER bị chặn GET/POST admin bằng 403; ADMIN đọc dashboard được 200. **Chưa chứng minh toàn bộ luồng login/thu hồi session với PostgreSQL thật tại máy này.**
- Store assets hiện **không phát ETag/304**. AssetCache có unit test PASS nhưng không được gọi trong tuyến assets hiện tại. Không dùng kết quả ETag lịch sử của Store để khẳng định tính năng đang hoạt động.
- **CHƯA KIỂM THỬ LAN ba máy vật lý**. Bộ script/hướng dẫn LAN không phải bằng chứng đã triển khai thực tế.
- Không chạy lại được PostgreSQL riêng tại máy này: Windows Application Control chặn initdb.exe, WinError 4551. Không kết nối DB thật để thay thế thử nghiệm đã bị chặn.

Hai phát hiện hành vi sai có phạm vi rõ ràng được phân tích ở mục 10: lệnh cấu hình LAN lỗi Unicode trên Windows; điều kiện If-None-Match wildcard bị bỏ qua trên tài nguyên Store đang tồn tại. Phát hiện thứ hai là giới hạn giao thức của origin Store, mức độ phụ trợ; **không được dùng để kết luận bộ cân bằng tải không hoạt động** hoặc bắt buộc phát triển thêm toàn bộ HTTP caching.

Không sửa source, script, cấu hình, UI, migration hay tài liệu cũ. Không commit/push/merge/đổi nhánh; không chạy build có dọn target, start/stop phiên thật, seed/reset DB thật, backup/restore dữ liệu thật. Chỉ thêm báo cáo này trong repository. Dữ liệu thử và log nằm ngoài checkout; các fixture tạm do bộ test tự quản lý không phải dữ liệu dự án.

## 2. Thông tin commit được kiểm tra

### 2.1. Nhận dạng và phạm vi source

| Thuộc tính | Giá trị xác nhận |
|---|---|
| Workspace cha | D:\PBL4; không phải Git repository |
| Repository thực tế | D:\PBL4\reverse-proxy-demo |
| Remote origin | https://github.com/nguyenvanquanghjhj/reverse-proxy.git |
| Nhánh local | main, theo dõi origin/main |
| HEAD | 32cbd9f879770269783ea69a2687d054636fd861 |
| Thời điểm commit | 20/11/2026 10:14:00 +07:00 |
| Git ban đầu | Sạch; không có thay đổi tracked hoặc untracked trong checkout |
| Mốc đối chiếu | 71eafcc8d8dad29a3074c654a36953abd8b06a82, 15/11/2026 15:16:04 +07:00 |
| Khoảng thay đổi | 13 commit sau mốc; 107 file thay đổi, 54.120 dòng thêm, 6.028 dòng bỏ theo git diff --shortstat |
| Công nghệ thực tế | Java release 21, JDK HttpServer, socket HTTP/1.1, JDBC PostgreSQL; PowerShell/Python; Docker Compose |
| Công cụ audit | Microsoft OpenJDK 21.0.13.0.2+1-LTS trong D:\PBL4\tools\jdk21; Python 3.12 |
| PATH hệ thống | java/javac mặc định trỏ JDK 26; audit dùng đường dẫn tuyệt đối tới JDK 21 |

Không coi tên commit “docs” hoặc “refactor” là bằng chứng hành vi giữ nguyên. `git diff 71eafcc8..HEAD` cho thấy **26 file Java main/test thay đổi**, gồm ProxyHandler, UpstreamTransport, HealthChecker, Dispatcher, LC, scorer Adaptive, StoreController, OrderService, FileStorage và các test. Ngoài ra có PdfLab, template HTML, công cụ Windows LAN, QA snapshot và chuyển vị trí evidence. Vì vậy test hoặc số đo tại 71eafcc8 không tự động đại diện HEAD.

Cấu trúc đã kiểm tra:

| Vùng | Nội dung |
|---|---|
| src/main/java/com/example/proxy | Listener, forwarding, thuật toán, health, metrics, chính sách HTTP |
| src/main/java/com/example/backend | Host backend, workload, admission, file lớn, fault fixture |
| src/main/java/com/example/store | Store hiện tại: controller/service/repository/security/storage |
| src/main/java/com/example/shop | JDBC/config và API shop cũ; vẫn được tham chiếu, không phải toàn bộ dead code |
| src/main/resources/web | Dashboard, workload console, Store và bảng benchmark đã lưu |
| src/test/java | 32 lớp *Test.java và TestSuite |
| config, db/migrations | Cấu hình nền và schema V001–V005 |
| scripts, scripts/ops | Build/run/test, thí nghiệm, giám sát, backup, kiểm tra triển khai |
| deploy/compose, deploy/lan | Container và công cụ cấu hình ba máy Windows |
| docs/evidence, docs/history | Bằng chứng và manifest lịch sử; cần gắn đúng phiên bản |
| .shop, .runtime, .env.store, shared-files | Tài nguyên được bảo toàn; không dùng làm môi trường thử ghi |

### 2.2. Quy ước bằng chứng

Báo cáo phân biệt năm việc: **(1) có mã; (2) mã nằm trong đường gọi; (3) có test; (4) test đã chạy trên SHA nào; (5) có quan sát thực nghiệm nào**. Không thay thế việc (5) bằng việc (1).

| Mã | Loại bằng chứng | Giá trị và giới hạn |
|---|---|---|
| S | Đọc source tại HEAD | Chứng minh triển khai/đường gọi; chưa đủ chứng minh mọi tình huống runtime |
| J | Chạy lại Java ngày 22/11 tại HEAD | 32 main PASS, fixture tự tạo; DBPool dùng synthetic JDBC, không phải PostgreSQL |
| L | Chạy lại core live tại HEAD | Ba backend JVM thật + proxy thật, HTTP loopback, Store/Shop tắt |
| F | Fixture HTTP Store tại HEAD | StoreApplication + controller + proxy thật, session/schema từ JDBC giả; không login/transaction PostgreSQL thật |
| P | Chạy lại Python tại HEAD | Operations 41 test: 40 PASS, 1 ERROR; mock HTTP defense tool PASS |
| CI | Đọc log CI đúng HEAD | Run 44666334377, step và log cụ thể; không đánh đồng toàn workflow với từng job |
| H | Evidence lịch sử có trong workspace | Có giá trị lịch sử; không tự quy về HEAD khi source fingerprint khác |
| U | Chưa xác minh | Không có thực nghiệm phù hợp, hoặc thử nghiệm bị chặn |

Nguồn CI chính: [run 44666334377](https://github.com/nguyenvanquanghjhj/reverse-proxy/actions/runs/44666334377), [Java Windows và Python lỗi](https://github.com/nguyenvanquanghjhj/reverse-proxy/actions/runs/44666334377/job/136056428832), [Docker live](https://github.com/nguyenvanquanghjhj/reverse-proxy/actions/runs/44666334377/job/136056428445), [Java Linux](https://github.com/nguyenvanquanghjhj/reverse-proxy/actions/runs/44666334377/job/136056428853), [tools/config Linux](https://github.com/nguyenvanquanghjhj/reverse-proxy/actions/runs/44666334377/job/136056428865). Đã đọc log Java Windows/Python và Docker, không chỉ nhìn badge.

Các đường dẫn source bên dưới tính từ repository. Cú pháp `file:line` là vị trí tại HEAD, kèm class/method để tìm lại khi số dòng thay đổi. Tệp thí nghiệm ngoài checkout chỉ là nơi giữ log cục bộ, không phải thành phần bắt buộc để build dự án.

## 3. Bài toán và ý nghĩa đề tài

### 3.1. Những khái niệm cần hiểu

**Backend** là máy chủ xử lý yêu cầu ứng dụng. Một backend vừa nhận HTTP, vừa truy vấn DB, tính toán, đọc tệp và tạo phản hồi. Khi số yêu cầu tăng hoặc một loại công việc kéo dài, CPU, heap, số luồng, hàng chờ, socket, DB connection, khóa DB hay tốc độ đĩa có thể trở thành nút thắt. Một server không cần dùng hết CPU mới bị quá tải: chờ DB hoặc tệp cũng có thể làm kín mọi suất phục vụ.

**Reverse proxy** đứng trước backend, nhận request tại một địa chỉ mà client biết và thay client liên lạc với backend. Trong dự án: client → Proxy:8080 → một backend → Proxy → client. Proxy có thể kiểm HTTP, giới hạn tài nguyên và ghi metrics. Nó không tự xử lý thanh toán hoặc trừ kho.

**Load balancer** quyết định chọn backend nào trong các backend có thể phục vụ. Đây là trách nhiệm khác với chuyển tiếp HTTP: forward đúng nhưng luôn chọn một node vẫn là proxy, chưa chứng minh phân phối tải. Dự án phối hợp hai trách nhiệm trong ProxyHandler và Dispatcher. Thuật ngữ gateway/reverse proxy trong HTTP được mô tả ở [RFC 9110 `3.7](https://www.rfc-editor.org/rfc/rfc9110.html#section-3.7).

Nhiều backend tạo thêm suất phục vụ và tạo cơ hội tiếp tục xử lý khi một tiến trình lỗi. Tuy nhiên, ba JVM trên cùng máy vẫn chung CPU, RAM, NIC, điện và hệ điều hành; không tạo ba miền lỗi vật lý độc lập. Chúng cũng không khắc phục một PostgreSQL hoặc một proxy duy nhất.

**Round Robin (RR)** chia lượt trong tập node hợp lệ, hữu ích làm baseline đơn giản. **Least Connections (LC)** ở dự án này chọn node có ít request lease đang chạy nhất, hữu ích khi request có thời lượng khác nhau. **Adaptive** dùng dữ liệu học và telemetry để dự báo chi phí hoàn tất; có ích khi backend và workload không đồng nhất, nhưng có rủi ro dự báo sai và không mặc nhiên nhanh nhất.

**Health check** chủ động hỏi node còn phục vụ không; tránh tiếp tục đưa request tới node đã chết và tạo căn cứ đưa node trở lại. **Readiness/capability** hỏi cụ thể DB/tệp/nghiệp vụ có dùng được không, khác với tiến trình còn sống. **Circuit breaker** tạm ngừng đưa thêm việc sau chuỗi lỗi và giới hạn phép thử phục hồi. Ba cơ chế có vai trò bổ sung, không phải ba tên gọi cho cùng một kiểm tra.

**Timeout** giới hạn thời gian chiếm tài nguyên. **Admission control** giới hạn số việc được nhận. **Metrics/monitoring** giúp biết request tới đâu, có bao nhiêu thành công/lỗi, chậm ở mức nào và node nào đang được chọn. Không có giới hạn đồng thời, chỉ tăng virtual thread vẫn có thể làm hết socket/heap/DB connection.

**PostgreSQL** giữ dữ liệu chung: người dùng, session, đơn, tồn kho, trạng thái vận hành, metadata tệp. **Shared File Storage** giữ byte ảnh/PDF; DB giữ khóa tệp, MIME, kích thước và SHA-256. Chúng hỗ trợ workload có trạng thái, không thuộc định nghĩa bắt buộc của mọi reverse proxy.

### 3.2. A — Phát biểu bài toán

Trong hệ thống ứng dụng HTTP có nhiều yêu cầu đồng thời và thời gian xử lý không đồng nhất, việc phụ thuộc vào một máy chủ ứng dụng có thể làm tăng hàng chờ, độ trễ và nguy cơ gián đoạn khi tiến trình lỗi. Đề tài nghiên cứu và hiện thực một reverse proxy kết hợp cân bằng tải ở tầng ứng dụng bằng Java 21, cung cấp điểm truy cập thống nhất, chuyển tiếp thông điệp HTTP tới backend phù hợp, lựa chọn backend bằng Round Robin, Least Connections và Adaptive, đồng thời quản lý giới hạn tài nguyên, thời hạn xử lý, sức khỏe và khả năng phục vụ. Việc đánh giá dựa trên tính đúng đắn của giao thức, hành vi phân phối, xử lý lỗi và số đo thực nghiệm trong điều kiện xác định; ứng dụng Mộc Store được sử dụng như workload có trạng thái để kiểm tra dữ liệu và tệp dùng chung qua nhiều backend.

### 3.3. B — Mục tiêu nghiên cứu và triển khai

1. Thực hiện đúng đường HTTP request/response qua một origin, bảo toàn dữ liệu trong các trường hợp thuộc phạm vi.
2. Hiện thực và giải thích được RR, LC, Adaptive trên cùng chính sách eligibility.
3. Phát hiện lỗi backend, loại khỏi lựa chọn và kiểm soát phục hồi; trả lỗi hữu hạn khi không có node phù hợp.
4. Giới hạn concurrent request, task, body/header/socket và thời gian; quản lý lease/ticket khi thành công, lỗi, timeout hoặc shutdown.
5. Quan sát được phân phối, in-flight, outcome, độ trễ và telemetry; phân biệt lỗi nghiệp vụ với lỗi vận chuyển.
6. Kiểm tra workload tổng hợp và workload Store có transaction/idempotency; so sánh thuật toán bằng cùng điều kiện và cùng source.
7. Xác định giới hạn triển khai local, Docker và LAN, không suy diễn độ sẵn sàng hay quy mô chưa đo.

### 3.4. C — Phạm vi

**Cốt lõi bắt buộc:** HTTP forwarding; lựa chọn backend thực sự; ba chiến lược đã đặt ra; sức khỏe/lỗi; timeout và giới hạn tài nguyên; metrics; kiểm chứng và so sánh có phương pháp.

**Phụ trợ nhưng có ích:** Store, PostgreSQL, shared storage, dashboard, script lifecycle, Docker, backup và công cụ LAN. Chúng làm thí nghiệm và vận hành có ý nghĩa; không biến mọi chức năng bán hàng thành mục tiêu đề tài.

**Mở rộng đã có nhưng opt-in:** upstream keep-alive pool, rate limit, critical QoS, download bulkhead, listener quản trị riêng, file streaming và PDF lab. Phải chỉ rõ profile bật khi trình bày.

**Không đặt làm yêu cầu:** proxy tổng quát hỗ trợ mọi giao thức; HTTPS termination nội bộ, HTTP/2–3, WebSocket/CONNECT; autoscaling/cloud orchestration; proxy HA; đồng thuận/replication DB; thương mại điện tử đầy đủ.

### 3.5. D — Ý nghĩa

Về kỹ thuật, dự án nối thuật toán lựa chọn với giao thức, concurrency và xử lý sự cố. Về học thuật, nó cung cấp cơ sở so sánh chiến lược theo thời gian phục vụ, tải và độ không đồng nhất thay vì chỉ nhìn số request. Về thực tiễn, nó minh họa cách giữ dữ liệu chung khi request đổi backend và cách giới hạn tác động của một node hoặc dependency lỗi.

### 3.6. E — Giới hạn tuyên bố

Không tuyên bố đạt SLA, HA hoàn chỉnh, failover không mất request, mở rộng ngang tuyến tính, hiệu suất tốt hơn sản phẩm chuyên dụng, bảo mật được chứng nhận, ACID xuyên DB–filesystem, hoặc triển khai production. Không suy ra ba máy vật lý từ ba port/JVM/container. Không gọi số đo giả định, mock hoặc dữ liệu cũ là benchmark hiện tại.

## 4. Kiến trúc hiện thực

### 4.1. Sơ đồ và đường gọi thực tế

~~~mermaid
flowchart LR
    C[Client HTTP] --> R[ReverseProxyServer / HttpServer]
    R --> P[ProxyHandler: validate, admission, deadline]
    P --> D[Dispatcher: eligibility, algorithm, lease]
    D -. backend được chọn .-> P
    P --> T[UpstreamTransport hoặc StreamingProxy]
    T --> B[BackendApplicationServer]
    B --> W[WorkloadHandler]
    B --> S[StoreApplication / StoreController]
    S --> V[Service / Repository]
    V --> DB[(PostgreSQL chung)]
    V --> FS[(Shared File Storage)]
    B --> T
    T --> P
    P --> C
    H[HealthChecker] --> B
    H --> D
~~~

Đây là sơ đồ **rút ra từ đường gọi source**. L xác minh nhánh workload với ba JVM thật; F xác minh một số nhánh Store bằng JDBC giả; CI xác minh nhánh Store/PostgreSQL/file trong Docker. Không coi mọi nhánh trên sơ đồ đều đã được chạy tại máy kiểm toán.

1. **Khởi động proxy:** `proxy/Main.java:20`, Main.main gọi HttpListenerPolicy.installDefaults, ConfigLoader.load và ReverseProxyServer.start. `proxy/server/ReverseProxyServer.java:26` tạo Dispatcher/HealthChecker/ProxyHandler; `:46` gắn context “/” với handler và “/__proxy/” với management.
2. **Tiếp nhận trước handler:** `proxy/server/BoundedRequestExecutor.java:26`, execute giữ semaphore task, mở virtual thread, đăng ký deadline; không có executor queue vô hạn. Hết task có thể đóng kết nối trước khi có HTTP status/request ID. `HttpListenerPolicy.java:7` giới hạn kết nối/idle/header toàn JVM, khác giới hạn request.
3. **Theo dõi request:** `proxy/handler/ProxyHandler.java:59`, handleTracked tạo/kiểm request ID, ghi log, phân loại traffic và đặt deadline. `:153`, forward xin PriorityAdmission.Ticket rồi kiểm metadata, phương thức, rate limiter và bulkhead nếu bật.
4. **Đầu vào HTTP:** `IncomingRequestValidator.java:16`, host; `:29`, forwardedRequest; `:57`, framing. Kiểm Host, origin-form, Content-Length/Transfer-Encoding, giới hạn header; chặn đường control backend qua proxy. CONNECT/protocol upgrade trả 501; TRACE bị chặn ở ProxyHandler.
5. **Body/header:** `ProxyHandler.java:197` đọc body có giới hạn; `:201` giữ end-to-end headers, loại header Forwarded/X-Forwarded do client cung cấp, đặt X-Forwarded-For từ peer, X-Forwarded-Proto=http, Via và request ID.
6. **Chọn node:** `ProxyHandler.java:217` gọi Dispatcher.acquire(method, URI). Dispatcher dưới một lock lọc capability, alive/draining, in-flight, circuit; Adaptive thêm dự báo work budget. Chọn và reserve work/in-flight/circuit trước khi trả Lease (`Dispatcher.java:68–143`).
7. **Chuyển HTTP:** `ProxyHandler.java:236` gọi UpstreamTransport.exchange cho tuyến thường. `UpstreamTransport.java:104` kiểm request/framing, ghi request HTTP/1.1 vào socket, đọc status/header/body; timeout/kích thước hữu hạn. Host upstream được dựng từ backend cấu hình, không từ địa chỉ tùy ý của client.
8. **Backend:** `backend/BackendApplicationServer.java:77` nhận route; `:134` workload điều phối admission, worker semaphore và handler. `:176` phục vụ file lớn; `:179` vào StoreApplication; `:185` fallback LegacyShopApi; `:192` vào WorkloadHandler.
9. **Nghiệp vụ:** `store/StoreApplication.java:35` vào StoreController cho /api/store; `StoreController.java:20` kiểm method/session/CSRF/admin và gọi service. OrderService/Sql dùng JDBC, FileService/FileStorage dùng metadata và byte tệp.
10. **Hoàn tất phía backend:** `ProxyHandler.java:224` sở hữu lease; `Dispatcher.java:362`, Lease.finish cập nhật work/health/circuit đúng một lần, release in-flight. Với buffered HTTP, bước này xảy ra khi đã đọc xong upstream, **trước khi client nhận đủ body**.
11. **Trả về client:** `ProxyHandler.java:301` sao chép headers, đặt headers proxy và xử lý HEAD/204/304 không body. `:325` ghi body. `:328` trả bulkhead/admission trong finally; handleTracked kết thúc traffic/log. Lỗi downstream không được tính ngược thành backend chết.

### 4.2. Ba thuật toán và điều kiện lựa chọn

| Thành phần | Hiện thực thực tế | Bằng chứng |
|---|---|---|
| RR | sequence modulo số candidate đủ điều kiện; khi tập candidate đổi không bảo đảm chia chính xác mọi cửa sổ | RoundRobinLoadBalancer.select, :7–8; J, L |
| LC | min inFlight của request lease, hòa chọn lastSelectedNanos nhỏ nhất; không đếm socket idle | LeastConnectionsLoadBalancer.select, :8–10; J, L |
| Adaptive | dự báo service/wait/outstanding theo route; score = completion dự báo × resource penalty; thăm dò node lâu chưa chọn mỗi N dispatch | EstimatedWork.forecast :135; AdaptiveCandidateScorer.score :9; AdaptiveLoadBalancer.select :17–26; J, L |
| Eligibility chung | capability đủ mới khi route cần; alive; không drain; dưới giới hạn in-flight; circuit cho phép | Dispatcher.acquire :84–120 |
| Atomic reservation | tổng max in-flight, chọn node và reserve cùng một lock; không thực hiện I/O dưới lock | Dispatcher.acquire :69, :123–143 |
| Kết quả | Lease có cờ released; finish/close không giải phóng hai lần; late probe có health generation | Dispatcher.Lease :332; BackendMetrics.health :71 |

Adaptive **không phải AI/machine learning tổng quát**. EstimatedWork dùng nhóm route hữu hạn và EWMA; không học theo vô hạn URL. Chỉ mẫu thành công đủ điều kiện mới huấn luyện service estimate; request phải chờ queue không tự coi RTT là service time (`EstimatedWork.complete:167`). Công thức penalty hiện tại:

~~~text
penalty = 1 + 0,35 × CPU_pressure + 0,5 × memory_pressure
            + 2 × error_EWMA + uncertainty
score = ECT_dự_báo × penalty
ECT = thời_gian_chờ_dự_báo + thời_gian_dịch_vụ_dự_báo
~~~

`BackendMetrics.snapshot:105–122` tính CPU pressure có chặn mẫu số, memory pressure tăng trên 75%, penalty bất định khi telemetry thiếu/cũ; warmup làm giảm capacity hữu hiệu. EWMA alpha nền 0,2. Dispatcher áp completion budget = min(readTimeout, requestTimeout), nền 3.000 ms, và có relearning probe có cooldown (`Dispatcher.java:106–118,164`). Exploration mặc định 50 không có nghĩa mỗi 50 request client chắc chắn một node bất kỳ được chọn: nó áp lên lượt chọn Adaptive trong tập hợp lệ.

### 4.3. Health, circuit, lỗi và timeout

HealthChecker.pollLoop (`healthcheck/HealthChecker.java:57`) có một vòng poll nối tiếp cho từng backend, dùng virtual thread. Gọi /health, nếu healthy thì /ready khi capability routing bật, rồi /metrics. **500 ms là thời gian nghỉ giữa vòng**, không phải cam kết phát hiện lỗi đúng 500 ms: cần cộng thời gian probe, timeout và scheduling.

BackendMetrics (`metrics/BackendMetrics.java:62–85`) chuyển DOWN khi health fail/transport fail; phục hồi sau số health success yêu cầu, dùng generation tránh kết quả cũ làm sống lại node sai và warmup 5 giây. Telemetry lỗi không tự đánh DOWN backend (`HealthChecker.java:101–114`).

CircuitBreaker (`loadbalancer/CircuitBreaker.java:21–52`): CLOSED nhận việc; đủ lỗi liên tiếp → OPEN; hết cooldown → HALF_OPEN, tối đa một probe; thành công đóng circuit, thất bại mở lại. Nền threshold 5, cooldown 5.000 ms. Đơn bị từ chối nghiệp vụ không tương đương transport fail; dependency failure đã nhận diện có nhánh completeServiceFailure riêng.

| Tình huống | Hành vi có trong code |
|---|---|
| Không có backend khỏe/đủ capacity/capability | 503 |
| Capacity handler/request/download hết | Có thể đóng kết nối trước HTTP, hoặc 503 khi đã vào handler |
| Client rate quá mức khi bật limiter | 429 + Retry-After |
| Body/header quá giới hạn | 413/431; upstream response vượt policy trả 502 |
| Không kết nối/response HTTP upstream hỏng | 502; cập nhật transport failure |
| Backend/read deadline timeout | 504 khi còn có thể gửi headers; cập nhật timeout |
| Lỗi sau headers/body đã bắt đầu | Kết thúc truyền, không gửi một response lỗi thứ hai |
| Backend dependency Store lỗi có nhận dạng | Giới hạn theo service/capability; không tự kết luận toàn JVM chết |
| Mất response của POST sau khả năng commit | Không tự replay POST; client phải kiểm tra hoặc dùng lại cùng idempotency intent |

### 4.4. Mặc định và tính năng opt-in

Nguồn: `config/application.properties:2–74` và ProxyConfig. Đây là **config nền**, không phải khẳng định phiên Store đang chạy dùng đúng mọi giá trị này.

| Tham số | Giá trị nền | Ý nghĩa |
|---|---|---|
| Proxy / backend | 127.0.0.1:8080; 9001/9002/9003, capacity 8 | Local loopback; trường thứ ba là capacity, không phải trọng số WRR |
| Strategy | ADAPTIVE | Đổi qua control API có điều kiện |
| Proxy in-flight / handler tasks | 128 / 144 | Hai giới hạn khác nhau |
| Backend max in-flight tại dispatcher | 64/node | Worker service capacity vẫn là 8/node |
| Body / header | 1 MiB / 32 KiB, 100 fields | Buffered request/response hữu hạn |
| Chunk metadata | 64 KiB | Không tính payload chunk vào cùng budget này |
| Connect / read / request timeout | 500 / 3.000 / 5.000 ms | Không coi read timeout là toàn bộ SLA |
| Health interval/timeout/recovery | 500 ms / 500 ms / 2 success | Có độ trễ quan sát |
| Telemetry stale / warmup | 3.000 / 5.000 ms | Dữ liệu cũ và slow start |
| Circuit / capability / decision trace | Bật | Không phụ thuộc việc UI có đang mở |
| Rate limit / upstream TCP pool | Tắt / tắt | Có code và test, chưa bật ở config nền |
| Critical reserved slots / PDF bulkhead | 0 / 0 | QoS reserve và bulkhead đang tắt |
| Management listener riêng | Tắt | /__proxy/* trên data port vẫn có, giới hạn loopback |
| Streaming | Tắt, deadline profile 120.000 ms | Chỉ GET/HEAD /api/pbl4/files/* khi bật |
| JDBC pool | SHOP_DB_POOL_ENABLED mặc định false | Compose bật true; độc lập upstream pool |

### 4.5. JVM backend và tính phân tán

`scripts/app_runtime.py:182–213` khởi chạy BackendApplicationServer mỗi node bằng một Popen JVM riêng; ba port local 9001/9002/9003; proxy là JVM thứ tư. `backend/BackendApplicationServer.java:238` là main, nhận port/capacity/delay/cpuWorkers/cpuBudget. Launcher local truyền delay 0; gọi main không tham số có default delay theo port 20/80/240 ms. Không nhầm hai profile.

Có thể khai báo thêm địa chỉ trong backend.servers; ConfigLoader.load(Properties) (`config/ConfigLoader.java:21`) tạo danh sách backend, Dispatcher tạo state theo danh sách đó. Đây là **mở rộng bằng cấu hình khi khởi động**, chưa có bằng chứng hot-add/hot-remove/service discovery. Tăng số node vẫn bị giới hạn proxy, DB, storage và phần cứng.

Compose chạy ba container cùng network, dùng một PostgreSQL và store-files volume. LAN có vai trò MAIN chứa Proxy+B1+DB+storage, BACKEND2 và BACKEND3 nằm máy khác. Hướng dẫn đòi cùng DB/SMB thật, token và kiểm identity; unit test cấu hình không thay thế kiểm thử mạng thật.

## 5. Kiểm kê chức năng

Chỉ dùng năm trạng thái yêu cầu. **HOÀN THÀNH** ở đây luôn giới hạn trong hợp đồng và ca thử ghi bên cạnh, không phải cam kết mọi lỗi mọi tải. **MỘT PHẦN** có thể là thiếu coverage hoặc chỉ hiện thực một phần phạm vi; **CHƯA XÁC MINH** không có nghĩa không có code.

| STT | Thành phần/chức năng | Vai trò | Trạng thái | Bằng chứng | Kết quả kiểm thử | Hạn chế |
|---|---|---|---|---|---|---|
| 1 | Listener proxy HTTP | Một địa chỉ nhận request | HOÀN THÀNH | ReverseProxyServer.start:46; Main.main:20 | J, L | Loopback nền, chưa production |
| 2 | Forward request | Gửi method/target/header/body | HOÀN THÀNH | ProxyHandler.forward:153; UpstreamTransport.exchange:104 | J HttpTransport/Contract/Intermediary; L | HTTP/1.1 trong phạm vi |
| 3 | Return response | Trả status/header/body | HOÀN THÀNH | ProxyHandler:301–326 | J; L; CI Docker file hash | Buffered giới hạn byte |
| 4 | RR | Chia lượt candidate | HOÀN THÀNH | RoundRobinLoadBalancer.select:7 | J; L 12/12, 4–4–4 | Candidate đổi có thể lệch |
| 5 | LC | Min active lease | HOÀN THÀNH | LeastConnectionsLoadBalancer.select:8 | J; L 12/12 | Không đếm mọi TCP socket |
| 6 | Adaptive selection | Score/exploration | HOÀN THÀNH | AdaptiveLoadBalancer.select:17 | J AdaptiveDecision/Dispatcher/EstimatedWork; L | Chưa chứng minh nhanh nhất |
| 7 | Adaptive work estimate | Dự báo service/queue | HOÀN THÀNH | EstimatedWork.forecast:135, complete:167; Dispatcher:100 | J deterministic model | Model có sai số, telemetry trễ |
| 8 | Atomic lease | Chọn và giữ chỗ đồng bộ | HOÀN THÀNH | Dispatcher.acquire:68; Lease:333 | J ConcurrentLifecycle/Lifecycle/Dispatcher | Lock tập trung |
| 9 | Health / recovery | DOWN/UP, warmup | HOÀN THÀNH | HealthChecker.pollLoop:57; BackendMetrics.health:71 | J; L kill/restart | Không cam kết recovery 500 ms |
| 10 | Circuit breaker | Ngắt chuỗi lỗi | HOÀN THÀNH | CircuitBreaker:21–52; Lease.finish | J CircuitBreaker + HttpTest | Một half-open probe |
| 11 | Capability routing | Phân biệt DB/tệp và alive | HOÀN THÀNH | CapabilityRouting.required:66; Dispatcher:84 | J CapabilityRouting/JsonStrict | Live DB network cut tại HEAD chưa chạy lại |
| 12 | Timeout / deadline | Hạn chế giữ tài nguyên | HOÀN THÀNH | ProxyHandler:78,268; RequestDeadline; executor | J; L 504 ~3 s | Sau headers không đổi status |
| 13 | Proxy admission | Chặn quá tải hữu hạn | HOÀN THÀNH | PriorityAdmission.tryAcquire; executor.execute:26 | J PriorityAdmission/Hardening/ListenerResource | Reject trước HTTP có thể chỉ đóng socket |
| 14 | Backend admission | Giới hạn active+queued | HOÀN THÀNH | BackendApplicationServer:164–202; RequestAdmission | J; L | Không đo capacity tối đa |
| 15 | Rate limiting | Token bucket theo peer | HOÀN THÀNH | ClientRateLimiter.acquire; config:51 | J RateLimiter | Opt-in, NAT gộp người dùng |
| 16 | Critical QoS | Dành slot cho order POST | HOÀN THÀNH | PriorityAdmission; config:23 | J PriorityAdmissionSecurity/Test | Reserved=0 nền; không bảo đảm DB thành công |
| 17 | Download bulkhead | Cách ly download | HOÀN THÀNH | DownloadBulkhead.acquire; ProxyHandler:189 | J DownloadBulkhead; CI streaming | Tắt ở config nền |
| 18 | Upstream keep-alive pool | Tái dùng socket GET | HOÀN THÀNH | UpstreamTransport:127–201 | J Pooling/Failure/Adversarial | Tắt nền; không pool/retry POST |
| 19 | Tệp lớn streaming | Không buffer toàn file | HOÀN THÀNH | StreamingProxy.transfer:53; LargeFileHandler.serve:26 | J; CI 32/100/256 MiB, SHA/resume/restart | Profile opt-in; không phải Store PDF |
| 20 | Range / ETag streaming | Resume/conditional | HOÀN THÀNH | LargeFileHandler:64–87; RangeRequest | J Range/Streaming; CI If-Range | Single range, immutable public files |
| 21 | PDF lab HTTPS | Nhập mẫu QA có receipt | MỘT PHẦN | PdfLab.begin:29; importPdf:87; web/pdf-lab.js | J PdfLabSecurity PASS | Chưa nhập HTTPS live trong audit; tắt nền |
| 22 | Metrics traffic | Outcome/latency theo nhóm | HOÀN THÀNH | TrafficMetrics.classify:15, completed:38 | J TrafficOutcome; L counters nhất quán | Ring 2048, không phải time-series bền vững |
| 23 | Metrics backend/transport | CPU/heap/leases/socket I/O | HOÀN THÀNH | BackendMetrics.snapshot:105; TransportMetrics | J; L; CI metrics | Phạm vi gauge khác nhau |
| 24 | Dashboard HTML | Hiển thị/đổi strategy | MỘT PHẦN | MonitoringPage; web/monitoring.html:79 | API J/L/CI; browser lịch sử H | Chưa kiểm browser UI HEAD |
| 25 | Logging request | Correlation/error hữu hạn | HOÀN THÀNH | ProxyHandler.handleTracked:59–132; AppLogger | J/L quan sát log | Không tracing phân tán; path che |
| 26 | Control plane | Loopback/action/token | HOÀN THÀNH | ReverseProxyServer.monitor:82; OperatorAuth:12 | J Management/PrioritySecurity; CI token | Token vắng cho phép legacy local |
| 27 | PostgreSQL/JDBC | Dữ liệu chung đa JVM | HOÀN THÀNH | shop/Database.with:25; Sql.statement:10 | CI checkout/SQL/restart | Không chạy DB mới local được |
| 28 | JDBC pool hygiene | Reset/rollback connection | HOÀN THÀNH | Database.recycle:68 | J DatabasePool giả driver | PostgreSQL pool failure live HEAD chưa đủ |
| 29 | Order create/replay | Commit và chống nhân đôi intent | HOÀN THÀNH | OrderService.create:15; V002:42–49 | CI một đơn, stock -1, replay sau restart | Không bảo đảm exactly-once mọi failure |
| 30 | Cạnh tranh stock/rollback nhiều JVM | Không bán vượt kho | MỘT PHẦN | OrderService:24–43; Sql.transaction:29 | H có ca; HEAD source+CI đơn lẻ | Chưa chạy lại stress transaction HEAD |
| 31 | Catalog/search/detail | Workload đọc SQL | HOÀN THÀNH | StoreController:24–28; CatalogRepository | CI catalog/ảnh/PDF; S | Filter đầy đủ/UI chưa thử lại |
| 32 | Session/auth/login/logout | Phiên chung, CSRF | MỘT PHẦN | SessionService.resolve:21, csrf:35, authenticate:42 | CI session thật; F role fixture | Login/logout end-to-end PG HEAD chưa thử đủ |
| 33 | API admin role gate | Bảo vệ nghiệp vụ quản trị | HOÀN THÀNH | StoreController:40–52; SessionService.admin:41 | F anonymous 401 / CUSTOMER 403 / ADMIN 200 | Fixture role giả; không kết luận toàn diện bảo mật |
| 34 | /shop, /admin, /admin/shop | Route giao diện | HOÀN THÀNH | StoreApplication.handle:35–44; site.js:13 | F 200/303/403 theo vai trò | Admin shop là shell công khai; UI browser chưa nghiệm thu |
| 35 | Store assets trả byte | JS/CSS cho UI | HOÀN THÀNH | StoreApplication:29–44 | F app.js 200, 5521 byte | Không ETag; xem dòng kế |
| 36 | Store conditional wildcard | Tôn trọng điều kiện HTTP | CÓ LỖI | StoreApplication:43–44; Http.send:21 | F If-None-Match:* →200 | Nhóm A-02, lỗi origin phụ trợ |
| 37 | AssetCache helper | Tạo/so ETag | MỘT PHẦN | AssetCache.etag:13, notModified:23 | J AssetCache PASS | Không được gọi bởi tuyến assets |
| 38 | Shared Store file download | Metadata + hash + byte | HOÀN THÀNH | FileService.download:51; FileStorage.read:55 | CI ảnh/PDF đúng hash, giữ sau restart | Store PDF bounded, không Range |
| 39 | Upload PDF/ảnh | Kiểm định dạng và lưu tệp | MỘT PHẦN | StoreController:48; FileService.upload:35; FileStorage:30 | S; H có kiểm; CI seed/download | Upload HTTP HEAD chưa chạy PostgreSQL thật |
| 40 | Store operations/admin CRUD | Pause, trạng thái đơn, catalog | MỘT PHẦN | OperationsService.update:32; CatalogService; OrderService.transition:66 | S; H; F chặn CUSTOMER | Chưa nghiệm thu toàn bộ CRUD HEAD |
| 41 | API Shop lịch sử | Workload cũ | CHƯA XÁC MINH | BackendApplicationServer:185; LegacyShopApi.route:49; OrderRepository | S có đường gọi khi SHOP_ENABLED | L tắt, Docker chỉ chứng minh Store hiện tại |
| 42 | Local lifecycle/build scripts | Chạy/dừng đúng JVM | MỘT PHẦN | run.ps1; app_runtime.launch:137, stop:171; build.ps1 | S; J compile thay thế an toàn | Không chạy launcher thật trong audit |
| 43 | TestSuite Java | Chạy mọi *Test main | HOÀN THÀNH | TestSuite.main:9–53; pom.xml:43–49 | J 32/32; CI Linux/Windows Java | Không có automatic PG Store integration trong 32 main |
| 44 | Python ops Windows | QA/backup/LAN tooling | CÓ LỖI | windows_nodes.configure:117; test_windows_nodes:27 | P 40 PASS, 1 ERROR; CI giống | Unicode console cp1252 |
| 45 | Docker build/live | Stack cô lập và persistence | HOÀN THÀNH | compose.yaml; Dockerfile; acceptance_docker_ci.py:22 | CI 68/68 đúng SHA | Runner Linux một host; profile streaming riêng |
| 46 | LAN ba máy vật lý | Backend nhiều máy + DB/SMB | CHƯA XÁC MINH | deploy/lan/windows_nodes.py; README | Unit config có; physical NOT TESTED | Không có ba máy/SMB thật trong audit |
| 47 | Benchmark so sánh HEAD | Chứng minh hiệu năng tương đối | CHƯA XÁC MINH | research.py; defense_live.py; evidence H | L smoke hữu hạn; H benchmark source khác | Không có benchmark đối chứng mới trên HEAD |
| 48 | Backup/restore | Bảo toàn DB và tệp | MỘT PHẦN | pg_safeguard; consistent_bundle.backup_offline:188, restore_new:245 | P fake archive; H live cũ | Không backup/restore dữ liệu thật; chưa live HEAD |
| 49 | Backend mở rộng bằng config | Thêm địa chỉ khi start | MỘT PHẦN | ConfigLoader.load:21; Dispatcher constructor:35 | S/J danh sách; L ba node | Chưa đo >3 node; không hot registry |
| 50 | HA của proxy/DB/storage | Loại single point of failure | CHƯA TRIỂN KHAI | Một proxy, một PG, một volume trong Compose | S; không có HA test | Ngoài phạm vi bắt buộc; không tính là lỗi thiếu của lõi |

Dòng 50 được liệt kê để làm rõ giới hạn theo yêu cầu; trạng thái không hàm ý bắt buộc phát triển HA. Một thành phần có trạng thái HOÀN THÀNH không làm các dòng phụ thuộc chưa xác minh tự thành hoàn thành.

## 6. Đối chiếu với tên đề tài

**Nếu bỏ toàn bộ Mộc Store, dự án vẫn còn một reverse proxy và load balancer có nội dung kỹ thuật độc lập**: listener, HTTP/1.1 transport, RR/LC/Adaptive, health/metrics, circuit, admission/deadline, workload tổng hợp, control API và công cụ thử. L đã thực nghiệm đúng trường hợp Store/Shop đều tắt.

| Tiêu chí trực tiếp | Đánh giá nghiệm thu | Cơ sở và giới hạn |
|---|---|---|
| 1. Một địa chỉ HTTP thống nhất | ĐÃ ĐẠT | Listener thực, J/L |
| 2. Forward request | ĐÃ ĐẠT | Socket transport và test protocol, J/L |
| 3. Response quay về client | ĐÃ ĐẠT | Body/header/status, file hash J/CI |
| 4. RR | ĐÃ ĐẠT | Chọn luân phiên; L 4–4–4 |
| 5. LC | ĐÃ ĐẠT | Active lease; deterministic/concurrent tests |
| 6. Adaptive | ĐÃ ĐẠT về hiện thực; CHƯA ĐỦ BẰNG CHỨNG về ưu thế hiệu năng HEAD | Có forecast/penalty/exploration, không chỉ tên class |
| 7. Backend sống/chết | ĐÃ ĐẠT | Polling, generation, recovery; L kill/restart |
| 8. Mất kết nối backend | ĐÃ ĐẠT trong ca đã thử | 502/504 hoặc loại node; không tự replay request đang mất |
| 9. Giới hạn đồng thời | ĐÃ ĐẠT về cơ chế | J admission/resource tests; không có capacity claim |
| 10. Timeout/lỗi HTTP | ĐÃ ĐẠT trong hợp đồng core; ĐẠT MỘT PHẦN nếu bao gồm origin Store conditional | J/L; A-02 chỉ tuyến Store phụ trợ |
| 11. Metrics/hiệu năng | ĐẠT MỘT PHẦN | Bộ đo hoạt động; chưa đối chứng hiệu năng mới |
| 12. Giám sát/vận hành | ĐẠT MỘT PHẦN | API/CI Docker có; Windows LAN lỗi console; UI HEAD chưa browser-test |
| 13. Load test/so sánh thuật toán | ĐẠT MỘT PHẦN | Harness/raw lịch sử có; HEAD smoke không là benchmark đầy đủ |
| 14. Mở rộng backend | ĐẠT MỘT PHẦN | Config danh sách; chưa chứng minh throughput scale hay hot-add |
| 15. Nhiều máy vật lý | CHƯA ĐỦ BẰNG CHỨNG | Script/hướng dẫn không là nghiệm thu |
| 16. Single point of failure | Giới hạn được xác nhận | Proxy, DB, storage và host có thể là SPOF; HA không thuộc yêu cầu bắt buộc |
| 17. Trạng thái dữ liệu khi đổi node | ĐẠT MỘT PHẦN | CI session/order/file/restart; concurrency/failure DB HEAD chưa đầy đủ |

**Loại tải đã có cơ sở:** request HTTP hữu hạn theo contract; workload nhẹ/chậm/CPU/tải tệp tổng hợp; catalog/order/PDF trong Docker; file lớn bất biến khi bật profile riêng. **Quy mô đã xác minh trực tiếp local:** một host, ba backend JVM, thử 96 request với concurrency 16. **Quy mô CI:** ba backend container cùng host, file tới 256 MiB dưới proxy heap 64 MiB trong profile thử.

Không có số liệu để nói “chịu X người dùng thực”, “tải liên tục Y giờ”, “công suất tăng gấp ba” hoặc “Adaptive luôn tốt nhất”. Việc L chọn toàn bộ 12 request vào một node với Adaptive chứng minh khác biệt chính sách, chưa chứng minh lựa chọn đó tối ưu.

## 7. Vai trò của Mộc Store

### 7.1. Vì sao ứng dụng bán hàng phù hợp

Không có biên bản để kết luận động cơ thật của nhóm. **Xét về kỹ thuật**, ứng dụng bán hàng là lựa chọn phù hợp vì tạo ra nhiều workload quen thuộc:

| Hoạt động | Công việc tài nguyên | Điều cần chứng minh đối với proxy |
|---|---|---|
| Xem/tìm sản phẩm | SQL đọc, JSON, ảnh | Phân phối request đọc; response/data nhất quán |
| Đăng nhập/session | Hash mật khẩu, DB session, cookie, CSRF | Đổi backend vẫn nhận đúng identity |
| Đặt hàng | SQL ghi, khóa hàng, tính tiền, commit | Không làm mất ý định hoặc nhân đôi đơn khi retry có key |
| Quản trị sản phẩm/đơn | Quyền, CRUD, optimistic version | Proxy không thay thế kiểm quyền backend |
| Tải PDF/ảnh | DB metadata, disk read, SHA, truyền byte | Nội dung đúng khi backend đổi; tải tệp ảnh hưởng request khác thế nào |
| Pause bán/upload/download | Trạng thái vận hành chung | Business pause khác node chết/DB chết |

Chỉ cần một nhóm catalog, một giao dịch đặt hàng có replay, một file đọc và vài tình huống lỗi đã đủ làm workload minh họa tốt. Gallery nhiều mẫu, chi tiết thương hiệu, dashboard doanh thu và quy trình bán hàng phong phú là phụ trợ.

### 7.2. Trạng thái và dữ liệu đi đâu

SessionService lưu hash token và CSRF trong PostgreSQL (`SessionService.java:21–33`). Backend bất kỳ đọc cùng DB nên không bắt buộc sticky session cho cơ chế này. Cart và draft phía trình duyệt không biến thành trạng thái trong RAM của một backend. OrderService lấy giá/stock có thẩm quyền trong DB, dùng row lock và transaction. FileStorage dùng chung root thật; khóa opaque trong DB giúp backend bất kỳ tìm cùng byte.

**Transaction** là nhóm thay đổi SQL commit hoặc rollback cùng nhau. **Idempotency** là nhận diện lại cùng ý định request bằng key và hash chuẩn hóa. Chúng khác nhau: transaction không ngăn client gửi lại tạo đơn mới nếu dùng key mới; idempotency không thay row lock bảo vệ stock. `Checkout.parse:18–21` đưa guest token, user ID và items theo thứ tự cố định vào hash, tránh khác thứ tự Map giữa JVM.

Không có transaction phân tán qua PostgreSQL và filesystem. `FileService.add:42–49` lưu byte trước, ghi metadata bằng SQL; khi commit bất định có thể giữ orphan để audit thay vì xóa nhầm file đã commit. Đây là giới hạn được thiết kế thận trọng, không được quảng cáo atomic DB+tệp.

### 7.3. /shop, /admin, /admin/shop và API

`StoreApplication.matches:34` nhận các đường UI. `handle:42` yêu cầu session/admin cho /admin và các đường con, **trừ /admin/shop và nhánh con**. `web/store/site.js:13` dùng BASE=/admin/shop cho shell cửa hàng. Không đọc tên đường dẫn rồi gán quyền.

Kết quả F, qua proxy và StoreApplication thật, JDBC trả session role fixture:

| Request | Vai trò | Kết quả |
|---|---|---|
| GET /shop | Anonymous | 200 |
| GET /admin | Anonymous | 303 tới /shop/login?next=admin |
| GET /admin/shop | Anonymous | 200, shell UI |
| GET /api/store/admin/dashboard | Anonymous | 401 |
| GET /admin | CUSTOMER | 403 |
| GET /api/store/admin/dashboard | CUSTOMER | 403 |
| POST /api/store/admin/categories, CSRF hợp lệ trong fixture | CUSTOMER | 403 |
| GET /api/store/admin/dashboard | ADMIN | 200 |

StoreController gọi SessionService.admin trước nhánh nghiệp vụ admin (`:40–52`), còn request ghi phải qua csrf (`:29–30`). **Không phát hiện bypass admin trong các ca trên.** F không kiểm chứng mật khẩu thật, persistence/revoke session, mọi URL encoding, mọi endpoint và mọi kiểu tấn công; không kết luận ứng dụng “an toàn tuyệt đối”.

### 7.4. Store có làm lệch trọng tâm không?

Source có phần lõi đủ độc lập; L xác nhận không cần Store để cân bằng tải. Vì vậy Store **không làm mất bản chất đề tài**. Tuy nhiên, thuyết trình chủ yếu catalog/giao diện/quản trị sẽ làm người nghe hiểu nhầm mục tiêu. Vai trò nghiên cứu của Store phải luôn gắn với phân phối, request có trạng thái, transaction, file correctness và failure isolation. Các endpoint /products, /orders tổng hợp ở WorkloadHandler không phải /api/store/products, /api/store/orders; không dùng đơn demo trong RAM làm bằng chứng PostgreSQL commit.


## 8. Các tính năng đã hoàn thành

Trong các ca được xác nhận, những nhóm sau có **code, đường gọi và kiểm chứng hành vi phù hợp**:

1. **Proxy HTTP/1.1:** nhận request, chuyển tiếp và trả status/header/body. J kiểm framing/chunk/EOF, HEAD/204/304 và đầu vào bất hợp lệ; L đi tới backend JVM thật.
2. **Ba chiến lược:** RR/LC/Adaptive được Dispatcher gọi khi request đến; thay strategy không làm mất lease đang chạy.
3. **Quản lý tài nguyên:** reserve/release, in-flight/task bounds, deadline/cancel/shutdown có test concurrency/lifecycle.
4. **Health/failure/recovery và circuit:** L kill node riêng, quan sát loại và phục hồi; J kiểm circuit bằng model và HTTP.
5. **Metrics/control:** JSON, Prometheus, request ID/log, decision trace, strategy/drain. CI Docker kiểm token và management bị chặn từ host bridge.
6. **Streaming opt-in:** single range/ETag/HEAD, hash, deadline/bulkhead/cleanup. CI đúng HEAD thử 32/100/256 MiB trước/sau restart với proxy heap 64 MiB.
7. **Đường Store quan trọng:** CI HEAD tạo session, tạo một đơn PostgreSQL, SQL stock -1, replay sau restart, ảnh/PDF đúng hash.
8. **Role gate API admin trong fixture:** F cho anonymous 401 / CUSTOMER 403 / ADMIN 200. Không nâng kết luận thành toàn bộ bảo mật hoặc identity lifecycle đã nghiệm thu.

“Hoàn thành” ở trên giới hạn theo ca thử, không có nghĩa mọi workload/mọi lỗi đã được bao phủ. Tính năng opt-in có test PASS vẫn không được giới thiệu như mặc định đang bật.

## 9. Các tính năng còn thiếu trong phạm vi — Nhóm B

Đây là thiếu coverage hoặc đáp ứng một phần mục tiêu, khác với lỗi đã tái hiện và không phải lệnh phát triển thêm.

| Mã | Nội dung | Thiếu ở mức nào | Ảnh hưởng |
|---|---|---|---|
| B-01 | Benchmark HEAD | Có harness/dữ liệu cũ; chưa có đối chứng đầy đủ mới gắn HEAD | Chưa bảo vệ tuyên bố Adaptive tốt nhất hiện tại |
| B-02 | Stock/transaction cạnh tranh | Source có lock/unique/hash; CI HEAD chỉ một đơn/replay | Chưa xác nhận lại oversell, nhiều key, rollback, commit bất định |
| B-03 | Dependency failure thật | Unit capability có; live DB/storage fault sâu là lịch sử | Không nói mọi ca fault đã tái chạy HEAD |
| B-04 | Login/logout/CSRF PostgreSQL | CI session thật, F role guard; chưa full identity lifecycle | Chưa nghiệm thu rotation/revoke đa node end-to-end |
| B-05 | Upload/CRUD/operations | Có source và H; CI HEAD chủ yếu seed/download | Chưa nghiệm thu toàn bộ website hiện tại |
| B-06 | Browser UI HEAD | API/HTML có, browser evidence cũ | Chưa xác nhận mọi nút/flow giao diện |
| B-07 | Mở rộng backend | Config danh sách, ba node đã chạy | Chưa đo >3 node hoặc scale throughput |
| B-08 | Physical LAN | Script ba vai trò/DB/SMB/preflight có | Chưa kiểm ba máy, RTT, firewall và SMB thật |
| B-09 | Windows vận hành | Lỗi Unicode tái hiện | CI chưa xanh, configure có thể báo lỗi sau khi ghi |
| B-10 | Trace một request cụ thể | Chỉ lastDecision, không gắn request ID trong AdaptiveDecision | Request khác chen vào có thể thay snapshot |
| B-11 | Độ bền/capacity | Smoke ngắn, không soak/capacity envelope HEAD | Không quy đổi thành số khách hàng/SLA |

B-02–B-06 chủ yếu thiếu **bằng chứng nghiệm thu trên đúng phiên bản**, không phải kết luận không có chức năng. Không bắt buộc bổ sung autoscaling, service discovery hoặc HA để lấp khoảng trống bằng chứng.

## 10. Các lỗi có bằng chứng — Nhóm A

### A-01. Configure LAN lỗi Unicode trên Windows dùng cp1252

- **Mô tả/điều kiện:** windows_nodes.configure ghi cấu hình rồi in thông báo tiếng Việt. stdout cp1252 không mã hóa được “Đ”, ném UnicodeEncodeError.
- **Source:** `deploy/lan/windows_nodes.py:67`, configure; ghi node.json `:116`, print `:117`. Test `scripts/ops/test_windows_nodes.py:23–27`.
- **Quan sát HEAD:** `python -B -m unittest discover -s scripts/ops -p 'test_*.py' -v`: **41 test, 40 PASS, 1 ERROR**. Trace qua test_windows_nodes.py:27 → windows_nodes.py:117 → encodings/cp1252.py:19.
- **CI HEAD:** job 136056428832 lỗi cùng test/exception; Java trước đó 32 main PASS, HTTP tooling Windows sau đó bị skip. Linux tools/config PASS.
- **Mức độ:** **Trung bình cho công cụ triển khai/demo Windows; thấp cho lõi forwarding đang chạy.**
- **Ảnh hưởng:** cả workflow FAIL; configure có thể đã tạo file dù báo lỗi, thử lại cùng directory gặp từ chối ghi đè. Không có bằng chứng làm sai thuật toán hoặc hỏng DB.
- **Tái hiện an toàn:** chạy unittest dùng directory/env fixture tạm, không chạy configure vào cấu hình thật.
- **Không sửa:** không đổi print, encoding hoặc workflow.

~~~text
File ".../scripts/ops/test_windows_nodes.py", line 27
    with patch.dict(os.environ,{},clear=True):lan.configure(args)
File ".../deploy/lan/windows_nodes.py", line 117
    print(...)
UnicodeEncodeError: 'charmap' codec can't encode character '\u0110'
in position 0: character maps to <undefined>
Ran 41 tests
FAILED (errors=1)
~~~

Ghi chú commit có nói lỗi archive-corruption cũ; audit **không xác nhận lỗi đó còn tái hiện**. Test_refuse_changed_archive PASS tại máy này và trong CI được đọc. Không dùng mô tả commit thay kết quả chạy.

### A-02. Origin Store bỏ qua If-None-Match wildcard trên asset tồn tại

- **Mô tả:** GET /store/assets/app.js trả asset; gửi `If-None-Match: *` vẫn trả 200 cùng nội dung.
- **Source:** `StoreApplication.handle:43–44` luôn Http.send(200,...); `store/controller/Http.java:21` không đánh giá điều kiện. `AssetCache.java:13,23` có etag/notModified nhưng không có caller trong main source. `ProxyHandler.java:306–310` chỉ thêm Cache-Control.
- **F tại HEAD:** HTTP/1.1 qua proxy/StoreApplication thật, JDBC giả phục vụ schema/session. GET 200; ETag ABSENT; **5.521 byte**, SHA-256 **faccce485394d39617f7c1b7c00ddac31b2b5c540e32a4e9ae197ce39ee89d2c**; wildcard GET 200. Cache-Control: public, no-cache, must-revalidate.
- **Căn cứ:** với tài nguyên hiện có mà GET bình thường trả 200, origin phải đánh giá If-None-Match; wildcard làm điều kiện false, GET/HEAD trả 304 theo [RFC 9110 `13.1.2](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.2). Intermediary chuyển điều kiện tới origin theo [`13.2.1](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.2.1).
- **Mức độ/ảnh hưởng:** **Thấp, giới hạn HTTP conditional của origin Store phụ trợ**; truyền lại byte. Không chứng minh hỏng đơn, bypass admin hoặc sai cân bằng tải.
- **Phạm vi:** **không phát ETag tự thân không bị kết luận là lỗi** vì README đã ghi không hỗ trợ. Phát hiện hẹp là wildcard precondition bị bỏ qua. Không biến phát hiện thành yêu cầu xây cache HTTP tổng quát hoặc điều kiện bắt buộc để lõi được công nhận.
- **Tái hiện an toàn:** fixture không DB thật, hoặc GET read-only tới asset trong môi trường được xác nhận.
- **Không sửa:** không nối helper vào route hay đổi header.

Tuyến `/api/pbl4/files/*` xử lý preconditions riêng tại LargeFileHandler:70–83, có J/CI riêng; không lẫn hai tuyến.

### A-03. Tài liệu gọi sai tên method AssetCache

`docs/deployment/OPERATIONS_UPGRADE.md:8` nhắc `AssetCache.conditional`; lớp hiện chỉ có etag và notModified (`AssetCache.java:13,23`). **Mức thấp, lỗi tham chiếu tài liệu** làm tìm nhầm symbol. Nội dung chính “route không gọi helper” vẫn đúng. Tái hiện bằng đọc/tìm symbol; không sửa tài liệu.

### Các điểm không đủ căn cứ gán là lỗi

| Vấn đề | Kết luận |
|---|---|
| /admin/shop anonymous200 | Shell UI công khai có chủ đích trong code; API admin chặn ở F, chưa thấy bypass |
| Adaptive dồn request một node | Phù hợp scoring, không phải lỗi chỉ vì không chia đều |
| Không replay POST sau timeout | Chính sách thận trọng trước commit bất định |
| Orphan khi commit file/DB bất định | Giữ để audit, không tự kết luận mất dữ liệu |
| Không WebSocket/HTTP2/CONNECT | Giới hạn được định nghĩa, không lỗi core |
| Thiếu benchmark HEAD/LAN | Thiếu bằng chứng, không phải hành vi sai đã tái hiện |
| Evidence cũ Store 304 | Snapshot khác; không là test PASS HEAD |

## 11. Những nội dung ngoài phạm vi — Nhóm C

Không đưa các mục sau vào danh sách bắt buộc hoàn thành:

- Thanh toán ngân hàng, voucher, vận chuyển, hoàn tiền, tích điểm, chat, gợi ý, ERP hay hệ thống thương mại đầy đủ.
- Thêm mẫu sản phẩm, trang trí storefront, thêm thống kê doanh thu không phục vụ thí nghiệm.
- Mọi tính năng HTTP nâng cao, HTTP/2–3, WebSocket, CONNECT hay tương đương đầy đủ sản phẩm proxy chuyên dụng.
- Kubernetes, cloud autoscaling, service mesh, discovery động, nhiều vùng, CDN.
- Proxy HA, PostgreSQL replication/failover, đồng thuận, distributed transaction DB–filesystem. Đây là giới hạn phải thừa nhận, chưa là mục tiêu hiện tại.
- Chứng nhận bảo mật, chống DDoS Internet, SLA production hoặc benchmark cạnh tranh Nginx/HAProxy.
- Mở control API ra LAN hoặc tắt CSRF để tiện demo.
- Refactor/tối ưu/dọn dữ liệu để làm audit trông tốt hơn.

LAN đã có trong hướng triển khai nên phải ghi đúng trạng thái. Có thể giới hạn tuyên bố thay vì bổ sung tính năng để hợp thức hóa một tuyên bố quá rộng.

## 12. Kết quả kiểm thử và giới hạn bằng chứng

### 12.1. Cô lập và nơi giữ log

Không gọi build.ps1 (có Remove-Item build outputs), run.ps1 start/stop (có nhánh init/stop DB thật), các script live DB/backup/cleanup chưa được xác nhận độc lập.

Biên dịch main/test bằng `javac --release 21 -encoding UTF-8` tới directory riêng. Chép test source để TestSuite tìm file theo relative path; resources đọc trực tiếp từ repository. TestSuite chạy từ nơi riêng nên không ghi đè target hiện có.

~~~text
C:\Users\admin\AppData\Local\Temp\
pbl4-audit-20261122-bc333f108c1b43939baa3375cdc02b8d\
  classes\
  src\test\java\
  target\test-reports\
  core-live\results.json
  core-live\proxy.properties
  core-live\*.log
  integration\results.json
  audit_core.py
  audit_integration.py
  AuditStoreFixture.java
  fixture-classes\
  store-fixture-workspace.log
~~~

L dùng backend port **49312/49313/49314**, proxy **49315**; PID backend ban đầu **16144/15916/26028**, proxy **19744**. Kết thúc chỉ child Popen đã tạo; receipt `children_stopped=true`. Không dừng phiên thật theo port/tên.

F dùng JDBC giả với HTTP/proxy/StoreApplication thật. Canonical-path truy cập OS temp bị AccessDenied trong sandbox, nên dùng **storage thử rỗng** `D:\PBL4\audit-temp-20261122-bc333f10\store`, ngoài checkout. Không upload/tạo file nghiệp vụ vào đó. Role/session do fixture cấp để kiểm guard, không được gọi là đăng nhập PostgreSQL thật.

PostgreSQL mới có data root trong temp/integration, nhưng initdb bị WinError 4551 **trước khi DB được khởi tạo/chạy**. Không có kết quả giao dịch local để công bố. Các lỗi compile/HTTP2 mặc định lúc dựng harness chỉ thuộc harness ngoài checkout; kết quả cuối F dùng HTTP/1.1, không tính chúng là lỗi source ứng dụng.

### 12.2. Java: 32 test main PASS

TestSuite.main (`:9–53`) tìm *Test.java, mỗi lớp một JVM, -ea, timeout 60 s, XML một testcase/main. POM tắt Surefire (`:35`) nhưng antrun phase test gọi TestSuite (`:43–49`); **không phải Maven bỏ mọi test**.

| Nhóm | Test main đã chạy, đều PASS |
|---|---|
| Lựa chọn/model | AdaptiveDecisionTest, DispatcherTest, SchedulerParityTest, loadbalancer.EstimatedWorkTest |
| Health/circuit/capability | HealthCheckerTest, CircuitBreakerTest, CircuitBreakerHttpTest, CapabilityRoutingTest, CapabilityJsonStrictTest |
| Protocol | HttpTransportTest, HttpIntermediaryTest, ProxyContractTest, ChunkMetadataTest |
| Resource/lifecycle | ConcurrentLifecycleTest, LifecycleTest, ListenerResourceTest, RequestDeadlineTest, HardeningTest |
| Admission/control | PriorityAdmissionTest, PriorityAdmissionSecurityTest, RateLimiterTest, ManagementListenerTest, handler.DownloadBulkheadTest |
| Pool | UpstreamPoolingTest, UpstreamPoolingFailureTest, UpstreamPoolingAdversarialTest, DatabasePoolTest |
| File/cache/metrics | StreamingProxyTest, RangeRequestTest, AssetCacheTest, PdfLabSecurityTest, TrafficOutcomeTest |

~~~text
Passed 32 test mains; XML reports: target\test-reports
AUDIT_SUITE_EXIT=0
~~~

DatabasePoolTest deregister driver và dùng DriverStub (`:56–61`), không chứng minh PostgreSQL/OrderService transaction thật. AssetCacheTest không chứng minh route dùng helper.

### 12.3. Core live: 11 kiểm tra hữu hạn

| Ca L tại HEAD | Quan sát | Kết luận |
|---|---|---|
| Ba JVM backend + proxy | Mỗi node health/metrics, state UP | PASS |
| RR 12 tuần tự | 200: 12, 4/4/4 | PASS |
| LC 12 tuần tự | 200: 12, 4/4/4 | PASS; ranking có J riêng |
| Adaptive 12 tuần tự | 200: 12, cả12 vào port 49313 | PASS selection, chưa thử tối ưu |
| 96 request/concurrency 16 | 200: 96 | PASS smoke, không capacity benchmark |
| GET /echo?audit=one | 200 / body rỗng đúng GET body rỗng | PASS; chi tiết body forwarding do J |
| /download?sizeKiB=64 | 200,65.536 byte; SHA 4b640d85ab3ba30fd02c9fc9db4a8928f416322ad27022ea58a65aaee68a4df2 | PASS synthetic file |
| Dừng backend đầu | DOWN; 15 request200 qua port 49314 | PASS sau phát hiện, chưa đo đúng thời điểm kill |
| Khởi động lại node | UP, recoveries=1 | PASS |
| Backend delay 4.000 ms | 504,~3,000giây | PASS timeout trong ca này |
| Dừng hết backend | 503 | PASS |

Traffic cuối: **152 started/completed, 150 successful, 2 failed**; TIMEOUT=1 và PROXY_REJECTED=1 được chèn chủ động; active=0. Không gọi đây là tỷ lệ lỗi vận hành thực.

### 12.4. Python và CI đúng phiên bản

| Phạm vi | Nơi/ngày | Kết quả tại HEAD |
|---|---|---|
| Operations unittest | Windows local 22/11 | 41 test:40 PASS, 1 ERROR A-01 |
| test_defense_live.py | Windows local 22/11 | PASS mock compare/drain/evidence/report/restoration |
| Java Maven | CI Linux 20/11 | Job success |
| Java standalone | CI Windows 20/11 | 32 main PASS; job fail ở Python sau đó |
| Python tools/operations/Compose config | CI Linux 20/11 | tools-and-config success |
| Docker real stack | CI Linux 20/11 | docker-live success,68 checkpoint PASS |
| Docker mới local | Windows audit | CHƯA KIỂM THỬ; Docker không có trong PATH |
| PostgreSQL mới local | Windows audit | CHƯA KIỂM THỬ; Application Control chặn initdb |
| LAN ba máy vật lý | Không có môi trường phù hợp | CHƯA KIỂM THỬ |

Test defense_live dùng **MockProxy Python** dù header ghi tên9001/9002/9003; không dùng nó chứng minh ba JVM Java chạy. L mới là bằng chứng đó.

CI Docker log có build/start/ba node, management loopback và token sai/thiếu, strategy/drain/resume, checkout SQL/stock, ảnh/PDF SHA, restart PG/backend/proxy, same-key replay, volume persistence, streaming 32/100/256 MiB full/resume trước/sau restart, active/openBodies/bulkhead về0, snapshot/watchdog. **68 là checkpoint có cả setup/copy**, không phải68 testcase nghiệp vụ độc lập.

### 12.5. Đủ bằng chứng để bảo vệ từng yêu cầu chưa?

| Yêu cầu | Evidence hiện tại | Giới hạn lời trình bày |
|---|---|---|
| Chạy ba backend | L JVM,CI container | Đủ một host, chưa ba máy |
| Đổi ba thuật toán | L/J;CI control | Đủ cơ chế/contract |
| Phân phối HTTP | Header L,dispatch J | Đủ phân phối, chưa tối ưu |
| Một backend dừng | L kill/restart,J lỗi | Đủ ca đã chạy, không zero-loss |
| Tải đồng thời | L 96/concurrency 16,J concurrency | Đủ smoke/invariant, chưa capacity |
| Timeout | L504,J deadlines | Đủ ca; sau headers có giới hạn |
| Mộc Store | CI order/catalog/file,F route | Đủ workload, chưa toàn bộ UI/CRUD |
| PostgreSQL transaction | CI đơn/stock/replay/restart | Đủ luồng thật; chưa full concurrency/fault HEAD |
| ADMIN API | F CUSTOMER403/source | Đủ guard fixture; chưa auth PG end-to-end |
| PDF | CI Store PDF đúngSHA | Đủ download; file streaming .bin không được gọi PDF thật |
| UploadPDF | Source/H | CHƯA ĐỦ BẰNG CHỨNG HEAD live |
| Physical LAN | Config/unit/hướng dẫn | CHƯA ĐỦ BẰNG CHỨNG |

### 12.6. Đối chiếu evidence lịch sử và report

1. `docs/evidence/unified/operational-result.json` ghi PASS Store ETag/304. F/source HEAD trả200 khôngETag: **khác snapshot**, không trích thành kết quả hiện tại.
2. So bảng source SHA trong `unified/browser-result.json` với HEAD: **65 file khớp,38 khác,7 đường dẫn không còn**. Chỉ kết luận không cùng snapshot, không coi mọi khác biệt là lỗi.
3. Đọc **17 environment JSON** trong `D:\PBL4\evidence\benchmarks`: không bộ nào khớp toàn bộ source fingerprint hiện tại. environment.json ngày15/11 có111file,30file khác HEAD. Không chuyển p95/RPS cũ thành benchmark HEAD.
4. `docs/evidence/core-streaming/benchmark.json` chỉ tóm tắt18runs×180requests,2profiles,3repeats/strategy/profile; chưa tự gắn HEAD. Khác experiment108runs×240 của assemble_research; không ghép hai bộ.
5. `web/benchmark-comparison.html:1` ghi số đo06/11/2026,loopback một máy; đây là dữ liệu lưu, không realtime.
6. README:109–110 hiện đã phân biệt Store khôngETag với streaming cóETag. Không cáo buộc README hứa Store 304.
7. `D:\PBL4\reports\DEFENSE_GUIDE.md` tự ghi dựa c18c758 và thay đổi18/11. REVIEW.md là review Word cũ; audit này không nghiệm thu lại DOCX hiện đang mở.
8. core-streaming/memory.json phân biệt RSS/heap. Số liệu đó là lịch sử, không là đo RAM mới hay bằng chứng leak HEAD.

Các câu “ba máy đã nghiệm thu”, “Store assets hiện304”, “toàn CI xanh”, “mọi test Windows pass”, “Adaptive vượt trội hiện tại”, “stress transaction HEAD đã nghiệm thu” vượt evidence. Ngược lại, “Docker chưa được thử ở HEAD” cũng sai vì có CI đúng SHA.

### 12.7. Cách đọc metrics/benchmark

- p95 là ngưỡng khoảng95% mẫu không vượt; phải ghi nhóm mẫu/cửa sổ.
- TrafficMetrics có ring 2.048; p95 từ20mẫu,p99 từ100mẫu, nearest-rank (`TrafficMetrics.java:104`). Không là lịch sử latency bền vững.
- Traffic data loại management; requests_total handler có thể gồm management. Không so như cùng mẫu.
- Socket transport không gồm mọi socket OS/health/streaming/idle; byte Java I/O không phải byte NIC.
- RTT/first-byte gồm nhiều thành phần; chưa tách riêng CPU/DB lock/queue bằng một counter đó.
- p95 mọi outcome có thể thấp vì503 nhanh. Cần xem status/success rate/success RPS/success-only latency.
- RPS delta và latency ring không nhất thiết cùng time window. Không suy bottleneck từ screenshot hoặc chọn thuật toán tốt nhất bằng12 request.


## 13. Hướng dẫn hiểu dự án từ đầu đến cuối

### 13.1. Vấn đề thực tế xuất hiện như thế nào?

Một người dùng mở trang sản phẩm, người khác đăng nhập, người khác đặt đơn, người khác tải PDF. Đó đều là HTTP request nhưng chi phí khác nhau. Đọc tệp, hash mật khẩu, chờ khóa SQL hoặc tính toán CPU đều chiếm thời gian và tài nguyên. Khi nhiều request cùng đợi, request mới phải chờ lâu hơn hoặc bị từ chối.

Trong dự án, `WorkloadHandler.handle` tạo các loại tải có kiểm soát: /work, /slow, /compute, /products, /orders, /download. Store tạo công việc ứng dụng thật qua /api/store. Hai nhóm bổ sung cho nhau: tải tổng hợp giúp biết biến nào được điều khiển; Store giúp kiểm chứng dữ liệu dùng chung.

### 13.2. Nếu chỉ có một backend thì sao?

Mọi request dồn về cùng giới hạn worker và dependency. **Ví dụ GIẢ ĐỊNH, không phải benchmark:** một backend có 8 suất xử lý, mỗi việc mất 100 ms, bỏ qua mọi overhead thì mức lý tưởng là 8/0,1 = 80 việc/giây. Khi có 20 việc cùng đến, chỉ 8 việc làm ngay; các việc còn lại phải chờ hoặc bị chặn. Không được gọi 80 là công suất thực của dự án.

`BackendApplicationServer` có worker semaphore theo capacity và RequestAdmission giới hạn cả active lẫn queued. Vì vậy kể cả virtual thread rất rẻ, request vẫn không được nhận vô hạn. Nếu backend duy nhất chết, không còn nơi thực hiện công việc.

### 13.3. Thêm ba backend mang lại gì?

Proxy có thể đưa việc tới A, B, C. **Ví dụ GIẢ ĐỊNH:** mỗi node còn 8 suất và độc lập mọi tài nguyên thì có thể có 24 công việc làm đồng thời; nếu vẫn chung DB chậm thì DB có thể thành nút thắt trước khi dùng hết 24 suất. Trên một máy, ba JVM vẫn phải chia CPU/RAM; con số 24 không phải bằng chứng throughput gấp ba.

L đã chạy ba JVM thật ở ba port. Điều đó chứng minh request có thể được nhiều tiến trình phục vụ, không chứng minh máy vật lý khác nhau hoặc host failure tolerance.

### 13.4. Reverse proxy đứng ở đâu và làm gì?

Client chỉ biết một origin. Proxy nhận request, kiểm metadata và giới hạn, chọn backend, gửi request, đọc response, trả lại cho client. Client không cần tự tìm node còn sống.

`ReverseProxyServer` mở listener; `ProxyHandler` quản lý request; `Dispatcher` chọn backend; `UpstreamTransport` thực hiện mạng upstream. Đây là bốn trách nhiệm khác nhau. Dispatcher không tự gửi HTTP; backend được chọn không có nghĩa response đã tới client.

### 13.5. Hiểu từng thuật toán bằng một ví dụ

**RR:** nếu A/B/C đều hợp lệ, chọn lần lượt A → B → C → A. Nếu B không hợp lệ, thuật toán làm việc với tập candidate còn lại. Code: `RoundRobinLoadBalancer.select`, một sequence modulo kích thước danh sách.

**LC:** **ví dụ GIẢ ĐỊNH** A có 4 lease, B có 1, C có 2; B được ưu tiên. Request kết thúc sẽ giảm lease. Điều đang đếm là việc chưa hoàn tất phía upstream, không phải tất cả kết nối TCP. Code: `LeastConnectionsLoadBalancer.select` và `Dispatcher.Lease`.

**Adaptive:** **ví dụ GIẢ ĐỊNH** A phục vụ mới mất 40 ms nhưng phải đợi 2.000 ms, ECT=2.040 ms; B mất 100 ms và không đợi, ECT=100 ms. Nếu penalty bằng nhau, B tốt hơn theo mô hình. CPU/memory/error/telemetry cũ có thể tăng penalty. Code: `EstimatedWork.forecast` → `AdaptiveCandidateScorer.score` → `AdaptiveLoadBalancer.select`. Điểm thấp là dự đoán, không phải thời gian chắc chắn.

EWMA nghĩa là trung bình có trọng số, mẫu mới thay một phần ước lượng cũ. **Ví dụ GIẢ ĐỊNH:** estimate 100 ms, mẫu mới 200 ms, alpha 0,2 → 120 ms. Giá trị này không biến hệ thống thành AI và không đảm bảo dự báo chính xác.

### 13.6. Một request đi qua hệ thống như thế nào?

Xét GET /work?cost=1:

1. HttpServer nhận kết nối; executor giữ slot và deadline.
2. ProxyHandler gán request ID, admission, kiểm Host/HTTP/body/rate policy.
3. Dispatcher lọc node rồi giữ lease atomically.
4. UpstreamTransport mở/tái dùng socket theo config và gửi HTTP/1.1.
5. BackendApplicationServer giữ admission/worker rồi gọi WorkloadHandler.
6. Backend tạo response; transport đọc response.
7. Proxy hoàn tất lease, cập nhật backend metrics rồi ghi response tới client.
8. Cuối request trả ticket, cập nhật traffic/log.

Có thể tự trình bày theo request ID, X-Proxy-Backend và X-Proxy-Strategy. Không cần kể mọi class phụ ngay từ đầu. GET /api/store/products có sáu bước mạng tương tự, nhưng ở backend đi vào StoreController/CatalogRepository và PostgreSQL.

### 13.7. Khi một backend chết thì sao?

Có hai đường phát hiện: health probe thất bại, hoặc request gặp transport failure/timeout. Backend bị loại khỏi candidate; request mới có thể tới node khác. Khi health success đủ lần, node trở lại và warmup tăng capacity hữu hiệu dần.

Request đang chạy đúng lúc node chết có thể trả 502/504 hoặc bị ngắt nếu đã bắt đầu response. **Không hứa tất cả request đều được cứu.** Nếu tất cả node không hợp lệ, proxy trả 503. Circuit breaker hạn chế đưa thêm việc sau chuỗi lỗi; nó không phục hồi dữ liệu hoặc tự tạo response thành công.

L thử loại node sau khi kill rồi request tiếp, phục hồi, timeout và hết node. J thử thêm tình huống protocol và tài nguyên. Chưa có số đo định lượng zero-loss/failover SLA.

### 13.8. Khi đặt hàng, request và dữ liệu đi đâu?

Trình duyệt gửi POST /api/store/orders với cookie, CSRF, Idempotency-Key và body. Proxy kiểm request, Dispatcher chọn node có order_create capability. Backend → StoreController → Checkout.parse → OrderService.create → Sql.transaction → Database.with → PostgreSQL.

Trong transaction: thử tạo idempotency row, kiểm replay/hash, kiểm cửa hàng cho bán, khóa variant/product theo thứ tự, đọc giá/stock thực, kiểm tồn, ghi order items, trừ kho, cập nhật tổng và lịch sử, commit. Nếu lỗi trước commit, transaction rollback. Response JSON quay qua proxy về client.

Không tin giá tổng từ trình duyệt. Không dùng một biến stock trong RAM backend để đồng bộ ba JVM. Khi response bị mất, cùng key/body giúp tìm lại đơn đã commit. Đổi key có nghĩa ý định khác, vẫn có thể tạo đơn khác. CI HEAD chứng minh một đơn và replay sau restart; concurrency toàn diện chưa được audit chạy lại với PostgreSQL.

### 13.9. Vì sao dùng chung PostgreSQL và shared storage?

Nếu mỗi JVM có DB riêng, một node có thể thấy đã trừ kho còn node khác chưa thấy; session có thể chỉ được một node nhận. Session/stock/order dùng chung PostgreSQL giúp mọi node hỏi cùng trạng thái có thẩm quyền.

Nếu byte ảnh/PDF chỉ nằm ổ riêng backend A, request tiếp theo tới B có thể không tìm được. Shared storage giữ cùng tệp, DB giữ metadata và checksum. Trên một máy là cùng root; LAN phải là cùng share thật. Ba folder cùng tên trên ba máy không phải shared storage.

DB/filesystem là hai hệ thống khác nhau: SQL commit không tự commit file. Vì thế phải thừa nhận orphan/commit bất định và audit bảo toàn, không nói transaction đã bao phủ mọi byte trên đĩa.

### 13.10. Dashboard đang đo gì?

Dashboard là một client đọc API management; **không phải bộ điều phối**. Đóng dashboard không làm thuật toán ngừng chạy.

Nó hiển thị strategy, backend state, lease/in-flight, dispatch/completion/error, latency EWMA, CPU/heap ratio, telemetry freshness, circuit/capability và last Adaptive decision. Phần traffic có started/completed/success/failed, outcome, p50/p95/p99 theo mẫu ring. Transport có socket/byte/connect/first-byte trong phạm vi riêng.

Một backend có state UP vẫn có thể không phục vụ order nếu mất DB/capability. Một request backend xử lý xong vẫn có thể không đến đủ client. Một snapshot “node A điểm thấp nhất” không chứng minh nút gửi thử vừa tới A, vì trace chỉ giữ quyết định cuối và request khác có thể chen vào.

### 13.11. Benchmark thực hiện thế nào và chứng minh gì?

`defense_live.py` đọc strategy, đổi có kiểm chứng, chạy warmup, gửi request bằng concurrency hữu hạn, lưu raw latency/status/backend/strategy, tổng hợp và phục hồi trạng thái. `research.py` có kịch bản đồng nhất/không đồng nhất/chậm/mixed/overload; `assemble_research.py` yêu cầu fingerprint Java/resources trùng khi ghép dữ liệu. `heterogeneous_benchmark.py` tự ghi label strategy chưa xác minh; không tự tin nhãn truyền từ CLI.

Một đối chứng có ý nghĩa cần cùng source/profile/workload/request count/concurrency và điều kiện phần cứng; nhiều lượt lặp; lưu warmup, HTTP errors, timeout, distribution và success RPS. Cần tách p95 mọi outcome với p95 thành công. **Ví dụ GIẢ ĐỊNH:** thuật toán trả nhanh 503 cho nửa request có p95 nhỏ hơn vẫn có thể phục vụ kém hơn thuật toán thành công đầy đủ.

Hệ thống có khả năng tổ chức thí nghiệm như vậy; audit này không thực hiện lại đầy đủ benchmark HEAD. Mock Python không phải benchmark Java; L 96 request chỉ là smoke. Các biểu đồ cũ cần gắn đúng snapshot.

### 13.12. Kết quả có ý nghĩa gì?

Kết quả có ý nghĩa nhất là đường HTTP và lựa chọn thực sự hoạt động, resource invariant được kiểm, backend lỗi không luôn kéo cả proxy dừng, và workload dùng chung có thể giữ đơn/tệp qua restart trong CI. Đây là bằng chứng hiện thực và tính đúng theo phạm vi.

Kết quả không cho phép suy hiệu năng vượt trội, dùng được production hoặc không có lỗi. Giá trị nghiên cứu nằm cả ở việc giải thích đúng giới hạn và nguyên nhân đánh đổi giữa chia lượt, chọn theo tải hiện tại và dự báo.

### 13.13. Khác website bán hàng Java thông thường ở đâu?

Website thông thường tập trung nghiệp vụ: người dùng/sản phẩm/đơn. Dự án này có thêm **một tầng trung gian do nhóm hiện thực**, với parser/transport HTTP, scheduler và state concurrency, health/circuit, deadline/admission, metrics và bộ thử.

Một đơn bán hàng không tự chứng minh load balancing. Ngược lại, L chạy với Store tắt vẫn chứng minh lõi của đề tài. Store có vai trò minh họa request có trạng thái và dependency chứ không thay thế nội dung thuật toán.

### 13.14. Vì sao đánh giá là Reverse Proxy & Load Balancer?

Có thể chỉ ra trực tiếp ProxyHandler → Dispatcher → RR/LC/Adaptive → transport → backend và thực nghiệm thay strategy/kill node/timeout. Đây là đối tượng nghiên cứu phù hợp tên đề tài.

Khi bảo vệ, trình bày bài toán, đường request, thuật toán, giới hạn và evidence trước; dùng một luồng Store làm ví dụ ứng dụng. Nếu dành phần lớn thời gian cho mẫu điện thoại/giỏ hàng/CRUD, người nghe sẽ không thấy phần kỹ thuật chính dù source có nó.

### 13.15. Bản giới thiệu khoảng ba phút

> Đề tài của nhóm là xây dựng một Reverse Proxy kết hợp Load Balancer bằng Java 21. Bài toán là khi nhiều người cùng dùng ứng dụng, một backend có thể bị đầy hàng chờ hoặc gặp sự cố. Client cần một địa chỉ truy cập thống nhất, còn hệ thống cần chọn nơi xử lý phù hợp.
>
> Trong kiến trúc của nhóm, request đi vào Proxy, được kiểm tra giao thức và giới hạn tài nguyên, sau đó Dispatcher chọn một backend hợp lệ. Có ba chiến lược: Round Robin chia lượt; Least Connections chọn ít request đang xử lý nhất; Adaptive dự báo thời gian hoàn tất dựa trên công việc và tín hiệu tài nguyên. Proxy chuyển request tới backend rồi trả response về client.
>
> Hệ thống còn có health check, circuit breaker, timeout và admission control. Khi một backend dừng, node đó được loại khỏi lựa chọn; request đang lỗi vẫn có thể thất bại, nên nhóm không tuyên bố chuyển lỗi hoàn toàn không gián đoạn. Khi không còn backend phù hợp, Proxy trả 503.
>
> Mộc Store được dùng làm ứng dụng thí nghiệm có trạng thái. Catalog tạo tải đọc, đặt hàng tạo giao dịch ghi, ảnh/PDF tạo tải tệp. Các backend dùng chung PostgreSQL và storage để request tới node khác vẫn dùng cùng dữ liệu. Giao dịch SQL và idempotency phục vụ tính đúng của đơn hàng; chúng không phải thuật toán cân bằng tải.
>
> Ở commit được kiểm toán, 32 test main Java chạy lại đều pass. Phiên thử riêng đã chạy ba JVM backend, đổi ba thuật toán, xử lý tải đồng thời hữu hạn, dừng và phục hồi backend, nhận 504 khi timeout và 503 khi hết node. CI đúng commit có Docker live, SQL xác nhận đơn/stock, replay sau restart và file đúng checksum.
>
> Giới hạn hiện tại là chưa nghiệm thu ba máy vật lý, chưa có benchmark đối chứng đầy đủ mới trên HEAD và công cụ Windows còn lỗi Unicode. Một Proxy, DB và storage vẫn là điểm lỗi đơn. Nhóm vì vậy trình bày đây là hệ thống thực nghiệm về proxy và cân bằng tải, không tuyên bố nền tảng production hay Adaptive luôn nhanh nhất.

Đây là lời giới thiệu dựa vào evidence hiện tại; khi demo thật phải gọi rõ profile và môi trường đang dùng.

## 14. Nội dung dùng cho báo cáo học thuật

### 14.1. Phát biểu bài toán

Trong các ứng dụng HTTP, giới hạn năng lực xử lý và thời gian phục vụ không đồng nhất của backend có thể dẫn đến tăng độ trễ, tích lũy hàng chờ hoặc gián đoạn khi tiến trình lỗi. Đề tài nghiên cứu cơ chế reverse proxy kết hợp cân bằng tải tầng ứng dụng nhằm cung cấp điểm truy cập thống nhất, phân phối yêu cầu tới backend có khả năng phục vụ, kiểm soát tài nguyên và quan sát hành vi trong các điều kiện tải và lỗi xác định.

### 14.2. Mục tiêu đề tài

Đề tài hướng tới hiện thực đường chuyển tiếp HTTP/1.1, xây dựng và giải thích ba chiến lược Round Robin, Least Connections và Adaptive, kết hợp giám sát sức khỏe, giới hạn đồng thời, timeout và xử lý lỗi. Việc đánh giá xem xét tính đúng đắn của chuyển tiếp, lựa chọn backend và quản lý tài nguyên; ứng dụng Mộc Store được sử dụng để minh họa tải có trạng thái và sử dụng dữ liệu, tệp dùng chung.

### 14.3. Phạm vi nghiên cứu

Phạm vi thực nghiệm đã xác nhận gồm ba backend trên một host, các fixture Java và một stack Docker trên runner Linux. Hệ thống hỗ trợ các phương thức và chính sách HTTP/1.1 được hiện thực; tuyến file streaming là tính năng bật riêng. Triển khai LAN ba máy được mô tả ở mức cấu hình/hướng dẫn nhưng chưa có kết quả nghiệm thu vật lý. Các chức năng thương mại điện tử nâng cao, tính sẵn sàng cao của toàn hệ thống và giao thức nâng cao ngoài contract không thuộc mục tiêu bắt buộc.

### 14.4. Kết quả triển khai thực tế

Source được kiểm tra tại commit 32cbd9f879770269783ea69a2687d054636fd861 thể hiện đường gọi hoàn chỉnh từ listener qua ProxyHandler, Dispatcher và transport tới backend. Các thuật toán sử dụng tập backend đủ điều kiện chung, việc lựa chọn và giữ chỗ được đồng bộ bằng lease. Hệ thống có health/capability, circuit breaker, deadline/admission và metrics. Store sử dụng PostgreSQL, session dùng chung, giao dịch đặt hàng có idempotency và tệp kiểm checksum. Một số khả năng như pooling, rate limit, QoS và streaming chỉ có hiệu lực khi bật profile tương ứng.

### 14.5. Ý nghĩa kỹ thuật

Kết quả triển khai cho thấy sự liên hệ giữa thuật toán lựa chọn backend và các vấn đề giao thức, cạnh tranh tài nguyên, lỗi từng thành phần và tính đúng của workload có trạng thái. Việc giữ nguyên điều kiện eligibility cho nhiều chiến lược giúp hình thành cơ sở so sánh. Workload ứng dụng và workload tổng hợp cho phép tiếp cận hai khía cạnh bổ sung: kiểm soát điều kiện thử và phản ánh dependency của ứng dụng.

### 14.6. Kết quả kiểm thử được xác nhận

Tại ngày 22/11/2026, source hiện tại được biên dịch bằng JDK 21 và 32 chương trình kiểm thử Java chạy thành công trong môi trường riêng. Thử nghiệm core với ba JVM backend xác nhận thay đổi chiến lược, hoàn thành 96 request đồng thời hữu hạn với concurrency 16, loại backend bị dừng, phục hồi node và trả mã lỗi phù hợp trong các ca timeout/hết backend. Đây là kiểm tra chức năng và tài nguyên, chưa xác định năng lực phục vụ tối đa.

CI cùng commit có job Docker live thành công, bao gồm giao dịch đơn hàng PostgreSQL được kiểm bằng SQL, replay sau restart và xác minh checksum tệp. Workflow tổng thể vẫn thất bại vì lỗi Unicode trong test công cụ cấu hình Windows. Kiểm thử giao dịch PostgreSQL mới tại máy audit không thực hiện được do chính sách hệ điều hành chặn initdb. Các kết quả trên phải được trình bày đúng phạm vi môi trường.

### 14.7. Hạn chế

Chưa có bằng chứng triển khai và đo trên ba máy vật lý, chưa lặp lại đầy đủ benchmark đối chứng tại HEAD, và chưa bao phủ toàn bộ nghiệp vụ Store với dependency thật ở phiên bản hiện tại. Các thành phần Proxy, PostgreSQL và shared storage còn là điểm lỗi đơn. Store assets không phát ETag và bỏ qua wildcard precondition trong ca thử; công cụ Windows có lỗi output encoding. Bằng chứng lịch sử có source fingerprint khác nên không được sử dụng như nghiệm thu toàn bộ HEAD.

### 14.8. Mức độ đáp ứng mục tiêu

Các mục tiêu hiện thực lõi và chứng minh hành vi proxy/cân bằng tải trong môi trường thử xác định đã có cơ sở. Mục tiêu đánh giá hiệu năng tương đối hiện tại, độ đầy đủ kiểm thử dependency/transaction và triển khai physical LAN mới đáp ứng một phần hoặc chưa đủ bằng chứng. Không lượng hóa mức hoàn thành bằng phần trăm khi chưa có bộ tiêu chí có trọng số được thống nhất.

### 14.9. Kết luận chung

Dự án có cơ sở được đánh giá là một hệ thống thực nghiệm Reverse Proxy & Load Balancer. Giá trị đã được xác nhận nằm ở hiện thực và kiểm chứng đường HTTP, chính sách lựa chọn, quản lý tài nguyên và các kịch bản lỗi, cùng một số luồng ứng dụng có trạng thái. Kết luận này không đồng nghĩa hệ thống đạt mức triển khai production hoặc có ưu thế hiệu năng được chứng minh trên mọi điều kiện. Những giới hạn và nội dung chưa kiểm thử cần được giữ trong báo cáo và lời trình bày bảo vệ.

## 15. Bộ câu hỏi và trả lời bảo vệ

**1. Tại sao chọn đề tài này?**  
Bài toán là phân phối HTTP và xử lý giới hạn/lỗi backend qua một địa chỉ thống nhất. Động cơ thực tế của nhóm cần tự trình bày, nhưng nội dung kỹ thuật source chứng minh là ProxyHandler, Dispatcher và ba thuật toán; không chỉ xây storefront.

**2. Reverse Proxy khác Load Balancer thế nào?**  
Reverse proxy nhận và chuyển tiếp request/response. Load balancer chọn backend nào. Ở đây ProxyHandler/UpstreamTransport đảm trách chuyển tiếp, Dispatcher cùng LoadBalancer đảm trách chọn.

**3. Vì sao Java 21?**  
POM đặt release21, code dùng virtual thread và API Java21 như getFirst. Virtual thread giúp mô hình blocking I/O dễ tổ chức; vẫn cần admission bounds. Audit không có benchmark chứng minh Java21 nhanh hơn mọi lựa chọn khác.

**4. Vì sao ba backend?**  
Ba node đủ minh họa chia lượt, chênh tải, mất một node còn hai node và phục hồi. Đó là quy mô thử nghiệm, không số lượng tối ưu hay ba máy độc lập. L đã xác minh ba JVM.

**5. Có thực sự cân bằng tải hay chỉ forward?**  
Dispatcher gọi thuật toán trên mỗi acquire đủ điều kiện. L đổi strategy và RR/LC phân phối 12 request mỗi loại thành4–4–4; J có assertions lựa chọn/concurrency. Đó là behavior, không chỉ tên class.

**6. RR được hiện thực thế nào?**  
RoundRobinLoadBalancer.select dùng sequence modulo số candidate; candidate đã được Dispatcher lọc health/circuit/capacity. Tập node thay đổi thì phân phối cửa sổ ngắn không nhất thiết đều.

**7. LC đang đếm gì?**  
Đếm inFlight của request lease, không đếm mọi socket TCP hoặc idle pool. Hòa thì ưu tiên lastSelectedNanos nhỏ hơn. Code tại LeastConnectionsLoadBalancer:8–10.

**8. Adaptive chọn dựa trên gì?**  
EstimatedWork dự báo service/wait/completion theo nhóm route, capacity và việc outstanding. Scorer nhân completion với resource/error/uncertainty penalty. AdaptiveLoadBalancer chọn min score hoặc exploration; Dispatcher reserve cùng lock.

**9. Adaptive có phải AI không?**  
Không có mô hình AI tổng quát trong code. Đó là dự báo hữu hạn theo nhóm workload, EWMA và công thức penalty có trọng số; cần kiểm thực nghiệm sai số và kết quả.

**10. Adaptive có luôn tốt hơn RR/LC không?**  
Chưa có bằng chứng như vậy. Nó có overhead, warmup, exploration và sai số. Benchmark lịch sử khác fingerprint HEAD; smoke hiện tại không đủ xếp hạng hiệu năng.

**11. Khi hai request đồng thời chọn cùng backend có vượt giới hạn không?**  
Dispatcher dùng cùng lock cho snapshot/selection/reservation và kiểm tổng/per-backend in-flight. Lease release được bảo vệ khỏi thực hiện hai lần. J có ConcurrentLifecycleTest/DispatcherTest; còn capacity vật lý không đồng nghĩa với counter.

**12. Nếu tắt một backend thì chuyện gì xảy ra?**  
Health hoặc transport failure loại node, request mới dùng node còn lại. Request đang dở có thể lỗi. L đã kill riêng một child,15 request sau phát hiện đều200 và node restart phục hồi.

**13. Nếu tất cả backend chết?**  
Không có lease phù hợp nên ProxyHandler trả503. L đã thử. Không tạo thành công giả hoặc xếp hàng vô hạn.

**14. Health check khác metrics và readiness?**  
Health là node sống/khả năng phục vụ cơ bản; metrics là dữ liệu quan sát; readiness/capability cho DB/file/nghiệp vụ cụ thể. Telemetry lỗi không tự suy node chết. Code: HealthChecker.pollLoop, CapabilityRouting, BackendMetrics.

**15. Circuit breaker giải quyết gì?**  
Hạn chế liên tiếp đưa việc tới node đang lỗi: CLOSED→OPEN→HALF_OPEN, một probe, rồi đóng hoặc mở lại. Nó không rollback DB hay bảo đảm request bị mất sẽ thành công.

**16. Timeout trả gì?**  
Ca backend chậm trước response có thể504; lỗi kết nối/HTTP hỏng502; hết candidate503. Đã gửi header rồi thì không thể gửi response lỗi thứ hai. L có504 khoảng3giây trong profile thử.

**17. Vì sao không retry POST sang backend khác ngay khi lỗi?**  
Backend có thể đã commit nhưng response bị mất. Retry vô điều kiện có thể tạo hiệu ứng trùng. Dự án không auto replay POST; cùng idempotency key/body ở ứng dụng cho phép kiểm lại ý định.

**18. PostgreSQL có phải thành phần bắt buộc của load balancer?**  
Không. Nó cần cho workload Store có trạng thái. L chạy lõi với Store tắt; Store dùng PG để nhiều backend thấy cùng session/stock/order.

**19. Transaction và idempotency khác nhau thế nào?**  
Transaction giữ nhóm ghi SQL commit/rollback cùng nhau. Idempotency key+hash nhận diện lại một ý định để không tạo đơn lần hai. OrderService:17–43 dùng cả hai; CI HEAD có same-key replay sau restart.

**20. Đã chứng minh không bán vượt kho chưa?**  
Source khóa variant/product và kiểm stock trong transaction; đây là thiết kế liên quan trực tiếp. CI HEAD chứng minh một đơn/stock-1, chưa đầy đủ stress cạnh tranh. Không dùng source hoặc test pool giả để tuyên bố đã stress PG HEAD.

**21. Vì sao cần shared storage ngoài DB?**  
DB giữ metadata/hash, shared storage giữ byte. Backend đổi vẫn phải đọc cùng tệp thật; CI kiểm file giữ qua restart. Ba folder local riêng trên ba máy không đáp ứng điều đó.

**22. Mộc Store có cần thiết không?**  
Không bắt buộc cho proxy hoạt động, nhưng hữu ích để có workload SQL/session/transaction/file. Nội dung nghiên cứu là cách proxy phục vụ workload này và phản ứng khi lỗi, không số trang bán hàng.

**23. Khách vào /admin/shop có chiếm quyền admin không?**  
URL đó trả shell200 nhưng API vẫn gọi SessionService.admin. F anonymous API401, CUSTOMER GET/POST403, ADMIN GET200. Chưa tìm bypass trong ca thử; chưa tuyên bố mọi kiểm tra bảo mật đã hoàn thành.

**24. Dashboard đang đo mọi socket và mọi request của toàn hệ thống không?**  
Không. Các bộ đếm có phạm vi riêng; traffic data loại management, transport gauge không gồm health/streaming/idle. p95 từ ring 2.048, không phải toàn lịch sử.

**25. Tại sao test AssetCache pass mà JS không304?**  
Test gọi helper trực tiếp. StoreApplication.handle không gọi helper và luôn gửi200. Đó là ví dụ có test cho class nhưng đường chạy tính năng chưa dùng class.

**26. PDF Store có giống large-file streaming không?**  
Không. Store PDF bounded theo min(512 KiB,SHOP_MAX_PDF_BYTES), mặc định256KiB, đọc có SHA và không hỗ trợ Range. File streaming là public immutable /api/pbl4/files/*, opt-in, 32 KiB buffer,tối đa1GiB theo code; CI đo tới256MiB.

**27. Nếu Proxy chết thì sao?**  
Điểm truy cập thống nhất mất dù backend còn chạy. Chưa có proxy HA/VIP/replication trong kiến trúc. Đây là SPOF được thừa nhận, không được nói “HA chỉ nhờ ba backend”.

**28. Nếu database chết thì sao?**  
Nghiệp vụ cầnDB không làm bình thường; capability có thể loại route và trả503, static/workload độc lập vẫn có thể chạy theo khả năng còn lại. Source/unit có phân loại; chưa chạy lại DB failure thật local HEAD do initdb bị chặn.

**29. CI hiện tại có xanh không?**  
Không. Java Linux và Docker live success, bước Java Windows32 main PASS nhưng job Windows fail ở Python Unicode. tools/config Linux success. Phải nêu từng job, không gom thành tất cả pass hoặc tất cả chức năng hỏng.

**30. Đã triển khai nhiều máy thật chưa?**  
Chưa đủ bằng chứng. Có scripts/hướng dẫn MAIN+B2+B3, shared DB/SMB, nhưng L là loopback và CI là một Docker host. Physical 3-PC LAN: CHƯA KIỂM THỬ.

**31. Kết quả nghiên cứu hiện tại nói được điều gì?**  
Nói được lõi và các cơ chế có behavior xác minh, một số transaction/file path có CI thật; có thể phân tích các đánh đổi. Chưa có đối chứng hiệu năng mới đủ để xếp hạng hoặc cam kết quy mô người dùng.

**32. Nếu dừng phát triển ngay, có bảo vệ được không?**  
Có cơ sở bảo vệ phần lõi với phạm vi thực nghiệm rõ, code và evidence cụ thể. Chưa đủ cho mọi tuyên bố mở rộng; phải công khai lỗi Windows, conditional Store, benchmark/source gap, LAN chưa thử và SPOF. Không cần bịa kết quả hoặc thêm nghiệp vụ bán hàng để lấp chỗ trống.

## 16. Kết luận nghiệm thu

### 16.1. Phân loại quyết định

| Mức | Áp dụng cho phần nào | Cơ sở |
|---|---|---|
| **ĐÃ ĐẠT** | Lõi nhận/forward/response HTTP/1.1; RR/LC/Adaptive selection; health/circuit/deadline/admission và các ca core được xác nhận | S+J 32 PASS+L; CI Java/Docker đúng HEAD |
| **ĐÃ ĐẠT** | Store order đơn lẻ/SQL stock/replay sau restart, download hash/persistence, streaming theo profile CI | Log Docker CI đúng HEAD; chỉ các ca đã nêu |
| **ĐẠT MỘT PHẦN** | Mục tiêu kiểm toán/nghiệm thu toàn dự án; Store toàn diện; vận hành Windows; đánh giá hiệu năng và độ rộng failure/transaction coverage | Có phần hiện thực/kiểm thử tốt nhưng còn lỗi và evidence gap |
| **CHƯA ĐỦ BẰNG CHỨNG** | Physical LAN ba máy, scaling >3 node, benchmark đối chứng đầy đủ HEAD, full browser/identity/CRUD/upload/fault matrix HEAD | Không có ca phù hợp hoặc không chạy lại được trong môi trường cô lập |
| **CHƯA ĐẠT** | Windows operations regression toàn bộ xanh; lệnh configure với stdout cp1252; Store wildcard conditional trong ca F | Lỗi được tái hiện A-01/A-02 |
| Không là tiêu chí bắt buộc | Production HA, cloud/discovery, HTTP mọi phiên bản, thương mại đầy đủ | Ngoài phạm vi; không gán thành lỗi cần phát triển |

Không dùng phần trăm hoàn thành tùy tiện. Không dùng một lỗi phụ trợ phủ nhận mọi kết quả core; cũng không dùng core PASS để xóa lỗi vận hành hoặc khoảng trống nghiệm thu.

### 16.2. Đủ cơ sở bảo vệ ở mức nào?

**Có cơ sở kỹ thuật để bảo vệ một đồ án Reverse Proxy & Load Balancer ở mức hệ thống thực nghiệm**, với code có đường gọi thật, test mới trên đúng SHA, smoke ba JVM và CI Docker có dependency thật. Phải trình bày đúng giới hạn thay vì “mọi thứ đã hoàn thành”.

**Chưa có cơ sở để xác nhận toàn bộ phạm vi mở rộng đã nghiệm thu**, hoặc hứa production/HA/physical LAN/ưu thế hiệu năng hiện tại. Việc hội đồng chấp nhận cuối cùng còn phụ thuộc tiêu chí học phần, chất lượng giải thích và demo; báo cáo không thay quyết định của giảng viên.

### 16.3. Rủi ro nếu dừng tại HEAD

1. Console encoding Windows có thể làm configure thất bại sau khi đã ghi cấu hình; CI giữ trạng thái FAIL.
2. Store asset conditional chưa đúng trong ca wildcard; không có lợi ích ETag304 từ helper chưa nối.
3. Proxy,DB,storage và host vẫn là điểm lỗi đơn; node replica không xóa các điểm lỗi này.
4. Request đang thực hiện khi node/network lỗi có thể thất bại; POST có thể commit dù client không nhận response.
5. Thí nghiệm ngắn và fingerprint cũ có thể bị trình bày nhầm thành benchmark HEAD.
6. Nghiệp vụ cạnh tranh, upload, dependency fault và UI/identity chưa đủ coverage hiện tại; không thể loại trừ lỗi còn ẩn.
7. LAN/SMB thật có rủi ro quyền, origin/token, canonical path và atomic rename chưa được vật lý nghiệm thu.
8. Feature mặc định tắt có thể bị demo nhầm như đang bật; streaming/pooling/QoS dùng profile riêng.

Những rủi ro này là phần phải thừa nhận; audit không chuyển chúng thành lệnh lập trình hoặc sửa cấu hình.

### 16.4. Mười nhận định quan trọng nhất

1. Bản chất dự án là proxy và scheduler HTTP có hiện thực độc lập, không phải chỉ một website bán hàng.
2. Lõi đã có kiểm chứng mới:32 main Java PASS và ba backend JVM thật.
3. Ba chiến lược chọn khác nhau; LC đếm active lease, Adaptive dự báo chứ không chia đều.
4. Eligibility, reservation, completion và resource bounds quan trọng ngang bản thân công thức lựa chọn.
5. Backend failure/recovery không tương đương không mất request hoặc HA toàn hệ thống.
6. Mộc Store hợp lý khi làm workload chung DB/session/file; nghiệp vụ thương mại nâng cao không cần thành mục tiêu.
7. Có class/test PASS không chứng minh feature được gọi: AssetCache là ví dụ cụ thể.
8. CI HEAD có cả kết quả tốt và lỗi: Docker 68 checkpoint PASS, Windows operations Unicode ERROR.
9. Evidence lịch sử khác fingerprint không chứng minh benchmark hoặc mọi behavior của HEAD; LAN physical chưa thử.
10. Kết luận đúng là core đạt trong phạm vi thử, toàn dự án đạt một phần; không suy production hoặc hiệu năng vượt trội.

### 16.5. Xác nhận phạm vi thao tác

Báo cáo này là tài liệu mới duy nhất được thêm vào checkout. Không sửa source/config/script/UI/SQL hay tài liệu cũ; không commit/push/merge/đổi branch. Không reset/seed/migrate/stop DB thật, không xóa/di chuyển tài nguyên thật, không dùng .env.store thật cho thử ghi. Các tiến trình được dừng chỉ thuộc phiên thử cô lập do audit tạo. Các kết quả chưa chạy hoặc bị chặn đã được ghi rõ, không tự tạo PASS.

Kết luận cuối: **đủ cơ sở bảo vệ bản chất và hiện thực lõi với giới hạn trung thực; nghiệm thu toàn bộ dự án ở mức ĐẠT MỘT PHẦN.**



