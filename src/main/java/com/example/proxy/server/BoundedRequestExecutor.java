package com.example.proxy.server;

import com.example.proxy.util.AppLogger;
import com.example.proxy.util.RequestDeadline;
import java.util.concurrent.*;

/** No task queue: also bounds JDK header parsing, before an HttpExchange reaches our handler. */
public final class BoundedRequestExecutor implements Executor, AutoCloseable {
    private static final AppLogger log = AppLogger.of(BoundedRequestExecutor.class);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("ingress-deadlines").factory());
    private final Semaphore slots;
    private final int timeoutMs;
    private final int limit;
    private boolean accepting = true;

    public BoundedRequestExecutor(int limit, int timeoutMs) {
        if (limit < 1 || timeoutMs < 1) throw new IllegalArgumentException("Positive task and deadline bounds required");
        slots = new Semaphore(limit);
        this.limit = limit;
        this.timeoutMs = timeoutMs;
        timers.setRemoveOnCancelPolicy(true);
    }
    @Override public synchronized void execute(Runnable command) {
        if (!accepting || !slots.tryAcquire()) {
            // HttpServer closes this connection when execute rejects, before headers/ID exist.
            log.warn("event=ingress_rejected reason=task_capacity_or_shutdown");
            throw new RejectedExecutionException("Ingress capacity exhausted or stopping");
        }
        try {
            workers.execute(() -> {
                Thread owner = Thread.currentThread();
                long expires = RequestDeadline.afterMillis(timeoutMs);
                try (TaskDeadline deadline = new TaskDeadline(owner, expires)) {
                    RequestDeadline.run(expires, command, deadline::forRoute);
                } finally {
                    slots.release();
                }
            });
        } catch (RuntimeException failure) { slots.release(); throw failure; }
    }
    private final class TaskDeadline implements AutoCloseable, Runnable {
        private final Thread owner;
        private long expires;
        private boolean finished, promoted;
        private ScheduledFuture<?> task;
        TaskDeadline(Thread owner, long expires) {
            this.owner=owner; this.expires=expires; schedule();
        }
        private void schedule() {
            task=timers.schedule(this,Math.max(0,expires-System.nanoTime()),TimeUnit.NANOSECONDS);
        }
        synchronized long forRoute(int timeoutMs) {
            if (timeoutMs<1 || timeoutMs>120000) throw new IllegalArgumentException("Route deadline outside bounds");
            if (!promoted && !finished && System.nanoTime()<expires && !owner.isInterrupted()) {
                promoted=true; task.cancel(false); expires=RequestDeadline.afterMillis(timeoutMs); schedule();
            }
            return expires;
        }
        @Override public synchronized void run() {
            if (!finished) {
                if (System.nanoTime()<expires) schedule();
                else owner.interrupt();
            }
        }
        @Override public synchronized void close() { finished=true; task.cancel(false); }
    }
    synchronized void stopAccepting() { accepting = false; }
    int activeTasks() { return limit - slots.availablePermits(); }
    boolean hasCapacity() { return slots.availablePermits() > 0; }
    @Override public void close() {
        stopAccepting();
        workers.shutdownNow();
        timers.shutdownNow();
        try {
            if (!workers.awaitTermination(2, TimeUnit.SECONDS)) log.warn("handler_executor_shutdown_timeout");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
