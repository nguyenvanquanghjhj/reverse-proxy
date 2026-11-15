package com.example.proxy.server;

import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Operator credentials are independent from forwarded client identity and never logged.
 * Loopback restriction must ALSO be enforced by the caller.
 */
public final class OperatorAuth {
    private OperatorAuth() { }
    public static boolean authorized(HttpExchange exchange) {
        String configured=System.getenv("PBL4_CONTROL_TOKEN");
        if (configured == null || configured.isBlank()) return true; // local legacy workflow
        if (configured.length() < 32) return false; // refuse weak configured secrets
        String provided=exchange.getRequestHeaders().getFirst("X-PBL4-Control-Token");
        if (provided==null) return false;
        return MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
