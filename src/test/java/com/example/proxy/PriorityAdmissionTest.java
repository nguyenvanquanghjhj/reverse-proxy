package com.example.proxy;

import com.example.proxy.handler.PriorityAdmission;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class PriorityAdmissionTest {
    public static void main(String[] args) throws Exception {
        var admission = new PriorityAdmission(4, 1);
        var n = new ArrayList<PriorityAdmission.Ticket>();
        for (int i = 0; i < 3; i++) {
            var ticket = admission.tryAcquire("GET", "/api/store/products");
            if (ticket == null) throw new AssertionError("normal not admitted");
            n.add(ticket);
        }
        if (admission.tryAcquire("GET", "/api/store/products") != null)
            throw new AssertionError("normal traffic consumed reserved slot");
        var order = admission.tryAcquire("POST", "/api/store/orders");
        if (order == null || admission.availablePermits() != 0)
            throw new AssertionError("critical order was not protected");
        if (admission.tryAcquire("POST", "/api/store/orders") != null)
            throw new AssertionError("over-capacity critical order admitted");
        order.close(); order.close();
        if (admission.availablePermits() != 1) throw new AssertionError("double release");
        for (var t : n) t.close();
        if (admission.availablePermits() != 4) throw new AssertionError("permits leaked");
        if (PriorityAdmission.isCritical("GET", "/api/store/orders") ||
            PriorityAdmission.isCritical("POST", "/api/store/orders/123") ||
            PriorityAdmission.isCritical("post", "/api/store/orders"))
            throw new AssertionError("unexpected priority promotion");

        var concurrent = new PriorityAdmission(9, 2);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(16);
        var error = new AtomicReference<Throwable>();
        for (int i = 0; i < 16; i++) {
            final boolean critical = i % 4 == 0;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 3000; j++) {
                        var ticket = concurrent.tryAcquire(critical ? "POST" : "GET",
                            critical ? "/api/store/orders" : "/api/store/products");
                        if (ticket != null) ticket.close();
                    }
                } catch (Throwable failure) { error.compareAndSet(null, failure); }
                finally { done.countDown(); }
            });
        }
        start.countDown(); done.await();
        if (error.get() != null) throw new AssertionError("concurrency failure", error.get());
        if (concurrent.availablePermits() != 9) throw new AssertionError("concurrency leak");
        System.out.println("PriorityAdmissionTest PASS");
    }
}
