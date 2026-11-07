package com.example.backend;

/** Serializes admission with drain activation. Already accepted work is never cancelled by draining. */
public final class RequestAdmission {
    private final int limit;
    private int admitted;
    private boolean draining;

    public RequestAdmission(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Positive admission limit required");
        this.limit = limit;
    }
    public synchronized boolean enter() {
        if (draining || admitted >= limit) return false;
        admitted++;
        return true;
    }
    public synchronized void leave() {
        if (admitted == 0) throw new IllegalStateException("Unbalanced admission release");
        admitted--;
    }
    public synchronized void drain(boolean enabled) { draining = enabled; }
    public synchronized boolean draining() { return draining; }
    public synchronized int admitted() { return admitted; }
}
