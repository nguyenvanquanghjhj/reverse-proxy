# Kiểm định readability refactor — 26/10/2026

Nhánh `refactor/defense-readability`; commit gốc
`6550f5c01e2fc11f62074e59fae984e4ef1c486c`, working tree ban đầu sạch.
Giữ nguyên package, kiến trúc đồng bộ, endpoints và dữ liệu benchmark công bố.
[FINAL_VERIFICATION](FINAL_VERIFICATION.md) là kiểm định lịch sử, không thay bằng
kết quả của lần này.

## Baseline trước khi sửa Java main

Windows; Microsoft OpenJDK **21.0.12.1+1**, Python **3.12**. JDK dùng từ
`.demo/tools/jdk21-presentation/jdk-21.0.12.1+1`; chỉ thêm vào PATH của tiến trình.

| Kiểm tra đã thực thi | Kết quả |
|---|---|
| `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test.ps1` trên JDK 21 | PASS, 13 test mains; log `target/baseline-jdk21.log`, bản sao reports `target/baseline-jdk21-reports/` |
| `python -X utf8 -m unittest discover -s scripts -p 'test_*.py'` | PASS, 24 tests; log `target/baseline-python.log` |
| `python tools/defense_artifacts.py --verify benchmarks/defense/20261021` | PASS, SHA/archive/frozen validators/bảng công bố |
| `python tools/check_reports.py --include-defense` | PASS trên baseline, trước khi thêm tài liệu |
| Probe HTTP thật: RR 6 bước, LC có Slow đang giữ lease, health DOWN/UP, Adaptive `/work?cost=2` | PASS; [bằng chứng riêng](assets/readability-20261026/capture.json), [trace](assets/readability-20261026/decision.json) |
| `ProxyContractTest` chạy riêng trên Java main chưa sửa | PASS: thứ tự validation, management, status/error/HEAD, JSON/Prometheus chính xác và cleanup |
| 2.700 bước scheduler với seed `20261026`, clock inject, ba strategy | SHA-256 output baseline `f1b93fdb49772e638afad2906c6cb21383a39d3bc0fa7925dc7ee192c5dbbcfe` |

Đã thử thêm JDK **26.0.2.1** trước khi tìm được JDK 21 cục bộ: **FAIL 1/13**,
`HttpTransportTest` báo `oversized body rejected before upload: null` trong fixture
có deadline 600 ms; log `target/baseline-java.log`. JDK 21 sau đó PASS toàn bộ.
Không sửa timeout, nới assertion hoặc gán kết quả JDK 21 cho JDK 26.

Maven không có trên PATH; GitHub Actions cho nhánh local chưa chạy. Các run CI
trong tài liệu lịch sử không xác minh các thay đổi local này.

## Phạm vi

Ưu tiên bốn tài liệu ngắn và golden contracts, sau đó tách helper validation,
metrics và scoring thuần. Giữ `ProxyHandler.sendError()` tại API cũ: các nơi gọi
đã dùng chung nó, không cần thêm lớp error response chỉ để chuyển một hàm.
Giữ parser/socket trong `UpstreamTransport` và fixture `DemoBackendServer` để
tránh mở rộng phạm vi thay đổi framing, timing và workload trước bảo vệ.

Kết quả sau từng phần và danh sách trách nhiệm được bổ sung khi hoàn tất.
