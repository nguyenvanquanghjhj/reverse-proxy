# Kiểm định cuối — 21/10/2026

PASS chỉ ghi cho lệnh đã thực thi. NOT TESTED là chưa thực thi trong vòng này;
cấu hình CI hoặc file test tồn tại không thay thế kết quả chạy.

## Phạm vi và phiên bản

Đầu lượt: `main`, `04ed8f8379781c35614f5f257e0c10a409873669`, sạch và khớp
`origin/main`; đã đọc status, log, diff và branch. Mã dùng đo:
**`c2d5f20335ecef34ad10c5ff5c2c8a48b7601e8d`**, worktree detached sạch ở
`.demo/defense-source`. Tài liệu/kết quả/công cụ đóng gói bổ sung sau commit đó;
không thay thuật toán hoặc cấu hình đo để làm đẹp kết quả.

Đã rà lại ProxyHandler, UpstreamTransport, Dispatcher, ba thuật toán,
EstimatedWork, BackendMetrics, HealthChecker, ReverseProxyServer,
BoundedRequestExecutor, ProxyConfig/ConfigLoader, RequestDeadline/Json, cấu hình,
Java/Python tests, runner/validator, CI và các tài liệu kiến trúc/audit/benchmark.
Các audit cũ giữ nguyên dưới phạm vi lịch sử; xem cả [CODEBASE_REVIEW](history/CODEBASE_REVIEW.md).

Máy kiểm tra: Windows 11, 16 CPU logic; Microsoft JDK **21.0.12.1+1**,
Python 3.12. Chạy Java bằng JDK 21 thật, không chỉ biên dịch `release 21` bằng JDK mới.

## Kết quả thực thi

| Phần | Trạng thái | Lệnh/bằng chứng thực tế |
|---|---|---|
| Build JDK 21 + Java regression | PASS | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test.ps1`; JAR tạo thành công, **12 test mains**, từng JVM có `-ea`, timeout 60s và XML trong `target/test-reports` |
| Python/validator/render | PASS | `python -X utf8 -m unittest discover -s scripts -p "test_*.py"`; **24 tests** gồm ZIP tests và từ chối frozen validator lỗi; thực sự vẽ sáu cặp SVG/PNG |
| Dữ liệu lịch sử | PASS | `python scripts/verify_benchmark.py benchmarks/results/presentation-mixed-20261021`; **9 matched runs, 10.800 requests**, bao gồm kiểm tra mới cho request_types và aggregate |
| Quyết định thích nghi | PASS | AdaptiveDecisionTest: điểm thấp nhất, thăm dò, DOWN, circuit, giữ chỗ, work budget, học lại, dừng, tắt trace, không đổi lựa chọn/accounting khi bật trace |
| HTTP và vòng đời | PASS | HttpTransport/Hardening/Lifecycle/CircuitBreakerHttp/ManagementListener: socket thật, framing, timeout, burst, body/header bound, downstream disconnect, cleanup, listener riêng |
| Bảo mật trang giám sát | PASS | GET/HEAD, POST 405; từ IPv4 không loopback trả 403 dù giả X-Forwarded-For; đọc dashboard không dispatch; quota nghiệp vụ không chặn quản lý |
| Trình duyệt thật | PASS | `python tools/check_local_demo.py --java <JDK21>/bin/java.exe --output .demo/final-browser-20261021`; Edge headless: 6 loại tác vụ, header chiến lược, lịch sử sau reload, trực tiếp/proxy, 3 máy chủ, RPS từ counter, trace, offline/cũ/kết nối lại; không lỗi JavaScript |
| Giả thuyết Slow có kiểm soát | PASS | `tools/probe_slow.py` chạy lại từ worktree c2d5f20 sạch, dùng đúng classes benchmark; `slow-probe/observations.json` trong ZIP ghi dirty=false, Slow lạnh 2040ms với prior 50ms, Slow kế tiếp 503 WORK_BUDGET, Light 200; có config/log/script |
| Ma trận nguồn sạch | PASS | `tools/run_defense_matrix.py --loads 30 60 90 --requests 300 --warmup-requests 100 --repeats 3`; **54 runs / 16.200 mẫu**, ba frozen validators đều PASS 18 runs / 5400 mẫu; cả ba metadata commit c2d5f20 và dirty=false |
| ZIP độc lập và bảng công bố | PASS | `python tools/defense_artifacts.py --verify benchmarks/defense/20261021`; ZIP **5.817.193 byte**, SHA256, giải nén tạm, 3×18 run mới + 9 lịch sử, tính lại toàn bộ matrix/request-types/status-breakdown khớp byte |
| Tái tạo biểu đồ dữ liệu thật | PASS | `generate_charts.py --source .../load-60 --scenario mixed ...`; raw PASS trước export, tạo 6 SVG/PNG + HTML/CSV/MD; bộ lịch sử vẽ lại từ đúng bundle cũ chỉ đổi nhãn, CSV đo không đổi; đã xem hình throughput thực |
| README/CSV/HTML và liên kết | PASS | `python tools/check_reports.py --include-defense`; tính lại summary từ bundle, kiểm hai bộ manifest/generator, SVG/PNG, bảng HTML/MD/CSV, README, bảng lịch sử PERFORMANCE_ANALYSIS và toàn bộ liên kết nội bộ README/docs |
| Diễn tập PowerShell cuối | PASS | `demo.ps1 stop/start/rr/strategy -Strategy LEAST_CONNECTIONS/adaptive/health/stop`; RR đúng 6 bước, LC /work và /slow 200; demo 120 mẫu RR 40/40/40, Adaptive 118/1/1, cả hai 0 lỗi; B2 DOWN→WARMING→UP, nhận traffic lại; restart ADAPTIVE đọc trace thật, stop đóng cổng |
| Bản xuất Git không có file ignored | PASS | Xuất toàn bộ index sang `.demo/final-index-20261021/`; chạy `check_reports.py --include-defense` và `defense_artifacts.py --verify ...` trong bản đó, đều PASS; không có results/JDK của máy tác giả |
| Đúng source đã đo | PASS | Đối chiếu từng Java main hiện tại với ba frozen source snapshots, chỉ chuẩn hóa CRLF/LF: tất cả bằng nhau; config kết thúc trace=false; worktree c2d5f20 vẫn sạch |
| Maven lifecycle trên máy này | NOT TESTED | Không có `mvn` trên PATH và đường Maven Temp cũ đã mất. Build/test độc lập bằng JDK đã chạy; không gán kết quả Maven lịch sử cho phiên bản này |
| GitHub Actions Windows/Linux | PASS | Commit `264d1c5`: cả `windows-latest` và `ubuntu-latest` success tại [run 39633008763](https://github.com/nguyenvanquanghjhj/reverse-proxy/actions/runs/39633008763); Maven clean verify, 24 Python tests, report/ZIP validation, diff check và homogeneous smoke đều thành công |
| macOS, nhiều host, tải dài, LAN với máy khách riêng | NOT TESTED | Linux đã kiểm chứng bằng CI, chưa đo hiệu năng nhiều host; chỉ kiểm tra HTTP IPv4 cục bộ không loopback, không gọi đó là demo hai máy |

Các lần FAIL trong quá trình kiểm tra đã sửa và chạy lại: script PowerShell bị
ExecutionPolicy chặn khi gọi trực tiếp (dùng Bypass riêng tiến trình, không đổi
chính sách hệ thống); fixture ZIP cần giữ `orig_filename` trên Windows để kiểm
tra dấu phân cách gốc. Kết quả cuối Python cho hai ZIP tests là PASS, không bỏ test.

## Lỗi và thiếu sót thực sự đã xử lý

1. **Bảng dẫn xuất chưa được validator đối chiếu:** trước đây hash hợp lệ vẫn có
   thể chứa request_types/aggregate tính sai nếu manifest cũng được cập nhật.
   Nay tính lại count/error/avg/p50/p95/p99 theo loại và mean/stddev/median/min/max
   giữa các run từ raw đã kiểm tra, kiểm tra đủ/không trùng nhóm; có corruption tests.
2. **CI bỏ sót kiểm thử trình bày:** pattern chỉ chạy test_benchmark.py. Nay chạy
   toàn bộ `test_*.py`, cài Matplotlib chỉ cho phần kiểm thử chart; thêm đối chiếu
   report và verify archive. App Java/benchmark không thêm dependency.
3. **Hướng dẫn phụ thuộc đường Temp đã mất:** sửa JDK demo sang đường có thật;
   bỏ câu lệnh Maven Temp không tồn tại. Không tuyên bố Maven mới đã chạy.
4. **Nhãn hiển thị pha tiếng Anh:** Việt hóa trạm thử yêu cầu, nhãn thuật toán,
   biểu đồ/report và sơ đồ; giữ API/header/route/khóa JSON và tên riêng ổn định.
   Giữ lịch sử phiên cũ bằng chuyển nhãn mode trước khi validation.
5. **Thiếu màn hình quan sát liên tục:** thêm trang local nhỏ dùng metrics thật,
   báo cũ/mất kết nối, RPS có nguồn và mẫu số rõ; kiểm chứng bằng browser và HTTP.

6. **Xuất chart dùng nhầm runner của checkout hiện tại:** CRLF/LF làm hash khác
   snapshot dù cùng nội dung Git. Công cụ nay chạy đúng cặp frozen runner/validator
   của artifact, vẫn fail nếu validator thất bại; regression test và export thật
   đều PASS. Không sửa hash, bỏ validation hoặc thay raw.
7. **Test đường dẫn tạm trên Windows CI:** run đầu `39632150698` có Ubuntu PASS
   nhưng Windows FAIL ở Python. Assert so đường dẫn chưa chuẩn hóa với đường dẫn
   đã resolve không phù hợp khi Windows dùng tên rút gọn/alias. Chuẩn hóa cả hai
   phía, giữ nguyên test; thêm `scripts/ci_python_tests.py` chạy cùng discovery và
   xuất traceback thành annotation khi lỗi. Sau đó run `39633008763` PASS cả hai
   OS; không bỏ test, không thay kết quả đo. Log cũ không tải qua API công khai
   (403); không sử dụng credential để vượt giới hạn đó.

Không đổi RR/LC/ECT, trọng số phạt, admission, timeout hoặc workload. Không thêm
pooling, retry, HTTP/2, TLS, WebSocket hoặc Kubernetes.

## Hạn chế cần trình bày

| Mức | Hạn chế | Ảnh hưởng |
|---|---|---|
| Cao với production | HTTP/1.1 buffered, một socket/request, chưa pooling/TLS | Phạm vi đồ án; không suy ra năng lực proxy production |
| Cao với kết luận hiệu năng | Các JVM và bộ phát tải dùng chung host; thời gian chạy ngắn | Client lag/JIT/GC/tải máy ảnh hưởng; không khái quát hoặc coi là RPS tối đa |
| Vừa | Admission chưa bảo đảm công bằng Slow, cold start cần học | Luôn xem lỗi theo loại; chưa thay fairness khi chưa có thử nghiệm ghép cặp |
| Vừa | Trace chỉ giữ một quyết định, telemetry lấy mẫu | Không gán ảnh chụp hiện tại cho mọi lỗi lịch sử; không đo thời lượng state bằng số mẫu |
| Thấp trong demo local | Tổng counter gồm polling, snapshot không đồng thời | Nhãn và hướng dẫn giải thích rõ; RPS dùng completed của backend |
| Thấp, bảo trì CI | GitHub cảnh báo một số action hiện tại dùng phiên bản deprecated | Không gây lỗi trong run đã PASS; nên cập nhật action trong đợt bảo trì riêng |

[Hướng dẫn bảo vệ](DEFENSE_GUIDE.md) và [phân tích bộ đo cuối](DEFENSE_PERFORMANCE.md)
ghi các bước tự chạy, giải thích công thức và giới hạn bằng chứng.


## Các nhóm file thêm/sửa

| Nhóm | File/thư mục |
|---|---|
| Giao diện/HTTP | `DemoPage.java`, `server/MonitoringPage.java` (mới), `server/ReverseProxyServer.java` |
| Java tests | `AdaptiveDecisionTest.java`, `LifecycleTest.java`, `ManagementListenerTest.java` |
| Python validation/tests | `scripts/verify_benchmark.py`, `test_benchmark.py`, `test_presentation.py`, `test_defense_artifacts.py` và `ci_python_tests.py` (mới) |
| Công cụ | `tools/generate_charts.py`; mới: `run_defense_matrix.py`, `check_local_demo.py`, `probe_slow.py`, `defense_artifacts.py`, `analyze_slow.py`, `check_reports.py` |
| Cấu hình quy trình | `.github/workflows/ci.yml`, `.gitattributes`; application.properties kết thúc vẫn trace=false như trước đo |
| Tài liệu | README; mới: FINAL_VERIFICATION, DEFENSE_GUIDE, DEFENSE_PERFORMANCE; cập nhật BENCHMARK_GUIDE, CODEBASE_REVIEW, DEMO, METRICS_REFERENCE, PERFORMANCE_ANALYSIS, VISUALIZATION_GUIDE |
| Dữ liệu | `benchmarks/defense/20261021/` (ZIP/manifest/3 bảng); `benchmarks/presentation/defense-20261021/` (4 file bundle) |
| Trình bày | `docs/assets/charts/defense-20261021/` (17 file); Việt hóa nhãn bộ `mixed-20261021/` và `adaptive-flow.svg` |

Không xóa test/audit cũ, không reset working tree. Bộ historical CSV vẫn nguyên
byte; mọi raw mới và bản sao historical nằm trong ZIP, không chỉ trong results ignored.

## Bổ sung backend — 23/10/2026

Đã tái hiện bằng socket thật: bản cũ chấp nhận response có metadata chunk vượt
ngân sách thử nghiệm, dù payload nhỏ. Bổ sung `proxy.max.chunk.metadata.bytes`
(mặc định 65.536 byte), validation cấu hình, JSON/Prometheus counter cho limit
rejection và tập con chunk metadata. Chi tiết phạm vi/ngữ nghĩa tại
[kiến trúc](ARCHITECTURE.md) và [metrics](METRICS_REFERENCE.md).

- PASS: build JDK 21 và **13 Java test mains** bằng `scripts/test.ps1`. Test mới
  `ChunkMetadataTest` kiểm đúng biên/vượt 1 byte, nhiều chunk nhỏ, extension của
  last-chunk, trailer, ngân sách mặc định của constructor cũ và payload fixed/EOF.
  Backend giữ kết nối mở; test yêu cầu đúng `ChunkMetadataLimitException`, không
  coi timeout/EOF là đạt. Probe HALF_OPEN bị chặn trả slot; probe sau thành công
  đóng circuit, các permit/lease/socket/timer đều được thu hồi.
- PASS: `CircuitBreakerHttpTest` kiểm counter body/header riêng; metadata limit
  không tăng hoặc reset failure threshold. Toàn bộ JSON/Prometheus counter cũ vẫn
  được kiểm đúng cùng counter mới. Bản cuối của fixture cleanup được biên dịch và
  chạy riêng lại; log tại `target/chunk-budget-final.stdout.log` và `.stderr.log`.
- PASS: **24 Python tests** bằng `python -B -X utf8 scripts/ci_python_tests.py`;
  render thật, kiểm corruption và frozen validator vẫn hoạt động.
- PASS: `tools/check_reports.py --include-defense` và
  `tools/defense_artifacts.py --verify benchmarks/defense/20261021`: bảng, hình,
  SHA, archive lịch sử, raw validators và liên kết vẫn khớp.
- PASS: `benchmark.py --quick --scenarios homogeneous` rồi
  `verify_benchmark.py benchmarks/results/chunk-budget-smoke-20261023`: **3 runs,
  360 yêu cầu đo, 0 lỗi**; có frozen source/config, SHA, lịch gửi, counters và thống
  kê được đối chiếu. Dùng JDK 21, seed mặc định 20260927; metadata ghi rõ
  `git_dirty=true`. Đây chỉ là smoke kiểm tra tương thích sau sửa, không dùng
  kết luận tăng throughput hay thuật toán nào thắng. Artifact sinh tự động được
  giữ tại đường dẫn trên (ignored).

Giữ nguyên UI, RR/LC/Adaptive, health check, workload và dữ liệu công bố. Bộ 54 lần
đo phía trên vẫn thuộc source `c2d5f20`, không coi đó là số đo lại của bản nâng cấp.
Chưa commit/push; CI remote chưa chạy cho diff này. Pooling và retry vẫn chưa triển khai.
