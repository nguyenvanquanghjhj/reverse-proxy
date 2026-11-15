package com.example.proxy;

import com.example.proxy.config.ConfigLoader;
import com.example.proxy.loadbalancer.CapabilityRouting;
import com.example.proxy.loadbalancer.Dispatcher;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/** Tests fail-closed capability filtering without relying on live PostgreSQL. */
public final class CapabilityRoutingTest {
    private static CapabilityRouting.Status state(String catalog,String orders) {
        return CapabilityRouting.parse(("{\"status\":\"DEGRADED\",\"dependencies\":{\"capabilities\":{"
                + "\"static_files\":\"AVAILABLE\",\"catalog\":\""+catalog
                + "\",\"order_create\":\""+orders
                + "\",\"document_download\":\"AVAILABLE\",\"document_upload\":\"AVAILABLE\",\"file_read\":\"UNAVAILABLE\"}}}")
                .getBytes(StandardCharsets.UTF_8));
    }
    private static void assertTrue(boolean b,String message) { if(!b)throw new AssertionError(message); }
    public static void main(String[] args) {
        assertTrue("order_create".equals(CapabilityRouting.required("POST", URI.create("/api/store/orders"))),"orders requirement");
        assertTrue("file_read".equals(CapabilityRouting.required("GET",URI.create("/api/store/images/id"))),"image storage");
        assertTrue(CapabilityRouting.required("GET",URI.create("/work?cost=1"))==null,"independent workload");
        var fakeClock=new AtomicLong(5_000_000_000L);
        var props=new Properties();
        props.setProperty("backend.servers","127.0.0.1:19001:8,127.0.0.1:19002:8");
        props.setProperty("proxy.capability-routing.enabled","true");
        props.setProperty("healthcheck.recovery.successes","1");
        props.setProperty("adaptive.warmup.ms","0");
        props.setProperty("loadbalancer.strategy","ROUND_ROBIN");
        var config=ConfigLoader.load(props);
        var b1=config.getBackendServers().get(0);
        var b2=config.getBackendServers().get(1);
        var scheduler=new Dispatcher(config,fakeClock::get);
        scheduler.recordHealth(b1,scheduler.beginProbe(b1),true);
        scheduler.recordHealth(b2,scheduler.beginProbe(b2),true);
        scheduler.recordCapabilities(b1,scheduler.beginProbe(b1),state("AVAILABLE","UNAVAILABLE"));
        scheduler.recordCapabilities(b2,scheduler.beginProbe(b2),state("AVAILABLE","AVAILABLE"));
        var order=scheduler.acquire("POST",URI.create("/api/store/orders"));
        assertTrue(order != null && order.backend().equals(b2),"DB-loss replica must not receive order");
        order.completeHttp(2,201);
        try(order) { }
        var staticRequest=scheduler.acquire("GET",URI.create("/shop"));
        assertTrue(staticRequest!=null,"static content remains routable");
        staticRequest.completeHttp(2,200);
        try(staticRequest) { }
        // All available backends become ineligible for database routes when their probe expires.
        fakeClock.addAndGet(5_000_000_000L);
        assertTrue(scheduler.acquire("POST",URI.create("/api/store/orders"))==null,"stale must fail closed");
        assertTrue(state("AVAILABLE","PAUSED").withObservedAt(fakeClock.get())
                .supports("order_create",fakeClock.get(),1000),"business pause must reach HTTP 423");
        assertTrue(!CapabilityRouting.Status.unknown().supports("catalog",fakeClock.get(),1000),"unknown must fail closed");
        System.out.println("CapabilityRoutingTest PASS");
    }
}
