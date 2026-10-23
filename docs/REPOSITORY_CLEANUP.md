# Dọn repository — 22/10/2026

Baseline `c97c103`, working tree sạch trước dọn. Không commit/push hoặc đổi mã
Java, thuật toán, transport, workload, cấu hình, test và CI. Diff để người dùng duyệt.

## Phân loại và xử lý

| Nhóm | Quyết định |
|---|---|
| Bắt buộc giữ | Toàn bộ Java/test/scripts/tools, cấu hình/CI; raw/provenance, hai bộ CSV/biểu đồ dùng báo cáo và kết quả Adaptive kém hơn |
| Nên giữ | Audit, thiết kế và phân tích lịch sử: chuyển 6 tài liệu vào `docs/history/`, sửa các liên kết; không đổi số liệu |
| Có thể gộp | 5 bộ 27/09 trong `benchmarks/results/`: 738 file từng được Git giữ chuyển sang `benchmarks/history/20260927.zip`; archive giữ đủ 934 file cục bộ, cả source/classes vốn ignored. Danh sách từng file/SHA có trong manifest |
| Có thể xóa | Bản rời đã khớp archive; hai bản xuất Git, worktree phụ sạch, 5 bộ hình tạm, bản khôi phục thử, cache Python, ZIP tải JDK và thư mục chunk rỗng. Giữ JDK đã giải nén sau khi kiểm đủ 486 file khớp ZIP |
| Chưa rõ, giữ lại | Các profile Edge/QA, ảnh/log cũ, những bộ fault/resilience chưa có trong archive đã đối chiếu; không coi mọi `.log` là rác |

Hai bản raw cục bộ `defense-clean-20261021` và `presentation-mixed-20261021`
đã khớp từng byte với ZIP công bố trước khi bỏ bản giải nén. Không xóa dữ liệu
nghiên cứu duy nhất. `baseline-20260927` là lượt dở từ trước, giữ cả 3 file và ghi
rõ thiếu complete/snapshot trong manifest, không dùng làm bằng chứng hiệu năng.

Các cặp SVG/PNG có mục đích xuất khác nhau; HTML/CSV/Markdown phục vụ đọc và tính
lại số liệu nên giữ. BENCHMARK/BENCHMARK_GUIDE/VISUALIZATION_GUIDE có vai trò schema,
phương pháp và trình bày khác nhau, không gộp cơ học. Không xóa code chỉ vì ít nơi gọi.

## Số lượng và dung lượng

| Phạm vi | Trước | Sau theo diff | Giảm |
|---|---:|---:|---:|
| File giữ trong Git | 871 | 137 | 734 |
| Dung lượng tập file Git | 21,07 MB | 12,65 MB | 8,42 MB (40%) |
| File cục bộ, không tính .git | 8.442 | 3.557 | 4.885 |
| Dung lượng cục bộ, không tính .git | 766,68 MB | 451,15 MB | 315,53 MB |

MB = 1.000.000 byte. Thống kê sau build/test và bỏ bản xuất kiểm chứng tạm.

Sau là tập file còn được Git theo dõi cộng file mới cần đưa vào diff, loại các
đường dẫn đã chuyển/xóa. Dung lượng là tổng byte nội dung; không phải dung lượng
Git history hoặc kích thước clone qua mạng. Cục bộ gồm JDK và dữ liệu ignored,
không tính `.git`; build/test có thể sinh lại output sau lần đo.

## Kiểm tra sau dọn

- PASS: JDK 21 build và 12 Java test mains bằng `scripts/test.ps1`.
- PASS: 24 Python tests bằng `scripts/ci_python_tests.py`, gồm render SVG/PNG thật.
- PASS: archive mới nhất bằng `tools/defense_artifacts.py --verify benchmarks/defense/20261021`.
- PASS: 934 file lịch sử giải nén khớp SHA; `compare.py` chạy được với source/classes và dữ liệu khôi phục.
- PASS: `tools/check_reports.py --include-defense` kiểm bảng, hình, manifest, archive lịch sử và liên kết.
- PASS: `demo.ps1 start/basic/status/stop` dùng JDK đã giữ lại.
- PASS: Edge thật kiểm tra 6 tác vụ, lịch sử, dashboard đọc metrics/trace, mất kết nối/cũ/kết nối lại (`.demo/cleanup-browser-20261022`).
- PASS: bản xuất 137 file dự kiến giữ, không có dữ liệu ignored, vẫn kiểm chứng được toàn bộ report/archive.
- CI: cấu hình không đổi, vẫn gọi các test/checker trên. Không chạy CI remote cho diff chưa commit theo yêu cầu.

Không chạy lại benchmark tải nặng. Hướng dẫn lấy lại dữ liệu cũ:
[benchmarks/history](../benchmarks/history/README.md). Các vấn đề còn lại: lượt đo
dở lịch sử không thể phục dựng phần chưa từng được tạo; profile/bằng chứng cũ
chưa rõ giữ nguyên. Git history và remote không đổi.
