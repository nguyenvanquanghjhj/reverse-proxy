package com.example.store.service;

import com.example.shop.Database;
import com.example.store.repository.*;
import com.example.store.validation.Input;
import java.sql.*;
import java.util.*;

public final class CatalogService {
    private final Database db;
    public CatalogService(Database db){this.db=db;}
    public Map<String,Object> saveCategory(Long id,Map<String,Object> body)throws SQLException {
        Input.fields(body,"name","slug","active");String name=Input.text(body,"name",1,80),slug=Input.text(body,"slug",1,64);boolean active=Input.bool(body,"active");
        if(!slug.matches("[a-z0-9]+(-[a-z0-9]+)*"))throw Input.bad("Đường dẫn danh mục chỉ gồm chữ thường, số và dấu gạch nối.");
        return db.with(c->{var row=id==null?Sql.one(c,"INSERT INTO st_categories(name,slug,active) VALUES(?,?,?) RETURNING *",name,slug,active):Sql.one(c,"UPDATE st_categories SET name=?,slug=?,active=? WHERE id=? RETURNING *",name,slug,active,id);
            if(row==null)throw Input.missing();return row;});
    }
    public Map<String,Object> saveProduct(Long id,Map<String,Object> b)throws SQLException {
        Input.fields(b,"name","brand","description","category_id","screen","chipset","ram_gb","battery_mah","active","featured","version","variants");
        String name=Input.text(b,"name",2,160),brand=Input.text(b,"brand",1,64),description=Input.text(b,"description",30,4000),screen=Input.text(b,"screen",2,120),chip=Input.text(b,"chipset",2,120);
        long category=Input.number(b,"category_id",1,Long.MAX_VALUE),ram=Input.number(b,"ram_gb",1,64),battery=Input.number(b,"battery_mah",500,30000);
        boolean active=Input.bool(b,"active"),featured=Input.bool(b,"featured");var variants=Input.list(b,"variants",1,8);
        var validated=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();var ids=new HashSet<Long>();
        for(Object raw:variants){var v=Input.object(raw);Input.fields(v,"id","sku","color","storage_gb","price_vnd","stock","active");
            String sku=Input.text(v,"sku",1,64),color=Input.text(v,"color",1,40);if(!sku.matches("[A-Za-z0-9_-]+")||!seen.add(sku))throw Input.bad("SKU phải duy nhất và chỉ gồm chữ, số, gạch nối.");
            Long variantId=v.get("id")==null?null:Input.number(v,"id",1,Long.MAX_VALUE);if(variantId!=null&&!ids.add(variantId))throw Input.bad("Biến thể bị trùng.");
            var values=new LinkedHashMap<String,Object>();values.put("id",variantId);values.put("sku",sku);values.put("color",color);values.put("storage",Input.number(v,"storage_gb",16,4096));values.put("price",Input.number(v,"price_vnd",1000,1000000000));values.put("stock",Input.number(v,"stock",0,1000000));values.put("active",Input.bool(v,"active"));validated.add(values);
        }
        if(active&&validated.stream().noneMatch(v->Boolean.TRUE.equals(v.get("active"))))throw Input.bad("Cần ít nhất một phiên bản đang bán.");
        return Sql.transaction(db,c->{
            var cat=Sql.one(c,"SELECT active FROM st_categories WHERE id=? FOR SHARE",category);if(cat==null||active&&!Boolean.TRUE.equals(cat.get("active")))throw Input.bad("Danh mục chưa được bật.");
            long product;
            if(id==null){if(active)throw Input.bad("Hãy lưu bản nháp và thêm ảnh trước khi đăng bán.");product=((Number)Sql.one(c,"INSERT INTO st_products(category_id,name,brand,description,screen,chipset,ram_gb,battery_mah,active,featured) VALUES(?,?,?,?,?,?,?,?,?,?) RETURNING id",category,name,brand,description,screen,chip,ram,battery,false,featured).get("id")).longValue();}
            else{var old=Sql.one(c,"SELECT version FROM st_products WHERE id=? FOR UPDATE",id);if(old==null)throw Input.missing();if(((Number)old.get("version")).longValue()!=Input.number(b,"version",1,Integer.MAX_VALUE))throw Input.conflict("Sản phẩm đã được chỉnh sửa. Vui lòng tải lại.");
                if(active&&Sql.one(c,"SELECT id FROM st_files WHERE product_id=? AND kind='IMAGE' AND active LIMIT 1",id)==null)throw Input.bad("Cần thêm ít nhất một hình ảnh trước khi đăng bán.");
                product=id;Sql.update(c,"UPDATE st_products SET category_id=?,name=?,brand=?,description=?,screen=?,chipset=?,ram_gb=?,battery_mah=?,active=?,featured=?,version=version+1,updated_at=now() WHERE id=?",category,name,brand,description,screen,chip,ram,battery,active,featured,id);
                var existing=Sql.rows(c,"SELECT id FROM st_variants WHERE product_id=? ORDER BY id FOR UPDATE",id);
                for(var v:existing)if(!ids.contains(((Number)v.get("id")).longValue()))throw Input.bad("Giữ lại các phiên bản cũ và tắt phiên bản không còn bán.");
            }
            for(var v:validated){if(v.get("id")==null)Sql.update(c,"INSERT INTO st_variants(product_id,sku,color,storage_gb,price_vnd,stock,active) VALUES(?,?,?,?,?,?,?)",product,v.get("sku"),v.get("color"),v.get("storage"),v.get("price"),v.get("stock"),v.get("active"));
                else if(Sql.update(c,"UPDATE st_variants SET sku=?,color=?,storage_gb=?,price_vnd=?,stock=?,active=? WHERE id=? AND product_id=?",v.get("sku"),v.get("color"),v.get("storage"),v.get("price"),v.get("stock"),v.get("active"),v.get("id"),product)!=1)throw Input.bad("Phiên bản không thuộc sản phẩm.");}
            return CatalogRepository.detail(c,product,true);
        });
    }
}
