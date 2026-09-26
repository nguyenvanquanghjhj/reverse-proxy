package com.example.proxy;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ConfigLoader;
import com.example.proxy.healthcheck.HealthChecker;
import com.example.proxy.loadbalancer.Dispatcher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Poll real HTTP endpoints: bad telemetry must not take down a healthy service. */
public final class HealthCheckerTest {
    public static void main(String[] args) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer healthy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        HttpServer stalled = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicBoolean reachable = new AtomicBoolean(true);
        healthy.setExecutor(executor);
        stalled.setExecutor(executor);
        healthy.createContext("/health", e -> respond(e, reachable.get() ? 200 : 503, "health"));
        healthy.createContext("/metrics", e -> respond(e, 200, valid.get()
                ? "cpuLoad=0.7\nmemoryLoad=0.5\nactiveRequests=2\nqueuedRequests=1\n"
                : "cpuLoad=9\nmemoryLoad=0.5\nactiveRequests=2\nqueuedRequests=1\n"));
        stalled.createContext("/health", e -> {
            try { Thread.sleep(1000); respond(e, 200, "late"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); e.close(); }
            catch (java.io.IOException ignored) { e.close(); }
        });
        healthy.start(); stalled.start();
        Properties p = new Properties();
        p.setProperty("backend.servers", "127.0.0.1:" + stalled.getAddress().getPort() + ":4,127.0.0.1:" + healthy.getAddress().getPort() + ":4");
        p.setProperty("healthcheck.interval.ms", "40");
        p.setProperty("healthcheck.timeout.ms", "150");
        p.setProperty("healthcheck.recovery.successes", "2");
        p.setProperty("metrics.stale.ms", "150");
        p.setProperty("adaptive.warmup.ms", "0");
        var config = ConfigLoader.load(p);
        Dispatcher dispatcher = new Dispatcher(config);
        BackendServer good = config.getBackendServers().get(1);
        try (HealthChecker checker = new HealthChecker(config, dispatcher)) {
            checker.start();
            await(() -> dispatcher.snapshots().get(1).alive() && dispatcher.snapshots().get(1).telemetryFresh(), 5000,
                    "healthy backend should become ready despite another endpoint stalling");
            check(!dispatcher.snapshots().getFirst().alive(), "stalled backend is DOWN");
            check(dispatcher.snapshots().get(1).cpuLoad() > 0.6, "real endpoint telemetry ingested");
            valid.set(false);
            await(() -> !dispatcher.snapshots().get(1).telemetryFresh(), 3000, "invalid metrics must age out");
            check(dispatcher.snapshots().get(1).alive(), "invalid telemetry cannot mark service DOWN");
            try (var lease = dispatcher.acquire()) { check(lease != null && lease.backend().equals(good), "fallback routes healthy server"); }
            reachable.set(false);
            await(() -> !dispatcher.snapshots().get(1).alive(), 3000, "non-2xx health removes backend");
            check(dispatcher.acquire() == null, "all DOWN rejects dispatch");
            reachable.set(true); valid.set(true);
            await(() -> dispatcher.snapshots().get(1).alive() && dispatcher.snapshots().get(1).telemetryFresh(), 3000, "health and telemetry recover");
        } finally { healthy.stop(0); stalled.stop(0); executor.shutdownNow(); }
        System.out.println("HealthCheckerTest passed (independent polling, stale/invalid metrics, failure/recovery)");
    }
    private static void respond(HttpExchange e, int status, String text) throws java.io.IOException {
        try (e) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(status, bytes.length);
            e.getResponseBody().write(bytes);
        }
    }
    private static void await(BooleanSupplier condition, long timeoutMs, String message) throws Exception {
        long end = System.nanoTime() + timeoutMs * 1_000_000;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError(message);
            Thread.sleep(20);
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
