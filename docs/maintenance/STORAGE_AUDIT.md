> Historical record. Current layout and cleanup results: [WORKSPACE_CLEANUP.md](WORKSPACE_CLEANUP.md). Old paths are resolved by [WORKSPACE_PATHS.csv](WORKSPACE_PATHS.csv).

# Kiểm kê và dọn dung lượng PBL4 — 15/11/2026

Git root: `D:/PBL4/reverse-proxy-demo`. Baseline đã kiểm chứng: `8a2cc59c514cfde633637f1755c9c299161b2e31`. Branch: `maintenance/storage-audit-20261115`; working tree ban đầu sạch. Không merge main.

## Phạm vi và giới hạn phép đo

Đo từng tệp gồm hidden files, dùng NTFS FileStandardInfo AllocationSize và file identity để tránh đếm hardlink hai lần. Bốn junction được liệt kê và không đi theo. Không cộng Docker vào repository. Không tính allocation của directory/NTFS metadata.

Bảng dưới đo `D:/PBL4`, **loại thư mục nghiệm thu mới `storage-maintenance-20261115`** để so sánh cùng phạm vi trước/sau; dung lượng phát sinh của thư mục mới được đo riêng bên dưới. Snapshot trước: `2026-11-15T06:38:33.344560+00:00`; snapshot sau: `2026-11-15T07:00:35.259067+00:00`. Chênh lệch bao gồm build lại và docs mới tại thời điểm snapshot, chưa gồm Git objects sinh sau commit/push.

**Không thể khẳng định tổng đầy đủ của toàn workspace:** Windows từ chối liệt kê và đọc `unified-acceptance-20261115/backup-restore/restored-files`, kể cả ngoài sandbox. Giữ nguyên ACL và dữ liệu. Các tổng dưới là tổng chính xác của phần đọc được (cận dưới cho toàn workspace). Metadata của 34 tệp được manifest cũ liệt kê cho biết 4.127.530 byte logical; allocation/checksum hiện tại và khả năng có tệp khác là UNKNOWN. Không lấy số lịch sử làm số đo mới. Inventory riêng toàn checkout PASS, không lỗi.

## Byte trước / sau

Giữ nguyên phần lớn logical bytes vì giữ tất cả payload và bằng chứng. Cột allocated phản ánh dung lượng thực của file trên đĩa; nén NTFS không thay nội dung hoặc đường dẫn.

| Nhóm | Logical trước | Logical sau | Allocated trước | Allocated sau | Giảm allocated |
|---|---:|---:|---:|---:|---:|
| Source / cấu hình / docs | 2,615,193 | 2,676,793 | 3,026,264 | 3,100,208 | -73,944 |
| Build output | 16,905,475 | 17,666,816 | 18,495,664 | 19,460,976 | -965,312 |
| Cache | 12,820,379 | 12,408,379 | 13,201,560 | 12,734,616 | 466,944 |
| Logs / evidence | 796,944,763 | 796,946,251 | 806,684,128 | 550,426,088 | 256,258,040 |
| Database / backup | 471,490,985 | 471,490,985 | 471,771,672 | 471,771,672 | 0 |
| Git (toàn .git) | 24,605,143 | 24,605,143 | 24,721,632 | 24,721,632 | 0 |
| Ảnh/PDF/shared QA | 142,631,963 | 142,631,963 | 145,830,920 | 145,830,920 | 0 |
| ZIP / reference / patch | 774,598,604 | 774,598,604 | 775,192,856 | 775,192,856 | 0 |
| Payload QA streaming | 2,572,155,775 | 2,572,155,775 | 2,572,156,968 | 481,624,104 | 2,090,532,864 |
| Toolchain portable | 463,994,988 | 463,994,988 | 466,860,248 | 354,502,872 | 112,357,376 |
| Runtime/storage được bảo vệ (ngoài DB) | 137,571,513 | 137,571,513 | 139,814,632 | 139,814,632 | 0 |
| **Tổng đọc được, cùng phạm vi** | **5,416,334,781** | **5,416,747,210** | **5,437,756,544** | **2,979,180,576** | **2,458,575,968** |
| Docker local (riêng) | NOT RUN | NOT RUN | NOT RUN | NOT RUN | UNKNOWN |

Machine inventory: [trước](storage-before.json), [sau](storage-after.json), [biên nhận thao tác](storage-actions.json). Full per-file JSON nằm ngoài Git tại `D:/PBL4/storage-maintenance-20261115/before.json` và `after-final.json`.

## Đã thực hiện

- Nén NTFS đúng 677 tệp đã kiểm kê: 26 payload, 531 log/JSON/CSV/text và 120 file toolchain. SHA-256 trước/sau của cả 677 tệp bằng nhau; **giảm 2.452.467.712 allocated bytes**. Không di chuyển/xóa bằng chứng, không đổi đường dẫn.
- Xóa 26 Python bytecode có source `.py` tương ứng, **426.904 logical bytes**; đối chiếu SHA và đường dẫn trong workspace trước từng lần xóa. Không xóa source/cache Maven, test hay log.
- Build Java tái tạo các output standalone bằng script hiện có sau khi xác minh không có JVM ứng dụng dùng classes. Giữ classes của IDE/Maven và toàn bộ báo cáo.
- Sau regression, nén từng file QA mới và test report đã đóng, loại toàn bộ `postgres-data` và `private-*`; kiểm SHA trước/sau. Kết quả byte/count trong `storage-actions.json`.
- SHA phát hiện 8 nhóm bản sao lớn. Giữ các bản được script/docs/manifest lịch sử tham chiếu: payload, scheduler transcript, JDBC/tool archives. Không thay bằng symlink/hardlink.

Danh sách đầy đủ từng tệp và byte/SHA: `compression-receipts.json`, `compression-new-qa.json`, `delete-regenerable-pyc.json`, `duplicates.json` trong thư mục nghiệm thu mới. Hash các biên nhận nằm trong [storage-actions.json](storage-actions.json).

## Các phần lớn giữ lại

- PostgreSQL thật và hai cluster QA cũ, paired backup archives, `.env.store`, `.shop`, `.runtime`, `shared-files`: dữ liệu cần bảo toàn; không drop/truncate/reinit, không thay ACL.
- ZIP bằng chứng/source, bản vá và reference repos: cần tái hiện; chưa chứng minh được tất cả không còn tham chiếu. Không extract provenance ZIP.
- JDK 21, Maven, PG clients và cache Maven khoảng 12 MB: cần build/restore offline; toolchain nén lossless, không xóa bản portable đang dùng.
- TestSuite raw transcripts, log FAIL, CSV và JSON: bằng chứng lịch sử; nén giữ nguyên byte.
- `.git` khoảng 25 MB: `git count-objects -vH` báo garbage 0. Ba Git blob lớn nhất là archive lịch sử 5.817.193 / 5.390.873 / 2.883.632 byte. Không gc/prune/rewrite lịch sử.
- Thư mục restore không có quyền đọc: cần quyền đọc phù hợp để kiểm kê đầy đủ; giữ nguyên, không yêu cầu thao tác này để hoàn tất các phần dọn an toàn khác.

## Cấu trúc trước / sau và refactor

Giữ nguyên cây Java, tests, package, API và thuật toán RR/LC/Adaptive. Không đổi schema, giới hạn HTTP, transaction hoặc runtime defaults. Các evidence/QA folder vẫn ở vị trí cũ.

```text
reverse-proxy-demo/
  src/main, src/test        # giữ nguyên
  scripts/ops/              # thêm inventory chỉ đọc, 4 test kiểm an toàn và README index
  deploy/compose/           # giữ topology; thêm deploy/README.md
  docs/evidence/            # giữ toàn bộ receipts cũ
  docs/maintenance/         # báo cáo và JSON nhỏ, không có secret/database/raw payload
  target/                   # output tái tạo, vẫn ignored
  .shop .runtime shared-files .env.store   # giữ byte
../*-acceptance-*/          # QA/evidence giữ đường dẫn, nén lossless
../storage-maintenance-20261115/          # raw inventory/log/QA riêng của lần này
```

`.gitignore` bảo vệ thêm maintenance/acceptance outputs, `.m2`, `.venv`, pytest cache, QA PG và private env/password. CLI examples backup/restore dùng đường dẫn tương đối, bỏ ví dụ drive Windows cố định. Không có đường dẫn account/drive bắt buộc trong executable scripts của repo. CI nhận `maintenance/**`, Linux chạy unittest discover đầy đủ như Windows và ghi `docker system df -v` trước/sau trên runner.

## Regression sau dọn

| Hạng mục | Trạng thái | Log / receipt ngoài Git |
|---|---|---|
| Java 21 native, 30 test mains | PASS | `D:\PBL4\storage-maintenance-20261115\01-java-native.log` |
| Python operations, 22 test (18 cũ + 4 inventory) | PASS | `D:\PBL4\storage-maintenance-20261115\02-python-ops.log` |
| HTTP tooling mock, giữ test gốc | PASS | `D:\PBL4\storage-maintenance-20261115\03-python-http.log` |
| Maven -Pshop test, 30 mains / 30 XML, skip/error/failure = 0 | PASS | `D:\PBL4\storage-maintenance-20261115\05-maven.log` |
| Proxy + 3 backend + PG/JDBC/transaction/capability/QoS, 30 check | PASS | `D:\PBL4\storage-maintenance-20261115\06-operational.log` |
| Mộc Store / Edge / SQL / ảnh/PDF / checkout / response-loss, 316 check | PASS | `D:\PBL4\storage-maintenance-20261115\07-browser.log` |
| HTTP streaming / Range / 32/100/256 MiB / timeout / RST / backend chết, 70 check | PASS | `D:\PBL4\storage-maintenance-20261115\08-streaming.log` |
| Health / restart / circuit / watchdog / drain, 20 check | PASS | `D:\PBL4\storage-maintenance-20261115\09-operations.log` |
| RR/LC/Adaptive, 18 run × 180 request, hai cấu hình tải | PASS | `D:\PBL4\storage-maintenance-20261115\10-algorithms.log` |
| Real pg_dump/restore sang DB/root mới, 15 check | PASS | `D:\PBL4\storage-maintenance-20261115\11-backup-restore.log` |
| Compose config / backup CLI | PASS | `D:\PBL4\storage-maintenance-20261115\12-compose.log` |
| Đọc file nén NTFS / Range; 3 thuật toán light-load 180/180 HTTP200 | PASS (13 check) | `D:\PBL4\storage-maintenance-20261115\16-compressed-streaming.log` |
| Dữ liệu local bảo vệ / 2 cluster QA cũ | PASS | `D:\PBL4\storage-maintenance-20261115\preservation-final.json`; `old-qa-after.json` |
| Docker Windows local / đo disk local | NOT RUN | `D:\PBL4\storage-maintenance-20261115\docker-disk-local.log`; không có CLI/Engine pipe |
| LAN nhiều máy | NOT RUN | Không có máy thứ hai; tất cả QA mới là loopback |
| GitHub Actions trên branch push | NOT RUN tại thời điểm commit báo cáo | Receipt cuối, SHA và link sau push: `D:\PBL4\storage-maintenance-20261115\FINAL.md` |

Benchmark giữ toàn bộ `benchmark-01/*-raw/raw.csv`, summaries và samples. Có **12 outcome lỗi HTTP/transport** dưới tải cố định; PASS ở đây nghĩa là đã đo đủ 18 run, không phải tất cả request đều thành công. Không sửa thuật toán hay lọc outcome để làm đẹp số liệu.

Streaming dùng proxy heap 64 MiB; file thực lớn hơn heap. Xem `streaming/memory.json`, `transfers.json`, `socket-soak.json` và `metrics-final.json`. Upstream pool/QoS chỉ bật trong QA; defaults trong repo và `.env.store` local giữ nguyên.

## Bảo toàn và tái hiện

**5.323 tệp** trong `.env.store`, `.shop`, `.runtime`, `shared-files`, `db` được kiểm SHA trước/sau bằng nhau. Hai cluster QA cũ dừng, **7.761 tệp** giữ SHA. Thay đổi local `assemble_research.py` từ phiên trước giữ nguyên byte so với bản sao gốc. Working tree ban đầu sạch; không reset/clean/force-push. Database QA mới dùng `127.0.0.1:55457`, dữ liệu và archive được giữ ngoài Git.

Lệnh/cwd/exit code/thời gian/log: `D:\PBL4\storage-maintenance-20261115\runs.jsonl`. Native: `scripts/test.ps1`; Maven: `mvn -B --no-transfer-progress -Pshop test`; Python: `python -m unittest discover -s scripts/ops -p "test_*.py" -v` và `python scripts/test_defense_live.py`. Các live gate dùng script có sẵn cùng private env mới; helper `regression.py` ghi rõ từng lệnh.

Inventory hiện tại: `python scripts/ops/storage_audit.py --root .. --out ../maintenance/inventory.json --exclude ../maintenance`. Exit 2 là inventory chưa đầy đủ, không được gọi là PASS toàn phạm vi. Công cụ không dọn/xóa. JSON/log/private env/DB/archive lớn không được commit.

Bộ xét duyệt tự động đã chặn một lệnh nén có phạm vi bao gồm thư mục QA mới đang có PostgreSQL chạy. Lệnh đó không thực hiện; regression chạy riêng. Chỉ các file QA/log đã kiểm kê và đóng mới được nén sau đó; không nén cluster hoặc file private.

## Top 30 thư mục trước dọn

Các hàng lồng nhau không cộng lại với nhau. Đã gồm hidden directories.

| Thư mục | Logical bytes | Allocated bytes |
|---|---:|---:|
| `core-upgrade-acceptance-20261115` | 2,916,061,150 | 2,918,808,184 |
| `unified-acceptance-20261115` | 1,355,620,023 | 1,360,772,456 |
| `unified-acceptance-20261115/tools` | 1,066,597,589 | 1,069,836,656 |
| `core-upgrade-acceptance-20261115/streaming-01` | 525,710,631 | 525,797,544 |
| `core-upgrade-acceptance-20261115/streaming-final-03` | 517,420,570 | 517,516,160 |
| `core-upgrade-acceptance-20261115/streaming-final-02` | 517,414,331 | 517,507,928 |
| `core-upgrade-acceptance-20261115/streaming-final` | 517,387,454 | 517,479,256 |
| `core-upgrade-acceptance-20261115/streaming-02` | 517,381,919 | 517,471,064 |
| `v6-evidence` | 435,439,790 | 442,388,600 |
| `core-upgrade-acceptance-20261115/streaming-01/public-downloads` | 415,234,904 | 415,236,096 |
| `core-upgrade-acceptance-20261115/streaming-final-03/public-downloads` | 407,896,103 | 407,896,104 |
| `core-upgrade-acceptance-20261115/streaming-02/public-downloads` | 407,896,064 | 407,896,064 |
| `core-upgrade-acceptance-20261115/streaming-final/public-downloads` | 407,896,064 | 407,896,064 |
| `core-upgrade-acceptance-20261115/streaming-final-02/public-downloads` | 407,896,064 | 407,896,064 |
| `reverse-proxy-demo` | 383,768,920 | 387,385,128 |
| `unified-acceptance-20261115/tools/jdk21/jdk-21.0.13.0.2+1` | 343,464,884 | 343,957,112 |
| `unified-acceptance-20261115/tools/jdk21` | 343,464,884 | 343,957,112 |
| `reverse-proxy-demo/.shop` | 223,323,826 | 223,372,960 |
| `unified-acceptance-20261115/tools/jdk21/jdk-21.0.13.0.2+1/lib` | 207,000,414 | 207,036,448 |
| `final-acceptance-20261107` | 195,213,662 | 197,202,648 |
| `core-upgrade-acceptance-20261115/postgres-data` | 159,267,024 | 159,301,216 |
| `unified-acceptance-20261115/tools/pg16/pgsql` | 126,315,345 | 128,418,800 |
| `unified-acceptance-20261115/tools/pg16` | 126,315,345 | 128,418,800 |
| `core-upgrade-acceptance-20261115/postgres-data/base` | 124,637,987 | 124,656,440 |
| `reverse-proxy-demo/.shop/store-postgres` | 122,613,422 | 122,628,544 |
| `unified-acceptance-20261115/postgres-data` | 101,850,425 | 101,872,072 |
| `reverse-proxy-demo/.runtime/postgres` | 100,349,891 | 102,335,472 |
| `reverse-proxy-demo/.runtime` | 100,349,891 | 102,335,472 |
| `reverse-proxy-demo/.shop/store-postgres/base` | 87,726,653 | 87,729,736 |
| `final-acceptance-20261107/research` | 85,611,668 | 86,821,648 |

## Top 50 tệp trước dọn

| Tệp | Logical bytes | Allocated bytes |
|---|---:|---:|
| `unified-acceptance-20261115/tools/postgresql-16.14-1-windows-x64-binaries.zip` | 325,739,757 | 325,742,592 |
| `core-upgrade-acceptance-20261115/streaming-01/public-downloads/file-256.bin` | 268,435,456 | 268,435,456 |
| `core-upgrade-acceptance-20261115/streaming-02/public-downloads/file-256.bin` | 268,435,456 | 268,435,456 |
| `core-upgrade-acceptance-20261115/streaming-final/public-downloads/file-256.bin` | 268,435,456 | 268,435,456 |
| `core-upgrade-acceptance-20261115/streaming-final-02/public-downloads/file-256.bin` | 268,435,456 | 268,435,456 |
| `core-upgrade-acceptance-20261115/streaming-final-03/public-downloads/file-256.bin` | 268,435,456 | 268,435,456 |
| `unified-acceptance-20261115/tools/microsoft-jdk-21.0.13.0.2-windows-x64.zip` | 201,450,315 | 201,453,568 |
| `unified-acceptance-20261115/tools/jdk21/jdk-21.0.13.0.2+1/lib/modules` | 141,207,998 | 141,209,600 |
| `core-upgrade-acceptance-20261115/streaming-01/public-downloads/file-100.bin` | 104,857,600 | 104,857,600 |
| `core-upgrade-acceptance-20261115/streaming-01/resumed-100.bin` | 104,857,600 | 104,857,600 |
| `core-upgrade-acceptance-20261115/streaming-02/public-downloads/file-100.bin` | 104,857,600 | 104,857,600 |
| `core-upgrade-acceptance-20261115/streaming-02/resumed-100.bin` | 104,857,600 | 104,857,600 |
| `core-upgrade-acceptance-20261115/streaming-final/public-downloads/file-100.bin` | 104,857,600 | 104,857,600 |
| `core-upgrade-acceptance-20261115/streaming-final/resumed-100.bin` | 104,857,600 | 104,857,600 |
| `core-upgrade-acceptance-20261115/streaming-final-02/public-downloads/file-100.bin` | 104,857,600 | 104,857,600 |
| `core-upgrade-acceptance-20261115/streaming-final-02/resumed-100.bin` | 104,857,600 | 104,857,600 |
| `core-upgrade-acceptance-20261115/streaming-final-03/public-downloads/file-100.bin` | 104,857,600 | 104,857,600 |
| `core-upgrade-acceptance-20261115/streaming-final-03/resumed-100.bin` | 104,857,600 | 104,857,600 |
| `PBL4-final-acceptance-20261107.zip` | 90,712,203 | 90,714,112 |
| `unified-acceptance-20261115/tools/jdk21/jdk-21.0.13.0.2+1/lib/src.zip` | 53,082,420 | 53,084,160 |
| `final-acceptance-20261107/research/proxy.log` | 43,428,878 | 43,429,888 |
| `v6-evidence/v7-research/proxy.log` | 41,692,304 | 41,693,184 |
| `PBL4-v7-evidence.zip` | 35,373,362 | 35,377,152 |
| `unified-acceptance-20261115/tools/docker-compose-windows-x86_64.exe` | 34,616,320 | 34,619,392 |
| `core-upgrade-acceptance-20261115/streaming-01/public-downloads/file-32.bin` | 33,554,432 | 33,554,432 |
| `core-upgrade-acceptance-20261115/streaming-02/public-downloads/file-32.bin` | 33,554,432 | 33,554,432 |
| `core-upgrade-acceptance-20261115/streaming-final/public-downloads/file-32.bin` | 33,554,432 | 33,554,432 |
| `core-upgrade-acceptance-20261115/streaming-final-02/public-downloads/file-32.bin` | 33,554,432 | 33,554,432 |
| `core-upgrade-acceptance-20261115/streaming-final-03/public-downloads/file-32.bin` | 33,554,432 | 33,554,432 |
| `reverse-proxy-demo/.shop/proxy.log` | 32,559,965 | 32,563,200 |
| `reverse-proxy-demo/.runtime/postgres/bin/icudt67.dll` | 28,399,104 | 28,401,664 |
| `unified-acceptance-20261115/tools/pg16/pgsql/bin/icudt67.dll` | 28,399,104 | 28,401,664 |
| `reverse-proxy-demo/.git/objects/pack/pack-e70b0798561b0a9e5b4e5f019c6764471b2e2e9c.pack` | 23,529,660 | 23,531,520 |
| `unified-acceptance-20261115/tools/jdk21/jdk-21.0.13.0.2+1/jmods/java.base.jmod` | 22,237,621 | 22,241,280 |
| `final-acceptance-20261107/git-diff.patch` | 19,072,098 | 19,075,072 |
| `v6-evidence/v7-failures/proxy.log` | 18,037,818 | 18,038,784 |
| `core-upgrade-acceptance-20261115/postgres-data/pg_wal/000000010000000000000005` | 16,777,216 | 16,777,216 |
| `core-upgrade-acceptance-20261115/postgres-data/pg_wal/000000010000000000000006` | 16,777,216 | 16,777,216 |
| `reverse-proxy-demo/.shop/legacy-postgres/pg_wal/000000010000000000000002` | 16,777,216 | 16,777,216 |
| `reverse-proxy-demo/.shop/legacy-postgres/pg_wal/000000010000000000000003` | 16,777,216 | 16,777,216 |
| `reverse-proxy-demo/.shop/store-postgres/pg_wal/00000001000000000000000A` | 16,777,216 | 16,777,216 |
| `reverse-proxy-demo/.shop/store-postgres/pg_wal/00000001000000000000000B` | 16,777,216 | 16,777,216 |
| `unified-acceptance-20261115/postgres-data/pg_wal/000000010000000000000003` | 16,777,216 | 16,777,216 |
| `unified-acceptance-20261115/postgres-data/pg_wal/000000010000000000000004` | 16,777,216 | 16,777,216 |
| `unified-acceptance-20261115/tools/jdk21/jdk-21.0.13.0.2+1/bin/server/jvm.dll` | 14,056,760 | 14,057,472 |
| `unified-acceptance-20261115/tools/jdk21/jdk-21.0.13.0.2+1/bin/server/classes.jsa` | 13,434,880 | 13,434,880 |
| `unified-acceptance-20261115/tools/jdk21/jdk-21.0.13.0.2+1/bin/server/classes_nocoops.jsa` | 13,434,880 | 13,434,880 |
| `v6-evidence/v7-pdf-rerun/proxy.log` | 13,228,339 | 13,230,080 |
| `unified-acceptance-20261115/tools/jdk21/jdk-21.0.13.0.2+1/jmods/java.desktop.jmod` | 12,492,030 | 12,492,800 |
| `unified-acceptance-20261115/tools/jdk21/jdk-21.0.13.0.2+1/jmods/jdk.localedata.jmod` | 11,953,753 | 11,956,224 |

## Chi phí nghiệm thu mới (ngoài bảng cùng phạm vi)

Thư mục `storage-maintenance-20261115` được đo riêng: **744,658,665 logical bytes**, **306,401,456 allocated bytes** tại `2026-11-15T07:00:51.845574+00:00`. Bao gồm cluster QA mới, payload mới, screenshots, raw CSV/log, inventories và archive. Dữ liệu này được giữ. Khi trừ phần phát sinh này, giảm ròng trên phần workspace đo được là **2,152,174,512 allocated bytes** tại các snapshot đã nêu. Không dùng thay đổi free space toàn volume để quy hết cho dự án.

Kết quả sau commit/push và CI, có tính thêm Git objects/doc receipts phát sinh: [FINAL.md](../../../evidence/integration/FINAL-db906cf0c392.md).
