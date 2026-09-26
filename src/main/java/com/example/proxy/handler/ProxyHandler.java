package com.example.proxy.handler;

import com.example.proxy.config.ProxyConfig;
import com.example.proxy.loadbalancer.Dispatcher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Admission, HTTP forwarding and exactly-once scheduler completion. */
public final class ProxyHandler implements HttpHandler, AutoCloseable {
    private final ProxyConfig config;
    private final Dispatcher dispatcher;
    private final UpstreamTransport transport;
    private final Semaphore admission;
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("client-deadlines").factory());
    public ProxyHandler(ProxyConfig config, Dispatcher dispatcher) {
        this.config = config;
        this.dispatcher = dispatcher;
        this.transport = new UpstreamTransport(config);
        this.admission = new Semaphore(config.getMaxInflight());
        deadlines.setRemoveOnCancelPolicy(true);
    }
    @Override public void handle(HttpExchange exchange) throws IOException {
        if (!admission.tryAcquire()) {
            try { sendFailure(exchange, 503, "Proxy capacity exhausted"); } finally { exchange.close(); }
            return;
        }
        ScheduledFuture<?> deadline = clientDeadline(exchange);
        try (exchange) {
            if (exchange.getRequestMethod().equalsIgnoreCase("CONNECT") || exchange.getRequestHeaders().containsKey("Upgrade")) {
                sendFailure(exchange, 501, "CONNECT and protocol upgrades are not supported");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/control") || path.startsWith("/control/") || path.equals("/down") || path.equals("/up")) {
                sendFailure(exchange, 403, "Backend control endpoints must be accessed directly");
                return;
            }
            if (exchange.getRequestURI().isAbsolute() || exchange.getRequestURI().getRawAuthority() != null) {
                sendFailure(exchange, 400, "Only origin-form request targets are supported");
                return;
            }
            if (!validRequestFraming(exchange)) return;
            if (headerSize(exchange) > config.getMaxHeaderBytes()) { sendFailure(exchange, 431, "Request headers exceed limit"); return; }
            byte[] body;
            try { body = readBody(exchange.getRequestBody(), config.getMaxBodyBytes()); }
            catch (UpstreamTransport.BodyLimitException e) { sendFailure(exchange, 413, e.getMessage()); return; }
            catch (IOException e) { sendFailure(exchange, 400, "Incomplete request body"); return; }
            deadline.cancel(false); // Upstream has its own deadline; retain time to send a 504 response.
            Map<String, List<String>> headers = new LinkedHashMap<>(exchange.getRequestHeaders());
            headers.keySet().removeIf(name -> name.equalsIgnoreCase("forwarded") || name.toLowerCase(Locale.ROOT).startsWith("x-forwarded-"));
            headers.put("X-Forwarded-For", List.of(exchange.getRemoteAddress().getAddress().getHostAddress()));
            headers.put("X-Forwarded-Proto", List.of("http"));
            Dispatcher.Lease lease = dispatcher.acquire();
            if (lease == null) { sendFailure(exchange, 503, "No healthy backend has free capacity"); return; }
            UpstreamTransport.Response response;
            long start = System.nanoTime();
            try (lease) {
                try {
                    response = transport.exchange(lease.backend(), exchange.getRequestMethod(), exchange.getRequestURI(), headers, body);
                    lease.complete(elapsedMs(start), response.status() >= 500 || response.status() == 429, false);
                } catch (UpstreamTransport.BodyLimitException | UpstreamTransport.HeaderLimitException e) {
                    lease.complete(elapsedMs(start), true, false);
                    sendFailure(exchange, 502, "Backend response exceeds proxy limits");
                    return;
                } catch (SocketTimeoutException e) {
                    lease.complete(elapsedMs(start), true, true);
                    sendFailure(exchange, 504, "Backend timed out");
                    return;
                } catch (IOException e) {
                    lease.complete(elapsedMs(start), true, true);
                    sendFailure(exchange, 502, "Backend connection or HTTP response failed");
                    return;
                }
            }
            // The backend has finished. Downstream failures cannot change its health or count twice.
            deadline = clientDeadline(exchange);
            response.headers().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("content-length")) exchange.getResponseHeaders().put(name, new ArrayList<>(values));
            });
            exchange.getResponseHeaders().set("X-Proxy-Backend", lease.backend().getId());
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
        } finally { deadline.cancel(false); admission.release(); }
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
        byte[] bytes = (message + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
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
        ScheduledFuture<?> deadline = clientDeadline(exchange);
        try { sendError(exchange, status, message); } finally { deadline.cancel(false); }
    }
    private ScheduledFuture<?> clientDeadline(HttpExchange exchange) {
        // Closing a partially read JDK exchange may drain input: never block the shared timer thread.
        return deadlines.schedule(() -> Thread.startVirtualThread(exchange::close), config.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);
    }
    @Override public void close() { deadlines.shutdownNow(); transport.close(); }
}
