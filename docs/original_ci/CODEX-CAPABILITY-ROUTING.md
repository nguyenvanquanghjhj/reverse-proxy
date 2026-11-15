# Một nhiệm vụ Codex có trọng tâm — Capability-aware routing

**Chỉ dùng sau khi test baseline và backup dữ liệu.** Đây là đặc tả để giao một lượt triển khai,
không phải chức năng đã được code hoặc đã nghiệm thu trong gói file bổ sung.

## Đề bài cho Codex (có thể copy nguyên khối)

> Làm duy nhất capability-aware routing cho repo PBL4 Reverse Proxy, không viết lại các module
> Mộc Store, không đổi database, không sửa dữ liệu/migration, không thêm UI shop và không chỉnh hệ số
> RR/LC/Adaptive. Đọc source hiện tại trước khi sửa: HealthChecker, Dispatcher, BackendMetrics,
> EstimatedWork, ProxyConfig/ConfigLoader, DemoBackendServer, StoreApplication,
> OperationsService.dependencies, StoreController, ProxyHandler.
>
> Backend đã trả `GET /ready` (JSON) gồm `dependencies.capabilities` với trạng thái
> `AVAILABLE`, `UNAVAILABLE`, `PAUSED` theo khả năng xử lý, trong khi `/health` chủ yếu là liveness.
> Nhiệm vụ: bổ sung polling `/ready` bounded (timeout, max body, không ảnh hưởng liveness khi telemetry fail),
> lưu snapshot capability có generation+age để phản hồi cũ không ghi đè mới. Khả năng READY không đồng nghĩa
> toàn bộ capability READY; xử lý riêng bán hàng, download/upload PDF, catalog, static.
>
> Ánh xạ request class có sẵn (`EstimatedWork`) sang capability cần thiết trong một lớp/enum rõ ràng.
> Đảm bảo catalog/ảnh/static không bị loại khi chỉ mất JDBC nếu chúng thực sự còn phục vụ được;
> ORDER_WRITE/ORDER_READ chỉ đi backend mà phụ thuộc DB và service liên quan có thể phục vụ.
> Request chưa phân loại hoặc alias `/api/store/files/{id}` không được đoán MIME và không được giả
> định capability trên cơ sở URL thiếu chứng cứ; chính sách fallback phải nhất quán, viết thành test.
>
> `Dispatcher.acquire` chỉ lọc candidate trước khi chọn; sau lọc RR vẫn luân phiên, LC vẫn chọn
> in-flight thấp nhất, Adaptive giữ score và exploration. Không thay đổi retry: tuyệt đối không
> tự retry POST sau socket failure vì trạng thái commit không biết chắc. Khi không còn backend
> có capability, trả 503 rõ, có metric rejection riêng, không mở circuit của backend khác.
>
> Thêm cờ `capability.routing.enabled=false` mặc định để feature có thể tắt và rollback mà
> không đổi baseline. Khi enable, snapshot unknown/stale phải được quản lý rõ theo từng capability:
> ưu tiên fail-closed với ORDER_WRITE để không chuyển request vào backend không xác thực khả năng;
> có lý do trong decision trace và không làm sai health DOWN/circuit.
>
> Viết test độc lập (không phụ thuộc database thật) tối thiểu: B2 health UP nhưng DB UNAVAILABLE,
> POST order loại B2 và vẫn chọn B1/B3; GET static còn qua B2 nếu được phép; service PDF PAUSED
> không làm backend DOWN; dữ liệu capability stale/late-probe không overwrite generation mới;
> drain/half-open/in-flight vẫn đúng; RR/LC/Adaptive giữ parity khi feature OFF; 100 concurrent
> selection/release không leak lease/negative count; `/ready` malformed/timeout không crash proxy.
>
> Chạy `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test.ps1`, nếu không chạy được
> báo chính xác `NOT RUN`; không đánh dấu PASS giả. Thay đổi tối thiểu, báo file nào sửa, test nào thêm,
> API nào đổi, đánh giá rủi ro và cách rollback bằng một config flag. Không tự sửa README thành lời cam kết
> đã test LAN hoặc nhiều máy.

## Tiêu chí nghiệm thu có thể chứng minh trước giáo viên

1. Bật cap-aware routing; cho B2 `DB UNAVAILABLE`, nhưng process vẫn health UP.
2. Bắn GET route tĩnh và POST order với cùng ba backend; request cần DB tránh B2 ngay khi readiness mới đã được thu thập.
3. Tắt tính năng, thể hiện behavior cũ để so sánh, nhưng tránh tạo đơn thật khi giả lập lỗi.
4. Một PDF-only outage không làm circuit breaker mở cho catalog và order.
5. Chạy regression 19 test mains + test mới, rồi tạo benchmark độc lập ít nhất ba repeat.

Không nên ghép capability routing và upstream connection pooling vào cùng một thay đổi.
