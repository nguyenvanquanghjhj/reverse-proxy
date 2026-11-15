package com.example.backend;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared stateless HTTP parsing/framing; no application state or admission. */
final class BackendHttp {
    private BackendHttp() { }
    static int integer(Map<String, String> values, String key, int fallback, int min, int max) {
        int result = Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback)));
        if (result < min || result > max) throw new IllegalArgumentException(key + " outside " + min + ".." + max);
        return result;
    }
    static Map<String, String> query(HttpExchange exchange) {
        return parameters(exchange.getRequestURI().getRawQuery());
    }
    static Map<String, String> parameters(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw != null) for (String item : raw.split("&")) {
            String[] pair = item.split("=", 2);
            values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length < 2 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return values;
    }
    static void respond(HttpExchange exchange, int status, String body, String type) throws IOException {
        respondBytes(exchange, status, body.getBytes(StandardCharsets.UTF_8), type + "; charset=utf-8");
    }
    static void respondBytes(HttpExchange exchange, int status, byte[] bytes, String type) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        if (exchange.getRequestMethod().equals("HEAD") || status == 204 || status == 304) {
            if (status == 200) exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        }
    }
}
