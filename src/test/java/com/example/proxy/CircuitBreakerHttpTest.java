package com.example.proxy;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.server.ReverseProxyServer;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real HTTP status classification and fail/recover; deterministic concurrency is tested separately. */
public final class CircuitBreakerHttpTest {
    public static void main(String[] args) throws Exception {
        AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger(), health = new AtomicInteger();
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        backend.setExecutor(workers);
        backend.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                int code;
                if (path.equals("/health")) { health.incrementAndGet(); code = 200; }
                else if (path.equals("/metrics")) code = 404;
                else { calls.incrementAndGet(); code = status.get(); }
                byte[] body = "backend response".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (path.equals("/large")) body = new byte[256];
                if (path.equals("/headers")) exchange.getResponseHeaders().set("X-Large", "x".repeat(1500));
                exchange.sendResponseHeaders(code, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        backend.start();
        Properties p = new Properties();
        p.setProperty("proxy.port", "0"); p.setProperty("proxy.shutdown.grace.seconds", "0");
        p.setProperty("healthcheck.interval.ms", "20"); p.setProperty("healthcheck.recovery.successes", "1");
        p.setProperty("adaptive.warmup.ms", "0"); p.setProperty("proxy.circuit-breaker.failure-threshold", "2");
        p.setProperty("proxy.circuit-breaker.open-duration.ms", "1000");
        p.setProperty("proxy.max.body.bytes", "128"); p.setProperty("proxy.max.header.bytes", "1024");
        try (var proxy = new ReverseProxyServer(new ProxyConfig(p, List.of(new BackendServer("127.0.0.1", backend.getAddress().getPort(), 4))));
             var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            proxy.start(); await(() -> proxy.getDispatcher().hasAvailableBackend(), "health ready");
            URI url = URI.create("http://127.0.0.1:" + proxy.getPort() + "/test");
            for (String path : List.of("/large", "/headers", "/large", "/headers")) {
                check(get(client, url.resolve(path)).statusCode() == 502, "proxy limit enforced");
                check(proxy.getDispatcher().snapshotJson().contains("\"circuitFailures\":0"), "policy limit excluded from circuit");
                check(proxy.getDispatcher().snapshotJson().contains("\"circuitState\":\"CLOSED\""), "policy limit cannot open circuit");
            }
            String limitMetrics = get(client, url.resolve("/__proxy/metrics")).body();
            check(limitMetrics.contains("\"upstream_limit_rejections\":4")
                    && limitMetrics.contains("\"upstream_chunk_metadata_rejections\":0"), "body/header limit được đếm riêng, không giả thành chunk rejection");
            for (int code : new int[]{400, 401, 403, 404, 400, 401, 403, 404}) {
                status.set(code); check(get(client, url).statusCode() == code, "client error forwarded");
                check(proxy.getDispatcher().snapshotJson().contains("\"circuitFailures\":0"), "client error excluded");
            }
            status.set(500); check(get(client, url).statusCode() == 500, "first 5xx forwarded");
            status.set(429); check(get(client, url).statusCode() == 429, "upstream 429 counted");
            check(proxy.getDispatcher().snapshotJson().contains("\"circuitState\":\"OPEN\""), "mixed backend failures open");
            int dispatched = calls.get(), probes = health.get();
            var rejected = get(client, url);
            check(rejected.statusCode() == 503 && rejected.headers().firstValue("X-Proxy-Backend").isEmpty(), "open rejects without dispatch");
            check(calls.get() == dispatched, "open backend received no request");
            await(() -> health.get() > probes, "health polling continues while open");
            check(proxy.getDispatcher().snapshotJson().contains("\"circuitState\":\"OPEN\""), "health success cannot close circuit");
            await(() -> proxy.getDispatcher().hasAvailableBackend(), "cooldown");
            status.set(503); check(get(client, url).statusCode() == 503, "failed probe forwarded");
            check(proxy.getDispatcher().snapshotJson().contains("\"circuitOpenCount\":2"), "failed probe reopens");
            await(() -> proxy.getDispatcher().hasAvailableBackend(), "second cooldown");
            status.set(200); check(get(client, url).statusCode() == 200, "recovered probe");
            check(proxy.getDispatcher().snapshotJson().contains("\"circuitState\":\"CLOSED\""), "success closes");
            await(() -> proxy.diagnostics().activeHandlers() == 0, "cleanup");
            check(proxy.getDispatcher().snapshots().getFirst().inFlight() == 0 && proxy.diagnostics().upstreamSockets() == 0
                    && proxy.diagnostics().admissionAvailable() == 128, "no resource leaks");
        } finally { backend.stop(0); workers.close(); }
        System.out.println("CircuitBreakerHttpTest PASS: HTTP classification, fail/recover, health independence, cleanup");
    }
    private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void await(java.util.function.BooleanSupplier condition, String description) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean()) { check(System.nanoTime() < end, description + " timed out"); Thread.sleep(10); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
