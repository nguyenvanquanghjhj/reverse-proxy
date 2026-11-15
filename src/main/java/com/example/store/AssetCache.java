package com.example.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.HexFormat;

/** ETag for immutable-by-build public assets. Never use for identity-dependent HTML or APIs. */
public final class AssetCache {
    private AssetCache() { }

    public static String etag(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "\"" + HexFormat.of().formatHex(hash) + "\"";
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK SHA-256 unavailable", failure);
        }
    }

    /** Weak comparison is correct for If-None-Match on GET/HEAD (RFC 9110). */
    public static boolean notModified(List<String> ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isEmpty() || etag == null) return false;
        for (String field : ifNoneMatch) {
            if (field == null) continue;
            for (String raw : field.split(",")) {
                String item = raw.strip();
                if (item.equals("*")) return true;
                if (item.startsWith("W/")) item = item.substring(2);
                if (item.equals(etag)) return true;
            }
        }
        return false;
    }
}
