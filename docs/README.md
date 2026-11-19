> Workspace hiện tại: [kết quả dọn](maintenance/WORKSPACE_CLEANUP.md), [đường dẫn cũ → mới](maintenance/WORKSPACE_PATHS.csv), [evidence](../../evidence/README.md).

# Tài liệu PBL4

Bắt đầu với [bản đồ code](architecture/CODEBASE_MAP.md) và
[hướng dẫn chạy](../README.md). Các đường dẫn dưới đây thuộc checkout hiện tại.

| Nhóm | Nội dung |
|---|---|
| Kiến trúc | [Luồng request và class](architecture/CODEBASE_MAP.md), [Streaming / Range / PDF lab](architecture/HTTP_STREAMING.md) |
| Phát triển | [Bản đồ script và lệnh kiểm tra](development/SCRIPTS.md) |
| Vận hành | [Database, backup, metrics và operator](deployment/OPERATIONS_UPGRADE.md), [Docker / LAN](../deploy/README.md) |
| Kiểm thử | [Tiêu chí nghiệm thu](testing/ACCEPTANCE_GATES.md), [công cụ operations](../scripts/ops/README.md) |
| Báo cáo | [Core](reports/CORE_UPGRADE_ACCEPTANCE.md), [Unified](reports/UNIFIED_ACCEPTANCE_20261115.md) — kết quả lịch sử theo ngày |
| Bảo trì | [Dọn workspace 18/11/2026](maintenance/CLEANUP_20261118.md), [tái cấu trúc 15/11](maintenance/RESTRUCTURE_REPORT.md), [audit dung lượng cũ](maintenance/STORAGE_AUDIT.md) |
| Lịch sử | [Bản nguồn và provenance](history/README.md), [receipts được chọn](evidence) |

Báo cáo Word, bản sao dự phòng và hướng dẫn bảo vệ ngoài Git nằm ở
[reports của workspace](../../reports/README.md). Evidence gốc vẫn nằm ngoài
checkout. Kết quả lịch sử không thay thế kiểm thử của mã nguồn local hiện tại.
Các README trong `deploy/` và `scripts/ops/` giữ cạnh công cụ mà chúng hướng dẫn.
