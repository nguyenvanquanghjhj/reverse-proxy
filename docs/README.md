# Tài liệu PBL4

Đây là mục lục tài liệu chung của repository. Bắt đầu từ báo cáo hiện hành để
xác định điều gì đã kiểm thử; tài liệu lịch sử không thay cho nghiệm thu phiên bản mới.

## Bản đồ tài liệu

| Vị trí | Nội dung và điểm vào |
|---|---|
| architecture/ | [Bản đồ code, luồng request và entrypoint](architecture/CODEBASE_MAP.md); [HTTP streaming, Range và PDF lab](architecture/HTTP_STREAMING.md) |
| deployment/ | [Vận hành, script, Docker, backup/restore](deployment/OPERATIONS_UPGRADE.md); [triển khai LAN Windows](../deploy/lan/README.md) giữ tại deploy vì đi cùng launcher/package |
| testing/ | [Quy trình build, regression, CI và QA riêng](testing/VALIDATION.md) |
| reports/ | [Nghiệm thu hiện hành và mục chốt/dọn dẹp](reports/PBL4_FINAL_VALIDATION.md#13-chốt-main-ci-và-vệ-sinh-repository); [snapshot kiểm toán ban đầu](reports/PBL4_PROJECT_AUDIT_20261122.md) |
| evidence/ | [Bảng số đo](evidence/final-validation-20261122/aggregate.json), [CSV tổng hợp](evidence/final-validation-20261122/aggregate.csv), [biểu đồ p95](evidence/final-validation-20261122/latency-p95.png), [throughput](evidence/final-validation-20261122/throughput.png), [lỗi HTTP](evidence/final-validation-20261122/http-error-rate.png), [CI đã xác nhận](evidence/final-validation-20261122/ci-main.json) |
| history/ | [Manifest core](history/core/core-upgrade-bundle-manifest.json) và [manifest unified](history/unified/unified-release-manifest.json), giữ nguyên nguồn gốc release |
| maintenance/ | [WORKSPACE_PATHS.csv](maintenance/WORKSPACE_PATHS.csv) giữ ánh xạ đường dẫn/ZIP member/checksum của các lần lưu trữ trước; không phải danh sách cần xóa |

## Báo cáo hiện hành và lịch sử

- **Hiện hành:** [PBL4_FINAL_VALIDATION.md](reports/PBL4_FINAL_VALIDATION.md).
  Các mục 1–12 mô tả thí nghiệm sau sửa ngày 22/11; mục 13 bổ sung việc đưa vào
  main, CI và chuẩn hóa tài liệu. Không tạo báo cáo cleanup riêng.
- **Lịch sử bất biến:** [PBL4_PROJECT_AUDIT_20261122.md](reports/PBL4_PROJECT_AUDIT_20261122.md)
  kiểm toán commit nền 32cbd9f879770269783ea69a2687d054636fd861, trước sửa lỗi.
  Các nhận xét CI/conditional/QA trong đó là trạng thái tại thời điểm kiểm toán.
- [Evidence core-streaming](evidence/core-streaming/) và [evidence unified](evidence/unified/)
  là receipt độc lập của các release trước. Không cộng số test, gộp benchmark hoặc
  đổi nhãn SHA của chúng thành evidence mới.

Các tài liệu ngoài checkout đã có ở workspace vẫn giữ nguyên: `D:\PBL4\reports\REVIEW.md`
là review Word ngày 18/11; `D:\PBL4\reports\DEFENSE_GUIDE.md` là hướng dẫn bảo vệ cũ.
Word và ảnh nguồn ở `D:\PBL4\reports\`; raw/history lớn ở `D:\PBL4\evidence\`.
Chúng không nằm trong một bản clone GitHub. Dùng mục lục này và báo cáo hiện hành
để tìm kết quả mới, không mặc nhiên coi lời nhận xét trong review cũ còn đúng.

## Benchmark và nguồn gốc bằng chứng

Benchmark ngày 22/11 có **63 trial, 16.200 measured request, 4.068 warm-up**.
Commit nền là `32cbd9f879770269783ea69a2687d054636fd861` cộng patch sau sửa;
runtime source digest là
`5c40e0bfb81310db4613be9b763b01aecbcaa41a63c1df5d6e72798f2502852c`.
Commit đưa source đó vào main là `52d6ea79f5e7627e2d06939691f2b061e9b148ab`.
CI và chỉnh tài liệu sau đó không có nghĩa benchmark đã được đo lại.

[environment.json](evidence/final-validation-20261122/environment.json) giữ từng
source hash/JDK/profile; [execution.json](evidence/final-validation-20261122/execution.json)
xác nhận hoàn tất. [manifest.json](evidence/final-validation-20261122/manifest.json)
là bản sao nguyên byte manifest của **toàn bộ benchmark ngoài Git**, không phải
cam kết rằng 841 file được chép vào thư mục evidence trong repository.
PNG và SVG cùng tên được giữ để đọc trên GitHub hoặc đưa vào Word.
[Runner gốc](evidence/final-validation-20261122/benchmark_runner.py) cùng hai script
tổng hợp là recipe lịch sử có path workspace, cần artifact/path đúng khi tái lập.

Bộ đầy đủ nằm tại `D:\PBL4\evidence\final-validation-20261122`: raw.csv/warmup.csv
từng trial, config, metrics, events, log, compiled artifacts, SQL và dữ liệu QA.
Log/XML/CI và kiểm kê của lần chốt nằm tại `D:\PBL4\evidence\closeout-20261122`.
Git chỉ giữ phần công khai nhỏ; không giữ database, password file hoặc log riêng.
Giữ bộ raw cùng manifest trên bản sao lưu riêng; kiểm từng SHA trước khi công nhận
bản sao. Không đưa `pg-password.txt`, `.env.store` hoặc cluster QA vào gói công khai.

## Tìm bằng chứng khi bảo vệ

1. Đọc mục 2–3 của [nghiệm thu](reports/PBL4_FINAL_VALIDATION.md) cho lỗi trước/sau,
   Java/Python và transaction/idempotency đa JVM.
2. Dùng mục 4–8, aggregate và ba biểu đồ cho RR/LC/Adaptive. Adaptive có p95 tốt
   nhất trong profile backend không đều; LC tốt nhất ở mixed. Giữ cả timeout và SD.
3. Dùng [receipt CI main](evidence/final-validation-20261122/ci-main.json) cho run
   đã ghi nhận; xem [Actions trên main](https://github.com/nguyenvanquanghjhj/reverse-proxy/actions/workflows/pbl4-ci.yml?query=branch%3Amain)
   và đối chiếu **head SHA** khi cần trạng thái mới nhất.
4. Dùng WORKSPACE_PATHS.csv và manifest lịch sử để tìm ảnh/raw cũ hoặc đúng ZIP
   member trong workspace. Loopback/ba JVM/Docker không chứng minh physical LAN.
5. Xem mục 12.2 và 13 của báo cáo để cập nhật Word; không thay ảnh cũ bằng nhãn
   phiên bản mới và không gộp kết quả khác môi trường.

## Quy tắc duy trì

Làm việc trực tiếp trên **main**; fetch/đối chiếu remote trước mỗi push, không
force-push hoặc bỏ công việc người khác. Không tạo branch/PR khi chưa được yêu cầu.

Trước khi thêm tài liệu: tìm tài liệu cùng mục đích; ưu tiên cập nhật; xác định
giá trị lưu lâu dài và thư mục chuẩn; chỉ thêm mục lục khi có tài liệu đáng giữ.
Không tạo FINAL2/FINAL_NEW, báo cáo audit/cleanup lặp hoặc manifest trùng mục đích.
Giữ source catalog JSON dưới resources, receipt JSON dưới evidence và provenance
CSV tại maintenance; không chuyển mọi JSON/CSV vào docs chỉ theo phần mở rộng.
