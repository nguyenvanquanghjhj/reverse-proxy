package com.example.proxy.server;

import com.example.proxy.config.ProxyConfig;
import com.example.proxy.handler.ProxyHandler;
import com.example.proxy.healthcheck.HealthChecker;
import com.example.proxy.loadbalancer.Dispatcher;
import com.example.proxy.util.AppLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Owns all threads, sockets and monitoring lifecycle for one proxy instance. */
public final class ReverseProxyServer implements AutoCloseable {
    private static final AppLogger log = AppLogger.of(ReverseProxyServer.class);
    private final ProxyConfig config;
    private final Dispatcher dispatcher;
    private final HealthChecker healthChecker;
    private final ProxyHandler handler;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer httpServer;
    private boolean stopped;
    public ReverseProxyServer(ProxyConfig config) {
        this.config = config;
        this.dispatcher = new Dispatcher(config);
        this.healthChecker = new HealthChecker(config, dispatcher);
        this.handler = new ProxyHandler(config, dispatcher);
    }
    public synchronized void start() throws IOException {
        if (stopped || httpServer != null) throw new IllegalStateException("Proxy is already started or closed");
        try {
            httpServer = HttpServer.create(new InetSocketAddress(config.getBindHost(), config.getProxyPort()), config.getMaxInflight());
            httpServer.createContext("/", handler);
            httpServer.createContext("/__proxy/", this::monitor);
            httpServer.setExecutor(executor);
            healthChecker.start();
            httpServer.start();
            log.info("Reverse proxy listening at http://" + config.getBindHost() + ":" + getPort() + " using " + config.getLoadBalancerStrategy());
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    private void monitor(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                ProxyHandler.sendError(exchange, 405, "Method not allowed"); return;
            }
            String body;
            int status = 200;
            if (path.equals("/__proxy/metrics")) {
                if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                    ProxyHandler.sendError(exchange, 403, "Monitoring is loopback-only"); return;
                }
                body = dispatcher.snapshotJson();
            } else if (path.equals("/__proxy/health")) {
                boolean ready = dispatcher.hasAvailableBackend();
                status = ready ? 200 : 503;
                body = "{\"ready\":" + ready + "}";
            } else { ProxyHandler.sendError(exchange, 404, "Unknown proxy endpoint"); return; }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
                exchange.sendResponseHeaders(status, -1);
            } else { exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
        }
    }
    public Dispatcher getDispatcher() { return dispatcher; }
    public synchronized int getPort() { return httpServer == null ? config.getProxyPort() : httpServer.getAddress().getPort(); }
    public void stop() { close(); }
    @Override public synchronized void close() {
        if (stopped) return;
        stopped = true;
        if (httpServer != null) httpServer.stop(1);
        healthChecker.stop();
        handler.close();
        executor.shutdownNow();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
