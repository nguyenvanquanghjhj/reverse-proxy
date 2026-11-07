package com.example.shop;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public record OrderRequest(String customerName, List<Item> items) {
    public record Item(long productId, int quantity) { }
    public static OrderRequest parse(byte[] bytes) {
        Object parsed=JsonCodec.parse(bytes);
        if (!(parsed instanceof Map<?,?> map)) throw ShopException.invalid("Order must be an object");
        if (!(map.get("customerName") instanceof String raw)) throw ShopException.invalid("customerName is required");
        String name=raw.strip();
        if (name.isEmpty() || name.codePointCount(0,name.length())>80 || name.codePoints().anyMatch(Character::isISOControl))
            throw ShopException.invalid("customerName must contain 1..80 characters without controls");
        if (!(map.get("items") instanceof List<?> list) || list.isEmpty() || list.size()>20) throw ShopException.invalid("items must contain 1..20 lines");
        List<Item> items=new ArrayList<>(); Set<Long> seen=new HashSet<>();
        for (Object value:list) {
            if (!(value instanceof Map<?,?> line)) throw ShopException.invalid("Invalid item");
            long id=integer(line.get("productId")), quantity=integer(line.get("quantity"));
            if(id<1 || quantity<1 || quantity>100) throw ShopException.invalid("Positive productId; quantity 1..100 required");
            if(!seen.add(id)) throw ShopException.invalid("Duplicate productId");
            items.add(new Item(id,(int)quantity));
        }
        items.sort(Comparator.comparingLong(Item::productId));
        return new OrderRequest(name,List.copyOf(items));
    }
    private static long integer(Object value) {
        try { if(value instanceof BigDecimal n) return n.longValueExact(); }
        catch(ArithmeticException e) { throw ShopException.invalid("Expected integer within BIGINT range"); }
        throw ShopException.invalid("Expected integer");
    }
    public String hash() {
        List<Object> lines=new ArrayList<>();
        for(Item i:items) lines.add(List.of(i.productId(),i.quantity()));
        return sha256(JsonCodec.write(List.of(customerName,lines)).getBytes(StandardCharsets.UTF_8));
    }
    public static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String key(String value) {
        if(value==null || !value.matches("[A-Za-z0-9._:-]{1,128}")) throw new ShopException(400,"IDEMPOTENCY_KEY","Idempotency-Key must be 1..128 safe ASCII characters");
        return value;
    }
    public static long lineTotal(long price,int quantity) { return Math.multiplyExact(price,quantity); }
}
