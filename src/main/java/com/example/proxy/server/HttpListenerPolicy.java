package com.example.proxy.server;

/** JDK process-wide settings. Call before the FIRST HttpServer is created in a JVM.
 * Executor permits/backlog do not bound accepted, zero-byte TCP connections. */
public final class HttpListenerPolicy {
    private HttpListenerPolicy() { }
    public static void installDefaults() {
        limit("jdk.httpserver.maxConnections", 256, 1, 8192);
        limit("sun.net.httpserver.maxIdleConnections", 64, 0, 8192);
        limit("sun.net.httpserver.maxReqHeaders", 128, 1, 1000);
        limit("sun.net.httpserver.maxReqHeaderSize", 65536, 1024, 262144);
    }
    private static void limit(String key,int fallback,int min,int max) {
        String value=System.getProperty(key,Integer.toString(fallback));
        try { int parsed=Integer.parseInt(value);if(parsed<min||parsed>max)throw new NumberFormatException(); }
        catch(NumberFormatException invalid) { throw new IllegalArgumentException(key+" must be bounded: "+min+".."+max); }
        System.setProperty(key,value);
    }
}
