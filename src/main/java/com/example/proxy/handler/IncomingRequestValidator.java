package com.example.proxy.handler;

import com.example.proxy.config.ProxyConfig;
import java.net.URI;
import java.util.*;

/** Pure checks on metadata already parsed by JDK HttpServer; never reads a body or writes a response. */
final class IncomingRequestValidator {
    record Rejection(int status, String message) { }
    private final int maxBodyBytes, maxHeaderCount, maxHeaderBytes;

    IncomingRequestValidator(ProxyConfig config) {
        maxBodyBytes = config.getMaxBodyBytes();
        maxHeaderCount = config.getMaxHeaderCount();
        maxHeaderBytes = config.getMaxHeaderBytes();
    }
    Rejection forwardedRequest(String method, URI uri, Map<String, List<String>> headers) {
        if (method.equalsIgnoreCase("CONNECT") || headers.containsKey("Upgrade"))
            return new Rejection(501, "CONNECT and protocol upgrades are not supported");
        String path = uri.getPath();
        if (!method.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                || path == null || !path.startsWith("/")
                || uri.getRawFragment() != null
                || uri.toString().chars().anyMatch(c -> c <= 32 || c >= 127))
            return new Rejection(400, "Invalid request method or target");
        if (isControlPath(path))
            return new Rejection(403, "Backend control endpoints must be accessed directly");
        if (uri.isAbsolute() || uri.getRawAuthority() != null)
            return new Rejection(400, "Only origin-form request targets are supported");
        return managementRequest(method, uri, headers);
    }
    // Management keeps its own method/path policy in ReverseProxyServer.monitor().
    Rejection managementRequest(String method, URI uri, Map<String, List<String>> headers) {
        Rejection framing = framing(headers);
        return framing != null ? framing : headers(method, uri, headers);
    }
    private static boolean isControlPath(String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String part : path.replace('\\', '/').split("/")) {
            part = part.split(";", 2)[0];
            if (part.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); }
            else if (!part.isEmpty() && !part.equals(".")) parts.addLast(part);
        }
        return !parts.isEmpty() && Set.of("control", "down", "up").contains(parts.getFirst());
    }
    private Rejection framing(Map<String, List<String>> headers) {
        List<String> lengths = headers.get("Content-Length");
        List<String> transfer = headers.get("Transfer-Encoding");
        if ((lengths != null && transfer != null) || (transfer != null && (transfer.size() != 1 || !transfer.getFirst().equalsIgnoreCase("chunked"))))
            return new Rejection(400, "Ambiguous request framing");
        if (lengths != null) {
            try {
                if (lengths.size() != 1 || !lengths.getFirst().matches("[0-9]+")) throw new NumberFormatException();
                if (Long.parseLong(lengths.getFirst()) > maxBodyBytes)
                    return new Rejection(413, "Request body exceeds limit");
            } catch (NumberFormatException e) { return new Rejection(400, "Invalid Content-Length"); }
        }
        return null;
    }
    private Rejection headers(String method, URI uri, Map<String, List<String>> headers) {
        for (var entry : headers.entrySet()) {
            if (!UpstreamTransport.validHeaderName(entry.getKey()) || entry.getValue().stream().anyMatch(v -> !UpstreamTransport.validHeaderValue(v)))
                return new Rejection(400, "Invalid request header");
        }
        if (headers.values().stream().mapToInt(List::size).sum() > maxHeaderCount
                || headerSize(method, uri, headers) > maxHeaderBytes)
            return new Rejection(431, "Request headers exceed limit");
        return null;
    }
    private static long headerSize(String method, URI uri, Map<String, List<String>> headers) {
        long size = method.length() + uri.toString().length() + 16;
        for (Map.Entry<String, List<String>> entry : headers.entrySet())
            for (String value : entry.getValue()) size += entry.getKey().length() + value.length() + 4;
        return size;
    }
}
