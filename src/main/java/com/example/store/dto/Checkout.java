package com.example.store.dto;

import com.example.store.validation.Input;
import com.example.store.security.Passwords;
import com.example.shop.JsonCodec;
import java.util.*;

public record Checkout(String recipient,String phone,String address,String note,String guestToken,List<Line> items,String hash) {
    public record Line(long variantId,int quantity) {}
    public static Checkout parse(Map<String,Object> b,String userId){
        Input.fields(b,"recipient","phone","address","note","payment_method","guest_token","items");
        String recipient=Input.text(b,"recipient",2,80),phone=Input.text(b,"phone",9,15),address=Input.text(b,"address",10,300),note=Input.text(b,"note",0,500),token=Input.text(b,"guest_token",64,64);
        if(!phone.matches("\\+?[0-9]{9,14}"))throw Input.bad("Số điện thoại cần từ 9 đến 14 chữ số.");
        if(!token.matches("[0-9a-f]{64}"))throw Input.bad("Mã truy cập đơn không hợp lệ.");
        if(!Input.text(b,"payment_method",3,3).equals("COD"))throw Input.bad("Chỉ hỗ trợ thanh toán khi nhận hàng.");
        var lines=new ArrayList<Line>();var seen=new HashSet<Long>();
        for(Object raw:Input.list(b,"items",1,20)){var l=Input.object(raw);Input.fields(l,"variant_id","quantity");long id=Input.number(l,"variant_id",1,Long.MAX_VALUE);if(!seen.add(id))throw Input.bad("Sản phẩm trong giỏ bị trùng.");lines.add(new Line(id,(int)Input.number(l,"quantity",1,20)));}
        lines.sort(Comparator.comparingLong(Line::variantId));var canonical=new LinkedHashMap<String,Object>();canonical.put("recipient",recipient);canonical.put("phone",phone);canonical.put("address",address);canonical.put("note",note);canonical.put("guest",token);canonical.put("user",userId);
        // Map.of iteration order is randomized per JVM; canonical intent must have explicit field order.
        canonical.put("items",lines.stream().map(l->{var line=new LinkedHashMap<String,Object>();line.put("variant",l.variantId());line.put("quantity",l.quantity());return line;}).toList());
        return new Checkout(recipient,phone,address,note,token,List.copyOf(lines),Passwords.sha(JsonCodec.write(canonical)));
    }
}
