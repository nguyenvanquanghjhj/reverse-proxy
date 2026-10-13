package com.example.proxy.healthcheck;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.loadbalancer.Dispatcher;
import com.example.proxy.metrics.BackendMetrics;
import com.example.proxy.util.AppLogger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One serial polling loop per backend; health and telemetry failures have different meanings. */
public final class HealthChecker implements AutoCloseable {
    private static final AppLogger log = AppLogger.of(HealthChecker.class);
    private boolean stopped;
    private final LongAdder healthFailures = new LongAdder(), telemetryFailures = new LongAdder();
    public long healthFailures() { return healthFailures.sum(); }
    public long telemetryFailures() { return telemetryFailures.sum(); }
    private final ProxyConfig config;
    private final Dispatcher dispatcher;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client;
    private final AtomicBoolean running = new AtomicBoolean();
    public HealthChecker(ProxyConfig config, Dispatcher dispatcher) {
        this.config = config;
        this.dispatcher = dispatcher;
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(config.getHealthCheckTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public synchronized void start() {
        if (stopped) throw new IllegalStateException("Health checker is closed");
        if (!running.compareAndSet(false, true)) return;
        for (BackendServer backend : config.getBackendServers()) workers.execute(() -> pollLoop(backend));
    }
    private void pollLoop(BackendServer backend) {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            var healthProbe = dispatcher.beginProbe(backend);
            boolean healthy;
            try {
                var response = fetch(backend.getBaseUrl() + config.getHealthCheckPath());
                healthy = response.statusCode() >= 200 && response.statusCode() < 300;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException e) { healthy = false; }
            catch (RuntimeException failure) {
                log.warn("health_probe_failed error_type=" + failure.getClass().getSimpleName());
                healthy = false;
            }
            if (!healthy) healthFailures.increment();
            if (!running.get()) break;
            dispatcher.recordHealth(backend, healthProbe, healthy);
            if (healthy) {
                var metricsProbe = dispatcher.beginProbe(backend);
                try {
                    var response = fetch(backend.getBaseUrl() + config.getMetricsPath());
                    if (response.statusCode() == 200 && running.get())
                        dispatcher.recordTelemetry(backend, metricsProbe, parseTelemetry(response.body()));
                    else telemetryFailures.increment();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException | TimeoutException | IOException | IllegalArgumentException invalid) {
                    telemetryFailures.increment();
                    // Missing/invalid telemetry ages out; a working application is NOT marked DOWN.
                }
            }
            try { Thread.sleep(config.getHealthCheckIntervalMs()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }
    private HttpResponse<byte[]> fetch(String address) throws InterruptedException, ExecutionException, TimeoutException {
        var request = HttpRequest.newBuilder(URI.create(address))
                .timeout(Duration.ofMillis(config.getHealthCheckTimeoutMs())).GET().build();
        CompletableFuture<HttpResponse<byte[]>> response;
        synchronized (this) {
            if (stopped) throw new InterruptedException("Health checker is closed");
            response = client.sendAsync(request, ignored -> new LimitedBody(16384));
        }
        try { return response.get(config.getHealthCheckTimeoutMs(), TimeUnit.MILLISECONDS); }
        finally { if (!response.isDone()) response.cancel(true); }
    }
    public static BackendMetrics.Telemetry parseTelemetry(byte[] body) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(new String(body, StandardCharsets.UTF_8)));
        return new BackendMetrics.Telemetry(ratio(p, "cpuLoad"), ratio(p, "memoryLoad"),
                Integer.parseInt(required(p, "activeRequests")), Integer.parseInt(required(p, "queuedRequests")));
    }
    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing metric: " + key);
        return value.trim();
    }
    private static double ratio(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.trim().equals("-1") || value.trim().equalsIgnoreCase("NaN"))
            return Double.NaN;
        return Double.parseDouble(value.trim());
    }
    public void stop() {
        synchronized (this) {
            if (stopped) return;
            stopped = true;
            running.set(false);
            workers.shutdownNow();
        }
        client.shutdownNow();
        try { if (!workers.awaitTermination(2, TimeUnit.SECONDS)) log.warn("health_workers_shutdown_timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    public void close() { stop(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - data.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalArgumentException("Metrics response too large"));
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                data.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(data.toByteArray()); }
    }
}
