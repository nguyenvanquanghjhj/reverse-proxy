# Theo dấu `GET /work?cost=2`

Đọc cùng [sơ đồ 5 tầng](START_HERE.md). Request mẫu đi qua **HTTP và socket thật**;
`cost=2` chỉ là metadata workload demo, không có nghĩa proxy tự đo được chi phí
chính xác của một request nghiệp vụ bất kỳ.

| Bước | Class / method | Dữ liệu và quyền sở hữu |
|---|---|---|
| 1 | `ReverseProxyServer.start()` | Đăng ký `/` với handler và `/__proxy/` với management; JDK `HttpServer` parse HTTP client. |
| 2 | `BoundedRequestExecutor.execute()` | Giới hạn ingress task trước cả khi parse xong header; truyền deadline qua `RequestDeadline`. |
| 3 | `ProxyHandler.handle()` → `handleTracked()` | Chọn/giữ request ID, tăng counter bắt đầu, đăng ký owner và deadline; thêm security headers. |
| 4 | `ProxyHandler.forward()` → `IncomingRequestValidator` | Lấy admission permit, kiểm method/target/control path → framing → headers, đúng thứ tự cũ; lỗi dừng trước khi cấp lease. |
| 5 | `ClientRateLimiter.acquire()` rồi `ProxyHandler.readBody()` | Quota dùng peer IP; buffer upload có giới hạn. Quota không áp dụng cho management. |
| 6 | `ProxyHandler.forward()` | Loại hop-by-hop headers và thông tin Forwarded giả, đặt request ID/peer IP/proto; kiểm deadline còn lại. |
| 7 | `Dispatcher.acquire(uri)` → `EstimatedWork.Request.of(uri)` | Dưới một lock: LIGHT, units=2; lọc DOWN/circuit/in-flight, dự báo service/chờ, kiểm work budget. |
| 8 | `AdaptiveCandidateScorer` → `AdaptiveLoadBalancer.select()` | Tính `completionMs × resourcePenalty`, chọn min hoặc exploration, phá hòa theo lần chọn cũ nhất/thứ tự cấu hình. |
| 9 | `Dispatcher.acquire()` → `EstimatedWork.reserve()` / `BackendMetrics.reserve()` | Ghi trace snapshot trước reserve; reserve work/in-flight/circuit ticket trước khi nhả lock, trả `Lease`. |
| 10 | `UpstreamTransport.exchange()` → `readResponse()`; `DemoBackendServer.workload()` | Socket gửi origin-form `/work?cost=2`; backend ngủ `delayMs × 2`. Response được parse/buffer có giới hạn, socket/timer được dọn. |
| 11 | `Lease.complete()` rồi `Lease.close()` | Trả reservation và cập nhật EWMA/circuit dưới lock; `released` ngăn complete/close lặp. Nếu không complete thì close chỉ hủy giữ chỗ. |
| 12 | `ProxyHandler.forward()` rồi `handleTracked()` | Chuyển status/headers/body về client, trả admission permit; hủy deadline, bỏ owner, cập nhật `ProxyRequestMetrics` và log. |

Lease **đã kết thúc trước bước tải body xuống client**. Client ngắt ở bước 12
không được tạo thêm lỗi backend hoặc hoàn tất lease lần hai. Lock Dispatcher
không đi qua socket I/O. Trace được ghi trước reserve trong cùng critical section;
refactor giữ nguyên thứ tự này.

Mở mã theo thứ tự: [server](../src/main/java/com/example/proxy/server/ReverseProxyServer.java),
[handler](../src/main/java/com/example/proxy/handler/ProxyHandler.java),
[dispatcher](../src/main/java/com/example/proxy/loadbalancer/Dispatcher.java),
[estimated work](../src/main/java/com/example/proxy/loadbalancer/EstimatedWork.java),
[transport](../src/main/java/com/example/proxy/handler/UpstreamTransport.java),
[backend](../src/main/java/com/example/backend/DemoBackendServer.java).

## Trace từ lần chạy thật

Trace và response được lưu tại [decision.json](assets/readability-20261026/decision.json),
[response.json](assets/readability-20261026/response.json), cùng
[bối cảnh thu thập](assets/readability-20261026/capture.json) và
[cấu hình thực thi](assets/readability-20261026/ADAPTIVE.properties).
Đây là bằng chứng đọc luồng HTTP cục bộ ngày 26/10/2026, **không phải benchmark**.
Fixture dùng cổng trống do hệ điều hành cấp để tránh đụng demo 8080/9001–9003.
Các số được giữ nguyên từ endpoint; không thay cổng thành số minh họa.

Endpoint trả HTTP 200 cho request ID `readability-work-2`, backend `127.0.0.1:55597`.
Body nhận được: `{"server":"backend-55597","cost":2,"capacity":8,"requestNo":9}`.
Đây là toàn bộ decision JSON lấy ngay sau request đó:

```json
{
  "enabled": true,
  "lastDecision": {
    "sequence": 1,
    "route": "LIGHT",
    "units": 2,
    "budgetMs": 3000.0,
    "reason": "MINIMUM_SCORE",
    "selectedBackend": "127.0.0.1:55597",
    "candidates": [
      {
        "backend": "127.0.0.1:55597",
        "eligibility": "ELIGIBLE",
        "inFlight": 0,
        "effectiveCapacity": 8.0,
        "serviceMs": 100.0,
        "waitMs": 0.0,
        "completionMs": 100.0,
        "outstandingMs": 0.0,
        "resourcePenalty": 1.0,
        "rankingScoreMs": 100.0
      },
      {
        "backend": "127.0.0.1:55598",
        "eligibility": "ELIGIBLE",
        "inFlight": 0,
        "effectiveCapacity": 8.0,
        "serviceMs": 100.0,
        "waitMs": 0.0,
        "completionMs": 100.0,
        "outstandingMs": 0.0,
        "resourcePenalty": 1.0,
        "rankingScoreMs": 100.0
      },
      {
        "backend": "127.0.0.1:55599",
        "eligibility": "ELIGIBLE",
        "inFlight": 0,
        "effectiveCapacity": 8.0,
        "serviceMs": 100.0,
        "waitMs": 0.0,
        "completionMs": 100.0,
        "outstandingMs": 0.0,
        "resourcePenalty": 1.0,
        "rankingScoreMs": 100.0
      }
    ]
  }
}
```

Cả ba candidate cùng score 100 ms, nên lần chọn đầu dùng thứ tự backend trong cấu hình.
Service 100 ms là prior 50 ms/unit × 2 trước khi request này huấn luyện EWMA;
không phải latency đo được của request vừa hoàn tất.

`serviceMs` là dự báo cho cả 2 units; `completionMs = waitMs + serviceMs`;
`rankingScoreMs = completionMs × resourcePenalty`. Candidate `inFlight` là trước
reserve nên có thể bằng 0 dù request sau đó đã chạy ở backend được chọn.
`MINIMUM_SCORE` chỉ nói minimum trong tập hợp lệ; `EXPLORATION` có thể chọn score cao hơn.

Để tự quan sát, bật `adaptive.decision-trace.enabled=true`, restart ADAPTIVE theo
[demo](DEMO_5_MINUTES.md), tắt các tab đang gửi workload, rồi chạy liên tiếp:

```powershell
curl.exe -i -H "X-Request-Id: readability-work-2" "http://127.0.0.1:8080/work?cost=2"
curl.exe http://127.0.0.1:8080/__proxy/decision
```

Đối chiếu `X-Proxy-Backend` với `selectedBackend` và `server` trong body. Trace chỉ
giữ quyết định gần nhất, không có request ID; request khác có thể ghi đè giữa hai
lệnh. Đọc management không dispatch, nhưng tải `/demo` có dispatch. Tắt tracing
khi benchmark. [AdaptiveDecisionTest](../src/test/java/com/example/proxy/AdaptiveDecisionTest.java)
kiểm tracing không thay lựa chọn, trạng thái hoặc lộ query bí mật.
