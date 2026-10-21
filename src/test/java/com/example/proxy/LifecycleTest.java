package com.example.proxy;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.handler.UpstreamTransport;
import com.example.proxy.server.ReverseProxyServer;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real sockets, bounded waits and resource invariants for ingress, cancellation and draining. */
public final class LifecycleTest {
    public static void main(String[] args) throws Exception {
        deadlineAcrossPhases();
        partialHeaders();
        burstAndErrors();
        connectionRefused();
        draining();
        invalidTimeouts();
        await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && Set.of("client-deadlines","upstream-deadlines","ingress-deadlines").contains(t.getName())), "deadline scheduler threads terminate");
        System.out.println("LifecycleTest PASS: whole deadline, bounded ingress, burst recovery, JSON errors, remote management, draining");
    }
    private static void deadlineAcrossPhases() throws Exception {
        try (Fixture f = new Fixture(2000, 4, 0); Socket client = f.socket()) {
            f.ready();
            long start = System.nanoTime();
            client.getOutputStream().write(bytes("POST /hold HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2\r\n\r\na"));
            await(() -> f.proxy.diagnostics().activeHandlers() == 1, "upload entered");
            Thread.sleep(1200); // Test client consumes most of the one shared budget.
            client.getOutputStream().write(bytes("b"));
            check(f.entered.await(1, TimeUnit.SECONDS), "upstream receives completed upload");
            String response;
            try { response = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (SocketException closed) { response = ""; }
            check(!response.contains(" 200 "), "deadline cannot restart after upload");
            check(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(2800), "one deadline across phases");
            f.clean();
        }
    }
    private static void partialHeaders() throws Exception {
        try (Fixture f = new Fixture(700, 1, 0); Socket slow = f.socket()) {
            slow.getOutputStream().write(bytes("GET / HTTP/1.1\r\nHost: local"));
            Thread.sleep(100);
            try (Socket rejected = f.socket()) {
                rejected.getOutputStream().write(bytes("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"));
                try { check(rejected.getInputStream().read() == -1, "task saturation closes connection"); }
                catch (SocketException reset) { }
            }
            try { check(slow.getInputStream().read() == -1, "partial header expires"); }
            catch (SocketException reset) { }
            f.ready();
            check(f.get("/ok").statusCode() == 200, "executor slot recovers after incomplete headers");
            f.clean();
        }
    }
    private static void burstAndErrors() throws Exception {
        try (Fixture f = new Fixture(2500, 32, 0)) {
            f.ready();
            var held = f.client.sendAsync(f.request("/hold"), HttpResponse.BodyHandlers.ofString());
            check(f.entered.await(1, TimeUnit.SECONDS), "held request entered");
            List<CompletableFuture<HttpResponse<String>>> burst = new ArrayList<>();
            for (int i = 0; i < 12; i++) burst.add(f.client.sendAsync(f.request("/ok"), HttpResponse.BodyHandlers.ofString()));
            for (var request : burst) {
                var response = request.get(2, TimeUnit.SECONDS);
                check(response.statusCode() == 503 && response.body().contains("\"error\":\"UNAVAILABLE\""), "burst rejected with JSON");
                check(response.body().contains(response.headers().firstValue("X-Request-Id").orElseThrow()), "error ID correlation");
                check(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), "JSON type");
                check(!response.body().contains("Exception") && !response.body().contains("127.0.0.1"), "safe error body");
            }
            f.release.countDown();
            check(held.get(2, TimeUnit.SECONDS).statusCode() == 200, "held request finishes");
            f.clean();
            check(f.get("/ok").statusCode() == 200, "burst recovery"); f.clean();
            check(f.get("/error").statusCode() == 503, "backend application error preserved"); f.clean();
            var metrics = f.get("/__proxy/metrics").body();
            check(metrics.contains("\"upstream_response_errors\":1") && metrics.contains("\"requests_rejected\":12"), "error counters separated");
            f.clean();
            var head = f.client.send(HttpRequest.newBuilder(f.uri("/control")).method("HEAD", HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
            check(head.statusCode() == 403 && head.body().isEmpty(), "HEAD JSON error has no body"); f.clean();
            check(f.client.send(HttpRequest.newBuilder(f.uri("/__proxy/metrics")).POST(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 405, "management method guard"); f.clean();
            Optional<InetAddress> remote = NetworkInterface.networkInterfaces().filter(n -> {
                try { return n.isUp(); } catch (SocketException e) { throw new UncheckedIOException(e); }
            }).flatMap(NetworkInterface::inetAddresses).filter(a -> a instanceof Inet4Address && !a.isLoopbackAddress()).findFirst();
            if (remote.isPresent()) {
                var dashboard = f.client.send(HttpRequest.newBuilder(URI.create("http://" + remote.get().getHostAddress() + ":" + f.proxy.getPort() + "/__proxy/dashboard"))
                        .header("X-Forwarded-For", "127.0.0.1").timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
                check(dashboard.statusCode() == 403, "remote dashboard blocked despite spoofed forwarding header"); f.clean();
                var response = f.client.send(HttpRequest.newBuilder(URI.create("http://" + remote.get().getHostAddress() + ":" + f.proxy.getPort() + "/__proxy/metrics"))
                        .header("X-Forwarded-For", "127.0.0.1").timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
                check(response.statusCode() == 403, "remote management blocked despite spoofed forwarding header"); f.clean();
            } else System.out.println("SKIP remote interface: no non-loopback IPv4 available");
        }
    }
    private static void connectionRefused() throws Exception {
        int port;
        try (ServerSocket unused = new ServerSocket(0)) { port = unused.getLocalPort(); }
        Properties p = new Properties(); p.setProperty("proxy.port", "0"); p.setProperty("proxy.shutdown.grace.seconds", "0");
        p.setProperty("healthcheck.interval.ms", "60000"); p.setProperty("healthcheck.recovery.successes", "1");
        var backend = new BackendServer("127.0.0.1", port, 1);
        try (ReverseProxyServer proxy = new ReverseProxyServer(new ProxyConfig(p,List.of(backend))); HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            proxy.start(); Thread.sleep(150);
            var dispatcher = proxy.getDispatcher(); dispatcher.recordHealth(backend, dispatcher.beginProbe(backend), true);
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxy.getPort() + "/" )).timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofString());
            check(response.statusCode() == 502, "refused connection is 502");
            await(() -> proxy.diagnostics().activeHandlers() == 0, "refusal cleanup");
            check(proxy.diagnostics().admissionAvailable() == 128 && proxy.diagnostics().upstreamSockets() == 0 && dispatcher.snapshots().getFirst().inFlight() == 0, "refused connection returns resources");
        }
    }
    private static void draining() throws Exception {
        try (Fixture f = new Fixture(4000,4,1); ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            f.ready();
            var held = f.client.sendAsync(f.request("/hold"), HttpResponse.BodyHandlers.ofString());
            check(f.entered.await(1,TimeUnit.SECONDS), "request before drain");
            Future<?> stopping = tasks.submit(f.proxy::close);
            await(() -> !f.proxy.getDispatcher().hasAvailableBackend(), "dispatcher enters drain");
            check(f.proxy.getDispatcher().acquire() == null, "no selection during grace");
            int probes = f.probes.get(); Thread.sleep(120);
            check(f.probes.get() == probes, "no health polls during grace");
            f.release.countDown(); check(held.get(2,TimeUnit.SECONDS).statusCode() == 200, "grace preserves admitted request");
            stopping.get(4,TimeUnit.SECONDS); f.clean(); f.proxy.close();
        }
    }
    private static void invalidTimeouts() {
        for (int value : new int[]{0,-1}) {
            try { new UpstreamTransport(value,100,100,100,1024); throw new AssertionError("invalid transport timeout accepted"); }
            catch (IllegalArgumentException expected) { }
            for (String key : List.of("proxy.connect.timeout.ms","proxy.read.timeout.ms","proxy.request.timeout.ms","healthcheck.timeout.ms","proxy.max.handler.tasks")) {
                Properties p=new Properties(); p.setProperty(key,Integer.toString(value));
                try { new ProxyConfig(p,List.of(new BackendServer("localhost",9001,1))); throw new AssertionError(key); }
                catch (IllegalArgumentException expected) { check(expected.getMessage().contains(key), "config identifies invalid key"); }
            }
        }
    }
    private static final class Fixture implements AutoCloseable {
        final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
        final HttpServer backend;
        final HttpClient client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger probes=new AtomicInteger();
        final ReverseProxyServer proxy;
        Fixture(int timeout,int tasks,int grace) throws IOException {
            backend=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16); backend.setExecutor(workers);
            backend.createContext("/",e -> {
                try(e) {
                    String path=e.getRequestURI().getPath(); e.getRequestBody().readAllBytes();
                    if (path.equals("/health")) probes.incrementAndGet();
                    if (path.equals("/hold")) { entered.countDown(); release.await(4,TimeUnit.SECONDS); }
                    if (path.equals("/metrics")) { e.sendResponseHeaders(404,-1); return; }
                    e.sendResponseHeaders(path.equals("/error") ? 503 : 200,2); e.getResponseBody().write(bytes("OK"));
                } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }); backend.start();
            Properties p=new Properties(); p.setProperty("proxy.port","0"); p.setProperty("proxy.bind.host","0.0.0.0");
            p.setProperty("proxy.max.inflight","1");p.setProperty("proxy.max.handler.tasks",Integer.toString(tasks));
            p.setProperty("proxy.request.timeout.ms",Integer.toString(timeout));p.setProperty("proxy.read.timeout.ms",Integer.toString(timeout));
            p.setProperty("proxy.shutdown.grace.seconds",Integer.toString(grace));p.setProperty("healthcheck.interval.ms","20");
            p.setProperty("healthcheck.recovery.successes","1");p.setProperty("adaptive.warmup.ms","0");
            proxy=new ReverseProxyServer(new ProxyConfig(p,List.of(new BackendServer("127.0.0.1",backend.getAddress().getPort(),4))));proxy.start();
        }
        URI uri(String path) { return URI.create("http://127.0.0.1:"+proxy.getPort()+path); }
        HttpRequest request(String path) { return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).build(); }
        HttpResponse<String> get(String path) throws Exception { return client.send(request(path),HttpResponse.BodyHandlers.ofString()); }
        Socket socket() throws IOException { Socket socket=new Socket("127.0.0.1",proxy.getPort());socket.setSoTimeout(3500);return socket; }
        void ready() throws Exception { await(() -> proxy.getDispatcher().hasAvailableBackend(),"backend ready"); }
        void clean() throws Exception { await(() -> {var d=proxy.diagnostics();return d.admissionAvailable()==1 && d.activeHandlers()==0 && d.deadlineTasks()==0 && d.upstreamSockets()==0 && d.upstreamDeadlineTasks()==0 && proxy.getDispatcher().snapshots().getFirst().inFlight()==0;},"all resources returned"); }
        public void close() throws Exception { release.countDown();proxy.close();client.shutdownNow();backend.stop(0);workers.shutdownNow();check(workers.awaitTermination(2,TimeUnit.SECONDS),"fixture workers stop"); }
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.ISO_8859_1); }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static void await(BooleanSupplier condition,String message) throws Exception { long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()){check(System.nanoTime()<until,message);Thread.sleep(10);} }
}
