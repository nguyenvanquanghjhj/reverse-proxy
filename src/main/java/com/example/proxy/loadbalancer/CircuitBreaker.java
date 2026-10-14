package com.example.proxy.loadbalancer;

import com.example.proxy.config.ProxyConfig;

/** All access is under Dispatcher.lock; generations isolate late completions from older circuits. */
final class CircuitBreaker {
    enum State { CLOSED, OPEN, HALF_OPEN }
    private State state = State.CLOSED;
    private final boolean enabled;
    private final int threshold;
    private final long cooldownNanos;
    private long generation, openedAt, openedCount;
    private int failures;
    private boolean probing;

    CircuitBreaker(ProxyConfig config) {
        enabled = config.isCircuitBreakerEnabled();
        threshold = config.getCircuitFailureThreshold();
        cooldownNanos = config.getCircuitOpenDurationMs() * 1_000_000L;
    }
    boolean eligible(long now) {
        advance(now);
        return !enabled || state == State.CLOSED || state == State.HALF_OPEN && !probing;
    }
    long reserve(long now) {
        if (!eligible(now)) throw new IllegalStateException("Circuit is not eligible");
        if (enabled && state == State.HALF_OPEN) probing = true;
        return generation;
    }
    void complete(long ticket, boolean failed, long now) {
        if (!enabled || ticket != generation || state == State.OPEN) return;
        if (state == State.HALF_OPEN) {
            probing = false;
            if (failed) open(now);
            else { state = State.CLOSED; failures = 0; generation++; }
        } else if (failed) {
            if (++failures >= threshold) open(now);
        } else failures = 0;
    }
    void cancel(long ticket, long now) {
        // Cancellation is not a backend failure, but does not establish recovery either.
        if (enabled && ticket == generation && state == State.HALF_OPEN) {
            probing = false;
            generation++;
        }
    }
    private void open(long now) {
        state = State.OPEN; openedAt = now; generation++; probing = false; openedCount++;
    }
    private void advance(long now) {
        if (enabled && state == State.OPEN && now - openedAt >= cooldownNanos) {
            state = State.HALF_OPEN; generation++; probing = false;
        }
    }
    String json(long now) {
        advance(now);
        return "\"circuitState\":\"" + state + "\",\"circuitFailures\":" + failures
                + ",\"circuitOpenCount\":" + openedCount + ",\"circuitProbeInFlight\":" + probing;
    }
    int stateCode(long now) { advance(now); return state.ordinal(); }
    long openedCount() { return openedCount; }
    int failures() { return failures; }
    boolean probing() { return probing; }
}
