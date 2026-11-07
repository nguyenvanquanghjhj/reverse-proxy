package com.example.shop;

import java.sql.*;
import java.util.*;

public final class OrderRepository {
    public record Created(Map<String,Object> order,boolean replay) { }
    private final Database db;
    public OrderRepository(Database db) { this.db=db; }
    public Created create(OrderRequest request,String key) throws SQLException {
        String hash=request.hash(); UUID id=UUID.randomUUID();
        return db.with(c -> {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); c.setAutoCommit(false);
            try {
                int inserted;
                try(var s=c.prepareStatement("INSERT INTO orders(id,customer_name,total_vnd,idempotency_key,request_hash) VALUES(?,?,0,?,?) ON CONFLICT(idempotency_key) DO NOTHING")) {
                    s.setObject(1,id); s.setString(2,request.customerName()); s.setString(3,key); s.setString(4,hash); inserted=s.executeUpdate();
                }
                if(inserted==0) {
                    UUID old;
                    // A new statement gets a fresh READ COMMITTED snapshot after the competing INSERT commits.
                    try(var s=c.prepareStatement("SELECT id,request_hash FROM orders WHERE idempotency_key=?")) {
                        s.setString(1,key);
                        try(var r=s.executeQuery()) {
                            if(!r.next()) throw new SQLException("Missing committed idempotency row");
                            if(!hash.equals(r.getString(2))) throw new ShopException(409,"IDEMPOTENCY_CONFLICT","Key already used for another order");
                            old=r.getObject(1,UUID.class);
                        }
                    }
                    var result=read(c,old,true); c.commit(); return new Created(result,true);
                }
                long total=0;
                for(var item:request.items()) {
                    var p=ProductRepository.get(c,item.productId(),true); // canonical ascending product ID order
                    if((int)p.get("stockQuantity")<item.quantity()) throw new ShopException(409,"INSUFFICIENT_STOCK","Insufficient product stock");
                    long price=(long)p.get("priceVnd"), line=OrderRequest.lineTotal(price,item.quantity());
                    total=Math.addExact(total,line);
                    try(var s=c.prepareStatement("INSERT INTO order_items(order_id,product_id,quantity,unit_price_vnd,line_total_vnd) VALUES(?,?,?,?,?)")) {
                        s.setObject(1,id); s.setLong(2,item.productId()); s.setInt(3,item.quantity()); s.setLong(4,price); s.setLong(5,line); s.executeUpdate();
                    }
                    try(var s=c.prepareStatement("UPDATE products SET stock_quantity=stock_quantity-?,updated_at=now() WHERE id=?")) {
                        s.setInt(1,item.quantity()); s.setLong(2,item.productId()); s.executeUpdate();
                    }
                }
                try(var s=c.prepareStatement("UPDATE orders SET total_vnd=? WHERE id=?")) { s.setLong(1,total); s.setObject(2,id); s.executeUpdate(); }
                var result=read(c,id,false); c.commit(); return new Created(result,false);
            } catch(SQLException | RuntimeException e) {
                try { c.rollback(); } catch(SQLException rollback) { e.addSuppressed(rollback); }
                if(e instanceof ArithmeticException) throw ShopException.invalid("Order total exceeds BIGINT range");
                throw e;
            }
        });
    }
    public Map<String,Object> get(UUID id) throws SQLException { return db.with(c -> read(c,id,false)); }
    private static Map<String,Object> read(Connection c,UUID id,boolean replay) throws SQLException {
        Map<String,Object> order=new LinkedHashMap<>();
        try(var s=c.prepareStatement("SELECT id,total_vnd,status,created_at FROM orders WHERE id=?")) {
            s.setObject(1,id);
            try(var r=s.executeQuery()) {
                if(!r.next()) throw new ShopException(404,"ORDER_NOT_FOUND","Order not found");
                order.put("orderId",r.getObject(1).toString()); order.put("totalVnd",r.getLong(2)); order.put("status",r.getString(3));
                order.put("createdAt",r.getTimestamp(4).toInstant().toString()); order.put("idempotentReplay",replay);
            }
        }
        List<Object> items=new ArrayList<>();
        try(var s=c.prepareStatement("SELECT product_id,quantity,unit_price_vnd,line_total_vnd FROM order_items WHERE order_id=? ORDER BY product_id")) {
            s.setObject(1,id);
            try(var r=s.executeQuery()) { while(r.next()) items.add(Map.of("productId",r.getLong(1),"quantity",r.getInt(2),"unitPriceVnd",r.getLong(3),"lineTotalVnd",r.getLong(4))); }
        }
        order.put("items",items); return order;
    }
}
