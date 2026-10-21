package com.example.proxy;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.loadbalancer.Dispatcher;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Explanations must describe actual choices without changing routing or exposing raw URLs. */
public final class AdaptiveDecisionTest {
    public static void main(String[] args) throws Exception {
        var backends = List.of(new BackendServer("localhost",9001,1), new BackendServer("localhost",9002,1));
        Properties p = new Properties(); p.setProperty("adaptive.decision-trace.enabled","true");
        p.setProperty("healthcheck.recovery.successes","1"); p.setProperty("adaptive.warmup.ms","0");
        p.setProperty("adaptive.exploration.interval","2");
        var clock = new AtomicLong(10_000_000_000L);
        var dispatcher = new Dispatcher(new ProxyConfig(p,backends),clock::get);
        dispatcher.recordHealth(backends.getFirst(),dispatcher.beginProbe(backends.getFirst()),true);
        try (var chosen = dispatcher.acquire(URI.create("/work?cost=2&secret=do-not-store"))) {
            check(chosen != null && chosen.backend().equals(backends.getFirst()),"actual selected backend");
            String trace = dispatcher.decisionJson();
            check(trace.contains("MINIMUM_SCORE") && trace.contains("DOWN") && trace.contains("\"units\":2"),"score and exclusions explained");
            check(trace.contains("\"inFlight\":0") && !trace.contains("secret") && !trace.contains("do-not-store"),"pre-reservation bounded metadata");
            check(dispatcher.decisionJson().equals(trace),"read does not dispatch or overwrite explanation");
            chosen.complete(10,false,false);
        }
        dispatcher.recordHealth(backends.get(1),dispatcher.beginProbe(backends.get(1)),true);
        try (var exploring = dispatcher.acquire(URI.create("/work"))) {
            check(exploring != null && exploring.backend().equals(backends.get(1)),"actual exploration chooses least recently used");
            check(dispatcher.decisionJson().contains("EXPLORATION"),"exploration not falsely explained as minimum score");
            Files.createDirectories(Path.of("target/test-reports"));
            Files.writeString(Path.of("target/test-reports/adaptive-decision.json"),dispatcher.decisionJson());
        }
        // Train an idle slow route above its admission budget, then explain rejection and relearning.
        Properties one = new Properties(); one.putAll(p); one.setProperty("adaptive.exploration.interval","50");
        var single = new Dispatcher(new ProxyConfig(one,List.of(backends.getFirst())),clock::get);
        single.recordHealth(backends.getFirst(),single.beginProbe(backends.getFirst()),true);
        var training = single.acquire(URI.create("/slow")); check(training != null,"training lease"); training.complete(4000,false,false);
        check(single.acquire(URI.create("/slow")) == null,"work admission unchanged");
        check(single.decisionJson().contains("WORK_BUDGET") && single.decisionJson().contains("NO_ELIGIBLE_BACKEND"),"rejection explained");
        clock.addAndGet(5_000_000_001L);
        try (var probe = single.acquire(URI.create("/slow"))) {
            check(probe != null && single.decisionJson().contains("RELEARNING_PROBE"),"idle relearning exception explained");
        }
        single.stopAccepting(); check(single.acquire() == null && single.decisionJson().contains("DRAINING"),"draining explained");
        p.setProperty("adaptive.decision-trace.enabled","false");
        var disabled = new Dispatcher(new ProxyConfig(p,backends)); disabled.acquire();
        check(disabled.decisionJson().equals("{\"enabled\":false,\"lastDecision\":null}"),"disabled retains no explanation");
        // With identical clocks/workloads, tracing must not alter routing or accounting.
        Properties on = new Properties(); on.putAll(p); on.setProperty("adaptive.decision-trace.enabled","true");
        var traced = new Dispatcher(new ProxyConfig(on,backends),clock::get);
        var plain = new Dispatcher(new ProxyConfig(p,backends),clock::get);
        for (var d : List.of(traced,plain))
            backends.forEach(b->d.recordHealth(b,d.beginProbe(b),true));
        for (int i=0;i<20;i++) {
            URI route=URI.create(i%3==0 ? "/slow" : "/work?cost=2");
            try(var a=traced.acquire(route);var b=plain.acquire(route)) {
                check(a!=null && b!=null && a.backend().equals(b.backend()),"trace preserves actual selection");
                a.complete(20+i,false,false); b.complete(20+i,false,false);
            }
            clock.addAndGet(1_000_000);
            check(traced.snapshotJson().equals(plain.snapshotJson()),"trace preserves scheduler state");
        }
        p.setProperty("adaptive.decision-trace.enabled","invalid");
        try { new ProxyConfig(p,backends); throw new AssertionError("invalid flag accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("adaptive.decision-trace.enabled"),"validation key"); }
        check(dispatcher.snapshots().stream().allMatch(s->s.inFlight()==0),"no trace lease leaks");
        // A healthy backend may still be excluded by the circuit or its lease limit.
        Properties protection = new Properties(); protection.putAll(on);
        protection.setProperty("proxy.circuit-breaker.failure-threshold","1");
        protection.setProperty("backend.max.inflight","1");
        var guarded = new Dispatcher(new ProxyConfig(protection,List.of(backends.getFirst())),clock::get);
        guarded.recordHealth(backends.getFirst(),guarded.beginProbe(backends.getFirst()),true);
        var held = guarded.acquire(); check(held != null,"guarded lease");
        check(guarded.acquire() == null && guarded.decisionJson().contains("INFLIGHT_LIMIT"),"lease exclusion explained");
        held.complete(10,true,false);
        check(guarded.acquire() == null && guarded.decisionJson().contains("CIRCUIT_UNAVAILABLE"),"healthy but open circuit explained");
        check(guarded.snapshots().getFirst().inFlight()==0,"excluded requests reserve nothing");
        Properties rankedSettings = new Properties(); rankedSettings.putAll(on);
        rankedSettings.setProperty("adaptive.exploration.interval","50");
        var ranked = new Dispatcher(new ProxyConfig(rankedSettings,backends),clock::get);
        backends.forEach(b->ranked.recordHealth(b,ranked.beginProbe(b),true));
        var slower = ranked.acquire(URI.create("/work"));
        check(slower.backend().equals(backends.getFirst()),"deterministic equal-prior tie"); slower.complete(500,false,false);
        try (var minimum = ranked.acquire(URI.create("/work"))) {
            check(minimum.backend().equals(backends.get(1)),"minimum chosen among two healthy candidates with unequal learned service");
            check(ranked.decisionJson().contains("MINIMUM_SCORE") && ranked.decisionJson().contains("\"serviceMs\":500"),"minimum trace retains higher-scoring eligible alternative");
        }
        System.out.println("AdaptiveDecisionTest PASS: scores, exclusions, exploration, admission, relearning, privacy, disabled mode");
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
