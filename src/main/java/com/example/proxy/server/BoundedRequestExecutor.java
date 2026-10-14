package com.example.proxy.server;

import com.example.proxy.util.AppLogger;
import com.example.proxy.util.RequestDeadline;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** No task queue: also bounds JDK header parsing, before an HttpExchange reaches our handler. */
final class BoundedRequestExecutor implements Executor, AutoCloseable {
    private static final AppLogger log = AppLogger.of(BoundedRequestExecutor.class);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("ingress-deadlines").factory());
    private final Semaphore slots;
    private final int timeoutMs;
    private final int limit;
    private boolean accepting = true;

    BoundedRequestExecutor(int limit, int timeoutMs) {
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
                AtomicBoolean finished = new AtomicBoolean();
                ScheduledFuture<?> deadline = null;
                try {
                    deadline = timers.schedule(() -> {
                        if (!finished.get()) owner.interrupt();
                    }, Math.max(0, expires - System.nanoTime()), TimeUnit.NANOSECONDS);
                    RequestDeadline.run(expires, command);
                } finally {
                    finished.set(true);
                    if (deadline != null) deadline.cancel(false);
                    slots.release();
                }
            });
        } catch (RuntimeException failure) { slots.release(); throw failure; }
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
