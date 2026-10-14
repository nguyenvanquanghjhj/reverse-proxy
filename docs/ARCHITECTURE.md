# Kiến trúc và thuật toán để trình bày PBL4

**Cập nhật Adaptive:** phần score theo latency/count dưới đây mô tả baseline và các metric tổng hợp vẫn xuất để đối chiếu. Quyết định Adaptive hiện dùng EWMA service theo route, estimated outstanding work và admission theo deadline, trong cùng Dispatcher/Lease; xem [thiết kế và benchmark Mixed trước/sau](ADAPTIVE_COMPLETION.md). Hệ số resource penalty và RR/LC giữ nguyên; lifecycle, transport và guard hardening được mô tả ở cuối tài liệu.

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

Adaptive có thể thua khi backend đồng đều, metric nhiễu, workload đổi nhanh hơn chu kỳ poll/EWMA, capacity khai báo sai, hoặc CPU/RAM không phải nút thắt (ví dụ database dùng chung). Khởi động/học lại và thăm dò cũng có chi phí. EstimatedWork hiện học theo nhóm route cố định và cost giới hạn của demo, không suy ra chi phí tùy ý cho mọi URL/ứng dụng thực.

## Liên hệ hai học phần và tài liệu

- **Mạng máy tính:** kết nối TCP client/proxy/backend, socket, HTTP framing/headers, timeout, health detection, HTTP reverse proxy, phân tải.
- **Hệ điều hành:** virtual/platform threads, chờ I/O, semaphore, shared state và khóa, giới hạn tài nguyên, process CPU/heap, nhiều JVM backend độc lập.
- [JDK 21 OperatingSystemMXBean](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.management/com/sun/management/OperatingSystemMXBean.html) định nghĩa metric hệ điều hành/tiến trình và khả năng unavailable.
- [Envoy slow start](https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/load_balancing/slow_start) là tài liệu đối chiếu ý tưởng tăng tải theo thời gian hồi phục. Mã ở đây tự triển khai, không dùng Envoy.

Giới hạn HTTP và hướng dẫn chạy nằm trong README; phương pháp đo và cách xác minh artifact nằm trong `BENCHMARK.md`.

## Lifecycle và guard sau hardening 11/10/2026

Phần công thức score ở trên là baseline để đối chiếu. Quyết định hiện tại dùng EstimatedWork/ECT theo route nhân resource penalty, như `ADAPTIVE_COMPLETION.md`; không phải chọn trực tiếp score baseline. Nhóm route/cost được giới hạn, không dùng URL tùy ý làm khóa. Các hệ số vẫn là heuristic, không phải ML hoặc bảo đảm tối ưu.

Admission data-plane được giữ từ trước đọc body đến khi đóng exchange. `finally` trả permit; lease try-with-resources bảo đảm release một lần kể cả ngoại lệ chưa dự đoán. Lease kết thúc trước khi tải response xuống client. Socket đăng ký trước kiểm tra closed; schedule bị reject được chuyển thành IOException. Handler cũng đăng ký thread trước kiểm tra closed; shutdown interrupt thread còn chạy và đóng transport. Không xóa interrupt flag. Mỗi handler dùng virtual thread riêng.

Deadline upload/download interrupt thread sở hữu I/O; deadline upstream đóng socket. Scheduler remove-on-cancel tránh tích lũy task. Mỗi pha có thể dùng đủ `proxy.request.timeout.ms`; connect/read còn chịu timeout nhỏ hơn. Tổng request có thể dài hơn một deadline pha. `HttpServer.stop(grace)` ngừng listener và đợi request hiện tại, sau đó handler/health/executor đóng; không giữ khóa Dispatcher trong I/O. Management có admission riêng 8 request và deadline để không thay đổi budget data-plane dùng trong benchmark.

Validation client diễn ra trước acquire lease: method/target/header/framing sai không làm backend DOWN. Upstream từ chối CL+TE, CL trùng/overflow, TE khác chunked, CL trên 1xx/204, header injection, EOF/truncated body, chunk terminator sai. Header byte/count budget gồm informational headers và trailers. JDK parser ingress có behavior/giới hạn riêng trước handler. Socket luôn đóng nên không có reuse sau framing không chắc chắn.

Health checker không restart sau stop; chỉ bắt lỗi HTTP/timeout/parse cụ thể và công bố failure counters. Lỗi telemetry để mẫu cũ hết hạn, không coi là CPU=0 và không đánh DOWN ứng dụng. Recovery tăng health generation để probe bắt đầu trước recovery không ghi đè state mới. Latency âm/nonfinite không được học nhưng lease vẫn được trả.

Một estimate vượt admission budget trước đây có thể ngăn mọi lần học lại của route đó. Nay backend hoàn toàn rảnh được thử lại tối đa một request sau cooldown `proxy.request.timeout.ms`; request thử vẫn có timeout thật. Guard tránh loại backend/route vĩnh viễn. Atomic reservation, EWMA, warm-up và bounded exploration hạn chế dao động; chưa thêm score hysteresis vì chưa có thí nghiệm cho ngưỡng mới. Không thay hệ số theo benchmark cũ.

JSON dùng helper escape tập trung, số không hữu hạn thành null. Giữ field benchmark cũ, thêm counters handler/probe ở root. `/__proxy/prometheus` xuất request/error/timeout counters, handler in-flight và backend selection/in-flight. Admission không có hàng đợi nên không xuất queue_wait giả. `requests_total` gồm management; backend counters chỉ gồm lease. Snapshot các nhóm counter không nguyên tử cùng nhau. Log không chứa raw query/body/path động.

Giới hạn giữ có chủ ý: không pooling để giữ parser/benchmark một socket/request kiểm chứng được; chưa RSS/GC pause/trace phân tán. JDK không cung cấp callback client disconnect trong lúc backend đang chạy; socket upstream đóng khi hoàn thành, lỗi hoặc timeout. Incomplete ingress header chưa vào handler không thuộc admission/deadline này; cần gateway TLS/auth/rate-limit ở biên khi deploy. Các endpoint quản lý đều loopback-only; không có backend discovery/config động.
## Backend protection (2026-10-14)

Each configured backend has an independent circuit breaker, guarded by the same
Dispatcher lock as selection, work reservations and in-flight accounting. All three
balancing strategies filter circuit eligibility before selecting a backend. OPEN
backends receive no new request leases; if none are eligible the proxy returns 503.
There is no waiting queue or automatic retry.

- CLOSED counts consecutive completed transport/timeout failures, HTTP 5xx and
  upstream HTTP 429. A completed non-failure resets the count. HTTP 400/401/403/404
  do not count as backend failures. Proxy-generated errors before dispatch do not
  touch the circuit. Downstream write failures happen after lease completion.
- OPEN becomes HALF_OPEN lazily on selection/readiness/metrics after the monotonic
  cooldown. Health checks still run and cannot reset the circuit. A backend marked
  DOWN must also satisfy the existing health recovery policy before receiving a probe.
- HALF_OPEN reserves exactly one request under the Dispatcher lock. Success closes
  the circuit; failure reopens it for a fresh cooldown. A cancelled or policy-limited
  probe returns its slot and stays HALF_OPEN, without claiming failure or recovery.
  Generation tickets prevent late
  completions from a previous circuit epoch from changing the current state.
- Leases remain idempotent: complete/close release estimated work and in-flight
  exactly once, including stale completions. Circuit recovery does not bypass
  health recovery, warmup, global/backend capacity, or graceful shutdown.

Configuration: `proxy.circuit-breaker.enabled=true`, `failure-threshold=5`,
`open-duration.ms=5000`, `half-open-max-probes=1` under the same prefix. Threshold
and duration must be positive; probe counts other than 1 are explicitly rejected.
JSON adds `circuitState`, `circuitFailures`, `circuitOpenCount` and
`circuitProbeInFlight` to each backend. Prometheus adds `proxy_backend_circuit_state`
(CLOSED=0, OPEN=1, HALF_OPEN=2), `proxy_backend_circuit_open_total`,
`proxy_backend_circuit_failures` and `proxy_backend_circuit_probe_in_flight`.
Open count only increases on classified backend failures. Proxy body/header limits
do not increment or reset the circuit failure count; error metrics and adaptive
learning still record the unsuccessful exchange without marking the backend DOWN.

The optional client limiter uses token buckets keyed by the numeric TCP peer IP.
`Forwarded` and `X-Forwarded-For` cannot change identity. Clients behind a NAT share
a quota; deployment behind another proxy requires a separate trusted-proxy design.
The limiter runs after header/framing validation, before reading the request body or
acquiring a backend lease. Rejection is JSON HTTP 429 (`RATE_LIMITED`), with
`Retry-After` in seconds and the existing request ID/security headers. Monitoring
and local management endpoints retain their existing access/admission rules and
are exempt from client quotas.

`proxy.rate-limit.enabled=false` preserves the existing demo/benchmark traffic by
default; enable explicitly to enforce limits. Defaults under `proxy.rate-limit.`:
`requests-per-second=100`, `burst=200`, `max-clients=4096`, `idle.ms=60000`.
All values are validated, including finite positive rate and idle time at least a
full burst refill. The bounded access-ordered table evicts only expired idle
buckets. If full, it rejects an unknown peer instead of evicting an active quota.
This avoids quota bypass by client churn and uses no background threads. A single
short synchronized section makes refill, expiration and consumption atomic.

JSON counters: `rate_limit_rejected`, `rate_limit_capacity_rejected`,
`rate_limit_clients`; Prometheus: `proxy_rate_limit_rejected_total`,
`proxy_rate_limit_capacity_rejected_total`, `proxy_rate_limit_clients`.
Capacity rejection is a subset of all rate rejections. No per-IP metric labels are
exported. Idle entries are reclaimed on the next data request, not by monitoring.

Connection pooling and retries are **not implemented** in this increment. Transport
still uses one socket per exchange and `Connection: close`. Safe reuse needs a
separate framing/lifecycle design covering EOF-delimited responses, unread bytes,
stale idle sockets, pool bounds, deadlines and shutdown; retries also need explicit
idempotency rules. No partial pooling path or untested retry is enabled.
`proxy.connection-pool.enabled=false` is explicit; setting it to true fails
configuration validation instead of silently pretending connections are reused.

## Optional management listener

Set `proxy.management.enabled=true` to add a second HTTP listener on
`127.0.0.1:${proxy.management.port}` (default 9090, 0 selects an ephemeral test port).
Its own bounded executor uses `proxy.management.max.handler.tasks=16` and the
existing request deadline, including incomplete header parsing. It shares the
existing monitoring handler, metrics and loopback access/method checks. Only
`/__proxy/metrics`, `/__proxy/prometheus`, `/__proxy/health` are served; other paths
return 404 and never acquire a backend lease. No new authentication or remote
management exposure is introduced. Disabled mode creates no second listener.

Legacy loopback-only management routes on the data listener remain available for
compatibility. Their executor can still saturate with data traffic; monitoring via
the separate listener remains available in that case. The management admission
limit (8), handler counters/deadline scheduler and process CPU/memory are shared:
this isolates ingress task capacity, not all process resources. Management requests
do not consume client tokens. Readiness now includes available data ingress slots.

JSON adds `data_ingress_tasks` and `management_ingress_tasks`; Prometheus adds
`proxy_data_ingress_tasks` and `proxy_management_ingress_tasks` gauges. These include
incomplete headers and the monitoring request itself on its listener; they are not
backend lease counts. Bind failure rolls back both listeners; shutdown stops the
management listener immediately, then uses the configured grace for data requests
and closes both executors. There is no additional management grace period.
