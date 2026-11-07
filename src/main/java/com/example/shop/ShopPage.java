package com.example.shop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable classpath bundle. HTTP input cannot address arbitrary resources or filesystem paths. */
public final class ShopPage {
    record Asset(byte[] bytes, String contentType) { }
    private static final Map<String, Asset> ASSETS = load();
    private ShopPage() { }
    private static Map<String, Asset> load() {
        Map<String, Asset> assets = new LinkedHashMap<>();
        for (String name : new String[]{"index.html", "shop.css", "api.js", "order-state.js", "shop.js"}) {
            String type = name.endsWith(".html") ? "text/html" : name.endsWith(".css") ? "text/css" : "text/javascript";
            try (var stream = ShopPage.class.getResourceAsStream("/web/shop/" + name)) {
                if (stream == null) throw new IOException("Missing packaged shop resource: " + name);
                byte[] bytes = stream.readNBytes(262145);
                if (bytes.length == 0 || bytes.length > 262144) throw new IOException("Invalid shop resource size: " + name);
                String path = name.equals("index.html") ? "/shop" : "/shop/assets/" + name;
                assets.put(path, new Asset(bytes, type + "; charset=utf-8"));
            } catch (IOException failure) { throw new IllegalStateException("Build must package src/main/resources", failure); }
        }
        assets.put("/shop/", assets.get("/shop"));
        return Map.copyOf(assets);
    }
    static Asset asset(String path) { return ASSETS.get(path); }
    static void verifyPackaged() { if (ASSETS.size() != 6) throw new IllegalStateException("Incomplete shop bundle"); }
    /** Compatibility for document callers. Scripts and styles are packaged separately. */
    public static String html() { return new String(ASSETS.get("/shop").bytes(), StandardCharsets.UTF_8); }
}
