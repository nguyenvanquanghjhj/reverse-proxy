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
    private boolean stopped, starting;
    public ReverseProxyServer(ProxyConfig config) {
        this.config = config;
        this.dispatcher = new Dispatcher(config);
        this.healthChecker = new HealthChecker(config, dispatcher);
        this.handler = new ProxyHandler(config, dispatcher);
    }
    public void start() throws IOException {
        synchronized (this) {
            if (stopped || starting || httpServer != null) throw new IllegalStateException("Proxy is already started or closed");
            starting = true;
        }
        HttpServer created = null;
        try {
            // DNS and bind are outside the lifecycle lock. close() may win while they run.
            created = HttpServer.create(new InetSocketAddress(config.getBindHost(), config.getProxyPort()), config.getMaxInflight());
            created.createContext("/", handler);
            created.createContext("/__proxy/", e -> handler.handleManagement(e, this::monitor));
            created.setExecutor(executor);
            synchronized (this) {
                if (stopped) throw new IllegalStateException("Proxy closed during startup");
                httpServer = created;
                healthChecker.start();
                httpServer.start();
            }
            log.info("Reverse proxy listening at http://" + config.getBindHost() + ":" + getPort() + " using " + config.getLoadBalancerStrategy());
        } catch (IOException | RuntimeException e) {
            if (created != null) created.stop(0);
            close();
            throw e;
        }
    }
    private void monitor(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                ProxyHandler.sendError(exchange, 403, "Monitoring is loopback-only"); return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                ProxyHandler.sendError(exchange, 405, "Method not allowed"); return;
            }
            String body;
            int status = 200;
            if (path.equals("/__proxy/metrics")) {
                String snapshot = dispatcher.snapshotJson();
                body = snapshot.substring(0, snapshot.length() - 1) + "," + handler.countersJson()
                        + ",\"health_probe_failures\":" + healthChecker.healthFailures()
                        + ",\"telemetry_probe_failures\":" + healthChecker.telemetryFailures() + "}";
            } else if (path.equals("/__proxy/prometheus")) {
                body = handler.prometheus() + dispatcher.prometheus();
            } else if (path.equals("/__proxy/health")) {
                boolean ready = dispatcher.hasAvailableBackend() && handler.diagnostics().admissionAvailable() > 0;
                status = ready ? 200 : 503;
                body = "{\"ready\":" + ready + "}";
            } else { ProxyHandler.sendError(exchange, 404, "Unknown proxy endpoint"); return; }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", path.equals("/__proxy/prometheus") ? "text/plain; version=0.0.4; charset=utf-8" : "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
                exchange.sendResponseHeaders(status, -1);
            } else { exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
        }
    }
    public ProxyHandler.Diagnostics diagnostics() { return handler.diagnostics(); }
    public Dispatcher getDispatcher() { return dispatcher; }
    public synchronized int getPort() { return httpServer == null ? config.getProxyPort() : httpServer.getAddress().getPort(); }
    public void stop() { close(); }
    @Override public void close() {
        HttpServer server;
        synchronized (this) {
            if (stopped) return;
            stopped = true;
            server = httpServer;
        }
        // HttpServer closes its listener first, then waits at most this grace for existing exchanges.
        if (server != null) server.stop(config.getShutdownGraceSeconds());
        handler.close();
        healthChecker.stop();
        executor.shutdownNow();
        try { if (!executor.awaitTermination(2, TimeUnit.SECONDS)) log.warn("handler_executor_shutdown_timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
