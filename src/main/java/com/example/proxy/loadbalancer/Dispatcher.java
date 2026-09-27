package com.example.proxy.loadbalancer;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.metrics.BackendMetrics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.net.URI;

/** Single synchronization boundary for selection, reservations, health and learning. No I/O under lock. */
public final class Dispatcher {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<BackendServer, BackendMetrics> metrics = new LinkedHashMap<>();
    private final Map<BackendServer, EstimatedWork> work = new LinkedHashMap<>();
    private long workRejected;
    private final ProxyConfig config;
    private final LoadBalancer algorithm;
    private final LongSupplier clock;
    public Dispatcher(ProxyConfig config) { this(config, System::nanoTime); }
    public Dispatcher(ProxyConfig config, LongSupplier clock) {
        this.config = config;
        this.clock = clock;
        algorithm = LoadBalancerFactory.create(config.getLoadBalancerStrategy(), config.getExplorationInterval());
        config.getBackendServers().forEach(b -> {
            metrics.put(b, new BackendMetrics(b, config.getInitialLatencyMs()));
            work.put(b, new EstimatedWork(config.getInitialLatencyMs()));
        });
    }
    public Lease acquire() { return acquire(URI.create("/")); }
    public Lease acquire(URI uri) {
        lock.lock();
        try {
            long now = clock.getAsLong();
            var request = EstimatedWork.Request.of(uri);
            boolean adaptive = algorithm instanceof AdaptiveLoadBalancer;
            Map<BackendServer, EstimatedWork.Forecast> forecasts = new LinkedHashMap<>();
            List<BackendMetrics.Snapshot> candidates = new ArrayList<>();
            int total = 0;
            boolean workLimited = false;
            for (BackendMetrics value : metrics.values()) {
                var snapshot = value.snapshot(now, config);
                total += snapshot.inFlight();
                if (!snapshot.eligible()) continue;
                if (adaptive) {
                    var estimate = work.get(snapshot.backend());
                    estimate.generation(value.healthGeneration());
                    var forecast = estimate.forecast(request, now, snapshot.effectiveCapacity(), external(snapshot));
                    forecasts.put(snapshot.backend(), forecast);
                    double budget = completionBudgetMs();
                    if (forecast.completionMs() > budget || forecast.outstandingMs() + forecast.serviceMs()
                            > Math.max(1, Math.floor(snapshot.effectiveCapacity())) * budget) {
                        workLimited = true;
                        continue;
                    }
                }
                candidates.add(snapshot);
            }
            if (total >= config.getMaxInflight()) return null;
            var chosen = adaptive ? ((AdaptiveLoadBalancer) algorithm).select(candidates,
                    s -> forecasts.get(s.backend()).completionMs() * resourcePenalty(s)) : algorithm.select(candidates);
            if (chosen == null) { if (workLimited) workRejected++; return null; }
            EstimatedWork.Reservation reservation = adaptive ? work.get(chosen.backend()).reserve(request, now,
                    forecasts.get(chosen.backend()), Math.max(1, (int) Math.floor(chosen.effectiveCapacity())), external(chosen)) : null;
            metrics.get(chosen.backend()).reserve(now);
            return new Lease(chosen.backend(), reservation);
        } finally { lock.unlock(); }
    }
    private static int external(BackendMetrics.Snapshot s) { return s.telemetryFresh() ? s.externalOutstanding() : 0; }
    private double completionBudgetMs() {
        // Conservatively treat the read deadline as a completion deadline for buffered responses.
        return Math.min(config.getReadTimeoutMs(), config.getRequestTimeoutMs());
    }
    private static double resourcePenalty(BackendMetrics.Snapshot s) {
        // Preserve the existing dimensionless CPU/memory/error/uncertainty weights exactly.
        return s.scoreMs() / (s.latencyEwmaMs() * (1 + (s.inFlight() + external(s) + 1.0) / s.effectiveCapacity()));
    }
    public Probe beginProbe(BackendServer backend) {
        lock.lock();
        try {
            var state = require(backend);
            return new Probe(state.healthGeneration(), state.inFlight());
        } finally { lock.unlock(); }
    }
    public void recordHealth(BackendServer backend, Probe probe, boolean healthy) {
        lock.lock();
        try { require(backend).health(healthy, probe.generation(), clock.getAsLong(), config); }
        finally { lock.unlock(); }
    }
    public void recordTelemetry(BackendServer backend, Probe probe, BackendMetrics.Telemetry sample) {
        lock.lock();
        try {
            var state = require(backend);
            if (state.healthGeneration() == probe.generation())
                state.telemetry(sample, clock.getAsLong(), config.getEwmaAlpha(), probe.inFlight());
        } finally { lock.unlock(); }
    }
    private BackendMetrics require(BackendServer backend) {
        var result = metrics.get(backend);
        if (result == null) throw new IllegalArgumentException("Unknown backend");
        return result;
    }
    public List<BackendMetrics.Snapshot> snapshots() {
        lock.lock();
        try {
            long now = clock.getAsLong();
            return metrics.values().stream().map(m -> m.snapshot(now, config)).toList();
        } finally { lock.unlock(); }
    }
    public boolean hasAvailableBackend() { return snapshots().stream().anyMatch(BackendMetrics.Snapshot::eligible); }
    public String snapshotJson() {
        lock.lock();
        try {
        StringBuilder json = new StringBuilder("{\"strategy\":\"").append(algorithm.getName())
                .append("\",\"estimatedWorkRejected\":").append(workRejected).append(",\"backends\":[");
        boolean first = true;
        for (var s : snapshots()) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":\"").append(s.backend().getId()).append("\",\"host\":\"").append(s.backend().host())
                .append("\",\"port\":").append(s.backend().port()).append(",\"state\":\"").append(s.state())
                .append("\",\"alive\":").append(s.alive()).append(",\"inFlight\":").append(s.inFlight())
                .append(",\"dispatched\":").append(s.dispatched()).append(",\"completed\":").append(s.completed())
                .append(",\"errors\":").append(s.errors()).append(",\"latencyEwmaMs\":").append(number(s.latencyEwmaMs()))
                .append(",\"errorEwma\":").append(number(s.errorEwma())).append(",\"cpuLoad\":").append(number(s.cpuLoad()))
                .append(",\"memoryLoad\":").append(number(s.memoryLoad())).append(",\"telemetryAgeMs\":")
                .append(s.telemetryAgeMs() < 0 ? "null" : Long.toString(s.telemetryAgeMs()))
                .append(",\"telemetryFresh\":").append(s.telemetryFresh()).append(",\"capacity\":").append(s.backend().capacity())
                .append(",\"effectiveCapacity\":").append(number(s.effectiveCapacity()))
                .append(",\"scoreMs\":").append(s.alive() ? number(s.scoreMs()) : "null")
                .append(",\"warmup\":").append(number(s.warmup())).append(",\"remoteActiveRequests\":").append(s.remoteActiveRequests())
                .append(",\"remoteQueuedRequests\":").append(s.remoteQueuedRequests())
                .append(",\"externalOutstanding\":").append(s.externalOutstanding())
                .append(",\"inFlightLimit\":").append(s.inFlightLimit());
            if (algorithm instanceof AdaptiveLoadBalancer) {
                var estimate = work.get(s.backend());
                estimate.generation(require(s.backend()).healthGeneration());
                var f = estimate.forecast(EstimatedWork.Request.of(URI.create("/")), clock.getAsLong(), s.effectiveCapacity(), external(s));
                json.append(",\"estimatedOutstandingWorkMs\":").append(number(f.outstandingMs()))
                    .append(",\"estimatedQueuedWorkMs\":").append(number(f.queuedMs()))
                    .append(",\"estimatedNextSlotMs\":").append(number(f.waitMs()))
                    .append(",\"completionBudgetMs\":").append(number(completionBudgetMs()));
            }
            json.append('}');
        }
        return json.append("]}").toString();
        } finally { lock.unlock(); }
    }
    private static String number(double value) { return Double.isFinite(value) ? Double.toString(value) : "null"; }
    public record Probe(long generation, int inFlight) { }

    public final class Lease implements AutoCloseable {
        private final BackendServer backend;
        private final EstimatedWork.Reservation reservation;
        private boolean released;
        private Lease(BackendServer backend, EstimatedWork.Reservation reservation) { this.backend = backend; this.reservation = reservation; }
        public BackendServer backend() { return backend; }
        public void complete(double latencyMs, boolean error, boolean transportFailure) {
            lock.lock();
            try {
                if (released) return;
                released = true;
                if (reservation != null) {
                    var estimate = work.get(backend);
                    estimate.generation(require(backend).healthGeneration());
                    estimate.complete(reservation, latencyMs, error || transportFailure, config.getEwmaAlpha());
                }
                require(backend).complete(latencyMs, error || transportFailure, transportFailure, config.getEwmaAlpha());
            } finally { lock.unlock(); }
        }
        public void close() {
            lock.lock();
            try {
                if (!released) {
                    released = true;
                    if (reservation != null) work.get(backend).release(reservation);
                    require(backend).release();
                }
            } finally { lock.unlock(); }
        }
    }
}
