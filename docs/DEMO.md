# Demo trực tiếp PBL4 — Windows PowerShell

## Project thực sự có gì?

```text
Client (curl / demo.ps1)
       |
       v
<IP máy chủ>:8080  Reverse Proxy Java 21 (demo script: 0.0.0.0)
       |       Dispatcher: chọn backend + giữ chỗ đồng bộ
       +-----> backend-1 :9001
       +-----> backend-2 :9002
       +-----> backend-3 :9003
               mỗi backend là một JVM riêng
```

Đây là HTTP reverse proxy qua TCP socket, không phải TCP tunnel tổng quát. Proxy dùng virtual threads, admission và health check. RR, LC, Adaptive là ba strategy thật trong code. Strategy đọc lúc khởi động; **đổi strategy phải restart proxy**. Script làm việc đó, không có API đổi nóng.

Backend trả JSON `server: backend-9001` và header `X-Backend`. Proxy thêm `X-Proxy-Backend: 127.0.0.1:9001`. Script chỉ rút gọn tên hiển thị: `backend-9001` = `backend-1`, tương tự 9002/9003; không tạo dữ liệu giả.

## Chuẩn bị một lần

Mở Windows PowerShell trong repository:

```powershell
cd D:\PBL4\reverse-proxy-demo
Set-ExecutionPolicy -Scope Process Bypass

# JDK 21 hiện đã có trên máy kiểm tra; nếu chuyển máy hãy sửa đường dẫn này.
$env:JAVA_HOME = 'C:\Users\admin\AppData\Local\Temp\pbl4-audit-tools\jdk21\jdk-21.0.12.1+1'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
javac -version

# Build không cần Maven/network:
.\scripts\build.ps1

# Build + test bằng Maven, nếu mvn đã có trên PATH:
mvn verify
# Hoặc Maven đã có sẵn trên máy kiểm tra:
& 'C:\Users\admin\AppData\Local\Temp\pbl4-audit-tools\apache-maven-3.9.16\bin\mvn.cmd' verify
```

Nên giữ JDK 21 trong vị trí ổn định trên máy trước buổi kiểm tra; đường dẫn Temp trên là vị trí thực đã dùng khi xác minh. Không tải gì trong giờ demo. `demo.ps1 start` tự build lại bằng JDK 21, nên có thể bỏ lệnh build riêng nếu chỉ chạy demo. Không cần Python cho bốn demo.

Đảm bảo port **8080, 9001, 9002, 9003** còn trống, không chạy benchmark hay script backend/proxy khác đồng thời. Không gửi request từ terminal/trình duyệt khác trong demo RR; chúng cũng làm bộ đếm vòng tiến lên. Tắt tải CPU nặng không liên quan.

## Chuỗi lệnh chính cho buổi kiểm tra: đúng bốn demo

### Giao diện web của ba backend

Sau khi `demo.ps1 start`, mở bốn tab:

- `http://127.0.0.1:9001/demo`: Backend 01 **Aurora**, xanh ngọc.
- `http://127.0.0.1:9002/demo`: Backend 02 **Ember**, cam.
- `http://127.0.0.1:9003/demo`: Backend 03 **Orbit**, tím.
- `http://127.0.0.1:8080/demo`: vào qua proxy, giao diện là của backend được chọn.

Trang có hình server SVG, port/PID thật, số lượt phản hồi của backend, active/queue, delay cấu hình và heap. Những số này là snapshot lúc request được xử lý, không phải giám sát liên tục. Backend tắt sau đó thì trang cũ không tự biết; tải lại để kiểm tra. `/demo` vẫn qua admission, worker và health như workload khác; `/work`, `/slow`, `/compute` vẫn giữ API JSON cũ.

Nút **Gửi request tiếp** gọi lại `/demo` trên host/cổng hiện tại: đang ở 9001 thì tiếp tục tới B1; đang ở 8080 thì tiếp tục qua Proxy và được chọn backend theo strategy. Nút **Đi qua Proxy** chuyển từ backend trực tiếp sang cổng 8080 trên cùng host; nếu đã qua Proxy thì hai nút cùng gửi request mới qua Proxy. Các tab Aurora/Ember/Orbit mở **backend trực tiếp trên máy chủ** khi dùng localhost/127.0.0.1; từ máy khác, tab chỉ hiển thị danh tính, không có liên kết vì backend lắng nghe nội bộ. Lịch sử dưới trang lưu tối đa sáu response đã nhận trong tab/origin bằng sessionStorage, không sinh request nền. Số lượt là counter riêng của backend, có thể nhảy số nếu có client khác hoặc reset khi restart JVM.

Muốn thấy web đổi màu theo vòng:

```powershell
.\scripts\demo.ps1 strategy -Strategy ROUND_ROBIN
# Mở http://127.0.0.1:8080/demo rồi bấm Gửi request tiếp.
# Giữ nguyên URL 8080, quan sát B1 -> B2 -> B3 và lịch sử trong tab.
```

Không có ảnh/font/CSS ngoài: favicon cũng nhúng bằng data URI, cache tắt. Mỗi lần tải trang tạo một request ứng dụng; không có polling hoặc background fetch. SVG chỉ minh họa server; các số trên thẻ mới là dữ liệu đo thật. Đường đi trên trang lấy từ metadata forwarding và URL trình duyệt; có thể đối chiếu response header `X-Proxy-Backend` trong DevTools Network.

Nếu sửa giao diện khi cụm đang chạy, phải `demo.ps1 stop` rồi `demo.ps1 start` để dùng JAR mới; chỉ refresh trình duyệt không thay được code của JVM cũ.

### Cho máy khác truy cập qua Wi-Fi/LAN

Máy chủ chạy Proxy và cả ba backend. Máy khách chỉ cần trình duyệt, không cần Java hay repository. Hai máy kết nối cùng mạng Wi-Fi/LAN (không dùng guest network có client isolation).

```powershell
# Trên máy chủ, sau bước chuẩn bị JAVA_HOME ở trên:
.\scripts\demo.ps1 stop
.\scripts\demo.ps1 start -BindHost 0.0.0.0
.\scripts\demo.ps1 status
```

Script mặc định bind `0.0.0.0:8080`, in URL LAN theo IPv4 của card mạng có gateway. Ví dụ IP Wi-Fi lúc kiểm tra là `172.20.10.2`, máy khách mở **http://172.20.10.2:8080/demo**. Xem `ipconfig` hoặc chạy `status` nếu đổi Wi-Fi/hotspot. `0.0.0.0` là địa chỉ lắng nghe, không phải địa chỉ nhập trên trình duyệt. Không dùng `localhost`/`127.0.0.1` trên máy khách để gọi máy chủ.

Đổi strategy giữ nguyên bind host của phiên. Muốn chỉ demo trên máy chủ: `stop` rồi `start -BindHost 127.0.0.1`. Các phiên cũ không có bind host được xem là loopback-only; cần stop/start để bật LAN. Nếu chạy Java thủ công bằng `config/application.properties`, sửa `proxy.bind.host=0.0.0.0` trong cấu hình đó trước khi start; file mặc định vẫn là loopback.

Nếu Windows Firewall chặn, mở **PowerShell Run as administrator trên máy chủ**, chạy một lần (chỉ TCP 8080, chỉ nguồn trong subnet nội bộ):

```powershell
if (-not (Get-NetFirewallRule -Name 'PBL4-Demo-LAN-8080' -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -Name 'PBL4-Demo-LAN-8080' -DisplayName 'PBL4 Demo LAN TCP 8080' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8080 -RemoteAddress LocalSubnet -Profile Any
}
```

Rule áp dụng cả khi Wi-Fi/hotspot được Windows đánh dấu Public. Không cần tắt firewall hoặc mở 9001–9003. Xóa rule sau buổi demo nếu không dùng nữa:

```powershell
Remove-NetFirewallRule -Name 'PBL4-Demo-LAN-8080'
```

Nếu vẫn không vào được, trên **máy khách Windows** chạy:

```powershell
Test-NetConnection 172.20.10.2 -Port 8080
```

`TcpTestSucceeded: True` nghĩa là đã kết nối được cổng Proxy. Nếu False: kiểm tra URL/IP mới, Proxy đã start, firewall, VPN và chế độ cách ly thiết bị của Wi-Fi/hotspot. Mở từ chính máy chủ bằng IP LAN chỉ kiểm tra được listener; vẫn cần máy thứ hai để xác nhận đường mạng/firewall thực tế.

Chỉ đưa máy khách URL cổng 8080. Các backend và `/control` vẫn nội bộ; `/__proxy/metrics` vẫn chỉ cho loopback. Số `127.0.0.1:900x` trên sơ đồ là địa chỉ nội bộ **máy chủ** mà Proxy kết nối tới. Demo LAN không thay đổi thuật toán cân bằng tải.

```powershell
.\scripts\demo.ps1 start
.\scripts\demo.ps1 basic       # A: Client -> Proxy -> Backend -> Client
.\scripts\demo.ps1 rr          # B: 6 request tuần tự
.\scripts\demo.ps1 adaptive    # C: RR rồi Adaptive, cùng workload, backend khác delay
.\scripts\demo.ps1 health      # D: tắt/bật thật process Backend 2
.\scripts\demo.ps1 stop
```

`start` khởi động 4 JVM ẩn và trả lại terminal, đợi cả ba backend `UP`. Mặc định Adaptive, capacity mỗi backend 8, delay 20ms, CPU background=0. Gọi `start` lại khi cả cụm đang chạy sẽ kiểm tra và hiển thị trạng thái, không tạo thêm process. Sau khi đóng terminal, process vẫn chạy: mở terminal mới và dùng `stop` để dừng.

Không dùng `Stop-Process -Name java` hoặc `taskkill /IM java.exe`. Script lưu PID, thời điểm process được tạo, Java path và mã phiên/role trong `.demo/state.json`; trước khi kill, nó xác minh cả bốn dấu hiệu. PID tái sử dụng hoặc không khớp sẽ bị từ chối. Chỉ process do script tạo mới bị dừng. Các lệnh demo bị khóa để không chạy đồng thời.

`.demo/<session>/` chứa JAR riêng đã build, `proxy.properties`, stdout/stderr của từng JVM, CSV demo C. Chỉ file config riêng được sửa; `config/application.properties` và thuật toán không bị sửa. Thư mục `.demo/` nằm ngoài `target` để `mvn clean` không xóa PID tracking. Không xóa `.demo` khi process còn chạy.

### DEMO A — Reverse Proxy cơ bản (1–3 giây sau start)

```powershell
.\scripts\demo.ps1 basic
# Hoặc nhìn cả response header/body trực tiếp:
curl.exe -i "http://127.0.0.1:8080/work?cost=1"
```

Ví dụ output thật, backend được chọn có thể khác:

```text
Request 1 -> backend-3 (port 9003) HTTP 200
Client URL: http://127.0.0.1:8080/work?cost=1
X-Proxy-Backend: 127.0.0.1:9003
Body: {"server":"backend-9003","cost":1,"capacity":8,"requestNo":1}
```

**Nói với giảng viên:** “Client chỉ gọi port 8080. Backend tạo body có tên server; proxy chuyển tiếp và thêm header địa chỉ backend. Script kiểm tra body và header khớp nhau. Request đã đi qua proxy, không gọi trực tiếp port 9003.” `requestNo` tăng theo backend, không phải số request chung của hệ thống.

### DEMO B — Round Robin (8–12 giây gồm restart/warmup)

```powershell
.\scripts\demo.ps1 rr
```

Script đưa cả ba delay về 20ms, restart proxy với RR, đợi cả ba `UP`, rồi gửi đúng 6 request tuần tự:

```text
Strategy: ROUND_ROBIN (proxy restarted; all backends UP)
Request 1 -> backend-1 (port 9001) HTTP 200
Request 2 -> backend-2 (port 9002) HTTP 200
Request 3 -> backend-3 (port 9003) HTTP 200
Request 4 -> backend-1 (port 9001) HTTP 200
Request 5 -> backend-2 (port 9002) HTTP 200
Request 6 -> backend-3 (port 9003) HTTP 200
PASS: B1 -> B2 -> B3 -> B1 -> B2 -> B3
```

Script thực sự kiểm tra chuỗi trên và báo lỗi nếu khác. **Giải thích:** “RR quay vòng trên tập backend đủ điều kiện. Cả ba đang UP nên thấy vòng ba phần tử. Nếu backend DOWN hoặc hết admission thì tập ứng viên thay đổi; không thể đòi chuỗi này trong mọi trạng thái.”

### DEMO C — Adaptive với backend khác delay (khoảng 30–60 giây)

```powershell
.\scripts\demo.ps1 adaptive
```

Script dùng `/control` có sẵn: B1/B2/B3 delay = **20/80/240ms**, capacity đều 8. Không giả lập CPU load bằng số delay; đây là khác biệt thời gian phục vụ. Chạy RR trước, Adaptive sau; mỗi strategy restart proxy/đợi UP, cùng 12 request warm-up và 120 request đo `/work?cost=1`, 15 batch × tối đa 8 request đồng thời. Không đổi timeout, trọng số hay công thức. Đây là demo đóng vòng theo batch, **không phải benchmark RPS công bằng theo lịch đến cố định**.

Output có dạng:

```text
ROUND_ROBIN: 120 measured requests, 8 concurrent, 12 separate warm-up requests
  backend-1: 40 responses, sampled inflight peak=...
  backend-2: 40 responses, sampled inflight peak=...
  backend-3: 40 responses, sampled inflight peak=...
  Errors=0; elapsed=...s. Raw CSV saved.
ADAPTIVE: 120 measured requests, 8 concurrent, 12 separate warm-up requests
  backend-1: ... responses, sampled inflight peak=..., estimated work peak=...ms
  backend-2: ... responses, sampled inflight peak=..., estimated work peak=...ms
  backend-3: ... responses, sampled inflight peak=..., estimated work peak=...ms
```

RR vẫn quay vòng và thường 40/40/40 khi ba backend luôn đủ điều kiện. Adaptive được kỳ vọng nghiêng về backend phục vụ nhanh; **số chia cụ thể lấy từ run thật, không cố định hoặc sửa số cho đẹp**. Nếu không thấy khác biệt, xem CSV/trạng thái, không tuyên bố Adaptive thắng. Script không bỏ lỗi: có request lỗi sẽ báo demo chưa sạch và giữ CSV.

**Giải thích:** “Adaptive học service cost theo route bằng EWMA và giữ estimated outstanding work của mỗi backend. Nó ước lượng `ECT = thời gian chờ slot + service estimate`, kết hợp resource penalty hiện có. Vì vậy số request giao cho mỗi backend có thể khác nhau. Đếm ít connection hơn chưa chắc là hoàn thành nhanh hơn.”

Metrics xuất `estimatedOutstandingWorkMs`, `estimatedNextSlotMs`; không nhầm `scoreMs` tổng hợp cũ thành ECT chính xác của mọi request. CPU/heap chỉ là tín hiệu phụ trong tình huống này. CSV `ROUND_ROBIN-requests.csv`, `ADAPTIVE-requests.csv` và các file `*-metrics.csv` lưu trong thư mục session. Peak chỉ là max quan sát trên các mẫu, không phải đỉnh liên tục.

Cuối demo khôi phục delay 20/20/20, proxy còn Adaptive và còn lịch sử học; dùng lệnh đổi strategy/restart nếu muốn reset. Demo D tự restart để bắt đầu rõ ràng. Không suy luận demo này khắc phục regression slow trong benchmark Mixed trước đây.

### DEMO D — Health Check (15–25 giây gồm restart)

```powershell
.\scripts\demo.ps1 health
```

Script dùng RR để nhìn rõ backend quay lại, đợi ba backend UP rồi **kill đúng process Backend 2**, không chỉ bật cờ healthy=false. Trong lúc chờ phát hiện DOWN chưa gửi traffic: điều này tách health polling khỏi lỗi request. Sau DOWN gửi 6 request, kiểm tra không request nào tới B2; bật lại B2 và poll trạng thái mỗi 100ms.

```text
backend-2: UP             # có thể còn UP ngay sau kill, trước health poll tiếp theo
backend-2: DOWN
Traffic must now use only backend-1/backend-3:
Request 1 -> backend-1 ...
Request 2 -> backend-3 ...
...
backend-2: DOWN
backend-2: WARMING
backend-2: UP
Request ... -> backend-2 (port 9002) HTTP 200
PASS: DOWN excluded; WARMING/UP recovery; backend-2 receives traffic again.
```

**Giải thích:** “Polling cách 500ms, cần 2 lần health thành công liên tiếp để hồi phục. WARMING tăng khả năng nhận tải dần trong 5 giây, rồi UP. Không phát hiện chết tức thời; request gửi ngay khi backend vừa chết vẫn có thể lỗi trước khi health check kịp cập nhật.” Script dùng timeout chờ tối đa 20 giây, không coi ngủ một khoảng cố định là bằng chứng state đã đổi.

Muốn tự điều khiển từng bước:

```powershell
.\scripts\demo.ps1 backend2-stop
.\scripts\demo.ps1 status
curl.exe "http://127.0.0.1:8080/work?cost=1"
.\scripts\demo.ps1 backend2-start
.\scripts\demo.ps1 status
```

## Các lệnh tra cứu và chọn strategy

```powershell
.\scripts\demo.ps1 strategy -Strategy ROUND_ROBIN
.\scripts\demo.ps1 strategy -Strategy LEAST_CONNECTIONS
.\scripts\demo.ps1 strategy -Strategy ADAPTIVE
.\scripts\demo.ps1 status

$m = Invoke-RestMethod http://127.0.0.1:8080/__proxy/metrics
$m.backends | Format-Table port,state,inFlight,latencyEwmaMs,estimatedOutstandingWorkMs,estimatedNextSlotMs
curl.exe http://127.0.0.1:8080/__proxy/health
curl.exe http://127.0.0.1:9002/health
curl.exe http://127.0.0.1:9002/metrics
```

LC chọn backend ít lease đang chạy, dùng last-selected để phá hòa. Lệnh chọn LC hữu ích để xác nhận strategy thật đang hoạt động; không thêm một demo thứ năm vào phần trình bày.

| Endpoint | Ý nghĩa thực tế |
|---|---|
| Proxy `/work?cost=1` | Forward; backend sleep `delayMs × cost` |
| Proxy `/slow` | Forward; backend sleep 2 giây, không nhân cost |
| Proxy `/compute?cost=8` | Forward; backend tính CPU, 2 triệu vòng × cost |
| Backend `:900x/health` | UP hoặc HTTP 503 theo health backend |
| Backend `:900x/metrics` | Text properties: CPU, heap, active, queued, received... |
| Proxy `/__proxy/health` | Proxy có backend đủ điều kiện hay chưa |
| Proxy `/__proxy/metrics` | JSON strategy và state/counter/estimates từng backend; loopback-only |
| Backend `/control?delayMs=240` | Thay delay, chỉ truy cập backend trực tiếp qua loopback |

`/health` hoặc `/metrics` gọi qua port 8080 được forward tới một backend, **không phải** health/metrics riêng của proxy. `/control` gọi qua proxy bị chặn 403. Cost hợp lệ 1..20.

## Start thủ công để hiểu từng process (không chạy cùng demo.ps1)

Sau `demo.ps1 stop` và build, mở bốn terminal ở repository, thiết lập JDK 21 cho từng terminal:

```powershell
# Terminal 1
java -cp target/classes com.example.backend.DemoBackendServer 9001 8 20 0
# Terminal 2
java -cp target/classes com.example.backend.DemoBackendServer 9002 8 20 0
# Terminal 3
java -cp target/classes com.example.backend.DemoBackendServer 9003 8 20 0
# Terminal 4
java -cp target/classes com.example.proxy.Main config/application.properties
```

Dừng Backend 2 thủ công bằng Ctrl+C **trong Terminal 2**, rồi chạy lại đúng lệnh đó. Đổi strategy thủ công: sửa `loadbalancer.strategy` trong file cấu hình, Ctrl+C Terminal 4 rồi start lại proxy. Không nhầm thao tác này với config riêng `.demo/<session>/proxy.properties` của script. Những process start thủ công không thuộc PID tracking của `demo.ps1 stop`.

## Xử lý nhanh khi có trục trặc

| Triệu chứng | Cách xử lý |
|---|---|
| PowerShell chặn script | `Set-ExecutionPolicy -Scope Process Bypass`, hoặc `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo.ps1 start -JavaHome "C:\path\jdk-21"` |
| `JDK 21 required` | Máy từng mặc định Java 26; đặt JAVA_HOME đúng và PATH như trên. Có thể truyền `-JavaHome` khi start. |
| Port busy | `Get-NetTCPConnection -State Listen -LocalPort 8080,9001,9002,9003` rồi xem `Get-Process -Id <OwningProcess>`. Dừng đúng terminal/ứng dụng chủ port. Không kill tất cả Java. |
| Proxy chưa sẵn sàng / HTTP 503 | `demo.ps1 status`, đợi health/warmup. Nếu process chết: xem `.demo/<session>/*.err.log`, `stop` rồi `start`. |
| B2 đã dừng dở / Ctrl+C giữa health | `backend2-start`, hoặc `stop` rồi `start`. Script tracking giữ process còn lại. |
| PID không khớp | Script từ chối tác động; xem `.demo/state.json` và process thực tế. Không sửa PID để ép kill, không xóa tracking khi còn process chạy. |
| RR không đúng vòng | Dừng request từ terminal/trình duyệt khác, kiểm tra cả ba UP, chạy lại `rr` để reset sequence. |
| Adaptive phân phối khác ví dụ | Đây là số đo thật phụ thuộc EWMA/CPU/JIT; xem CSV và trạng thái. Không chỉnh thuật toán trước giờ demo để ép kết quả. |
| Muốn về trạng thái ban đầu | `demo.ps1 stop` rồi `demo.ps1 start`; runtime config được tạo lại, log cũ giữ nguyên. |

## Kết quả xác minh

Đã diễn tập A → B → C → D ngày 29/09/2026 bằng JDK Microsoft 21.0.12.1 trên Windows PowerShell 5.1:

- A: HTTP 200, client URL port 8080, body `backend-9003` khớp `X-Proxy-Backend: 127.0.0.1:9003`.
- B: 9001 → 9002 → 9003 → 9001 → 9002 → 9003, cả sáu HTTP 200.
- C: RR **40/40/40**, Adaptive **118/1/1**, cả hai **0 lỗi** trên 120 request/strategy. Inflight peak lấy mẫu RR 3/3/3, Adaptive 8/1/0; estimated work peak Adaptive 264,6/50,0/0,0ms. B3 có 1 response nhưng không bị bắt trúng tại mẫu inflight, minh họa giới hạn sampling. Thời gian phần gửi 120 request RR 3,86s, Adaptive 0,82s; tổng lệnh còn gồm restart, health/warmup và 12 warm-up riêng mỗi strategy. Đây là số một lượt minh họa, không phải benchmark kết luận hiệu năng.
- D: thực sự tắt JVM B2; quan sát UP → DOWN mà chưa gửi workload; sáu request chỉ tới B1/B3; bật B2, quan sát DOWN → WARMING → UP và request quay lại B2.
- Smoke LC: monitoring xác nhận `LEAST_CONNECTIONS`; `/work`, `/slow`, `/compute`, `/health`, `/metrics`, `/__proxy/health`, `/__proxy/metrics` trả 200. Health/metrics trực tiếp cả ba backend trả 200.
- Gọi start lặp lại giữ nguyên PID; thử sai identity process thì stop từ chối và tất cả process vẫn còn sống. Kiểm tra stop/start riêng B2, stop lặp, từ chối port bị chiếm và cleanup không sót JVM demo.
- Maven verify sau cùng: **BUILD SUCCESS**, 17 scenario Dispatcher/estimator/concurrency, HealthChecker PASS, 60 HTTP checks PASS; log tại `.demo/verify.log`. Không disable test. Không chạy benchmark lớn trong lượt chuẩn bị demo.
- Sau kiểm chứng đã dừng hết process demo, kiểm tra không còn JVM mang marker phiên và không còn listener demo trên 8080/9001/9002/9003. Source Java và cấu hình production không thay đổi trong lượt này.
