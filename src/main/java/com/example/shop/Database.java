package com.example.shop;

import java.sql.*;
import java.util.Properties;
import java.util.concurrent.*;

/** Bounded connections per JVM; backend admission also bounds waiters. No unbounded pool. */
public final class Database {
    @FunctionalInterface public interface Work<T> { T run(Connection connection) throws SQLException; }
    private final ShopConfig config;
    private final Semaphore permits;
    public Database(ShopConfig config) { this.config=config; permits=new Semaphore(config.maxConnections(), true); }
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
            try (Connection c=DriverManager.getConnection(config.dbUrl(), properties)) { return work.run(c); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new SQLTransientConnectionException("Interrupted", "08001", e);
        } finally { if(acquired) permits.release(); }
    }
    public void checkSchema() throws SQLException {
        with(c -> { try (var s=c.prepareStatement("SELECT version FROM shop_schema_version WHERE version=1"); var r=s.executeQuery()) {
            if (!r.next()) throw new SQLException("Run ShopAdmin migrate before starting shop", "55000");
        } return null; });
    }
}
