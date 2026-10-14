package com.example.proxy;

import com.example.backend.DemoBackendServer;
import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.handler.ClientRateLimiter;
import com.example.proxy.server.ReverseProxyServer;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class RateLimiterTest {
    public static void main(String[] args) throws Exception {
        boundaries();
        Properties p = settings(); p.setProperty("proxy.rate-limit.requests-per-second","2");p.setProperty("proxy.rate-limit.max-clients","2");
        p.setProperty("proxy.rate-limit.idle.ms","1000");
        var clock=new AtomicLong();var limiter=new ClientRateLimiter(config(p,9001),clock::get);
        check(limiter.acquire("a").allowed() && limiter.acquire("a").allowed(),"burst");
        check(!limiter.acquire("a").allowed() && limiter.acquire("b").allowed(),"independent client quotas");
        check(!limiter.acquire("c").allowed(),"bounded client table");
        clock.addAndGet(500_000_000);check(limiter.acquire("a").allowed(),"fractional refill");
        check(!limiter.acquire("a").allowed(),"no extra refill");
        clock.addAndGet(1_000_000_000);check(limiter.acquire("c").allowed(),"idle eviction");
        try(var tasks=Executors.newVirtualThreadPerTaskExecutor()) {
            var shared=new ClientRateLimiter(config(p,9001),clock::get);List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<64;i++)results.add(tasks.submit(()->shared.acquire("same").allowed()));
            int allowed=0;for(var result:results)if(result.get(2,TimeUnit.SECONDS))allowed++;
            check(allowed==2,"atomic quota under concurrency");
        }
        p.setProperty("proxy.rate-limit.enabled","false");var off=new ClientRateLimiter(config(p,9001));
        for(int i=0;i<100;i++)check(off.acquire("x").allowed(),"disabled compatibility");
        p.setProperty("proxy.rate-limit.idle.ms","1");
        try { config(p,9001);throw new AssertionError("idle eviction could bypass quota"); }catch(IllegalArgumentException expected) { }
        integration();
        System.out.println("RateLimiterTest PASS: quota, refill, eviction, concurrency, spoofing, HTTP 429, cleanup, management exemption");
    }
    private static void integration() throws Exception {
        try(var backend=new DemoBackendServer(0,4,0,0,4)) {
            backend.start();Properties p=settings();p.setProperty("proxy.rate-limit.requests-per-second","0.1");
            p.setProperty("proxy.rate-limit.idle.ms","20000");
            try(var proxy=new ReverseProxyServer(config(p,backend.getPort()));var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
                proxy.start();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
                while(!proxy.getDispatcher().hasAvailableBackend()){check(System.nanoTime()<until,"ready timeout");Thread.sleep(10);}
                String base="http://127.0.0.1:"+proxy.getPort();
                for(int i=0;i<4;i++) {
                    var response=client.send(HttpRequest.newBuilder(URI.create(base+"/work?cost=1")).header("X-Forwarded-For","fake-"+i)
                            .header("X-Request-Id","rate-"+i).timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofString());
                    check(response.statusCode()==(i<2?200:429),"peer IP cannot be spoofed");
                    if(i>=2)check(response.headers().firstValue("Retry-After").isPresent() && response.body().contains("RATE_LIMITED") && response.body().contains("rate-"+i),"429 contract");
                }
                var metrics=client.send(HttpRequest.newBuilder(URI.create(base+"/__proxy/metrics")).timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofString());
                check(metrics.statusCode()==200 && metrics.body().contains("\"rate_limit_rejected\":2"),"management exempt and counters available");
                check(proxy.getDispatcher().snapshots().getFirst().dispatched()==2,"rejections never get backend lease");
                check(proxy.getDispatcher().snapshots().getFirst().inFlight()==0,"rate rejection has no backend in-flight");
                check(proxy.getDispatcher().snapshotJson().contains("\"circuitFailures\":0"),"proxy 429 never poisons backend");
                until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(proxy.diagnostics().activeHandlers()!=0){check(System.nanoTime()<until,"cleanup timeout");Thread.sleep(5);}
                check(proxy.diagnostics().admissionAvailable()==128 && proxy.diagnostics().upstreamSockets()==0,"resources returned");
            }
        }
    }
    private static void boundaries() {
        Properties p = settings(); p.setProperty("proxy.rate-limit.burst", "1");
        p.setProperty("proxy.rate-limit.requests-per-second", "0.001");
        p.setProperty("proxy.rate-limit.max-clients", "1"); p.setProperty("proxy.rate-limit.idle.ms", "1000000");
        var clock = new AtomicLong(); var limiter = new ClientRateLimiter(config(p, 9001), clock::get);
        check(limiter.acquire("127.0.0.1").allowed(), "burst one");
        for (int i = 0; i < 10; i++) {
            var denied = limiter.acquire("127.0.0.1");
            check(!denied.allowed() && denied.retryAfterSeconds() == 1000, "low rate retry has no token debt");
            check(!limiter.acquire("::1").allowed(), "IPv6 cannot evict active IPv4 quota");
        }
        clock.addAndGet(999_999_999_999L);
        var almost = limiter.acquire("127.0.0.1");
        check(!almost.allowed() && almost.retryAfterSeconds() == 1, "Retry-After rounds up");
        clock.incrementAndGet(); check(limiter.acquire("127.0.0.1").allowed(), "fractional refill eventually grants exactly one");
        clock.addAndGet(1_000_000_000_000L); check(limiter.acquire("::1").allowed(), "idle IPv4 expires for IPv6");
        var denied = limiter.acquire("::1"); check(!denied.allowed() && denied.retryAfterSeconds() >= 1, "IPv6 quota");
    }
    private static Properties settings(){Properties p=new Properties();p.setProperty("proxy.port","0");p.setProperty("proxy.shutdown.grace.seconds","0");p.setProperty("healthcheck.recovery.successes","1");p.setProperty("adaptive.warmup.ms","0");p.setProperty("proxy.rate-limit.enabled","true");p.setProperty("proxy.rate-limit.burst","2");return p;}
    private static ProxyConfig config(Properties p,int port){return new ProxyConfig(p,List.of(new BackendServer("127.0.0.1",port,4)));}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
