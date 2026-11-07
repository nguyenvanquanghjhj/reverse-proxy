package com.example.store.repository;

import com.example.shop.Database;
import java.sql.*;
import java.util.*;

/** Prepared SQL primitives; transactions belong to a service, not to an HTTP handler. */
public final class Sql {
    private Sql() {}
    public static PreparedStatement statement(Connection c,String sql,Object... args)throws SQLException {
        var s=c.prepareStatement(sql);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;
    }
    public static int update(Connection c,String sql,Object... args)throws SQLException {
        try(var s=statement(c,sql,args)){return s.executeUpdate();}
    }
    public static List<Map<String,Object>> rows(Connection c,String sql,Object... args)throws SQLException {
        try(var s=statement(c,sql,args);var r=s.executeQuery()){
            var list=new ArrayList<Map<String,Object>>();var md=r.getMetaData();
            while(r.next()) {var m=new LinkedHashMap<String,Object>();for(int i=1;i<=md.getColumnCount();i++){
                Object v=r.getObject(i);if(v instanceof Timestamp t)v=t.toInstant().toString();
                else if(v instanceof UUID||v instanceof java.time.temporal.TemporalAccessor)v=v.toString();
                m.put(md.getColumnLabel(i),v);
            }list.add(m);}return list;
        }
    }
    public static Map<String,Object> one(Connection c,String sql,Object... args)throws SQLException {
        var rows=rows(c,sql,args);return rows.isEmpty()?null:rows.getFirst();
    }
    public static <T>T transaction(Database db,Database.Work<T> work)throws SQLException {
        return db.with(c->{c.setAutoCommit(false);try {T result=work.run(c);c.commit();return result;}
            catch(SQLException|RuntimeException e){c.rollback();throw e;}});
    }
}
