package com.example.proxy.loadbalancer;
import com.example.proxy.metrics.BackendMetrics;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Estimated completion cost, with bounded exploration of previously slow backends. */
public final class AdaptiveLoadBalancer implements LoadBalancer {
    private final int explorationInterval;
    private long dispatches;
    public AdaptiveLoadBalancer(int explorationInterval) { this.explorationInterval = explorationInterval; }
    public BackendMetrics.Snapshot select(List<BackendMetrics.Snapshot> candidates) {
        return select(candidates, BackendMetrics.Snapshot::scoreMs);
    }
    public BackendMetrics.Snapshot select(List<BackendMetrics.Snapshot> candidates,
                                          ToDoubleFunction<BackendMetrics.Snapshot> completionScore) {
        if (candidates.isEmpty()) return null;
        Comparator<BackendMetrics.Snapshot> ordering = ++dispatches % explorationInterval == 0
                ? Comparator.comparingLong(BackendMetrics.Snapshot::lastSelectedNanos)
                : Comparator.comparingDouble(completionScore)
                    .thenComparingLong(BackendMetrics.Snapshot::lastSelectedNanos);
        return candidates.stream().min(ordering).orElse(null);
    }
    public String getName() { return "ADAPTIVE"; }
}
