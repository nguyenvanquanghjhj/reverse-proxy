# Dữ liệu benchmark lịch sử

Các thư mục đo 27/09/2026 được gộp nguyên byte vào [20260927.zip](20260927.zip)
để giảm số file trong repository. [Manifest](20260927-manifest.json) giữ SHA-256
của archive và **934 file**, gồm cả source/classes đóng băng vốn chỉ có cục bộ.
Không đổi số liệu, không bỏ các lượt Adaptive kém hơn hoặc lượt đo dở.

| Thư mục trong ZIP | Trạng thái giữ lại |
|---|---|
| baseline-20260927-isolated | 27 lượt lịch sử, có complete và snapshot |
| baseline-20260927 | Lượt dở: chỉ 3 file, chưa có complete/source/classes; không dùng kết luận hiệu năng |
| official-20260927 | 27 lượt, gồm BEFORE Mixed |
| ect-mixed-official-20260927 | 9 lượt AFTER Mixed, bảng và compare.py |
| ect-mixed-smoke-20260927 | 3 lượt kiểm tra nhanh, không dùng kết luận thắng/thua |

## Kiểm tra và khôi phục

Từ gốc repository, kiểm SHA toàn bộ archive/file lịch sử cùng các báo cáo:

```powershell
python tools/check_reports.py --include-defense
python -m zipfile -t benchmarks/history/20260927.zip
# Chọn thư mục đích mới, chưa có dữ liệu cần giữ.
python -m zipfile -e benchmarks/history/20260927.zip target/history-restored
python -X utf8 target/history-restored/ect-mixed-official-20260927/compare.py
```

Đã kiểm tra giải nén đủ 934 file và chạy lại `compare.py` thành công trên Windows.
Script so sánh cần hai thư mục official/ect-mixed cạnh nhau; nó chỉ tái tạo các
bảng dẫn xuất, không chạy tải. Các đường dẫn `benchmarks/results/...` trong lệnh
cũ của tài liệu lịch sử ghi lại nơi đã chạy, không còn là dữ liệu rời được Git giữ.

Các bộ này dùng schema 1 và runner cũ. SHA xác nhận toàn vẹn dữ liệu đóng gói,
không làm cho validator hiện hành tự tương thích với runner cũ. Bộ đo mới để bảo vệ
nằm ở [defense/20261021](../defense/20261021/manifest.json); CSV để vẽ biểu đồ ở
`benchmarks/presentation/`. Kết quả và hạn chế lịch sử giữ trong
[ADAPTIVE_COMPLETION](../../docs/history/ADAPTIVE_COMPLETION.md).
