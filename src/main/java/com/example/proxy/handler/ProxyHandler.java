package com.example.proxy.handler;

import com.example.proxy.config.ProxyConfig;
import com.example.proxy.util.AppLogger;
import com.example.proxy.util.Json;
import com.example.proxy.util.RequestDeadline;
import com.example.proxy.loadbalancer.Dispatcher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Admission, HTTP forwarding and exactly-once scheduler completion. */
public final class ProxyHandler implements HttpHandler, AutoCloseable {
    private static final AppLogger log = AppLogger.of(ProxyHandler.class);
    // Cờ đánh dấu handler đã đóng (shutdown). Dùng CAS để close() chỉ chạy đúng 1 lần.
    private final AtomicBoolean closed = new AtomicBoolean();
    // Map: thread đang xử lý request -> request id. Dùng để đếm request đang chạy,
    // để ngắt (interrupt) khi hết deadline hoặc khi shutdown.
    private final Map<Thread, String> owners = new ConcurrentHashMap<>();
    private final ProxyRequestMetrics metrics = new ProxyRequestMetrics();
    private final com.example.proxy.metrics.TrafficMetrics traffic = new com.example.proxy.metrics.TrafficMetrics();
    private final ProxyConfig config;
    // Dispatcher = bộ cân bằng tải, chọn backend và cấp "lease" (quyền dùng 1 slot của backend).
    private final Dispatcher dispatcher;
    // Gửi request thường (có buffer toàn bộ body) tới backend.
    private final UpstreamTransport transport;
    // Gửi request dạng streaming (response lớn, không buffer hết vào RAM).
    private final StreamingProxy streaming;
    private final ClientRateLimiter rateLimiter;
    private final IncomingRequestValidator validator;
    // Kiểm soát số request đồng thời, có chừa slot riêng cho request quan trọng (critical).
    private final PriorityAdmission admission;
    // Bulkhead riêng cho tải file/PDF để download nặng không làm nghẽn các request khác.
    private final DownloadBulkhead downloads;
    // Các endpoint quản trị (/__proxy/...) chỉ cho tối đa 8 request cùng lúc.
    private final Semaphore managementAdmission = new Semaphore(8);
    // Một thread lập lịch duy nhất, dùng để "đánh thức" (interrupt) request quá hạn.
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("client-deadlines").factory());
    public ProxyHandler(ProxyConfig config, Dispatcher dispatcher) {
        this.config = config;
        this.rateLimiter = new ClientRateLimiter(config);
        this.validator = new IncomingRequestValidator(config);
        this.dispatcher = dispatcher;
        this.transport = new UpstreamTransport(config);
        this.streaming = new StreamingProxy(config);
        this.admission = new PriorityAdmission(config.getMaxInflight(),
                config.getReservedCriticalSlots(), config.getQosMaxCriticalPerPeer());
        this.downloads=new DownloadBulkhead(config.getPdfBulkheadMaxInflight(),config.getPdfBulkheadQueueCapacity(),config.getPdfBulkheadWaitMs());
        // Task deadline bị hủy sẽ được xóa khỏi hàng đợi ngay, tránh rò rỉ bộ nhớ.
        deadlines.setRemoveOnCancelPolicy(true);
    }
    // Điểm vào cho request thường (cần forward sang backend).
    @Override public void handle(HttpExchange exchange) throws IOException {
        handleTracked(exchange, null, false);
    }
    // Điểm vào cho request quản trị của chính proxy (metrics, health...), không forward.
    public void handleManagement(HttpExchange exchange, HttpHandler action) throws IOException {
        handleTracked(exchange, action, true);
    }
    // Khung xử lý chung: đo metrics, gắn request id, đặt deadline, ghi log đầu/cuối.
    private void handleTracked(HttpExchange exchange, HttpHandler action, boolean management) throws IOException {
        var result=new RequestResult();
        long started = System.nanoTime();
        String id = requestId(exchange);
        String error = "";
        metrics.requestStarted();
        // Phân loại request (vd DOCUMENT_DOWNLOAD...) để thống kê theo từng loại. Request quản trị không phân loại.
        String requestClass = management ? null : Dispatcher.requestClass(exchange.getRequestMethod(), exchange.getRequestURI());
        if (requestClass != null) traffic.started(requestClass);
        owners.put(Thread.currentThread(), id);
        ClientDeadline deadline = null;
        log.info("event=request_started request_id=" + id);
        try {
            securityHeaders(exchange);
            exchange.getResponseHeaders().set("X-Request-Id", id);
            // Register before checking closed: either close() sees this owner, or this check rejects it.
            if (closed.get()) { Thread.currentThread().interrupt(); exchange.close(); throw new IOException("Proxy handler is closed"); }
            // Route streaming có timeout riêng (dài hơn); route còn lại dùng timeout chung.
            long expires = !management && config.isStreamingEnabled()
                    && StreamingProxy.eligible(exchange.getRequestMethod(), exchange.getRequestURI().getPath())
                    ? RequestDeadline.forRoute(config.getStreamingTimeoutMs())
                    : RequestDeadline.currentOrNew(config.getRequestTimeoutMs());
            deadline = clientDeadline(expires);
            if (management) {
                // Không lấy được permit (>8 request quản trị) thì trả 503 ngay.
                boolean admitted = managementAdmission.tryAcquire();
                try (exchange) {
                    if (!admitted) sendError(exchange, 503, "Proxy capacity exhausted");
                    else if (validRequest(exchange, true)) action.handle(exchange);
                } finally {
                    if (admitted) managementAdmission.release();
                }
            } else forward(exchange, deadline.expires,result);
        } catch (IOException | RuntimeException failure) {
            error = failure.getClass().getSimpleName();
            throw failure;
        } finally {
            // Dọn dẹp + ghi nhận kết quả cuối cùng, luôn chạy dù thành công hay lỗi.
            if (deadline != null) deadline.close();
            owners.remove(Thread.currentThread());
            int status = exchange.getResponseCode();
            if(!error.isEmpty() && result.outcome==null)result.outcome=com.example.proxy.metrics.TrafficMetrics.Outcome.CLIENT_FAILURE;
            metrics.requestCompleted(status, !error.isEmpty(), exchange.getResponseHeaders().getFirst("X-Proxy-Backend") != null);
            if (requestClass != null) traffic.completed(requestClass, status, !error.isEmpty(),
                    exchange.getResponseHeaders().getFirst("X-Proxy-Backend") != null, elapsedMs(started),result.outcome==null?
                    com.example.proxy.metrics.TrafficMetrics.classify(requestClass,status,!error.isEmpty(),exchange.getResponseHeaders().getFirst("X-Proxy-Backend")!=null,null):result.outcome);
            // Quy đổi mã HTTP lỗi thành tên loại lỗi để đưa vào log.
            if (error.isEmpty() && status >= 400) error = switch (status) {
                case 502 -> "UpstreamFailure";
                case 504 -> "UpstreamTimeout";
                case 503 -> "Unavailable";
                default -> "HttpError";
            };
            // Query/body/credentials and arbitrary path segments never enter logs.
            String path = exchange.getRequestURI().getPath();
            // Chỉ log path nằm trong danh sách cho phép; path khác bị che thành "/<redacted>" để tránh lộ dữ liệu nhạy cảm.
            if (!Set.of("/", "/work", "/slow", "/compute", "/echo", "/workload", "/demo", "/products", "/orders",
                    "/download", "/__proxy/metrics", "/__proxy/health", "/__proxy/prometheus").contains(path == null ? "" : path)) path = "/<redacted>";
            log.info("event=request_completed request_id=" + id + " method=" + Json.quote(exchange.getRequestMethod())
                    + " path=" + Json.quote(path) + " backend=" + Json.quote(Optional.ofNullable(exchange.getResponseHeaders().getFirst("X-Proxy-Backend")).orElse(""))
                    + " status=" + status + " latency_ms=" + Json.number(elapsedMs(started)) + " error_type=" + Json.quote(error));
        }
    }
    // Chỉ nhận X-Request-Id từ client nếu hợp lệ (1 giá trị, ký tự an toàn, <=64); ngược lại tự sinh UUID.
    private static String requestId(HttpExchange exchange) {
        List<String> ids = exchange.getRequestHeaders().get("X-Request-Id");
        return ids != null && ids.size() == 1 && ids.getFirst().matches("[A-Za-z0-9._-]{1,64}")
                ? ids.getFirst() : UUID.randomUUID().toString();
    }
    // Các header bảo mật gắn vào mọi response.
    public static void securityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
    }
    // Nhóm hàm xuất số liệu (JSON/Prometheus) cho các endpoint /__proxy/...
    public String countersJson() {
        return metrics.countersJson(owners::size, rateLimiter::json);
    }
    public String prometheus() {
        return metrics.prometheus(owners::size, rateLimiter::prometheus) + admission.prometheus();
    }
    public String trafficJson() { return traffic.json(); }
    public String transportJson() { return transport.metricsJson(); }
    public String connectionPoolJson() { return transport.connectionPoolJson(); }
    public String streamingJson() { return streaming.metricsJson(); }
    public String streamingPrometheus() { return streaming.prometheus(); }
    public String downloadBulkheadJson(){return downloads.json();}
    public String qosJson() { return admission.json(); }
    public String trafficPrometheus() { return traffic.prometheus(); }
    // Ảnh chụp trạng thái nội bộ, dùng để kiểm tra rò rỉ tài nguyên (thread, socket, task).
    public record Diagnostics(int admissionAvailable, int activeHandlers, int deadlineTasks, int upstreamSockets, int upstreamDeadlineTasks) { }
    public Diagnostics diagnostics() {
        return new Diagnostics(admission.availablePermits(), owners.size(), deadlines.getQueue().size(), transport.activeSocketCount(), transport.deadlineTaskCount());
    }
    // Lưu kết quả phân loại của request (thành công, timeout, lỗi DB...) để ghi vào traffic metrics.
    private static final class RequestResult { com.example.proxy.metrics.TrafficMetrics.Outcome outcome; }
    // Luồng chính: admission -> kiểm tra hợp lệ -> rate limit -> bulkhead download -> đọc body
    // -> chuẩn hóa header -> chọn backend -> forward -> trả response về client.
    private void forward(HttpExchange exchange, long expires,RequestResult result) throws IOException {
        // Bước 1: xin slot xử lý. Hết slot thì từ chối 503 (có ưu tiên cho request critical).
        PriorityAdmission.Ticket admissionTicket = admission.tryAcquire(
                exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRemoteAddress().getAddress().getHostAddress());
        boolean admitted = admissionTicket != null;
        DownloadBulkhead.Ticket downloadTicket=null;
        try (exchange) {
            if (!admitted) {
                sendError(exchange, 503, "Proxy capacity exhausted");
                return;
            }
            // Bước 2: kiểm tra Host, method, header... của request đến.
            if (!validRequest(exchange, false)) return;
            // TRACE bị chặn vì có thể bị lợi dụng để lộ header nhạy cảm.
            if (exchange.getRequestMethod().equals("TRACE")) {
                sendFailure(exchange, 501, "TRACE is not supported"); return;
            }
            // OPTIONS + Max-Forwards: theo chuẩn HTTP, = 0 thì proxy tự trả lời, > 0 thì giảm 1 rồi forward.
            if (exchange.getRequestMethod().equals("OPTIONS")) {
                var hops = exchange.getRequestHeaders().get("Max-Forwards");
                if (hops != null) {
                    if (hops.size() != 1 || !hops.getFirst().matches("[0-9]{1,9}")) {
                        sendFailure(exchange, 400, "Invalid Max-Forwards"); return;
                    }
                    int count = Integer.parseInt(hops.getFirst());
                    if (count == 0) {
                        exchange.getResponseHeaders().set("Via", "1.1 pbl4");
                        exchange.sendResponseHeaders(204, -1); return;
                    }
                }
            }
            // Bước 3: giới hạn tốc độ theo IP client. Vượt mức -> 429 kèm Retry-After.
            var rate = rateLimiter.acquire(exchange.getRemoteAddress().getAddress().getHostAddress());
            if (!rate.allowed()) {
                exchange.getResponseHeaders().set("Retry-After", Long.toString(rate.retryAfterSeconds()));
                sendFailure(exchange, 429, "Client request rate exceeded");
                return;
            }
            // Bước 4: request tải file/PDF/streaming phải qua bulkhead riêng. Chờ quá hạn -> 503.
            if(downloads.enabled() && ("DOCUMENT_DOWNLOAD".equals(Dispatcher.requestClass(exchange.getRequestMethod(),exchange.getRequestURI())) || StreamingProxy.eligible(exchange.getRequestMethod(),exchange.getRequestURI().getPath()))){
                downloadTicket=downloads.acquire(expires);
                if(downloadTicket==null){
                    exchange.getResponseHeaders().set("Retry-After","1");
                    exchange.getResponseHeaders().set("X-Proxy-Rejection","download-bulkhead");
                    sendFailure(exchange,503,"Download capacity exhausted");return;
                }
            }
            // Bước 5: đọc body request vào RAM, có giới hạn kích thước (413 nếu quá lớn).
            byte[] body;
            try { body = readBody(exchange.getRequestBody(), config.getMaxBodyBytes()); }
            catch (UpstreamTransport.BodyLimitException e) { sendFailure(exchange, 413, e.getMessage()); return; }
            catch (IOException e) { sendFailure(exchange, 400, "Incomplete request body"); return; }
            // Bước 6: chuẩn hóa header gửi lên backend (bỏ hop-by-hop, chống giả mạo X-Forwarded-*).
            Map<String, List<String>> headers = new LinkedHashMap<>(UpstreamTransport.endToEndHeaders(exchange.getRequestHeaders()));
            if (exchange.getRequestMethod().equals("OPTIONS")) headers.replaceAll((name, values) ->
                    name.equalsIgnoreCase("Max-Forwards") ? List.of(Integer.toString(Integer.parseInt(values.getFirst()) - 1)) : values);
            // Xóa Forwarded/X-Forwarded-* do client gửi để client không giả mạo IP; phần dưới proxy tự đặt lại.
            headers.keySet().removeIf(name -> name.equalsIgnoreCase("forwarded") || name.toLowerCase(Locale.ROOT).startsWith("x-forwarded-"));
            // X-Request-Id và X-Service-Outcome do proxy/backend kiểm soát, không tin giá trị từ client.
            headers.keySet().removeIf(name -> name.equalsIgnoreCase("X-Request-Id")||name.equalsIgnoreCase("X-Service-Outcome"));
            // HttpServer already accepted/read the entire request body. Do not ask upstream to continue it.
            headers.keySet().removeIf(name -> name.equalsIgnoreCase("Expect"));
            // Gộp các Via có sẵn rồi thêm Via của proxy này vào cuối.
            var via = new ArrayList<String>();
            headers.entrySet().removeIf(entry -> { if (!entry.getKey().equalsIgnoreCase("Via")) return false; via.addAll(entry.getValue()); return true; });
            via.add("1.1 pbl4"); headers.put("Via", via);
            headers.put("X-Request-Id", List.of(exchange.getResponseHeaders().getFirst("X-Request-Id")));
            headers.put("X-Forwarded-For", List.of(exchange.getRemoteAddress().getAddress().getHostAddress()));
            headers.put("X-Forwarded-Proto", List.of("http"));
            // Hết hạn hoặc bị ngắt trước khi gọi backend thì dừng luôn (ném timeout).
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= expires) throw new SocketTimeoutException("Request deadline exceeded");
            // Bước 7: nhờ Dispatcher chọn backend khỏe còn slot trống. Không có -> 503.
            Dispatcher.Lease lease = dispatcher.acquire(exchange.getRequestMethod(), exchange.getRequestURI());
            if (lease == null) { sendFailure(exchange, 503, "No healthy backend has free capacity"); return; }
            exchange.getResponseHeaders().set("X-Proxy-Backend", lease.backend().getId());
            log.info("event=backend_selected request_id=" + exchange.getResponseHeaders().getFirst("X-Request-Id") + " backend=" + Json.quote(lease.backend().getId()));
            UpstreamTransport.Response response;
            long start = System.nanoTime();
            // try-with-resources trên lease: đảm bảo slot backend luôn được trả lại.
            // Mỗi nhánh bên dưới phải báo kết quả cho lease ĐÚNG MỘT LẦN (exactly-once completion).
            try (lease) {
                try {
                    // Nhánh streaming: truyền thẳng backend -> client, xong thì return luôn.
                    if (config.isStreamingEnabled() && StreamingProxy.eligible(exchange.getRequestMethod(),exchange.getRequestURI().getPath())) {
                        exchange.getResponseHeaders().set("X-Proxy-Strategy",lease.strategy());
                        int status=streaming.transfer(exchange,lease.backend(),headers,expires);
                        result.outcome=com.example.proxy.metrics.TrafficMetrics.classify(
                            Dispatcher.requestClass(exchange.getRequestMethod(),exchange.getRequestURI()),status,false,true,null);
                        if(status>=400)metrics.upstreamResponseError();
                        if(status==503)lease.completeServiceFailure(elapsedMs(start));
                        else lease.completeHttp(elapsedMs(start),status);
                        return;
                    }
                    // Nhánh thường: gửi request, nhận trọn response vào RAM.
                    response = transport.exchange(lease.backend(), exchange.getRequestMethod(), exchange.getRequestURI(), headers, body, expires);
                    String service=response.headers().entrySet().stream().filter(entry->entry.getKey().equalsIgnoreCase("X-Service-Outcome"))
                            .filter(entry->entry.getValue().size()==1).map(entry->entry.getValue().getFirst()).findFirst().orElse(null);
                    result.outcome=com.example.proxy.metrics.TrafficMetrics.classify(Dispatcher.requestClass(exchange.getRequestMethod(),exchange.getRequestURI()),response.status(),false,true,service);
                    if (response.status() >= 400) {
                        metrics.upstreamResponseError();
                        log.warn("event=upstream_response_error request_id=" + exchange.getResponseHeaders().getFirst("X-Request-Id") + " status=" + response.status());
                    }
                    // 503 do DB/kho lưu trữ phía sau backend lỗi được tính là lỗi dịch vụ; còn lại tính theo mã HTTP.
                    boolean dependency=response.status()==503&&exchange.getRequestURI().getPath().startsWith("/api/store/")
                            &&(result.outcome==com.example.proxy.metrics.TrafficMetrics.Outcome.DATABASE_UNAVAILABLE||result.outcome==com.example.proxy.metrics.TrafficMetrics.Outcome.STORAGE_UNAVAILABLE);
                    if(dependency)lease.completeServiceFailure(elapsedMs(start));
                    else lease.completeHttp(elapsedMs(start), response.status());
                // Response backend vượt giới hạn proxy (body/header/chunk): trả 502, không phạt sức khỏe backend.
                } catch (UpstreamTransport.BodyLimitException | UpstreamTransport.HeaderLimitException | UpstreamTransport.ChunkMetadataLimitException e) {
                    metrics.upstreamLimitRejected(e instanceof UpstreamTransport.ChunkMetadataLimitException);
                    log.warn("event=upstream_limit_rejected request_id=" + exchange.getResponseHeaders().getFirst("X-Request-Id")
                            + " error_type=" + e.getClass().getSimpleName());
                    lease.completePolicyRejection(elapsedMs(start));
                    sendFailure(exchange, 502, "Backend response exceeds proxy limits");
                    return;
                // Hết hạn khi đang streaming: nếu chưa gửi header thì trả 504, đã gửi rồi thì chỉ còn cách ném lỗi.
                } catch (StreamingProxy.DeadlineIOException e) {
                    result.outcome=com.example.proxy.metrics.TrafficMetrics.Outcome.TIMEOUT;
                    lease.completePolicyRejection(elapsedMs(start));
                    if(exchange.getResponseCode()!=-1)throw e;
                    sendFailure(exchange,504,"Streaming route timed out");return;
                // Client ngắt kết nối giữa chừng khi streaming.
                } catch (StreamingProxy.DownstreamIOException e) {
                    result.outcome=com.example.proxy.metrics.TrafficMetrics.Outcome.CLIENT_FAILURE;
                    lease.completeServiceFailure(elapsedMs(start));
                    throw e;
                // Backend quá chậm: trả 504 và ghi nhận backend lỗi.
                } catch (SocketTimeoutException e) {
                    result.outcome=com.example.proxy.metrics.TrafficMetrics.Outcome.TIMEOUT;
                    metrics.upstreamTimeout();
                    log.warn("event=upstream_timeout request_id=" + exchange.getResponseHeaders().getFirst("X-Request-Id"));
                    lease.complete(elapsedMs(start), true, true);
                    sendFailure(exchange, 504, "Backend timed out");
                    return;
                // Các lỗi I/O còn lại (mất kết nối, response HTTP sai...).
                } catch (IOException e) {
                    // Đã gửi header cho client rồi thì không thể gửi thêm response lỗi nữa.
                    if(exchange.getResponseCode()!=-1) {
                        // A downstream disconnect must not mark a healthy backend as failed.
                        if(e instanceof StreamingProxy.DownstreamIOException || e instanceof StreamingProxy.DeadlineIOException) {
                            lease.completePolicyRejection(elapsedMs(start));
                            result.outcome=e instanceof StreamingProxy.DownstreamIOException
                                ? com.example.proxy.metrics.TrafficMetrics.Outcome.CLIENT_FAILURE
                                : com.example.proxy.metrics.TrafficMetrics.Outcome.TIMEOUT;
                        } else {
                            lease.complete(elapsedMs(start),true,true);
                            result.outcome=com.example.proxy.metrics.TrafficMetrics.Outcome.TRANSPORT_FAILURE;
                            metrics.upstreamFailure(e);
                        }
                        throw e; // Cannot send a second HTTP response after committing headers.
                    }
                    // Chưa gửi gì cho client: trả 502 Bad Gateway.
                    result.outcome=com.example.proxy.metrics.TrafficMetrics.Outcome.TRANSPORT_FAILURE;
                    log.warn("event=upstream_error request_id=" + exchange.getResponseHeaders().getFirst("X-Request-Id") + " error_type=" + e.getClass().getSimpleName());
                    metrics.upstreamFailure(e);
                    lease.complete(elapsedMs(start), true, true);
                    sendFailure(exchange, 502, "Backend connection or HTTP response failed");
                    return;
                }
            }
            // Bước 8: gửi response của backend về client (chỉ tới đây với luồng không streaming).
            // The backend has finished. Downstream failures cannot change its health or count twice.
            // Sao chép header backend sang client, trừ Content-Length (sẽ tự đặt) và X-Request-Id (của proxy).
            response.headers().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("content-length") && !name.equalsIgnoreCase("x-request-id")) exchange.getResponseHeaders().put(name, new ArrayList<>(values));
            });
            securityHeaders(exchange);
            String assetPath = exchange.getRequestURI().getPath();
            // Riêng tài nguyên tĩnh /store/assets/ được phép cache nhưng phải kiểm tra lại (revalidate) mỗi lần.
            if (("GET".equals(exchange.getRequestMethod()) || "HEAD".equals(exchange.getRequestMethod()))
                    && assetPath != null && assetPath.startsWith("/store/assets/")
                    && (response.status() == 200 || response.status() == 304))
                exchange.getResponseHeaders().set("Cache-Control", "public, no-cache, must-revalidate");
            exchange.getResponseHeaders().add("Via", "1.1 pbl4");
            exchange.getResponseHeaders().set("X-Proxy-Backend", lease.backend().getId());
            exchange.getResponseHeaders().set("X-Proxy-Strategy", lease.strategy());
            // HEAD, 204, 304 không có body: chỉ gửi header (length -1).
            boolean noBody = exchange.getRequestMethod().equals("HEAD") || response.status() == 204 || response.status() == 304;
            if (noBody) {
                if (response.status() != 204) response.headers().forEach((name, values) -> {
                    if (name.equalsIgnoreCase("content-length")) exchange.getResponseHeaders().put(name, new ArrayList<>(values));
                });
                exchange.sendResponseHeaders(response.status(), -1);
            } else if (response.body().length == 0) {
                exchange.getResponseHeaders().set("Content-Length", "0");
                exchange.sendResponseHeaders(response.status(), -1);
            } else {
                exchange.sendResponseHeaders(response.status(), response.body().length);
                exchange.getResponseBody().write(response.body());
            }
        } finally {
            // Luôn trả lại vé bulkhead và vé admission, kể cả khi lỗi.
            if(downloadTicket!=null)downloadTicket.close();
            if (admissionTicket != null) admissionTicket.close();
        }
    }
    // Kiểm tra request đến; nếu bị từ chối thì gửi lỗi cho client và trả false.
    private boolean validRequest(HttpExchange exchange, boolean management) throws IOException {
        var rejection = validator.host(exchange.getProtocol(), exchange.getRequestHeaders());
        if (rejection == null) rejection = management
                ? validator.managementRequest(exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRequestHeaders())
                : validator.forwardedRequest(exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRequestHeaders());
        if (rejection == null) return true;
        sendFailure(exchange, rejection.status(), rejection.message());
        return false;
    }
    // Đọc body theo từng khối 8KB, vượt giới hạn thì dừng ngay (chống request khổng lồ làm tràn RAM).
    private static byte[] readBody(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) >= 0) {
            if (count > limit - body.size()) throw new UpstreamTransport.BodyLimitException("Request body exceeds limit");
            body.write(buffer, 0, count);
        }
        return body.toByteArray();
    }
    // Gửi response lỗi dạng JSON thống nhất: {status, error, message, requestId}.
    public static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        securityHeaders(exchange);
        String id = exchange.getResponseHeaders().getFirst("X-Request-Id");
        if (id == null) { id = requestId(exchange); exchange.getResponseHeaders().set("X-Request-Id", id); }
        // Đổi mã HTTP thành mã lỗi dạng chữ cho client dễ xử lý.
        String code = switch (status) {
            case 400 -> "BAD_REQUEST"; case 403 -> "FORBIDDEN"; case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED"; case 429 -> "RATE_LIMITED"; case 413 -> "BODY_TOO_LARGE"; case 431 -> "HEADERS_TOO_LARGE";
            case 501 -> "NOT_IMPLEMENTED"; case 502 -> "BAD_GATEWAY"; case 503 -> "UNAVAILABLE";
            case 504 -> "UPSTREAM_TIMEOUT"; default -> "REQUEST_FAILED";
        };
        log.warn("event=request_rejected request_id=" + id + " status=" + status + " error_code=" + code);
        byte[] bytes = ("{\"status\":" + status + ",\"error\":" + Json.quote(code)
                + ",\"message\":" + Json.quote(message) + ",\"requestId\":" + Json.quote(id) + "}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        // Đóng kết nối sau lỗi để tránh kết nối ở trạng thái lửng.
        exchange.getResponseHeaders().set("Connection", "close");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
    private static double elapsedMs(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private void sendFailure(HttpExchange exchange, int status, String message) throws IOException {
        sendError(exchange, status, message);
    }
    // Tạo bộ hẹn giờ cho request; nếu handler đã đóng thì báo lỗi I/O.
    private ClientDeadline clientDeadline(long expires) throws IOException {
        try { return new ClientDeadline(Thread.currentThread(), expires); }
        catch (RejectedExecutionException closed) { throw new IOException("Proxy handler is closed", closed); }
    }
    // Hẹn giờ cho 1 request: tới hạn mà request chưa xong thì interrupt thread đang xử lý nó.
    private final class ClientDeadline implements AutoCloseable {
        private final Thread owner;
        private final long expires;
        private final ScheduledFuture<?> task;
        private boolean cancelled;
        ClientDeadline(Thread owner, long expires) {
            this.owner = owner;
            this.expires = expires;
            task = deadlines.schedule(this::expire, Math.max(0, expires - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
        // synchronized để expire() và close() không chạy chồng nhau (tránh ngắt nhầm sau khi request đã xong).
        private synchronized void expire() {
            if (!cancelled) {
                // JDK HttpServer uses interruptible SocketChannel I/O. Interrupt the reader/writer
                // itself: exchange.close() from another thread can wait for a blocked drain.
                log.warn("event=request_deadline request_id=" + owners.get(owner));
                owner.interrupt();
            }
        }
        @Override public synchronized void close() {
            cancelled = true;
            task.cancel(false);
            // Each handler owns a fresh virtual thread; preserve interrupts, including external cancellation.
        }
    }
    // Tắt proxy: ngắt mọi request đang chạy rồi đóng transport, streaming và bộ hẹn giờ.
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        owners.forEach((thread, id) -> { log.warn("event=shutdown_cancel request_id=" + id); thread.interrupt(); });
        transport.close();
        streaming.close();
        deadlines.shutdownNow();
    }
}