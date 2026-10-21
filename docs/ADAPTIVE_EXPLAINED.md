# Adaptive: từ tín hiệu đến quyết định

Mục tiêu là chọn backend có chi phí hoàn tất request mới thấp hơn trong số backend
còn đủ điều kiện. Đây là heuristic ECT (estimated completion time), không phải mô
hình ML hoặc bảo đảm server được chọn luôn nhanh nhất.

## Input và bước lọc

| Input | Nguồn / ý nghĩa |
|---|---|
| Health, circuit state | HealthChecker và completion thực tế; DOWN/OPEN không nhận lease |
| In-flight | Lease giữ tại proxy, cập nhật nguyên tử với selection |
| Capacity và warmup | Capacity cấu hình × hệ số slow start 0.1..1 |
| Service EWMA theo route | Upstream RTT thành công/unit khi lúc cấp lease còn slot, không có tải ngoài |
| Request class/units | 9 nhóm cố định; cost 1..20 của LIGHT/CPU, còn lại units=1 |
| CPU, memory | Ratio process CPU/budget và JVM heap, có freshness |
| Remote active/queued | Telemetry backend; xấp xỉ phần công việc ngoài proxy |
| Error EWMA | 5xx/429, transport errors và exchange không thành công |

Các nhóm: LIGHT (`/work`), SLOW (`/slow`), CPU (`/compute`), PRODUCTS, ORDERS,
FILE_SMALL/MEDIUM/LARGE (`/download` 64/256/768 KiB), OTHER. Không tạo map theo URL
hoặc client. Backend vẫn kiểm tra input thực; cost chỉ là metadata hữu ích cho demo.

## Công thức đúng với code

```text
EWMAnew = EWMAold + alpha × (sample − EWMAold)        alpha mặc định 0.2
service = routeServicePerUnit × units                 ms
c = max(1, floor(configuredCapacity × warmup))         số slot mô phỏng
external = max(0, remoteActive + remoteQueued − localInFlightAtProbeStart)
wait = earliestVirtualSlot + external × genericServiceEWMA / c
ECT = wait + service

cpuPressure = cpu / max(0.05, 1 − cpu)
memoryPressure = max(0, (heapRatio − 0.75) / 0.25)
uncertainty = 0.25 nếu stale; 0.125 nếu fresh nhưng thiếu CPU/heap; 0 nếu đủ
penalty = 1 + 0.35×cpuPressure + 0.5×memoryPressure + 2×errorEWMA + uncertainty
rankingScore = ECT × penalty
B = min(proxy.read.timeout.ms, proxy.request.timeout.ms)
```

Telemetry stale không cung cấp CPU/heap/external cho phép tính. Các hệ số penalty
được giữ nguyên; memory pressure phản ánh heap, không suy ra RAM vật lý hoặc GC pause.

`EstimatedWork.forecast()` mô phỏng reservation FIFO trên các slot bằng priority
queue. Công việc đang chạy giảm phần còn lại theo thời gian dự kiến; công việc chờ
giữ service estimate. Lease còn sống giữ tối thiểu `min(initialMs, serviceMs)`, tránh
tuyên bố một request đã xong chỉ vì đồng hồ dự đoán nó xong.

Lọc theo work: `ECT <= B` và `outstandingWork + service <= c × B`. Nếu estimate quá
cao nhưng backend idle, không có external work và đã nghỉ ít nhất một request
timeout, cho một relearning request. Đây là ngoại lệ có chủ đích để backend không
bị loại vĩnh viễn vì prior cũ. Circuit HALF_OPEN vẫn chỉ cho một probe riêng.

Trong tập còn lại, chọn ranking nhỏ nhất, phá hòa bằng `lastSelectedNanos` cũ nhất;
nếu vẫn hòa, giữ thứ tự cấu hình. Mỗi `adaptive.exploration.interval` lần select
không rỗng (mặc định 50), chọn backend lâu nhất chưa được chọn trong tập hợp lệ.
Exploration có thể chọn score cao hơn; không được giải thích sai thành minimum score.

RR/LC dùng cùng health, circuit, slow start và count limits, nhưng **không dùng work
admission**. So sánh benchmark vì vậy là so sánh cả routing và admission policy.

## Quan sát quyết định thật

Đặt `adaptive.decision-trace.enabled=true`, restart proxy, gửi vài request rồi đọc:

```powershell
curl.exe "http://127.0.0.1:8080/work?cost=2"
curl.exe http://127.0.0.1:8080/__proxy/decision
```

Endpoint loopback cũng có trên listener management nếu bật. GET/HEAD dùng cùng
guard với metrics; không dispatch, không thay quyết định và không tiêu thụ quota.
Mặc định tracing tắt; lúc đó trả `enabled=false,lastDecision=null`. RR/LC không ghi
Adaptive trace kể cả khi flag được bật.

Snapshot giữ **đúng một quyết định gần nhất**, có sequence, route/units, budget,
selectedBackend, reason và bảng candidates gồm service/wait/work/penalty/score trước
reservation. `null` nghĩa là không tính forecast cho backend bị health/circuit/count
lọc. Đây là quan sát một thời điểm; request tiếp theo có thể thay snapshot ngay.
Không có history vô hạn, request ID, raw URL, body hoặc query bí mật.

| Reason / eligibility | Giải thích |
|---|---|
| MINIMUM_SCORE | Chọn minimum trong các candidate đủ điều kiện |
| EXPLORATION | Chọn backend lâu nhất chưa được chọn, có thể score cao hơn |
| DOWN / INFLIGHT_LIMIT / CIRCUIT_UNAVAILABLE | Không tham gia ranking |
| WORK_BUDGET | Forecast vượt budget, chưa được relearn |
| RELEARNING_PROBE | Forecast vượt budget nhưng đủ điều kiện thử học lại |
| MAX_INFLIGHT / DRAINING / NO_ELIGIBLE_BACKEND | Không cấp lease; selectedBackend=null |

Trace được ghi dưới khóa Dispatcher cùng lựa chọn thực; không I/O dưới khóa.
Chỉ tốn bộ nhớ theo số backend cấu hình. Tracing có overhead O(backends), nên giữ
tắt khi đo benchmark và bật lúc demo giải thích.

## Ưu điểm và giới hạn

- Có thể tránh backend ít connection nhưng nhiều công việc chậm; thích hợp với
  backend khác năng lực, latency hoặc áp lực CPU thay đổi.
- Admission giúp giảm chờ/timeout khi đã có estimate hợp lý, nhưng 503 sớm vẫn là lỗi.
- FIFO/slot là xấp xỉ. RTT gồm transport và có thể lẫn queue; chỉ học mẫu không queue
  gây thiếu mẫu trong bão hòa kéo dài. Cold start và health recovery cần học lại.
- `/slow` tốn 2 giây. 60 RPS × 10% slow × 2 giây = 12 slot, đã bằng toàn bộ 3×4
  slot benchmark trước khi phục vụ light/CPU. Routing không tạo thêm công suất.
- Backend đồng nhất, ít tải có thể khiến RR/LC đơn giản và tương đương; telemetry
  lag, overhead và exploration có thể làm Adaptive thua ở tail hoặc từng route.
- Không bảo đảm fairness theo loại request. Xem breakdown slow và lỗi trước khi
  kết luận bằng success RPS tổng. Không đổi trọng số hay fairness dựa trên một run.

`scoreMs` trong metrics là score RTT/count cũ dùng để suy ra resource penalty;
**rankingScoreMs trong decision snapshot** mới là điểm của request được giải thích.
Xem [kết quả và giới hạn dữ liệu](PERFORMANCE_ANALYSIS.md).
