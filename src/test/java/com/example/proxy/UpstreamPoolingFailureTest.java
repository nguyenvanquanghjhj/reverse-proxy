package com.example.proxy;

import com.example.proxy.config.ConfigLoader;
import com.example.proxy.handler.UpstreamTransport;
import com.example.proxy.server.ReverseProxyServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real TCP faults with pooling ON: no retry, no poisoned reuse, bounded cleanup and idle expiry. */
public final class UpstreamPoolingFailureTest {
    private static void check(boolean value,String label) { if(!value) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        try(var server=new Fixture()) {
            var p=new Properties();
            p.setProperty("backend.servers","127.0.0.1:"+server.port()+":8");
            p.setProperty("proxy.connection-pool.enabled","true");
            p.setProperty("proxy.connection-pool.idle-ttl.ms","500");
            p.setProperty("proxy.read.timeout.ms","1000");
            p.setProperty("proxy.request.timeout.ms","250");
            try(var transport=new UpstreamTransport(ConfigLoader.load(p))) {
                good(transport,server,"/stale");
                server.closedAfterResponse.await(2,TimeUnit.SECONDS);
                int accepted=server.accepted.get();
                failure(transport,server,"GET","/after-stale",new byte[0]);
                check(server.accepted.get()==accepted,"stale GET must not be retried automatically");
                clean(transport); good(transport,server,"/ok");
                for(String path:List.of("/truncated","/ambiguous","/timeout")) {
                    long began=System.nanoTime();
                    failure(transport,server,"GET",path,new byte[0]);
                    check(System.nanoTime()-began<TimeUnit.SECONDS.toNanos(2),"fault bounded by request deadline: "+path);
                    clean(transport); good(transport,server,"/ok");
                }
                good(transport,server,"/eof");
                accepted=server.accepted.get();good(transport,server,"/ok");
                check(server.accepted.get()==accepted+1,"EOF-delimited response must force a new socket");
                failure(transport,server,"POST","/commit-then-close",new byte[]{1});
                check(server.posts.get()==1,"unknown POST outcome must never be replayed");
                clean(transport);
                // Idle expiration is swept every five seconds, independently of traffic.
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
                while(!transport.connectionPoolJson().contains("\"idle\":0") && System.nanoTime()<deadline) Thread.sleep(25);
                check(transport.connectionPoolJson().contains("\"idle\":0"),"idle TTL closes pooled sockets without another request");
                good(transport,server,"/ok");
                transport.close();
                check(transport.activeSocketCount()==0 && transport.connectionPoolJson().contains("\"idle\":0"),"shutdown closes active and idle sockets");
            }
            downstreamDisconnect(server,p);
        }
        System.out.println("UpstreamPoolingFailureTest PASS: stale, truncated, ambiguous, EOF, timeout, POST no-retry, TTL, downstream disconnect and shutdown");
    }
    private static void downstreamDisconnect(Fixture server,Properties settings) throws Exception {
        var p=new Properties();p.putAll(settings);
        try(var free=new ServerSocket(0)) {p.setProperty("proxy.port",Integer.toString(free.getLocalPort()));}
        p.setProperty("loadbalancer.strategy","ROUND_ROBIN");
        p.setProperty("healthcheck.interval.ms","100");
        p.setProperty("healthcheck.recovery.successes","1");
        p.setProperty("adaptive.warmup.ms","250");
        try(var proxy=new ReverseProxyServer(ConfigLoader.load(p))) {
            proxy.start();
            await(()->proxy.getDispatcher().snapshots().getFirst().state().equals("UP"),"backend health and warmup");
            int slow=server.slowRequests.get();
            try(var downstream=new Socket("127.0.0.1",proxy.getPort())) {
                downstream.getOutputStream().write(("GET /timeout HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                downstream.getOutputStream().flush();
                await(()->server.slowRequests.get()>slow,"upstream exchange started before downstream disconnect");
            }
            await(()->proxy.diagnostics().activeHandlers()==0 && proxy.diagnostics().upstreamSockets()==0
                      && proxy.getDispatcher().snapshots().getFirst().inFlight()==0,"disconnect releases handler, socket and backend lease");
            // A transport timeout intentionally marks this backend DOWN until a fresh probe recovers it.
            await(()->proxy.getDispatcher().snapshots().getFirst().state().equals("UP"),"backend probe recovery after timeout");
            try(var client=java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build()) {
                var response=client.send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+proxy.getPort()+"/ok"))
                        .timeout(java.time.Duration.ofSeconds(3)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
                check(response.statusCode()==200 && response.body().equals("PBL4"),"pooled proxy serves clean bytes after downstream disconnect: HTTP "+response.statusCode()+" "+proxy.getDispatcher().snapshotJson());
            }
        }
    }
    private static void await(java.util.function.BooleanSupplier condition,String label) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()) {
            if(System.nanoTime()>deadline)throw new AssertionError(label);
            Thread.sleep(10);
        }
    }
    private static void good(UpstreamTransport t,Fixture f,String path) throws IOException {
        var response=t.exchange("127.0.0.1",f.port(),"GET",URI.create(path),Map.of(),new byte[0]);
        check(response.status()==200 && Arrays.equals(response.body(),new byte[]{80,66,76,52}),"intact response after fault: "+path);
    }
    private static void failure(UpstreamTransport t,Fixture f,String method,String path,byte[] body) throws IOException {
        try { t.exchange("127.0.0.1",f.port(),method,URI.create(path),Map.of(),body); }
        catch(IOException expected) { return; }
        throw new AssertionError("Expected transport failure: "+path);
    }
    private static void clean(UpstreamTransport t) {
        check(t.activeSocketCount()==0,"exchange socket released");
        check(t.deadlineTaskCount()<=1,"only the periodic idle sweeper may remain queued");
    }
    private static final class Fixture implements AutoCloseable {
        final ServerSocket listener;
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        final AtomicInteger accepted=new AtomicInteger(),posts=new AtomicInteger(),slowRequests=new AtomicInteger();
        final CountDownLatch closedAfterResponse=new CountDownLatch(1);
        final Thread accepting;
        Fixture() throws IOException {
            listener=new ServerSocket(0,32,InetAddress.getLoopbackAddress());
            accepting=Thread.ofVirtual().start(()->{
                while(!listener.isClosed()) try {
                    Socket socket=listener.accept();sockets.add(socket);accepted.incrementAndGet();
                    Thread.ofVirtual().start(()->serve(socket));
                } catch(IOException closed) { break; }
            });
        }
        int port() { return listener.getLocalPort(); }
        void serve(Socket socket) {
            try(socket) {
                socket.setSoTimeout(10000);
                var in=socket.getInputStream();var out=socket.getOutputStream();
                while(true) {
                    var request=new ByteArrayOutputStream();int value;
                    while((value=in.read())!=-1) {
                        request.write(value);if(request.size()>8192)return;
                        if(request.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;
                    }
                    if(value<0)return;
                    String head=request.toString(StandardCharsets.US_ASCII),path=head.split(" ")[1];
                    int bytes=0;
                    for(String line:head.split("\r\n")) if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                        bytes=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                    if(in.readNBytes(bytes).length!=bytes)return;
                    if(path.equals("/commit-then-close")) {posts.incrementAndGet();return;}
                    if(path.equals("/timeout")) {slowRequests.incrementAndGet();Thread.sleep(1000);return;}
                    String response=switch(path) {
                        case "/truncated" -> "HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\nPBL4";
                        case "/ambiguous" -> "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\nPBL4";
                        case "/eof" -> "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nPBL4";
                        default -> "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: keep-alive\r\n\r\nPBL4";
                    };
                    out.write(response.getBytes(StandardCharsets.US_ASCII));out.flush();
                    if(Set.of("/stale","/truncated","/ambiguous","/eof").contains(path))return;
                }
            } catch(IOException ignored) { }
            catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
            finally { sockets.remove(socket);closedAfterResponse.countDown(); }
        }
        public void close() throws Exception {
            listener.close();for(var socket:sockets)socket.close();accepting.join(1000);
        }
    }
}
