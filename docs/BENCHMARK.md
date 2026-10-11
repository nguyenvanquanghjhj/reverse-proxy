# Phương pháp benchmark công bằng

**Nhiều JVM chạy trên cùng một máy; kết quả không đại diện cho các backend trên nhiều máy vật lý độc lập.** CPU budget không là quota, heap không là RSS. Ba workload gốc và hệ số routing giữ nguyên; hardening bổ sung guard học lại backend rảnh khi estimate cũ vượt budget. Không dùng kết quả cũ để điều chỉnh thuật toán.

## Workload được chốt trước khi đo

| Kịch bản | Backend 1 / 2 / 3: capacity, delay | Request |
|---|---|---|
| A — homogeneous | cả ba: 4 slot, 30 ms | 100% `/work?cost=1` |
| B — heterogeneous | 8/20 ms; 4/60 ms; 2/160 ms | 100% `/work?cost=1` |
| C — mixed | cả ba: 4 slot, 20 ms | 60% light `/work?cost=1`; 10% slow `/slow?cost=1` chờ 2 s; 30% CPU `/compute?cost=8` |

Mixed dùng chuỗi phân loại xáo trộn theo seed. Tỷ lệ chính xác nếu số request chia hết cho 10; phần dư được lấy từ chu kỳ cố định. Compute chạy 16 triệu vòng xorshift, không sleep; thời gian phụ thuộc CPU/JIT nên không gán một latency giả định cho nó. Capacity là số slot xử lý song song, không phải CPU quota. Ba JVM chạy cùng máy vẫn chia sẻ CPU/RAM.

Rate mặc định 60 request/s áp dụng giống nhau cho mọi chiến lược. Không chọn rate riêng để ưu ái một chiến lược. Có thể chạy thêm rate thấp/cao với output mới; phải giữ cả kết quả cũ. RPS ở đây là throughput tại tải đầu vào đã đặt, **không phải RPS tối đa của hệ thống**.

## Chạy lại

Cần Python 3 và **JDK 21** (java cùng javac). Không cần package Python, Maven hoặc framework mới. Script tự copy source Java, biên dịch `--release 21` vào thư mục riêng của phép đo, ghi checksum source/class/config/script và dùng đúng bộ class đó xuyên suốt. Nó không dùng `target/classes`, nơi IDE có thể tự biên dịch lại.

```powershell
# Khi java trên PATH đã là JDK 21:
python scripts/benchmark.py --requests 1200 --warmup-requests 300 --rate 60 --repeats 3

# Chỉ rõ JDK nếu PATH đang trỏ sang phiên bản khác:
python scripts/benchmark.py --java "C:/path/to/jdk-21/bin/java.exe" --requests 1200 --warmup-requests 300 --rate 60 --repeats 3 --output benchmarks/results/my-baseline

# Kiểm tra nhanh pipeline; không dùng để kết luận thuật toán thắng/thua:
python scripts/benchmark.py --quick

# Kiểm tra các bất biến của phép đo:
python -m unittest discover -s scripts -p test_benchmark.py

# Xác minh một thư mục kết quả đã hoàn tất, không chạy lại benchmark:
python scripts/verify_benchmark.py benchmarks/results/my-baseline
```

Output mặc định: `benchmarks/results/<timestamp>`. Script từ chối thư mục đã tồn tại, kể cả rỗng. Có `complete.json` mới là toàn bộ ma trận đã chạy xong; thư mục thiếu file này có thể là lượt bị ngắt, không nên gộp nhầm vào báo cáo.

## Kiểm soát sự công bằng

- Mỗi strategy/scenario/repeat khởi động lại 3 JVM backend + 1 JVM proxy. Heap mỗi JVM `-Xms32m -Xmx128m`; HTTP/1.1, một kết nối TCP/request, không retry, không cache.
- Cấu hình proxy lấy từ cùng snapshot `config/application.properties`; chỉ đổi port/address/capacity theo workload và tên strategy. Health, timeout, inflight limit, slow-start và tất cả hệ số giữ nguyên cho cả ba.
- Chờ tất cả backend UP và kết thúc slow-start, chạy cùng warm-up có lịch riêng, rồi đợi công việc proxy/backend về 0. Warm-up được lưu CSV nhưng không trộn vào kết quả đo. Không reset EWMA sau warm-up.
- Cùng repeat dùng **cùng file lịch gửi**: request index, loại, path và thời điểm dự kiến giống nhau cho ba strategy. Seed đổi giữa repeat nhưng ghép cặp giữa strategy. Dùng thời gian monotonic.
- Chọn thứ tự strategy bằng seed rồi quay vòng qua các repeat. Với 3 repeat, mỗi strategy xuất hiện ở mỗi vị trí đúng một lần. Không chạy các strategy cạnh tranh CPU cùng lúc.
- Client gửi theo lịch định trước; tối đa 64 worker mặc định. Nếu worker không theo kịp, request bị trễ vẫn được ghi nhận. Không bỏ mẫu chậm để làm đẹp percentile; cột client lag cho biết giới hạn bộ tạo tải.
- Thăm dò telemetry giống nhau cho mọi strategy, khoảng 0.5 giây mặc định. Các HTTP telemetry call có overhead thật, không loại trừ overhead đó khỏi số đo của riêng một strategy.

## CSV để vẽ biểu đồ

| File | Dữ liệu |
|---|---|
| `summary.csv` | Mỗi strategy/scenario/repeat: RPS, avg/p50/p95/p99, errors, error_rate, client lag, lỗi warm-up |
| `aggregate.csv` | Median/min/max qua các repeat; median của percentile từng lượt, **không phải percentile của tập gộp** |
| `backends.csv` | Delta received/dispatched/completed/errors; success responses; mean/max CPU, heap, active/queued/inflight từng backend |
| `request_types.csv` | Avg/p50/p95/p99 và lỗi tách light/slow/CPU để không che khuất một loại request |
| `schedule-*.csv` | Lịch gửi gốc, dùng chung cho ba strategy trong cùng scenario/repeat |
| `<run>/requests.csv` | Từng request: planned/sent/completed time, status, loại, backend trả lời, latency và client lag |
| `<run>/telemetry.csv` | Chuỗi thời gian backend active/queue, proxy inflight, CPU/heap, latency EWMA/score, health và tuổi metric |
| `<run>/summary.csv` | Một dòng kết quả của run: chỉ số tổng thể và các cột `backend_1_*` đến `backend_3_*`, gồm request count, active/inflight, CPU và heap |
| `<run>/boundaries.csv` | Counter trước/sau cửa sổ đo sau khi đã drain; dùng tách warm-up và đo |
| `<run>/warmup.csv` | Mọi request warm-up, kể cả lỗi |

Mỗi run còn có config thực dùng và log JVM. `metadata.json` lưu runtime, OS/Python/logical CPU, seed/tham số/checksum. Snapshot source cùng classes nằm tại `source/`, `classes/` trong output; có thể giữ khi đóng gói artifact, không cần commit file class.

## Định nghĩa chỉ số và hạn chế

**Latency chính** = thời điểm nhận xong response − thời điểm gửi **dự kiến**. Vì vậy bao gồm cả client lag. `request_latency_ms` tính từ lúc thực gửi để phân biệt chậm ở client với chậm ở proxy/backend. Percentile dùng nearest-rank. Avg/p50/p95/p99 chính chỉ tính response 2xx, đi cùng error_rate; các cột `all_outcomes_*` tính cả request lỗi/timeout để không che failure. Nếu không có success thì percentile để trống, không giả bằng 0.

`success_rps = số response 2xx / max(cửa sổ gửi dự kiến, thời điểm completion cuối)`. `completed_rps` gồm cả lỗi. Không dùng request bị reject nhanh để tuyên bố throughput thành công cao. `client_lag_warning` bật nếu p95 lag lớn hơn max(20 ms, khoảng cách hai request); đây là cờ cần kiểm tra, không phải kiểm định thống kê.

**Số request backend nhận** lấy delta `receivedRequests`, tính cả request bị backend từ chối và không tính health/metrics/control. `dispatched_requests` là proxy đã cấp lease; có thể lớn hơn received nếu connect thất bại. `successful_client_responses` là response thành công định danh được backend; không đánh đồng với số request nhận. Không suy đoán backend cho response 503 chưa định tuyến.

**Active/connections:** `backend_active` là đang thực thi, `backend_queued` là đang đợi; `proxy_inflight` là request có lease. Với một upstream socket/request, inflight gần số kết nối bận nhưng còn bao gồm giai đoạn connect. Chưa đo TCP connection idle/OS độc lập. Mean/max là trên các mẫu lấy định kỳ, có thể bỏ lỡ spike giữa hai mẫu; snapshot proxy và backend không đồng thời tuyệt đối.

**CPU/RAM:** chỉ xuất CPU process khi backend đã có mẫu CPU time hợp lệ (`cpuSamples>0`). Có cả occupied cores và ratio so với CPU budget; budget không tạo quota. Heap bytes/ratio là JVM heap, không phải RSS hay RAM riêng của server. Host RAM chỉ là bối cảnh chung, không cộng ba lần thành tổng RAM backend. Không xuất host CPU vì code hiện tại có thể biến giá trị unavailable thành 0. Polling/JIT/GC cũng tiêu tốn CPU và được tính trong CPU tiến trình.

Phép đo trên một laptop, với artificial delay và vài repeat, chỉ cho bằng chứng về cấu hình này. Cần chạy dài hơn, thêm mức tải và kiểm tra client lag/độ dao động trước khi khái quát. Không gộp các lượt chạy khác seed/rate/máy thành một kết luận thắng tuyệt đối.

## Artifact schema 2 và fault smoke

Runner kiểm tra count/rate/concurrency/repeat/warmup dương, rate/sample interval hữu hạn, interval >= 0.05 giây; scenario không trùng, JDK đúng 21 và output phải là thư mục mới. Helper summarize hỗ trợ empty rows (count/RPS=0, latency trống); timing NaN/Infinity/âm bị từ chối. CSV/JSON không ghi số NaN/Infinity. OS telemetry không hữu hạn là unknown, không tự đổi thành zero.

Metadata thêm commit Git, trạng thái dirty, schema version và giới hạn máy chung. Snapshot source/classes/config và **runner/verifier** được lưu cùng hash. `complete.json` chỉ được viết sau khi mọi cluster đóng, chứa manifest SHA-256 artifact. Đây là kiểm tra toàn vẹn, không phải chữ ký chống giả mạo. Verifier kiểm tra file/hash, tập run đầy đủ, schedule tái tạo từ seed, warmup, summary tính lại từ requests, backend summary/counters tính lại từ boundaries+telemetry và boundary đã drain. Verifier vẫn hoạt động với `python -O`.

Phiên bản runner phải khớp để tính lại thống kê. Artifact cũ cần script tương ứng; schema 2 có thể dùng script đóng băng:

```powershell
python benchmarks/results/my-run/verify_benchmark.py benchmarks/results/my-run
```

Summary mới thêm median thực (khác nearest-rank p50 khi số mẫu chẵn), min/max, sample stddev, coefficient of variation, all-outcome mean và timeout count (504 hoặc TimeoutError client). Một mẫu thì stddev để trống. Aggregate bổ sung mean/sample stddev qua repeat. Chưa xuất confidence interval: request trong một run có tương quan, vài repeat không đủ để tuyên bố độ tin cậy; không tạo interval giả từ hàng nghìn mẫu phụ thuộc nhau.

Ba scenario mới **opt-in**, không thay A/B/C mặc định:

```powershell
python scripts/benchmark.py --quick --requests 12 --warmup-requests 6 --rate 30 --concurrency 4 --scenarios timeout errors recovery --output benchmarks/results/fault-smoke
```

- `timeout`: `/slow`, read timeout 300 ms / deadline upstream 500 ms giống nhau cho ba strategy. Failure là kết quả mong đợi; kiểm tra accounting/cleanup, không xếp hạng hiệu năng.
- `errors`: tỷ lệ light/429/503 cố định qua `/status?code=429|503` của fixture; lỗi trả nhanh không vào latency thành công.
- `recovery`: health backend 1 DOWN tại 25% và UP tại 65% cửa sổ gửi, ghi thời điểm thực ở `events.csv`; không kill/restart JVM nên không reset counter. Smoke quá ngắn có thể không đủ cho health poll thấy toàn bộ outage. Đo recovery cần tăng count/thời lượng. Crash/restart thật còn cần xử lý counter reset riêng.

Mixed vẫn 60/10/30. Slow upload/disconnect được kiểm tra bằng Java socket regression, chưa thêm vào benchmark so sánh. Chưa đo RSS, GC pause, connection reuse hoặc confidence interval. Admission/routing vẫn được báo chung kèm error rate và success RPS.

Ctrl+C/exception hủy job chưa chạy, đánh thức worker đang đợi lịch, chờ request đang chạy hết timeout, join sampler rồi dừng đúng các JVM đã tạo và đóng log. Lượt bị ngắt không có complete.json và bị verifier từ chối. Port động có cửa sổ bind race giữa chọn port và JVM bind; startup lỗi được báo và cluster cleanup, không âm thầm đổi workload.
