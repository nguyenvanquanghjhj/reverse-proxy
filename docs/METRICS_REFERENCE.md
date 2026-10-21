# Metrics reference

Đọc snapshot bằng GET/HEAD trên loopback:

```text
/__proxy/metrics      JSON counters và backend snapshots
/__proxy/prometheus   Prometheus text exposition
/__proxy/health       {"ready":true|false}, HTTP 200 hoặc 503
/__proxy/decision     Quyết định Adaptive gần nhất nếu bật tracing
```

Cùng endpoint trên cổng data hoặc management tùy chọn. Management luôn bind
`127.0.0.1`, mặc định tắt; guard dựa trên peer IP, không tin X-Forwarded-For.
Đây là snapshot tại lúc đọc, không phải dashboard realtime. Một lần scrape cũng
là handler/request, không có backend lease và không tiêu thụ token client.

## JSON root

| Field | Loại | Nghĩa / giới hạn |
|---|---|---|
| strategy | String | ROUND_ROBIN, LEAST_CONNECTIONS, ADAPTIVE |
| requests_total | Counter | Tất cả request vào handler, gồm management |
| requests_successful | Counter | Handler hoàn tất 2xx, không có downstream exception |
| requests_failed | Counter | HTTP >=400, chưa có response hoặc downstream exception |
| requests_rejected | Counter | HTTP >=400 chưa có X-Proxy-Backend; thường chưa dispatch |
| upstream_errors | Counter | Exchange lỗi, gồm upstream HTTP >=400 |
| upstream_response_errors | Counter | HTTP >=400, framing/body/header/EOF response lỗi |
| upstream_connection_errors | Counter | IOException kết nối/transport khác response classification |
| upstream_timeouts | Counter | Timeout upstream; có thể client deadline đóng trước khi gửi 504 |
| estimatedWorkRejected | Counter | Adaptive không có candidate khi ít nhất một backend bị work-budget loại |
| rate_limit_rejected | Counter | Proxy từ chối quota hoặc bảng client đầy; không phải upstream 429 |
| rate_limit_capacity_rejected | Counter | Tập con ở trên: bảng client đầy với peer mới |
| rate_limit_clients | Gauge | Client bucket đang giữ; idle cleanup diễn ra khi có data request |
| in_flight | Gauge | Handler đang sống, gồm request scrape hiện tại |
| data_ingress_tasks | Gauge | Task data, gồm parser chưa vào handler |
| management_ingress_tasks | Gauge | Task listener management riêng; 0 nếu disabled |
| health_probe_failures / telemetry_probe_failures | Counter | Poll thất bại; telemetry lỗi không tự làm DOWN |

Counter đơn điệu trong vòng đời một process, reset khi restart. `successful+failed`
không bắt buộc bằng `requests_total`: request có thể còn chạy hoặc là 3xx. Ingress
bị JDK từ chối trước handler có thể không có request ID hoặc các counter này.
Các nhóm snapshot không nguyên tử cùng nhau; không suy ra phép trừ là giao dịch.

## JSON backends[]

| Field | Ý nghĩa |
|---|---|
| id / host / port | Backend tĩnh cấu hình; nhãn hữu hạn |
| state / alive | DOWN, WARMING, UP / health eligibility |
| inFlight | Lease đang giữ; không phải mọi TCP connection |
| dispatched / completed / errors | Counter lease cấp / hoàn tất / exchange không thành công |
| latencyEwmaMs | RTT upstream của mẫu không lỗi, smoothed; không phải p95 hoặc end-to-end latency |
| errorEwma | EWMA ratio lỗi [0,1], không phải counter |
| cpuLoad / memoryLoad | CPU process/budget và heap ratio; null nếu unknown |
| telemetryAgeMs / telemetryFresh | Tuổi mẫu / còn đủ mới để dùng cho ranking |
| remoteActiveRequests / remoteQueuedRequests | Mẫu app executing/queue gần nhất, có thể stale |
| externalOutstanding | max(0, remoteActive+remoteQueued−local tại đầu probe), xấp xỉ tải ngoài |
| capacity / effectiveCapacity | Concurrency cấu hình / đã nhân warmup |
| warmup / inFlightLimit | Hệ số 0..1 / giới hạn lease hiện tại |
| scoreMs | RTT/count score cũ; **không phải ECT request-specific** |
| estimatedOutstandingWorkMs | Forecast công việc còn giữ, Adaptive only |
| estimatedQueuedWorkMs | Phần work xếp hàng ước lượng, không phải queue thật |
| estimatedNextSlotMs | Wait forecast cho nhóm OTHER (`/`), không phải mọi request |
| completionBudgetMs | min(read timeout, request timeout) dùng admission ECT |
| circuitState | CLOSED / OPEN / HALF_OPEN |
| circuitFailures | Gauge lỗi liên tiếp; không tăng do proxy quota/body/header limit |
| circuitOpenCount | Counter lần mở vì backend failure |
| circuitProbeInFlight | Boolean probe HALF_OPEN đang được giữ |

`completed` bao gồm backend HTTP 4xx dù `errors` không tính 400/401/403/404 là lỗi
circuit. Lease đóng trước complete có thể chỉ tăng dispatched. Metrics không tự
chuyển circuit state sau cooldown; selection/readiness mới tiến state nếu phù hợp.
UNKNOWN/không áp dụng dùng null/trường vắng, không giả thành số đo 0.

## Prometheus

| Nhóm tên | Type / label |
|---|---|
| proxy_requests_total, proxy_requests_successful_total, proxy_requests_failed_total, proxy_requests_rejected_total | Counter, không label |
| proxy_upstream_errors_total, proxy_upstream_response_errors_total, proxy_upstream_connection_errors_total, proxy_upstream_timeouts_total | Counter, không label |
| proxy_rate_limit_rejected_total, proxy_rate_limit_capacity_rejected_total | Counter, không label |
| proxy_rate_limit_clients, proxy_in_flight, proxy_data_ingress_tasks, proxy_management_ingress_tasks | Gauge, không label |
| proxy_backend_selection_total | Counter, backend tĩnh |
| proxy_backend_in_flight | Gauge, backend tĩnh |
| proxy_backend_circuit_state | Gauge: CLOSED=0, OPEN=1, HALF_OPEN=2 |
| proxy_backend_circuit_open_total | Counter, backend tĩnh |
| proxy_backend_circuit_failures, proxy_backend_circuit_probe_in_flight | Gauge, backend tĩnh |

Không mọi JSON field đều có bản Prometheus. Backend identifier được validate trước
khi dùng làm label; không có label theo client IP, raw URL hoặc request ID. Không
export NaN/Infinity. Chưa có latency histogram, nên không dùng quantile trên EWMA
để giả percentile; p50/p95/p99 được tính từ raw request CSV trong benchmark.

## Decision snapshot

Xem schema/reason tại [ADAPTIVE_EXPLAINED](ADAPTIVE_EXPLAINED.md). Candidate score
là pre-reservation của một quyết định thực, khác snapshot current load có thể đã
thay đổi. Tracing mặc định tắt, tối đa một snapshot và chỉ có dữ liệu bounded.
