package com.example.proxy.handler;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.server.ReverseProxyServer;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class DownloadBulkheadTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static long deadline(){return System.nanoTime()+TimeUnit.SECONDS.toNanos(3);}
    public static void main(String[] args)throws Exception {
        var disabled=new DownloadBulkhead(0,1,30);try(var ticket=disabled.acquire(deadline())){check(ticket!=null&&!disabled.enabled(),"disabled passes without admission");}
        var gate=new DownloadBulkhead(1,1,1000);var held=gate.acquire(deadline());
        var admitted=new AtomicReference<DownloadBulkhead.Ticket>();var cancelled=new AtomicBoolean();
        Thread waiter=Thread.ofVirtual().start(()->{try{admitted.set(gate.acquire(deadline()));}catch(InterruptedIOException e){cancelled.set(Thread.currentThread().isInterrupted());}});
        long until=deadline();while(gate.waiting()!=1&&System.nanoTime()<until)Thread.sleep(1);
        check(gate.waiting()==1&&gate.acquire(deadline())==null,"finite waiting capacity rejects excess immediately");
        held.close();held.close();waiter.join(2000);check(!waiter.isAlive()&&admitted.get()!=null,"queued request acquires after release");
        admitted.get().close();check(gate.active()==0&&gate.waiting()==0,"double close does not over-release");
        held=gate.acquire(deadline());
        Thread interrupted=Thread.ofVirtual().start(()->{try{gate.acquire(deadline());throw new AssertionError("expected interrupt");}catch(InterruptedIOException e){cancelled.set(Thread.currentThread().isInterrupted());}});
        until=deadline();while(gate.waiting()!=1&&System.nanoTime()<until)Thread.sleep(1);
        interrupted.interrupt();interrupted.join(2000);held.close();
        check(cancelled.get()&&!interrupted.isAlive()&&gate.waiting()==0&&gate.active()==0,"cancellation preserves interruption and releases waiting slot");
        check(gate.acquire(System.nanoTime()-1)==null,"expired deadline does not admit");
        var timed=new DownloadBulkhead(1,2,30);held=timed.acquire(deadline());long began=System.nanoTime();
        check(timed.acquire(deadline())==null,"wait timeout rejects");check(System.nanoTime()-began>=TimeUnit.MILLISECONDS.toNanos(15),"bounded timed wait really waits");held.close();
        var concurrent=new DownloadBulkhead(2,16,30);var peak=new AtomicInteger();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<300;i++)tasks.add(pool.submit(()->{try(var ticket=concurrent.acquire(deadline())){if(ticket!=null){peak.accumulateAndGet(concurrent.active(),Math::max);Thread.sleep(1);}}catch(Exception e){throw new RuntimeException(e);}}));
            for(var task:tasks)task.get(5,TimeUnit.SECONDS);
        }
        check(peak.get()<=2&&concurrent.active()==0&&concurrent.waiting()==0,"concurrent bounded admission and cleanup");
        httpIsolation();System.out.println("DownloadBulkheadTest PASS: bounds, finite wait, cancellation, double close, concurrency, HTTP isolation");
    }
    private static void httpIsolation()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new AtomicBoolean(true);
        var threads=Executors.newVirtualThreadPerTaskExecutor();var backend=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);backend.setExecutor(threads);
        backend.createContext("/",exchange->{try(exchange){
            String path=exchange.getRequestURI().getPath();
            if(path.startsWith("/api/store/documents/")&&first.compareAndSet(true,false)){entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            byte[] body=(path.equals("/health")?"UP":path.equals("/metrics")?"cpuLoad=0\nmemoryLoad=0\nactiveRequests=0\nqueuedRequests=0\ncapacity=4\n":"ok").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
        }});backend.start();
        Properties values=new Properties();values.setProperty("proxy.port","0");values.setProperty("healthcheck.interval.ms","20");values.setProperty("adaptive.warmup.ms","0");
        values.setProperty("healthcheck.recovery.successes","1");values.setProperty("proxy.pdf.bulkhead.max.inflight","1");values.setProperty("proxy.pdf.bulkhead.queue.capacity","1");values.setProperty("proxy.pdf.bulkhead.wait.ms","30");
        var config=new ProxyConfig(values,List.of(new BackendServer("127.0.0.1",backend.getAddress().getPort(),4)));
        try(var proxy=new ReverseProxyServer(config);var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build()){
            proxy.start();long until=deadline();while(!proxy.getDispatcher().hasAvailableBackend()&&System.nanoTime()<until)Thread.sleep(5);
            check(proxy.getDispatcher().hasAvailableBackend(),"backend ready");String base="http://127.0.0.1:"+proxy.getPort();
            var request=HttpRequest.newBuilder(URI.create(base+"/api/store/documents/test")).timeout(Duration.ofSeconds(4)).GET().build();
            var pending=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());check(entered.await(2,TimeUnit.SECONDS),"first document holds bulkhead");
            var rejected=client.send(request,HttpResponse.BodyHandlers.ofString());check(rejected.statusCode()==503&&rejected.headers().firstValue("X-Proxy-Rejection").orElse("").equals("download-bulkhead")&&rejected.headers().firstValue("X-Proxy-Backend").isEmpty(),"PDF overload rejects before lease");
            var work=client.send(HttpRequest.newBuilder(URI.create(base+"/work")).GET().build(),HttpResponse.BodyHandlers.ofString());check(work.statusCode()==200,"other work remains available");
            release.countDown();check(pending.get(3,TimeUnit.SECONDS).statusCode()==200&&client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()==200,"PDF recovers after release");
            var metrics=client.send(HttpRequest.newBuilder(URI.create(base+"/__proxy/metrics")).GET().build(),HttpResponse.BodyHandlers.ofString()).body();
            String counters=metrics.substring(metrics.indexOf("\"downloadBulkhead\":"));counters=counters.substring(0,counters.indexOf('}')+1);
            check(counters.contains("\"active\":0")&&counters.contains("\"waiting\":0")&&counters.contains("\"rejected\":1")&&metrics.contains("\"circuitState\":\"CLOSED\""),"cleanup and healthy circuit after policy rejection");
        }finally{release.countDown();backend.stop(0);threads.close();}
    }
}
