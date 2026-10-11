package com.example.proxy;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.handler.UpstreamTransport;
import com.example.proxy.server.ReverseProxyServer;
import com.example.proxy.util.Json;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Failure paths use real client sockets and assert resource invariants after each failure. */
public final class HardeningTest {
    public static void main(String[] args) throws Exception {
        json();
        try (Fixture f = new Fixture()) {
            f.ready();
            var valid = f.client.send(HttpRequest.newBuilder(f.uri("/echo")).header("X-Request-Id", "pbl4-123").build(), HttpResponse.BodyHandlers.ofString());
            check(valid.statusCode() == 200, "request accepted");
            check(valid.headers().firstValue("X-Request-Id").orElse("").equals("pbl4-123"), "ID cannot be overwritten by backend");
            check(valid.headers().firstValue("X-Observed-Id").orElse("").equals("pbl4-123"), "ID forwarded");
            for (var header : Map.of("Cache-Control", "no-store", "X-Content-Type-Options", "nosniff", "X-Frame-Options", "DENY").entrySet())
                check(valid.headers().firstValue(header.getKey()).orElse("").equals(header.getValue()), "security header " + header.getKey());
            f.clean();
            var invalidId = f.client.send(HttpRequest.newBuilder(f.uri("/echo")).header("X-Request-Id", "a".repeat(65)).build(), HttpResponse.BodyHandlers.ofString());
            check(invalidId.headers().firstValue("X-Request-Id").orElse("").matches("[a-f0-9-]{36}"), "invalid ID regenerated");
            f.clean();
            for (String path : List.of("/control", "/a/../control", "/%63ontrol", "/%2e/control", "/control;param=x")) {
                check(f.get(path).statusCode() == 403, "normalized management route blocked: " + path);
                f.clean();
            }
            String headers = "X-A: x\r\n".repeat(101);
            check(f.raw("GET /echo HTTP/1.1\r\nHost: localhost\r\n" + headers + "\r\n").contains(" 431 "), "header values counted");
            f.clean();
            for (String framing : List.of("Content-Length: nope", "Content-Length: 1\r\nTransfer-Encoding: chunked", "Content-Length: 1\r\nContent-Length: 2", "Transfer-Encoding: gzip")) {
                String rejected = f.raw("POST /echo HTTP/1.1\r\nHost: localhost\r\n" + framing + "\r\n\r\n");
                check(rejected.contains(" 400 ") || (framing.equals("Transfer-Encoding: gzip") && rejected.contains(" 501 ")), "malformed client framing rejected");
                f.clean();
            }
            long dispatched = f.proxy.getDispatcher().snapshots().getFirst().dispatched();
            try (Socket upload = f.socket()) {
                upload.getOutputStream().write(bytes("POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\na"));
                upload.shutdownOutput();
                upload.getInputStream().readAllBytes();
            }
            f.clean();
            check(f.proxy.getDispatcher().snapshots().getFirst().dispatched() == dispatched, "truncated upload never gets lease");
            try (Socket slow = f.socket()) {
                slow.getOutputStream().write(bytes("POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\na"));
                await(() -> f.proxy.diagnostics().admissionAvailable() == 0, "upload entered");
                f.clean(); // Client intentionally stays connected without finishing its body.
            }
            try (Socket chunk = f.socket()) {
                chunk.getOutputStream().write(bytes("POST /echo HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\nZ\r\n"));
                chunk.shutdownOutput();
                chunk.getInputStream().readAllBytes();
            }
            f.clean();
            check(f.get("/timeout").statusCode() == 504, "backend timeout surfaced");
            f.clean();
            f.ready();
            var json = f.get("/__proxy/metrics");
            check(json.body().contains("\"upstream_timeouts\":1") && !json.body().contains("NaN") && !json.body().contains("Infinity"), "finite monitoring and timeout accounting");
            Files.createDirectories(Path.of("target/test-reports"));
            Files.writeString(Path.of("target/test-reports/monitoring.json"), json.body());
            check(f.get("/__proxy/prometheus").body().contains("proxy_backend_selection_total{backend="), "Prometheus backend counters");
            f.clean();
            try (Socket client = f.socket()) {
                client.getOutputStream().write(bytes("GET /hold HTTP/1.1\r\nHost: localhost\r\n\r\n"));
                check(f.entered.await(2, TimeUnit.SECONDS), "backend request entered before client disconnect");
            }
            f.release.countDown();
            f.clean();
            check(f.proxy.getDispatcher().snapshots().getFirst().inFlight() == 0, "disconnect releases lease");
        }
        shutdown();
        gracefulShutdown();
        transportHeaders();
        System.out.println("HardeningTest passed: IDs, JSON, framing, upload/disconnect, timeout, cleanup and shutdown");
    }
    private static void shutdown() throws Exception {
        try (Fixture f = new Fixture(); Socket upload = f.socket()) {
            f.ready();
            upload.getOutputStream().write(bytes("POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nx"));
            await(() -> f.proxy.diagnostics().admissionAvailable() == 0, "upload started before shutdown");
            long start = System.nanoTime();
            f.proxy.close();
            check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(4), "shutdown bounded for stalled upload");
            f.clean();
            f.proxy.close();
            try { f.proxy.start(); throw new AssertionError("restart accepted"); }
            catch (IllegalStateException expected) { }
        }
        try (Fixture f = new Fixture(); Socket client = f.socket()) {
            f.ready();
            client.getOutputStream().write(bytes("GET /hold HTTP/1.1\r\nHost: localhost\r\n\r\n"));
            check(f.entered.await(2, TimeUnit.SECONDS), "upstream entered");
            f.proxy.close();
            f.clean();
        }
    }
    private static void gracefulShutdown() throws Exception {
        try (Fixture f = new Fixture(2); ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            f.ready();
            var response = f.client.sendAsync(HttpRequest.newBuilder(f.uri("/hold")).build(), HttpResponse.BodyHandlers.ofString());
            check(f.entered.await(2, TimeUnit.SECONDS), "request admitted before graceful shutdown");
            var stopping = tasks.submit(f.proxy::close);
            Thread.sleep(50);
            check(!stopping.isDone(), "shutdown waits for live exchange during grace");
            f.release.countDown();
            check(response.get(3, TimeUnit.SECONDS).statusCode() == 200, "request completes within grace");
            stopping.get(4, TimeUnit.SECONDS);
            f.clean();
        }
    }
    private static void transportHeaders() throws Exception {
        try (UpstreamTransport transport = new UpstreamTransport(100, 100, 200, 32, 1024)) {
            for (Map<String, List<String>> headers : List.of(Map.of("Content-Length", List.of("0"), "Transfer-Encoding", List.of("chunked")),
                    Map.of("X-Test", List.of("x\r\nInjected: y")), Map.of("Connection", List.of("bad\u000b")))) {
                try { transport.exchange("127.0.0.1", 1, "GET", URI.create("/"), headers, new byte[0]); throw new AssertionError("header accepted"); }
                catch (ProtocolException expected) { }
                check(transport.activeSocketCount() == 0 && transport.deadlineTaskCount() == 0, "invalid headers allocate no socket/deadline");
            }
        }
    }
    private static void json() {
        check(Json.quote("\"\\\n\r\t\u0000").equals("\"\\\"\\\\\\n\\r\\t\\u0000\""), "JSON escaping");
        check(Json.number(Double.NaN).equals("null") && Json.number(Double.POSITIVE_INFINITY).equals("null"), "nonfinite JSON numbers");
    }
    private static final class Fixture implements AutoCloseable {
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        final ReverseProxyServer proxy;
        Fixture() throws IOException { this(0); }
        Fixture(int grace) throws IOException {
            backend.setExecutor(executor);
            backend.createContext("/", e -> {
                try (e) {
                    String path = e.getRequestURI().getPath();
                    if (path.equals("/metrics")) { e.sendResponseHeaders(404, -1); return; }
                    if (path.equals("/hold")) { entered.countDown(); release.await(3, TimeUnit.SECONDS); }
                    if (path.equals("/timeout")) Thread.sleep(800);
                    if (path.equals("/echo")) e.getResponseHeaders().set("X-Observed-Id", e.getRequestHeaders().getFirst("X-Request-Id"));
                    e.getResponseHeaders().set("X-Request-Id", "backend-spoof");
                    e.sendResponseHeaders(200, 2); e.getResponseBody().write(bytes("OK"));
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            backend.start();
            Properties p = new Properties();
            p.setProperty("proxy.port", "0"); p.setProperty("proxy.max.inflight", "1");
            p.setProperty("proxy.request.timeout.ms", grace == 0 ? "400" : "2000"); p.setProperty("proxy.read.timeout.ms", grace == 0 ? "200" : "1500");
            p.setProperty("healthcheck.interval.ms", "20"); p.setProperty("healthcheck.recovery.successes", "1");
            p.setProperty("adaptive.warmup.ms", "0"); p.setProperty("proxy.shutdown.grace.seconds", Integer.toString(grace));
            proxy = new ReverseProxyServer(new ProxyConfig(p, List.of(new BackendServer("127.0.0.1", backend.getAddress().getPort(), 4))));
            proxy.start();
        }
        URI uri(String path) { return URI.create("http://127.0.0.1:" + proxy.getPort() + path); }
        Socket socket() throws IOException { Socket s = new Socket("127.0.0.1", proxy.getPort()); s.setSoTimeout(2500); return s; }
        HttpResponse<String> get(String path) throws IOException, InterruptedException {
            return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
        }
        String raw(String text) throws IOException {
            try (Socket socket = socket()) {
                socket.getOutputStream().write(bytes(text));
                socket.shutdownOutput();
                return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            }
        }
        void ready() throws InterruptedException { await(() -> proxy.getDispatcher().hasAvailableBackend(), "healthy backend"); }
        void clean() throws InterruptedException {
            await(() -> {
                var d = proxy.diagnostics();
                return d.admissionAvailable() == 1 && d.activeHandlers() == 0 && d.deadlineTasks() == 0
                        && d.upstreamSockets() == 0 && d.upstreamDeadlineTasks() == 0
                        && proxy.getDispatcher().snapshots().stream().allMatch(s -> s.inFlight() == 0);
            }, "all permits/leases/sockets/deadlines returned");
        }
        public void close() { release.countDown(); proxy.close(); client.shutdownNow(); backend.stop(0); executor.shutdownNow(); }
    }
    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean()) { if (System.nanoTime() >= until) throw new AssertionError(message); Thread.sleep(10); }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.ISO_8859_1); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
