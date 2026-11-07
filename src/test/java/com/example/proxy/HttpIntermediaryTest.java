package com.example.proxy;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.server.ReverseProxyServer;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Raw local TCP requests: intermediary semantics that a browser normalizes away. */
public final class HttpIntermediaryTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var seen = new AtomicReference<Map<String,List<String>>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        var workers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(workers);
        server.createContext("/", e -> { try(e) {
            // Background health/telemetry probes must not overwrite the request
            // whose forwarded headers the foreground assertions inspect.
            if (!e.getRequestURI().getPath().equals("/")) { e.sendResponseHeaders(200, -1); return; }
            e.getRequestBody().readAllBytes(); seen.set(new TreeMap<>(e.getRequestHeaders()));
            byte[] b = "payload".getBytes(StandardCharsets.US_ASCII);
            e.sendResponseHeaders(200, e.getRequestMethod().equals("HEAD") ? -1 : b.length);
            if (!e.getRequestMethod().equals("HEAD")) e.getResponseBody().write(b);
        }}); server.start();
        var p = new Properties(); p.setProperty("proxy.port", "0"); p.setProperty("adaptive.warmup.ms", "0");
        p.setProperty("healthcheck.recovery.successes", "1"); p.setProperty("healthcheck.interval.ms", "50");
        try (var proxy = new ReverseProxyServer(new ProxyConfig(p, List.of(new BackendServer("127.0.0.1", server.getAddress().getPort(), 4))))) {
            proxy.start(); long until = System.nanoTime() + 3_000_000_000L;
            while (!proxy.getDispatcher().hasAvailableBackend() && System.nanoTime() < until) Thread.sleep(10);
            check(proxy.getDispatcher().hasAvailableBackend(), "fixture ready"); int port = proxy.getPort();
            check(raw(port,"GET / HTTP/1.1\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 400"), "missing Host");
            check(raw(port,"GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 400"), "duplicate Host");
            for(String value : List.of("example.com/path","user@example.com","[invalid]","x:abc"))
                check(raw(port,"GET / HTTP/1.1\r\nHost: "+value+"\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 400"), "invalid Host " + value);
            for(String value : List.of("localhost:8080","[::1]:8080","","sub_domain.test"))
                check(raw(port,"GET / HTTP/1.1\r\nHost: "+value+"\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 200"), "valid Host " + value);
            check(raw(port,"GET / HTTP/1.0\r\nConnection: close\r\n\r\n").contains("payload"), "HTTP/1.0 without Host");
            String lower = raw(port,"head / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            check(lower.endsWith("payload"), "method names case sensitive");
            String options = raw(port,"OPTIONS / HTTP/1.1\r\nHost: localhost\r\nMax-Forwards: 0\r\nConnection: close\r\n\r\n");
            check(options.startsWith("HTTP/1.1 204") && !options.toLowerCase().contains("x-proxy-backend"), "OPTIONS terminates locally at zero");
            raw(port,"OPTIONS / HTTP/1.1\r\nHost: localhost\r\nMax-Forwards: 2\r\nConnection: close\r\n\r\n");
            check(seen.get().get("Max-forwards").getFirst().equals("1"), "Max-Forwards decremented");
            String via = raw(port,"POST / HTTP/1.1\r\nHost: localhost\r\nVia: 1.1 prior\r\nExpect: 100-continue\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc");
            check(via.contains("payload") && via.toLowerCase().contains("via: 1.1 pbl4"), "Via response");
            check(!seen.get().containsKey("Expect") && seen.get().get("Via").toString().contains("prior") && seen.get().get("Via").toString().contains("pbl4"), "buffered Expect consumed; Via appended");
            check(raw(port,"TRACE / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 501"), "TRACE rejected without credential reflection");
            long deadline = System.nanoTime()+1_000_000_000L;
            while(proxy.diagnostics().activeHandlers()>0 && System.nanoTime()<deadline)Thread.sleep(10);
            check(proxy.diagnostics().upstreamSockets()==0 && proxy.diagnostics().upstreamDeadlineTasks()==0, "transport cleanup");
        } finally { server.stop(0); workers.close(); }
        System.out.println("HttpIntermediaryTest PASS: " + checks + " real-socket checks");
    }
    private static String raw(int port,String request)throws Exception {
        try(var s=new Socket("127.0.0.1",port)){s.setSoTimeout(3000);s.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));s.getOutputStream().flush();return new String(s.getInputStream().readAllBytes(),StandardCharsets.ISO_8859_1);}
    }
    private static void check(boolean pass,String label){checks++;if(!pass)throw new AssertionError(label);}
}
