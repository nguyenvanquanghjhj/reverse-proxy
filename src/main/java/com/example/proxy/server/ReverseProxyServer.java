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

/** Owns all threads, sockets and monitoring lifecycle for one proxy instance. */
public final class ReverseProxyServer implements AutoCloseable {
    private static final AppLogger log = AppLogger.of(ReverseProxyServer.class);
    private final ProxyConfig config;
    private final Dispatcher dispatcher;
    private final HealthChecker healthChecker;
    private final ProxyHandler handler;
    private final BoundedRequestExecutor executor;
    private final BoundedRequestExecutor managementExecutor;
    private HttpServer httpServer;
    private HttpServer managementServer;
    private boolean stopped, starting;
    public ReverseProxyServer(ProxyConfig config) {
        this.config = config;
        this.executor = new BoundedRequestExecutor(config.getMaxHandlerTasks(), config.getRequestTimeoutMs());
        this.managementExecutor = config.isManagementEnabled()
                ? new BoundedRequestExecutor(config.getManagementMaxTasks(), config.getRequestTimeoutMs()) : null;
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
        HttpServer management = null;
        try {
            // DNS and bind are outside the lifecycle lock. close() may win while they run.
            created = HttpServer.create(new InetSocketAddress(config.getBindHost(), config.getProxyPort()), config.getMaxHandlerTasks());
            created.createContext("/", handler);
            created.createContext("/__proxy/", e -> handler.handleManagement(e, this::monitor));
            created.setExecutor(executor);
            if (managementExecutor != null) {
                // Fixed loopback bind: this listener is never a second data proxy.
                management = HttpServer.create(new InetSocketAddress("127.0.0.1", config.getManagementPort()), config.getManagementMaxTasks());
                management.createContext("/", e -> handler.handleManagement(e, this::monitor));
                management.setExecutor(managementExecutor);
            }
            synchronized (this) {
                if (stopped) throw new IllegalStateException("Proxy closed during startup");
                httpServer = created;
                managementServer = management;
                healthChecker.start();
                httpServer.start();
                if (managementServer != null) managementServer.start();
            }
            log.info("Reverse proxy listening at http://" + config.getBindHost() + ":" + getPort() + " using " + config.getLoadBalancerStrategy());
        } catch (IOException | RuntimeException e) {
            // A bound-but-unstarted JDK HttpServer has no dispatcher to process selector
            // deregistration. Start it only for cleanup with admission already disabled.
            executor.stopAccepting();
            if (managementExecutor != null) managementExecutor.stopAccepting();
            disposeCreated(management);
            disposeCreated(created);
            close();
            throw e;
        }
    }
    private static void disposeCreated(HttpServer server) {
        if (server == null) return;
        try { server.start(); }
        catch (IllegalStateException alreadyStartedOrStopped) { }
        server.stop(0);
    }
    private void monitor(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                ProxyHandler.sendError(exchange, 403, "Monitoring is loopback-only"); return;
            }
            String path = exchange.getRequestURI().getPath();
            String body;
            int status = 200;
            if (path.equals("/__proxy/strategy")) {
                if (!exchange.getRequestMethod().equals("POST")) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    ProxyHandler.sendError(exchange, 405, "Method not allowed"); return;
                }
                if (!"cycle-strategy".equals(exchange.getRequestHeaders().getFirst("X-Proxy-Action"))) {
                    ProxyHandler.sendError(exchange, 403, "Strategy changes require the dashboard action header"); return;
                }
                String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
                if (exchange.getRequestHeaders().containsKey("Transfer-Encoding")
                        || (contentLength != null && !contentLength.equals("0"))) {
                    ProxyHandler.sendError(exchange, 400, "Strategy action does not accept a request body"); return;
                }
                String strategy = dispatcher.cycleStrategy();
                body = "{\"strategy\":\"" + strategy + "\"}";
            } else {
                if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                    ProxyHandler.sendError(exchange, 405, "Method not allowed"); return;
                }
                if (path.equals("/__proxy/dashboard")) {
                    body = MonitoringPage.HTML.replace("@@DATA_PORT@@", Integer.toString(getPort()));
                } else if (path.equals("/__proxy/metrics")) {
                    String snapshot = dispatcher.snapshotJson();
                    body = snapshot.substring(0, snapshot.length() - 1) + ",\"backendRuntime\":" + dispatcher.backendRuntimeJson()
                        + "," + handler.countersJson()
                            + ",\"health_probe_failures\":" + healthChecker.healthFailures()
                            + ",\"data_ingress_tasks\":" + getDataIngressTasks()
                            + ",\"management_ingress_tasks\":" + getManagementIngressTasks()
                            + ",\"telemetry_probe_failures\":" + healthChecker.telemetryFailures() + "}";
                } else if (path.equals("/__proxy/decision")) {
                    body = dispatcher.decisionJson();
                } else if (path.equals("/__proxy/prometheus")) {
                    body = handler.prometheus() + dispatcher.prometheus()
                            + "# TYPE proxy_data_ingress_tasks gauge\nproxy_data_ingress_tasks " + getDataIngressTasks() + "\n"
                            + "# TYPE proxy_management_ingress_tasks gauge\nproxy_management_ingress_tasks " + getManagementIngressTasks() + "\n";
                } else if (path.equals("/__proxy/health")) {
                    boolean ready = dispatcher.hasAvailableBackend() && handler.diagnostics().admissionAvailable() > 0 && executor.hasCapacity();
                    status = ready ? 200 : 503;
                    body = "{\"ready\":" + ready + "}";
                } else { ProxyHandler.sendError(exchange, 404, "Unknown proxy endpoint"); return; }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", path.equals("/__proxy/dashboard") ? "text/html; charset=utf-8"
                    : path.equals("/__proxy/prometheus") ? "text/plain; version=0.0.4; charset=utf-8" : "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
                exchange.sendResponseHeaders(status, -1);
            } else { exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
        }
    }
    public ProxyHandler.Diagnostics diagnostics() { return handler.diagnostics(); }
    public int getDataIngressTasks() { return executor.activeTasks(); }
    public int getManagementIngressTasks() { return managementExecutor == null ? 0 : managementExecutor.activeTasks(); }
    public Dispatcher getDispatcher() { return dispatcher; }
    public synchronized int getPort() { return httpServer == null ? config.getProxyPort() : httpServer.getAddress().getPort(); }
    public synchronized int getManagementPort() {
        return managementExecutor == null ? -1 : managementServer == null ? config.getManagementPort() : managementServer.getAddress().getPort();
    }
    public void stop() { close(); }
    @Override public void close() {
        HttpServer server;
        HttpServer management;
        synchronized (this) {
            if (stopped) return;
            stopped = true;
            server = httpServer;
            management = managementServer;
        }
        executor.stopAccepting();
        if (managementExecutor != null) managementExecutor.stopAccepting();
        dispatcher.stopAccepting();
        healthChecker.stop();
        if (management != null) management.stop(0);
        // HttpServer closes its listener first, then waits at most this grace for existing exchanges.
        if (server != null) server.stop(config.getShutdownGraceSeconds());
        handler.close();
        executor.close();
        if (managementExecutor != null) managementExecutor.close();
    }
}
