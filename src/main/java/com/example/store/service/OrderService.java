package com.example.store.service;

import com.example.shop.*;
import com.example.store.dto.Checkout;
import com.example.store.repository.Sql;
import com.example.store.security.*;
import com.example.store.validation.Input;
import java.sql.*;
import java.util.*;

public final class OrderService {
    private final Database db;
    public static final long SHIPPING_VND=30000;
    public OrderService(Database db){this.db=db;}
    public Map<String,Object> create(Checkout request,String key,SessionService.Session user)throws SQLException {
        if(key==null||!key.matches("[A-Za-z0-9_-]{16,128}"))throw Input.bad("Thiếu mã xác nhận yêu cầu. Vui lòng tải lại trang.");
        return Sql.transaction(db,c->{
            UUID id=UUID.randomUUID();String userId=user==null?null:user.userId();
            int created=Sql.update(c,"INSERT INTO st_orders(id,user_id,guest_hash,recipient,phone,address,note,subtotal_vnd,shipping_vnd,total_vnd,idempotency_key,request_hash) VALUES(?,?,?,?,?,?,?,0,0,0,?,?) ON CONFLICT(idempotency_key) DO NOTHING",id,userId==null?null:UUID.fromString(userId),Passwords.sha(request.guestToken()),request.recipient(),request.phone(),request.address(),request.note(),key,request.hash());
            if(created==0){var old=Sql.one(c,"SELECT id,request_hash FROM st_orders WHERE idempotency_key=?",key);if(old==null||!Passwords.equal(request.hash(),(String)old.get("request_hash")))throw new ShopException(409,"IDEMPOTENCY_CONFLICT","Yêu cầu đặt hàng đã thay đổi. Hãy kiểm tra lại đơn trước đó.");
                var result=read(c,UUID.fromString((String)old.get("id")));result.put("replayed",true);return result;}
            OperationsService.admitSale(c);
            long subtotal=0;
            // Consistent lock order across all JVMs; read every authoritative price and stock under the lock.
            String placeholders=String.join(",",Collections.nCopies(request.items().size(),"?"));
            Object[] ids=request.items().stream().map(Checkout.Line::variantId).toArray();
            var locked=Sql.rows(c,"SELECT v.*,p.name,p.active product_active,c.active category_active FROM st_variants v JOIN st_products p ON p.id=v.product_id JOIN st_categories c ON c.id=p.category_id WHERE v.id IN ("+placeholders+") ORDER BY p.id,v.id FOR UPDATE OF v,p",ids);
            var byId=new HashMap<Long,Map<String,Object>>();for(var row:locked)byId.put(((Number)row.get("id")).longValue(),row);
            var selected=new ArrayList<Map<String,Object>>();
            for(var line:request.items()){
                var v=byId.get(line.variantId());
                if(v==null||!Boolean.TRUE.equals(v.get("active"))||!Boolean.TRUE.equals(v.get("product_active"))||!Boolean.TRUE.equals(v.get("category_active")))throw new ShopException(409,"UNAVAILABLE","Một sản phẩm đã ngừng bán. Vui lòng cập nhật giỏ hàng.");
                if(((Number)v.get("stock")).intValue()<line.quantity())throw new ShopException(409,"OUT_OF_STOCK","Số lượng còn lại không đủ. Vui lòng cập nhật giỏ hàng.");
                selected.add(v);
            }
            for(int i=0;i<request.items().size();i++){var line=request.items().get(i);var v=selected.get(i);long price=((Number)v.get("price_vnd")).longValue(),total=Math.multiplyExact(price,line.quantity());subtotal=Math.addExact(subtotal,total);
                Sql.update(c,"INSERT INTO st_order_items(order_id,variant_id,product_name,sku,color,storage_gb,quantity,unit_price_vnd,line_total_vnd) VALUES(?,?,?,?,?,?,?,?,?)",id,line.variantId(),v.get("name"),v.get("sku"),v.get("color"),v.get("storage_gb"),line.quantity(),price,total);
                Sql.update(c,"UPDATE st_variants SET stock=stock-? WHERE id=?",line.quantity(),line.variantId());}
            var touchedProducts=new TreeSet<Long>();for(var v:selected)touchedProducts.add(((Number)v.get("product_id")).longValue());
            for(long product:touchedProducts)Sql.update(c,"UPDATE st_products SET version=version+1,updated_at=now() WHERE id=?",product);
            Sql.update(c,"UPDATE st_orders SET subtotal_vnd=?,shipping_vnd=?,total_vnd=? WHERE id=?",subtotal,SHIPPING_VND,Math.addExact(subtotal,SHIPPING_VND),id);
            Sql.update(c,"INSERT INTO st_order_history(order_id,status,actor_id) VALUES(?,'CREATED',?)",id,userId==null?null:UUID.fromString(userId));
            var result=read(c,id);result.put("replayed",false);return result;
        });
    }
    public Map<String,Object> get(UUID id,String token,SessionService.Session s)throws SQLException {
        return db.with(c->{var permission=Sql.one(c,"SELECT user_id,guest_hash FROM st_orders WHERE id=?",id);
            boolean allowed=permission!=null&&(s!=null&&(s.admin()||s.userId()!=null&&s.userId().equals(permission.get("user_id")))||token!=null&&token.matches("[0-9a-f]{64}")&&Passwords.equal(Passwords.sha(token),(String)permission.get("guest_hash")));
            if(!allowed)throw Input.missing();return read(c,id);});
    }
    public List<Map<String,Object>> list(SessionService.Session s,Map<String,String> q,boolean admin)throws SQLException {
        SessionService.customer(s);if(admin)SessionService.admin(s);
        int page=com.example.store.repository.CatalogRepository.integer(q,"page",1,1,10000);
        var args=new ArrayList<Object>();String where=" WHERE true";
        if(!admin){where+=" AND user_id=?";args.add(UUID.fromString(s.userId()));}
        if(q.containsKey("status")&&!q.get("status").isBlank()){where+=" AND status=?";args.add(q.get("status"));}
        if(q.containsKey("id")&&!q.get("id").isBlank()){where+=" AND id=?";args.add(Input.uuid(q.get("id")));}
        args.add((page-1)*20);String query="SELECT id,recipient,total_vnd,status,payment_status,created_at FROM st_orders"+where+" ORDER BY created_at DESC LIMIT 20 OFFSET ?";
        return db.with(c->Sql.rows(c,query,args.toArray()));
    }
    private static Map<String,Object> read(Connection c,UUID id)throws SQLException {
        var result=Sql.one(c,"SELECT id,recipient,phone,address,note,subtotal_vnd,shipping_vnd,total_vnd,payment_method,payment_status,status,created_at FROM st_orders WHERE id=?",id);
        if(result==null)throw Input.missing();result.put("items",Sql.rows(c,"SELECT variant_id,product_name,sku,color,storage_gb,quantity,unit_price_vnd,line_total_vnd FROM st_order_items WHERE order_id=? ORDER BY variant_id",id));
        result.put("history",Sql.rows(c,"SELECT status,created_at FROM st_order_history WHERE order_id=? ORDER BY id",id));return result;
    }
    public Map<String,Object> transition(UUID id,Map<String,Object> body,SessionService.Session actor)throws SQLException {
        SessionService.admin(actor);Input.fields(body,"status","confirm_payment");String next=Input.text(body,"status",3,16);boolean paid=Input.bool(body,"confirm_payment");
        return Sql.transaction(db,c->{var old=Sql.one(c,"SELECT status,payment_status FROM st_orders WHERE id=? FOR UPDATE",id);if(old==null)throw Input.missing();String previous=(String)old.get("status");
            if(!previous.equals(next)){
                boolean valid=switch(previous){case "CREATED"->Set.of("CONFIRMED","CANCELLED").contains(next);case "CONFIRMED"->Set.of("PACKING","CANCELLED").contains(next);case "PACKING"->Set.of("SHIPPED","CANCELLED").contains(next);case "SHIPPED"->next.equals("DELIVERED");default->false;};
                if(!valid)throw Input.conflict("Không thể chuyển trạng thái đơn theo thứ tự này.");
                if(next.equals("CANCELLED")){
                    var items=Sql.rows(c,"SELECT i.variant_id,i.quantity,v.product_id FROM st_order_items i JOIN st_variants v ON v.id=i.variant_id JOIN st_products p ON p.id=v.product_id WHERE i.order_id=? ORDER BY p.id,v.id FOR UPDATE OF p,v",id);
                    var products=new TreeSet<Long>();for(var item:items){Sql.update(c,"UPDATE st_variants SET stock=stock+? WHERE id=?",item.get("quantity"),item.get("variant_id"));products.add(((Number)item.get("product_id")).longValue());}
                    for(long product:products)Sql.update(c,"UPDATE st_products SET version=version+1,updated_at=now() WHERE id=?",product);
                }
                Sql.update(c,"UPDATE st_orders SET status=?,updated_at=now() WHERE id=?",next,id);Sql.update(c,"INSERT INTO st_order_history(order_id,status,actor_id) VALUES(?,?,?)",id,next,UUID.fromString(actor.userId()));
            }
            if(paid&&!next.equals("DELIVERED"))throw Input.bad("Chỉ xác nhận thu tiền sau khi giao hàng.");
            if(paid&&!"PAID".equals(old.get("payment_status"))){Sql.update(c,"UPDATE st_orders SET payment_status='PAID',updated_at=now() WHERE id=?",id);Sql.update(c,"INSERT INTO st_order_history(order_id,status,actor_id) VALUES(?,'PAYMENT_RECEIVED',?)",id,UUID.fromString(actor.userId()));}
            return read(c,id);
        });
    }
    public Map<String,Object> dashboard()throws SQLException {return db.with(c->Sql.one(c,"SELECT (SELECT count(*) FROM st_products) products,(SELECT count(*) FROM st_products WHERE active) active_products,(SELECT count(*) FROM st_orders WHERE status='CREATED') new_orders,(SELECT count(*) FROM st_orders WHERE status IN ('CONFIRMED','PACKING','SHIPPED')) processing_orders,(SELECT count(*) FROM st_orders WHERE status='DELIVERED') delivered_orders,(SELECT count(*) FROM st_orders WHERE status='CANCELLED') cancelled_orders,(SELECT count(*) FROM st_orders) orders,(SELECT coalesce(sum(total_vnd),0) FROM st_orders WHERE status<>'CANCELLED') ordered_vnd,(SELECT coalesce(sum(total_vnd),0) FROM st_orders WHERE status='DELIVERED') delivered_vnd,(SELECT coalesce(sum(total_vnd),0) FROM st_orders WHERE payment_status='PAID') collected_vnd,(SELECT count(*) FROM st_variants WHERE stock=0 AND active) out_of_stock"));}
}
