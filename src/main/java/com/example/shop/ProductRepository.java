package com.example.shop;

import java.sql.*;
import java.util.*;

public final class ProductRepository {
    private final Database db;
    public ProductRepository(Database db) { this.db=db; }
    public List<Map<String,Object>> list() throws SQLException {
        return db.with(c -> {
            List<Map<String,Object>> result=new ArrayList<>();
            try(var s=c.prepareStatement("SELECT * FROM products WHERE status='ACTIVE' ORDER BY id LIMIT 100"); var r=s.executeQuery()) {
                while(r.next()) result.add(row(r));
            } return result;
        });
    }
    public Map<String,Object> get(long id) throws SQLException { return db.with(c -> get(c,id,false)); }
    static Map<String,Object> get(Connection c,long id,boolean lock) throws SQLException {
        try(var s=c.prepareStatement("SELECT * FROM products WHERE id=?"+(lock?" FOR UPDATE":""))) {
            s.setLong(1,id);
            try(var r=s.executeQuery()) {
                if(!r.next()) throw new ShopException(404,"PRODUCT_NOT_FOUND","Product not found");
                var p=row(r);
                if(!p.get("status").equals("ACTIVE")) throw new ShopException(lock?422:404,"PRODUCT_INACTIVE","Product unavailable");
                return p;
            }
        }
    }
    static Map<String,Object> row(ResultSet r) throws SQLException {
        return Map.of("id",r.getLong("id"),"sku",r.getString("sku"),"name",r.getString("name"),
                "priceVnd",r.getLong("price_vnd"),"stockQuantity",r.getInt("stock_quantity"),"status",r.getString("status"));
    }
}
