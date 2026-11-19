# Bỏ backup tạm ngoài workspace

Theo yêu cầu tiếp theo của người dùng ngày 20/11/2026, bỏ ba thư mục tạm sau khi kiểm tra phụ thuộc: `D:\PBL4_BACKUP`, `D:\PBL4-archives`, `D:\PBL4-checkpoints`.
Đây là rollback checkpoint, bản restore thử, database QA dừng, output build/test và helper một lần. Không có PostgreSQL/Java/Python đang dùng ba thư mục; cấu hình ứng dụng không phụ thuộc chúng. Các DB ở đây dùng `STORE_ENV=test` và port QA 55468, tách khỏi DB cửa hàng 55434.

| Thư mục | File | Byte logic trước khi xóa |
|---|---:|---:|
| `D:\PBL4_BACKUP` | 3,064 | 947,693,277 |
| `D:\PBL4-archives` | 2,839 | 212,598,940 |
| `D:\PBL4-checkpoints` | 8 | 150,508,009 |

Tổng payload tạm được bỏ: **1,310,800,226 byte**, **5,911 file**.
[REMOVED_BACKUPS.csv](REMOVED_BACKUPS.csv) giữ tên file, byte, SHA-256 và vị trí log giữ lại. [Receipt thực thi](../../../evidence/integration/backup-retirement.json) ghi trạng thái xóa.
Chỉ giữ raw log/receipt còn chứng minh các phép kiểm đã chạy và cây thư mục trước. Không giữ installer, build cache, cụm PostgreSQL thử hoặc Word helper để phòng hờ.
Gói phục hồi QA đã kiểm SHA, source đã publish, database/store files thật và hai DOCX trong workspace được giữ nguyên.

Các số liệu ở WORKSPACE_METRICS/WORKSPACE_TREE là snapshot kết thúc đợt dọn trước khi publish; Git sinh thêm object khi commit/push. Không dùng số snapshot đó để tuyên bố tổng dung lượng hiện tại của cả workspace lẫn backup bên ngoài.

Log/receipt còn giữ:

- [cleanup-java-native.log](../../../evidence/protocol/cleanup-java-native.log) — 8,334 byte, SHA `209c5b713830`.
- [cleanup-maven.log](../../../evidence/ci/cleanup-maven.log) — 5,262 byte, SHA `47d4e5c4a904`.
- [cleanup-runtime-result.json](../../../evidence/integration/cleanup-runtime-result.json) — 1,024 byte, SHA `c2918d32d000`.
- [cleanup-launcher-start.log](../../../evidence/integration/cleanup-launcher-start.log) — 287 byte, SHA `f22eb831bb0c`.
- [status-bd0a89979443.log](../../../evidence/integration/status-bd0a89979443.log) — 118 byte, SHA `bd0a89979443`.
- [cleanup-data-restore.json](../../../evidence/integration/cleanup-data-restore.json) — 252 byte, SHA `d2621a41627e`.
- [cleanup-data-package.json](../../../evidence/integration/cleanup-data-package.json) — 316 byte, SHA `b67a4db3a912`.
- [WORKSPACE_TREE_BEFORE.txt](WORKSPACE_TREE_BEFORE.txt) — 1,496,658 byte, SHA `d6297eb6d8dc`.
- [cleanup-python.log](../../../evidence/protocol/cleanup-python.log) — 4,689 byte, SHA `f2f9bd8bcd33`.
- [cleanup-defense.log](../../../evidence/protocol/cleanup-defense.log) — 2,192 byte, SHA `68ff407b4a9f`.
- [cleanup-browser-failure.log](../../../evidence/integration/cleanup-browser-failure.log) — 2,128 byte, SHA `95ddbed2d38f`.
- [cleanup-deployment-checks.json](../../../evidence/integration/cleanup-deployment-checks.json) — 455 byte, SHA `d8ff7a7dd115`.
- [previous-cleanup-final.json](../../../evidence/integration/previous-cleanup-final.json) — 2,613 byte, SHA `461129a07f7a`.
- [cleanup-streaming.log](../../../evidence/streaming/cleanup-streaming.log) — 4,343 byte, SHA `9af8aac93eab`.
- [cleanup-template-extraction.json](../../../evidence/protocol/cleanup-template-extraction.json) — 469 byte, SHA `aeadf797e049`.
- [cleanup-runtime-parity.json](../../../evidence/protocol/cleanup-runtime-parity.json) — 394 byte, SHA `8054cf84ede2`.
- [cleanup-template-parity-classes.log](../../../evidence/protocol/cleanup-template-parity-classes.log) — 258 byte, SHA `a9b3d5185086`.
- [cleanup-template-parity-jar.log](../../../evidence/protocol/cleanup-template-parity-jar.log) — 258 byte, SHA `a9b3d5185086`.
- [cleanup-template-parity-maven-jar.log](../../../evidence/protocol/cleanup-template-parity-maven-jar.log) — 258 byte, SHA `a9b3d5185086`.
- [cleanup-default-metrics.json](../../../evidence/streaming/cleanup-default-metrics.json) — 9,049 byte, SHA `723fdf64308e`.
- [files.json](../../../evidence/streaming/files.json) — 403 byte, SHA `20751c4626e2`.
- [cleanup-fixture-inputs.json](../../../evidence/streaming/cleanup-fixture-inputs.json) — 1,685 byte, SHA `19d4e94165a6`.
- [cleanup-heap.log](../../../evidence/streaming/cleanup-heap.log) — 283 byte, SHA `3e0c4d99c6ca`.
- [cleanup-memory.json](../../../evidence/streaming/cleanup-memory.json) — 251 byte, SHA `33ad7f8e6d02`.
- [cleanup-metrics-final.json](../../../evidence/streaming/cleanup-metrics-final.json) — 9,182 byte, SHA `cd902704c704`.
- [result.json](../../../evidence/streaming/result.json) — 8,042 byte, SHA `24e9c79d55d8`.
- [cleanup-socket-soak.json](../../../evidence/streaming/cleanup-socket-soak.json) — 1,985 byte, SHA `0a130227afe2`.
- [cleanup-transfers.json](../../../evidence/streaming/cleanup-transfers.json) — 115,871 byte, SHA `8dccf7962bf2`.

Kiểm tra sau xóa: cả ba thư mục không còn; 99 file được bảo vệ giữ nguyên SHA; Python operations chạy lại PASS 41 tests. [Log Python mới](../../../evidence/protocol/cleanup-python-current.log).
