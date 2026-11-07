package com.example.shop;

import java.nio.file.Path;
import java.util.Map;

public record ShopConfig(String dbUrl, String dbUser, String dbPassword, Path filesDir, int maxPdfBytes, String adminToken, int maxConnections) {
    public ShopConfig {
        if (dbUrl == null || !dbUrl.startsWith("jdbc:postgresql:") || dbUser == null || dbUser.isBlank())
            throw new IllegalArgumentException("SHOP_DB_URL must use PostgreSQL; SHOP_DB_USER is required");
        if (filesDir == null || !filesDir.isAbsolute()) throw new IllegalArgumentException("SHOP_FILES_DIR must be absolute");
        if (maxPdfBytes < 1 || maxPdfBytes > 786432 || maxConnections < 1 || maxConnections > 32)
            throw new IllegalArgumentException("SHOP_MAX_PDF_BYTES must be 1..786432; SHOP_DB_MAX_CONNECTIONS 1..32");
        if (dbPassword == null || adminToken == null) throw new IllegalArgumentException("Invalid shop configuration");
    }
    public static boolean enabled() {
        String value = System.getenv().getOrDefault("SHOP_ENABLED", "false");
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException("SHOP_ENABLED must be true or false");
        return Boolean.parseBoolean(value);
    }
    public static ShopConfig environment() {
        Map<String,String> e = System.getenv();
        Path directory=e.containsKey("SHOP_FILES_DIR") ? Path.of(e.get("SHOP_FILES_DIR")) : Path.of("shared-files").toAbsolutePath();
        if(!directory.isAbsolute()) throw new IllegalArgumentException("SHOP_FILES_DIR must be a nonempty absolute path");
        return new ShopConfig(e.getOrDefault("SHOP_DB_URL", "jdbc:postgresql://127.0.0.1:5432/pbl4_shop"),
                e.getOrDefault("SHOP_DB_USER", "pbl4_shop"), e.getOrDefault("SHOP_DB_PASSWORD", ""),
                directory.normalize(),
                Integer.parseInt(e.getOrDefault("SHOP_MAX_PDF_BYTES", "262144")),
                e.getOrDefault("SHOP_UPLOAD_ADMIN_TOKEN", ""), Integer.parseInt(e.getOrDefault("SHOP_DB_MAX_CONNECTIONS", "8")));
    }
}
