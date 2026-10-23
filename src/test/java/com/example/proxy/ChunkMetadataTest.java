package com.example.proxy;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.handler.UpstreamTransport;
import com.example.proxy.server.ReverseProxyServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Kiểm tra giới hạn metadata bằng socket thật, kể cả khi backend chưa đóng kết nối. */
public final class ChunkMetadataTest {
    private static final String CHUNKED = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n";
    private static int checks;

    public static void main(String[] args) throws Exception {
        boundaries();
        configuration();
        integration();
        System.out.println("ChunkMetadataTest: " + checks + " kiểm tra PASS");
    }

    private static void boundaries() throws Exception {
        try (RawBackend backend = new RawBackend(); UpstreamTransport transport = new UpstreamTransport(config(backend.port()))) {
            // 6 byte tiền tố + padding + CRLF + CRLF sau data + last-chunk + CRLF cuối = padding + 15.
            String exact = "1;pad=" + "x".repeat(1009) + "\r\na\r\n0\r\n\r\n";
            check(text(exchange(backend, transport, CHUNKED + exact).body()).equals("a"), "chấp nhận đúng 1024 byte metadata");
            rejectMetadata(backend, transport, CHUNKED + exact.replace("pad=x", "pad=xx"));
            check(text(exchange(backend, transport, CHUNKED + "1\r\na\r\n".repeat(203) + "0\r\n\r\n").body()).equals("a".repeat(203)),
                    "nhiều chunk nhỏ vẫn hợp lệ khi chưa vượt tổng giới hạn");
            rejectMetadata(backend, transport, CHUNKED + "1\r\na\r\n".repeat(204) + "0\r\n\r\n");
            String trailer = "1\r\na\r\n0\r\nX: " + "x".repeat(1009) + "\r\n\r\n";
            check(text(exchange(backend, transport, CHUNKED + trailer).body()).equals("a"), "trailer và CRLF cuối được tính đúng tại biên");
            rejectMetadata(backend, transport, CHUNKED + trailer.replace("X: x", "X: xx"));
            String terminal = "0;pad=" + "x".repeat(1014) + "\r\n\r\n";
            check(exchange(backend, transport, CHUNKED + terminal).body().length == 0, "extension trên last-chunk cũng có giới hạn");
            rejectMetadata(backend, transport, CHUNKED + terminal.replace("pad=x", "pad=xx"));
            for (String response : List.of("HTTP/1.1 200 OK\r\nContent-Length: 2048\r\n\r\n", "HTTP/1.0 200 OK\r\n\r\n")) {
                backend.holdOpen = false;
                check(exchange(backend, transport, response + "b".repeat(2048)).body().length == 2048,
                        "giới hạn metadata không tính nhầm payload fixed-length hoặc EOF");
            }
            backend.holdOpen = true;
            check(text(exchange(backend, transport, CHUNKED + "2\r\nOK\r\n0\r\n\r\n").body()).equals("OK"),
                    "ngân sách được tạo lại cho mỗi exchange sau các lần bị chặn");
            check(transport.activeSocketCount() == 0 && transport.deadlineTaskCount() == 0, "không còn socket hoặc timer sau exchange");
        }
        try (RawBackend backend = new RawBackend(); UpstreamTransport transport = new UpstreamTransport(500, 1500, 4000, 20000, 4096)) {
            String exactDefault = "1\r\na\r\n".repeat(13106) + "00\r\n\r\n";
            check(exchange(backend, transport, CHUNKED + exactDefault).body().length == 13106,
                    "constructor cũ áp dụng mặc định 65536 byte metadata");
            rejectMetadata(backend, transport, CHUNKED + exactDefault.replace("00\r\n", "000\r\n"));
        }
        Properties p = settings();
        p.setProperty("proxy.max.header.bytes", "1024");
        p.setProperty("proxy.max.chunk.metadata.bytes", "8192");
        try (RawBackend backend = new RawBackend();
             UpstreamTransport transport = new UpstreamTransport(new ProxyConfig(p, List.of(backend.backend())))) {
            try {
                exchange(backend, transport, CHUNKED + "0\r\nX: " + "x".repeat(1000) + "\r\n\r\n");
                throw new AssertionError("trailer vượt header budget đã được chấp nhận");
            } catch (UpstreamTransport.HeaderLimitException expected) { checks++; }
            check(transport.activeSocketCount() == 0 && transport.deadlineTaskCount() == 0, "header budget của trailer vẫn độc lập và cleanup đúng");
        }
    }

    private static void configuration() {
        for (String value : List.of("0", "-1", "1023", "16777217", "NaN")) {
            Properties p = settings(); p.setProperty("proxy.max.chunk.metadata.bytes", value);
            try {
                new ProxyConfig(p, List.of(new BackendServer("127.0.0.1", 9001, 4)));
                throw new AssertionError("cấu hình không hợp lệ được chấp nhận: " + value);
            } catch (IllegalArgumentException expected) { checks++; }
        }
        for (String value : List.of("1024", "16777216")) {
            Properties p = settings(); p.setProperty("proxy.max.chunk.metadata.bytes", value);
            new ProxyConfig(p, List.of(new BackendServer("127.0.0.1", 9001, 4))); checks++;
        }
        Properties p = settings(); p.remove("proxy.max.chunk.metadata.bytes");
        check(new ProxyConfig(p, List.of(new BackendServer("127.0.0.1", 9001, 4))).getMaxChunkMetadataBytes() == 65536,
                "cấu hình cũ không cần thêm key mới");
    }

    private static void integration() throws Exception {
        try (RawBackend backend = new RawBackend(); ReverseProxyServer proxy = new ReverseProxyServer(config(backend.port()));
             HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            proxy.start(); await(() -> proxy.getDispatcher().hasAvailableBackend(), "health ban đầu");
            String base = "http://127.0.0.1:" + proxy.getPort();
            backend.response = CHUNKED + "1\r\na\r\n".repeat(204) + "0\r\n\r\n";
            var limited = get(client, base + "/data");
            check(limited.statusCode() == 502 && limited.body().contains("BAD_GATEWAY"), "metadata vượt giới hạn trả JSON 502");
            check(limited.headers().firstValue("X-Request-Id").isPresent(), "502 có request ID");
            check(proxy.getDispatcher().snapshotJson().contains("\"circuitFailures\":0"), "policy limit không tạo backend failure");
            backend.response = "HTTP/1.1 500 Error\r\nContent-Length: 0\r\n\r\n";
            check(get(client, base + "/data").statusCode() == 500, "lỗi backend đầu tiên");
            backend.response = CHUNKED + "1\r\na\r\n".repeat(204) + "0\r\n\r\n";
            check(get(client, base + "/data").statusCode() == 502, "policy limit giữa hai lỗi backend");
            check(proxy.getDispatcher().snapshotJson().contains("\"circuitFailures\":1"), "policy limit không reset lỗi liên tiếp");
            backend.response = "HTTP/1.1 500 Error\r\nContent-Length: 0\r\n\r\n";
            check(get(client, base + "/data").statusCode() == 500, "lỗi backend thứ hai");
            check(proxy.getDispatcher().snapshotJson().contains("\"circuitState\":\"OPEN\""), "circuit mở đúng threshold");
            await(() -> proxy.getDispatcher().hasAvailableBackend(), "hết cooldown");
            backend.response = CHUNKED + "1\r\na\r\n".repeat(204) + "0\r\n\r\n";
            check(get(client, base + "/data").statusCode() == 502, "probe bị policy limit");
            String state = proxy.getDispatcher().snapshotJson();
            check(state.contains("\"circuitState\":\"HALF_OPEN\"") && state.contains("\"circuitProbeInFlight\":false"),
                    "probe bị chặn trả slot, không giả định đã hồi phục");
            backend.response = CHUNKED + "2\r\nOK\r\n0\r\n\r\n";
            check(get(client, base + "/data").body().equals("OK"), "probe sau đó thành công");
            check(proxy.getDispatcher().snapshotJson().contains("\"circuitState\":\"CLOSED\""), "probe thành công đóng circuit");
            String metrics = get(client, base + "/__proxy/metrics").body();
            check(metrics.contains("\"upstream_limit_rejections\":3") && metrics.contains("\"upstream_chunk_metadata_rejections\":3"),
                    "JSON phân biệt limit rejection");
            check(metrics.contains("\"upstream_errors\":5") && metrics.contains("\"upstream_response_errors\":5"),
                    "giữ đúng các counter lỗi sẵn có");
            String prom = get(client, base + "/__proxy/prometheus").body();
            check(prom.contains("# TYPE proxy_upstream_chunk_metadata_rejections_total counter\n")
                    && prom.contains("proxy_upstream_chunk_metadata_rejections_total 3\n")
                    && prom.contains("proxy_upstream_limit_rejections_total 3\n"), "Prometheus có counter đúng, không gắn nhãn client");
            await(() -> proxy.diagnostics().activeHandlers() == 0, "handler cleanup");
            var d = proxy.diagnostics();
            check(d.admissionAvailable() == 128 && d.deadlineTasks() == 0 && d.upstreamSockets() == 0 && d.upstreamDeadlineTasks() == 0
                    && proxy.getDispatcher().snapshots().getFirst().inFlight() == 0, "trả đầy đủ permit, lease, socket và timer");
        }
    }

    private static ProxyConfig config(int port) { return new ProxyConfig(settings(), List.of(new BackendServer("127.0.0.1", port, 4))); }
    private static Properties settings() {
        Properties p = new Properties();
        p.setProperty("proxy.port", "0"); p.setProperty("proxy.shutdown.grace.seconds", "0");
        p.setProperty("loadbalancer.strategy", "ROUND_ROBIN"); p.setProperty("adaptive.warmup.ms", "0");
        p.setProperty("healthcheck.interval.ms", "60000"); p.setProperty("healthcheck.recovery.successes", "1");
        p.setProperty("proxy.max.body.bytes", "4096"); p.setProperty("proxy.max.header.bytes", "4096");
        p.setProperty("proxy.max.chunk.metadata.bytes", "1024");
        p.setProperty("proxy.circuit-breaker.failure-threshold", "2"); p.setProperty("proxy.circuit-breaker.open-duration.ms", "1000");
        return p;
    }
    private static UpstreamTransport.Response exchange(RawBackend backend, UpstreamTransport transport, String response) throws Exception {
        backend.response = response;
        return transport.exchange("127.0.0.1", backend.port(), "GET", URI.create("/data"), Map.of(), new byte[0]);
    }
    private static void rejectMetadata(RawBackend backend, UpstreamTransport transport, String response) throws Exception {
        try {
            exchange(backend, transport, response);
            throw new AssertionError("metadata vượt giới hạn được chấp nhận");
        } catch (UpstreamTransport.ChunkMetadataLimitException expected) { checks++; }
        check(transport.activeSocketCount() == 0 && transport.deadlineTaskCount() == 0, "cleanup ngay sau metadata rejection");
    }
    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void await(BooleanSupplier condition, String description) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean()) { check(System.nanoTime() < end, description + " hết thời gian"); Thread.sleep(10); }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.ISO_8859_1); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.ISO_8859_1); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }

    private static final class RawBackend implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final Future<?> acceptor;
        volatile String response;
        volatile boolean holdOpen = true;
        RawBackend() throws IOException {
            acceptor = workers.submit(() -> {
                try {
                    while (!listener.isClosed()) {
                        Socket socket = listener.accept(); sockets.add(socket);
                        workers.submit(() -> serve(socket));
                    }
                } catch (IOException stopped) { if (!listener.isClosed()) throw new UncheckedIOException(stopped); }
            });
        }
        int port() { return listener.getLocalPort(); }
        BackendServer backend() { return new BackendServer("127.0.0.1", port(), 4); }
        private void serve(Socket socket) {
            try (socket) {
                socket.setSoTimeout(3000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                String first = reader.readLine(), line;
                if (first == null) return;
                while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                boolean probe = first.startsWith("GET /health ") || first.startsWith("GET /metrics ");
                String selected = probe ? "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n" : response;
                socket.getOutputStream().write(bytes(selected)); socket.getOutputStream().flush();
                if (holdOpen && !probe) socket.getInputStream().read();
            } catch (IOException expected) { }
            finally { sockets.remove(socket); }
        }
        public void close() throws Exception {
            listener.close();
            acceptor.get(1, TimeUnit.SECONDS);
            for (Socket socket : sockets) socket.close();
            workers.shutdownNow();
            check(workers.awaitTermination(4, TimeUnit.SECONDS), "backend thử nghiệm đã dừng");
        }
    }
}
