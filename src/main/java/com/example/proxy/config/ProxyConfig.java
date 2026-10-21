package com.example.proxy.config;

import com.example.proxy.backend.BackendServer;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Validated immutable settings; telemetry values are ratios, not percentages. */
public final class ProxyConfig { 
    private final Properties values = new Properties();
    private final List<BackendServer> backends;
    public ProxyConfig(Properties properties, List<BackendServer> backends) {
        values.putAll(properties);
        this.backends = List.copyOf(backends);
        if (backends.isEmpty()) throw new IllegalArgumentException("backend.servers is empty");
        if (backends.stream().map(BackendServer::getId).distinct().count() != backends.size())
            throw new IllegalArgumentException("Duplicate backend address");
        getProxyPort(); getConnectTimeoutMs(); getReadTimeoutMs(); getRequestTimeoutMs();
        getMaxBodyBytes(); getMaxHeaderBytes(); getMaxInflight(); getHealthCheckIntervalMs();
        getHealthCheckTimeoutMs(); getMetricsStaleMs(); getWarmupMs(); getBackendMaxInflight();
        getRecoverySuccesses(); getEwmaAlpha(); getInitialLatencyMs(); getExplorationInterval();
        getHealthCheckPath(); getMetricsPath(); getMaxHeaderCount(); getShutdownGraceSeconds(); getMaxHandlerTasks();
        isCircuitBreakerEnabled(); getCircuitFailureThreshold(); getCircuitOpenDurationMs(); getCircuitHalfOpenMaxProbes();
        isDecisionTraceEnabled();
        isManagementEnabled(); getManagementPort(); getManagementMaxTasks();
        if (bool("proxy.connection-pool.enabled", false))
            throw new IllegalArgumentException("proxy.connection-pool.enabled=true is not supported; pooling is not implemented");
        isRateLimitEnabled(); getRateLimitPerSecond(); getRateLimitBurst(); getRateLimitMaxClients(); getRateLimitIdleMs();
        if (getRateLimitIdleMs() < 1000.0 * getRateLimitBurst() / getRateLimitPerSecond())
            throw new IllegalArgumentException("proxy.rate-limit.idle.ms must cover a full burst refill");
        String bind = getBindHost();
        if (!BackendServer.validHost(bind)) {
            // URI validates a bracketed IPv6 literal without DNS/network I/O.
            if (!bind.contains(":") || java.net.URI.create("http://[" + bind + "]/").getHost() == null)
                throw new IllegalArgumentException("Invalid proxy.bind.host");
        }
        if (!List.of("ADAPTIVE", "ROUND_ROBIN", "LEAST_CONNECTIONS").contains(getLoadBalancerStrategy()))
            throw new IllegalArgumentException("Unknown loadbalancer.strategy: " + getLoadBalancerStrategy());
        if (values.containsKey("healthcheck.interval.seconds"))
            throw new IllegalArgumentException("Use healthcheck.interval.ms instead of healthcheck.interval.seconds");
    }
    private int integer(String key, int fallback, int min, int max) {
        try {
            int result = Integer.parseInt(values.getProperty(key, Integer.toString(fallback)).trim());
            if (result < min || result > max) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be in [" + min + ", " + max + "]");
        }
    }
    private double decimal(String key, double fallback, double min, double max) {
        double result;
        try { result = Double.parseDouble(values.getProperty(key, Double.toString(fallback)).trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid " + key, e); }
        if (!Double.isFinite(result) || result < min || result > max)
            throw new IllegalArgumentException(key + " must be in [" + min + ", " + max + "]");
        return result;
    }
    private String path(String key, String fallback) {
        String result = values.getProperty(key, fallback).trim();
        if (!result.matches("/[a-zA-Z0-9_./-]*")) throw new IllegalArgumentException("Invalid " + key);
        return result;
    }
    private boolean bool(String key, boolean fallback) {
        String value = values.getProperty(key, Boolean.toString(fallback)).trim();
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(key + " must be true or false");
        return Boolean.parseBoolean(value);
    }
    public boolean isCircuitBreakerEnabled() { return bool("proxy.circuit-breaker.enabled", true); }
    public boolean isDecisionTraceEnabled() { return bool("adaptive.decision-trace.enabled", false); }
    public boolean isManagementEnabled() { return bool("proxy.management.enabled", false); }
    public int getManagementPort() { return integer("proxy.management.port", 9090, 0, 65535); }
    public int getManagementMaxTasks() { return integer("proxy.management.max.handler.tasks", 16, 1, 1024); }
    public int getCircuitFailureThreshold() { return integer("proxy.circuit-breaker.failure-threshold", 5, 1, 100000); }
    public int getCircuitOpenDurationMs() { return integer("proxy.circuit-breaker.open-duration.ms", 5000, 1, 3600000); }
    public int getCircuitHalfOpenMaxProbes() { return integer("proxy.circuit-breaker.half-open-max-probes", 1, 1, 1); }
    public boolean isRateLimitEnabled() { return bool("proxy.rate-limit.enabled", false); }
    public double getRateLimitPerSecond() { return decimal("proxy.rate-limit.requests-per-second", 100, .001, 1000000); }
    public int getRateLimitBurst() { return integer("proxy.rate-limit.burst", 200, 1, 1000000); }
    public int getRateLimitMaxClients() { return integer("proxy.rate-limit.max-clients", 4096, 1, 1000000); }
    public int getRateLimitIdleMs() { return integer("proxy.rate-limit.idle.ms", 60000, 1, 86400000); }
    public int getProxyPort() { return integer("proxy.port", 8080, 0, 65535); }
    public String getBindHost() { return values.getProperty("proxy.bind.host", "127.0.0.1").trim(); }
    public List<BackendServer> getBackendServers() { return backends; }
    public String getLoadBalancerStrategy() {
        return values.getProperty("loadbalancer.strategy", "ADAPTIVE").trim().toUpperCase(Locale.ROOT);
    }
    public int getConnectTimeoutMs() { return integer("proxy.connect.timeout.ms", 500, 1, 120000); }
    public int getReadTimeoutMs() { return integer("proxy.read.timeout.ms", 3000, 1, 120000); }
    public int getRequestTimeoutMs() { return integer("proxy.request.timeout.ms", 5000, 1, 300000); }
    public int getMaxBodyBytes() { return integer("proxy.max.body.bytes", 1048576, 1, 16777216); }
    public int getMaxHeaderBytes() { return integer("proxy.max.header.bytes", 32768, 1024, 262144); }
    public int getMaxHeaderCount() { return integer("proxy.max.header.count", 100, 1, 1000); }
    public int getShutdownGraceSeconds() { return integer("proxy.shutdown.grace.seconds", 2, 0, 30); }
    public int getMaxHandlerTasks() { return integer("proxy.max.handler.tasks", getMaxInflight() + 16, 1, 100016); }
    public int getMaxInflight() { return integer("proxy.max.inflight", 128, 1, 100000); }
    public int getBackendMaxInflight() { return integer("backend.max.inflight", 64, 1, 100000); }
    public int getHealthCheckIntervalMs() { return integer("healthcheck.interval.ms", 500, 20, 60000); }
    public int getHealthCheckTimeoutMs() { return integer("healthcheck.timeout.ms", 500, 1, 60000); }
    public String getHealthCheckPath() { return path("healthcheck.path", "/health"); }
    public String getMetricsPath() { return path("metrics.path", "/metrics"); }
    public int getMetricsStaleMs() { return integer("metrics.stale.ms", 3000, 20, 600000); }
    public int getWarmupMs() { return integer("adaptive.warmup.ms", 5000, 0, 600000); }
    public int getRecoverySuccesses() { return integer("healthcheck.recovery.successes", 2, 1, 100); }
    public double getEwmaAlpha() { return decimal("adaptive.ewma.alpha", 0.2, 0.001, 1); }
    public double getInitialLatencyMs() { return decimal("adaptive.initial.latency.ms", 50, 0.1, 120000); }
    public int getExplorationInterval() { return integer("adaptive.exploration.interval", 50, 2, 1000000); }
}
