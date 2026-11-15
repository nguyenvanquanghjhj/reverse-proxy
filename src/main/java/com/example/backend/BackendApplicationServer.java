package com.example.backend;

import com.sun.management.OperatingSystemMXBean;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.example.shop.ShopConfig;
import com.example.shop.LegacyShopApi;
import com.example.store.StoreApplication;
import com.example.shop.JsonCodec;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import static com.example.backend.BackendHttp.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** HTTP host for the store, legacy API compatibility, file service and explicit research workloads. */
public final class BackendApplicationServer implements AutoCloseable {
    private final HttpServer server;
    private final LegacyShopApi shop;
    private final StoreApplication store;
    private final StoreFaults storeFaults=new StoreFaults();
    private final com.example.proxy.server.BoundedRequestExecutor executor = new com.example.proxy.server.BoundedRequestExecutor(128,10000);
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore workers;
    private final RequestAdmission admission;
    private final int capacity;
    private final double cpuBudget;
    private final AtomicInteger active = new AtomicInteger(), queued = new AtomicInteger();
    private final AtomicLong completed = new AtomicLong();
    private final WorkloadHandler research;
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong queueWaitNanos = new AtomicLong(), queueWaitSamples = new AtomicLong();
    private volatile long cpuSamples;
    private final List<CpuWorker> background = new ArrayList<>();
    private final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private volatile boolean healthy = true;
    private volatile int delayMs;
    private volatile double cpuLoad = -1, processCpuCores = -1, serviceTimeMs;
    private volatile long cpuSink;
    private long previousCpuNanos, previousSampleNanos;

    public BackendApplicationServer(int port, int capacity, int delayMs, int cpuWorkers, double cpuBudget) throws IOException {
        if (port < 0 || port > 65535 || capacity < 1 || capacity > 1024 || delayMs < 0 || delayMs > 10000
                || cpuWorkers < 0 || cpuWorkers > 64 || !Double.isFinite(cpuBudget) || cpuBudget <= 0)
            throw new IllegalArgumentException("Invalid port/capacity/delay/cpuWorkers/cpuBudget");
        this.capacity = capacity; this.delayMs = delayMs; this.cpuBudget = cpuBudget;
        research = new WorkloadHandler(
                () -> new WorkloadHandler.State(getPort(), this.capacity, active.get(), queued.get(), this.delayMs), completed);
        shop = ShopConfig.enabled() ? new LegacyShopApi(ShopConfig.environment()) : null;
        store = StoreApplication.enabled() ? new StoreApplication(ShopConfig.environment()) : null;
        workers = new Semaphore(capacity, true);
        admission = new RequestAdmission(capacity * 8); // Executing plus waiting; reject excess immediately.
        server = HttpServer.create(new InetSocketAddress(
                System.getenv().getOrDefault("PBL4_BACKEND_BIND_HOST", "127.0.0.1"), port), 64);
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
        } else { cpuLoad = processCpuCores = -1; }
        previousSampleNanos = now; previousCpuNanos = current;
    }
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (LargeFileHandler.matches(path) && Set.of("GET","HEAD").contains(exchange.getRequestMethod()))
                com.example.proxy.util.RequestDeadline.forRoute(120000);
            switch (path) {
                case "/health" -> {exchange.getResponseHeaders().set("X-Backend-Draining",Boolean.toString(healthy&&admission.draining()));respond(exchange, healthy && !admission.draining() ? 200 : 503, healthy&&admission.draining() ? "DRAINING" : healthy ? "UP" : "DOWN", "text/plain");}
                case "/live" -> respond(exchange, healthy ? 200 : 503, healthy ? "UP" : "DOWN", "text/plain");
                case "/ready" -> readiness(exchange);
                case "/__test/store-fault" -> storeFaults.control(exchange);
                case "/metrics" -> { if (!probeAllowed(exchange))
                    respond(exchange, 403, "Metrics access denied", "text/plain");
                    else respond(exchange, 200, metrics(), "text/plain"); }
                case "/control", "/down", "/up" -> control(exchange, path);
                default -> workload(exchange, path);
            }
        }
    }
    private static boolean probeAllowed(HttpExchange exchange) {
        if (exchange.getRequestHeaders().containsKey("X-Forwarded-For")) return false;
        if (exchange.getRemoteAddress().getAddress().isLoopbackAddress()) return true;
        String configured = System.getenv("PBL4_PROBE_TOKEN");
        String provided = exchange.getRequestHeaders().getFirst("X-PBL4-Probe-Token");
        return configured != null && configured.length() >= 32 && provided != null
            && java.security.MessageDigest.isEqual(
                configured.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                provided.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private void readiness(HttpExchange exchange) throws IOException {
        if (!probeAllowed(exchange)) {
            respond(exchange,403,"Readiness access denied","text/plain");return;
        }
        if (!Set.of("GET","HEAD").contains(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow","GET, HEAD");respond(exchange,405,"Method not allowed","text/plain");return;
        }
        Map<String,Object> dependencies=store==null?Map.of("status","NOT_CONFIGURED"):store.dependencies();
        boolean draining=admission.draining(),ready=healthy&&!draining&&(store==null||"READY".equals(dependencies.get("status")));
        respond(exchange,ready?200:503,JsonCodec.write(Map.of("status",ready?"READY":draining?"DRAINING":"DEGRADED","live",healthy,"draining",draining,"admitted",admission.admitted(),"dependencies",dependencies)),"application/json; charset=utf-8");
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
            String draining=values.getOrDefault("draining",Boolean.toString(admission.draining()));
            if(!draining.equals("true")&&!draining.equals("false"))throw new IllegalArgumentException("draining must be true or false");
            delayMs = newDelay; setCpuWorkers(newWorkers);
            admission.drain(Boolean.parseBoolean(draining));
            healthy = path.equals("/up") || (!path.equals("/down") && Boolean.parseBoolean(health));
            respond(exchange, 200, metrics(), "text/plain");
        } catch (IllegalArgumentException e) { respond(exchange, 400, e.getMessage(), "text/plain"); }
    }
    private void workload(HttpExchange exchange, String path) throws IOException {
        received.incrementAndGet(); // Excludes health, telemetry and control; includes rejected work.
        if(admission.draining()){exchange.getResponseHeaders().set("Retry-After","1");respond(exchange,503,"Backend draining","text/plain");return;}
        if (path.equals("/status")) {
            try {
                int status = integer(query(exchange), "code", 503, 400, 599);
                if (status != 429 && status != 503) throw new IllegalArgumentException("code must be 429 or 503");
                exchange.getResponseHeaders().set("X-Backend", "backend-" + getPort());
                respond(exchange, status, "Simulated backend error", "text/plain");
            } catch (IllegalArgumentException invalid) { respond(exchange, 400, invalid.getMessage(), "text/plain"); }
            return;
        }
        final int cost;
        final int fileKiB;
        try {
            Map<String, String> values = path.startsWith("/api/") || path.equals("/shop") || path.startsWith("/shop/") ? Map.of() : query(exchange);
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
        if (!admission.enter()) { respond(exchange, 503, "Backend admission closed or full", "text/plain"); return; }
        boolean acquired = false;
        long started = 0;
        queued.incrementAndGet();
        try {
            long queueStarted = System.nanoTime();
            acquired = workers.tryAcquire(3, TimeUnit.SECONDS);
            queueWaitNanos.addAndGet(System.nanoTime() - queueStarted); queueWaitSamples.incrementAndGet();
            queued.decrementAndGet();
            if (!acquired) { respond(exchange, 503, "Backend queue timeout", "text/plain"); return; }
            active.incrementAndGet(); started = System.nanoTime();
            if (!healthy) { respond(exchange, 503, "Backend is down", "text/plain"); return; }
            if (LargeFileHandler.matches(path)) {
                LargeFileHandler.serve(exchange); completed.incrementAndGet(); return;
            }
            if (store != null && StoreApplication.matches(path)) {
                exchange.getResponseHeaders().set("X-Backend", "backend-" + getPort());
                if(storeFaults.before(exchange))return;
                store.handle(exchange); completed.incrementAndGet(); return;
            }
            if (path.startsWith("/api/") || path.equals("/shop") || path.startsWith("/shop/")) {
                exchange.getResponseHeaders().set("X-Backend", "backend-" + getPort());
                if (shop == null) respond(exchange, 503, "Shop disabled; enable SHOP_ENABLED and configure PostgreSQL", "text/plain");
                else shop.handle(exchange);
                completed.incrementAndGet();
                return;
            }
            research.handle(exchange, path, cost, fileKiB);
        } catch (InterruptedException e) {
            if (!acquired) queued.decrementAndGet();
            Thread.currentThread().interrupt();
        } finally {
            if (acquired) {
                double elapsed = (System.nanoTime() - started) / 1_000_000.0;
                synchronized (this) { serviceTimeMs = serviceTimeMs == 0 ? elapsed : 0.2 * elapsed + 0.8 * serviceTimeMs; }
                active.decrementAndGet(); workers.release();
            }
            admission.leave();
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
                + "healthy=%s%ndelayMs=%d%ncpuWorkers=%d%ncompletedRequests=%d%nreceivedRequests=%d%nprocessId=%d%nheapUsedBytes=%d%ncpuSamples=%d%ndraining=%s%nadmittedRequests=%d%nqueueWaitTotalMs=%.3f%nqueueWaitSamples=%d%n",
                cpuLoad, processCpuCores, cpuBudget, runtime.availableProcessors(),
                clamp((double) heapUsed / runtime.maxMemory()), heapUsed, runtime.maxMemory(),
                availableRatio(os.getCpuLoad()), hostTotal <= 0 ? -1 : clamp(1.0 - (double) hostFree / hostTotal), hostTotal,
                active.get(), queued.get(), capacity, serviceTimeMs, healthy, delayMs, backgroundSize(),
                completed.get(), received.get(), ProcessHandle.current().pid(), heapUsed, cpuSamples, admission.draining(),admission.admitted(),queueWaitNanos.get()/1_000_000.0,queueWaitSamples.get());
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
        final Thread thread = Thread.ofPlatform().daemon(true).name("workload-cpu-worker").unstarted(() -> {
            long value = 12345;
            while (running && !Thread.currentThread().isInterrupted()) { value = WorkloadHandler.burn(100_000, value); cpuSink = value; }
        });
    }
    private static double availableRatio(double value) { return Double.isFinite(value) && value >= 0 ? clamp(value) : -1; }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    @Override public void close() {
        setCpuWorkers(0); server.stop(0); sampler.shutdownNow(); executor.close();
    }
    public static void main(String[] args) throws IOException {
        com.example.proxy.server.HttpListenerPolicy.installDefaults();
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9001;
        int capacity = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        int defaultDelay = switch (port) { case 9002 -> 80; case 9003 -> 240; default -> 20; };
        int delay = args.length > 2 ? Integer.parseInt(args[2]) : defaultDelay;
        int cpuWorkers = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        double cpuBudget = args.length > 4 ? Double.parseDouble(args[4]) : capacity;
        BackendApplicationServer backend = new BackendApplicationServer(port, capacity, delay, cpuWorkers, cpuBudget);
        Runtime.getRuntime().addShutdownHook(new Thread(backend::close)); backend.start();
        System.out.printf(Locale.ROOT, "Backend http://127.0.0.1:%d capacity=%d delayMs=%d cpuBudget=%.2f cores%n", backend.getPort(), capacity, delay, cpuBudget);
    }
}
