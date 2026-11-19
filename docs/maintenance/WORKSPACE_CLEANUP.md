# Dọn và hợp nhất workspace PBL4

Workspace còn **4 thư mục cấp cao nhất**: `reverse-proxy-demo`, `reports`, `evidence`, `tools`.
Không còn junction, checkout tham khảo, thư mục `archives`, nhóm screenshot riêng hay thư mục dự án mang ngày trong phần đã quét.
Thời gian thực hiện: 18–20/11/2026. Không commit, push, reset hoặc sửa nội dung báo cáo Word.

| Chỉ tiêu | Trước | Sau |
|---|---:|---:|
| Thư mục cấp cao nhất, gồm junction | 13 | 4 |
| Thư mục vật lý, không gồm junction | 2,396 | 570 |
| Junction | 19 | 0 |
| File vật lý | 54,869 | 11,261 |
| Dung lượng file (byte logic) | 6,957,409,609 | 1,730,977,541 |
| Thư mục dự án mang ngày đã thấy | 25 | 0 |
| Git checkout vật lý | 2 | 1 |
| File dư trong nhóm trùng SHA, ngoài DB đang chạy | 30,941 | 645 |

Giảm **5,226,432,068 byte logic (5.226 GB)** và **43,608 file**.
Dung lượng NTFS cấp phát đo sau: 1,657,015,310 byte. Chưa có phép đo cấp phát cùng thời điểm trước nên không gọi số byte logic giảm là dung lượng ổ đĩa thực giải phóng.
Baseline ban đầu có 54.835 file đọc được. Đã bổ sung 34 file từng bị từ chối truy cập sau khi đối chiếu từng SHA với object có sẵn trong checkpoint đã xác minh trước khi bỏ; baseline đầy đủ là 54.869 file. Không còn nhánh chưa kiểm kê. Manifest ban đầu giữ nguyên; phần bổ sung được đối chiếu trong [receipt phục hồi](../../../evidence/integration/protected-qa-recovery.json).

Đã xóa 48,004 đường dẫn gốc sau checkpoint và đối chiếu. Con số này gồm file đã chuyển sang vị trí chính thức, bản trùng, file thừa và file QA rời đã đóng gói phục hồi; không cộng nhầm toàn bộ thành rác bị loại bỏ.
Loại bỏ DOCX trung gian, Word/WPS temp, cache chụp ảnh, helper dùng một lần, snapshot source/build cũ, reference checkout sạch không được main sử dụng, PostgreSQL portable trùng, Maven cache trùng và Compose executable không chạy được.
Input streaming được xóa chỉ khi khớp recipe và SHA; raw phép đo, FAIL, CSV/JSON/log và chứng cứ duy nhất còn giữ. [Recipe](../../../evidence/streaming/fixture-recipes.json).

17 cluster QA dừng cùng storage/config/backup được bảo toàn: 35.229 đường dẫn, 121 dataset, 1.500.419.756 byte gốc → gói 143.970.216 byte.
Đã xác minh toàn bộ object và khôi phục thử cluster 1.705 file. Đây là dữ liệu QA phục hồi được, không phải thư mục archives giữ nguyên đống cũ. [Cách khôi phục](../../../evidence/recovery/README.md).
Hai DOCX chính thức và 36 ảnh duy nhất ở [reports](../../../reports/README.md); DOCX trùng SHA với bản gốc trong Downloads.

Đã cập nhật 415 liên kết Markdown, đổi hai thư mục evidence trong docs thành `unified`/`core-streaming`, sửa `.gitignore`, `.gitattributes`, `toolchain.json` và quy tắc tạo thư mục QA.
Thư mục QA mới dùng `<label>-run-<random-id>`; timestamp ở metadata/file. Raw receipt và Word giữ đường dẫn lịch sử trong nội dung; [bảng ánh xạ 51.220 file](WORKSPACE_PATHS.csv) ghi vị trí mới hoặc object phục hồi trong checkpoint.
[INDEX.csv](../../../evidence/INDEX.csv) giữ ngữ cảnh nguồn và SHA gốc. Các log/data raw giữ nguyên byte; Markdown điều hướng có thay đổi.
18 receipt trùng với bộ receipt trong repository được giữ tại `docs/evidence`; bản ngoài đã xóa sau SHA và mọi chỉ mục/liên kết chuyển về bản chính.
JDK/Maven giữ layout nhà cung cấp. PostgreSQL client dùng `.runtime/postgres/bin`; không còn `tools/pg16` và cache `tools/m2` trùng user Maven cache.

| Kiểm tra | Kết quả |
|---|---|
| Java 21 native build + suite | PASS, 32 test mains |
| Maven `-Pshop package` | PASS; antrun skip vì native suite đã chạy riêng |
| Python ops | PASS, 41 tests |
| Defense live mock | PASS |
| PowerShell start/status/stop, proxy + 3 backend | PASS, DB QA 55468 và storage ngoài workspace; đã dừng hết QA |
| Hash source/config/tests, runtime và shared storage gốc | PASS, 1,884 file; thay đổi chủ ý chỉ ở công cụ đường dẫn và tài liệu |
| Git HEAD và staged diff | Giữ nguyên; working tree và file untracked được bảo toàn, trừ dọn output tái tạo và đổi chỗ tài liệu |
| Hash hai DOCX / raw evidence / gói QA | PASS |
| Compose + CI YAML syntax | PASS với PyYAML giữ trong tools |
| Docker Engine / LAN nhiều máy | NOT RUN, thiếu Docker và máy LAN thứ hai |
| Full browser acceptance | NOT RUN đợt này; lần trước FAIL kỳ vọng URL `/shop` trong khi app chuyển `/admin/shop` |

Không có lệnh setup/test ghi lên DB thật. `.shop`, `.runtime`, `shared-files`, `.env.store` giữ vị trí; cluster thật 55434 không bị dừng hay reset.
Source Java, test Java và resource ứng dụng giữ nguyên SHA so với checkpoint trước đợt dọn này. Các lần chạy build/runtime diễn ra ngày 18/11; lần đối chiếu và chốt báo cáo ngày 20/11 xác nhận source vẫn giữ nguyên. Log kiểm tra thật đã chuyển sang evidence; xem [nhật ký xóa backup](BACKUP_REMOVAL.md).
[Receipt kiểm tra](../../../evidence/integration/workspace-cleanup-validation.json).

Nhánh QA từng bị từ chối truy cập đã đọc được trong môi trường ngày 20/11. Toàn bộ 34 file, 4.127.530 byte trùng chính xác dataset `storage-492ec7b26a52` đã có trong gói phục hồi.
Đã kiểm SHA của object trong checkpoint gốc, khôi phục thử riêng đủ 34 file, đối chiếu từng file rồi xóa bản trùng và thư mục rỗng. [Receipt](../../../evidence/integration/protected-qa-recovery.json).
Không còn ngoại lệ chưa quét, junction hay thư mục dự án mang ngày. File trong database/storage thật không bị xóa.
Cache `target/classes` của checkout chính được IDE tạo lại trong lúc kiểm tra được giữ vì đang dùng; các bản sao build giữa source/resource/JAR/runtime và file nội bộ nhà cung cấp có thể trùng byte hợp lệ.

Java Language Server cũ của VS Code đã tái tạo 188 output trong snapshot/reference sau khi source bị xóa. Đã dừng đúng dịch vụ JDT của workspace này, checkpoint/hash output mới rồi xóa chúng; không đóng VS Code hoặc sửa buffer.
Mở [PBL4.code-workspace](../../../PBL4.code-workspace) để IDE chỉ import checkout chính. Không tạo thêm thư mục config ở root.

Checkpoint tạm bên ngoài workspace đã được bỏ theo yêu cầu sau khi kiểm tra không có phụ thuộc runtime. Log kiểm chứng cần thiết đã giữ lại, không giữ rollback cho các file thừa đã xóa.
[Nhật ký bỏ backup](BACKUP_REMOVAL.md), [manifest file đã bỏ](REMOVED_BACKUPS.csv).
Đối chiếu snapshot trước khi publish: [số liệu JSON](WORKSPACE_METRICS.json), [cây trước](WORKSPACE_TREE_BEFORE.txt), [cây sau](WORKSPACE_TREE.txt), [bảng đường dẫn](WORKSPACE_PATHS.csv).
Các row `discarded-obsolete-backup` ghi nhận file thừa đã bỏ vĩnh viễn. Dữ liệu QA cần phục hồi vẫn nằm trong gói `evidence/recovery/qa-data.zip`.
