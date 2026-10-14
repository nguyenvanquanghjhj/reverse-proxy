package com.example.proxy.handler;

import com.example.proxy.config.ProxyConfig;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/** Per-peer token bucket. Bounded memory, no background threads, no trust in forwarding headers. */
public final class ClientRateLimiter {
    public record Decision(boolean allowed, long retryAfterSeconds) { }
    private final boolean enabled;
    private final double rate;
    private final int burst, maxClients;
    private final long idleNanos;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Bucket> clients = new LinkedHashMap<>(16, .75f, true);
    private long rejected, capacityRejected;
    private static final class Bucket {
        double tokens;
        long updated;
        Bucket(double tokens, long updated) { this.tokens = tokens; this.updated = updated; }
    }
    public ClientRateLimiter(ProxyConfig config) { this(config, System::nanoTime); }
    public ClientRateLimiter(ProxyConfig config, LongSupplier clock) {
        enabled = config.isRateLimitEnabled(); rate = config.getRateLimitPerSecond();
        burst = config.getRateLimitBurst(); maxClients = config.getRateLimitMaxClients();
        idleNanos = config.getRateLimitIdleMs() * 1_000_000L; this.clock = clock;
    }
    public synchronized Decision acquire(String peer) {
        if (!enabled) return new Decision(true, 0);
        long now = clock.getAsLong();
        // Access order and updated timestamps agree. Only fully refilled idle buckets expire.
        var iterator = clients.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().updated < idleNanos) break;
            iterator.remove();
        }
        Bucket bucket = clients.get(peer);
        if (bucket == null) {
            if (clients.size() >= maxClients) {
                rejected++; capacityRejected++;
                long wait = idleNanos - (now - clients.entrySet().iterator().next().getValue().updated);
                return new Decision(false, Math.max(1, (wait + 999_999_999L) / 1_000_000_000L));
            }
            bucket = new Bucket(burst, now); clients.put(peer, bucket);
        }
        bucket.tokens = Math.min(burst, bucket.tokens + Math.max(0, now - bucket.updated) / 1_000_000_000.0 * rate);
        bucket.updated = now;
        if (bucket.tokens >= 1) { bucket.tokens--; return new Decision(true, 0); }
        rejected++;
        return new Decision(false, Math.max(1, (long) Math.ceil((1 - bucket.tokens) / rate)));
    }
    public synchronized String json() {
        return "\"rate_limit_rejected\":" + rejected + ",\"rate_limit_capacity_rejected\":" + capacityRejected
                + ",\"rate_limit_clients\":" + clients.size();
    }
    public synchronized String prometheus() {
        return "# TYPE proxy_rate_limit_rejected_total counter\nproxy_rate_limit_rejected_total " + rejected
                + "\n# TYPE proxy_rate_limit_capacity_rejected_total counter\nproxy_rate_limit_capacity_rejected_total " + capacityRejected
                + "\n# TYPE proxy_rate_limit_clients gauge\nproxy_rate_limit_clients " + clients.size() + "\n";
    }
}
