package com.example.proxy.loadbalancer;

import static com.example.proxy.loadbalancer.AdaptiveCandidateScorer.external;
import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.metrics.BackendMetrics;
import com.example.proxy.util.Json;
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
    private final Map<BackendServer, CircuitBreaker> circuits = new LinkedHashMap<>();
    private long workRejected;
    private long decisionSequence;
    private AdaptiveDecision lastDecision;
    private volatile boolean draining;
    public void stopAccepting() {
        lock.lock();
        try { draining = true; } finally { lock.unlock(); }
    }
    private final ProxyConfig config;
    private LoadBalancer algorithm;
    private final LongSupplier clock;
    public Dispatcher(ProxyConfig config) { this(config, System::nanoTime); }
    public Dispatcher(ProxyConfig config, LongSupplier clock) {
        this.config = config;
        this.clock = clock;
        algorithm = LoadBalancerFactory.create(config.getLoadBalancerStrategy(), config.getExplorationInterval());
        config.getBackendServers().forEach(b -> {
            metrics.put(b, new BackendMetrics(b, config.getInitialLatencyMs()));
            circuits.put(b, new CircuitBreaker(config));
            work.put(b, new EstimatedWork(config.getInitialLatencyMs()));
        });
    }
    /** Cycles strategies atomically with dispatch selection; in-flight leases remain valid. */
    public String cycleStrategy() {
        lock.lock();
        try {
            String next = switch (algorithm.getName()) {
                case "ADAPTIVE" -> "ROUND_ROBIN";
                case "ROUND_ROBIN" -> "LEAST_CONNECTIONS";
                case "LEAST_CONNECTIONS" -> "ADAPTIVE";
                default -> throw new IllegalStateException("Unknown active strategy: " + algorithm.getName());
            };
            algorithm = LoadBalancerFactory.create(next, config.getExplorationInterval());
            lastDecision = null;
            return next;
        } finally { lock.unlock(); }
    }
    public Lease acquire() { return acquire(URI.create("/")); }
    public Lease acquire(URI uri) {
        lock.lock();
        try {
            // 1. Check draining before touching selection state.
            if (draining) {
                trace(EstimatedWork.Request.of(uri), "DRAINING", null, List.of());
                return null;
            }
            long now = clock.getAsLong();
            var request = EstimatedWork.Request.of(uri);
            boolean adaptive = algorithm instanceof AdaptiveLoadBalancer;
            Map<BackendServer, EstimatedWork.Forecast> forecasts = new LinkedHashMap<>();
            List<BackendMetrics.Snapshot> candidates = new ArrayList<>();
            List<AdaptiveDecision.Candidate> explanation = config.isDecisionTraceEnabled() && adaptive ? new ArrayList<>() : null;
            int total = 0;
            boolean workLimited = false;
            // 2. Snapshot eligible backends in configuration order, under this same lock.
            for (BackendMetrics value : metrics.values()) {
                var snapshot = value.snapshot(now, config);
                total += snapshot.inFlight();
                if (!snapshot.eligible() || !circuits.get(snapshot.backend()).eligible(now)) {
                    if (explanation != null) explanation.add(explain(snapshot, !snapshot.alive() ? "DOWN"
                            : !snapshot.eligible() ? "INFLIGHT_LIMIT" : "CIRCUIT_UNAVAILABLE", null));
                    continue;
                }
                if (adaptive) {
                    // 3. Apply Adaptive work budget to each eligible snapshot; keep generation/probe state here.
                    var estimate = work.get(snapshot.backend());
                    estimate.generation(value.healthGeneration());
                    var forecast = estimate.forecast(request, now, snapshot.effectiveCapacity(), external(snapshot));
                    forecasts.put(snapshot.backend(), forecast);
                    double budget = completionBudgetMs();
                    boolean overBudget = forecast.completionMs() > budget || forecast.outstandingMs() + forecast.serviceMs()
                            > Math.max(1, Math.floor(snapshot.effectiveCapacity())) * budget;
                    // An old service estimate above budget must not exclude a recovered idle backend forever.
                    // At most one live relearning probe per backend, with a full request-timeout cooldown.
                    boolean relearn = snapshot.inFlight() == 0 && external(snapshot) == 0
                            && now - snapshot.lastSelectedNanos() >= config.getRequestTimeoutMs() * 1_000_000L;
                    if (overBudget && !relearn) {
                        if (explanation != null) explanation.add(explain(snapshot, "WORK_BUDGET", forecast));
                        workLimited = true;
                        continue;
                    }
                    if (explanation != null) explanation.add(explain(snapshot, overBudget ? "RELEARNING_PROBE" : "ELIGIBLE", forecast));
                }
                candidates.add(snapshot);
            }
            // 4. Select a candidate; EstimatedWork predicts, AdaptiveLoadBalancer only chooses.
            if (total >= config.getMaxInflight()) { trace(request, "MAX_INFLIGHT", null, explanation); return null; }
            var chosen = adaptive ? ((AdaptiveLoadBalancer) algorithm).select(candidates,
                    s -> AdaptiveCandidateScorer.score(s, forecasts.get(s.backend()))) : algorithm.select(candidates);
            if (chosen == null) {
                if (workLimited) workRejected++;
                trace(request, "NO_ELIGIBLE_BACKEND", null, explanation);
                return null;
            }
            // 5. Record the pre-reservation trace at its original position (still under the lock).
            if (adaptive) trace(request, ((AdaptiveLoadBalancer) algorithm).selectionReason(), chosen.backend().getId(), explanation);
            // 6. Reserve work, in-flight and the circuit ticket atomically before returning the lease.
            EstimatedWork.Reservation reservation = adaptive ? work.get(chosen.backend()).reserve(request, now,
                    forecasts.get(chosen.backend()), Math.max(1, (int) Math.floor(chosen.effectiveCapacity())), external(chosen)) : null;
            metrics.get(chosen.backend()).reserve(now);
            return new Lease(chosen.backend(), algorithm.getName(), reservation, circuits.get(chosen.backend()).reserve(now));
        } finally { lock.unlock(); }
    }
    private static AdaptiveDecision.Candidate explain(BackendMetrics.Snapshot s, String eligibility, EstimatedWork.Forecast f) {
        Double penalty = f == null ? null : AdaptiveCandidateScorer.resourcePenalty(s);
        return new AdaptiveDecision.Candidate(s.backend().getId(), eligibility, s.inFlight(), s.effectiveCapacity(),
                f == null ? null : f.serviceMs(), f == null ? null : f.waitMs(), f == null ? null : f.outstandingMs(),
                penalty, f == null ? null : f.completionMs() * penalty);
    }
    private void trace(EstimatedWork.Request request, String reason, String backend, List<AdaptiveDecision.Candidate> candidates) {
        if (config.isDecisionTraceEnabled() && algorithm instanceof AdaptiveLoadBalancer)
            lastDecision = new AdaptiveDecision(++decisionSequence, request.route().name(), request.units(), completionBudgetMs(),
                    reason, backend, candidates == null ? List.of() : candidates);
    }
    public String decisionJson() {
        lock.lock();
        try {
            boolean enabled = config.isDecisionTraceEnabled() && algorithm instanceof AdaptiveLoadBalancer;
            return "{\"enabled\":" + enabled + ",\"lastDecision\":" + (lastDecision == null ? "null" : lastDecision.json()) + "}";
        } finally { lock.unlock(); }
    }
    private double completionBudgetMs() {
        // Conservatively treat the read deadline as a completion deadline for buffered responses.
        return Math.min(config.getReadTimeoutMs(), config.getRequestTimeoutMs());
    }
    /** Runtime-only backend details used by the demo UI; kept separate from scheduler snapshots. */
    public String backendRuntimeJson() {
        lock.lock();
        try {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (var s : snapshots()) {
                if (!first) json.append(',');
                first = false;
                var runtime = s.runtimeInfo();
                json.append("{\"port\":").append(s.backend().port())
                    .append(",\"backendPid\":").append(runtime.available() ? Long.toString(runtime.processId()) : "null")
                    .append(",\"backendCapacity\":").append(runtime.available() ? Integer.toString(runtime.capacity()) : "null")
                    .append(",\"configuredDelayMs\":").append(runtime.available() ? Integer.toString(runtime.delayMs()) : "null")
                    .append(",\"backendServiceTimeMs\":").append(runtime.available() ? number(runtime.serviceTimeMs()) : "null")
                    .append(",\"backendCompletedRequests\":").append(runtime.available() ? Long.toString(runtime.completedRequests()) : "null")
                    .append(",\"backendReceivedRequests\":").append(runtime.available() ? Long.toString(runtime.receivedRequests()) : "null")
                    .append(",\"heapUsedBytes\":").append(runtime.available() ? Long.toString(runtime.heapUsedBytes()) : "null")
                    .append('}');
            }
            return json.append(']').toString();
        } finally { lock.unlock(); }
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
    public boolean hasAvailableBackend() {
        lock.lock();
        try {
            if (draining) return false;
            var current = snapshots();
            long now = clock.getAsLong();
            return current.stream().mapToLong(BackendMetrics.Snapshot::inFlight).sum() < config.getMaxInflight()
                    && current.stream().anyMatch(s -> s.eligible() && circuits.get(s.backend()).eligible(now));
        } finally { lock.unlock(); }
    }

    public String snapshotJson() {
        lock.lock();
        try {
        StringBuilder json = new StringBuilder("{\"strategy\":").append(Json.quote(algorithm.getName()))
                .append(",\"estimatedWorkRejected\":").append(workRejected).append(",\"backends\":[");
        boolean first = true;
        for (var s : snapshots()) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":").append(Json.quote(s.backend().getId())).append(",\"host\":").append(Json.quote(s.backend().host()))
                .append(",\"port\":").append(s.backend().port()).append(",\"state\":").append(Json.quote(s.state()))
                .append(",\"alive\":").append(s.alive()).append(",\"inFlight\":").append(s.inFlight())
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
            json.append(',').append(circuits.get(s.backend()).json());
            json.append('}');
        }
        return json.append("]}").toString();
        } finally { lock.unlock(); }
    }
    public String prometheus() {
        lock.lock();
        try {
        StringBuilder out = new StringBuilder("# TYPE proxy_backend_selection_total counter\n# TYPE proxy_backend_in_flight gauge\n");
        out.append("# TYPE proxy_backend_circuit_state gauge\n# TYPE proxy_backend_circuit_open_total counter\n");
        out.append("# HELP proxy_backend_circuit_state CLOSED=0, OPEN=1, HALF_OPEN=2\n");
        out.append("# TYPE proxy_backend_circuit_failures gauge\n# TYPE proxy_backend_circuit_probe_in_flight gauge\n");
        for (var s : snapshots()) {
            // Backend identifiers are constrained by BackendServer's host validation.
            String label = "{backend=\"" + s.backend().getId() + "\"} ";
            out.append("proxy_backend_selection_total").append(label).append(s.dispatched()).append('\n');
            out.append("proxy_backend_in_flight").append(label).append(s.inFlight()).append('\n');
            out.append("proxy_backend_circuit_state").append(label).append(circuits.get(s.backend()).stateCode()).append('\n');
            out.append("proxy_backend_circuit_open_total").append(label).append(circuits.get(s.backend()).openedCount()).append('\n');
            out.append("proxy_backend_circuit_failures").append(label).append(circuits.get(s.backend()).failures()).append('\n');
            out.append("proxy_backend_circuit_probe_in_flight").append(label).append(circuits.get(s.backend()).probing() ? 1 : 0).append('\n');
        }
        return out.toString();
        } finally { lock.unlock(); }
    }
    private static String number(double value) { return Json.number(value); }
    public record Probe(long generation, int inFlight) { }

    public final class Lease implements AutoCloseable {
        private final BackendServer backend;
        private final String strategy;
        private final EstimatedWork.Reservation reservation;
        private boolean released;
        private final long circuitTicket;
        private Lease(BackendServer backend, String strategy, EstimatedWork.Reservation reservation, long circuitTicket) {
            this.backend = backend; this.strategy = strategy; this.reservation = reservation; this.circuitTicket = circuitTicket;
        }
        public BackendServer backend() { return backend; }
        public String strategy() { return strategy; }
        public void complete(double latencyMs, boolean error, boolean transportFailure) {
            complete(latencyMs, error, transportFailure, true);
        }
        /** Proxy policy limits do not establish backend failure or recovery. */
        public void completePolicyRejection(double latencyMs) {
            complete(latencyMs, true, false, false);
        }
        private void complete(double latencyMs, boolean error, boolean transportFailure, boolean circuitOutcome) {
            lock.lock();
            try {
                if (released) return;
                released = true;
                if (circuitOutcome) circuits.get(backend).complete(circuitTicket, error || transportFailure, clock.getAsLong());
                else circuits.get(backend).cancel(circuitTicket, clock.getAsLong());
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
                    circuits.get(backend).cancel(circuitTicket, clock.getAsLong());
                    if (reservation != null) work.get(backend).release(reservation);
                    require(backend).release();
                }
            } finally { lock.unlock(); }
        }
    }
}
