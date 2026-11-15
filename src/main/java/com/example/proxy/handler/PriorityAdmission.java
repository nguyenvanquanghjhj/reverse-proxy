package com.example.proxy.handler;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;
import java.util.Map;

/**
 * Two-class bounded admission. Reserved capacity is held for transactional writes;
 * normal traffic cannot consume it, but critical traffic can use all free slots.
 * No queue, no blocking under a lock, and no retry of requests.
 */
public final class PriorityAdmission {
    private final int capacity;
    private final int reserved;
    private final int maxCriticalPerPeer;
    private final Map<String,Integer> criticalByPeer = new HashMap<>();
    private int active, normal, critical;
    private long rejectedPeerCap;
    private long acceptedNormal, acceptedCritical, rejectedNormal, rejectedCritical;

    public PriorityAdmission(int capacity, int reserved) {
        this(capacity, reserved, capacity);
    }
    public PriorityAdmission(int capacity, int reserved, int maxCriticalPerPeer) {
        if (capacity < 1 || reserved < 0 || reserved >= capacity
                || maxCriticalPerPeer < 1 || maxCriticalPerPeer > capacity)
            throw new IllegalArgumentException("Invalid priority admission limits");
        this.capacity = capacity;
        this.reserved = reserved;
        this.maxCriticalPerPeer = maxCriticalPerPeer;
    }

    /** Only order creation is critical; arbitrary clients cannot promote themselves via headers. */
    public static boolean isCritical(String method, String path) {
        return "POST".equals(method) && ("/api/store/orders".equals(path) || "/api/orders".equals(path));
    }

    /** Legacy standalone caller; the production handler passes a real peer IP. */
    public synchronized Ticket tryAcquire(String method, String path) {
        return tryAcquire(method, path, null);
    }
    public synchronized Ticket tryAcquire(String method, String path, String peer) {
        boolean priority = reserved > 0 && isCritical(method, path);
        if (priority && peer != null && criticalByPeer.getOrDefault(peer, 0) >= maxCriticalPerPeer) {
            rejectedCritical++; rejectedPeerCap++;
            return null;
        }
        if (active == capacity || (!priority && normal >= capacity - reserved)) {
            if (priority) rejectedCritical++; else rejectedNormal++;
            return null;
        }
        active++;
        if (priority) {
            critical++; acceptedCritical++;
            if (peer != null) criticalByPeer.merge(peer, 1, Integer::sum);
        } else { normal++; acceptedNormal++; }
        return new Ticket(this, priority, peer);
    }

    private synchronized void release(boolean priority, String peer) {
        if (active <= 0 || (priority ? critical : normal) <= 0)
            throw new IllegalStateException("Unbalanced priority admission release");
        active--;
        if (priority) {
            critical--;
            if (peer != null) {
                Integer count = criticalByPeer.get(peer);
                if (count == null || count < 1) throw new IllegalStateException("Peer accounting mismatch");
                if (count == 1) criticalByPeer.remove(peer); else criticalByPeer.put(peer, count - 1);
            }
        } else normal--;
    }

    public synchronized int availablePermits() { return capacity - active; }

    public synchronized String json() {
        return "{\"capacity\":" + capacity + ",\"reservedCritical\":" + reserved
                + ",\"active\":" + active + ",\"activeNormal\":" + normal
                + ",\"activeCritical\":" + critical
                + ",\"acceptedNormal\":" + acceptedNormal
                + ",\"acceptedCritical\":" + acceptedCritical
                + ",\"rejectedNormal\":" + rejectedNormal
                + ",\"rejectedCritical\":" + rejectedCritical
                + ",\"rejectedPeerCap\":" + rejectedPeerCap
                + ",\"criticalPeers\":" + criticalByPeer.size() + "}";
    }

    public synchronized String prometheus() {
        return "# TYPE proxy_qos_active gauge\nproxy_qos_active " + active + "\n"
                + "# TYPE proxy_qos_reserved_slots gauge\nproxy_qos_reserved_slots " + reserved + "\n"
                + "# TYPE proxy_qos_rejections_total counter\n"
                + "proxy_qos_rejections_total{class=\"normal\"} " + rejectedNormal + "\n"
                + "proxy_qos_rejections_total{class=\"critical\"} " + rejectedCritical + "\n"
                + "# TYPE proxy_qos_peer_cap_rejections_total counter\n"
                + "proxy_qos_peer_cap_rejections_total " + rejectedPeerCap + "\n";
    }

    public static final class Ticket implements AutoCloseable {
        private final PriorityAdmission admission;
        private final boolean critical;
        private final String peer;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Ticket(PriorityAdmission admission, boolean critical, String peer) {
            this.admission = admission;
            this.critical = critical;
            this.peer = peer;
        }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) admission.release(critical, peer);
        }
    }
}
