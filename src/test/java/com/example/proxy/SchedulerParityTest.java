package com.example.proxy;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.loadbalancer.Dispatcher;
import com.example.proxy.metrics.BackendMetrics;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Golden scheduler transcript captured on the unmodified 6550f5c baseline. */
public final class SchedulerParityTest {
    public static void main(String[] args) throws Exception {
        StringBuilder golden = new StringBuilder();
        for (String strategy : List.of("ROUND_ROBIN", "LEAST_CONNECTIONS", "ADAPTIVE")) {
            Properties p = new Properties();
            p.setProperty("loadbalancer.strategy", strategy);
            p.setProperty("adaptive.decision-trace.enabled", "true");
            p.setProperty("adaptive.exploration.interval", "7");
            p.setProperty("healthcheck.recovery.successes", "2");
            p.setProperty("proxy.circuit-breaker.failure-threshold", "2");
            p.setProperty("proxy.circuit-breaker.open-duration.ms", "100");
            p.setProperty("adaptive.warmup.ms", "250");
            p.setProperty("proxy.max.inflight", "10");
            p.setProperty("backend.max.inflight", "6");
            var backends = List.of(new BackendServer("localhost", 9001, 1),
                    new BackendServer("localhost", 9002, 2), new BackendServer("localhost", 9003, 4));
            var clock = new AtomicLong(10_000_000_000L);
            var d = new Dispatcher(new ProxyConfig(p, backends), clock::get);
            var random = new Random(20261026);
            var held = new ArrayList<Dispatcher.Lease>();
            for (int i = 0; i < 900; i++) {
                clock.addAndGet((1 + random.nextInt(100)) * 1_000_000L);
                var backend = backends.get(random.nextInt(3));
                switch (random.nextInt(7)) {
                    case 0, 1 -> {
                        var lease = d.acquire(URI.create(List.of("/work?cost=2", "/slow", "/compute?cost=8", "/download?sizeKiB=768").get(random.nextInt(4))));
                        golden.append(lease == null ? "rejected" : lease.backend().getId()).append('\n');
                        if (lease != null) held.add(lease);
                    }
                    case 2 -> d.recordHealth(backend, d.beginProbe(backend), random.nextInt(5) != 0);
                    case 3 -> d.recordTelemetry(backend, d.beginProbe(backend), new BackendMetrics.Telemetry(random.nextDouble(), random.nextDouble(), random.nextInt(4), random.nextInt(3)));
                    default -> {
                        if (!held.isEmpty()) {
                            var lease = held.remove(random.nextInt(held.size()));
                            if (random.nextBoolean()) lease.complete(10 + random.nextInt(5000), random.nextInt(5) == 0, random.nextInt(10) == 0);
                            lease.close(); lease.close();
                        }
                    }
                }
                golden.append(d.snapshotJson()).append('\n').append(d.decisionJson()).append('\n').append(d.prometheus());
            }
            held.forEach(Dispatcher.Lease::close);
            d.stopAccepting(); d.acquire();
            golden.append(d.snapshotJson()).append('\n').append(d.decisionJson()).append('\n');
        }
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(golden.toString().getBytes(StandardCharsets.UTF_8)));
        if (!digest.equals("f1b93fdb49772e638afad2906c6cb21383a39d3bc0fa7925dc7ee192c5dbbcfe"))
            throw new AssertionError("Scheduler baseline changed: " + digest);
        System.out.println("SchedulerParityTest PASS: 2700 deterministic states match 6550f5c baseline");
    }
}
