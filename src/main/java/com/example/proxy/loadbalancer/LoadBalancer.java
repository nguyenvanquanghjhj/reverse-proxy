package com.example.proxy.loadbalancer;
import com.example.proxy.metrics.BackendMetrics;
import java.util.List;

/** Invoked only under the dispatcher's lock, with healthy, admissible candidates. */
public interface LoadBalancer {
    BackendMetrics.Snapshot select(List<BackendMetrics.Snapshot> candidates);
    String getName();
}
