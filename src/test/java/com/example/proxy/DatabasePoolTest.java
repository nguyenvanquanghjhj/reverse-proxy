package com.example.proxy;

import com.example.shop.Database;
import com.example.shop.ShopConfig;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** Independent synthetic-driver test for pool state reset; no live PostgreSQL required. */
public final class DatabasePoolTest {
    static final AtomicInteger connections = new AtomicInteger(), rollbacks=new AtomicInteger();
    static final class DriverStub implements Driver {
        @Override public boolean acceptsURL(String url) { return url.equals("jdbc:postgresql:pbl4_pool_test"); }
        @Override public Connection connect(String url,Properties ignored) {
            if(!acceptsURL(url))return null;
            connections.incrementAndGet();
            class State { boolean auto=true,closed=false,readOnly=false; }
            State state=new State();
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (p,m,args)->{
                return switch (m.getName()) {
                    case "getAutoCommit" -> state.auto;
                    case "setAutoCommit" -> { state.auto=(boolean)args[0];yield null; }
                    case "rollback" -> { rollbacks.incrementAndGet();yield null; }
                    case "isValid" -> !state.closed;
                    case "isClosed" -> state.closed;
                    case "close" -> { state.closed=true;yield null; }
                    case "isReadOnly" -> state.readOnly;
                    case "setReadOnly" -> { state.readOnly=(boolean)args[0];yield null; }
                    case "clearWarnings" -> null;
                    case "toString" -> "SyntheticConnection";
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p==args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                };
            });
        }
        @Override public int getMajorVersion(){return 1;}
        @Override public int getMinorVersion(){return 0;}
        @Override public boolean jdbcCompliant(){return false;}
        @Override public DriverPropertyInfo[] getPropertyInfo(String url,Properties info){return new DriverPropertyInfo[0];}
        @Override public Logger getParentLogger(){return Logger.getGlobal();}
    }
    static void assertTrue(boolean b,String name){ if(!b)throw new AssertionError(name); }
    public static void main(String[] args)throws Exception {
        if(args.length==0){
            var cmd=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-ea","-cp",System.getProperty("java.class.path"),DatabasePoolTest.class.getName(),"child");
            cmd.environment().put("SHOP_DB_POOL_ENABLED","true");
            cmd.inheritIO();Process process=cmd.start();
            if(!process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)||process.exitValue()!=0)
                throw new AssertionError("JDBC pooled-mode child failed");
            return;
        }
        var registered=DriverManager.getDrivers();
        while(registered.hasMoreElements()) DriverManager.deregisterDriver(registered.nextElement());
        DriverManager.registerDriver(new DriverStub());
        var config=new ShopConfig("jdbc:postgresql:pbl4_pool_test","test","secret",Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath(),262144,"",2);
        var db=new Database(config);
        db.with(c->{c.setAutoCommit(false);return null;});
        db.with(c->{assertTrue(c.getAutoCommit(),"auto-commit was not reset after previous borrower");return null;});
        assertTrue(connections.get()==1,"expected idle connection reuse, actual="+connections);
        assertTrue(rollbacks.get()>=1,"connection cleanup must rollback outstanding transaction");
        try {db.with(c->{throw new SQLException("connection failure","08006");});
            throw new AssertionError("expected SQLException");}
        catch(SQLException expected){ assertTrue("08006".equals(expected.getSQLState()),"SQLState propagates"); }
        db.with(c->{assertTrue(c.getAutoCommit(),"discarded connection replacement is clean");return null;});
        assertTrue(connections.get()==2,"SQLState 08 must force fresh connection");
        assertTrue(db.poolStats().contains("\"reused\":"),"pool metrics exposed");
        System.out.println("DatabasePoolTest PASS");
    }
}
