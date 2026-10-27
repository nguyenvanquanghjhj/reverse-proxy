# Kiểm định readability refactor — 26/10/2026

Nhánh `refactor/defense-readability`; commit gốc
`6550f5c01e2fc11f62074e59fae984e4ef1c486c`, working tree ban đầu sạch.
Giữ nguyên package, kiến trúc đồng bộ, endpoints và dữ liệu benchmark công bố.
[FINAL_VERIFICATION](FINAL_VERIFICATION.md) là kiểm định lịch sử, không thay bằng
kết quả của lần này.

## Baseline trước khi sửa Java main

Windows; Microsoft OpenJDK **21.0.12.1+1**, Python **3.12**. JDK dùng từ
`.demo/tools/jdk21-presentation/jdk-21.0.12.1+1`; chỉ thêm vào PATH của tiến trình.

| Kiểm tra đã thực thi | Kết quả |
|---|---|
| `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test.ps1` trên JDK 21 | PASS, 13 test mains; log `target/baseline-jdk21.log`, bản sao reports `target/baseline-jdk21-reports/` |
| `python -X utf8 -m unittest discover -s scripts -p 'test_*.py'` | PASS, 24 tests; log `target/baseline-python.log` |
| `python tools/defense_artifacts.py --verify benchmarks/defense/20261021` | PASS, SHA/archive/frozen validators/bảng công bố |
| `python tools/check_reports.py --include-defense` | PASS trên baseline, trước khi thêm tài liệu |
| Probe HTTP thật: RR 6 bước, LC có Slow đang giữ lease, health DOWN/UP, Adaptive `/work?cost=2` | PASS; [bằng chứng riêng](assets/readability-20261026/capture.json), [trace](assets/readability-20261026/decision.json) |
| `ProxyContractTest` chạy riêng trên Java main chưa sửa | PASS: thứ tự validation, management, status/error/HEAD, JSON/Prometheus chính xác và cleanup |
| 2.700 bước scheduler với seed `20261026`, clock inject, ba strategy | SHA-256 output baseline `f1b93fdb49772e638afad2906c6cb21383a39d3bc0fa7925dc7ee192c5dbbcfe` |

Đã thử thêm JDK **26.0.2.1** trước khi tìm được JDK 21 cục bộ: **FAIL 1/13**,
`HttpTransportTest` báo `oversized body rejected before upload: null` trong fixture
có deadline 600 ms; log `target/baseline-java.log`. JDK 21 sau đó PASS toàn bộ.
Không sửa timeout, nới assertion hoặc gán kết quả JDK 21 cho JDK 26.

Maven không có trên PATH; GitHub Actions cho nhánh local chưa chạy. Các run CI
trong tài liệu lịch sử không xác minh các thay đổi local này.

## Phạm vi

Ưu tiên bốn tài liệu ngắn và golden contracts, sau đó tách helper validation,
metrics và scoring thuần. Giữ `ProxyHandler.sendError()` tại API cũ: các nơi gọi
đã dùng chung nó, không cần thêm lớp error response chỉ để chuyển một hàm.
Giữ parser/socket trong `UpstreamTransport` và fixture `DemoBackendServer` để
tránh mở rộng phạm vi thay đổi framing, timing và workload trước bảo vệ.

## File và trách nhiệm sau refactor

| File thêm/sửa | Trách nhiệm / thay đổi |
|---|---|
| [ProxyHandler.java](../src/main/java/com/example/proxy/handler/ProxyHandler.java) | Còn 276 dòng từ 355; điều phối ID, owner, admission, quota, deadline, lease, transport và downstream. Các public method, error JSON và security headers vẫn ở API cũ. |
| [IncomingRequestValidator.java](../src/main/java/com/example/proxy/handler/IncomingRequestValidator.java) — mới, 76 dòng | Kiểm metadata đã qua JDK parser, trả `Rejection(status,message)` hoặc null; không I/O, không gửi response. Forwarded request giữ target → framing → headers; management chỉ framing → headers. |
| [ProxyRequestMetrics.java](../src/main/java/com/example/proxy/handler/ProxyRequestMetrics.java) — mới, 57 dòng | Sở hữu LongAdder, phân loại kết quả và xuất JSON/Prometheus; không sở hữu permit, timer hay lease. Supplier giữ vị trí lấy mẫu owner/quota trong export. |
| [AdaptiveCandidateScorer.java](../src/main/java/com/example/proxy/loadbalancer/AdaptiveCandidateScorer.java) — mới, 19 dòng | Chỉ tính external work còn fresh, resource penalty và ECT × penalty từ snapshot/forecast; giữ nguyên biểu thức và thứ tự số thực. |
| [Dispatcher.java](../src/main/java/com/example/proxy/loadbalancer/Dispatcher.java) | Gọi scorer; chú thích sáu đoạn acquire, vẫn một ReentrantLock cho mọi state, selection/reservation/completion/health generation. Work budget và relearning tiếp tục ở đây. |
| [AdaptiveLoadBalancer.java](../src/main/java/com/example/proxy/loadbalancer/AdaptiveLoadBalancer.java) | Chỉ sửa chú thích: đây là bộ chọn theo score/exploration, không tự forecast/học ML. |
| [EstimatedWork.java](../src/main/java/com/example/proxy/loadbalancer/EstimatedWork.java) | Chỉ sửa chú thích vai trò và ví dụ tính service/wait/ECT; không sửa mô hình FIFO/EWMA hoặc công thức. |
| [ProxyContractTest.java](../src/test/java/com/example/proxy/ProxyContractTest.java) — mới | Khóa precedence validation, management riêng, error/HEAD schema, security headers, counter JSON/Prometheus chính xác, permit/socket/timer cleanup. Chạy qua public handler API, không gọi helper mới. |
| [SchedulerParityTest.java](../src/test/java/com/example/proxy/SchedulerParityTest.java) — mới | So SHA-256 transcript 2.700 bước deterministic với baseline gốc: selection, snapshot, trace, Prometheus của ba strategy, health/telemetry/complete/close/drain. Khi lệch ghi transcript để chẩn đoán. |
| [README.md](../README.md) | Dẫn bốn tài liệu ngắn ở đầu và chỉ rõ kiểm định refactor riêng. |
| [START_HERE.md](START_HERE.md), [REQUEST_WALKTHROUGH.md](REQUEST_WALKTHROUGH.md) — mới | Sơ đồ năm tầng, method thật, `/work?cost=2`, giải thích thứ tự lease và trace thật. |
| [DEFENSE_QA_SHORT.md](DEFENSE_QA_SHORT.md), [DEMO_5_MINUTES.md](DEMO_5_MINUTES.md) — mới | 15 câu hỏi có code/test; bốn tình huống demo, cleanup và khôi phục nguyên byte cấu hình. |
| `docs/assets/readability-20261026/{decision.json,response.json,capture.json,ADAPTIVE.properties}` — mới | Chỉ là bằng chứng HTTP local baseline, có commit/config/thời điểm; không nằm trong bundle benchmark công bố. |
| `docs/READABILITY_REFACTOR.md` — mới | Báo cáo hiện tại, phân biệt baseline, fail trung gian, pass cuối và phần chưa chạy. |

## Các bất biến đã đối chiếu

Không chủ đích thay behavior HTTP, headers/status/error, quota, timeout, counter,
trace, thuật toán, tie-break hoặc lifecycle. Helper đều package-private, không mở
thêm public API. `ProxyHandler` vẫn gọi `Lease.complete()` trong try-with-resources
sau upstream exchange, trước downstream; close lặp không giải phóng lần hai.

`acquire()` được chú thích theo **thứ tự source thật**: check draining → snapshot
eligibility → Adaptive budget trong vòng lặp → select → trace trước reservation
→ reserve nguyên tử. Không đẩy trace xuống sau reserve chỉ để khớp sơ đồ đề xuất.
Không tách map, clock, circuit, generation hoặc lock khỏi Dispatcher; không I/O
dưới lock. Không tạo thêm `AdmissionPolicy` để chuyển một biểu thức ngắn.

Golden scheduler được tạo từ Java main gốc trước refactor, seed `20261026` và
clock inject; chuỗi dùng UTF-8 và newline `\n` cố định. Cùng checksum sau refactor
kiểm cả kết quả chọn, trace và state xuất ra, không dùng timing thật làm golden.
Để xem thay đổi có chủ đích sau này, có thể chạy chính test đó với main classes
biên dịch từ commit gốc rồi đối chiếu transcript; không cập nhật hash chỉ để hết lỗi.
Socket integration suites vẫn cần thiết cho parser, timeout và concurrency thật.

## Kiểm tra sau từng phần

| Phần | Java JDK 21 | Python | Artifact/report |
|---|---|---|---|
| Tài liệu + khóa baseline (`bcea65e`) | 13 mains baseline + hai golden mains chạy riêng PASS | 24 PASS | PASS |
| Validation (`5493e88`) | 15 mains PASS | 24 PASS | PASS |
| Metrics (`b1367d4`) | Lượt đầu 14/15; build lại toàn bộ: **15 PASS** | 24 PASS | PASS |
| Scorer + chú thích Dispatcher/Adaptive (`2d8c2a3`) | **15 mains PASS**, gồm golden 2.700 bước khớp baseline | **24 PASS** | PASS |

Fail trung gian ở metrics: file `SchedulerParityTest.class` bị ghi đè sau khi tôi
sửa test trong lúc suite đang chạy. `javap` quan sát major version **55** (Java 11)
và class chứa `Unresolved compilation problems`, trong khi `javac --release 21`
phải tạo major **65**. Build lại, giữ source yên trong suốt test, xác nhận major 65
và chạy toàn bộ 15 mains thành công; không sửa thuật toán hoặc bỏ test. Log:
`target/readability-metrics-java.log`, `target/metrics-scheduler-overwritten.log`,
`target/readability-metrics-java-rerun.log`.

Logs cuối: `target/readability-final-java.log`, `target/readability-final-python.log`;
XML và log từng suite trong `target/test-reports/`. Sau lượt này, một probe dùng
`target/classes` lại gặp class chứa `Unresolved compilation problem` dù source
không đổi. JAR đã build vẫn có đủ **53 class major 65**, không chứa problem stub;
đối chiếu từng class trong JAR với build frozen độc lập của smoke: **tất cả giống
nguyên byte**. Đã trả output classes về bản build đúng và chạy lại hai golden
mains qua JAR: PASS. Không sửa cấu hình IDE của người dùng.

JAR SHA-256 của lượt xác minh:
`1d53d034a4be17b0ed7d0c86ba98b537aa9d5dfd4c40c26b174c52b2504ee463`.
Probe runtime cuối dùng JAR này thay vì class directory có thể bị ghi đè.
**PASS** trên JAR cuối: RR đúng sáu bước, LC tránh backend đang giữ Slow, health
DOWN/phục hồi loại rồi đưa B2 về routing, `/work?cost=2` HTTP 200 và decision có
backend khớp response. Log/config/JSON thật tại `target/readability-final-jar/`.

Đã chạy **nguyên khối PowerShell trong DEMO_5_MINUTES.md** trên một bản sao riêng
ở `target/readability-demo-rehearsal`, sau phần metrics: RR 6 bước đúng; LC giữ Slow
rồi gửi Light; Adaptive trả trace bật; health loại DOWN rồi B2 nhận request lại sau
WARMING/UP. Đối chiếu cấu hình khôi phục nguyên byte và bốn cổng 8080/9001–9003 đã
được trả. Lượt đầu sandbox chặn `Get-CimInstance Win32_Process` (script kiểm định
chủ sở hữu PID); chạy lại ngoài sandbox thành công, giữ nguyên kiểm tra an toàn
PID của script. Log `target/readability-demo-rehearsal-rerun.log`.

Smoke riêng, JDK 21, không thay dữ liệu công bố:

```powershell
python -X utf8 scripts/benchmark.py --java "$env:JAVA_HOME/bin/java.exe" --quick --requests 12 --warmup-requests 6 --scenarios homogeneous --output benchmarks/results/readability-smoke-20261026
python -X utf8 scripts/verify_benchmark.py benchmarks/results/readability-smoke-20261026
```

**PASS: 3 runs, 36 request đo, 0 lỗi**, hashes/schedules/counters/statistics khớp.
Output là thư mục mới, ignored; metadata ghi commit `b1367d4`, `git_dirty=true`
vì scorer chưa commit lúc đóng băng source. Log `target/readability-smoke.log`.
Không dùng RPS/p95 của smoke để so thắng/thua hoặc tuyên bố tăng hiệu năng.

## Phần không đổi và chưa thực hiện

`git diff` với commit gốc không có thay đổi ở `benchmarks/`, các chart/bundle cũ,
`DEFENSE_PERFORMANCE.md`, `FINAL_VERIFICATION.md` hoặc `config/application.properties`.
Không thêm pooling, retry POST, TLS, HTTP/2 hay framework. Không chạy lại thí nghiệm
để thay số liệu đã công bố hoặc kết luận hiệu năng tăng sau refactor.

Maven, GitHub Actions Windows/Ubuntu cho nhánh này: **chưa chạy**. Chưa push nhánh;
GitHub About chưa sửa vì phiên không có `gh`/connector GitHub đã kết nối. Mô tả
có thể dùng: “Java 21 HTTP/1.1 reverse proxy with RR, LC and explainable Adaptive
load balancing — PBL4.” Không tự thêm tên thành viên hoặc phân công chưa xác minh.

Thí nghiệm ablation routing/admission, fairness Slow, độ nhạy hệ số và nhiều host
là công việc tiếp theo, chưa được thực hiện trong refactor này. Bộ 15 câu hỏi và
câu trả lời gắn method/test ở [DEFENSE_QA_SHORT](DEFENSE_QA_SHORT.md).
