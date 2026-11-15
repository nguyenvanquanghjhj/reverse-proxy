package com.example.proxy;

import com.example.proxy.loadbalancer.CapabilityRouting;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** /ready must fail closed on malformed JSON, duplicate keys, missing capabilities, stale polls. */
public final class CapabilityJsonStrictTest {
    private static CapabilityRouting.Status parse(String s) {
        return CapabilityRouting.parse(s.getBytes(StandardCharsets.UTF_8));
    }
    private static void assertIt(boolean b, String message) {
        if (!b) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        String correct="{\"status\":\"DEGRADED\",\"dependencies\":{\"capabilities\":{\"catalog\":\"AVAILABLE\",\"order_create\":\"UNAVAILABLE\",\"static_files\":\"AVAILABLE\"}}}";
        long now=-80_000_000_000L; // System.nanoTime may be negative.
        var state=parse(correct).withObservedAt(now);
        assertIt(state.supports("catalog",now,1500),"negative monotonic clock must be supported");
        assertIt(!state.supports("order_create",now,1500),"DB-down node must not take orders");
        assertIt(!state.supports("catalog",now+1_600_000_000L,1500),"stale status must fail closed");
        assertIt(!parse("INVALID "+correct).withObservedAt(100).supports("catalog",100,1000),"garbage prefix");
        assertIt(!parse(correct+"TRAILING").withObservedAt(100).supports("catalog",100,1000),"garbage suffix");
        assertIt(!parse("{\"dependencies\":{\"capabilities\":{\"catalog\":\"AVAILABLE\",\"catalog\":\"AVAILABLE\",\"order_create\":\"AVAILABLE\"}}}")
                .withObservedAt(100).supports("order_create",100,1000),"duplicate key");
        assertIt(!parse("{\"capabilities\":{\"catalog\":\"AVAILABLE\",\"order_create\":\"AVAILABLE\"}}")
                .withObservedAt(100).supports("catalog",100,1000),"wrong JSON nesting");
        assertIt("order_create".equals(CapabilityRouting.required("POST",URI.create("/api/store/orders"))),"route requirement");
        System.out.println("CapabilityJsonStrictTest PASS");
    }
}
