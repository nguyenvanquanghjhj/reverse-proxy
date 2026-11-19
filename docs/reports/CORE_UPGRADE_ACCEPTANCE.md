# Nghiệm thu PBL4 Core Streaming — 15/11/2026

Nguồn duy nhất: gói `PBL4_CORE_UPGRADE_20261115` trong Downloads, pin `6e7f3ac`. Installer check/diff/review/apply một lần; không sửa bundle gốc. Không thay thuật toán RR/LC/Adaptive hoặc mở rộng Mộc Store.

| Gate | Hạng mục | Kết quả | Bằng chứng |
|---|---|---|---|
| G1 | Java 21 Windows native / Linux Maven | PASS | [29-java-native-source-final.log](../../../evidence/protocol/29-java-native-source-final-67e62ccbe80d.log)<br>[TEST-com.example.proxy.StreamingProxyTest.xml](../../../evidence/streaming/TEST-com-example-proxy-StreamingProxyTest-8f184d96773a.xml)<br>[github-ci-feature.json](../../../evidence/ci/github-ci-feature-e2bd46556369.json) |
| G2 | Python operations / HTTP | PASS | [22-python-ops-final.log](../../../evidence/integration/22-python-ops-final-f3b1f3fccde8.log)<br>[23-python-http-final.log](../../../evidence/integration/23-python-http-final-796c6bff9859.log)<br>[04-bundle-standalone.log](../../../evidence/integration/04-bundle-standalone-bd2da9dfd2e7.log)<br>[05-bundle-anchors.log](../../../evidence/integration/05-bundle-anchors-91fe8d37a84b.log) |
| G3 | 32/100/256 MiB, SHA, Range, RAM | PASS | [28-streaming-final.log](../../../evidence/streaming/28-streaming-final-c51311f0e1d7.log)<br>[result.json](../../../evidence/streaming/result-2c497f694ebc.json)<br>[transfers.json](../../../evidence/streaming/transfers-cc7300d3081c.json)<br>[memory.json](../../../evidence/streaming/memory-ffe63fdbd196.json)<br>[heap.log](../../../evidence/streaming/heap-4657604b9d29.log) |
| G4 | Disconnect / backend death / timeout / disk / resource cleanup | PASS | [result.json](../../../evidence/streaming/result-2c497f694ebc.json)<br>[socket-soak.json](../../../evidence/streaming/socket-soak-904e6520832c.json)<br>[metrics-final.json](../../../evidence/streaming/metrics-final-08abcdcde36f.json)<br>[com.example.proxy.StreamingProxyTest.log](../../../evidence/streaming/com-example-proxy-StreamingProxyTest-eed49c468b5c.log) |
| G5 | Three JVMs + PostgreSQL + store/browser | PASS | [30-operational-release.log](../../../evidence/ci/30-operational-release-0ca99a3384b0.log)<br>[result.json](../../../evidence/ci/result-a314efa23460.json)<br>[31-browser-release.log](../../../evidence/ci/31-browser-release-a9e5898747eb.log)<br>[acceptance.json](../../../evidence/integration/acceptance-058118db46fb.json) |
| G6 | Docker Linux / restart persistence | PASS | [result.json](../../../evidence/ci/result-d4d97c0050bb.json)<br>[job.log](../../../evidence/ci/job-e0820596ce94.log)<br>[github-ci-feature.json](../../../evidence/ci/github-ci-feature-e2bd46556369.json)<br>[21-docker-local-probe.log](../../../evidence/ci/21-docker-local-probe-b80a3b9531a8.log) |
| G7 | RR / LC / Adaptive, homogeneous + heterogeneous | PASS | [19-benchmark.log](../../../evidence/benchmarks/19-benchmark-c9fee9386984.log)<br>[result.json](../../../evidence/benchmarks/result-5b5969466b52.json)<br>[results.json](../../../evidence/benchmarks/results-8edde03bdaf9.json)<br>[environment.json](../../../evidence/benchmarks/environment-3f605b71c471.json) |
| G8 | Upstream/JDBC pools + QoS ON/OFF | PASS | [30-operational-release.log](../../../evidence/ci/30-operational-release-0ca99a3384b0.log)<br>[result.json](../../../evidence/streaming/result-2c497f694ebc.json)<br>[com.example.proxy.UpstreamPoolingAdversarialTest.log](../../../evidence/streaming/com-example-proxy-UpstreamPoolingAdversarialTest-e80080a58ab3.log)<br>[com.example.proxy.UpstreamPoolingFailureTest.log](../../../evidence/streaming/com-example-proxy-UpstreamPoolingFailureTest-fdd083cc2f46.log) |
| G9 | Protected data, secrets, local edits | PASS | [20-preservation.log](../../../evidence/integration/20-preservation-89b3e0726863.log)<br>[preservation-result.json](../../../evidence/integration/preservation-result-63f12337fc69.json)<br>[source-final.json](../../../evidence/integration/source-final-55534d5dbcb1.json) |
| G10 | Independent final end-to-end repeat | PASS | [28-streaming-final.log](../../../evidence/streaming/28-streaming-final-c51311f0e1d7.log)<br>[30-operational-release.log](../../../evidence/ci/30-operational-release-0ca99a3384b0.log)<br>[31-browser-release.log](../../../evidence/ci/31-browser-release-a9e5898747eb.log)<br>[34-backup-restore-final.log](../../../evidence/recovery/34-backup-restore-final-2adead27d613.log)<br>[17-operations.log](../../../evidence/integration/17-operations-f4a3a85692d1.log)<br>[github-ci-feature.json](../../../evidence/ci/github-ci-feature-e2bd46556369.json) |
| G11 | Multi-machine LAN | NOT RUN | [lan-not-run.log](../../../evidence/integration/lan-not-run-bf3bb09fbd65.log) |
| G12 | HTTP/1.1 + transaction/storage compatibility | PASS | [29-java-native-source-final.log](../../../evidence/protocol/29-java-native-source-final-67e62ccbe80d.log)<br>[com.example.proxy.HttpIntermediaryTest.log](../../../evidence/protocol/com-example-proxy-HttpIntermediaryTest-62dd1ddba651.log)<br>[com.example.proxy.HttpTransportTest.log](../../../evidence/protocol/com-example-proxy-HttpTransportTest-27cb3f6d6812.log)<br>[com.example.proxy.StreamingProxyTest.log](../../../evidence/streaming/com-example-proxy-StreamingProxyTest-eed49c468b5c.log)<br>[34-backup-restore-final.log](../../../evidence/recovery/34-backup-restore-final-2adead27d613.log)<br>[result.json](../../../evidence/recovery/result-00d0a6b9ce9c.json) |

## Phạm vi và giới hạn

- G1: Native PASS 30/30; Linux is verified by CI on the new source.
- G2: 18 ops tests; preserved HTTP tooling; original bundle standalone 23 assertions.
- G5: 30 operational + 316 browser/SQL checks on the new build; dedicated databases.
- G6: Windows Docker runtime NOT RUN: missing Engine pipe; Linux actual runtime is mandatory.
- G7: 18 runs, 180 requests each; 3 repeats per algorithm/profile. Raw CSV and process/metrics samples are siblings of results.json; algorithms unchanged.
- G8: All deployment flags remain OFF. ON uses isolated QA; no POST transport replay.
- G9: 5,323 protected files; original assembler edit byte identical; no scheduler modifications.
- G10: Native rebuild; new PostgreSQL/browser/backup/streaming runs; independent CI rebuilds all tests and real Docker.
- G11: No real second host; explicitly nonblocking per bundle; never substitute one Docker bridge.
- G12: Known-length public streaming route; original buffered chunked/EOF/informational/204/304 tests preserved. 19 table row hashes and 34 file hashes after new-target restore.

Streaming dùng buffer 32 KiB, heap proxy giới hạn 64 MiB. Deadline riêng chỉ được nâng sau khi parse tuyến đọc; header parsing và request đặt hàng giữ timeout cũ. Không gửi 502 thứ hai sau khi header đã commit. HEAD bỏ qua Range và giữ độ dài; ETag/If-Range dùng cùng descriptor file. Windows có thể từ chối thay file đang mở; bài thử xác nhận nội dung cũ và xuất bản lại sau khi đóng.

Raw evidence, config, SHA source/runtime, CPU/RSS/socket samples, CSV benchmark, ảnh/PDF trình duyệt và dữ liệu QA được giữ riêng trong `D:\PBL4\core-upgrade-acceptance-20261115`. Commands/cwd/exit/duration: [runs.jsonl](../../../evidence/integration/runs-079fe91994f3.jsonl). Chỉ receipt đã lọc được commit; không đưa env, credential, archive, database hay bản ghi khách hàng vào Git.

Các log FAIL được giữ: `12-streaming-live.log` (Windows từ chối atomic replace khi reader còn mở) và `18-streaming-final.log` (fixture vừa restart proxy rồi đòi pool hit trước khi gửi GET lặp). Đã sửa fixture, giữ nguyên kiểm thử và chạy lại toàn bộ. Bản cuối PASS 70 check, gồm GET lặp tạo reuse thật, scope deadline, tên file không đuôi, Via và timer cancellation. Probe Docker Windows exit 1 là bằng chứng thiếu Engine, được ghi NOT RUN, không gán PASS.

Pool upstream, streaming và QoS reservation vẫn OFF trong cấu hình triển khai. JDBC pool trong môi trường local cũ không đổi. Toàn bộ dataset/cluster QA mới được giữ; không DROP/TRUNCATE, không xóa volume, không reset/clean Git, không force-push.

## Kết quả thí nghiệm

File 32/100/256 MiB có RSS đỉnh tương ứng (MiB): `{"32": 114.78, "100": 105.75, "256": 95.91}`; heap tối đa 64 MiB. RSS gồm bộ nhớ native JVM, không phải heap.

| Điều kiện | Thuật toán | p95 all (ms), 3 lần | Tổng lỗi / request |
|---|---|---|---|
| homogeneous | ROUND_ROBIN | 2008.5518, 2017.3276, 2016.2982 | 2 / 540 |
| homogeneous | LEAST_CONNECTIONS | 2009.7034, 2012.1328, 2012.9044 | 0 / 540 |
| homogeneous | ADAPTIVE | 2009.9327, 2015.4092, 2018.7853 | 0 / 540 |
| heterogeneous | ROUND_ROBIN | 2116.8633, 2087.1397, 2503.6345 | 4 / 540 |
| heterogeneous | LEAST_CONNECTIONS | 2007.2183, 2017.0355, 2014.2012 | 1 / 540 |
| heterogeneous | ADAPTIVE | 2009.1487, 2018.0338, 2022.9076 | 2 / 540 |

Mỗi run có p50/p95/p99 all và success, RPS, phân bố backend, lỗi và timestamp. Percentile nội suy tuyến tính được ghi trong receipt. Heterogeneous thay capacity/delay/CPU budget công khai; CPU budget là tham số telemetry, không phải quota OS. Không tuyên bố thuật toán nào luôn thắng từ số đo loopback này.

G7 PASS nghĩa là hoàn tất và lưu đầy đủ 18 phép đo có xác minh strategy, cấu hình, source/runtime SHA và mọi outcome. Có **9 HTTP 504 / 3.240 request**, không có transport error. Các 504 có latency khoảng 3 giây, theo read budget cũ; snapshot của homogeneous-2-RR thấy B2 có tối đa 11 in-flight với 4 worker, sau run mọi backend về 0 và UP. Đây là kết quả tải/queueing của các thuật toán hiện có, không bị xóa khỏi percentile hoặc đổi thành success. Không tăng timeout, đổi admission/scheduler hay hệ số Adaptive để làm đẹp số đo.

CSV và process/metrics samples cho từng run nằm trong `benchmark-01/*-raw/raw.csv` và `benchmark-01/*-samples.json`. Các HTTP timeout của benchmark không được gọi là zero-error vận hành.

Feature CI: [https://github.com/nguyenvanquanghjhj/reverse-proxy/actions/runs/43683180239](https://github.com/nguyenvanquanghjhj/reverse-proxy/actions/runs/43683180239), SHA `41634ca44e0e33f22d02eec448f4ba0252420792`, success.

Bản receipt trong Git được chốt trước merge. SHA main và CI trên main cuối cùng được cập nhật trong [bản nghiệm thu ngoài Git](../../../evidence/integration/ACCEPTANCE-0f7061efaac2.md). Chỉ merge/push khi G1–G10/G12 đều PASS và feature CI 4/4 xanh; sau đó xác minh lại main CI 4/4.
