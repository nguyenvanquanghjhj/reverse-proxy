package com.example.shop;

import java.sql.*;
import java.util.Properties;
import java.util.concurrent.*;

/** Bounded connections per JVM; backend admission also bounds waiters. No unbounded pool. */
public final class Database {
    @FunctionalInterface public interface Work<T> { T run(Connection connection) throws SQLException; }
    private final ShopConfig config;
    private final Semaphore permits;
    private final boolean pooling;
    private final java.util.ArrayDeque<Idle> idle = new java.util.ArrayDeque<>();
    private long created, reused, discarded;
    private record Idle(Connection connection, long returnedNanos) {}
    public synchronized String poolStats() { return "{\"enabled\":"+pooling+",\"idle\":"+idle.size()
        +",\"created\":"+created+",\"reused\":"+reused+",\"discarded\":"+discarded+"}"; }
    public Database(ShopConfig config) {
        this.config=config; permits=new Semaphore(config.maxConnections(), true);
        String flag=System.getenv().getOrDefault("SHOP_DB_POOL_ENABLED","false");
        if (!flag.equals("true")&&!flag.equals("false"))
            throw new IllegalArgumentException("SHOP_DB_POOL_ENABLED must be true/false");
        pooling=Boolean.parseBoolean(flag);
    }
    public <T> T with(Work<T> work) throws SQLException {
        boolean acquired=false;
        try {
            acquired=permits.tryAcquire(500, TimeUnit.MILLISECONDS);
            if (!acquired) throw new SQLTransientConnectionException("Connection capacity exhausted", "08001");
            var properties = new Properties();
            properties.setProperty("user", config.dbUser()); properties.setProperty("password", config.dbPassword());
            properties.setProperty("connectTimeout", "2"); properties.setProperty("socketTimeout", "3");
            properties.setProperty("options", "-c statement_timeout=2000 -c lock_timeout=1500 -c idle_in_transaction_session_timeout=5000");
            properties.setProperty("ApplicationName", "pbl4-shop");
            Connection c = null; boolean healthy = true;
            try {
                c = borrow(properties);
                return work.run(c);
            } catch (SQLException failure) {
                healthy = failure.getSQLState()==null || !failure.getSQLState().startsWith("08");
                throw failure;
            } finally { if (c != null) recycle(c,healthy); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new SQLTransientConnectionException("Interrupted", "08001", e);
        } finally { if(acquired) permits.release(); }
    }
    private Connection borrow(Properties properties) throws SQLException {
        if (pooling) {
            while(true) {
                Idle entry;
                synchronized(this) { entry=idle.pollLast(); }
                if(entry==null)break;
                Connection c=entry.connection();
                try {
                    if (System.nanoTime()-entry.returnedNanos()<=60_000_000_000L && c.isValid(1)) {
                        synchronized(this) { reused++; }
                        return c;
                    }
                } catch(SQLException unusable) { /* discard connection */ }
                try { c.close(); } catch(SQLException ignored) {}
                synchronized(this) { discarded++; }
            }
        }
        Connection createdConnection=DriverManager.getConnection(config.dbUrl(),properties);
        synchronized(this) { created++; }
        return createdConnection;
    }
    private void recycle(Connection c, boolean healthy) {
        try {
            if (!healthy || c.isClosed()) { c.close();synchronized(this){discarded++;}return; }
            // Transaction hygiene is mandatory: Sql.transaction leaves autoCommit=false.
            if (!c.getAutoCommit()) { c.rollback();c.setAutoCommit(true); }
            if (c.isReadOnly()) c.setReadOnly(false);
            c.clearWarnings();
            if (pooling && c.isValid(1)) {
                synchronized(this) {
                    if (idle.size()<config.maxConnections()) {
                        idle.addLast(new Idle(c,System.nanoTime()));return;
                    }
                }
            }
        } catch(SQLException invalid) { synchronized(this){discarded++;} }
        try { c.close(); } catch(SQLException ignored) {}
    }
    public void checkSchema() throws SQLException {
        with(c -> { try (var s=c.prepareStatement("SELECT version FROM shop_schema_version WHERE version=1"); var r=s.executeQuery()) {
            if (!r.next()) throw new SQLException("Run ShopAdmin migrate before starting shop", "55000");
        } return null; });
    }
}
