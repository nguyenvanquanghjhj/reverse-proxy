package com.example.backend;

import java.io.IOException;

/** Compatibility entrypoint for saved experiments; new launchers use BackendApplicationServer. */
@Deprecated
public final class DemoBackendServer implements AutoCloseable {
    private final BackendApplicationServer backend;
    public DemoBackendServer(int port, int capacity, int delayMs, int cpuWorkers, double cpuBudget) throws IOException {
        backend = new BackendApplicationServer(port, capacity, delayMs, cpuWorkers, cpuBudget);
    }
    public void start() { backend.start(); }
    public int getPort() { return backend.getPort(); }
    @Override public void close() { backend.close(); }
    public static void main(String[] args) throws IOException { BackendApplicationServer.main(args); }
}
