package com.example.proxy.loadbalancer;
import com.example.proxy.metrics.BackendMetrics;
import java.util.List;

public final class RoundRobinLoadBalancer implements LoadBalancer {
    private long sequence;
    public BackendMetrics.Snapshot select(List<BackendMetrics.Snapshot> candidates) {
        return candidates.isEmpty() ? null : candidates.get((int) Math.floorMod(sequence++, (long) candidates.size()));
    }
    public String getName() { return "ROUND_ROBIN"; }
}
