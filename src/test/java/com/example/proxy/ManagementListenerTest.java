package com.example.proxy;

import com.example.backend.DemoBackendServer;
import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.server.ReverseProxyServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class ManagementListenerTest {
    public static void main(String[] args) throws Exception {
        try (var backend = new DemoBackendServer(0, 4, 0, 0, 4)) {
            backend.start();
            try (var proxy = new ReverseProxyServer(config(settings(), backend.getPort()));
                 var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
                proxy.start(); await(() -> proxy.getDispatcher().hasAvailableBackend(), "backend ready");
                int data = proxy.getPort(), management = proxy.getManagementPort();
                check(data != management && management > 0, "separate ports");
                try (Socket slow = partial(data)) {
                    await(() -> proxy.getDataIngressTasks() == 1, "data parser occupied");
                    var metrics = get(client, management, "/__proxy/metrics");
                    check(metrics.statusCode() == 200 && metrics.body().contains("\"data_ingress_tasks\":1"), "monitor survives data saturation");
                    Files.createDirectories(Path.of("target/test-reports"));
                    Files.writeString(Path.of("target/test-reports/management-metrics.json"), metrics.body());
                    await(() -> proxy.getManagementIngressTasks() == 0, "metrics done");
                    check(get(client, management, "/__proxy/health").statusCode() == 503, "readiness includes data ingress capacity");
                    await(() -> proxy.getManagementIngressTasks() == 0, "health done");
                    check(get(client, management, "/work").statusCode() == 404, "management never forwards application routes");
                    // Keep the incomplete request open until the ingress deadline; closing the
                    // client early can make the JDK parser treat EOF as the end of its headers.
                    slow.setSoTimeout(3000);
                    try { check(slow.getInputStream().read() == -1, "data partial header deadline"); }
                    catch (SocketException reset) { }
                }
                await(() -> proxy.getDataIngressTasks() == 0 && proxy.getManagementIngressTasks() == 0, "slots returned");
                try (Socket slow = partial(management)) {
                    await(() -> proxy.getManagementIngressTasks() == 1, "management parser occupied");
                    check(get(client, data, "/work?cost=1").statusCode() == 200, "management saturation does not block data");
                    try (Socket extra = new Socket("127.0.0.1", management)) {
                        extra.setSoTimeout(2500);
                        extra.getOutputStream().write("GET /__proxy/metrics HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        try { check(extra.getInputStream().read() == -1, "bounded management executor rejects excess"); }
                        catch (SocketException reset) { }
                    }
                    slow.setSoTimeout(2500);
                    try { check(slow.getInputStream().read() == -1, "management partial header deadline"); }
                    catch (SocketException reset) { }
                }
                await(() -> proxy.getDataIngressTasks() == 0 && proxy.getManagementIngressTasks() == 0, "parser expiry cleanup");
                check(get(client, data, "/work?cost=1").statusCode() == 429, "data quota enforced");
                await(() -> proxy.getDataIngressTasks() == 0, "rate rejection cleanup");
                check(get(client, management, "/__proxy/metrics").statusCode() == 200, "management exempt from quota");
                await(() -> proxy.getManagementIngressTasks() == 0, "metrics cleanup");
                check(get(client, data, "/__proxy/metrics").statusCode() == 200, "legacy route preserved");
                await(() -> proxy.getDataIngressTasks() == 0, "legacy metrics cleanup");
                check(get(client, management, "/__proxy/health").statusCode() == 200, "readiness recovers");
                await(() -> proxy.getManagementIngressTasks() == 0, "readiness cleanup");
                var prometheus = get(client, management, "/__proxy/prometheus");
                Files.writeString(Path.of("target/test-reports/management.prom"), prometheus.body());
                check(prometheus.body().contains("# TYPE proxy_management_ingress_tasks gauge"), "management metrics");
                await(() -> proxy.getManagementIngressTasks() == 0, "prometheus cleanup");
                long dispatched = proxy.getDispatcher().snapshots().getFirst().dispatched();
                var decision = get(client, management, "/__proxy/decision");
                check(decision.statusCode() == 200 && decision.body().equals(proxy.getDispatcher().decisionJson())
                        && decision.body().contains("MINIMUM_SCORE"), "enabled decision endpoint snapshot");
                check(proxy.getDispatcher().snapshots().getFirst().dispatched() == dispatched, "decision read never dispatches");
                await(() -> proxy.getManagementIngressTasks() == 0, "decision cleanup");
                var dashboard = get(client, management, "/__proxy/dashboard");
                check(dashboard.statusCode() == 200 && dashboard.headers().firstValue("Content-Type").orElse("").startsWith("text/html"), "dashboard is HTML");
                check(dashboard.body().contains("lang=\"vi\"") && dashboard.body().contains("/__proxy/decision")
                        && dashboard.body().contains("id=\"cycle-strategy\"")
                        && !dashboard.body().contains("@@DATA_PORT@@"), "Vietnamese live dashboard with resolved data port");
                check(proxy.getDispatcher().snapshots().getFirst().dispatched() == dispatched, "dashboard never dispatches");
                await(() -> proxy.getManagementIngressTasks() == 0, "dashboard cleanup");
                var dashboardHead = client.send(HttpRequest.newBuilder(uri(management, "/__proxy/dashboard")).method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
                check(dashboardHead.statusCode() == 200 && dashboardHead.body().isEmpty(), "dashboard HEAD has no body");
                await(() -> proxy.getManagementIngressTasks() == 0, "dashboard HEAD cleanup");
                var dashboardPost = client.send(HttpRequest.newBuilder(uri(management, "/__proxy/dashboard")).POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
                check(dashboardPost.statusCode() == 405, "dashboard method guard");
                await(() -> proxy.getManagementIngressTasks() == 0, "dashboard POST cleanup");
                var missingAction = client.send(HttpRequest.newBuilder(uri(management, "/__proxy/strategy"))
                        .POST(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
                check(missingAction.statusCode() == 403, "strategy change requires dashboard action header");
                await(() -> proxy.getManagementIngressTasks() == 0, "rejected strategy action cleanup");
                check(get(client, management, "/__proxy/metrics").body().contains("\"strategy\":\"ADAPTIVE\""),
                        "rejected cross-origin style action leaves strategy unchanged");
                await(() -> proxy.getManagementIngressTasks() == 0, "strategy check cleanup");
                check(cycleStrategy(client, management).body().contains("\"strategy\":\"ROUND_ROBIN\""), "one click switches to round robin");
                check(cycleStrategy(client, management).body().contains("\"strategy\":\"LEAST_CONNECTIONS\""), "second click switches to least connections");
                check(cycleStrategy(client, management).body().contains("\"strategy\":\"ADAPTIVE\""), "third click returns to adaptive");
                var head = client.send(HttpRequest.newBuilder(uri(management, "/__proxy/metrics")).method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
                check(head.statusCode() == 200 && head.body().isEmpty(), "HEAD supported");
                await(() -> proxy.getManagementIngressTasks() == 0, "head cleanup");
                var post = client.send(HttpRequest.newBuilder(uri(management, "/__proxy/metrics")).POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
                check(post.statusCode() == 405 && post.headers().firstValue("Allow").orElse("").equals("GET, HEAD"), "methods restricted");
                await(() -> proxy.getManagementIngressTasks() == 0, "post cleanup");
                try (Socket stalledData = partial(data); Socket stalledManagement = partial(management)) {
                    await(() -> proxy.getDataIngressTasks() == 1 && proxy.getManagementIngressTasks() == 1, "both parsers occupied");
                    long start = System.nanoTime(); proxy.close(); proxy.close();
                    check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(4), "bounded shutdown");
                    await(() -> proxy.getDataIngressTasks() == 0 && proxy.getManagementIngressTasks() == 0, "both executors drained");
                    check(proxy.diagnostics().activeHandlers() == 0 && proxy.diagnostics().upstreamSockets() == 0, "handler cleanup");
                }
                refused(data); refused(management);
            }
            startupFailure(backend.getPort());
            Properties disabled = settings(); disabled.setProperty("proxy.management.enabled", "false");
            try (var proxy = new ReverseProxyServer(config(disabled, backend.getPort()))) {
                proxy.start(); check(proxy.getManagementPort() == -1, "disabled has no extra listener");
            }
            try (var proxy = new ReverseProxyServer(config(settings(), backend.getPort()))) {
                proxy.close();
                try { proxy.start(); throw new AssertionError("start after close accepted"); } catch (IllegalStateException expected) { }
            }
        }
        for (var entry : Map.of("proxy.management.enabled", "yes", "proxy.management.port", "65536",
                "proxy.management.max.handler.tasks", "0", "proxy.connection-pool.enabled", "true").entrySet()) {
            Properties p = settings(); p.setProperty(entry.getKey(), entry.getValue());
            try { config(p, 9001); throw new AssertionError("invalid config accepted"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains(entry.getKey()), "config key in error"); }
        }
        System.out.println("ManagementListenerTest PASS: isolation, limits, deadlines, rate exemption, compatibility, bind rollback, shutdown");
    }
    private static void startupFailure(int backend) throws Exception {
        try (var occupied = new ServerSocket(0)) {
            int data;
            try (var free = new ServerSocket(0)) { data = free.getLocalPort(); }
            Properties p = settings(); p.setProperty("proxy.port", Integer.toString(data));
            p.setProperty("proxy.management.port", Integer.toString(occupied.getLocalPort()));
            try (var proxy = new ReverseProxyServer(config(p, backend))) {
                try { proxy.start(); throw new AssertionError("bind collision accepted"); } catch (IOException expected) { }
            }
            try (var rebound = new ServerSocket()) { rebound.bind(new InetSocketAddress("127.0.0.1", data)); }
        }
    }
    private static Properties settings() {
        Properties p = new Properties();
        p.setProperty("proxy.port", "0"); p.setProperty("proxy.management.enabled", "true"); p.setProperty("proxy.management.port", "0");
        p.setProperty("proxy.max.handler.tasks", "1"); p.setProperty("proxy.management.max.handler.tasks", "1");
        p.setProperty("proxy.request.timeout.ms", "2000"); p.setProperty("proxy.shutdown.grace.seconds", "0");
        p.setProperty("healthcheck.recovery.successes", "1"); p.setProperty("adaptive.warmup.ms", "0");
        p.setProperty("adaptive.decision-trace.enabled", "true");
        p.setProperty("proxy.rate-limit.enabled", "true"); p.setProperty("proxy.rate-limit.burst", "1");
        p.setProperty("proxy.rate-limit.requests-per-second", "0.01"); p.setProperty("proxy.rate-limit.idle.ms", "100000");
        return p;
    }
    private static HttpResponse<String> cycleStrategy(HttpClient client, int port) throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri(port, "/__proxy/strategy"))
                .header("X-Proxy-Action", "cycle-strategy")
                .POST(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == 200, "strategy action accepted");
        return response;
    }
    private static ProxyConfig config(Properties p, int backend) { return new ProxyConfig(p, List.of(new BackendServer("127.0.0.1", backend, 4))); }
    private static Socket partial(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: local".getBytes(StandardCharsets.US_ASCII)); return socket;
    }
    private static URI uri(int port, String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private static HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(port, path)).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void refused(int port) throws IOException {
        try (Socket socket = new Socket()) {
            try { socket.connect(new InetSocketAddress("127.0.0.1", port), 500); throw new AssertionError("listener still open"); }
            catch (ConnectException expected) { }
        }
    }
    private static void await(BooleanSupplier condition, String description) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean()) { check(System.nanoTime() < end, description + " timed out"); Thread.sleep(5); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
