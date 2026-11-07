package com.example.store.repository;

import com.example.shop.Database;
import com.example.store.validation.Input;
import java.sql.*;
import java.util.*;

public final class CatalogRepository {
    private final Database db;
    public CatalogRepository(Database db){this.db=db;}
    public List<Map<String,Object>> categories(boolean admin)throws SQLException {
        return db.with(c->Sql.rows(c,"SELECT id,slug,name,active FROM st_categories"+(admin?"":" WHERE active")+" ORDER BY id"));
    }
    public Map<String,Object> catalog(Map<String,String> q,boolean admin)throws SQLException {
        int page=integer(q,"page",1,1,10000),limit=integer(q,"limit",12,1,24);
        String search=q.getOrDefault("q","").strip();if(search.length()>100)throw Input.bad("Từ khóa quá dài.");
        var args=new ArrayList<Object>();String where=admin?" WHERE true":" WHERE p.active AND c.active";
        if(!search.isEmpty()){where+=" AND (p.name ILIKE ? ESCAPE '!' OR p.brand ILIKE ? ESCAPE '!')";
            String pattern="%"+search.replace("!","!!").replace("%","!%").replace("_","!_")+"%";args.add(pattern);args.add(pattern);}
        if(q.containsKey("category")&&!q.get("category").isEmpty()){where+=" AND c.slug=?";args.add(q.get("category"));}
        if(q.containsKey("brand")&&!q.get("brand").isEmpty()){where+=" AND p.brand=?";args.add(q.get("brand"));}
        if("true".equals(q.get("featured")))where+=" AND p.featured";
        if("true".equals(q.get("stock")))where+=" AND v.stock>0";
        if(q.containsKey("min")&&!q.get("min").isEmpty()){where+=" AND v.price_vnd>=?";args.add((long)integer(q,"min",0,0,1000000000));}
        if(q.containsKey("max")&&!q.get("max").isEmpty()){where+=" AND v.price_vnd<=?";args.add((long)integer(q,"max",1000000000,0,1000000000));}
        String order=switch(q.getOrDefault("sort","new")){case "price_asc"->"price_vnd,p.id";case "price_desc"->"price_vnd DESC,p.id";case "name"->"p.name,p.id";case "new"->"p.created_at DESC,p.id DESC";default->throw Input.bad("Cách sắp xếp không hợp lệ.");};
        String from=" FROM st_products p JOIN st_categories c ON c.id=p.category_id JOIN st_variants v ON v.product_id=p.id"+(admin?"":" AND v.active")+where;
        Object[] filter=args.toArray();String query="SELECT p.id,p.name,p.brand,p.active,p.featured,c.name category,c.slug category_slug,min(v.price_vnd) price_vnd,sum(v.stock) stock,"
            +"count(v.id) variant_count,string_agg(v.sku,', ' ORDER BY v.id) skus,"
            +"(SELECT count(*) FROM st_files f WHERE f.product_id=p.id AND f.active AND f.kind='IMAGE') image_count,"
            +"(SELECT count(*) FROM st_files f WHERE f.product_id=p.id AND f.active AND f.kind='PDF') pdf_count,"
            +"(SELECT '/api/store/images/'||f.id FROM st_files f WHERE f.product_id=p.id AND f.active AND f.kind='IMAGE' ORDER BY f.position,f.created_at LIMIT 1) image_url"
            +from+" GROUP BY p.id,c.id ORDER BY "+order+" LIMIT ? OFFSET ?";
        args.add(limit);args.add((page-1)*limit);Object[] all=args.toArray();
        return db.with(c->{var count=Sql.one(c,"SELECT count(DISTINCT p.id) total"+from,filter);
            return Map.of("items",Sql.rows(c,query,all),"total",count.get("total"),"page",page,"limit",limit,
                "brands",Sql.rows(c,"SELECT DISTINCT brand FROM st_products WHERE active ORDER BY brand"));});
    }
    public Map<String,Object> detail(long id,boolean admin)throws SQLException {return db.with(c->detail(c,id,admin));}
    public static Map<String,Object> detail(Connection c,long id,boolean admin)throws SQLException {
        var p=Sql.one(c,"SELECT p.*,c.name category,c.slug category_slug FROM st_products p JOIN st_categories c ON c.id=p.category_id WHERE p.id=?"+(admin?"":" AND p.active AND c.active"),id);
        if(p==null)throw Input.missing();
        String source=(String)p.get("source_metadata");p.put("source_metadata",source==null||source.isEmpty()?null:com.example.shop.JsonCodec.parse(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        p.put("variants",Sql.rows(c,"SELECT id,sku,color,storage_gb,price_vnd,stock,active FROM st_variants WHERE product_id=?"+(admin?"":" AND active")+" ORDER BY id",id));
        p.put("files",Sql.rows(c,"SELECT id,kind,display_name,mime,size_bytes,sha256,position,'/api/store/'||CASE WHEN kind='IMAGE' THEN 'images/' ELSE 'documents/' END||id url FROM st_files WHERE product_id=? AND active ORDER BY kind,position,created_at",id));return p;
    }
    public static int integer(Map<String,String> q,String key,int fallback,int min,int max){
        try{int n=Integer.parseInt(q.getOrDefault(key,Integer.toString(fallback)));if(n<min||n>max)throw new NumberFormatException();return n;}
        catch(NumberFormatException e){throw Input.bad("Tham số "+key+" không hợp lệ.");}
    }
}
