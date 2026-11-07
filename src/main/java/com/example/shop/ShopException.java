package com.example.shop;

public final class ShopException extends RuntimeException {
    public final int status;
    public final String code;
    public ShopException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public static ShopException invalid(String message) { return new ShopException(422, "VALIDATION", message); }
}
