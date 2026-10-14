package com.example.proxy;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.loadbalancer.Dispatcher;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class CircuitBreakerTest {
    public static void main(String[] args) throws Exception {
        for (String strategy : List.of("ROUND_ROBIN", "LEAST_CONNECTIONS", "ADAPTIVE")) {
            Fixture f = new Fixture(strategy, true);
            var old = f.lease();
            f.lease().complete(10, true, false);
            f.lease().complete(10, false, false); // Non-backend failure resets consecutive failures.
            f.lease().complete(10, true, false);
            check(f.dispatcher.hasAvailableBackend(), "below threshold");
            f.lease().complete(10, true, false);
            check(f.state("OPEN") && f.dispatcher.acquire() == null, "threshold opens " + strategy);
            f.healthy(); check(f.state("OPEN"), "health probe cannot bypass cooldown");
            f.clock.addAndGet(99_000_000); check(f.dispatcher.acquire() == null, "cooldown enforced");
            f.clock.addAndGet(1_000_000);
            List<Dispatcher.Lease> probes = new ArrayList<>();
            try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Dispatcher.Lease>> attempts = new ArrayList<>();
                for (int i = 0; i < 64; i++) attempts.add(tasks.submit(() -> f.dispatcher.acquire()));
                for (var attempt : attempts) { var lease = attempt.get(2,TimeUnit.SECONDS); if (lease != null) probes.add(lease); }
            }
            check(probes.size() == 1 && f.state("HALF_OPEN"), "single atomic probe " + strategy);
            old.complete(10, false, false);
            check(f.state("HALF_OPEN") && !f.dispatcher.hasAvailableBackend(), "late success cannot release probe");
            probes.getFirst().complete(10, true, false);
            check(f.state("OPEN"), "failed probe reopens");
            f.clock.addAndGet(100_000_000);
            var cancelled = f.lease(); cancelled.close(); cancelled.close();
            check(f.state("HALF_OPEN"), "cancelled probe returns slot without claiming failure or recovery");
            var limited = f.lease(); limited.completePolicyRejection(10); limited.close();
            check(f.state("HALF_OPEN") && f.dispatcher.hasAvailableBackend(), "policy rejection returns probe without changing state");
            var recovered = f.lease(); recovered.complete(10, false, false); recovered.close();
            check(f.state("CLOSED") && f.dispatcher.hasAvailableBackend(), "successful probe closes");
            check(f.dispatcher.snapshots().getFirst().inFlight() == 0, "all leases released");
            check(f.dispatcher.prometheus().contains("proxy_backend_circuit_state{backend="), "circuit metrics");
            // Transport failures retain health semantics: cooldown alone cannot resurrect DOWN.
            f.lease().complete(10, true, true); check(f.dispatcher.acquire() == null, "transport failure marks DOWN");
            f.healthy(); f.lease().complete(10, true, true); f.clock.addAndGet(100_000_000);
            check(f.dispatcher.acquire() == null, "HALF_OPEN still requires healthy backend");
            f.healthy(); f.lease().complete(10,false,false);
            f.dispatcher.stopAccepting(); check(f.dispatcher.acquire() == null, "draining never probes");
        }
        Fixture disabled = new Fixture("ROUND_ROBIN",false);
        for(int i=0;i<10;i++) disabled.lease().complete(10,true,false);
        check(disabled.state("CLOSED") && disabled.dispatcher.hasAvailableBackend(), "disabled circuit compatibility");
        for (String strategy : List.of("ROUND_ROBIN", "LEAST_CONNECTIONS", "ADAPTIVE")) {
            Properties p = new Properties();
            p.setProperty("loadbalancer.strategy", strategy); p.setProperty("healthcheck.recovery.successes", "1");
            p.setProperty("adaptive.warmup.ms", "0"); p.setProperty("proxy.circuit-breaker.failure-threshold", "1");
            var backends = List.of(new BackendServer("localhost", 9001, 8), new BackendServer("localhost", 9002, 8));
            var dispatcher = new Dispatcher(new ProxyConfig(p, backends));
            backends.forEach(b -> dispatcher.recordHealth(b, dispatcher.beginProbe(b), true));
            var broken = dispatcher.acquire(); check(broken != null, "initial lease");
            broken.complete(1, true, false);
            for (int i = 0; i < 10; i++) {
                var fallback = dispatcher.acquire();
                check(fallback != null && !fallback.backend().equals(broken.backend()), "open backend skipped " + strategy);
                fallback.complete(1, false, false);
            }
            check(dispatcher.snapshots().stream().allMatch(s -> s.inFlight() == 0), "fallback releases leases");
        }
        for(var entry:Map.of("proxy.circuit-breaker.enabled","maybe","proxy.circuit-breaker.failure-threshold","0",
                "proxy.circuit-breaker.open-duration.ms","-1","proxy.circuit-breaker.half-open-max-probes","2").entrySet()) {
            Properties p=new Properties();p.setProperty(entry.getKey(),entry.getValue());
            try { new ProxyConfig(p,List.of(new BackendServer("localhost",9001,1)));throw new AssertionError("invalid config accepted"); }
            catch(IllegalArgumentException expected) { check(expected.getMessage().contains(entry.getKey()),"key in config error"); }
        }
        System.out.println("CircuitBreakerTest PASS: all strategies, concurrency, cooldown, generations, cancellation, health, config");
    }
    private static final class Fixture {
        final AtomicLong clock=new AtomicLong(1_000_000_000L);
        final BackendServer backend=new BackendServer("localhost",9001,8);
        final Dispatcher dispatcher;
        Fixture(String strategy, boolean enabled) {
            Properties p=new Properties();p.setProperty("loadbalancer.strategy",strategy);p.setProperty("healthcheck.recovery.successes","1");
            p.setProperty("adaptive.warmup.ms","0");p.setProperty("proxy.circuit-breaker.failure-threshold","2");
            p.setProperty("proxy.circuit-breaker.open-duration.ms","100");p.setProperty("proxy.circuit-breaker.enabled",Boolean.toString(enabled));
            dispatcher=new Dispatcher(new ProxyConfig(p,List.of(backend)),clock::get);healthy();
        }
        void healthy() { dispatcher.recordHealth(backend,dispatcher.beginProbe(backend),true); }
        Dispatcher.Lease lease() { var lease=dispatcher.acquire();check(lease!=null,"lease expected");return lease; }
        boolean state(String name) { return dispatcher.snapshotJson().contains("\"circuitState\":\""+name+"\""); }
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
