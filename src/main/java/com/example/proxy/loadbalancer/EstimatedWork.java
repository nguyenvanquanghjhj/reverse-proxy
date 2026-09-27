package com.example.proxy.loadbalancer;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.PriorityQueue;

/** Bounded route learning and a virtual FIFO server. Access only under Dispatcher's lock. */
final class EstimatedWork {
    enum Route { LIGHT, SLOW, CPU, OTHER }
    record Request(Route route, int units) {
        static Request of(URI uri) {
            String path = uri.getPath();
            Route route = switch (path == null ? "" : path) {
                case "/work" -> Route.LIGHT;
                case "/slow" -> Route.SLOW;
                case "/compute" -> Route.CPU;
                default -> Route.OTHER;
            };
            int units = 1;
            // Match the demo's bounded cost metadata, never key a map by unbounded URLs.
            if ((route == Route.LIGHT || route == Route.CPU) && uri.getRawQuery() != null) {
                try {
                    for (String part : uri.getRawQuery().split("&")) {
                        String[] pair = part.split("=", 2);
                        if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals("cost"))
                            units = pair.length == 2 ? Integer.parseInt(URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) : 1;
                    }
                } catch (IllegalArgumentException invalid) { units = 1; }
            }
            return new Request(route, units >= 1 && units <= 20 ? units : 1);
        }
    }
    record Forecast(double serviceMs, double waitMs, double completionMs, double outstandingMs, double queuedMs) { }
    static final class Reservation {
        final Request request;
        final long generation, admittedNanos;
        final double serviceMs, startDelayMs;
        final boolean learn;
        Reservation(Request request, long generation, long now, Forecast forecast, boolean learn) {
            this.request = request; this.generation = generation; this.admittedNanos = now;
            serviceMs = forecast.serviceMs(); startDelayMs = forecast.waitMs(); this.learn = learn;
        }
    }
    private final EnumMap<Route, Double> servicePerUnit = new EnumMap<>(Route.class);
    private final List<Reservation> outstanding = new ArrayList<>();
    private final double initialMs;
    private long generation;
    private double genericMs;
    EstimatedWork(double initialMs) { this.initialMs = initialMs; genericMs = initialMs; }
    void generation(long current) {
        if (generation != current) {
            generation = current;
            servicePerUnit.clear();
            genericMs = initialMs;
            // A health transition must not discard work still owned by live leases.
        }
    }
    private double service(Request request) {
        return servicePerUnit.getOrDefault(request.route(), initialMs) * request.units();
    }
    Forecast forecast(Request request, long now, double capacity, int external) {
        int slots = Math.max(1, (int) Math.floor(capacity));
        // No need to allocate unused lanes for a very large configured capacity.
        int lanes = Math.min(slots, outstanding.size() + 1);
        PriorityQueue<Double> available = new PriorityQueue<>();
        for (int i = 0; i < lanes; i++) available.add(0.0);
        double work = 0, queued = 0;
        int position = 0;
        for (Reservation job : outstanding) {
            double start = available.remove();
            double duration = Math.max(job.serviceMs, service(job.request));
            if (position++ < slots) {
                double elapsed = Math.max(0, (now - job.admittedNanos) / 1_000_000.0 - job.startDelayMs);
                // An overdue live lease still occupies a slot; never declare it finished by a clock prediction.
                duration = Math.max(Math.min(initialMs, duration), duration - elapsed);
            } else queued += duration;
            work += duration;
            available.add(start + duration);
        }
        double externalWork = external * genericMs;
        double wait = available.element() + externalWork / slots;
        double cost = service(request);
        return new Forecast(cost, wait, wait + cost, work + externalWork, queued + externalWork);
    }
    Reservation reserve(Request request, long now, Forecast forecast, int slots, int external) {
        var job = new Reservation(request, generation, now, forecast, outstanding.size() < slots && external == 0);
        outstanding.add(job);
        return job;
    }
    void release(Reservation job) {
        if (!outstanding.remove(job)) throw new IllegalStateException("Unknown work reservation");
    }
    void complete(Reservation job, double elapsedMs, boolean error, double alpha) {
        release(job);
        // RTT of a queued request is NOT service time. Only learn from reservations with a free slot.
        // Old-generation completions and failures cannot train a recovered backend.
        if (!error && job.learn && job.generation == generation && Double.isFinite(elapsedMs) && elapsedMs >= 0) {
            double sample = Math.max(0.1, elapsedMs);
            servicePerUnit.merge(job.request.route(), sample / job.request.units(), (old, next) -> old + alpha * (next - old));
            genericMs += alpha * (sample - genericMs);
        }
    }
}
