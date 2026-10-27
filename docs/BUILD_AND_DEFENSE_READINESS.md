# Build độc lập và màn hình giải thích quyết định

Phiên rà soát sau refactor ngày 26/10/2026 tập trung vào build có thể chạy lại,
Decision Explorer dùng dữ liệu thật và một ablation độc lập. Không tách tiếp
Dispatcher hoặc thêm framework/tính năng production.

## Build từ source

Dùng **JDK 21**, không chỉ `javac --release 21` với một runtime bất kỳ. Build,
test và run scripts chọn `java/javac/jar` từ cùng thư mục JDK và từ chối JDK 26.
Lỗi test trên JDK 26 trong lượt trước không phải bằng chứng tương thích Java 26.

```powershell
$env:JAVA_HOME = "$PWD\.demo\tools\jdk21-presentation\jdk-21.0.12.1+1" # sửa theo máy thực
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test.ps1
python tools/verify_clean_build.py --java "$env:JAVA_HOME/bin/java.exe" --output target/my-clean-build
```

Output phải là thư mục mới. Linux/macOS dùng `bash scripts/test.sh`, sau đó
chạy cùng Python verifier với `--java "$JAVA_HOME/bin/java"`.

| Nơi | Quyền sở hữu |
|---|---|
| `target/classes`, `target/test-classes` | Có thể được IDE/Maven sử dụng; standalone scripts không đọc/ghi/xóa. |
| `target/standalone/classes`, `target/standalone/test-classes` | Build/test độc lập, clean trước khi compile bằng JDK 21. |
| `target/reverse-proxy-demo.jar` | JAR mới từ standalone build; run scripts, demo và Cluster mặc định dùng JAR. |
| `benchmarks/results/.../classes` | Class của frozen source riêng từng thí nghiệm. |

Verifier tạo bản sao source mới có đường dẫn chứa dấu cách, đặt class lỗi giả lập
vào output IDE, build hai lần, chèn class cũ vào output standalone rồi kiểm clean
đã xóa nó. Nó đối chiếu mọi class/JAR, major version 65, không có problem stub,
giữ nguyên canary IDE và gửi `/work?cost=2` qua proxy/backend từ JAR vừa build.
Đây là kiểm tra cách ly output, không phải tuyên bố đã xác định PID writer của
lỗi trước. Chưa có bằng chứng trực tiếp quy trách nhiệm cho một IDE/plugin cụ thể.

CI chạy Maven và standalone tests trên cả Windows/Ubuntu, thêm fresh source build,
Python tests, validators và smoke. Kết quả của commit cụ thể mới là bằng chứng;
file workflow tồn tại không có nghĩa CI đã PASS.

## Decision Explorer

Sau khi bật `adaptive.decision-trace.enabled=true` và restart ADAPTIVE, mở
`http://127.0.0.1:8080/__proxy/dashboard` tại máy cục bộ. Phần Explorer:

1. Bấm **Gửi /work?cost=2 và giữ ảnh chụp**; chỉ thao tác bấm mới gửi workload.
2. Đọc backend được chọn, route/units/budget/sequence và bảng service, wait,
   **ECT**, penalty, score. Hàng backend được chọn được tô màu.
3. `MINIMUM_SCORE` và `EXPLORATION` có giải thích khác nhau; exploration có thể
   chọn điểm cao hơn. `WORK_BUDGET`, DOWN, circuit/count exclusion và relearning
   được hiển thị theo snapshot, không tự tạo forecast cho ứng viên đã bị loại.
4. **Giữ ảnh chụp** ngăn polling ghi đè; **Theo dõi quyết định mới** bật lại cập nhật.
   Mở JSON nguyên bản để đối chiếu đúng số endpoint.
5. Trace không có request ID: response và snapshot sau response chưa phải khóa
   tương quan tuyệt đối. Đóng traffic khác khi trình bày một request cụ thể.

Listener quản lý riêng chỉ cho đọc, nút gửi workload bị vô hiệu hóa. Lỗi đọc trace
xóa số cũ; snapshot được giữ có nhãn riêng. Favicon nhúng không tạo request proxy
ẩn. Không dùng điểm minh họa 80/60/100 ms hoặc số đo tự dựng trên giao diện.

Kiểm bằng Edge thật (Playwright là dependency của kiểm tra, không phải ứng dụng):

```powershell
python tools/check_decision_explorer.py --java "$env:JAVA_HOME/bin/java.exe" --output target/my-explorer-check
```

Ảnh chụp và `live-decision.json` lấy từ HTTP thật. Render fixtures chỉ dùng để kiểm
exploration/rejection/exclusion/disabled, không dùng làm bằng chứng đo.
[Ảnh Explorer thật](assets/readiness-20261026/explorer-live.png),
[trace đối chiếu](assets/readiness-20261026/live-decision.json),
[kết quả trình duyệt](assets/readiness-20261026/explorer-verification.json) và
[clean build](assets/readiness-20261026/clean-build-verification.json) được giữ riêng.
[Demo 5 phút](DEMO_5_MINUTES.md) và [walkthrough](REQUEST_WALKTHROUGH.md) vẫn áp dụng;
[QA](DEFENSE_QA_SHORT.md) giải thích vòng đời và heuristic.

## Ablation riêng

Runner [tools/ablation.py](../tools/ablation.py) yêu cầu source đã commit và sạch.
Nó tạo ba source copy trong `benchmarks/results`: full, **no-work-admission**
(chỉ bỏ lọc forecast vượt budget) và **no-exploration** (chỉ chọn minimum).
Health, circuit, in-flight/global admission, deadline, lease, service learning và
penalty đều giữ nguyên. Không thêm cờ vô hiệu hóa guard vào cấu hình production.

```powershell
python tools/ablation.py --java "$env:JAVA_HOME/bin/java.exe" --output benchmarks/results/ablation-my-run
python tools/ablation.py --verify benchmarks/results/ablation-my-run
```

Kế hoạch mặc định: Mixed, 300 request đo + 100 warmup, 60 RPS, concurrency 64,
ba lần lặp × ba biến thể. Cùng seed/lịch gửi cho các biến thể trong mỗi lần lặp;
xoay thứ tự biến thể giữa ba lần để giảm lệch thời gian. Lưu patch/source/class,
raw requests/telemetry/config/counters và frozen runner/validator có SHA.

`summary.csv` có successes/errors và tỷ lệ thành công từng loại; p95 là trung vị
p95 **phản hồi thành công** từng lần chạy, không phải p95 gộp. Fairness gap là
max−min tỷ lệ thành công giữa Light/Slow/CPU, không phải bảo đảm công bằng.
Không bỏ qua 503 chỉ vì phản hồi nhanh. Đây là thử nghiệm nhỏ trên một host,
không trộn vào dữ liệu bảo vệ lịch sử và không suy rộng thành benchmark đa máy.

## Kết quả xác minh trên máy trình chiếu

Người dùng xác nhận máy Windows hiện tại là máy bảo vệ. Lượt tổng duyệt dùng
bản sao source mới, build JDK 21, chạy các lệnh trong [demo 5 phút](DEMO_5_MINUTES.md):
Round Robin, Least Connections, Adaptive/trace, backend DOWN → WARMING → UP.
Config được trả về nguyên byte; các cổng demo được giải phóng sau khi dừng.
[Log tổng duyệt](assets/readiness-20261026/demo-rehearsal.txt) lưu diễn biến thực tế,
chỉ chuẩn hóa xuống dòng và bỏ khoảng trắng cuối dòng khi lưu vào Git.

- **16/16 Java test mains PASS**, gồm test 64 client đồng thời: 8 upstream timeout,
  56 admission rejection, permit/lease không âm và trả hết, request tiếp theo thành công.
- **27/27 Python tests PASS**; historical report/raw validators PASS.
- Hai clean build từ source mới có class khớp byte, không có problem stub; khởi động
  proxy/backend từ JAR mới và `/work?cost=2` trả 200. Canary output IDE không đổi.
- Edge thật xác minh trace, freeze, exploration/rejection/exclusion, lỗi fetch và
  listener quản lý. Ảnh/JSON thật liên kết ở phần Explorer phía trên.
- CI của source commit `fbaf979b4338683ce4edbc350b319c956bae8d58` **PASS cả Windows
  và Ubuntu**: [run 40276448758](https://github.com/nguyenvanquanghjhj/reverse-proxy/actions/runs/40276448758),
  [bản ghi trạng thái](assets/readiness-20261026/ci-source-verification.json).
  Run này chưa có bundle ablation; kiểm định bundle được bật cho commit chứa bundle.
- Run `2400ecb` đã **PASS cả hai hệ điều hành**, gồm bundle ablation:
  [run 40278061451](https://github.com/nguyenvanquanghjhj/reverse-proxy/actions/runs/40278061451).
  Trước đó run `ddca18a` có một lần standalone test Ubuntu thất bại với thông báo
  `HTTP/1.1 503 Service Unavailable`; Windows PASS. Chưa tái hiện lỗi này trên máy
  trình chiếu hoặc lần CI kế tiếp. Quyền tải log chi tiết qua API không khả dụng
  (403 với credential Git; 401 với workflow token), nên chưa xác định được nguyên
  nhân của lần lỗi đó. Không coi lần PASS sau là bằng chứng đã tìm ra nguyên nhân.
  Test runner hiện công khai tên suite và stack trace trong CI annotations nếu
  lỗi xuất hiện lại; mọi assertion vẫn giữ nguyên. Workflow chẩn đoán tạm đã bỏ.
- Chín lượt ablation đã hoàn tất, raw/patch/hash/schedule và summary đều PASS.
  [Báo cáo ablation riêng](ADAPTIVE_ABLATION.md) có success/p95 từng workload,
  bảng từng lượt và cảnh báo client lag; không trộn vào benchmark lịch sử.

JDK 26 bị script từ chối có chủ đích; phạm vi xác minh là Java 21. Chưa xác định
PID gây ghi đè output cũ; việc dùng output standalone và JAR đã được kiểm chứng
cách ly khỏi canary ở output IDE. Chưa thực hiện benchmark đa máy/VM.
