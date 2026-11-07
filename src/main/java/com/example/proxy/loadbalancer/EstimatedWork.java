package com.example.proxy.loadbalancer;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.regex.Pattern;

/** Bounded route learning and a virtual FIFO server. Access only under Dispatcher's lock.
 * Illustrative arithmetic: with one slot holding 2000ms of SLOW work, LIGHT at 20ms/unit
 * and cost=2 has service=40ms, wait=2000ms, ECT=2040ms at the same instant.
 * This model supplies forecasts; AdaptiveLoadBalancer chooses and Dispatcher owns the state boundary. */
final class EstimatedWork {
    enum Route { LIGHT, SLOW, CPU, PRODUCTS, ORDERS, FILE_SMALL, FILE_MEDIUM, FILE_LARGE,
        ORDER_WRITE, ORDER_READ, DOCUMENT_LIST, DOCUMENT_UPLOAD, DOCUMENT_DOWNLOAD, OTHER,
        STORE_SEARCH, STORE_AUTH, STORE_ADMIN_READ, STORE_ADMIN_WRITE, STORE_IMAGE }
    record Request(Route route, int units) {
        private static final Pattern PRODUCT = Pattern.compile("/api/products/[^/]+");
        private static final Pattern ORDER = Pattern.compile("/api/orders/[^/]+");
        private static final Pattern DOCUMENTS = Pattern.compile("/api/products/[^/]+/documents");
        private static final Pattern DOWNLOAD = Pattern.compile("/api/documents/[^/]+/download");
        private static final Pattern STORE_SEARCH_QUERY = Pattern.compile("(^|&)q=[^&]+");
        static Request of(URI uri) {
            return of("GET",uri);
        }
        static Request of(String method, URI uri) {
            String path = uri.getPath();
            Route store = storeRoute(method,path,uri.getRawQuery());
            if (store != null) return new Request(store,1);
            Route shop = shopRoute(method,path);
            if (shop != null) return new Request(shop,1);
            Route route = switch (path == null ? "" : path) {
                case "/work" -> Route.LIGHT;
                case "/slow" -> Route.SLOW;
                case "/compute" -> Route.CPU;
                case "/products" -> Route.PRODUCTS;
                case "/orders" -> Route.ORDERS;
                case "/download" -> downloadRoute(uri);
                default -> Route.OTHER;
            };
            int units = 1;
            // Match the demo's bounded cost metadata, never key a map by unbounded URLs.
            if ((route == Route.LIGHT || route == Route.CPU) && uri.getRawQuery() != null) {
                try {
                    for (String part : uri.getRawQuery().split("&")) {
                        String[] pair = part.split("=", 2);
                        if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals("cost"))
                            units = pair.length == 2 ? Integer.parseInt(URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) : 1;
                    }
                } catch (IllegalArgumentException invalid) { units = 1; }
            }
            return new Request(route, units >= 1 && units <= 20 ? units : 1);
        }
        private static Route shopRoute(String method,String path) {
            if(path==null || !path.startsWith("/api/")) return null;
            boolean read=method.equals("GET") || method.equals("HEAD");
            if(read && (path.equals("/api/products") || PRODUCT.matcher(path).matches())) return Route.PRODUCTS;
            if(method.equals("POST") && path.equals("/api/orders")) return Route.ORDER_WRITE;
            if(read && ORDER.matcher(path).matches()) return Route.ORDER_READ;
            if(DOCUMENTS.matcher(path).matches()) {
                if(method.equals("POST")) return Route.DOCUMENT_UPLOAD;
                if(read) return Route.DOCUMENT_LIST;
            }
            if(read && DOWNLOAD.matcher(path).matches()) return Route.DOCUMENT_DOWNLOAD;
            return null;
        }
        private static Route storeRoute(String method,String path,String query) {
            if(path==null||!path.startsWith("/api/store/"))return null;
            boolean read=method.equals("GET")||method.equals("HEAD");
            if(path.startsWith("/api/store/admin/")) {
                if(method.equals("POST")&&path.endsWith("/files"))return Route.DOCUMENT_UPLOAD;
                return read?Route.STORE_ADMIN_READ:Route.STORE_ADMIN_WRITE;
            }
            if(path.startsWith("/api/store/auth/")&&method.equals("POST"))return Route.STORE_AUTH;
            if(path.equals("/api/store/products")&&read)return query!=null&&STORE_SEARCH_QUERY.matcher(query).find()?Route.STORE_SEARCH:Route.PRODUCTS;
            if(path.startsWith("/api/store/products/")&&read)return Route.PRODUCTS;
            if(path.equals("/api/store/orders")&&method.equals("POST"))return Route.ORDER_WRITE;
            if(path.startsWith("/api/store/orders")&&read)return Route.ORDER_READ;
            if(path.startsWith("/api/store/documents/")&&read)return Route.DOCUMENT_DOWNLOAD;
            if(path.startsWith("/api/store/images/")&&read)return Route.STORE_IMAGE;
            return Route.OTHER;
        }
        private static Route downloadRoute(URI uri) {
            int size = 256;
            try {
                if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
                    String[] pair = part.split("=", 2);
                    if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals("sizeKiB"))
                        size = Integer.parseInt(URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8));
                }
            } catch (IllegalArgumentException invalid) { size = 256; }
            // Bounded demo sizes; learn each size separately instead of assuming latency scales linearly with bytes.
            return size == 64 ? Route.FILE_SMALL : size == 768 ? Route.FILE_LARGE : Route.FILE_MEDIUM;
        }
    }
    record Forecast(double serviceMs, double waitMs, double completionMs, double outstandingMs, double queuedMs) { }
    static final class Reservation {
        final Request request;
        final long generation, admittedNanos;
        final double serviceMs, startDelayMs;
        final boolean learn;
        Reservation(Request request, long generation, long now, Forecast forecast, boolean learn) {
            this.request = request; this.generation = generation; this.admittedNanos = now;
            serviceMs = forecast.serviceMs(); startDelayMs = forecast.waitMs(); this.learn = learn;
        }
    }
    private final EnumMap<Route, Double> servicePerUnit = new EnumMap<>(Route.class);
    private final List<Reservation> outstanding = new ArrayList<>();
    private final double initialMs;
    private long generation;
    private double genericMs;
    EstimatedWork(double initialMs) { this.initialMs = initialMs; genericMs = initialMs; }
    String estimatesJson() {
        StringBuilder out = new StringBuilder("{");
        servicePerUnit.forEach((route, value) -> {
            if (out.length() > 1) out.append(',');
            out.append(com.example.proxy.util.Json.quote(route.name())).append(':').append(com.example.proxy.util.Json.number(value));
        });
        return out.append('}').toString();
    }
    void generation(long current) {
        if (generation != current) {
            generation = current;
            servicePerUnit.clear();
            genericMs = initialMs;
            // A health transition must not discard work still owned by live leases.
        }
    }
    private double service(Request request) {
        return servicePerUnit.getOrDefault(request.route(), initialMs) * request.units();
    }
    Forecast forecast(Request request, long now, double capacity, int external) {
        int slots = Math.max(1, (int) Math.floor(capacity));
        // No need to allocate unused lanes for a very large configured capacity.
        int lanes = Math.min(slots, outstanding.size() + 1);
        PriorityQueue<Double> available = new PriorityQueue<>();
        for (int i = 0; i < lanes; i++) available.add(0.0);
        double work = 0, queued = 0;
        int position = 0;
        for (Reservation job : outstanding) {
            double start = available.remove();
            double duration = Math.max(job.serviceMs, service(job.request));
            if (position++ < slots) {
                double elapsed = Math.max(0, (now - job.admittedNanos) / 1_000_000.0 - job.startDelayMs);
                // An overdue live lease still occupies a slot; never declare it finished by a clock prediction.
                duration = Math.max(Math.min(initialMs, duration), duration - elapsed);
            } else queued += duration;
            work += duration;
            available.add(start + duration);
        }
        double externalWork = external * genericMs;
        double wait = available.element() + externalWork / slots;
        double cost = service(request);
        return new Forecast(cost, wait, wait + cost, work + externalWork, queued + externalWork);
    }
    Reservation reserve(Request request, long now, Forecast forecast, int slots, int external) {
        var job = new Reservation(request, generation, now, forecast, outstanding.size() < slots && external == 0);
        outstanding.add(job);
        return job;
    }
    void release(Reservation job) {
        if (!outstanding.remove(job)) throw new IllegalStateException("Unknown work reservation");
    }
    void complete(Reservation job, double elapsedMs, boolean error, double alpha) {
        release(job);
        // RTT of a queued request is NOT service time. Only learn from reservations with a free slot.
        // Old-generation completions and failures cannot train a recovered backend.
        if (!error && job.learn && job.generation == generation && Double.isFinite(elapsedMs) && elapsedMs >= 0) {
            double sample = Math.max(0.1, elapsedMs);
            servicePerUnit.merge(job.request.route(), sample / job.request.units(), (old, next) -> old + alpha * (next - old));
            genericMs += alpha * (sample - genericMs);
        }
    }
}
