package com.example.proxy;

import com.example.proxy.config.ConfigLoader;
import com.example.proxy.server.ReverseProxyServer;
import com.example.shop.ShopConfig;
import com.example.store.StoreApplication;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/** Actual Store HTTP routes with a schema-only JDBC fixture, never a live database. */
public final class StoreConditionalHttpTest {
    private static final String JDBC = "jdbc:postgresql:pbl4_conditional_fixture";

    private static Object stub(Class<?> type, java.lang.reflect.InvocationHandler handler) {
        return Proxy.newProxyInstance(StoreConditionalHttpTest.class.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private static Object connectionMethod(Method method) {
        return switch (method.getName()) {
            case "getAutoCommit", "isValid" -> true;
            case "isClosed", "isReadOnly" -> false;
            case "close", "clearWarnings", "rollback", "setAutoCommit", "setReadOnly" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }
    private static final class SchemaDriver implements Driver {
        public boolean acceptsURL(String url) { return JDBC.equals(url); }
        public Connection connect(String url, Properties properties) {
            if (!acceptsURL(url)) return null;
            return (Connection) stub(Connection.class, (connection, method, args) -> {
                if (!method.getName().equals("prepareStatement")) return connectionMethod(method);
                if (!args[0].equals("SELECT version FROM shop_schema_version WHERE version=5"))
                    throw new AssertionError("Unexpected database access: " + args[0]);
                return stub(PreparedStatement.class, (statement, action, values) -> {
                    if (action.getName().equals("close")) return null;
                    if (!action.getName().equals("executeQuery")) throw new AssertionError(action.getName());
                    boolean[] first = {true};
                    return stub(ResultSet.class, (result, operation, arguments) -> switch (operation.getName()) {
                        case "next" -> { boolean available = first[0]; first[0] = false; yield available; }
                        case "getObject" -> 5;
                        case "close" -> null;
                        case "getMetaData" -> stub(ResultSetMetaData.class, (metadata, field, index) -> switch (field.getName()) {
                            case "getColumnCount" -> 1;
                            case "getColumnLabel" -> "version";
                            default -> throw new AssertionError(field.getName());
                        });
                        default -> throw new AssertionError(operation.getName());
                    });
                });
            });
        }
        public int getMajorVersion() { return 1; }
        public int getMinorVersion() { return 0; }
        public boolean jdbcCompliant() { return false; }
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) { return new DriverPropertyInfo[0]; }
        public Logger getParentLogger() { return Logger.getGlobal(); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static HttpResponse<byte[]> request(HttpClient client, int port, String path, String method, String condition) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (condition != null) builder.header("If-None-Match", condition);
        return client.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static void verify(HttpClient client, int port) throws Exception {
        String asset = "/store/assets/app.js";
        var initial = request(client, port, asset, "GET", null);
        check(initial.statusCode() == 200 && initial.body().length > 0, "existing asset GET");
        check(initial.headers().firstValue("ETag").isEmpty(), "wildcard fix must not introduce ETags");
        for (String method : new String[]{"GET", "HEAD"}) {
            var conditional = request(client, port, asset, method, " * ");
            check(conditional.statusCode() == 304, method + " existing asset wildcard expected 304, got " + conditional.statusCode());
            check(conditional.body().length == 0, method + " 304 must not have a body");
            var missing = request(client, port, "/store/assets/not-present.js", method, "*");
            check(missing.statusCode() == 404, method + " missing asset must remain 404");
            if (method.equals("HEAD")) check(missing.body().length == 0, "missing HEAD has no body");
            var ordinary = request(client, port, asset, method, "\"unrecognized-tag\"");
            check(ordinary.statusCode() == 200, "unsupported entity-tag condition retains existing behavior");
            var html = request(client, port, "/shop", method, "*");
            check(html.statusCode() == 200, "private HTML route is outside the static asset contract");
            check(html.headers().firstValue("Cache-Control").orElse("").equals("no-store"), "private cache policy unchanged");
            var api = request(client, port, "/api/store/policy", method, "*");
            check(api.statusCode() == 200, "API condition behavior unchanged");
        }
    }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("target"));
        Path files = Files.createTempDirectory(Path.of("target"), "store-conditional-").toAbsolutePath().normalize();
        var driver = new SchemaDriver();
        DriverManager.registerDriver(driver);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 32);
        try {
            var application = new StoreApplication(new ShopConfig(JDBC, "fixture", "", files, 262144, "", 2));
            backend.setExecutor(executor);
            backend.createContext("/", exchange -> { try (exchange) { application.handle(exchange); } });
            backend.createContext("/health", exchange -> { try (exchange) { exchange.sendResponseHeaders(200, -1); } });
            backend.createContext("/metrics", exchange -> { try (exchange) {
                byte[] body = "activeRequests=0\nqueuedRequests=0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } });
            backend.start();
            Properties properties = new Properties();
            properties.setProperty("proxy.port", "0");
            properties.setProperty("backend.servers", "127.0.0.1:" + backend.getAddress().getPort() + ":8");
            properties.setProperty("proxy.capability-routing.enabled", "false");
            properties.setProperty("healthcheck.interval.ms", "20");
            properties.setProperty("healthcheck.recovery.successes", "1");
            properties.setProperty("adaptive.warmup.ms", "0");
            properties.setProperty("proxy.shutdown.grace.seconds", "0");
            try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
                 var proxy = new ReverseProxyServer(ConfigLoader.load(properties))) {
                proxy.start();
                long limit = System.nanoTime() + 5_000_000_000L;
                while (!proxy.getDispatcher().hasAvailableBackend()) {
                    check(System.nanoTime() < limit, "fixture health timeout"); Thread.sleep(20);
                }
                verify(client, backend.getAddress().getPort());
                verify(client, proxy.getPort());
            }
            System.out.println("StoreConditionalHttpTest PASS: direct and proxied GET/HEAD, existing/missing assets, private routes unchanged");
        } finally {
            backend.stop(0); executor.close(); DriverManager.deregisterDriver(driver);
        }
    }
}
