# Hướng dẫn bảo vệ PBL4 trên Windows

Điểm cần chứng minh: proxy chuyển tiếp HTTP thật, giữ chỗ an toàn khi đồng thời,
loại máy chủ lỗi và giải thích được lựa chọn thích nghi. Kết quả đo có cả thất bại;
không trình bày Adaptive như thuật toán luôn tốt nhất.

## 1. Chuẩn bị trước buổi bảo vệ

Mở PowerShell tại `D:\PBL4\reverse-proxy-demo`. Cần JDK **21**, gồm `java`,
`javac`, `jar`; không chỉ JRE. Python chỉ cần khi kiểm tra dữ liệu hoặc vẽ lại biểu đồ.

```powershell
cd D:\PBL4\reverse-proxy-demo
Set-ExecutionPolicy -Scope Process Bypass
# Đường dẫn thực đã kiểm tra trên máy này; sửa nếu chuyển sang máy khác.
$env:JAVA_HOME = "$PWD\.demo\tools\jdk21-presentation\jdk-21.0.12.1+1"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
javac -version
.\scripts\test.ps1
python -m pip install -r tools/requirements-charts.txt
python -m unittest discover -s scripts -p "test_*.py"
```

Giữ JDK tại vị trí ổn định trước buổi demo, không tải công cụ trong giờ bảo vệ.
Không chạy benchmark và demo đồng thời. Các cổng 8080, 9001–9003 phải còn trống.
Script chỉ dừng tiến trình có PID, thời gian bắt đầu và dấu phiên khớp với phiên
đã ghi; nếu phát hiện PID khác, xử lý tiến trình đó thủ công, không tắt đại trà Java.

Để thấy lý do định tuyến, trước khi khởi động đặt trong
`config/application.properties`:

```properties
adaptive.decision-trace.enabled=true
```

Đây là chế độ trình diễn. Bộ benchmark công bố dùng `false` để không trộn chi phí
ghi quyết định vào so sánh. Có thể bật listener quản lý riêng bằng
`proxy.management.enabled=true`; cổng 9090 vẫn chỉ bind `127.0.0.1`.

```powershell
.\scripts\demo.ps1 stop
.\scripts\demo.ps1 start -BindHost 127.0.0.1 -Strategy ROUND_ROBIN
.\scripts\demo.ps1 status
Start-Process http://127.0.0.1:8080/demo
Start-Process http://127.0.0.1:8080/__proxy/dashboard
```

Trang thử yêu cầu giữ thao tác cũ, nhãn tiếng Việt. Dashboard đọc số liệu thật mỗi
2 giây, chỉ truy cập cục bộ. Ba bộ đếm tổng/thành công/thất bại gồm cả lần đọc quản
lý và có thể lệch tạm thời vì yêu cầu đang chạy. Tốc độ trên dashboard lấy chênh
lệch `sum(backends.completed)` chia thời gian monotonic thực, gồm cả phản hồi lỗi;
không đồng nghĩa tốc độ thành công của benchmark. Không có CPU hoặc độ trễ giả.
Khi mất kết nối, trang giữ số cũ có nhãn rõ ràng và xóa tốc độ; quá 6 giây báo cũ.

## 2. Luân phiên: chứng minh đường chuyển tiếp

```powershell
.\scripts\demo.ps1 rr
```

Đóng tab dashboard khi cần dãy định tuyến chính xác; không bấm gửi hoặc tải lại
`/demo` từ tab khác trong đoạn này. Script gửi sáu yêu cầu nhẹ và kiểm tra
9001 → 9002 → 9003 → 9001 → 9002 → 9003. Quản lý không cấp giữ chỗ nên bản thân
việc đọc metrics không tiến vòng; tải trang thử yêu cầu thì có.

```powershell
.\scripts\demo.ps1 basic
curl.exe -i "http://127.0.0.1:8080/work?cost=1"
```

Chỉ ra URL vẫn ở 8080, tiêu đề `X-Proxy-Backend`, `X-Proxy-Strategy`,
`X-Request-Id` và `server` trong JSON. Proxy giữ chỗ trước khi mở socket;
không chỉ đổi nhãn máy chủ trên trang.

## 3. Ít kết nối nhất: quan sát khi có công việc chậm

```powershell
.\scripts\demo.ps1 strategy -Strategy LEAST_CONNECTIONS
```

Trong một tab gửi **Tác vụ chậm**; trong lúc chờ, tab khác gửi **Tác vụ nhẹ**.
Quan sát giữ chỗ trên dashboard và máy chủ trả lời. LC chọn ít yêu cầu giữ chỗ
nhất, phá hòa bằng lần được chọn cũ nhất. Tên gọi connection gần với socket bận
vì mỗi lần chuyển tiếp có một socket; không phải phép đo mọi kết nối TCP của OS.
Một yêu cầu chậm và một yêu cầu nhẹ đều đóng góp một vào bộ đếm LC.

## 4. Thích nghi: giải thích quyết định và giới hạn

```powershell
.\scripts\demo.ps1 strategy -Strategy ADAPTIVE
curl.exe "http://127.0.0.1:9002/control?delayMs=240&cpuWorkers=0&healthy=true"
1..12 | ForEach-Object { curl.exe -s "http://127.0.0.1:8080/work?cost=1" }
curl.exe http://127.0.0.1:8080/__proxy/decision
```

Mở dashboard để đọc dịch vụ/chờ/hệ số phạt/điểm từng ứng viên. `MINIMUM_SCORE`
chọn điểm nhỏ nhất trong tập hợp lệ. `EXPLORATION` là thăm dò định kỳ, có thể chọn
điểm cao hơn. `WORK_BUDGET` là từ chối theo công việc dự báo; `DOWN`,
`CIRCUIT_UNAVAILABLE`, `INFLIGHT_LIMIT` bị loại trước xếp hạng. Chỉ giữ quyết định
gần nhất; yêu cầu sau ghi đè, không gắn trace hiện tại với một dòng lịch sử tùy ý.

```text
mới = cũ + 0,2 × (mẫu − cũ)                       EWMA
dịch vụ = EWMA dịch vụ mỗi đơn vị theo nhóm × số đơn vị
ECT = chờ slot ước lượng + dịch vụ                 ms
điểm = ECT × hệ số phạt                            ms
B = min(read timeout, request timeout)             mặc định 3000 ms
```

Hệ số phạt không có đơn vị: CPU 0,35; áp lực heap sau 75% là 0,5; lỗi EWMA là 2;
thêm bất định nếu telemetry cũ/thiếu. CPU dùng CPU tiến trình/ngân sách công bố,
heap không phải RAM vật lý. Công thức đầy đủ ở [ADAPTIVE_EXPLAINED](ADAPTIVE_EXPLAINED.md).
Chỉ học dịch vụ từ phản hồi thành công, không có hàng đợi lúc giữ chỗ và cùng thế
hệ sức khỏe. Không học từ thời gian `/health` hoặc lỗi trả nhanh.

Admission yêu cầu `ECT <= B` và `công việc còn lại + dịch vụ <= số slot × B`.
Khi máy chủ rảnh đủ lâu, có một cơ hội học lại để estimate cũ không loại nó mãi.
Circuit HALF_OPEN vẫn chỉ có một phép thử riêng. Từ chối nhanh là lỗi 503,
không được đếm thành công vì latency nhỏ.

```powershell
# Khôi phục độ trễ trước bài tiếp theo.
curl.exe "http://127.0.0.1:9002/control?delayMs=20&cpuWorkers=0&healthy=true"
```

Có thể dùng `.\scripts\demo.ps1 adaptive` để script chạy hai nhánh RR/Adaptive
cùng 120 yêu cầu, 8 đồng thời, giữ CSV thật. Đây là demo định tuyến, không phải
benchmark công suất tối đa và không thay thế bộ đo công bố.

## 5. Máy chủ ngừng và phục hồi

```powershell
.\scripts\demo.ps1 backend2-stop
.\scripts\demo.ps1 status
curl.exe "http://127.0.0.1:8080/work?cost=1"
.\scripts\demo.ps1 backend2-start
.\scripts\demo.ps1 status
```

Theo dõi DOWN → WARMING → UP. Health check vẫn chạy khi circuit mở. DOWN và
OPEN là hai cơ chế riêng; không khẳng định mỗi lần kill đều làm circuit OPEN:
health có thể phát hiện trước khi đủ năm lỗi chuyển tiếp. Yêu cầu đã gửi tới máy
chủ vừa chết có thể lỗi, không tự retry POST. Khi phục hồi tăng tải dần, không lập
tức coi năng lực đã đầy. Test Java kiểm chứng CLOSED/OPEN/HALF_OPEN, một phép thử,
timeout, completion muộn và không rò giữ chỗ.

```powershell
curl.exe http://127.0.0.1:8080/__proxy/metrics
curl.exe http://127.0.0.1:8080/__proxy/prometheus
.\scripts\demo.ps1 stop
```

Lệnh stop dừng các tiến trình demo đã ghi, giữ log/CSV. Script demo dùng kết thúc
tiến trình; graceful shutdown của server được kiểm chứng riêng bằng socket trong
`LifecycleTest`, không lấy việc stop script làm bằng chứng drain ứng dụng.

## 6. Mở và kiểm chứng kết quả đo

```powershell
Start-Process .\docs\assets\charts\defense-20261021\index.html
python tools/defense_artifacts.py --verify benchmarks/defense/20261021
python tools/generate_charts.py --bundle benchmarks/presentation/defense-20261021 --output target/defense-preview
```

[Phân tích cuối](DEFENSE_PERFORMANCE.md) có đủ hai kịch bản × ba mức tải × ba
thuật toán; [dữ liệu](../benchmarks/defense/20261021/matrix.csv) giữ cả ba lần lặp.
Bộ cũ 10.800 yêu cầu vẫn ở [phân tích lịch sử](history/PERFORMANCE_ANALYSIS.md).

Đọc ba thứ cùng nhau: số lượng thành công, tốc độ thành công/giây, độ trễ của
những phản hồi thành công. Số lượng cộng các lần lặp; tốc độ và phân vị công bố là
trung vị thống kê từng lần chạy. Thời gian drain và số lỗi khác nhau làm thứ hạng
count và median RPS có thể khác. Độ trễ thành công không có những yêu cầu bị loại.
Mỗi lần chạy chỉ 300 mẫu đo, khoảng 30 Slow; p99 theo loại có ít mẫu, không khái
quát thành bảo đảm tail latency. Dữ liệu một host chưa chứng minh hiệu năng nhiều máy.

## 7. Dàn ý sáu slide

| Slide | Nội dung và bằng chứng | Thao tác |
|---|---|---|
| 1 | Bài toán, Java 21, HTTP/1.1, sơ đồ luồng và giới hạn phạm vi | Gửi `/work`, đối chiếu URL/header/JSON |
| 2 | Giữ chỗ nguyên tử, deadline, bounded ingress, health/circuit | RR sáu bước; ngừng B2 và phục hồi |
| 3 | RR, LC, công thức ECT/EWMA/phạt/admission | Tạo độ trễ B2, đọc quyết định thật |
| 4 | Phương pháp: commit sạch, seed ghép cặp, 54 lần chạy, 16.200 mẫu | Mở manifest và lệnh verify độc lập |
| 5 | Counts/tốc độ/lỗi/độ trễ; phạm vi thắng, thua, chưa đủ bằng chứng | Mở biểu đồ chính và bảng ba tải |
| 6 | Slow, cold start, bão hòa, fairness và giới hạn | Đọc thí nghiệm trace có kiểm soát; phân biệt chứng minh/giả thuyết |

Khi bị hỏi “vì sao không luôn tốt hơn?”, trả lời bằng kết quả cụ thể: overhead,
estimate, exploration, tài nguyên dùng chung và admission có thể ảnh hưởng từng
loại yêu cầu. Không tạo thêm công suất bằng đổi cách định tuyến; không đổi hệ số
sau khi nhìn số liệu để tuyên bố chiến thắng.
