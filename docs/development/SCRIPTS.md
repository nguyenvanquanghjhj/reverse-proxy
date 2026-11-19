# Bản đồ script

Chạy từ root checkout. Giữ vị trí các entrypoint vì runner, Python import,
PowerShell, CI và deployment đang dùng chúng. Không có script sửa Word trong
nhóm công cụ ứng dụng này.

| Mục đích | Script |
|---|---|
| Build Java 21 | `scripts/build.ps1`, `scripts/java21.ps1`; Maven: `pom.xml` |
| Start / status / stop | `scripts/run.ps1`, `scripts/store.py`; lifecycle dùng chung: `scripts/app_runtime.py` |
| Chuẩn bị JDBC / PostgreSQL portable | `scripts/prepare_runtime.py`; adapter cũ `shop_prepare.py` |
| Adapter lifecycle cũ | `scripts/shop_demo.py` |
| Test Java | `scripts/test.ps1`, `src/test/java/com/example/proxy/TestSuite.java` |
| Test Python | `scripts/ops/test_*.py`, `scripts/test_defense_live.py` |
| Store / browser QA | `scripts/verify.py`, `verify_site.py`; cần private env QA |
| Hạ tầng lab | `scripts/lab.py`, `qa_paths.py`; schema, files, process và evidence riêng |
| Nghiên cứu / benchmark | `scripts/research.py`, `research_release.py`, `assemble_research.py`, `profile_lab.py`, `bulkhead.py`, `failure.py`, `defense_live.py` |
| Operations / backup / restore | `scripts/ops/pg_safeguard.py`, `consistent_bundle.py`, `health_snapshot.py`, `maintenance.py`, `proxy_watchdog.py`, `release_gate.py`, `storage_audit.py` |
| Acceptance có ghi dữ liệu | `scripts/ops/acceptance_live.py`, `acceptance_streaming.py`, `acceptance_docker_ci.py`; chỉ môi trường riêng |
| Đo LAN / phân bố tài nguyên | `scripts/ops/lan_benchmark.py`, `heterogeneous_benchmark.py`; cần host khác thật |
| Docker operator / TCP fault fixture | `scripts/ops/docker_operator.py`, `tcp_gate.py`; JDBC probe: `LiveJdbcProbe.java` |
| Windows LAN | `deploy/lan/windows.ps1`, `windows_nodes.py`, `package.py`, `lan_config.py`, `lan_check.py` |

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test.ps1
mvn -B --no-transfer-progress -Pshop test
python -m unittest discover -s scripts/ops -p "test_*.py" -v
python scripts/test_defense_live.py
```

`run.ps1 start` có thể khởi tạo DB ở lần chạy đầu; khi chỉ kiểm thử launcher,
phải dùng `-EnvFile <private-QA.env>` và `PBL4_RUNTIME_DIR` tuyệt đối trỏ vào vùng
QA mới. Không chạy setup/migrate/seed trên database thật để kiểm tra refactor.
Xem [operations](../../scripts/ops/README.md) và [deployment](../../deploy/README.md).

Các script sửa Word một lần của workspace đã được lưu trữ riêng; DOCX và kết
quả vẫn giữ nguyên. Vị trí và manifest: [chỉ mục báo cáo](../../../reports/README.md).
