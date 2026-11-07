package com.example.proxy.handler;

import java.io.InterruptedIOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.Locale;

/** Optional bounded admission for PDF handlers, independent of scheduler eligibility and backend health. */
final class DownloadBulkhead {
    private final int capacity,queueCapacity,waitMs;
    private final Semaphore slots,waiters;
    private final LongAdder attempted=new LongAdder(),admitted=new LongAdder(),rejected=new LongAdder(),cancelled=new LongAdder(),waitNanos=new LongAdder(),waitSamples=new LongAdder();
    DownloadBulkhead(int capacity,int queueCapacity,int waitMs) {
        if(capacity<0||capacity>128||queueCapacity<1||queueCapacity>128||waitMs<0||waitMs>5000)throw new IllegalArgumentException("Invalid download bulkhead bounds");
        this.capacity=capacity;this.queueCapacity=queueCapacity;this.waitMs=waitMs;
        slots=new Semaphore(capacity,true);waiters=new Semaphore(queueCapacity);
    }
    boolean enabled(){return capacity>0;}
    Ticket acquire(long expires)throws InterruptedIOException {
        if(!enabled())return new Ticket(null);
        attempted.increment();
        long remaining=expires-System.nanoTime();
        if(remaining<=0||!waiters.tryAcquire()){rejected.increment();return null;}
        long start=System.nanoTime();
        try {
            long budget=Math.min(TimeUnit.MILLISECONDS.toNanos(waitMs),Math.max(0,expires-start));
            if(!slots.tryAcquire(budget,TimeUnit.NANOSECONDS)){rejected.increment();return null;}
            admitted.increment();return new Ticket(this);
        }catch(InterruptedException interruption){
            cancelled.increment();Thread.currentThread().interrupt();throw new InterruptedIOException("Download admission interrupted");
        }finally{waitNanos.add(System.nanoTime()-start);waitSamples.increment();waiters.release();}
    }
    static final class Ticket implements AutoCloseable {
        private final DownloadBulkhead owner;private final AtomicBoolean closed=new AtomicBoolean();
        private Ticket(DownloadBulkhead owner){this.owner=owner;}
        @Override public void close(){if(owner!=null&&closed.compareAndSet(false,true))owner.slots.release();}
    }
    int active(){return capacity-slots.availablePermits();}
    int waiting(){return queueCapacity-waiters.availablePermits();}
    String json(){return String.format(Locale.ROOT,"{\"enabled\":%s,\"capacity\":%d,\"queueCapacity\":%d,\"waitMs\":%d,\"active\":%d,\"waiting\":%d,\"attempted\":%d,\"admitted\":%d,\"rejected\":%d,\"cancelled\":%d,\"waitSamples\":%d,\"waitTotalMs\":%.4f}",enabled(),capacity,queueCapacity,waitMs,active(),waiting(),attempted.sum(),admitted.sum(),rejected.sum(),cancelled.sum(),waitSamples.sum(),waitNanos.sum()/1_000_000.0);}
}
