# Adaptive: estimated completion time — Mixed

## Giả thuyết chốt trước benchmark AFTER

BEFORE là `benchmarks/results/official-20260927`, chỉ lấy Mixed, đủ 3 repetitions. Adaptive có error rate 19,08–24,25%, queue trung bình trên 9 backend/run là 10,51 request, queue max 28, heap trung bình 8,7%. Latency trước đây trộn cả ba loại request và thời gian chờ queue. Do đó cùng một inflight count chưa diễn tả số millisecond công việc còn lại.

Giả thuyết: ước lượng service theo route, giữ chỗ công việc đồng bộ và admission theo completion deadline sẽ giảm queue/timeout. Không giả định throughput hoặc error rate tổng chắc chắn cải thiện: 503 sớm vẫn là lỗi; request slow có thể bị từ chối nhiều hơn. Báo cáo cả phân phối, lỗi và latency theo loại request. Không tăng timeout hoặc đổi capacity/rate/client/seed để giúp thuật toán.

## Thiết kế và công thức

Chỉ Adaptive dùng mô hình mới. RR và LC giữ nguyên code selection và giới hạn inflight. `ProxyHandler` truyền URI vào Dispatcher; API HTTP không đổi. Không thêm dependency, queue trung tâm hay retry.

- Bốn nhóm cố định: `/work` (LIGHT), `/slow` (SLOW), `/compute` (CPU), các route còn lại (OTHER). Không giữ map theo URL/query vô hạn. Chỉ `/work` và `/compute` dùng `cost` 1..20 của demo; request không hợp lệ vẫn được backend kiểm tra như cũ. `/slow` không nhân cost vì backend luôn ngủ 2 giây.
- Với backend b, nhóm r và units u: `service(b,r,u) = ewmaPerUnit(b,r) × u`. Mẫu là upstream RTT/u của request thành công được cấp khi còn slot và không thấy tải ngoài proxy. EWMA dùng alpha cấu hình hiện có: `Enew = Eold + alpha × (sample − Eold)`. Cold start dùng `adaptive.initial.latency.ms` chung, không hardcode slow=2000ms trong proxy.
- Không học từ request đã dự kiến phải xếp hàng, HTTP lỗi, transport failure hoặc lease thuộc generation cũ. Đây vẫn là service **ước lượng**: RTT gồm TCP/HTTP overhead và có thể lẫn queue do telemetry/scheduling sai số.
- Mỗi backend giữ danh sách reservation FIFO. Số slot dự kiến `c=max(1,floor(capacity×warmup))`. Mô phỏng slot rảnh sớm nhất bằng priority queue. Công việc đang chạy giảm theo thời gian đã qua tính từ thời điểm bắt đầu dự kiến; công việc còn xếp hàng giữ nguyên chi phí. Lease chưa hoàn thành giữ ít nhất `min(initialLatency, service)` millisecond, không biến mất chỉ vì quá thời gian dự đoán. Chi phí reservation đang sống chỉ tăng nếu EWMA học được service cao hơn.
- Tải ngoài proxy dùng `externalOutstanding × genericServiceEWMA`, chia cho c để cộng vào thời gian chờ. Chỉ dùng khi telemetry còn mới; đây là xấp xỉ vì không biết route của request đi trực tiếp backend.
- `ECT = earliestSlotWait + service(newRequest)`.
- Ngân sách `B = min(proxy.read.timeout.ms, proxy.request.timeout.ms)` = 3000ms trong phép đo. Chỉ nhận khi `ECT ≤ B` và `outstandingWork + service(newRequest) ≤ c × B`, đồng thời vẫn thỏa health/inflight/slow-start cũ. Nếu không còn backend phù hợp, trả 503 ngay; không chuyển sang queue proxy hoặc tăng timeout.
- Ranking = `ECT × resourcePenalty`. Giữ nguyên toàn bộ hệ số CPU/memory/error/uncertainty cũ; chỉ thay thành phần RTT chung/count bằng ECT theo request. Thăm dò theo khoảng cấu hình cũ chỉ chọn trong tập đã qua admission.

`scoreMs`/`latencyEwmaMs` trong monitoring vẫn là score/RTT tổng hợp cũ để đối chiếu; **không phải ECT của mọi route**. ECT phụ thuộc URI của request mới. Monitoring bổ sung `estimatedOutstandingWorkMs`, `estimatedQueuedWorkMs`, `estimatedNextSlotMs`, `completionBudgetMs` cho Adaptive, cùng counter proxy `estimatedWorkRejected`. Benchmark ghi các estimate backend vào telemetry và mean/max vào summary; RR/LC để trống các cột không áp dụng.

Một `ReentrantLock` của Dispatcher bảo vệ select + reserve, EWMA, danh sách work, health và hoàn tất lease. Không I/O dưới khóa. `complete`/`close` idempotent nên không trừ work hai lần. Khi generation đổi, bỏ lịch sử EWMA cũ nhưng giữ reservation còn sống đến khi chủ lease giải phóng; completion cũ không được train backend mới hồi phục. Các test dùng virtual threads và clock giả kiểm tra tính nguyên tử.

## HTTP test

`mvn clean verify` trước sửa đã PASS, nên không tái hiện được lỗi được báo tại `HttpTransportTest.java:220`. Tuy nhiên test có race trong giả định: nhận xong response trước không đảm bảo handler trước đã chạy finally và trả permit (test giới hạn inflight=1). Test mới retry có hạn **chỉ** response 503 chứa `Proxy capacity exhausted` khi chờ permit trước đó. Sau khi được nhận, nó vẫn buộc nhận 413 khi chỉ gửi header, giữ socket mở để kiểm tra permit được thu hồi, và kiểm tra oversized request không dispatch backend. Không đổi xử lý body hoặc timeout production, không disable test.

## Kế hoạch đo cố định

Java 21 Microsoft 21.0.12.1; cùng application.properties với BEFORE (khác CRLF/LF khi snapshot, giá trị giống nhau). Mixed vẫn 3 backend 4 slot/20ms, 60% light / 10% slow / 30% CPU. Chính thức: 1200 measured + 300 warm-up, 60 RPS, 64 client workers, seed 20260927, 3 repetitions, sampling 0.5s, cùng thứ tự strategy xoay vòng theo seed. Smoke duy nhất: `--quick --scenarios mixed`, sau khi Maven PASS; nếu pipeline PASS mới chạy chính thức. Không benchmark A/B hoặc thêm thí nghiệm khác.

## Hạn chế biết trước

Không có thông tin thời điểm backend thực bắt đầu từng request nên thứ tự FIFO/slot chỉ gần đúng; virtual threads và TCP có thể đảo thứ tự. Học từ mẫu không queue giúp tránh phản hồi khuếch đại queue nhưng dễ thiếu mẫu khi bão hòa kéo dài. Cold-start và recovery phải học lại; metadata cost chỉ có ý nghĩa với workload hiện tại. CPU/GC/tải ngoài có thể làm service thay đổi. Tổng riêng slow ở 60 RPS đã đòi khoảng `60×10%×2s = 12` slot, bằng cả 3×4 slot trước khi cộng light/CPU: admission không tạo thêm công suất. Vì vậy phải xem lỗi/phân phối riêng slow, không chỉ latency của các response thành công. BEFORE và AFTER chạy vào hai thời điểm trên cùng máy, nên vẫn có nhiễu host; không diễn giải chênh lệch của RR/LC thành tác dụng từ Adaptive.

## Kết quả thực đo

Artifacts giữ riêng, không ghi đè BEFORE:

- [BEFORE gốc](../benchmarks/results/official-20260927/summary.csv).
- [AFTER summary](../benchmarks/results/ect-mixed-official-20260927/summary.csv), [so sánh đầy đủ](../benchmarks/results/ect-mixed-official-20260927/COMPARISON.md).
- [CSV tổng hợp trước/sau](../benchmarks/results/ect-mixed-official-20260927/before-after.csv), [từng repetition](../benchmarks/results/ect-mixed-official-20260927/before-after-runs.csv), [từng loại request](../benchmarks/results/ect-mixed-official-20260927/before-after-types.csv).
- [Script tái tạo bảng từ CSV](../benchmarks/results/ect-mixed-official-20260927/compare.py): chỉ đọc BEFORE/AFTER, không chạy server. Nó kiểm tra checksum source, Java, tham số, config, schedule đo/warm-up và vị trí strategy trước khi tổng hợp.

Các chỉ số latency/RPS/error bên dưới là **median của 3 run**, không phải percentile gộp. Queue/inflight là trung bình của 9 giá trị mean backend/run; đỉnh là max trên mẫu telemetry.

| Adaptive Mixed | BEFORE | AFTER |
|---|---:|---:|
| Success RPS | 42,44 | 50,20 |
| Error rate | 19,83% | 6,92% |
| Average successful latency, ms | 883,69 | 477,75 |
| p95, ms | 2212,97 | 2013,02 |
| p99, ms | 2704,67 | 2640,67 |
| Backend queue mean / peak | 10,51 / 28 | 6,73 / 28 |
| Proxy inflight mean / peak | 13,57 / 33 | 9,31 / 32 |
| Backend 1 / 2 / 3 received, tổng 3 run | 1256 / 1054 / 1165 | 839 / 1072 / 1645 |
| Client lag p95 median, ms | 161,80 | 127,33 |

Success RPS tăng 18,29%, avg latency giảm 45,94%, queue mean giảm 35,99%. Cả 3 run AFTER (48,48–51,69 RPS; 81–94 lỗi/run) tốt hơn các run BEFORE tương ứng (40,18–42,82 RPS; 229–291 lỗi/run) về throughput và số lỗi. Nhưng **không cải thiện đồng đều**:

| Adaptive: thành công / số gửi | BEFORE | AFTER |
|---|---:|---:|
| Light | 1775 / 2160 | 2121 / 2160 |
| CPU | 876 / 1080 | 1048 / 1080 |
| Slow | 191 / 360 | 173 / 360 |

Slow error rate tăng 46,94% → 51,94%; successful average slow latency tăng 2198,94 → 2256,41ms. Tổng 504 tăng 111 → 130 (riêng slow 104 → 125). Ngược lại 503 giảm 647 → 128, giúp lỗi tổng giảm. Admission theo work từ chối 44 request; 3556 request còn lại thực sự tới backend, khớp tổng received. Vì vậy không thể diễn giải toàn bộ cải thiện là do từ chối sớm, cũng không thể che vấn đề slow bằng p95 tổng.

AFTER cùng lượt đo: RR median 41,41 RPS / 21,92% lỗi; LC 44,29 RPS / 16,58% lỗi. Adaptive tốt hơn hai đối chứng về chỉ số tổng trong ma trận Mixed này. RR/LC vẫn dao động dù code selection không đổi: BEFORE/AFTER chạy khác thời điểm trên một host; BEFORE còn xen A/B trước Mixed, AFTER chỉ chạy Mixed. Không suy rộng thành kết luận phổ quát hoặc quan hệ nhân quả đã kiểm soát hoàn toàn.

### Điều dữ liệu cho phép giải thích

1. Queue mean và inflight giảm cùng với latency light/CPU (avg theo loại: light 810,76 → 368,00ms; CPU 803,64 → 389,56ms). Điều này phù hợp giả thuyết phân biệt service cost giúp công việc ngắn tránh một phần hàng đợi dài. Phân phối received lệch hơn sau thay đổi, không phải cân đều số request; xem CSV từng run để tránh hiểu tổng ba lượt như một backend cố định luôn khỏe hơn.
2. Slow vẫn giữ slot 2 giây trong backend không đổi, chỉ còn khoảng 1 giây trước read timeout 3 giây. Các 504 slow AFTER có request latency median 3016,90ms; đỉnh queue vẫn 28. Dữ liệu cho thấy admission/ước lượng hiện tại chưa ngăn được hàng đợi gây timeout cho slow. Đây là regression thực, không được coi là thành công chỉ vì số lỗi tổng thấp hơn.
3. Telemetry AFTER vẫn thấy 77 mẫu WARMING và 50 mẫu DOWN trên 381 mẫu Adaptive (BEFORE 241/82 trên 387). Code hiện hành coi transport timeout là DOWN rồi hồi phục; estimator mới xóa EWMA theo health generation. **Suy luận cần kiểm chứng thêm**, không phải kết luận đã đo trực tiếp: vòng timeout/recovery cùng việc chỉ học mẫu không queue có thể làm một số route lâu ở prior thấp và đánh giá thiếu work slow. CSV chưa có service estimate trên từng quyết định nên chưa tách được ảnh hưởng này khỏi sai số FIFO, telemetry hoặc host scheduling.

Không chỉnh thêm thuật toán/tham số sau khi thấy số liệu, không chạy thêm đối chứng để chọn kết quả đẹp.

## Kiểm chứng đã chạy

```powershell
$env:JAVA_HOME = 'C:/Users/admin/AppData/Local/Temp/pbl4-audit-tools/jdk21/jdk-21.0.12.1+1'
& 'C:/Users/admin/AppData/Local/Temp/pbl4-audit-tools/apache-maven-3.9.16/bin/mvn.cmd' clean verify
python -m unittest discover -s scripts -p test_benchmark.py
python scripts/benchmark.py --quick --scenarios mixed --java "$env:JAVA_HOME/bin/java.exe" --seed 20260927 --rate 60 --concurrency 64 --sample-interval 0.5 --output benchmarks/results/ect-mixed-smoke-20260927
python scripts/verify_benchmark.py benchmarks/results/ect-mixed-smoke-20260927
python scripts/benchmark.py --java "$env:JAVA_HOME/bin/java.exe" --requests 1200 --warmup-requests 300 --rate 60 --concurrency 64 --seed 20260927 --repeats 3 --sample-interval 0.5 --scenarios mixed --output benchmarks/results/ect-mixed-official-20260927
python scripts/verify_benchmark.py benchmarks/results/ect-mixed-official-20260927
python benchmarks/results/ect-mixed-official-20260927/compare.py
```

Maven `BUILD SUCCESS`: 17 Dispatcher scenarios (gồm test estimator, admission, 64 virtual threads giữ chỗ và release đúng một lần), HealthChecker test, 60 HTTP checks. Test HTTP còn được chạy riêng 5 lần liên tiếp với `java -ea -cp 'target/classes;target/test-classes' com.example.proxy.HttpTransportTest`, đều PASS. 8 Python unit tests PASS. Surefire bỏ qua theo cấu trúc test main có sẵn; AntRun thực sự chạy các Java test trên, không disable test.

Đúng một smoke invocation Mixed: 3 run, 360 measured requests, validator PASS. Một invocation chính thức: 9 run, 10800 measured requests, validator PASS. Telemetry đầy đủ; schedule đo/warm-up và config BEFORE/AFTER khớp. Không chạy lại benchmark sau khi tổng hợp. Hai lỗi kiểu dữ liệu/encoding trong script sinh báo cáo đã sửa khi đọc CSV; không ảnh hưởng dữ liệu đo hoặc số lần benchmark.
