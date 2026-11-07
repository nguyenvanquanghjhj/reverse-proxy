package com.example.proxy.metrics;

import com.example.proxy.loadbalancer.Dispatcher;
import com.example.proxy.util.Json;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Data-handler metrics, excluding management polling. Fixed classes and bounded latency storage.
 * Latency includes client body reading and response sending, not just upstream service time.
 * Snapshots copy under the short recording lock, then sort outside it. */
public final class TrafficMetrics {
    public enum Outcome { SUCCESS, BUSINESS_REFUSAL, SERVICE_PAUSED, DATABASE_UNAVAILABLE, STORAGE_UNAVAILABLE,
        FILE_MISSING, BACKEND_REJECTED, UPSTREAM_HTTP_ERROR, TRANSPORT_FAILURE, TIMEOUT, PROXY_REJECTED, CLIENT_FAILURE }
    public static Outcome classify(String route,int status,boolean exception,boolean dispatched,String service) {
        if(exception)return Outcome.CLIENT_FAILURE;
        if(status>=200&&status<400)return Outcome.SUCCESS;
        if(!dispatched)return Outcome.PROXY_REJECTED;
        if(status==423&&java.util.Set.of("SALES_PAUSED","DOWNLOADS_PAUSED","DOCUMENT_UPLOAD_PAUSED").contains(service==null?"":service))return Outcome.SERVICE_PAUSED;
        if(status==503&&"DATABASE_UNAVAILABLE".equals(service))return Outcome.DATABASE_UNAVAILABLE;
        if(status==503&&"STORAGE_UNAVAILABLE".equals(service))return Outcome.STORAGE_UNAVAILABLE;
        if(status==404&&"FILE_MISSING".equals(service))return Outcome.FILE_MISSING;
        if(status==429&&!"STORE_AUTH".equals(route)||status==503)return Outcome.BACKEND_REJECTED;
        return status>=400&&status<500?Outcome.BUSINESS_REFUSAL:Outcome.UPSTREAM_HTTP_ERROR;
    }
    public static final int WINDOW = 2048;
    private final Stats total = new Stats();
    private final Map<String, Stats> routes = new LinkedHashMap<>();
    public TrafficMetrics() { Dispatcher.requestClasses().forEach(route -> routes.put(route, new Stats())); }
    public synchronized void started(String route) {
        Stats stats = require(route);
        total.started++; total.active++;
        stats.started++; stats.active++;
    }
    public synchronized void completed(String route, int status, boolean exception, boolean dispatched, double elapsedMs) {
        completed(route,status,exception,dispatched,elapsedMs,classify(route,status,exception,dispatched,null));
    }
    public synchronized void completed(String route,int status,boolean exception,boolean dispatched,double elapsedMs,Outcome outcome) {
        Stats stats = require(route);
        total.complete(status, exception, dispatched, elapsedMs,outcome);
        stats.complete(status, exception, dispatched, elapsedMs,outcome);
    }
    private Stats require(String route) {
        Stats result = routes.get(route);
        if (result == null) throw new IllegalArgumentException("Unknown fixed request class");
        return result;
    }
    private static final class Stats {
        long started, completed, successful, failed, noResponse, dispatched, rejected;
        long http4xx, http5xx, badRequest, conflict, invalidInput, rateLimited;
        int active;
        final Ring all = new Ring(), success = new Ring();
        final long[] outcomes=new long[Outcome.values().length];
        void complete(int status, boolean exception, boolean selected, double elapsed,Outcome outcome) {
            if (active <= 0) throw new IllegalStateException("Unbalanced traffic completion");
            active--; completed++;
            outcomes[outcome.ordinal()]++;
            boolean ok = !exception && status >= 200 && status < 400;
            if (ok) successful++; else failed++;
            if (status < 0) noResponse++;
            if (status >= 400 && status < 500) http4xx++;
            if (status >= 500 && status < 600) http5xx++;
            if (status == 400) badRequest++;
            if (status == 409) conflict++;
            if (status == 422) invalidInput++;
            if (status == 429) rateLimited++;
            if (selected) dispatched++; else if (!ok) rejected++;
            if (Double.isFinite(elapsed) && elapsed >= 0) { all.add(elapsed); if (ok) success.add(elapsed); }
        }
        Snapshot snapshot() { return new Snapshot(started, completed, successful, failed, noResponse, dispatched, rejected, active,
                http4xx, http5xx, badRequest, conflict, invalidInput, rateLimited, all.copy(), success.copy(),outcomes.clone()); }
    }
    private static final class Ring {
        final double[] samples = new double[WINDOW];
        int size, next;
        void add(double value) { samples[next] = value; next = (next + 1) % WINDOW; size = Math.min(WINDOW, size + 1); }
        double[] copy() { return Arrays.copyOf(samples, size); }
    }
    private record Snapshot(long started, long completed, long successful, long failed, long noResponse,
            long dispatched, long rejected, int active, long http4xx, long http5xx, long badRequest,
            long conflict, long invalidInput, long rateLimited, double[] all, double[] success,long[] outcomes) {
        String json() {
            return "{\"started\":" + started + ",\"completed\":" + completed + ",\"successful\":" + successful
                    + ",\"failed\":" + failed + ",\"noResponse\":" + noResponse + ",\"dispatched\":" + dispatched
                    + ",\"rejectedBeforeDispatch\":" + rejected + ",\"active\":" + active
                    + ",\"http4xx\":" + http4xx + ",\"http5xx\":" + http5xx
                    + ",\"badRequest400\":" + badRequest + ",\"conflict409\":" + conflict
                    + ",\"invalidInput422\":" + invalidInput + ",\"rateLimited429\":" + rateLimited
                    + ",\"errorRate\":" + (completed == 0 ? "null" : Json.number((double) failed / completed))
                    + ",\"latencyMs\":" + latency(all) + ",\"successfulLatencyMs\":" + latency(success) + ",\"outcomes\":"+outcomesJson(outcomes)+"}";
        }
    }
    private static String outcomesJson(long[] counts){
        var out=new StringBuilder("{");for(var outcome:Outcome.values()){
            if(out.length()>1)out.append(',');out.append(Json.quote(outcome.name())).append(':').append(counts[outcome.ordinal()]);
        }return out.append('}').toString();
    }
    /** Nearest rank; hide tail quantiles below 20/100 observations rather than imply reliable tails. */
    private static String latency(double[] values) {
        Arrays.sort(values);
        return "{\"samples\":" + values.length + ",\"p50\":" + quantile(values, .5, 2)
                + ",\"p95\":" + quantile(values, .95, 20) + ",\"p99\":" + quantile(values, .99, 100) + "}";
    }
    private static String quantile(double[] sorted, double q, int minimum) {
        return sorted.length < minimum ? "null" : Json.number(sorted[(int) Math.ceil(q * sorted.length) - 1]);
    }
    public String json() {
        Snapshot overall;
        Map<String, Snapshot> copies = new LinkedHashMap<>();
        synchronized (this) {
            overall = total.snapshot();
            routes.forEach((key, value) -> { if (value.started > 0) copies.put(key, value.snapshot()); });
        }
        StringBuilder out = new StringBuilder("{\"windowCapacity\":").append(WINDOW)
                .append(",\"total\":").append(overall.json()).append(",\"routes\":{");
        copies.forEach((key, value) -> {
            if (out.charAt(out.length() - 1) != '{') out.append(',');
            out.append(Json.quote(key)).append(':').append(value.json());
        });
        return out.append("}}").toString();
    }
    public synchronized String prometheus() {
        StringBuilder out = new StringBuilder("# TYPE proxy_data_requests_completed_total counter\n# TYPE proxy_data_requests_failed_total counter\n# TYPE proxy_data_requests_active gauge\n");
        routes.forEach((route, stats) -> {
            String label = "{route=\"" + route + "\"} ";
            out.append("proxy_data_requests_completed_total").append(label).append(stats.completed).append('\n');
            out.append("proxy_data_requests_failed_total").append(label).append(stats.failed).append('\n');
            out.append("proxy_data_requests_active").append(label).append(stats.active).append('\n');
            for(var outcome:Outcome.values())out.append("proxy_data_outcome_total{route=\"").append(route).append("\",outcome=\"").append(outcome.name()).append("\"} ").append(stats.outcomes[outcome.ordinal()]).append('\n');
        });
        return out.toString();
    }
}
