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
import java.util.concurrent.atomic.LongAdder;

/** Admission, HTTP forwarding and exactly-once scheduler completion. */
public final class ProxyHandler implements HttpHandler, AutoCloseable {
    private static final AppLogger log = AppLogger.of(ProxyHandler.class);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<Thread, String> owners = new ConcurrentHashMap<>();
    private final LongAdder requests = new LongAdder(), failed = new LongAdder(), rejected = new LongAdder();
    private final LongAdder timeouts = new LongAdder(), upstreamErrors = new LongAdder();
    private final LongAdder successful = new LongAdder(), connectionErrors = new LongAdder(), responseErrors = new LongAdder();
    private final ProxyConfig config;
    private final Dispatcher dispatcher;
    private final UpstreamTransport transport;
    private final ClientRateLimiter rateLimiter;
    private final Semaphore admission;
    private final Semaphore managementAdmission = new Semaphore(8);
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("client-deadlines").factory());
    public ProxyHandler(ProxyConfig config, Dispatcher dispatcher) {
        this.config = config;
        this.rateLimiter = new ClientRateLimiter(config);
        this.dispatcher = dispatcher;
        this.transport = new UpstreamTransport(config);
        this.admission = new Semaphore(config.getMaxInflight());
        deadlines.setRemoveOnCancelPolicy(true);
    }
    @Override public void handle(HttpExchange exchange) throws IOException {
        handleTracked(exchange, null, false);
    }
    public void handleManagement(HttpExchange exchange, HttpHandler action) throws IOException {
        handleTracked(exchange, action, true);
    }
    private void handleTracked(HttpExchange exchange, HttpHandler action, boolean management) throws IOException {
        long started = System.nanoTime();
        String id = requestId(exchange);
        String error = "";
        requests.increment();
        owners.put(Thread.currentThread(), id);
        ClientDeadline deadline = null;
        log.info("event=request_started request_id=" + id);
        try {
            securityHeaders(exchange);
            exchange.getResponseHeaders().set("X-Request-Id", id);
            // Register before checking closed: either close() sees this owner, or this check rejects it.
            if (closed.get()) { Thread.currentThread().interrupt(); exchange.close(); throw new IOException("Proxy handler is closed"); }
            deadline = clientDeadline(RequestDeadline.currentOrNew(config.getRequestTimeoutMs()));
            if (management) {
                boolean admitted = managementAdmission.tryAcquire();
                try (exchange) {
                    if (!admitted) sendError(exchange, 503, "Proxy capacity exhausted");
                    else if (validRequestFraming(exchange) && validRequestHeaders(exchange)) action.handle(exchange);
                } finally {
                    if (admitted) managementAdmission.release();
                }
            } else forward(exchange, deadline.expires);
        } catch (IOException | RuntimeException failure) {
            error = failure.getClass().getSimpleName();
            throw failure;
        } finally {
            if (deadline != null) deadline.close();
            owners.remove(Thread.currentThread());
            int status = exchange.getResponseCode();
            if (status < 0 || status >= 400 || !error.isEmpty()) failed.increment();
            else if (status >= 200 && status < 300) successful.increment();
            if (status >= 400 && exchange.getResponseHeaders().getFirst("X-Proxy-Backend") == null) rejected.increment();
            if (error.isEmpty() && status >= 400) error = switch (status) {
                case 502 -> "UpstreamFailure";
                case 504 -> "UpstreamTimeout";
                case 503 -> "Unavailable";
                default -> "HttpError";
            };
            // Query/body/credentials and arbitrary path segments never enter logs.
            String path = exchange.getRequestURI().getPath();
            if (!Set.of("/", "/work", "/slow", "/compute", "/echo", "/demo", "/products", "/orders",
                    "/download", "/__proxy/metrics", "/__proxy/health", "/__proxy/prometheus").contains(path == null ? "" : path)) path = "/<redacted>";
            log.info("event=request_completed request_id=" + id + " method=" + Json.quote(exchange.getRequestMethod())
                    + " path=" + Json.quote(path) + " backend=" + Json.quote(Optional.ofNullable(exchange.getResponseHeaders().getFirst("X-Proxy-Backend")).orElse(""))
                    + " status=" + status + " latency_ms=" + Json.number(elapsedMs(started)) + " error_type=" + Json.quote(error));
        }
    }
    private static String requestId(HttpExchange exchange) {
        List<String> ids = exchange.getRequestHeaders().get("X-Request-Id");
        return ids != null && ids.size() == 1 && ids.getFirst().matches("[A-Za-z0-9._-]{1,64}")
                ? ids.getFirst() : UUID.randomUUID().toString();
    }
    public static void securityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
    }
    public String countersJson() {
        return "\"requests_total\":" + requests.sum() + ",\"requests_failed\":" + failed.sum()
                + ",\"requests_rejected\":" + rejected.sum() + ",\"upstream_timeouts\":" + timeouts.sum()
                + ",\"requests_successful\":" + successful.sum()
                + ",\"upstream_connection_errors\":" + connectionErrors.sum() + ",\"upstream_response_errors\":" + responseErrors.sum()
                + ",\"upstream_errors\":" + upstreamErrors.sum() + ",\"in_flight\":" + owners.size() + "," + rateLimiter.json();
    }
    public String prometheus() {
        return metric("requests_total", requests.sum(), "counter") + metric("requests_failed_total", failed.sum(), "counter")
                + metric("requests_rejected_total", rejected.sum(), "counter") + metric("upstream_timeouts_total", timeouts.sum(), "counter")
                + metric("requests_successful_total", successful.sum(), "counter")
                + metric("upstream_connection_errors_total", connectionErrors.sum(), "counter")
                + metric("upstream_response_errors_total", responseErrors.sum(), "counter")
                + metric("upstream_errors_total", upstreamErrors.sum(), "counter") + metric("in_flight", owners.size(), "gauge") + rateLimiter.prometheus();
    }
    private static String metric(String name, long value, String type) {
        return "# TYPE proxy_" + name + " " + type + "\nproxy_" + name + " " + value + "\n";
    }
    public record Diagnostics(int admissionAvailable, int activeHandlers, int deadlineTasks, int upstreamSockets, int upstreamDeadlineTasks) { }
    public Diagnostics diagnostics() {
        return new Diagnostics(admission.availablePermits(), owners.size(), deadlines.getQueue().size(), transport.activeSocketCount(), transport.deadlineTaskCount());
    }
    private void forward(HttpExchange exchange, long expires) throws IOException {
        boolean admitted = admission.tryAcquire();
        try (exchange) {
            if (!admitted) {
                sendError(exchange, 503, "Proxy capacity exhausted");
                return;
            }
            if (exchange.getRequestMethod().equalsIgnoreCase("CONNECT") || exchange.getRequestHeaders().containsKey("Upgrade")) {
                sendFailure(exchange, 501, "CONNECT and protocol upgrades are not supported");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!exchange.getRequestMethod().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                    || path == null || !path.startsWith("/")
                    || exchange.getRequestURI().getRawFragment() != null
                    || exchange.getRequestURI().toString().chars().anyMatch(c -> c <= 32 || c >= 127)) {
                sendFailure(exchange, 400, "Invalid request method or target");
                return;
            }
            if (isControlPath(path)) {
                sendFailure(exchange, 403, "Backend control endpoints must be accessed directly");
                return;
            }
            if (exchange.getRequestURI().isAbsolute() || exchange.getRequestURI().getRawAuthority() != null) {
                sendFailure(exchange, 400, "Only origin-form request targets are supported");
                return;
            }
            if (!validRequestFraming(exchange)) return;
            if (!validRequestHeaders(exchange)) return;
            var rate = rateLimiter.acquire(exchange.getRemoteAddress().getAddress().getHostAddress());
            if (!rate.allowed()) {
                exchange.getResponseHeaders().set("Retry-After", Long.toString(rate.retryAfterSeconds()));
                sendFailure(exchange, 429, "Client request rate exceeded");
                return;
            }
            byte[] body;
            try { body = readBody(exchange.getRequestBody(), config.getMaxBodyBytes()); }
            catch (UpstreamTransport.BodyLimitException e) { sendFailure(exchange, 413, e.getMessage()); return; }
            catch (IOException e) { sendFailure(exchange, 400, "Incomplete request body"); return; }
            Map<String, List<String>> headers = new LinkedHashMap<>(UpstreamTransport.endToEndHeaders(exchange.getRequestHeaders()));
            headers.keySet().removeIf(name -> name.equalsIgnoreCase("forwarded") || name.toLowerCase(Locale.ROOT).startsWith("x-forwarded-"));
            headers.keySet().removeIf(name -> name.equalsIgnoreCase("X-Request-Id"));
            headers.put("X-Request-Id", List.of(exchange.getResponseHeaders().getFirst("X-Request-Id")));
            headers.put("X-Forwarded-For", List.of(exchange.getRemoteAddress().getAddress().getHostAddress()));
            headers.put("X-Forwarded-Proto", List.of("http"));
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= expires) throw new SocketTimeoutException("Request deadline exceeded");
            Dispatcher.Lease lease = dispatcher.acquire(exchange.getRequestURI());
            if (lease == null) { sendFailure(exchange, 503, "No healthy backend has free capacity"); return; }
            exchange.getResponseHeaders().set("X-Proxy-Backend", lease.backend().getId());
            log.info("event=backend_selected request_id=" + exchange.getResponseHeaders().getFirst("X-Request-Id") + " backend=" + Json.quote(lease.backend().getId()));
            UpstreamTransport.Response response;
            long start = System.nanoTime();
            try (lease) {
                try {
                    response = transport.exchange(lease.backend(), exchange.getRequestMethod(), exchange.getRequestURI(), headers, body, expires);
                    if (response.status() >= 400) {
                        responseErrors.increment(); upstreamErrors.increment();
                        log.warn("event=upstream_response_error request_id=" + exchange.getResponseHeaders().getFirst("X-Request-Id") + " status=" + response.status());
                    }
                    lease.complete(elapsedMs(start), response.status() >= 500 || response.status() == 429, false);
                } catch (UpstreamTransport.BodyLimitException | UpstreamTransport.HeaderLimitException e) {
                    upstreamErrors.increment(); responseErrors.increment();
                    lease.completePolicyRejection(elapsedMs(start));
                    sendFailure(exchange, 502, "Backend response exceeds proxy limits");
                    return;
                } catch (SocketTimeoutException e) {
                    timeouts.increment(); upstreamErrors.increment();
                    log.warn("event=upstream_timeout request_id=" + exchange.getResponseHeaders().getFirst("X-Request-Id"));
                    lease.complete(elapsedMs(start), true, true);
                    sendFailure(exchange, 504, "Backend timed out");
                    return;
                } catch (IOException e) {
                    log.warn("event=upstream_error request_id=" + exchange.getResponseHeaders().getFirst("X-Request-Id") + " error_type=" + e.getClass().getSimpleName());
                    upstreamErrors.increment();
                    if (e instanceof java.net.ProtocolException || e instanceof EOFException) responseErrors.increment();
                    else connectionErrors.increment();
                    lease.complete(elapsedMs(start), true, true);
                    sendFailure(exchange, 502, "Backend connection or HTTP response failed");
                    return;
                }
            }
            // The backend has finished. Downstream failures cannot change its health or count twice.
            response.headers().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("content-length") && !name.equalsIgnoreCase("x-request-id")) exchange.getResponseHeaders().put(name, new ArrayList<>(values));
            });
            securityHeaders(exchange);
            exchange.getResponseHeaders().set("X-Proxy-Backend", lease.backend().getId());
            exchange.getResponseHeaders().set("X-Proxy-Strategy", config.getLoadBalancerStrategy());
            boolean noBody = exchange.getRequestMethod().equalsIgnoreCase("HEAD") || response.status() == 204 || response.status() == 304;
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
            if (admitted) admission.release();
        }
    }
    private static boolean isControlPath(String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String part : path.replace('\\', '/').split("/")) {
            part = part.split(";", 2)[0];
            if (part.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); }
            else if (!part.isEmpty() && !part.equals(".")) parts.addLast(part);
        }
        return !parts.isEmpty() && Set.of("control", "down", "up").contains(parts.getFirst());
    }
    private boolean validRequestFraming(HttpExchange exchange) throws IOException {
        List<String> lengths = exchange.getRequestHeaders().get("Content-Length");
        List<String> transfer = exchange.getRequestHeaders().get("Transfer-Encoding");
        if ((lengths != null && transfer != null) || (transfer != null && (transfer.size() != 1 || !transfer.getFirst().equalsIgnoreCase("chunked")))) {
            sendFailure(exchange, 400, "Ambiguous request framing"); return false;
        }
        if (lengths != null) {
            try {
                if (lengths.size() != 1 || !lengths.getFirst().matches("[0-9]+")) throw new NumberFormatException();
                if (Long.parseLong(lengths.getFirst()) > config.getMaxBodyBytes()) {
                    sendFailure(exchange, 413, "Request body exceeds limit"); return false;
                }
            } catch (NumberFormatException e) { sendFailure(exchange, 400, "Invalid Content-Length"); return false; }
        }
        return true;
    }
    private boolean validRequestHeaders(HttpExchange exchange) throws IOException {
        for (var entry : exchange.getRequestHeaders().entrySet()) {
            if (!UpstreamTransport.validHeaderName(entry.getKey()) || entry.getValue().stream().anyMatch(v -> !UpstreamTransport.validHeaderValue(v))) {
                sendFailure(exchange, 400, "Invalid request header"); return false;
            }
        }
        if (exchange.getRequestHeaders().values().stream().mapToInt(List::size).sum() > config.getMaxHeaderCount()
                || headerSize(exchange) > config.getMaxHeaderBytes()) {
            sendFailure(exchange, 431, "Request headers exceed limit"); return false;
        }
        return true;
    }
    private static long headerSize(HttpExchange exchange) {
        long size = exchange.getRequestMethod().length() + exchange.getRequestURI().toString().length() + 16;
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet())
            for (String value : entry.getValue()) size += entry.getKey().length() + value.length() + 4;
        return size;
    }
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
    public static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        securityHeaders(exchange);
        String id = exchange.getResponseHeaders().getFirst("X-Request-Id");
        if (id == null) { id = requestId(exchange); exchange.getResponseHeaders().set("X-Request-Id", id); }
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
        exchange.getResponseHeaders().set("Connection", "close");
        if (exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
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
    private ClientDeadline clientDeadline(long expires) throws IOException {
        try { return new ClientDeadline(Thread.currentThread(), expires); }
        catch (RejectedExecutionException closed) { throw new IOException("Proxy handler is closed", closed); }
    }
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
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        owners.forEach((thread, id) -> { log.warn("event=shutdown_cancel request_id=" + id); thread.interrupt(); });
        transport.close();
        deadlines.shutdownNow();
    }
}
