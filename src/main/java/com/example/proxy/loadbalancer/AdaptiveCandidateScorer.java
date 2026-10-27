package com.example.proxy.loadbalancer;

import com.example.proxy.metrics.BackendMetrics;

/** Pure arithmetic over a supplied snapshot/forecast. No learning, clock, locks, reservations or I/O. */
final class AdaptiveCandidateScorer {
    private AdaptiveCandidateScorer() { }

    static double score(BackendMetrics.Snapshot snapshot, EstimatedWork.Forecast forecast) {
        return forecast.completionMs() * resourcePenalty(snapshot);
    }
    static double resourcePenalty(BackendMetrics.Snapshot s) {
        // Preserve the original expression and floating-point order, including all existing weights.
        return s.scoreMs() / (s.latencyEwmaMs() * (1 + (s.inFlight() + external(s) + 1.0) / s.effectiveCapacity()));
    }
    static int external(BackendMetrics.Snapshot s) {
        return s.telemetryFresh() ? s.externalOutstanding() : 0;
    }
}
