# Kiến trúc và thuật toán để trình bày PBL4

**Cập nhật Adaptive:** phần score theo latency/count dưới đây mô tả baseline và các metric tổng hợp vẫn xuất để đối chiếu. Quyết định Adaptive hiện dùng EWMA service theo route, estimated outstanding work và admission theo deadline, trong cùng Dispatcher/Lease; xem [thiết kế và benchmark Mixed trước/sau](ADAPTIVE_COMPLETION.md). RR/LC, transport, backend demo và công thức resource penalty không đổi.

## Luồng xử lý

```mermaid
flowchart LR
    C[Client HTTP] --> H[JDK HttpServer + virtual threads]
    H --> A[Admission + kiểm tra body]
    A --> D[Dispatcher: chọn và giữ chỗ trong một khóa]
    D --> T[TCP Socket: HTTP/1.1]
    T --> B1[Backend 1]
    T --> B2[Backend 2]
    T --> B3[Backend 3]
    T --> E[Đo latency, lỗi; trả lease]
    E --> M[BackendMetrics]
    P[HealthChecker: một vòng poll mỗi backend] --> B1
    P --> B2
    P --> B3
    P --> M
    M --> D
    M --> J[/__proxy/metrics JSON]
```

JDK `HttpServer` xử lý HTTP phía client. Proxy tự mở `Socket` TCP tới backend, tạo request HTTP/1.1 và đọc response. Mỗi upstream request dùng một socket, đóng sau khi nhận response; đây không phải TCP tunnel tổng quát và chưa có connection pool. Việc phân tích upstream HTTP, timeout và truyền byte vẫn nằm trong mã nhóm có thể đọc/trình bày.

Virtual threads cho phép đợi I/O mà không cần một platform thread cho mỗi request. Virtual threads không khiến CPU vô hạn: semaphore admission, giới hạn inflight từng backend, giới hạn body/header và timeout vẫn cần thiết. Body được đệm có giới hạn để biết kết quả upstream trước khi trả client; dung lượng tối đa mỗi body mặc định 1 MiB. Với nhiều request cùng lúc, tổng RAM còn gồm nhiều bản sao body, đối tượng HTTP và bộ đệm JDK; `max.inflight × max.body` không phải giới hạn chính xác toàn bộ heap.

## Metric nào lấy ở đâu?

| Metric | Nguồn | Ý nghĩa / giới hạn |
|---|---|---|
| inFlight | Dispatcher tại proxy | Lease đã giữ chỗ chưa hoàn tất, cập nhật ngay khi giao việc. Với HTTP/1.1 không multiplex, gần với số upstream connection bận; còn gồm giai đoạn đang kết nối. |
| latencyEwmaMs | Đồng hồ monotonic tại proxy | Thời gian kết nối + backend xử lý/chờ + nhận hết response. Không gồm client upload/download. Chỉ học latency từ request không lỗi để lỗi trả nhanh không làm server trông hấp dẫn. |
| errorEwma | Kết quả upstream | Mẫu 1 cho lỗi transport, HTTP 5xx hoặc 429; mẫu 0 cho thành công. Không coi lỗi client ngắt tải xuống là lỗi backend. |
| cpuLoad | Backend `/metrics` | CPU tiến trình được chuẩn hóa theo CPU budget công bố. Không phải số proxy tự suy ra. |
| memoryLoad | Backend `/metrics` | Heap JVM đã dùng / heap tối đa; không phải RSS hay RAM toàn host. |
| activeRequests, queuedRequests | Backend `/metrics` | Request đang thực thi và đang đợi slot, gồm traffic trực tiếp không đi qua proxy. |
| hostCpuLoad, hostMemoryLoad | Backend demo | Chỉ báo cáo để quan sát môi trường chung, không cộng lần nữa vào score. |
| capacity | Cấu hình proxy | Năng lực phục vụ song song do người vận hành đặt. Trường thứ ba `host:port:capacity` thay thế nghĩa weight cũ. Với demo phải khớp capacity backend. |

Backend demo lấy chênh lệch `getProcessCpuTime()` mỗi 250 ms: `processCpuCores = deltaCpuNanos / deltaWallNanos`, rồi `cpuLoad = clamp(processCpuCores / cpuBudget, 0, 1)`, với `cpuBudget=capacity` mặc định (tham số CLI thứ năm có thể đổi). Đây là cách diễn giải CPU của workload theo ngân sách lõi logic đã khai báo, **không đặt quota CPU thật**. Ví dụ một worker chiếm một lõi có thể dùng ít phần trăm toàn máy 32 lõi nhưng đã dùng gần hết budget 1 lõi. Nếu budget sai, score sai theo; backend thật cần cung cấp metric có mẫu số phù hợp container/cgroup/máy thực tế. Số âm hoặc không đo được được biểu diễn là unavailable.

Contract `/metrics`: HTTP 200, UTF-8 text theo Java properties, body tối đa 16 KiB:

```properties
cpuLoad=0.65
memoryLoad=0.40
activeRequests=3
queuedRequests=1
capacity=8
```

Hai trường request là bắt buộc, không âm. CPU/memory là ratio [0,1], có thể thiếu hoặc `-1`/`NaN` khi unavailable. `capacity` trong telemetry phục vụ kiểm tra/benchmark; scheduler dùng capacity cấu hình, không tự tin giá trị do endpoint gửi về. Có thể gửi thêm host/process/heap metric để benchmark ghi lại. Endpoint ứng dụng vẫn UP khi telemetry thiếu hoặc lỗi.

## Công thức adaptive thực tế

Với mỗi backend còn hoạt động và chưa chạm admission limit:

```text
EWMA(x) = previous + alpha × (sample - previous), alpha mặc định 0.2

w = clamp(timeSinceRecovery / warmupDuration, 0.1, 1)
c = configuredCapacity × w
external = max(0, remoteActive + remoteQueued - proxyInFlightAtProbeStart)
q = proxyInFlightNow + (telemetryFresh ? external : 0)

cpuPressure = cpu / max(0.05, 1 - cpu)
memoryPressure = max(0, (memory - 0.75) / 0.25)
uncertainty = 0.25 nếu telemetry cũ/thiếu
            = 0.125 nếu telemetry mới nhưng thiếu CPU hoặc memory
            = 0 khi đầy đủ

penalty = 1 + 0.35 × cpuPressure + 0.5 × memoryPressure
            + 2 × errorEWMA + uncertainty
scoreMs = latencyEWMA × (1 + (q + 1) / c) × penalty
```

Chọn score thấp nhất, phá hòa bằng backend lâu nhất chưa được chọn. Mỗi 50 lần dispatch (cấu hình được), chọn backend hợp lệ lâu nhất chưa được chọn để thăm dò. Thăm dò vẫn tuân theo DOWN và giới hạn warm-up/inflight. Nó có thể tăng một ít tail latency nhưng tránh loại vĩnh viễn backend từng chậm. Với lưu lượng rất thấp, việc học lại cũng chậm: khoảng thăm dò được tính theo request, không phải thời gian.

Các phần CPU, memory, error và uncertainty không có đơn vị. `q/c` là tỷ lệ lượng việc so với năng lực song song; latency cung cấp thang thời gian ms. `+1` tính cả request sắp giao. `scoreMs` là **điểm chi phí mang thang ms**, không phải dự báo thời gian đã được hiệu chuẩn hay xác suất thống kê.

Hệ số CPU 0.35 khiến áp lực tăng mạnh gần bão hòa nhưng không bỏ qua latency. Memory chỉ bị phạt sau 75% heap và tối đa thêm 0.5 vì heap cao không tự động đồng nghĩa xử lý chậm. Error tối đa thêm 2 giúp giảm ưu tiên server trả lỗi nhanh. Các hệ số này là giả thuyết kỹ thuật có chủ đích, chưa được tối ưu cho mọi workload; cần thí nghiệm sensitivity trước khi tuyên bố tối ưu. Cấu hình hiện cho phép chỉnh alpha, latency khởi tạo, warmup, exploration; hệ số penalty nằm tập trung trong `BackendMetrics.snapshot()` để dễ kiểm tra trong báo cáo.

Latency và CPU/memory đều được làm mượt. Mẫu latency đầu tiên thay prior 50 ms; mẫu sau theo EWMA. Latency phản ánh cả hàng đợi nên công thức có thể phạt hai lần tác động của chờ đợi. Công thức external cũng chỉ là xấp xỉ: request có thể hoàn thành trong lúc polling, không có giao dịch phân tán giữa proxy và backend. Cập nhật lease trực tiếp bảo đảm tránh lỗi nhiều thread cùng nhìn bộ đếm cũ trong một proxy; không bảo đảm phối hợp tuyệt đối giữa nhiều proxy độc lập.

Khi telemetry quá `metrics.stale.ms`, CPU/memory/remote queue bị bỏ khỏi score và thêm uncertainty. Giá trị cũ vẫn có thể hiển thị trong JSON, đi kèm `telemetryFresh=false` và tuổi mẫu; không được trình bày như tải hiện tại. Latency/error trực tiếp vẫn có tác dụng. Không dùng độ trễ `/health` để thay độ trễ công việc vì hai endpoint có chi phí khác nhau.

## Race condition, health và hồi phục

`Dispatcher` giữ một `ReentrantLock` cho các thao tác ngắn: lấy snapshot, chọn backend, tăng lease; completion và health cũng đi qua khóa đó. Không giữ khóa khi connect/read/write hoặc đợi health. Nhờ vậy hai thread không cùng chọn dựa trên số inflight chưa được giữ chỗ. `Lease.complete/close` có tính idempotent dưới cùng khóa: timeout, ngoại lệ và finally không trừ counter hai lần. Counter âm được coi là lỗi lập trình, không bị che bằng `max(0, count-1)`.

Backend bắt đầu DOWN cho đến đủ số health probe 2xx thành công liên tiếp (mặc định 2). Một probe thất bại hoặc lỗi transport làm DOWN; HTTP 5xx/429 chỉ tăng error penalty. Probe cũ không được ghi đè lỗi transport mới nhờ generation counter. Khi DOWN không cấp lease mới. Request đã cấp trước khi phát hiện sự cố có thể vẫn đang chạy và có thể thất bại: không thể bảo đảm không mất request khi server chết giữa chừng. Không tự retry để tránh thực hiện POST hai lần.

Sau hồi phục, warmup tăng từ 10% đến 100%. Nó giảm effective capacity và giới hạn inflight `max(1, ceil(backend.max.inflight × w))`. RR và LC cũng dùng cùng health/admission/giới hạn warmup; adaptive bổ sung ảnh hưởng lên score. Nếu tất cả backend cùng khởi động, slow start không tạo ra năng lực dư để phục vụ burst lớn; client có thể nhận 503.

Polling từng backend chạy độc lập trên virtual thread, nên một backend timeout không ngăn kiểm tra các backend khác. Mỗi lượt health/metrics có timeout tổng và body bound. Probe lỗi metrics không làm ứng dụng DOWN. Khi dừng server, executor và client polling được đóng; các script chỉ cleanup PID do chính chúng tạo.

## Vì sao có thể tốt hơn RR/LC, và khi nào không?

RR phù hợp backend đồng đều và request có chi phí tương tự, không cần học metric. LC phản ứng nhanh với lượng việc outstanding, nhưng một request trên backend đang bị tải CPU ngoài proxy có thể nặng hơn nhiều request trên backend khác. Adaptive có thêm tín hiệu từ latency thực, capacity và telemetry nên có cơ sở tránh backend ít kết nối nhưng xử lý chậm hoặc thiếu tài nguyên.

Adaptive có thể thua khi backend đồng đều, metric nhiễu, workload đổi nhanh hơn chu kỳ poll/EWMA, capacity khai báo sai, hoặc CPU/RAM không phải nút thắt (ví dụ database dùng chung). Khởi động/học lại và thăm dò cũng có chi phí. Không nhận diện trước request nặng/nhẹ theo URL; `/compute` và `/work?cost=...` chỉ tạo workload thí nghiệm. Cost-aware scheduling theo loại request là phần mở rộng riêng, cần học theo nhóm route và tránh cardinality vô hạn.

## Liên hệ hai học phần và tài liệu

- **Mạng máy tính:** kết nối TCP client/proxy/backend, socket, HTTP framing/headers, timeout, health detection, HTTP reverse proxy, phân tải.
- **Hệ điều hành:** virtual/platform threads, chờ I/O, semaphore, shared state và khóa, giới hạn tài nguyên, process CPU/heap, nhiều JVM backend độc lập.
- [JDK 21 OperatingSystemMXBean](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.management/com/sun/management/OperatingSystemMXBean.html) định nghĩa metric hệ điều hành/tiến trình và khả năng unavailable.
- [Envoy slow start](https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/load_balancing/slow_start) là tài liệu đối chiếu ý tưởng tăng tải theo thời gian hồi phục. Mã ở đây tự triển khai, không dùng Envoy.

Giới hạn HTTP và hướng dẫn chạy nằm trong README; phương pháp và kết quả đo thực tế nằm trong `BENCHMARK_RESULTS.md`.
