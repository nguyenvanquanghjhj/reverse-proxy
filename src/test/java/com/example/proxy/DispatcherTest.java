package com.example.proxy;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ConfigLoader;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.healthcheck.HealthChecker;
import com.example.proxy.loadbalancer.Dispatcher;
import com.example.proxy.metrics.BackendMetrics;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic decisions and concurrent reservation invariants; no sockets or sleeps. */
public final class DispatcherTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        run("CPU pressure can outweigh a lower in-flight count", DispatcherTest::cpuPressure);
        run("latency can outweigh a higher in-flight count", DispatcherTest::latencyPreference);
        run("fast errors do not make a backend attractive", DispatcherTest::errorPenalty);
        run("stale telemetry does not retain CPU or remote backlog penalties", DispatcherTest::staleTelemetry);
        run("external work is counted without counting proxy leases twice", DispatcherTest::externalWork);
        run("all strategies reserve atomically and respect both admission limits", DispatcherTest::concurrentAdmission);
        run("concurrent lease completion releases exactly once", DispatcherTest::idempotentRelease);
        run("unprobed and DOWN backends never receive a request", DispatcherTest::downRouting);
        run("recovery needs consecutive probes and ramps admission gradually", DispatcherTest::recoveryWarmup);
        run("old health and telemetry cannot override a transport failure", DispatcherTest::staleProbe);
        run("bounded exploration relearns an idle slow backend", DispatcherTest::exploration);
        run("configuration rejects ambiguous or nonfinite settings", DispatcherTest::invalidConfiguration);
        run("telemetry parser validates ratios and counters", DispatcherTest::telemetryValidation);
        System.out.println("DispatcherTest: " + passed + " scenarios passed.");
    }

    private static void cpuPressure() {
        Fixture f = new Fixture();
        learnLatencies(f, 50, 50);
        f.telemetry(0, 0.99, 0.2, 0, 0);
        f.telemetry(1, 0.05, 0.2, 0, 0);
        try (var occupied = required(f.acquire()); var next = required(f.acquire())) {
            equal(f.backend(1), occupied.backend(), "first request avoids busy CPU");
            equal(f.backend(1), next.backend(), "CPU-idle backend wins despite having more local work");
            check(f.snapshot(0).inFlight() == 0 && f.snapshot(1).inFlight() == 2,
                    "decision must differ from least-connections");
        }
    }

    private static void latencyPreference() {
        Fixture f = new Fixture();
        learnLatencies(f, 10, 200);
        List<Dispatcher.Lease> held = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                var lease = required(f.acquire());
                held.add(lease);
                equal(f.backend(0), lease.backend(), "fast backend still has a lower completion estimate");
            }
            check(f.snapshot(0).inFlight() == 4 && f.snapshot(1).inFlight() == 0,
                    "latency must influence selection beyond connection counts");
        } finally { held.forEach(Dispatcher.Lease::close); }
    }

    private static void errorPenalty() {
        Fixture f = new Fixture();
        learnLatencies(f, 50, 50);
        var failed = required(f.acquire());
        equal(f.backend(0), failed.backend(), "older equally fast backend chosen for sample");
        failed.complete(0.1, true, false);
        check(f.snapshot(0).alive(), "an application error does not imply broken transport");
        near(50, f.snapshot(0).latencyEwmaMs(), "a fast error is not a successful latency sample");
        check(f.snapshot(0).errors() == 1, "error counted once");
        check(f.snapshot(0).scoreMs() > f.snapshot(1).scoreMs(), "errors raise estimated cost");
        try (var next = required(f.acquire())) {
            equal(f.backend(1), next.backend(), "healthy completion history preferred");
        }
    }

    private static void staleTelemetry() {
        Fixture f = new Fixture("backend.servers", "127.0.0.1:9001:1,127.0.0.1:9002:1",
                "metrics.stale.ms", "20");
        f.telemetry(0, 0.99, 0.99, 1000, 1000);
        f.telemetry(1, 0, 0.2, 0, 0);
        try (var occupied = required(f.acquire())) {
            equal(f.backend(1), occupied.backend(), "fresh pressure must affect routing");
            f.advanceMs(21);
            f.telemetry(1, 0, 0.2, 1, 0);
            check(!f.snapshot(0).telemetryFresh(), "old sample is marked stale");
            check(f.snapshot(1).telemetryFresh(), "other backend has a current sample");
            try (var next = required(f.acquire())) {
                equal(f.backend(0), next.backend(), "stale CPU, memory and backlog must not keep penalizing A");
            }
        }
    }

    private static void externalWork() {
        Fixture f = new Fixture();
        try (var local = required(f.acquire())) {
            equal(f.backend(0), local.backend(), "first local reservation");
            f.telemetry(0, 0.1, 0.2, 1, 0);
            check(f.snapshot(0).externalOutstanding() == 0, "the backend observed our own lease");
            f.telemetry(0, 0.1, 0.2, 5, 2);
            check(f.snapshot(0).externalOutstanding() == 6, "include direct-client active and queued work");
            try (var next = required(f.acquire())) {
                equal(f.backend(1), next.backend(), "external work can divert a request");
            }
        }
    }

    private static void concurrentAdmission() throws Exception {
        for (String strategy : List.of("ADAPTIVE", "ROUND_ROBIN", "LEAST_CONNECTIONS")) {
            reservationBurst(strategy, 7, 3, 7);  // Global admission is the tighter limit.
            reservationBurst(strategy, 40, 3, 9); // Per-backend admission is the tighter limit.
        }
    }

    private static void reservationBurst(String strategy, int globalLimit, int backendLimit, int expected)
            throws Exception {
        Fixture f = new Fixture("backend.servers", "127.0.0.1:9001:8,127.0.0.1:9002:8,127.0.0.1:9003:8",
                "loadbalancer.strategy", strategy, "proxy.max.inflight", "" + globalLimit,
                "backend.max.inflight", "" + backendLimit);
        int clients = 64;
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch selected = new CountDownLatch(clients);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < clients; i++) futures.add(workers.submit(() -> {
                Dispatcher.Lease lease = null;
                ready.countDown();
                try {
                    await(start, "concurrent start");
                    lease = f.dispatcher.acquire();
                    if (lease != null) accepted.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("reservation interrupted", e);
                } finally { selected.countDown(); }
                try {
                    await(release, "concurrent release");
                    if (lease != null) lease.complete(10, false, false);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("completion interrupted", e);
                } finally { if (lease != null) lease.close(); }
            }));
            await(ready, "all clients ready");
            start.countDown();
            await(selected, "all clients selected");
            int inFlight = f.dispatcher.snapshots().stream().mapToInt(BackendMetrics.Snapshot::inFlight).sum();
            check(accepted.get() == expected, strategy + ": expected " + expected + " accepted, got " + accepted);
            check(inFlight == expected, strategy + ": no lost increments");
            for (var s : f.dispatcher.snapshots())
                check(s.inFlight() >= 0 && s.inFlight() <= backendLimit, strategy + ": backend cap exceeded");
            check(f.dispatcher.acquire() == null, strategy + ": further acquisition must be rejected");
            release.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            check(f.dispatcher.snapshots().stream().mapToInt(BackendMetrics.Snapshot::inFlight).sum() == 0,
                    strategy + ": no leaked reservations");
            check(f.dispatcher.snapshots().stream().mapToLong(BackendMetrics.Snapshot::completed).sum() == expected,
                    strategy + ": one completion per accepted request");
            check(f.dispatcher.snapshots().stream().mapToLong(BackendMetrics.Snapshot::dispatched).sum() == expected,
                    strategy + ": rejected requests do not increment dispatch count");
            try (var retry = required(f.acquire())) { check(retry.backend() != null, "capacity returns after completion"); }
        } finally {
            start.countDown();
            release.countDown();
            workers.shutdownNow();
            check(workers.awaitTermination(10, TimeUnit.SECONDS), "worker cleanup timed out");
        }
    }

    private static void idempotentRelease() throws Exception {
        Fixture f = new Fixture();
        var lease = required(f.acquire());
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 32; i++) futures.add(workers.submit(() -> {
                try { await(start, "shared lease completion"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                lease.complete(25, false, false);
                lease.close();
                lease.complete(1, true, true);
            }));
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            var s = f.snapshot(0);
            check(s.inFlight() == 0 && s.completed() == 1 && s.dispatched() == 1,
                    "competing completions must not double-release or double-count");
            check(s.alive() && s.errors() == 0, "late repeated completion cannot change health or errors");
            near(25, s.latencyEwmaMs(), "only the first successful completion updates latency");
            var closed = required(f.acquire());
            closed.close();
            closed.close();
            closed.complete(5, true, true);
            check(f.dispatcher.snapshots().stream().allMatch(x -> x.inFlight() == 0 && x.alive()),
                    "closing before completion also releases once without false transport failure");
        } finally {
            start.countDown();
            lease.close();
            workers.shutdownNow();
            check(workers.awaitTermination(10, TimeUnit.SECONDS), "worker cleanup timed out");
        }
    }

    private static void downRouting() {
        Fixture f = new Fixture(false);
        check(f.acquire() == null, "no backend is eligible before health verification");
        f.health(0, true);
        try (var lease = required(f.acquire())) { equal(f.backend(0), lease.backend(), "only verified backend eligible"); }
        f.health(0, false);
        check(f.acquire() == null, "a DOWN backend cannot be selected");
        f.health(1, true);
        try (var lease = required(f.acquire())) { equal(f.backend(1), lease.backend(), "healthy peer is still routable"); }
    }

    private static void recoveryWarmup() {
        Fixture f = new Fixture(false, "backend.servers", "127.0.0.1:9001:10",
                "healthcheck.recovery.successes", "3", "adaptive.warmup.ms", "1000", "backend.max.inflight", "20");
        f.health(0, true);
        f.health(0, true);
        check(f.acquire() == null, "two successes are insufficient");
        f.health(0, false);
        f.health(0, true);
        f.health(0, true);
        check(f.acquire() == null, "a failed probe resets the consecutive-success streak");
        f.health(0, true);
        check(f.snapshot(0).state().equals("WARMING") && f.snapshot(0).inFlightLimit() == 2,
                "recovery begins with 10% of the normal admission limit");
        try (var first = required(f.acquire()); var second = required(f.acquire())) {
            check(f.acquire() == null, "slow start limits reservations");
        }
        f.advanceMs(500);
        var halfway = f.snapshot(0);
        check(halfway.warmup() >= 0.5 && halfway.warmup() < 1, "warmup increases with the monotonic clock");
        check(halfway.inFlightLimit() >= 10 && halfway.inFlightLimit() < 20, "admission ramps with warmup");
        f.advanceMs(500);
        check(f.snapshot(0).state().equals("UP") && f.snapshot(0).inFlightLimit() == 20,
                "normal capacity returns after the warmup interval");
        var failed = required(f.acquire());
        failed.complete(1, true, true);
        check(!f.snapshot(0).alive(), "transport failure marks the backend DOWN");
        f.health(0, true);
        f.health(0, true);
        check(f.acquire() == null, "recovery rules still apply after an actual failure");
        f.health(0, true);
        check(f.snapshot(0).state().equals("WARMING") && f.snapshot(0).inFlightLimit() == 2,
                "a new recovery restarts slow start");
    }

    private static void staleProbe() {
        Fixture f = new Fixture("backend.servers", "127.0.0.1:9001:8");
        var oldProbe = f.dispatcher.beginProbe(f.backend(0));
        var failed = required(f.acquire());
        failed.complete(1, true, true);
        f.dispatcher.recordHealth(f.backend(0), oldProbe, true);
        f.dispatcher.recordTelemetry(f.backend(0), oldProbe, new BackendMetrics.Telemetry(0, 0, 0, 0));
        check(!f.snapshot(0).alive() && f.acquire() == null, "old successful health must not resurrect a failed backend");
        check(!f.snapshot(0).telemetryFresh() && f.snapshot(0).telemetryAgeMs() == -1,
                "telemetry from before the failure must be discarded");
        f.health(0, true);
        check(f.snapshot(0).alive(), "a new health generation can recover normally");
        f.dispatcher.recordHealth(f.backend(0), oldProbe, false);
        check(f.snapshot(0).alive(), "an old failed probe cannot overwrite the new generation either");
    }

    private static void exploration() {
        Fixture f = new Fixture("adaptive.exploration.interval", "4");
        learnLatencies(f, 200, 20); // Two dispatches; slow A is older than fast B.
        var usual = required(f.acquire());
        equal(f.backend(1), usual.backend(), "ordinary dispatch avoids the learned slow backend");
        usual.complete(20, false, false);
        var exploration = required(f.acquire());
        equal(f.backend(0), exploration.backend(), "fourth dispatch revisits the idle backend despite its old slow score");
        exploration.complete(5, false, false);
        try (var next = required(f.acquire())) {
            equal(f.backend(0), next.backend(), "new real request latency changes the next ordinary decision");
        }
    }

    private static void invalidConfiguration() throws Exception {
        expectInvalid(() -> ConfigLoader.load(properties("backend.servers", "127.0.0.1:9001:1,127.0.0.1:9001:9")), "duplicate address");
        expectInvalid(() -> ConfigLoader.load(properties("adaptive.ewma.alpha", "NaN")), "NaN EWMA coefficient");
        expectInvalid(() -> ConfigLoader.load(properties("adaptive.initial.latency.ms", "Infinity")), "infinite latency prior");
        expectInvalid(() -> ConfigLoader.load(properties("loadbalancer.strategy", "MAGIC")), "unknown strategy");
        expectInvalid(() -> ConfigLoader.load(properties("backend.servers", "127.0.0.1:9001:0")), "zero capacity");
        expectInvalid(() -> ConfigLoader.load(properties("backend.servers", "")), "empty backend list");
        expectInvalid(() -> ConfigLoader.load(properties("healthcheck.interval.seconds", "1")), "obsolete unit must not be silently ignored");
        Properties input = properties();
        ProxyConfig config = ConfigLoader.load(input);
        input.setProperty("proxy.max.inflight", "1");
        check(config.getMaxInflight() == 128, "configuration must not change through its caller's Properties");
        try {
            config.getBackendServers().clear();
            throw new AssertionError("backend list must be immutable");
        } catch (UnsupportedOperationException expected) { }
    }

    private static void telemetryValidation() throws Exception {
        var valid = parse("cpuLoad=0.7\nmemoryLoad=0.3\nactiveRequests=4\nqueuedRequests=2\n");
        near(0.7, valid.cpuLoad(), "CPU ratio preserved");
        check(valid.activeRequests() == 4 && valid.queuedRequests() == 2, "remote counters preserved");
        var missing = parse("activeRequests=0\nqueuedRequests=0\n");
        check(Double.isNaN(missing.cpuLoad()) && Double.isNaN(missing.memoryLoad()), "missing OS metrics remain unknown");
        var unavailable = parse("cpuLoad=-1\nmemoryLoad=NaN\nactiveRequests=0\nqueuedRequests=0\n");
        check(Double.isNaN(unavailable.cpuLoad()) && Double.isNaN(unavailable.memoryLoad()), "unavailable metric conventions");
        for (String invalid : List.of("1.01", "-0.1", "Infinity", "-Infinity", "75", "oops")) {
            expectInvalid(() -> parse("cpuLoad=" + invalid + "\nmemoryLoad=0\nactiveRequests=0\nqueuedRequests=0\n"), "invalid CPU " + invalid);
            expectInvalid(() -> parse("cpuLoad=0\nmemoryLoad=" + invalid + "\nactiveRequests=0\nqueuedRequests=0\n"), "invalid memory " + invalid);
        }
        expectInvalid(() -> parse("activeRequests=-1\nqueuedRequests=0\n"), "negative active count");
        expectInvalid(() -> parse("activeRequests=0\nqueuedRequests=1000001\n"), "unbounded queue count");
        expectInvalid(() -> parse("activeRequests=0\n"), "missing queue counter");
    }

    private static BackendMetrics.Telemetry parse(String text) throws Exception {
        return HealthChecker.parseTelemetry(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void learnLatencies(Fixture f, double firstMs, double secondMs) {
        var first = required(f.acquire());
        var second = required(f.acquire());
        try {
            equal(f.backend(0), first.backend(), "initial equal-score selection");
            equal(f.backend(1), second.backend(), "reservation affects the following decision");
            first.complete(firstMs, false, false);
            second.complete(secondMs, false, false);
        } finally { first.close(); second.close(); }
    }

    private static Properties properties(String... overrides) {
        Properties p = new Properties();
        p.setProperty("backend.servers", "127.0.0.1:9001:8,127.0.0.1:9002:8");
        p.setProperty("adaptive.warmup.ms", "0");
        p.setProperty("healthcheck.recovery.successes", "1");
        p.setProperty("adaptive.ewma.alpha", "1");
        p.setProperty("adaptive.exploration.interval", "1000000");
        for (int i = 0; i < overrides.length; i += 2) p.setProperty(overrides[i], overrides[i + 1]);
        return p;
    }

    private static final class Fixture {
        final AtomicLong clock = new AtomicLong(1_000_000_000L);
        final ProxyConfig config;
        final Dispatcher dispatcher;
        Fixture(String... overrides) { this(true, overrides); }
        Fixture(boolean healthy, String... overrides) {
            config = ConfigLoader.load(properties(overrides));
            dispatcher = new Dispatcher(config, clock::get);
            if (healthy) for (int i = 0; i < config.getBackendServers().size(); i++) {
                for (int j = 0; j < config.getRecoverySuccesses(); j++) health(i, true);
                telemetry(i, 0, 0.2, 0, 0);
            }
        }
        BackendServer backend(int index) { return config.getBackendServers().get(index); }
        BackendMetrics.Snapshot snapshot(int index) { return dispatcher.snapshots().get(index); }
        void advanceMs(long ms) { clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms)); }
        Dispatcher.Lease acquire() { advanceMs(1); return dispatcher.acquire(); }
        void health(int index, boolean healthy) {
            var backend = backend(index);
            dispatcher.recordHealth(backend, dispatcher.beginProbe(backend), healthy);
        }
        void telemetry(int index, double cpu, double memory, int active, int queued) {
            var backend = backend(index);
            dispatcher.recordTelemetry(backend, dispatcher.beginProbe(backend), new BackendMetrics.Telemetry(cpu, memory, active, queued));
        }
    }

    private static Dispatcher.Lease required(Dispatcher.Lease lease) {
        check(lease != null, "expected an available backend");
        return lease;
    }
    private static void await(CountDownLatch latch, String operation) throws InterruptedException {
        check(latch.await(10, TimeUnit.SECONDS), "timeout waiting for " + operation);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        check(expected.equals(actual), message + ": expected " + expected + ", got " + actual);
    }
    private static void near(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < 1e-9, message + ": expected " + expected + ", got " + actual);
    }
    private static void expectInvalid(Action action, String message) throws Exception {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("must reject " + message);
    }
    private static void run(String name, Action action) throws Exception {
        action.run();
        passed++;
        System.out.println("  PASS " + name);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
