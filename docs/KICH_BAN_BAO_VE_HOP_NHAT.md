# Kịch bản demo và bảo vệ PBL4

Bản này gộp phần kể chuyện, các bước chạy demo, bảng kiểm thử và cách giải thích benchmark thành một tài liệu. Kịch bản đầy đủ mất khoảng 8–9 phút; phần cuối có bản rút gọn nếu thầy chỉ cho 5 phút.

## 1. Chuẩn bị trước giờ trình bày

Mở PowerShell tại thư mục dự án. Kiểm tra java -version hiển thị Java 21. Script demo sẽ build và chạy các tiến trình do chính nó quản lý.

~~~powershell
cd D:\PBL4\reverse-proxy-demo
function demo { powershell -NoProfile -ExecutionPolicy Bypass -File "$PWD\scripts\demo.ps1" @args }
demo stop
demo start -BindHost 127.0.0.1 -Strategy ADAPTIVE
demo status
~~~

Chờ đến khi backend-1, backend-2, backend-3 đều báo UP. Mở sẵn hai tab:

- http://127.0.0.1:8080/demo để gửi request.
- http://127.0.0.1:8080/__proxy/dashboard để xem thuật toán, trạng thái và quyết định Adaptive.

Đóng các tab khác đang gửi request và không chạy benchmark cùng lúc. Request thừa có thể làm lệch chuỗi Round Robin hoặc ghi đè quyết định Adaptive mới nhất. Sau buổi demo, chạy demo stop; đóng PowerShell không tự dừng các JVM.

## 2. Kịch bản nói và thao tác trực tiếp — khoảng 8–9 phút

### 0:00–0:40 — Giới thiệu bài toán

**Nói:**

> “Đề tài của em là reverse proxy và cân bằng tải. Thay vì để client gọi từng server, client gửi request tới một địa chỉ chung là cổng 8080. Proxy chọn một trong ba backend ở cổng 9001, 9002 hoặc 9003, chuyển request tới backend rồi trả phản hồi về client.”

Chỉ vào sơ đồ **Máy khách → Proxy ngược → Máy chủ** trên trang /demo.

> “Ba backend là ba tiến trình Java riêng chạy trên cùng máy, không phải ba máy tính vật lý. Mỗi tiến trình có cổng và PID riêng. PID là mã Windows dùng để phân biệt tiến trình; thuật toán cân bằng tải không chọn server dựa trên PID.”

### 0:40–1:30 — Chứng minh request đi qua proxy thật

Trong PowerShell chạy:

~~~powershell
demo basic
~~~

Sau đó trên /demo, chọn **Tác vụ nhẹ** rồi bấm **Gửi yêu cầu**. Chỉ vào HTTP status, tên backend và JSON phản hồi.

**Nói:**

> “Request này đi vào cổng 8080. Phản hồi cho biết backend nào đã xử lý. Header X-Proxy-Backend và trường server trong JSON cho cùng một địa chỉ, nên mình xác nhận được request đã được proxy chuyển tiếp.”

> “HTTP và việc chuyển tiếp là thật. Công việc bên trong được giả lập để dễ đo: tác vụ nhẹ thường chờ khoảng 20 mili giây; nó không truy vấn cơ sở dữ liệu nghiệp vụ.”

### 1:30–2:20 — Cho thấy request chậm giữ một chỗ xử lý

Chuyển sang Least Connections bằng:

~~~powershell
demo strategy -Strategy LEAST_CONNECTIONS
~~~

Lệnh này khởi động lại proxy với chiến lược được chọn; tải lại tab demo nếu trình duyệt báo mất kết nối. Mở /demo trong hai tab. Ở tab thứ nhất chọn **Tác vụ chậm** và bấm gửi. Trong lúc nút còn chờ, ở tab thứ hai gửi **Tác vụ nhẹ**.

**Nói trong lúc chờ:**

> “Tác vụ chậm được backend giữ khoảng hai giây. Request đó chiếm một lượt xử lý; cả server không dừng lại vì backend còn nhiều worker. Trong lúc lượt này còn hoạt động, Least Connections có thể ưu tiên server đang giữ ít lượt xử lý hơn.”

Con số “đang xử lý” là ảnh chụp trạng thái tại thời điểm đọc. Nó sẽ về 0 sau khi request hoàn tất; đây không phải bộ đếm tổng.

### 2:20–3:10 — Chứng minh Round Robin

Đảm bảo không còn request chậm đang chạy, rồi chạy:

~~~powershell
demo rr
~~~

**Kết quả mong đợi:**

~~~text
PASS: B1 -> B2 -> B3 -> B1 -> B2 -> B3
~~~

**Nói:**

> “Round Robin xoay vòng giữa các backend đủ điều kiện. Nó chia lượt theo thứ tự, nhưng không biết request nào nặng hơn hoặc backend nào chậm hơn.”

Trong lúc lệnh đang kiểm tra, đừng gửi thêm request từ tab khác. Bộ đếm Round Robin là trạng thái dùng chung; request thừa sẽ làm chuỗi kiểm tra dịch chuyển.

### 3:10–5:30 — So sánh Round Robin với Adaptive

Chạy:

~~~powershell
demo adaptive
~~~

Script giữ độ trễ mô phỏng của ba backend ở **20 / 80 / 240 ms**, mỗi backend có 8 chỗ xử lý. Nó gửi cùng loại /work?cost=1 cho hai chiến lược: mỗi chiến lược có 12 request làm nóng, sau đó 120 request đo theo các nhóm 8 request đồng thời. Cuối bước, độ trễ vẫn là 20/80/240 ms; proxy để lại ở Adaptive.

**Nói:**

> “Ở đây em cố ý tạo ba backend có tốc độ khác nhau. Round Robin vẫn chia đều lượt nên có request phải vào máy chậm. Adaptive dùng lịch sử độ trễ và lượng việc đang chờ để dự đoán máy nào hoàn thành sớm hơn. Đây là demo định tuyến trong một tình huống cụ thể, chưa phải bằng chứng Adaptive luôn tốt nhất.”

Kết quả một lần tập dượt từng ghi Round Robin khoảng **40/40/40** request và Adaptive khoảng **115/3/2**; thời gian tương ứng khoảng **4,2 giây** và **1,5 giây**. Đây là kết quả của lần chạy đó, không phải con số cố định. Khi trình bày, hãy đọc số và thời gian hiện ra trong lần chạy hiện tại.

Nếu bảng quyết định Adaptive đang hiển thị, bấm **Gửi yêu cầu thử** và chỉ vào quyết định mới nhất.

> “ECT là thời gian chờ dự đoán cộng thời gian phục vụ dự đoán. Điểm là ECT nhân hệ số phạt tài nguyên. Proxy lọc các backend không đủ điều kiện rồi thường chọn backend có điểm thấp. Thuật toán có bước thăm dò và dựa trên ước lượng, nên không đảm bảo luôn chọn đúng máy nhanh nhất. Đây là thuật toán theo quy tắc và số liệu, không phải AI.”

Nếu trace không hiện, bỏ qua phần này và dùng kết quả CSV/terminal của demo adaptive; đừng dừng demo để sửa cấu hình trước mặt thầy. Trong repo hiện tại, cấu hình bật trace nằm ở config/application.properties.

### 5:30–6:50 — Backend hỏng và phục hồi

Mở dashboard để thầy thấy trạng thái, rồi chạy:

~~~powershell
demo health
~~~

Script chuyển sang Round Robin, dừng tiến trình backend 2, chờ health checker nhận ra backend đã DOWN, gửi sáu request xác nhận backend đó không còn nhận traffic, bật lại backend 2 rồi kiểm tra nó nhận request sau phục hồi.

**Kết quả cuối mong đợi:**

~~~text
PASS: DOWN excluded; WARMING/UP recovery; backend-2 receives traffic again.
~~~

**Nói:**

> “Khi health checker đánh dấu backend 2 là DOWN, proxy bỏ nó khỏi lựa chọn và tiếp tục chuyển request tới hai backend khỏe. Khi backend trở lại, nó qua giai đoạn WARMING rồi mới nhận traffic lại.”

Các mốc chuyển trạng thái có thể dao động theo máy. Bài này chứng minh phát hiện backend mất kết nối và phục hồi có kiểm soát; nó không tự nó chứng minh riêng cơ chế circuit breaker. Circuit breaker có bài kiểm tra resilience riêng.

### 6:50–7:30 — Nút gửi 300 request (tùy thời gian)

Trên /demo, bấm **Gửi 300 request** một lần. Giao diện phát 300 HTTP request thật qua proxy theo lịch khoảng 60 request/giây: **180 tác vụ nhẹ, 90 tác vụ CPU và 30 tác vụ chậm**. Đây không phải 300 request đồng thời. Bảng ghi kết quả từng request.

**Nói:**

> “Nút này giúp quan sát phân phối một đợt tải hỗn hợp. Nó không đảm bảo làm đầy hàng chờ hay tạo lỗi; khi ba backend khỏe, các request có thể đều thành công.”

Dashboard đọc metrics định kỳ, nên tổng của nó có thể khác số lần bấm hoặc số dòng trong lượt chạy này. “Đang xử lý” là số đang hoạt động tại thời điểm lấy mẫu; các bộ đếm hoàn tất là số tích lũy của bộ đếm tương ứng.

### 7:30–8:40 — Báo cáo benchmark và kết luận

Mở docs/assets/charts/defense-20261021/index.html. Đây là báo cáo tĩnh đã tạo từ dữ liệu benchmark lưu sẵn, không cập nhật theo request vừa gửi trên /demo.

Trang HTML này ghi rõ một thí nghiệm **Mixed ở 60 request/giây**: mỗi lần chạy có 300 request đo và 100 request làm nóng; lặp ba lần cho mỗi thuật toán. Vì vậy tổng của mỗi thuật toán trên bảng là 900 request:

| Thuật toán | Thành công | Thất bại | Thành công/giây |
|---|---:|---:|---:|
| Round Robin | 860/900 | 40 | 36,35 |
| Least Connections | 889/900 | 11 | 38,26 |
| Adaptive | 849/900 | 51 | 40,39 |

**Nói:**

> “Kết quả này không cho thấy một thuật toán thắng mọi chỉ số. Trong Mixed 60, Adaptive có tốc độ thành công trung vị cao nhất trong bảng này, nhưng cũng có nhiều request thất bại nhất; Least Connections có ít lỗi hơn. Ở kịch bản backend khác năng lực tại 60 request/giây, Adaptive và Least Connections đều thành công 900/900, còn p95 của Adaptive là khoảng 63 ms so với khoảng 189 ms của Least Connections. Kết luận phụ thuộc workload.”

> “Demo trên trình duyệt, bài kiểm tra định tuyến và benchmark là các phép thử khác nhau. Benchmark chạy theo lịch cố định, có warm-up, lặp lại và ghi cả thành công lẫn thất bại. Công việc backend vẫn là giả lập, và các JVM chạy chung một máy nên đây chưa phải phép đo production.”

Kết thúc bằng:

> “Tóm lại, client gửi request tới một cổng chung; proxy chọn backend, giữ giới hạn xử lý và trả phản hồi. Ba thuật toán có cách chọn khác nhau; health check giúp bỏ backend lỗi và đưa backend phục hồi trở lại có kiểm soát. Kết quả benchmark cho thấy trade-off theo từng loại tải.”

## 3. Bảng kiểm thử để trả lời khi thầy hỏi sâu

| Nhóm | Cách xác nhận | Trạng thái trong dự án |
|---|---|---|
| Chuyển tiếp cơ bản | demo basic hoặc curl.exe -i "http://127.0.0.1:8080/work?cost=1"; đối chiếu HTTP 200, X-Proxy-Backend và JSON server. | Chạy trực tiếp được. |
| Round Robin | demo rr; kiểm tra sáu request theo thứ tự 9001 → 9002 → 9003 lặp hai vòng. | Script tự báo PASS/FAIL. |
| Least Connections | Chọn chiến lược, giữ một /slow đang chạy rồi gửi /work từ tab thứ hai; quan sát backend nhận request. | Chạy trực tiếp được; kết quả phụ thuộc thời điểm gửi và trạng thái các backend. |
| Adaptive | demo adaptive tạo độ trễ backend không đều, so sánh cùng workload; xem trace nếu được bật. | Chạy bằng script; là demo định tuyến, không phải benchmark công suất. |
| Backend lỗi | demo health; xác nhận backend 2 ở DOWN không nhận traffic, rồi phục hồi qua WARMING/UP. | Chạy bằng script có kiểm tra PASS/FAIL. |
| Giới hạn đồng thời / 503 | Chạy powershell -ExecutionPolicy Bypass -File scripts/test.ps1. ConcurrentLifecycleTest dùng fixture giới hạn 8 lượt với 64 client để kiểm tra request bị từ chối và việc trả lại tài nguyên. | Có kiểm thử tự động. Nút 300 request trên web không đảm bảo tạo 503. |
| Circuit breaker | Chạy bài resilience riêng trong scripts/benchmark_resilience.py; cơ chế này kiểm tra lỗi upstream liên tiếp và trạng thái circuit. | Có trong code; không được chứng minh chỉ bằng demo health. |
| Rate limit / 429 | Cấu hình mặc định proxy.rate-limit.enabled=false. Bài resilience có thể bật giới hạn thấp để kiểm tra 429. | Có tính năng nhưng tắt mặc định; đừng kỳ vọng thấy 429 trong demo thường. |
| Cache | Không có bài cache hit/miss trong phiên bản hiện tại. | Chưa triển khai. Nói rõ nếu được hỏi. |
| Connection Pool | Mỗi upstream exchange tạo socket riêng và đóng kết nối; cấu hình bật pool bị từ chối. | Chưa triển khai. Không tuyên bố socket được tái sử dụng. |
| Benchmark | scripts/benchmark.py tạo dữ liệu thô; scripts/verify_benchmark.py kiểm chứng; tools/generate_charts.py xuất HTML và biểu đồ. | Báo cáo đã lưu là ngoại tuyến. index.html là Mixed 60; bảng 54 lần chạy đầy đủ nằm trong docs/DEFENSE_PERFORMANCE.md. |

Ma trận benchmark đầy đủ gồm 54 lần chạy, 16.200 request đo và 5.400 request warm-up: hai workload (Mixed và backend khác năng lực), ba mức 30/60/90 request/giây, ba thuật toán, ba lần lặp. Không cộng dữ liệu này với báo cáo HTML Mixed 60 như thể chúng là cùng một bảng.

## 4. Câu trả lời ngắn cho những câu dễ bị hỏi

**“Các công việc có thật không?”**

> “Request HTTP đi qua proxy và backend là thật. Phần xử lý được giả lập: chờ, tính CPU hoặc trả dữ liệu demo; không truy cập hệ thống nghiệp vụ hay cơ sở dữ liệu thật.”

**“Vì sao bấm gửi 300 mà không có 300 request đang xử lý?”**

> “300 là tổng số request được phát theo lịch khoảng 60 request/giây, không phải số request đồng thời. Request hoàn tất thì lượt đang xử lý được giải phóng. Muốn quan sát xử lý song song, em dùng bài demo adaptive, nơi script phát từng nhóm 8 request.”

**“Vì sao benchmark có lỗi nhưng em bấm tay không thấy lỗi?”**

> “Lượt bấm tay gửi request bình thường khi backend khỏe. Benchmark chạy nhiều lượt theo lịch tải cố định và có kịch bản gây tải, timeout hoặc phản hồi lỗi; một phản hồi ngoài 2xx hoặc lỗi client đều được tính là thất bại.”

**“Adaptive luôn tốt nhất à?”**

> “Không. Nó ước lượng thời gian hoàn thành và áp dụng hệ số phạt để xếp hạng backend đủ điều kiện. Kết quả phụ thuộc workload; bảng Mixed 60 cho thấy Adaptive có 51 lỗi còn Least Connections có 11, dù Adaptive có success RPS cao hơn trong bộ đo đó.”

**“Dashboard đang xử lý là gì?”**

> “Đó là số lượt đang giữ chỗ tại thời điểm dashboard đọc metrics, không phải số request đã gửi và cũng không phải tổng đợi từ đầu buổi. Dashboard cập nhật theo chu kỳ nên số có thể thay đổi hoặc đã về 0 khi mình nhìn.”

**“PID có ảnh hưởng chọn server không?”**

> “PID là mã nhận diện tiến trình Java trong Windows. Nó giúp phân biệt ba backend đang chạy; thuật toán định tuyến dùng tình trạng sức khỏe, tải và ước lượng thời gian, không dùng PID.”

## 5. Nếu chỉ có 5 phút

Giữ phần giới thiệu 30 giây, demo basic, demo rr, demo adaptive, demo health, rồi mở bảng Mixed 60 và nói kết luận một phút. Bỏ thao tác hai tab với /slow và bỏ nút 300 request; hai phần đó dễ kéo dài nhưng không cần để chứng minh luồng chính.

## 6. Lưu ý trước khi khẳng định kết quả kiểm thử

Chạy scripts/test.ps1 trước buổi bảo vệ và ghi lại kết quả của đúng checkout đang demo. Một ghi chú tập dượt cũ đề cập ManagementListenerTest có lúc lỗi timing trên PR trước; không lấy con số 15/16 trong ghi chú cũ làm trạng thái hiện tại. Không cần chạy toàn bộ test suite trước mặt thầy; nếu có lỗi chập chờn, trình bày kết quả thực tế đã xác minh và log tương ứng.

Tài liệu triển khai liên quan: docs/DEMO_5_MINUTES.md, docs/DEMO.md, docs/DEFENSE_PERFORMANCE.md, docs/BENCHMARK_GUIDE.md, config/application.properties.

