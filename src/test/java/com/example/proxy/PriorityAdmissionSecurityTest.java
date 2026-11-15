package com.example.proxy;

import com.example.proxy.handler.PriorityAdmission;

/** Per-peer cap bounds QoS abuse without leaking permits or permitting header self-promotion. */
public final class PriorityAdmissionSecurityTest {
    public static void main(String[] args) {
        var queue = new PriorityAdmission(8,2,2);
        var a1=queue.tryAcquire("POST","/api/store/orders","10.0.0.1");
        var a2=queue.tryAcquire("POST","/api/store/orders","10.0.0.1");
        if (a1==null || a2==null)throw new AssertionError("two orders should be admitted");
        if (queue.tryAcquire("POST","/api/store/orders","10.0.0.1")!=null)
            throw new AssertionError("one peer exhausted per-peer allowance");
        var b=queue.tryAcquire("POST","/api/store/orders","10.0.0.2");
        if (b==null)throw new AssertionError("different peer should be admitted");
        var ordinary=queue.tryAcquire("GET","/api/store/products","10.0.0.2");
        if (ordinary==null) throw new AssertionError("unrelated normal request must remain eligible");
        a1.close();a1.close();a2.close();b.close();
        // One ordinary ticket remains in-flight, so seven places should be free.
        if (queue.availablePermits()!=7) throw new AssertionError("unexpected released permits");
        var again=queue.tryAcquire("POST","/api/store/orders","10.0.0.1");
        if (again==null)throw new AssertionError("peer cap must reset after release");
        again.close();
        ordinary.close();
        if (queue.availablePermits()!=8) throw new AssertionError("all permits must return");
        if (!queue.json().contains("\"rejectedPeerCap\":1"))
            throw new AssertionError("missing peer rejection metric");
        System.out.println("PriorityAdmissionSecurityTest PASS");
    }
}
