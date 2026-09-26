package com.example.proxy.loadbalancer;
import com.example.proxy.metrics.BackendMetrics;
import java.util.Comparator;
import java.util.List;

/** One HTTP exchange per upstream socket: leases approximate busy connections. */
public final class LeastConnectionsLoadBalancer implements LoadBalancer {
    public BackendMetrics.Snapshot select(List<BackendMetrics.Snapshot> candidates) {
        return candidates.stream().min(Comparator.comparingInt(BackendMetrics.Snapshot::inFlight)
                .thenComparingLong(BackendMetrics.Snapshot::lastSelectedNanos)).orElse(null);
    }
    public String getName() { return "LEAST_CONNECTIONS"; }
}
