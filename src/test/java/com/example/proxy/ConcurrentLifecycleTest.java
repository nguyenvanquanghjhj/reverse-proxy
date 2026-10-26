package com.example.proxy;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.server.ReverseProxyServer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Real concurrent HTTP overload and timeouts must return every permit, lease, socket and timer. */
public final class ConcurrentLifecycleTest {
    public static void main(String[] args) throws Exception {
        int clients = 64, capacity = 8;
        var entered = new CountDownLatch(capacity);
        var release = new CountDownLatch(1);
        var backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 96);
        var backendTasks = Executors.newVirtualThreadPerTaskExecutor();
        backend.setExecutor(backendTasks);
        backend.createContext("/", e -> {
            try (e) {
                if (e.getRequestURI().getPath().equals("/hold")) {
                    entered.countDown(); release.await(6, TimeUnit.SECONDS);
                }
                if (e.getRequestURI().getPath().equals("/metrics")) { e.sendResponseHeaders(404, -1); return; }
                e.sendResponseHeaders(200, 2); e.getResponseBody().write(new byte[]{'O', 'K'});
            } catch (IOException timedOutClient) {
                // The proxy deliberately closed these upstream sockets on timeout.
            } catch (InterruptedException shutdown) { Thread.currentThread().interrupt(); }
        });
        backend.start();
        Properties p = new Properties();
        p.setProperty("proxy.port", "0"); p.setProperty("proxy.max.inflight", "8");
        p.setProperty("backend.max.inflight", "8"); p.setProperty("proxy.max.handler.tasks", "96");
        p.setProperty("proxy.read.timeout.ms", "1500"); p.setProperty("proxy.request.timeout.ms", "3500");
        p.setProperty("proxy.shutdown.grace.seconds", "0"); p.setProperty("loadbalancer.strategy", "ROUND_ROBIN");
        p.setProperty("healthcheck.interval.ms", "20"); p.setProperty("healthcheck.recovery.successes", "1");
        p.setProperty("adaptive.warmup.ms", "0"); p.setProperty("proxy.circuit-breaker.failure-threshold", "1000");
        try (var proxy = new ReverseProxyServer(new ProxyConfig(p,
                     List.of(new BackendServer("127.0.0.1", backend.getAddress().getPort(), capacity))));
             var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            proxy.start(); await(() -> proxy.getDispatcher().hasAvailableBackend(), "health ready");
            var ready = new CountDownLatch(clients);
            var start = new CountDownLatch(1);
            var observing = new AtomicBoolean(true);
            var observer = tasks.submit(() -> {
                while (observing.get()) {
                    var diagnostics = proxy.diagnostics();
                    int inFlight = proxy.getDispatcher().snapshots().getFirst().inFlight();
                    check(inFlight >= 0 && inFlight <= capacity, "lease bounds during overload");
                    check(diagnostics.admissionAvailable() >= 0 && diagnostics.admissionAvailable() <= capacity, "permit bounds");
                    Thread.sleep(1);
                }
                return null;
            });
            List<Future<Integer>> results = new ArrayList<>();
            try {
                for (int i = 0; i < clients; i++) results.add(tasks.submit(() -> {
                    ready.countDown(); check(start.await(4, TimeUnit.SECONDS), "start barrier");
                    return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxy.getPort() + "/hold"))
                            .timeout(Duration.ofSeconds(6)).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
                }));
                check(ready.await(4, TimeUnit.SECONDS), "clients ready"); start.countDown();
                check(entered.await(3, TimeUnit.SECONDS), "eight reservations reached backend");
                int unavailable = 0, timeouts = 0;
                for (var result : results) {
                    int status = result.get(7, TimeUnit.SECONDS);
                    if (status == 503) unavailable++;
                    else if (status == 504) timeouts++;
                    else throw new AssertionError("Unexpected overload outcome: " + status);
                }
                check(unavailable == clients - capacity && timeouts == capacity, "bounded admission and timeout outcomes");
                await(() -> {
                    var d = proxy.diagnostics();
                    return d.admissionAvailable() == capacity && d.activeHandlers() == 0 && d.deadlineTasks() == 0
                            && d.upstreamSockets() == 0 && d.upstreamDeadlineTasks() == 0
                            && proxy.getDispatcher().snapshots().getFirst().inFlight() == 0;
                }, "all resources returned after timeout burst");
                var snapshot = proxy.getDispatcher().snapshots().getFirst();
                check(snapshot.dispatched() == capacity && snapshot.completed() == capacity, "exactly one completion per lease");
                release.countDown(); await(() -> proxy.getDispatcher().hasAvailableBackend(), "health recovered");
                check(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxy.getPort() + "/ok"))
                        .timeout(Duration.ofSeconds(4)).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200, "capacity usable after burst");
            } finally {
                start.countDown(); release.countDown(); observing.set(false); observer.get(4, TimeUnit.SECONDS);
            }
        } finally { release.countDown(); backend.stop(0); backendTasks.shutdownNow(); }
        System.out.println("ConcurrentLifecycleTest PASS: 64 clients, 8 timeouts, 56 admission rejections, bounds, exactly-once completion and full cleanup");
    }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) { check(System.nanoTime() < end, message); Thread.sleep(10); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
