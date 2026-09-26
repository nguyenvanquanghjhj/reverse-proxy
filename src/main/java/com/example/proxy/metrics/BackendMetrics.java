package com.example.proxy.metrics;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;

/** Mutable only while Dispatcher holds its lock. Never exposed to callers. */
public final class BackendMetrics {
    private final BackendServer backend;
    private boolean alive;
    private int successes;
    private long healthGeneration;
    private long recoveredNanos;
    private int inFlight;
    private long dispatched, completed, errors;
    private long lastSelectedNanos;
    private double latencyEwmaMs;
    private double errorEwma;
    private boolean latencySampled;
    private double cpuLoad = Double.NaN, memoryLoad = Double.NaN;
    private int remoteActive, remoteQueued, externalOutstanding;
    private long telemetryNanos;
    private boolean telemetryReceived;

    public BackendMetrics(BackendServer backend, double initialLatencyMs) {
        this.backend = backend;
        latencyEwmaMs = initialLatencyMs;
    }
    public long healthGeneration() { return healthGeneration; }
    public int inFlight() { return inFlight; }
    public void reserve(long now) { inFlight++; dispatched++; lastSelectedNanos = now; }
    public void release() {
        if (inFlight <= 0) throw new IllegalStateException("Unbalanced lease release");
        inFlight--;
    }
    public void complete(double elapsedMs, boolean error, boolean transportFailure, double alpha) {
        release();
        completed++;
        if (error) errors++;
        // Fast refusals and 5xx must not make an unhealthy backend appear faster.
        if (!error && Double.isFinite(elapsedMs) && elapsedMs >= 0) {
            double sample = Math.max(0.1, elapsedMs);
            latencyEwmaMs = latencySampled ? ewma(latencyEwmaMs, sample, alpha) : sample;
            latencySampled = true;
        }
        errorEwma = ewma(errorEwma, error ? 1 : 0, alpha);
        if (transportFailure) down();
    }
    private void down() {
        alive = false;
        successes = 0;
        healthGeneration++;
        telemetryReceived = false;
        cpuLoad = memoryLoad = Double.NaN;
        externalOutstanding = 0;
    }
    /** A success sampled before a later transport failure must not resurrect the backend. */
    public void health(boolean healthy, long probeGeneration, long now, ProxyConfig config) {
        if (probeGeneration != healthGeneration) return;
        if (!healthy) { down(); return; }
        if (alive) return;
        if (++successes >= config.getRecoverySuccesses()) {
            alive = true;
            recoveredNanos = now;
            successes = 0;
            // Recover with a conservative prior, then learn from real workload (not /health latency).
            latencyEwmaMs = Math.max(latencyEwmaMs, config.getInitialLatencyMs());
            latencySampled = false;
        }
    }
    public void telemetry(Telemetry sample, long now, double alpha, int inFlightAtProbeStart) {
        cpuLoad = smoothAvailable(cpuLoad, sample.cpuLoad(), alpha);
        memoryLoad = smoothAvailable(memoryLoad, sample.memoryLoad(), alpha);
        remoteActive = sample.activeRequests();
        remoteQueued = sample.queuedRequests();
        // Approximation of other clients' work. Local leases are added live after this snapshot.
        externalOutstanding = Math.max(0, remoteActive + remoteQueued - inFlightAtProbeStart);
        telemetryNanos = now;
        telemetryReceived = true;
    }
    private static double smoothAvailable(double old, double sample, double alpha) {
        if (!Double.isFinite(sample)) return Double.NaN;
        return Double.isFinite(old) ? ewma(old, sample, alpha) : sample;
    }
    private static double ewma(double old, double sample, double alpha) {
        return old + alpha * (sample - old);
    }
    public Snapshot snapshot(long now, ProxyConfig config) {
        long ageMs = telemetryReceived ? Math.max(0, (now - telemetryNanos) / 1_000_000) : -1;
        boolean fresh = telemetryReceived && ageMs <= config.getMetricsStaleMs();
        double warmup = !alive ? 0 : config.getWarmupMs() == 0 ? 1
                : Math.min(1, Math.max(0.1, (now - recoveredNanos) / (config.getWarmupMs() * 1_000_000.0)));
        double effectiveCapacity = backend.capacity() * Math.max(0.1, warmup);
        int limit = Math.max(1, (int) Math.ceil(config.getBackendMaxInflight() * warmup));
        double outstanding = inFlight + (fresh ? externalOutstanding : 0);
        double cpu = fresh && Double.isFinite(cpuLoad) ? cpuLoad : 0;
        double memoryPressure = fresh && Double.isFinite(memoryLoad) ? Math.max(0, (memoryLoad - 0.75) / 0.25) : 0;
        // All penalties dimensionless. CPU pressure bounded at 20 to avoid infinities.
        double cpuPressure = cpu / Math.max(0.05, 1 - cpu);
        double uncertainty = !fresh ? 0.25 : (!Double.isFinite(cpuLoad) || !Double.isFinite(memoryLoad) ? 0.125 : 0);
        double penalty = 1 + 0.35 * cpuPressure + 0.5 * memoryPressure + 2 * errorEwma + uncertainty;
        double score = latencyEwmaMs * (1 + (outstanding + 1) / effectiveCapacity) * penalty;
        return new Snapshot(backend, alive, inFlight, dispatched, completed, errors, latencyEwmaMs,
                errorEwma, cpuLoad, memoryLoad, ageMs, fresh, effectiveCapacity, score, warmup,
                remoteActive, remoteQueued, externalOutstanding, lastSelectedNanos, limit);
    }
    public record Telemetry(double cpuLoad, double memoryLoad, int activeRequests, int queuedRequests) {
        public Telemetry {
            if ((!Double.isNaN(cpuLoad) && (!Double.isFinite(cpuLoad) || cpuLoad < 0 || cpuLoad > 1))
                    || (!Double.isNaN(memoryLoad) && (!Double.isFinite(memoryLoad) || memoryLoad < 0 || memoryLoad > 1))
                    || activeRequests < 0 || queuedRequests < 0 || activeRequests > 1_000_000 || queuedRequests > 1_000_000)
                throw new IllegalArgumentException("Invalid backend telemetry");
        }
    }
    public record Snapshot(BackendServer backend, boolean alive, int inFlight, long dispatched,
            long completed, long errors, double latencyEwmaMs, double errorEwma, double cpuLoad,
            double memoryLoad, long telemetryAgeMs, boolean telemetryFresh, double effectiveCapacity,
            double scoreMs, double warmup, int remoteActiveRequests, int remoteQueuedRequests,
            int externalOutstanding, long lastSelectedNanos, int inFlightLimit) {
        public boolean eligible() { return alive && inFlight < inFlightLimit; }
        public String state() { return !alive ? "DOWN" : warmup < 1 ? "WARMING" : "UP"; }
    }
}
