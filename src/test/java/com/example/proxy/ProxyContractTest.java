package com.example.proxy;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.handler.ProxyHandler;
import com.example.proxy.loadbalancer.Dispatcher;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Golden handler contracts, including validation precedence after the JDK parser boundary.
 * Real parser/framing and upstream failures remain covered by the socket integration suites. */
public final class ProxyContractTest {
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        p.setProperty("proxy.max.body.bytes", "8");
        p.setProperty("proxy.max.header.bytes", "1024");
        p.setProperty("proxy.max.header.count", "2");
        var config = new ProxyConfig(p, List.of(new BackendServer("localhost", 9001, 1)));
        try (var handler = new ProxyHandler(config, new Dispatcher(config))) {
            record Case(String method, String target, Map<String, List<String>> headers, int status, String message) { }
            var cases = List.of(
                new Case("CONNECT", "/control", Map.of("Content-Length", List.of("bad")), 501, "CONNECT and protocol upgrades are not supported"),
                new Case("GET", "/a/../control", Map.of(), 403, "Backend control endpoints must be accessed directly"),
                new Case("GET", "http://example.com/work", Map.of(), 400, "Only origin-form request targets are supported"),
                new Case("G ET", "/work", Map.of(), 400, "Invalid request method or target"),
                new Case("GET", "/work#fragment", Map.of(), 400, "Invalid request method or target"),
                new Case("POST", "/work", Map.of("Content-Length", List.of("9"), "Transfer-Encoding", List.of("chunked")), 400, "Ambiguous request framing"),
                new Case("POST", "/work", Map.of("Content-Length", List.of("9")), 413, "Request body exceeds limit"),
                new Case("POST", "/work", Map.of("Content-Length", List.of("nope")), 400, "Invalid Content-Length"),
                new Case("GET", "/work", Map.of("X-Test", List.of("bad\u000b")), 400, "Invalid request header"),
                new Case("GET", "/work", Map.of("X-Test", List.of("a", "b", "c")), 431, "Request headers exceed limit"),
                new Case("GET", "/work", Map.of("X-Test", List.of("x".repeat(1024))), 431, "Request headers exceed limit"),
                new Case("GET", "/work", Map.of(), 503, "No healthy backend has free capacity")
            );
            for (var c : cases) {
                var exchange = new Exchange(c.method(), c.target(), c.headers());
                handler.handle(exchange);
                error(exchange, c.status(), c.message());
                clean(handler);
            }
            // Management historically validates framing/headers only: a data-only target guard must not move here.
            var management = new Exchange("GET", "/control", Map.of());
            handler.handleManagement(management, e -> e.sendResponseHeaders(204, -1));
            check(management.status == 204, "management must not acquire data target policy");
            var framing = new Exchange("GET", "/control", Map.of("Content-Length", List.of("nope")));
            handler.handleManagement(framing, e -> { throw new AssertionError("invalid management request reached action"); });
            error(framing, 400, "Invalid Content-Length");
            var header = new Exchange("GET", "/control", Map.of("X-Test", List.of("bad\u000b")));
            handler.handleManagement(header, e -> { throw new AssertionError("invalid management header reached action"); });
            error(header, 400, "Invalid request header");
            handler.handleManagement(new Exchange("GET", "/", Map.of()), e -> e.sendResponseHeaders(302, -1));
            try {
                handler.handleManagement(new Exchange("GET", "/", Map.of()), e -> { throw new IOException("fixture"); });
                throw new AssertionError("handler swallowed exception");
            } catch (IOException expected) { }
            String counters = "\"requests_total\":17,\"requests_failed\":15,\"requests_rejected\":14,\"upstream_timeouts\":0,"
                    + "\"requests_successful\":1,\"upstream_connection_errors\":0,\"upstream_response_errors\":0,"
                    + "\"upstream_limit_rejections\":0,\"upstream_chunk_metadata_rejections\":0,\"upstream_errors\":0,\"in_flight\":0,"
                    + "\"rate_limit_rejected\":0,\"rate_limit_capacity_rejected\":0,\"rate_limit_clients\":0";
            check(handler.countersJson().equals(counters), "exact JSON counter names, order and classification: " + handler.countersJson());
            String expectedPrometheus = """
                    # TYPE proxy_requests_total counter
                    proxy_requests_total 17
                    # TYPE proxy_requests_failed_total counter
                    proxy_requests_failed_total 15
                    # TYPE proxy_requests_rejected_total counter
                    proxy_requests_rejected_total 14
                    # TYPE proxy_upstream_timeouts_total counter
                    proxy_upstream_timeouts_total 0
                    # TYPE proxy_requests_successful_total counter
                    proxy_requests_successful_total 1
                    # TYPE proxy_upstream_connection_errors_total counter
                    proxy_upstream_connection_errors_total 0
                    # TYPE proxy_upstream_response_errors_total counter
                    proxy_upstream_response_errors_total 0
                    # TYPE proxy_upstream_limit_rejections_total counter
                    proxy_upstream_limit_rejections_total 0
                    # TYPE proxy_upstream_chunk_metadata_rejections_total counter
                    proxy_upstream_chunk_metadata_rejections_total 0
                    # TYPE proxy_upstream_errors_total counter
                    proxy_upstream_errors_total 0
                    # TYPE proxy_in_flight gauge
                    proxy_in_flight 0
                    # TYPE proxy_rate_limit_rejected_total counter
                    proxy_rate_limit_rejected_total 0
                    # TYPE proxy_rate_limit_capacity_rejected_total counter
                    proxy_rate_limit_capacity_rejected_total 0
                    # TYPE proxy_rate_limit_clients gauge
                    proxy_rate_limit_clients 0
                    """;
            check(handler.prometheus().equals(expectedPrometheus), "exact Prometheus schema, order and values");
            clean(handler);
        }
        // Public error API: used by management too. Preserve body, headers, ID and HEAD length for each status.
        for (int status : new int[]{400, 403, 404, 405, 413, 429, 431, 501, 502, 503, 504, 418}) {
            var get = new Exchange("GET", "/", Map.of("X-Request-Id", List.of("contract-1")));
            ProxyHandler.sendError(get, status, "fixture");
            error(get, status, "fixture");
            var head = new Exchange("HEAD", "/", Map.of("X-Request-Id", List.of("contract-1")));
            ProxyHandler.sendError(head, status, "fixture");
            check(head.status == status && head.body.size() == 0 && head.length == -1, "HEAD no body");
            check(Integer.parseInt(head.response.getFirst("Content-Length")) == get.body.size(), "HEAD representation length");
        }
        System.out.println("ProxyContractTest PASS: validation precedence, management policy, error/HEAD JSON, exact metrics, cleanup");
    }
    private static void error(Exchange e, int status, String message) {
        String code = Map.ofEntries(Map.entry(400, "BAD_REQUEST"), Map.entry(403, "FORBIDDEN"), Map.entry(404, "NOT_FOUND"),
                Map.entry(405, "METHOD_NOT_ALLOWED"), Map.entry(413, "BODY_TOO_LARGE"), Map.entry(429, "RATE_LIMITED"),
                Map.entry(431, "HEADERS_TOO_LARGE"), Map.entry(501, "NOT_IMPLEMENTED"), Map.entry(502, "BAD_GATEWAY"),
                Map.entry(503, "UNAVAILABLE"), Map.entry(504, "UPSTREAM_TIMEOUT")).getOrDefault(status, "REQUEST_FAILED");
        String expected = "{\"status\":" + status + ",\"error\":\"" + code + "\",\"message\":\"" + message + "\",\"requestId\":\"contract-1\"}";
        check(e.status == status && e.body.toString(StandardCharsets.UTF_8).equals(expected), "golden error body: " + e.body);
        for (var h : Map.of("Cache-Control", "no-store", "X-Content-Type-Options", "nosniff", "X-Frame-Options", "DENY",
                "X-Request-Id", "contract-1", "Content-Type", "application/json; charset=utf-8", "Connection", "close").entrySet())
            check(h.getValue().equals(e.response.getFirst(h.getKey())), "error header " + h.getKey());
        check(e.response.getFirst("X-Proxy-Backend") == null, "no lease on validation rejection");
    }
    private static void clean(ProxyHandler handler) {
        var d = handler.diagnostics();
        check(d.admissionAvailable() == 128 && d.activeHandlers() == 0 && d.deadlineTasks() == 0
                && d.upstreamSockets() == 0 && d.upstreamDeadlineTasks() == 0, "all resources returned");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Exchange extends HttpExchange {
        final Headers request = new Headers(), response = new Headers();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final String method;
        final URI uri;
        int status = -1;
        long length;
        Exchange(String method, String target, Map<String, List<String>> headers) {
            this.method = method; uri = URI.create(target);
            request.putAll(headers); request.putIfAbsent("X-Request-Id", List.of("contract-1"));
        }
        public Headers getRequestHeaders() { return request; }
        public Headers getResponseHeaders() { return response; }
        public URI getRequestURI() { return uri; }
        public String getRequestMethod() { return method; }
        public HttpContext getHttpContext() { return null; }
        public void close() { }
        public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        public OutputStream getResponseBody() { return body; }
        public void sendResponseHeaders(int code, long length) { status = code; this.length = length; }
        public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 12345); }
        public int getResponseCode() { return status; }
        public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        public String getProtocol() { return "HTTP/1.1"; }
        public Object getAttribute(String name) { return null; }
        public void setAttribute(String name, Object value) { }
        public void setStreams(InputStream in, OutputStream out) { }
        public HttpPrincipal getPrincipal() { return null; }
    }
}
