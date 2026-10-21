package com.example.proxy.loadbalancer;

import com.example.proxy.util.Json;
import java.util.List;

/** One bounded, immutable pre-reservation explanation; no client identifiers or raw URLs. */
record AdaptiveDecision(long sequence, String route, int units, double budgetMs,
        String reason, String selectedBackend, List<Candidate> candidates) {
    AdaptiveDecision { candidates = List.copyOf(candidates); }
    record Candidate(String backend, String eligibility, int inFlight, double capacity,
            Double serviceMs, Double waitMs, Double outstandingMs, Double penalty, Double score) {
        String json() {
            return "{\"backend\":" + Json.quote(backend) + ",\"eligibility\":" + Json.quote(eligibility)
                    + ",\"inFlight\":" + inFlight + ",\"effectiveCapacity\":" + Json.number(capacity)
                    + ",\"serviceMs\":" + number(serviceMs) + ",\"waitMs\":" + number(waitMs)
                    + ",\"completionMs\":" + number(serviceMs == null ? null : serviceMs + waitMs)
                    + ",\"outstandingMs\":" + number(outstandingMs) + ",\"resourcePenalty\":" + number(penalty)
                    + ",\"rankingScoreMs\":" + number(score) + "}";
        }
    }
    private static String number(Double value) { return value == null ? "null" : Json.number(value); }
    String json() {
        return "{\"sequence\":" + sequence + ",\"route\":" + Json.quote(route) + ",\"units\":" + units
                + ",\"budgetMs\":" + Json.number(budgetMs) + ",\"reason\":" + Json.quote(reason)
                + ",\"selectedBackend\":" + (selectedBackend == null ? "null" : Json.quote(selectedBackend))
                + ",\"candidates\":[" + String.join(",", candidates.stream().map(Candidate::json).toList()) + "]}";
    }
}
