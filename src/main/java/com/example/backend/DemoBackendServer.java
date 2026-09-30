package com.example.backend;

import com.sun.management.OperatingSystemMXBean;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Controlled fixture; each backend must run in its own JVM for credible process CPU/heap metrics. */
public final class DemoBackendServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore workers, admission;
    private final int capacity;
    private final double cpuBudget;
    private final AtomicInteger active = new AtomicInteger(), queued = new AtomicInteger();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong received = new AtomicLong();
    private volatile long cpuSamples;
    private final List<CpuWorker> background = new ArrayList<>();
    private final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private volatile boolean healthy = true;
    private volatile int delayMs;
    private volatile double cpuLoad, processCpuCores, serviceTimeMs;
    private volatile long cpuSink;
    private long previousCpuNanos, previousSampleNanos;

    public DemoBackendServer(int port, int capacity, int delayMs, int cpuWorkers, double cpuBudget) throws IOException {
        if (port < 0 || port > 65535 || capacity < 1 || capacity > 1024 || delayMs < 0 || delayMs > 10000
                || cpuWorkers < 0 || cpuWorkers > 64 || !Double.isFinite(cpuBudget) || cpuBudget <= 0)
            throw new IllegalArgumentException("Invalid port/capacity/delay/cpuWorkers/cpuBudget");
        this.capacity = capacity; this.delayMs = delayMs; this.cpuBudget = cpuBudget;
        workers = new Semaphore(capacity, true);
        admission = new Semaphore(capacity * 8); // Executing plus waiting; reject excess immediately.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        previousCpuNanos = os.getProcessCpuTime(); previousSampleNanos = System.nanoTime();
        setCpuWorkers(cpuWorkers);
    }
    public void start() {
        sampler.scheduleAtFixedRate(this::sampleCpu, 100, 250, TimeUnit.MILLISECONDS);
        server.start();
    }
    public int getPort() { return server.getAddress().getPort(); }
    private void sampleCpu() {
        long now = System.nanoTime(), current = os.getProcessCpuTime();
        if (current >= 0 && previousCpuNanos >= 0 && now > previousSampleNanos) {
            // CPU seconds / wall seconds = occupied cores. Explicit CPU budget is measured in cores.
            processCpuCores = Math.max(0, (double) (current - previousCpuNanos) / (now - previousSampleNanos));
            cpuLoad = clamp(processCpuCores / cpuBudget);
            cpuSamples++;
        }
        previousSampleNanos = now; previousCpuNanos = current;
    }
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/health" -> respond(exchange, healthy ? 200 : 503, healthy ? "UP" : "DOWN", "text/plain");
                case "/metrics" -> respond(exchange, 200, metrics(), "text/plain");
                case "/control", "/down", "/up" -> control(exchange, path);
                default -> workload(exchange, path);
            }
        }
    }
    private void control(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                || exchange.getRequestHeaders().containsKey("X-Forwarded-For")) {
            respond(exchange, 403, "Control is direct-loopback only", "text/plain"); return;
        }
        try {
            Map<String, String> values = query(exchange);
            int newDelay = integer(values, "delayMs", delayMs, 0, 10000);
            int newWorkers = integer(values, "cpuWorkers", backgroundSize(), 0, 64);
            String health = values.getOrDefault("healthy", Boolean.toString(healthy));
            if (!health.equals("true") && !health.equals("false")) throw new IllegalArgumentException("healthy must be true or false");
            delayMs = newDelay; setCpuWorkers(newWorkers);
            healthy = path.equals("/up") || (!path.equals("/down") && Boolean.parseBoolean(health));
            respond(exchange, 200, metrics(), "text/plain");
        } catch (IllegalArgumentException e) { respond(exchange, 400, e.getMessage(), "text/plain"); }
    }
    private void workload(HttpExchange exchange, String path) throws IOException {
        received.incrementAndGet(); // Excludes health, telemetry and control; includes rejected work.
        final int cost;
        final int fileKiB;
        try {
            Map<String, String> values = query(exchange);
            cost = path.equals("/orders") ? 5 : integer(values, "cost", 1, 1, 20);
            fileKiB = path.equals("/download") ? integer(values, "sizeKiB", 256, 1, 768) : 0;
            if (path.equals("/download") && fileKiB != 64 && fileKiB != 256 && fileKiB != 768)
                throw new IllegalArgumentException("sizeKiB must be 64, 256 or 768");
        }
        catch (IllegalArgumentException e) { respond(exchange, 400, e.getMessage(), "text/plain"); return; }
        if ((path.equals("/orders") && !exchange.getRequestMethod().equals("POST"))
                || ((path.equals("/products") || path.equals("/download"))
                    && !Set.of("GET", "HEAD").contains(exchange.getRequestMethod()))) {
            exchange.getResponseHeaders().set("Allow", path.equals("/orders") ? "POST" : "GET, HEAD");
            respond(exchange, 405, "Method not allowed", "text/plain"); return;
        }
        if (!healthy) { respond(exchange, 503, "Backend is down", "text/plain"); return; }
        if (!admission.tryAcquire()) { respond(exchange, 503, "Backend admission limit", "text/plain"); return; }
        boolean acquired = false;
        long started = 0;
        queued.incrementAndGet();
        try {
            acquired = workers.tryAcquire(3, TimeUnit.SECONDS);
            queued.decrementAndGet();
            if (!acquired) { respond(exchange, 503, "Backend queue timeout", "text/plain"); return; }
            active.incrementAndGet(); started = System.nanoTime();
            if (!healthy) { respond(exchange, 503, "Backend is down", "text/plain"); return; }
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
            else Thread.sleep(path.equals("/slow") ? 2000L : (long) delayMs * cost);
            exchange.getResponseHeaders().set("X-Backend", "backend-" + getPort());
            exchange.getResponseHeaders().set("X-Request-Method", exchange.getRequestMethod());
            exchange.getResponseHeaders().set("X-Request-Uri", exchange.getRequestURI().toASCIIString());
            if (path.equals("/demo")) {
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                Runtime runtime = Runtime.getRuntime();
                String page = DemoPage.render(getPort(), capacity, active.get(), queued.get(), delayMs,
                        completed.incrementAndGet(), runtime.totalMemory() - runtime.freeMemory(),
                        exchange.getRequestHeaders().containsKey("X-Forwarded-For"));
                respond(exchange, 200, page, "text/html");
            } else if (path.equals("/products") || path.equals("/orders")) {
                long requestNo = completed.incrementAndGet();
                String result = "{\"server\":\"backend-" + getPort() + "\",\"requestNo\":" + requestNo;
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
                respond(exchange, 200, "{\"server\":\"backend-" + getPort() + "\",\"cost\":" + cost
                        + ",\"capacity\":" + capacity + ",\"requestNo\":" + completed.incrementAndGet() + "}", "application/json");
            }
        } catch (InterruptedException e) {
            if (!acquired) queued.decrementAndGet();
            Thread.currentThread().interrupt();
        } finally {
            if (acquired) {
                double elapsed = (System.nanoTime() - started) / 1_000_000.0;
                synchronized (this) { serviceTimeMs = serviceTimeMs == 0 ? elapsed : 0.2 * elapsed + 0.8 * serviceTimeMs; }
                active.decrementAndGet(); workers.release();
            }
            admission.release();
        }
    }
    private String metrics() {
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory(), hostTotal = os.getTotalMemorySize(), hostFree = os.getFreeMemorySize();
        return String.format(Locale.ROOT,
                "cpuLoad=%.6f%nprocessCpuCores=%.6f%ncpuBudget=%.3f%navailableProcessors=%d%n"
                + "memoryLoad=%.6f%nheapUsedBytes=%d%nheapMaxBytes=%d%n"
                + "hostCpuLoad=%.6f%nhostMemoryLoad=%.6f%nhostMemoryTotalBytes=%d%n"
                + "activeRequests=%d%nqueuedRequests=%d%ncapacity=%d%nserviceTimeMs=%.3f%n"
                + "healthy=%s%ndelayMs=%d%ncpuWorkers=%d%ncompletedRequests=%d%nreceivedRequests=%d%ncpuSamples=%d%n",
                cpuLoad, processCpuCores, cpuBudget, runtime.availableProcessors(),
                clamp((double) heapUsed / runtime.maxMemory()), heapUsed, runtime.maxMemory(),
                clamp(os.getCpuLoad()), hostTotal <= 0 ? 0 : clamp(1.0 - (double) hostFree / hostTotal), hostTotal,
                active.get(), queued.get(), capacity, serviceTimeMs, healthy, delayMs, backgroundSize(), completed.get(), received.get(), cpuSamples);
    }
    private synchronized int backgroundSize() { return background.size(); }
    private synchronized void setCpuWorkers(int count) {
        while (background.size() > count) background.remove(background.size() - 1).running = false;
        while (background.size() < count) {
            CpuWorker worker = new CpuWorker(); background.add(worker); worker.thread.start();
        }
    }
    private final class CpuWorker {
        volatile boolean running = true;
        final Thread thread = Thread.ofPlatform().daemon(true).name("demo-cpu-worker").unstarted(() -> {
            long value = 12345;
            while (running && !Thread.currentThread().isInterrupted()) { value = burn(100_000, value); cpuSink = value; }
        });
    }
    private static long burn(long iterations, long seed) {
        long value = seed;
        for (long i = 0; i < iterations; i++) { value ^= value << 13; value ^= value >>> 7; value ^= value << 17; }
        return value;
    }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static int integer(Map<String, String> values, String key, int fallback, int min, int max) {
        int result = Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback)));
        if (result < min || result > max) throw new IllegalArgumentException(key + " outside " + min + ".." + max);
        return result;
    }
    private static Map<String, String> query(HttpExchange exchange) {
        return parameters(exchange.getRequestURI().getRawQuery());
    }
    private static Map<String, String> parameters(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw != null) for (String item : raw.split("&")) {
            String[] pair = item.split("=", 2);
            values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length < 2 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return values;
    }
    private static void respond(HttpExchange exchange, int status, String body, String type) throws IOException {
        respondBytes(exchange, status, body.getBytes(StandardCharsets.UTF_8), type + "; charset=utf-8");
    }
    private static void respondBytes(HttpExchange exchange, int status, byte[] bytes, String type) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        if (exchange.getRequestMethod().equals("HEAD") || status == 204 || status == 304) {
            if (status == 200) exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        }
    }
    @Override public void close() {
        setCpuWorkers(0); server.stop(0); sampler.shutdownNow(); executor.shutdownNow();
    }
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9001;
        int capacity = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        int delay = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        int cpuWorkers = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        double cpuBudget = args.length > 4 ? Double.parseDouble(args[4]) : capacity;
        DemoBackendServer backend = new DemoBackendServer(port, capacity, delay, cpuWorkers, cpuBudget);
        Runtime.getRuntime().addShutdownHook(new Thread(backend::close)); backend.start();
        System.out.printf(Locale.ROOT, "Backend http://127.0.0.1:%d capacity=%d delayMs=%d cpuBudget=%.2f cores%n", backend.getPort(), capacity, delay, cpuBudget);
    }
}
