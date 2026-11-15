package com.example.proxy.util;

import java.util.concurrent.TimeUnit;

/** Shares the ingress task's monotonic budget with the handler and upstream transport. */
public final class RequestDeadline {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<java.util.function.IntToLongFunction> EXTEND = new ThreadLocal<>();
    private RequestDeadline() { }
    public static long afterMillis(int timeoutMs) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("Request timeout must be positive");
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    }
    public static long currentOrNew(int timeoutMs) {
        Long current = CURRENT.get();
        return current == null ? afterMillis(timeoutMs) : current;
    }
    public static void run(long expires, Runnable action) {
        Long previous = CURRENT.get();
        CURRENT.set(expires);
        try { action.run(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    /** Called once, after route parsing; header parsing retains the short ingress budget. */
    public static long forRoute(int timeoutMs) {
        var extend = EXTEND.get();
        if(extend==null)return afterMillis(timeoutMs);
        long expires = extend.applyAsLong(timeoutMs);
        CURRENT.set(expires);
        return expires;
    }
    public static void run(long expires, Runnable action, java.util.function.IntToLongFunction extension) {
        var previous=EXTEND.get();EXTEND.set(extension);
        try { run(expires, action); }
        finally { if(previous==null)EXTEND.remove();else EXTEND.set(previous); }
    }
}
