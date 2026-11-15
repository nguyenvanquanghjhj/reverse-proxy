# PBL4 — Trình diễn & kế hoạch nâng cấp không phá bản cũ

## Mục tiêu

Chứng minh reverse proxy là một thành phần hệ thống thật: định tuyến request, phân phối tải,
quan sát tài nguyên, bảo vệ giao dịch và xử lý lỗi. Cửa hàng là workload thực tế, **không** phải đề tài chính.

## Bản bổ sung này thay đổi gì?

Chỉ có file mới:

- `.github/workflows/pbl4-ci.yml`: mỗi push/PR chạy `scripts/test.ps1` trên Windows/JDK 21, giữ XML/log.
- `scripts/defense_live.py`: trình diễn ba thuật toán hoặc drain một backend, lưu raw CSV, summary Markdown,
  dashboard HTML và snapshot metrics. Mặc định chỉ gửi GET `/work?cost=2`, không tạo đơn hàng.
- `scripts/test_defense_live.py`: kiểm thử script bằng fake local HTTP server, **không** cần PostgreSQL.

Không chỉnh Java, migration, database, ảnh, tài liệu PDF, giá hoặc tồn kho; không tự push lên GitHub.

## Chuẩn bị (Windows PowerShell, chạy tại thư mục repo)

1. Sao lưu `.env.store`, `.shop`, `shared-files` và ảnh chụp dữ liệu nếu đang có đơn hàng cần giữ.
2. Chép đúng ba file mới vào repo, không ghi đè file hiện có.
3. Kiểm thử script độc lập: `python scripts/test_defense_live.py`
4. Chạy project theo README: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run.ps1 start`
5. Kiểm tra `http://127.0.0.1:8080/__proxy/dashboard` và chạy:

```powershell
python scripts/defense_live.py compare --requests 90 --workers 12 --out ..\pbl4-demo-compare-01
```

Kết quả ở thư mục mới: `raw-requests.csv`, `results.json`, `summary.md`, `report.html`, `metrics-before.json`, `metrics-after.json`.
Mở `report.html` trong trình duyệt để trình bày số liệu. So sánh này là **quick demonstration**, không thay thế
108 lần benchmark V7 và không suy ra thuật toán nào thắng mọi điều kiện.

### Trình diễn backend bị tạm ngừng phục vụ

```powershell
python scripts/defense_live.py drain --backend 127.0.0.1:9001 --requests 90 --workers 12 --out ..\pbl4-demo-drain-01
```

Script sẽ thử tự trả backend về `draining=false` trong `finally`. Nếu ngắt máy hoặc kill Python,
**phải xác nhận bằng dashboard**. Nếu B1 còn draining, chạy `python scripts/store.py resume --backend 9001`.
Chạy drain chỉ khi không có QA/benchmark khác và không đang dùng chung demo với người khác.

## Buổi bảo vệ: 8 phút có cao trào

| Phút | Thao tác | Điều chứng minh |
|---|---|---|
| 0–1 | Sơ đồ Client → Proxy → B1/B2/B3 → PostgreSQL | Vai trò proxy và shared state |
| 1–2 | Mở `/__proxy/dashboard` + ba backend | Topology, health, in-flight |
| 2–4 | Chạy `compare`, mở `report.html` | RR, LC, Adaptive đều có kết quả đo |
| 4–5 | Chạy `drain`, thấy B1 bị loại khỏi lựa chọn | Khả năng bảo trì không tiếp nhận request mới |
| 5–6 | Restore B1, health lại bình thường | Recovery/slow start, không phải zero downtime tuyệt đối |
| 6–7 | Tạo một đơn COD bằng web, kiểm tra DB | Giao dịch thật, idempotency; KHÔNG tự retry POST |
| 7–8 | Tải PDF, xem metrics/log, nêu hạn chế | Response download, memory bound, kế hoạch LAN |

**Phân biệt:** drain có chủ đích ≠ process crash; demo ở loopback ≠ triển khai LAN; GET tải demo ≠ kiểm thử đặt hàng.

## Kế hoạch ba mức, với vài ngày còn lại

### P0 — phải đạt trước khi viết kết luận
- CI có kết quả chạy xanh và test report đính kèm; nếu chưa xanh thì báo đúng trạng thái.
- Chạy `compare`/`drain` ít nhất ba lần độc lập, giữ raw evidence và ngày giờ.
- Không để thay đổi nâng cấp gây mất DB hoặc lịch sử đơn; có bản sao backup riêng.
- Viết báo cáo song song, đặt hình và kết quả thực vào đúng mục, không tạo biểu đồ giả.

### P1 — nâng cấp làm rõ điểm hổng thực tế
- Capability-aware routing: lấy `/ready` của B1/B2/B3, phân loại request, loại backend thiếu capability;
  viết test mất JDBC riêng một backend nhưng B1/B3 vẫn tạo đơn; không đánh dấu toàn backend DOWN nếu PDF chỉ tạm dừng.
- Kiểm thử LAN: trước tiên chạy các GET `/work` và `/slow` trên hai máy; mở cổng tối thiểu, đừng đưa admin và PostgreSQL ra Internet.
- Kiểm soát control plane: bảo đảm không expose `/__proxy/*` ra LAN trước khi có cơ chế xác thực/ủy quyền; không coi
  một custom header bất kỳ là bí mật xác thực.

### P2 — chỉ làm sau khi P0/P1 ổn định
- Docker Compose chỉ khi có bootstrap PostgreSQL, storage volume, migrations, healthcheck và dữ liệu tồn tại sau restart.
- JDBC pool bằng phép A/B thật (connection setup, p95, throughput, timeout, số connection).
- Upstream keep-alive/pooling: không thay đổi đồng thời với streaming và HTTP parser.
- Benchmark package: công cụ `scripts/research.py`/`scripts/lab.py` hiện có; dùng trước, không nhân bản.

## Tránh các tuyên bố quá mức

- Không gọi XAMPP là PostgreSQL: XAMPP dùng MariaDB; migration không chỉ là thay driver.
- Không nói đã chạy nhiều máy nếu chỉ chạy 127.0.0.1 trên một máy.
- Không gọi 503/502 trong failure window là không có lỗi.
- Không tạo các order trùng bằng retry mù; Idempotency-Key phải được bảo toàn.
- Không trộn số liệu từ những lần chạy khác source/config để tạo so sánh.
