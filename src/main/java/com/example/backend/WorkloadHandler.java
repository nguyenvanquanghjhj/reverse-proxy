package com.example.backend;

import com.sun.net.httpserver.HttpExchange;
import static com.example.backend.BackendHttp.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Synthetic research endpoints only; application admission stays in the backend host. */
final class WorkloadHandler {
    record State(int port, int capacity, int active, int queued, int delayMs) { }
    private final Supplier<State> snapshot;
    private final AtomicLong completed;
    private volatile long cpuSink;
    WorkloadHandler(Supplier<State> snapshot, AtomicLong completed) {
        this.snapshot = snapshot; this.completed = completed;
    }
    void handle(HttpExchange exchange, String path, int cost, int fileKiB) throws IOException, InterruptedException {
        State state = snapshot.get();
        byte[] body = exchange.getRequestBody().readNBytes(1_048_577);
        if (body.length > 1_048_576) { respond(exchange, 413, "Request body too large", "text/plain"); return; }
        int quantity = 1;
        if (path.equals("/orders")) {
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded")) {
                respond(exchange, 415, "Use application/x-www-form-urlencoded", "text/plain"); return;
            }
            try {
                Map<String, String> order = parameters(new String(body, StandardCharsets.UTF_8));
                if (!"notebook".equals(order.get("productId"))) throw new IllegalArgumentException("Unknown productId");
                quantity = integer(order, "quantity", 1, 1, 10);
            } catch (IllegalArgumentException invalid) { respond(exchange, 400, invalid.getMessage(), "text/plain"); return; }
        }
        if (path.equals("/compute")) cpuSink = burn(2_000_000L * cost, cost);
        else Thread.sleep(path.equals("/slow") ? 2000L : (long) state.delayMs() * cost);
        exchange.getResponseHeaders().set("X-Backend", "backend-" + state.port());
        exchange.getResponseHeaders().set("X-Request-Method", exchange.getRequestMethod());
        exchange.getResponseHeaders().set("X-Request-Uri", exchange.getRequestURI().toASCIIString());
        if (path.equals("/workload") || path.equals("/demo")) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            Runtime runtime = Runtime.getRuntime();
            State view = snapshot.get();
            String page = WorkloadConsole.render(view.port(), view.capacity(), view.active(), view.queued(), view.delayMs(),
                    completed.incrementAndGet(), runtime.totalMemory() - runtime.freeMemory(),
                    exchange.getRequestHeaders().containsKey("X-Forwarded-For"));
            respond(exchange, 200, page, "text/html");
        } else if (path.equals("/products") || path.equals("/orders")) {
            long requestNo = completed.incrementAndGet();
            String result = "{\"server\":\"backend-" + state.port() + "\",\"requestNo\":" + requestNo;
            if (path.equals("/products")) {
                result += ",\"products\":[{\"id\":\"notebook\",\"name\":\"Sổ tay PBL\",\"price\":49000},"
                        + "{\"id\":\"pen\",\"name\":\"Bút ghi chú\",\"price\":15000},"
                        + "{\"id\":\"bag\",\"name\":\"Túi canvas\",\"price\":89000}]}";
            } else {
                result += ",\"demo\":true,\"orderId\":\"DEMO-" + UUID.randomUUID() + "\","
                        + "\"product\":\"Sổ tay PBL\",\"quantity\":" + quantity + ",\"total\":" + (49_000 * quantity) + "}";
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            respond(exchange, path.equals("/orders") ? 201 : 200, result, "application/json");
        } else if (path.equals("/download")) {
            // A real, deterministic binary payload, bounded below the proxy's default 1 MiB buffer limit.
            byte[] file = new byte[fileKiB * 1024];
            for (int i = 0; i < file.length; i++) file[i] = (byte) (i % 251);
            completed.incrementAndGet();
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"pbl4-demo-" + fileKiB + "KiB.bin\"");
            respondBytes(exchange, 200, file, "application/octet-stream");
        } else if (path.equals("/echo")) {
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            respondBytes(exchange, 200, body, type == null ? "application/octet-stream" : type);
        } else if (path.equals("/no-content") || path.equals("/not-modified")) {
            respondBytes(exchange, path.equals("/no-content") ? 204 : 304, new byte[0], "text/plain");
        } else {
            respond(exchange, 200, "{\"server\":\"backend-" + state.port() + "\",\"cost\":" + cost
                    + ",\"capacity\":" + state.capacity() + ",\"requestNo\":" + completed.incrementAndGet() + "}", "application/json");
        }
    }

    static long burn(long iterations, long seed) {
        long value = seed;
        for (long i = 0; i < iterations; i++) { value ^= value << 13; value ^= value >>> 7; value ^= value << 17; }
        return value;
    }
}
