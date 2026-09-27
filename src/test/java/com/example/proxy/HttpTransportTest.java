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
import java.util.concurrent.atomic.AtomicReference;

/** Real loopback sockets; no test framework or mocked transport. */
public final class HttpTransportTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        framing();
        deadline();
        transportShutdown();
        proxyIntegration();
        System.out.println("HttpTransportTest: " + checks + " checks passed");
    }
    private static void framing() throws Exception {
        try (UpstreamTransport transport = new UpstreamTransport(500, 500, 1500, 64, 1024)) {
            AtomicReference<String> observed = new AtomicReference<>();
            try (Fixture fixture = new Fixture("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close, X-Secret\r\nX-Secret: hidden\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\n\r\nOK", observed)) {
                var response = transport.exchange("127.0.0.1", fixture.port(), "PATCH", URI.create("/echo?q=a%20b"),
                        Map.of("Connection", List.of("X-Private"), "X-Private", List.of("secret"), "X-Test", List.of("present")), bytes("payload"));
                check(response.status() == 200 && text(response.body()).equals("OK"), "fixed length response");
                check(!response.headers().containsKey("x-secret") && !response.headers().containsKey("connection"), "response hop headers stripped");
                check(response.headers().get("set-cookie").size() == 2, "repeated end-to-end headers preserved");
                check(observed.get().startsWith("PATCH /echo?q=a%20b HTTP/1.1\r\n") && observed.get().endsWith("payload"), "PATCH body and escaped target forwarded");
                check(!observed.get().contains("X-Private") && observed.get().contains("X-Test: present"), "request hop headers stripped");
            }
            var chunked = request(transport, "GET", "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nTrailer: X-Sum\r\n\r\n3;ext=yes\r\nabc\r\n2\r\nde\r\n0\r\nX-Sum: 5\r\n\r\n");
            check(text(chunked.body()).equals("abcde") && !chunked.headers().containsKey("transfer-encoding"), "interim, chunks and trailers decoded");
            check(request(transport, "GET", "HTTP/1.0 200 OK\r\n\r\nEOF body").body().length == 8, "EOF framing");
            check(request(transport, "HEAD", "HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n").body().length == 0, "HEAD ignores representation body length");
            check(request(transport, "GET", "HTTP/1.1 204 No Content\r\n\r\n").body().length == 0, "204 no body");
            check(request(transport, "GET", "HTTP/1.1 304 Not Modified\r\nContent-Length: 100000\r\n\r\n").body().length == 0, "304 no body");
            check(request(transport, "GET", "HTTP/1.1 302 Found\r\nLocation: /elsewhere\r\nContent-Length: 0\r\n\r\n").status() == 302, "redirect is returned unchanged");
            reject(transport, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\nOK", "duplicate length");
            reject(transport, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n", "TE plus CL");
            reject(transport, "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n", "unsupported transfer coding");
            reject(transport, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nTransfer-Encoding: chunked\r\n\r\n", "duplicate TE");
            reject(transport, "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nabc", "truncated fixed body");
            reject(transport, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabcXX", "invalid chunk terminator");
            reject(transport, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nContent-Length: 10\r\n\r\n", "framing trailer");
            reject(transport, "HTTP/1.1 101 Switching Protocols\r\n\r\n", "upgrade");
            reject(transport, "HTTP/1.1 200 OK\nContent-Length: 0\n\n", "bare LF");
            reject(transport, "HTTP/1.1 200 OK\r\nContent-Length: 65\r\n\r\n", "fixed body limit");
            reject(transport, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n41\r\n", "chunk body limit");
            reject(transport, "HTTP/1.1 200 OK\r\n\r\n" + "x".repeat(65), "EOF body limit");
            reject(transport, "HTTP/1.1 200 OK\r\nX-Huge: " + "x".repeat(1100) + "\r\n\r\n", "header limit");
        }
    }
    private static void deadline() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             UpstreamTransport transport = new UpstreamTransport(300, 300, 160, 1024, 1024)) {
            Thread backend = Thread.startVirtualThread(() -> {
                try (Socket socket = listener.accept()) {
                    readRequest(socket);
                    socket.getOutputStream().write(bytes("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n"));
                    for (int i = 0; i < 10; i++) { socket.getOutputStream().write('a'); socket.getOutputStream().flush(); Thread.sleep(45); }
                } catch (IOException | InterruptedException ignored) { }
            });
            long start = System.nanoTime();
            try {
                transport.exchange("127.0.0.1", listener.getLocalPort(), "GET", URI.create("/"), Map.of(), new byte[0]);
                throw new AssertionError("Trickling response escaped total deadline");
            } catch (SocketTimeoutException expected) { checks++; }
            check((System.nanoTime() - start) / 1_000_000 < 1000, "whole exchange deadline interrupts trickling body");
            backend.join(1500);
        }
    }
    private static void proxyIntegration() throws Exception {
        ExecutorService backendWorkers = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 32);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<String> forwarded = new AtomicReference<>();
        backend.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                if (path.equals("/hold")) { entered.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                if (path.equals("/slow")) { try { Thread.sleep(700); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                if (path.equals("/no-content")) { exchange.sendResponseHeaders(204, -1); return; }
                if (path.equals("/not-modified")) { exchange.getResponseHeaders().set("Content-Length", "1234"); exchange.sendResponseHeaders(304, -1); return; }
                if (path.equals("/redirect")) { exchange.getResponseHeaders().set("Location", "/echo"); exchange.sendResponseHeaders(302, -1); return; }
                if (path.equals("/health")) { exchange.sendResponseHeaders(200, -1); return; }
                if (path.equals("/metrics")) { exchange.sendResponseHeaders(404, -1); return; }
                forwarded.set(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
                byte[] response = path.equals("/big") ? new byte[2048] : bytes(exchange.getRequestMethod() + ":" + text(requestBody));
                exchange.getResponseHeaders().set("Connection", "close, X-Private");
                exchange.getResponseHeaders().set("X-Private", "secret");
                if (exchange.getRequestMethod().equals("HEAD")) { exchange.getResponseHeaders().set("Content-Length", "321"); exchange.sendResponseHeaders(200, -1); }
                else { exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); }
            }
        });
        backend.setExecutor(backendWorkers);
        backend.start();
        Properties properties = new Properties();
        properties.setProperty("proxy.port", "0");
        properties.setProperty("proxy.max.body.bytes", "1024");
        properties.setProperty("proxy.max.inflight", "1");
        properties.setProperty("proxy.read.timeout.ms", "200");
        properties.setProperty("proxy.request.timeout.ms", "600");
        properties.setProperty("healthcheck.interval.ms", "20");
        properties.setProperty("healthcheck.recovery.successes", "1");
        properties.setProperty("adaptive.warmup.ms", "0");
        ProxyConfig config = new ProxyConfig(properties, List.of(new BackendServer("127.0.0.1", backend.getAddress().getPort(), 4)));
        try (ReverseProxyServer proxy = new ReverseProxyServer(config); HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build()) {
            proxy.start();
            long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (!proxy.getDispatcher().hasAvailableBackend() && System.nanoTime() < readyDeadline) Thread.sleep(10);
            check(proxy.getDispatcher().hasAvailableBackend(), "health enables backend");
            String base = "http://127.0.0.1:" + proxy.getPort();
            long beforeRejected = proxy.getDispatcher().snapshots().getFirst().dispatched();
            for (String requestLine : List.of("GET /echo#fragment HTTP/1.1", "G(ET /echo HTTP/1.1")) {
                try (Socket clientSocket = new Socket("127.0.0.1", proxy.getPort())) {
                    clientSocket.setSoTimeout(2000);
                    clientSocket.getOutputStream().write(bytes(requestLine + "\r\nHost: localhost\r\nConnection: close\r\n\r\n"));
                    String statusLine = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.ISO_8859_1)).readLine();
                    check(statusLine != null && statusLine.contains(" 400 "), "malformed client input is a 400: " + statusLine);
                }
            }
            var afterRejected = proxy.getDispatcher().snapshots().getFirst();
            check(afterRejected.dispatched() == beforeRejected && afterRejected.errors() == 0 && afterRejected.alive(),
                    "invalid client input never reserves or poisons a backend");
            for (String method : List.of("PATCH", "DELETE", "GET")) {
                var request = HttpRequest.newBuilder(URI.create(base + "/echo")).header("X-Forwarded-For", "spoofed")
                        .method(method, HttpRequest.BodyPublishers.ofString("hello")).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                check(response.statusCode() == 200 && response.body().equals(method + ":hello"), method + " body through proxy: " + response.statusCode() + " " + response.body());
                check(response.headers().firstValue("X-Private").isEmpty(), "hop header removed through ingress");
                check(!"spoofed".equals(forwarded.get()), "forwarding metadata replaced");
            }
            var head = client.send(HttpRequest.newBuilder(URI.create(base + "/echo")).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            check(head.statusCode() == 200 && head.body().length == 0 && head.headers().firstValue("Content-Length").orElse("").equals("321"), "HEAD metadata through ingress");
            check(get(client, base + "/no-content").statusCode() == 204, "204 through ingress");
            check(get(client, base + "/not-modified").statusCode() == 304, "304 through ingress");
            check(get(client, base + "/redirect").statusCode() == 302, "302 not followed");
            check(get(client, base + "/control?cpu=1").statusCode() == 403, "backend controls blocked");
            check(get(client, base + "/__proxy/metrics").body().contains("backends"), "monitoring snapshot");
            check(get(client, base + "/__proxy/metrics/extra").statusCode() == 404, "monitor endpoint exact path");
            var tooLarge = client.send(HttpRequest.newBuilder(URI.create(base + "/echo")).POST(HttpRequest.BodyPublishers.ofByteArray(new byte[1025])).build(), HttpResponse.BodyHandlers.ofString());
            check(tooLarge.statusCode() == 413, "upload limit");
            long dispatchedBeforeUpload = proxy.getDispatcher().snapshots().getFirst().dispatched();
            try (Socket stalledUpload = rejectedUploadAfterPermitReturns(proxy.getPort())) {
                long recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                int nextStatus;
                do {
                    nextStatus = get(client, base + "/echo").statusCode();
                    if (nextStatus == 200) break;
                    Thread.sleep(30);
                } while (System.nanoTime() < recoveryDeadline);
                check(nextStatus == 200, "rejected client holding its socket open cannot retain the admission permit");
                check(proxy.getDispatcher().snapshots().getFirst().dispatched() == dispatchedBeforeUpload + 1,
                        "oversized upload never dispatches; only the recovery GET reaches backend");
            }
            check(get(client, base + "/big").statusCode() == 502, "upstream response limit");
            check(proxy.getDispatcher().hasAvailableBackend(), "body limit does not mark backend DOWN");
            CompletableFuture<HttpResponse<String>> pending = client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/hold")).build(), HttpResponse.BodyHandlers.ofString());
            check(entered.await(2, TimeUnit.SECONDS), "concurrent request reached backend");
            check(get(client, base + "/echo").statusCode() == 503, "global inflight admission");
            release.countDown();
            check(pending.get(2, TimeUnit.SECONDS).statusCode() == 200, "admitted request completes");
            check(get(client, base + "/slow").statusCode() == 504, "timeout returns 504 before closing client");
            check(proxy.getDispatcher().snapshots().stream().allMatch(s -> s.inFlight() == 0), "all leases released");
        } finally { release.countDown(); backend.stop(0); backendWorkers.shutdownNow(); }
    }
    private static void transportShutdown() throws Exception {
        CountDownLatch connected = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             UpstreamTransport transport = new UpstreamTransport(500, 30000, 30000, 1024, 1024);
             ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> backend = workers.submit(() -> {
                try (Socket socket = listener.accept()) {
                    readRequest(socket);
                    connected.countDown();
                    release.await(3, TimeUnit.SECONDS);
                } catch (IOException | InterruptedException ignored) { }
            });
            Future<Boolean> pending = workers.submit(() -> {
                try {
                    transport.exchange("127.0.0.1", listener.getLocalPort(), "GET", URI.create("/"), Map.of(), new byte[0]);
                    return false;
                } catch (IOException closed) { return true; }
            });
            try {
                check(connected.await(2, TimeUnit.SECONDS), "upstream read entered before shutdown");
                transport.close();
                check(pending.get(2, TimeUnit.SECONDS), "shutdown closes outstanding upstream socket without waiting for timeout");
                try {
                    transport.exchange("127.0.0.1", listener.getLocalPort(), "GET", URI.create("/"), Map.of(), new byte[0]);
                    throw new AssertionError("closed transport accepted another request");
                } catch (IOException expected) { checks++; }
            } finally { release.countDown(); backend.get(2, TimeUnit.SECONDS); }
        }
    }
    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static Socket rejectedUploadAfterPermitReturns(int port) throws Exception {
        // Receiving the previous response does not imply that its handler has left finally/released
        // the single admission permit. Retry ONLY that explicit 503; never send the oversized body.
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            Socket socket = new Socket("127.0.0.1", port);
            boolean retained = false;
            try {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(bytes("POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1025\r\n\r\n"));
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                String status = reader.readLine();
                if (status != null && status.contains(" 503 ")) {
                    String remaining = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                    check(remaining.contains("Proxy capacity exhausted"), "retry only a previous handler holding admission");
                    check(System.nanoTime() < until, "previous handler must return its admission permit");
                } else {
                    check(status != null && status.contains(" 413 "), "oversized body rejected before upload: " + status);
                    retained = true;
                    return socket;
                }
            } finally { if (!retained) socket.close(); }
            Thread.sleep(10);
        }
    }
    private static UpstreamTransport.Response request(UpstreamTransport transport, String method, String response) throws Exception {
        try (Fixture fixture = new Fixture(response, new AtomicReference<>())) {
            return transport.exchange("127.0.0.1", fixture.port(), method, URI.create("/"), Map.of(), new byte[0]);
        }
    }
    private static void reject(UpstreamTransport transport, String response, String message) throws Exception {
        try { request(transport, "GET", response); throw new AssertionError("Accepted " + message); }
        catch (IOException expected) { checks++; }
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.ISO_8859_1); }
    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.ISO_8859_1); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static String readRequest(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!text(head.toByteArray()).endsWith("\r\n\r\n")) {
            int next = in.read(); if (next < 0) throw new EOFException(); head.write(next);
            if (head.size() > 65536) throw new IOException("test request too large");
        }
        String headers = text(head.toByteArray());
        int length = 0;
        for (String line : headers.split("\r\n")) if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
        return headers + text(in.readNBytes(length));
    }
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server;
        final Thread worker;
        Fixture(String response, AtomicReference<String> observed) throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            worker = Thread.startVirtualThread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    observed.set(readRequest(socket));
                    socket.getOutputStream().write(bytes(response));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) { }
            });
        }
        int port() { return server.getLocalPort(); }
        public void close() throws Exception { server.close(); worker.join(2000); }
    }
}
