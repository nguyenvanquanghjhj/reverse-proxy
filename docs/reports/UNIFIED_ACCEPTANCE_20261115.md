# Nghiệm thu PBL4 unified — 15/11/2026

Branch: `feature/pbl4-unified-20261115`. Pull `origin/main` tới `f3137cffbad91f80136bd2ea40c860376ebffb46`; không merge main.

Trạng thái phân biệt source/fixture, regression của repository và vận hành thực. Dữ liệu QA nằm trong cluster mới `127.0.0.1:55455`, ngoài checkout. Các log và database của lần FAIL được giữ.

| # | Hạng mục | Source / fixture | Regression | Vận hành | Log / bằng chứng |
|---|---|---|---|---|---|
| 1 | Capability routing | PASS | PASS | PASS | [17c-operational-live.log](../../../evidence/integration/17c-operational-live-17df9493af1e.log)<br>[operational-03/result.json](../evidence/unified/operational-result.json)<br>[java-maven-complete-final-reports/com.example.proxy.CapabilityJsonStrictTest.log](../../../evidence/protocol/com-example-proxy-CapabilityJsonStrictTest-798903916cc3.log) |
| 2 | LAN nhiều máy | PASS | PASS | NOT RUN | [lan-not-run.log](../../../evidence/integration/lan-not-run-166ea6b9d17b.log)<br>[20-final-python-ops-tests.log](../evidence/unified/python-operations.log) |
| 3 | CI Linux / Windows | PASS | PASS | PASS | [github-ci.json](../evidence/unified/github-ci.json) |
| 4 | Docker Compose / restart | PASS | PASS | PASS | [14-compose-preflight.log](../../../evidence/integration/14-compose-preflight-8c63d481e87a.log)<br>[docker-engine.log](../../../evidence/ci/docker-engine-b32742581e37.log)<br>[github-docker/result.json](../evidence/unified/docker-result.json) |
| 5 | Raw evidence / provenance | PASS | PASS | PASS | [operational-03/environment.json](../../../evidence/integration/environment-8ae7ec3f08d7.json)<br>[operational-03/result.json](../evidence/unified/operational-result.json)<br>[artifact-manifest.json](../../../evidence/integration/artifact-manifest-f08ab52e10af.json) |
| 6 | JDBC pool / transaction | PASS | PASS | PASS | [operational-03/jdbc-false.log](../evidence/unified/jdbc-pool-off.log)<br>[operational-03/jdbc-true.log](../evidence/unified/jdbc-pool-on.log)<br>[23c-browser-postgres-acceptance.log](../../../evidence/integration/23c-browser-postgres-acceptance-db96b5924211.log) |
| 7 | Operator control plane | PASS | PASS | PASS | [17c-operational-live.log](../../../evidence/integration/17c-operational-live-17df9493af1e.log)<br>[github-docker/result.json](../evidence/unified/docker-result.json) |
| 8 | Upstream keep-alive | PASS | PASS | PASS | [25c-native-final.log](../evidence/unified/java-native.log)<br>[26-maven-final.log](../evidence/unified/java-maven.log)<br>[java-maven-complete-final-reports/com.example.proxy.UpstreamPoolingFailureTest.log](../../../evidence/streaming/com-example-proxy-UpstreamPoolingFailureTest-44ef2183316d.log) |
| 9 | QoS admission | PASS | PASS | PASS | [17c-operational-live.log](../../../evidence/integration/17c-operational-live-17df9493af1e.log)<br>[operational-03/result.json](../evidence/unified/operational-result.json) |
| 10 | ETag / 304 | PASS | PASS | PASS | [17c-operational-live.log](../../../evidence/integration/17c-operational-live-17df9493af1e.log)<br>[23c-browser-postgres-acceptance.log](../../../evidence/integration/23c-browser-postgres-acceptance-db96b5924211.log) |
| 11 | PostgreSQL + file backup | PASS | PASS | PASS | [22-real-backup-restore.log](../../../evidence/recovery/22-real-backup-restore-b311339f650b.log)<br>[backup-restore/backup-offline.log](../../../evidence/recovery/backup-offline-4d98b1ddec4f.log)<br>[backup-restore/backup-verify.log](../../../evidence/recovery/backup-verify-e9877b759ab5.log) |
| 12 | Restore vào DB / root mới | PASS | PASS | PASS | [backup-restore/result.json](../evidence/unified/backup-restore-result.json)<br>[backup-restore/table-fingerprints.json](../evidence/unified/backup-table-fingerprints.json)<br>[backup-restore/restored-store-audit.log](../evidence/unified/restored-store-audit.log) |
| 13 | SLO watchdog | PASS | PASS | PASS | [24-live-operations.log](../../../evidence/integration/24-live-operations-79845925689e.log)<br>[operations-live/watchdog/watchdog-events.jsonl](../evidence/unified/watchdog-events.jsonl)<br>[operations-live/watchdog/watchdog.csv](../../../evidence/integration/watchdog-e8fb0814d042.csv) |
| 14 | Health / maintenance | PASS | PASS | PASS | [24-live-operations.log](../../../evidence/integration/24-live-operations-79845925689e.log)<br>[operations-live/result.json](../evidence/unified/operations-result.json) |
| 15 | Release / bảo toàn dữ liệu | PASS | PASS | PASS | [preservation-result.json](../evidence/unified/preservation-result.json)<br>[29-release-gate.log](../../../evidence/ci/29-release-gate-261b76957ccf.log)<br>[release-final.json](../../../evidence/ci/release-final-09ab6711e2ff.json) |

## Kết quả có thể kiểm tra

- Java 21 native: **PASS**; Maven `-Pshop test`: **PASS**. Toàn bộ 28 `*Test.java` chạy bằng TestSuite. Surefire không chạy vì project dùng launcher mains; XML không bỏ suite.
- Python operations: **PASS**, 18 test; HTTP tooling: **PASS**; bundle integrator: 5 test PASS và Java component fixture PASS.
- PostgreSQL/HTTP: **PASS**, 30 check: cắt TCP tới PostgreSQL riêng B2 mà không restart JVM; 18 order tránh B2, static vẫn tới B2; all-DB-down fail closed; 423 được giữ; QoS reservation và peer cap; token control plane; Edge nhận ETag/304 thực.
- Browser/SQL/response-loss: **PASS**, 316 check. Luồng hiện tại yêu cầu đăng nhập và đặt hàng ngay trang sản phẩm; gallery/nguồn hãng, idempotency/stock, UNKNOWN replay, offline/loading/validation, 55 viewport/route và print được kiểm bằng Edge thật.
- Backup/restore: **PASS**; 379 order QA, 19 bảng và 34 tệp. `pg_dump` custom + TOC + SHA archive; DB/root mới; full-row hashes và mọi SHA tệp bằng nhau; StoreAdmin check/audit và HTTP ảnh/PDF trên app phục hồi; từ chối target đã tồn tại; nguồn không đổi.
- Watchdog/health/maintenance: **PASS**, 20 check; kill/restart đúng JVM test, BACKEND_DOWN/recovery, drain/resume và từ chối backend cuối.
- Docker Linux CI: **PASS**, 46 check. Windows local runtime **NOT RUN**: thiếu Engine named pipe và WSL; Compose CLI config đã PASS. Runtime CI dùng project/volume mới, secret sinh riêng, build image chạy TestSuite, kiểm exec/auth/drain và order/stock/file sau restart; không xóa volume. Log thực: [Docker job log](../evidence/unified/docker-job.log).
- LAN liên máy **NOT RUN**. Mọi đo local là loopback; Docker bridge trên một runner không được gọi là LAN nhiều máy.
- Upstream pool vẫn `false`, QoS reservation vẫn `0`; `.env.store` local giữ nguyên, JDBC pool local mặc định OFF. Các chế độ bật chỉ chạy trong fixture/DB riêng hoặc Compose CI.

## A/B JDBC cùng build, cùng workload

Mỗi chế độ 3 repeat × 60 order, concurrency 6, RR. Cả 360 order thành công. Đây là số liệu loopback của lần đo, không phải cam kết hiệu năng hay số liệu LAN. CSS print được sửa và build/QA lại sau phép đo; code Java transaction/routing trong phép A/B không đổi.

| Pool | Repeat | p95 mọi outcome (ms) | Thành công |
|---|---|---|---|
| false | jdbc-false-0 | 249.412 | 60/60 |
| false | jdbc-false-1 | 248.502 | 60/60 |
| false | jdbc-false-2 | 222.913 | 60/60 |
| true | jdbc-true-0 | 271.173 | 60/60 |
| true | jdbc-true-1 | 105.152 | 60/60 |
| true | jdbc-true-2 | 114.255 | 60/60 |

## Hòa giải installer và lỗi đã sửa

Nguồn duy nhất là folder unified trong Downloads. `--verify-bundle` xác nhận 81 mục; SHA-256 của MANIFEST gốc: `b94d1d05d35be730b7a957d1838949a2ff8aadd759daac71c6d703a980b49546`. Ba ZIP provenance chỉ được giữ/kiểm integrity, không extract hoặc cài riêng.

12 file baseline khác byte vì CRLF. Đã lưu bản gốc, đối chiếu LF của từng file với SHA Git blob trong bundle và `origin/main`; cả 12 khớp. Không bỏ kiểm tra baseline. Installer còn sai hai anchor khoảng trắng và xuất diff CRCRLF trên Windows; sửa tối thiểu trong bản sao được review, cập nhật manifest bản sao, kiểm lại, `git apply --check` PASS rồi apply đúng một lần. Bundle Downloads gốc không sửa.

Diff sửa installer: [unified-installer-fixes.patch](../history/unified/unified-installer-fixes.patch); inventory gốc: [unified-release-manifest.json](../history/unified/unified-release-manifest.json). Patch giữ byte/context nguyên vẹn bằng một rule `.gitattributes` riêng cho artifact, không thay kiểm tra whitespace source.

Các log FAIL được giữ: baseline/anchor/EOL (`02*`, `03*`); Python fixture Windows (`06-python-ops-tests.log`, `08-bundle-integrator-tests.log`); Java contract/schema/snapshot/socket-count (`10-java-native.log`); fixture startup DB-down (`17-operational-live.log`); expectation X-Forwarded-For (`17b-operational-live.log`); browser selector (`23-browser-postgres-acceptance.log`); lỗi print thật (`23b-browser-postgres-acceptance.log`); client fixture gửi HTTP/2 Upgrade ngoài contract (`25-native-final.log`) và chưa chờ health recovery sau timeout (`25b-native-final.log`). Đã sửa và chạy lại toàn bộ lệnh tương ứng; không xóa test hoặc thay hai hash parity lịch sử.

Thay đổi cục bộ `scripts/assemble_research.py` được commit riêng trước pull/install và kiểm byte-identical với bản sao ban đầu. Không reset/clean, không drop/truncate database, không xóa `.shop`, `.runtime`, `shared-files`, `.env.store`, không `down -v`.

## Tái hiện và dữ liệu riêng

Lệnh thực, cwd, exit code, thời gian và log nằm trong `D:/PBL4/unified-acceptance-20261115/runs.jsonl`. JDK 21, Maven và PG clients portable nằm dưới `tools/` ở cùng thư mục; các runner tự đặt PATH/JAVA_HOME trong process, không đổi JDK hệ thống.

Chạy `scripts/test.ps1`, `mvn -B --no-transfer-progress -Pshop test`, `python -m unittest discover -s scripts/ops -p "test_*.py" -v`, `python scripts/test_defense_live.py`. Live QA dùng `scripts/ops/acceptance_live.py --env-file <private-test-env> --out <new-directory>` và `scripts/verify.py --env-file <private-test-env> --out <new-directory>`; `scripts/ops/LiveJdbcProbe.java` được harness gọi bằng JDK 21 với JDBC thật. Docker acceptance chạy trong job `docker-live` trên runner mới.

Các DB QA và archive được giữ ngoài Git, bao gồm kết quả FAIL. Không đưa env/password/database/archive vào PR. Dataset backup riêng là `pbl4_operational_03`; restore là `pbl4_restored_20261115`, files root `backup-restore/restored-files`.

Bằng chứng đã chọn có thể review ngay trong repository tại [docs/evidence/unified](../evidence/unified/manifest.json). Chỉ gồm log và receipt, không chứa env/database/archive hay bản ghi khách hàng.

Bản máy đọc: [acceptance-results.json](../../../evidence/integration/acceptance-results-58194b360c81.json). Raw evidence có SHA-256 trong [artifact-manifest.json](../../../evidence/integration/artifact-manifest-f08ab52e10af.json).

PR nháp chưa được tạo: GitHub connector trả 403 thiếu quyền; bộ xét duyệt tự động chặn phương án đọc/tái sử dụng credential Git/GCM khi chưa có ủy quyền rõ. Branch đã push; CI chạy bằng push event được phép, không cần đọc credential. Không merge main.

GitHub Actions: [https://github.com/nguyenvanquanghjhj/reverse-proxy/actions/runs/43676363709](https://github.com/nguyenvanquanghjhj/reverse-proxy/actions/runs/43676363709).
