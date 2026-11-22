# Quy trình kiểm thử và QA

Dùng Java 21 và Python 3.12. [Mục lục chung](../README.md) dẫn đến kiến trúc,
vận hành và báo cáo; kết quả thực thi được ghi tại
[PBL4_FINAL_VALIDATION.md](../reports/PBL4_FINAL_VALIDATION.md), không tại trang quy trình này.

## Build và regression

```powershell
java --version
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test.ps1
mvn -B --no-transfer-progress -Pshop test
python -B -m unittest discover -s scripts/ops -p "test_*.py" -v
python -B scripts/test_defense_live.py
```

Chọn launcher PowerShell hoặc Maven để build/test; không cần chạy cả hai mỗi lần
nếu không có lý do kiểm riêng đường đóng gói. TestSuite tự tìm mọi `*Test.java`
và chạy các main với timeout, xuất XML/log tại `target/test-reports/`.
Surefire bị tắt có chủ đích. StoreConditionalHttpTest kiểm HTTP thật trực tiếp/qua
proxy, GET/HEAD asset có/thiếu; WindowsNodesTest ép stdout strict cp1252.

Không chạy launcher build trên checkout có JVM đang phục vụ hoặc khi nó sẽ ghi đè
evidence cần giữ. Dùng bản sao source QA mới, không có `.env.store`/runtime/database
thật, nếu không muốn thay build output local. Quy trình cụ thể và lệnh tái lập đã
được giữ tại mục 12.3 của báo cáo. Build tạo `target/reverse-proxy.jar` từ source;
file này không cần được tracked.

## Live QA và benchmark

Các lệnh sau cần private env QA đã được chuẩn bị riêng, không trỏ vào DB demo:

```powershell
python scripts/verify.py --env-file <private-test-env>
python scripts/ops/acceptance_live.py --env-file <private-test-env>
python scripts/ops/acceptance_streaming.py --env-file <private-test-env>
```

Output mặc định thuộc `evidence/` cạnh checkout; `PBL4_EVIDENCE_ROOT` dùng đường
dẫn tuyệt đối để đổi vùng lưu. `--out` bị từ chối nếu nằm trong checkout/runtime/
shared storage. Giữ log FAIL, config và fingerprint; không dùng PASS của snapshot
khác để xác nhận code mới. Không chạy hai benchmark cùng lúc và không stress DB thật.

Streaming acceptance tạo năm input khoảng 489 MiB. Sau PASS, dừng JVM và kiểm SHA,
script có thể xóa đúng input do nó tạo; `--retain-fixtures` giữ lại. Không tự gọi
cleanup trên raw/QA cũ. Với nhiệm vụ chốt hiện tại, không chạy lại benchmark;
bộ 63 trial vẫn gắn fingerprint trong environment.json và báo cáo.

## CI trên main

[pbl4-ci.yml](../../.github/workflows/pbl4-ci.yml) có bốn job:

| Job | Phạm vi |
|---|---|
| core-java-linux | Maven Java 21, toàn bộ test main và XML |
| core-java-windows | Native build/TestSuite, Python operations và HTTP tooling |
| tools-and-config | Python operations/HTTP, Compose config |
| docker-live | Stack QA riêng, proxy+ba backend+PostgreSQL, control/fault/restart/persistence |

Sau push, dùng `gh run list --workflow pbl4-ci.yml --branch main --commit <FULL_SHA>`
để chọn run tự tạo; `gh run view <RUN_ID>` cho kết quả từng job. Chỉ
`gh workflow run pbl4-ci.yml --ref main` khi xác nhận không có workflow push.
Dùng SHA đầy đủ; không lấy run gần nhất của một commit khác.

Tải artifact bằng `gh run download <RUN_ID> --dir <NEW_QA_DIRECTORY>`. Đối chiếu
XML có StoreConditionalHttpTest, log Python, result.json Docker và head SHA trước
khi ghi PASS. Test main, assertion, checkpoint Docker và repeat benchmark là các
đơn vị khác nhau, không cộng thành một số test tổng.
