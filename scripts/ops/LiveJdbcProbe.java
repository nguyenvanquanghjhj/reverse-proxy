import com.example.shop.Database;
import com.example.shop.JsonCodec;
import com.example.shop.ShopConfig;
import com.example.store.repository.Sql;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit real-PostgreSQL acceptance helper; run only by acceptance_live.py in its new schema. */
class LiveJdbcProbe {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        var c = ShopConfig.environment();
        var config = new ShopConfig(c.dbUrl(), c.dbUser(), c.dbPassword(), c.filesDir(), c.maxPdfBytes(), c.adminToken(), 4);
        var db = new Database(config);
        String table = "jdbc_acceptance_" + (System.getenv("SHOP_DB_POOL_ENABLED").equals("true") ? "on" : "off");
        db.with(connection -> { try (var s=connection.createStatement()) {
            s.execute("CREATE TABLE " + table + "(id integer PRIMARY KEY)");
        } return null; });
        db.with(connection -> { connection.setAutoCommit(false); try(var s=connection.createStatement()) {
            s.executeUpdate("INSERT INTO " + table + " VALUES (-1)");
        } return null; }); // Deliberately abandon this transaction: recycle must roll it back.
        db.with(connection -> { check(connection.getAutoCommit(), "autoCommit reset");
            try(var s=connection.createStatement(); var r=s.executeQuery("SELECT count(*) FROM " + table)) {
                r.next(); check(r.getInt(1)==0, "abandoned transaction was rolled back");
            } connection.setReadOnly(true); return null; });
        db.with(connection -> { check(!connection.isReadOnly(), "readOnly reset"); return null; });
        try {
            Sql.transaction(db, connection -> { Sql.update(connection, "INSERT INTO " + table + " VALUES (-2)");
                throw new SQLException("intentional transaction rollback", "P0001"); });
            throw new AssertionError("transaction failure was swallowed");
        } catch(SQLException expected) { check("P0001".equals(expected.getSQLState()), "SQLState preserved"); }
        var pids = ConcurrentHashMap.<Integer>newKeySet();
        var latencies = new ConcurrentLinkedQueue<Double>();
        try(var executor=Executors.newFixedThreadPool(12)) {
            var jobs = new ArrayList<Future<?>>();
            for(int i=0;i<48;i++) { final int id=i; jobs.add(executor.submit(() -> {
                long start=System.nanoTime();
                try { Sql.transaction(db, connection -> {
                    try(var s=connection.createStatement();var r=s.executeQuery("SELECT pg_backend_pid()")) {
                        r.next(); pids.add(r.getInt(1));
                    }
                    Sql.update(connection,"INSERT INTO " + table + " VALUES (?)",id);
                    return null;
                }); } catch(SQLException failure) { throw new CompletionException(failure); }
                latencies.add((System.nanoTime()-start)/1_000_000.0);
            })); }
            for(var job:jobs) job.get(20,TimeUnit.SECONDS);
        }
        db.with(connection -> { try(var s=connection.createStatement();var r=s.executeQuery("SELECT count(*) FROM " + table)) {
            r.next();check(r.getInt(1)==48,"48 concurrent committed transactions; no abandoned rows");
        } return null; });
        if (System.getenv("SHOP_DB_POOL_ENABLED").equals("true")) check(pids.size()<=4,"physical connections bounded by four permits");
        try {
            db.with(connection -> { try(var s=connection.createStatement()) {
                s.execute("SELECT pg_terminate_backend(pg_backend_pid())");
            } return null; });
            throw new AssertionError("terminated connection did not fail");
        } catch(SQLException expected) { check(expected.getSQLState().startsWith("08") || expected.getSQLState().equals("57P01"),"connection termination propagated"); }
        db.with(connection -> { try(var s=connection.createStatement();var r=s.executeQuery("SELECT 1")) {
            check(r.next() && r.getInt(1)==1,"recovery after broken connection");
        } return null; });
        var sorted=new ArrayList<>(latencies);Collections.sort(sorted);
        System.out.println(JsonCodec.write(Map.of("status","PASS","pool",db.poolStats(),"transactions",48,
            "distinct_backend_pids",pids.size(),"p95_ms",sorted.get((int)Math.ceil(sorted.size()*.95)-1))));
    }
}
