package com.example.proxy.loadbalancer;
import java.util.Locale;

public final class LoadBalancerFactory {
    private LoadBalancerFactory() { }
    public static LoadBalancer create(String name, int explorationInterval) {
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "ADAPTIVE" -> new AdaptiveLoadBalancer(explorationInterval);
            case "ROUND_ROBIN" -> new RoundRobinLoadBalancer();
            case "LEAST_CONNECTIONS" -> new LeastConnectionsLoadBalancer();
            default -> throw new IllegalArgumentException("Unsupported strategy: " + name);
        };
    }
}
