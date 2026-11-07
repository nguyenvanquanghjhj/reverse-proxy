package com.example.proxy.metrics;

import com.example.proxy.util.Json;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Upstream exchanges only, excluding health/telemetry probes. No URL or identity labels.
 * Socket I/O byte counters count successful Java read/write calls, not NIC traffic or TCP overhead. */
public final class TransportMetrics {
    private final LongAdder attempted = new LongAdder(), connected = new LongAdder(), completed = new LongAdder();
    private final LongAdder failed = new LongAdder(), bytesRead = new LongAdder(), bytesWritten = new LongAdder();
    private final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
    private final Samples connect = new Samples(), exchange = new Samples(), firstByte = new Samples();
    public void started() { attempted.increment(); int count = active.incrementAndGet(); peak.accumulateAndGet(count, Math::max); }
    public void connected(long nanos) { connected.increment(); connect.add(nanos); }
    public void firstByte(long nanos) { firstByte.add(nanos); }
    public void read(long count) { bytesRead.add(count); }
    public void written(long count) { bytesWritten.add(count); }
    public void finished(long nanos, boolean success) { exchange.add(nanos); completed.increment(); if (!success) failed.increment(); active.decrementAndGet(); }
    public String json() {
        return "{\"attempted\":" + attempted.sum() + ",\"connected\":" + connected.sum()
                + ",\"completed\":" + completed.sum() + ",\"failed\":" + failed.sum()
                + ",\"activeSockets\":" + active.get() + ",\"peakSockets\":" + peak.get()
                + ",\"bytesRead\":" + bytesRead.sum() + ",\"bytesWritten\":" + bytesWritten.sum()
                + ",\"connectMs\":" + connect.json() + ",\"exchangeMs\":" + exchange.json()
                + ",\"firstByteAfterConnectMs\":" + firstByte.json()
                + ",\"mode\":\"buffered-one-socket-per-exchange\",\"sampleCapacity\":2048}";
    }
    private static final class Samples {
        private final double[] ring = new double[2048]; private int count, next;
        synchronized void add(long nanos) { ring[next] = nanos / 1_000_000.0; next = (next + 1) % ring.length; count = Math.min(ring.length, count + 1); }
        String json() {
            double[] copy; synchronized (this) { copy = Arrays.copyOf(ring, count); }
            Arrays.sort(copy); double sum = 0; for (double v : copy) sum += v;
            return "{\"samples\":" + copy.length + ",\"mean\":" + (copy.length == 0 ? "null" : Json.number(sum / copy.length))
                    + ",\"p50\":" + quantile(copy, .5, 2) + ",\"p95\":" + quantile(copy, .95, 20)
                    + ",\"p99\":" + quantile(copy, .99, 100) + "}";
        }
        private static String quantile(double[] a, double q, int min) { return a.length < min ? "null" : Json.number(a[(int)Math.ceil(a.length * q) - 1]); }
    }
}
