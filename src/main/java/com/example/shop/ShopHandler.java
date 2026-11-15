package com.example.shop;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** Compatibility adapter for the historical API; StoreApplication owns the storefront. */
@Deprecated
public final class ShopHandler {
    private final LegacyShopApi api;
    public ShopHandler(ShopConfig config) throws IOException { api = new LegacyShopApi(config); }
    public void handle(HttpExchange exchange) throws IOException { api.handle(exchange); }
}
