package com.example.proxy.backend;

/** Address and configured service concurrency, not a routing weight. */
public record BackendServer(String host, int port, int capacity) {
    public BackendServer {
        if (!validHost(host) || port < 1 || port > 65535
                || capacity < 1 || capacity > 100000)
            throw new IllegalArgumentException("Invalid backend host:port:capacity");
    }
    public static boolean validHost(String host) {
        if (host == null || host.isEmpty() || host.length() > 253) return false;
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        for (String label : name.split("\\.", -1))
            if (!label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) return false;
        if (name.matches("[0-9.]+")) {
            String[] octets = name.split("\\.", -1);
            if (octets.length != 4) return false;
            for (String octet : octets)
                if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public int getCapacity() { return capacity; }
    public String getId() { return host + ":" + port; }
    public String getBaseUrl() { return "http://" + getId(); }
}
