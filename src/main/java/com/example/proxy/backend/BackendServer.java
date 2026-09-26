package com.example.proxy.backend;

/** Address and configured service concurrency, not a routing weight. */
public record BackendServer(String host, int port, int capacity) {
    public BackendServer {
        if (host == null || !host.matches("[a-zA-Z0-9._-]+") || port < 1 || port > 65535
                || capacity < 1 || capacity > 100000)
            throw new IllegalArgumentException("Invalid backend host:port:capacity");
    }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public int getCapacity() { return capacity; }
    public String getId() { return host + ":" + port; }
    public String getBaseUrl() { return "http://" + getId(); }
}
