package com.example.proxy;

import com.example.store.AssetCache;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class AssetCacheTest {
    public static void main(String[] args) {
        String a = AssetCache.etag("file a".getBytes(StandardCharsets.UTF_8));
        String b = AssetCache.etag("file b".getBytes(StandardCharsets.UTF_8));
        if (a.equals(b) || !a.matches("\"[0-9a-f]{64}\"")) throw new AssertionError("SHA ETag");
        if (!AssetCache.notModified(List.of(a), a)) throw new AssertionError("direct tag");
        if (!AssetCache.notModified(List.of("W/" + a), a)) throw new AssertionError("weak tag");
        if (!AssetCache.notModified(List.of("\"other\", " + a), a)) throw new AssertionError("multi tag");
        if (!AssetCache.notModified(List.of("*"), a)) throw new AssertionError("wildcard");
        if (AssetCache.notModified(List.of(b), a)) throw new AssertionError("incorrect match");
        if (AssetCache.notModified(null, a)) throw new AssertionError("null");
        System.out.println("AssetCacheTest PASS");
    }
}
