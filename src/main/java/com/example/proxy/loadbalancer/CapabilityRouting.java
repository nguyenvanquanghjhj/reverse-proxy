package com.example.proxy.loadbalancer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Route-specific backend eligibility, independent of liveness, global drain, and circuit state.
 * Input is the bounded /ready JSON emitted by StoreApplication, not client-provided data.
 * Unknown, malformed, or stale data fails closed for database/file-dependent routes.
 */
public final class CapabilityRouting {
    private static final Set<String> KNOWN = Set.of("static_files", "catalog", "order_create",
            "document_download", "document_upload", "file_read");
    private static final Pattern OBJECT = Pattern.compile("\\\"capabilities\\\"\\s*:\\s*\\{([^{}]{0,4096})\\}");
    private static final Pattern ENTRY = Pattern.compile("\\\"([a-z_]+)\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"");
    private CapabilityRouting() { }

    public record Status(Map<String,String> values, long observedNanos) {
        public Status { values = Map.copyOf(values); }
        public static Status unknown() { return new Status(Map.of(), Long.MIN_VALUE); }
        public Status withObservedAt(long now) { return new Status(values, now); }
        public boolean supports(String required, long now, long maxAgeMs) {
            if (required == null) return true;
            if (observedNanos == Long.MIN_VALUE || now < observedNanos
                    || now - observedNanos > maxAgeMs * 1_000_000L) return false;
            // PAUSED means business-policy rejection (e.g. HTTP 423), not dependency failure.
            // Preserve it rather than turning an intentional 423 into a proxy 503.
            String state = values.get(required);
            return "AVAILABLE".equals(state) || "PAUSED".equals(state);
        }
        public String json() {
            var out=new StringBuilder("{");
            values.forEach((key,value)->{
                if(out.length()>1)out.append(',');
                out.append('"').append(key).append("\":\"").append(value).append('"');
            });
            return out.append('}').toString();
        }
    }
    public static Status parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > 16384) return Status.unknown();
        try {
            Object parsed = com.example.shop.JsonCodec.parse(body);
            if (!(parsed instanceof Map<?, ?> root)) return Status.unknown();
            if (!(root.get("dependencies") instanceof Map<?, ?> dependencies)) return Status.unknown();
            if (!(dependencies.get("capabilities") instanceof Map<?, ?> candidates)) return Status.unknown();
            Map<String,String> values = new HashMap<>();
            for (var entry : candidates.entrySet()) {
                if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof String state))
                    return Status.unknown();
                if (KNOWN.contains(name)) {
                    if (!Set.of("AVAILABLE", "PAUSED", "UNAVAILABLE").contains(state))
                        return Status.unknown();
                    values.put(name, state);
                }
            }
            if (!values.containsKey("catalog") || !values.containsKey("order_create"))
                return Status.unknown();
            return new Status(values, Long.MIN_VALUE);
        } catch (RuntimeException invalid) {
            return Status.unknown();
        }
    }
    /** Null means this request is independent of /ready capabilities (e.g. /work). */
    public static String required(String method,URI uri) {
        String path=uri.getPath();
        if (path==null) return null;
        if (path.equals("/shop") || path.startsWith("/shop/") || path.startsWith("/store/assets/"))
            return "static_files";
        if(path.equals("/admin") || path.startsWith("/admin/"))return "catalog";
        if (!path.startsWith("/api/store"))return null;
        if (path.equals("/api/store/policy")) return "static_files";
        if (path.startsWith("/api/store/images/") || path.startsWith("/api/store/files/"))
            return "file_read";
        if (path.startsWith("/api/store/documents/")) return "document_download";
        if (path.startsWith("/api/store/admin/products/") && path.endsWith("/files")
                && method.equals("POST")) return "document_upload";
        if (path.startsWith("/api/store/admin/files/") && method.equals("GET")) return "file_read";
        if (path.equals("/api/store/orders") && method.equals("POST")) return "order_create";
        return "catalog";
    }
}
