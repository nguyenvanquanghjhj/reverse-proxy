package com.example.proxy.loadbalancer;

import java.net.URI;

/** Deterministic service/queue estimates with an injected monotonic time; no sleeping. */
public final class EstimatedWorkTest {
    private static final long NOW = 1_000_000_000L;
    public static void runAll() {
        var work = new EstimatedWork(50);
        var light = request("/work?cost=1");
        var slow = request("/slow?cost=20");
        var cpu = request("/compute?cost=8");
        learn(work, light, 20, 1);
        learn(work, slow, 2000, 1);
        learn(work, cpu, 80, 1);
        near(20, cost(work, light), "light isolated from slow");
        near(2000, cost(work, slow), "slow route ignores unused cost metadata");
        near(40, cost(work, request("/compute?cost=4")), "CPU cost normalized per unit");
        learn(work, light, 40, .2);
        near(24, cost(work, light), "EWMA uses configured alpha");
        near(50, cost(work, request("/unknown/123")), "bounded OTHER bucket starts from common prior");
        check(request("/work?cost=999999").units() == 1, "untrusted metadata bounded");
        check(request("/work?cost=%38").units() == 8, "existing decoded cost semantics");

        var business = new EstimatedWork(50);
        String[] routes = {"/products", "/orders", "/download?sizeKiB=64", "/download?sizeKiB=256", "/download?sizeKiB=768"};
        double[] times = {15, 120, 25, 60, 110};
        for (int i = 0; i < routes.length; i++) learn(business, request(routes[i]), times[i], 1);
        for (int i = 0; i < routes.length; i++) near(times[i], cost(business, request(routes[i])), "business request classes learn independently: " + routes[i]);
        near(60, cost(business, request("/download")), "default file size shares 256 KiB estimate");
        near(110, cost(business, request("/download?sizeKiB=%37%36%38")), "decoded file size matches backend semantics");
        near(50, cost(business, light), "business traffic does not train benchmark routes");

        var a = work.reserve(slow, NOW, work.forecast(slow, NOW, 1, 0), 1, 0);
        near(2000, work.forecast(light, NOW, 1, 0).waitMs(), "slow reservation consumes work immediately");
        var b = work.reserve(light, NOW, work.forecast(light, NOW, 1, 0), 1, 0);
        var halfway = work.forecast(light, NOW + 1_000_000_000L, 1, 0);
        near(1024, halfway.outstandingMs(), "running work ages but queued work does not");
        near(24, halfway.queuedMs(), "queued service stays separate");
        check(work.forecast(light, NOW + 10_000_000_000L, 1, 0).waitMs() >= 50,
                "overdue live job still occupies a slot");
        work.complete(a, 2100, false, .2);
        work.complete(b, 2400, false, .2);
        near(24, cost(work, light), "queued RTT must not inflate learned service time");
        near(0, work.forecast(light, NOW, 1, 0).outstandingMs(), "completion removes all reserved work");

        learn(work, light, 1, 1, true);
        near(24, cost(work, light), "fast failures cannot train service cost");
        var old = work.reserve(slow, NOW, work.forecast(slow, NOW, 1, 0), 1, 0);
        work.generation(1);
        check(work.forecast(light, NOW, 1, 0).outstandingMs() >= 2000, "recovery retains old live reservations");
        work.complete(old, 10000, false, 1);
        near(50, cost(work, slow), "old-generation completion cannot train recovered server");
        near(0, work.forecast(light, NOW, 1, 0).outstandingMs(), "old lease still releases exactly its work");
        check(work.forecast(light, NOW, 1, 3).waitMs() > 0, "external work contributes to waiting");

        var parallel = new EstimatedWork(50);
        var first = parallel.reserve(light, NOW, parallel.forecast(light, NOW, 4, 0), 4, 0);
        near(0, parallel.forecast(light, NOW, 4, 0).waitMs(), "unused parallel slot can start now");
        parallel.release(first);
        near(0, parallel.forecast(light, NOW, 4, 0).outstandingMs(), "cancelled reservation releases work");
    }
    private static EstimatedWork.Request request(String uri) { return EstimatedWork.Request.of(URI.create(uri)); }
    private static double cost(EstimatedWork work, EstimatedWork.Request request) { return work.forecast(request, NOW, 1, 0).serviceMs(); }
    private static void learn(EstimatedWork work, EstimatedWork.Request request, double ms, double alpha) {
        learn(work, request, ms, alpha, false);
    }
    private static void learn(EstimatedWork work, EstimatedWork.Request request, double ms, double alpha, boolean error) {
        var job = work.reserve(request, NOW, work.forecast(request, NOW, 1, 0), 1, 0);
        work.complete(job, ms, error, alpha);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void near(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < 1e-8, message + ": expected " + expected + ", actual " + actual);
    }
}
