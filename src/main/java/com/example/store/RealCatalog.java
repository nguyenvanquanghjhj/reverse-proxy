package com.example.store;

import com.example.shop.*;
import com.example.store.repository.Sql;
import com.example.store.storage.*;
import com.example.store.validation.Input;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Explicit, versioned operator import of verified facts and licensed local photographs.
 * No order/snapshot or stock/price updates on conversion. Old media remains retained for audit. */
final class RealCatalog {
    private static final String RELEASE="real-phones-20261107";
    private static final List<String> LEGACY=List.of("Vela One","Nami Air","Aster Studio","Vela Arc","Nami Note","Aster Flow","Vela Mini","Nami Rise","Aster Max","Vela Wave","Nami Light","Aster Play");
    private record Asset(String name,String kind,String mime,byte[] bytes,int position) { }
    private record Product(Map<String,Object> data,List<Map<String,Object>> variants,List<Asset> assets,String metadata) { }
    private static List<Product> load()throws Exception {
        String text;try(var in=RealCatalog.class.getResourceAsStream("/web/store/images/real/catalog.json")){
            if(in==null)throw new java.io.IOException("Verified catalog resource missing");text=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
        var root=Input.object(JsonCodec.parseResource(text.getBytes(StandardCharsets.UTF_8)));var result=new ArrayList<Product>();
        for(Object raw:Input.list(root,"items",12,12)){
            var p=Input.object(raw);var variants=new ArrayList<Map<String,Object>>();for(Object v:Input.list(p,"variants",2,2))variants.add(Input.object(v));
            for(var v:variants)if(Input.number(v,"ram_gb",1,64)!=Input.number(p,"ram_gb",1,64))throw new IllegalArgumentException("Inconsistent documented RAM tier");
            var assets=new ArrayList<Asset>();int position=0;
            for(Object image:Input.list(p,"images",1,4)){
                var f=Input.object(image);String resource=Input.text(f,"resource",1,200);
                if(!resource.startsWith("/web/store/images/real/")||resource.contains(".."))throw new IllegalArgumentException("Non-local catalog image");
                byte[] bytes;try(var in=RealCatalog.class.getResourceAsStream(resource)){if(in==null)throw new java.io.IOException("Missing photograph");bytes=in.readNBytes(FileStorage.LIMIT+1);}
                if(bytes.length==0||bytes.length>FileStorage.LIMIT)throw new IllegalArgumentException("Photograph exceeds storage bound");
                if(!OrderRequest.sha256(bytes).equals(Input.text(f,"sha256",64,64)))throw new IllegalArgumentException("Photograph does not match verified source manifest");
                assets.add(new Asset(Input.text(p,"name",1,80)+" photo "+(position+1),"IMAGE",Input.text(f,"mime",1,32),bytes,position++));
            }
            String name=Input.text(p,"name",1,80);
            assets.add(new Asset(name+".pdf","PDF","application/pdf",ProductPdf.bytes(name,Input.text(p,"screen",1,120),Input.text(p,"chipset",1,120),(int)Input.number(p,"ram_gb",1,64),(int)Input.number(p,"battery_mah",500,30000)),position));
            var metadata=new LinkedHashMap<String,Object>();metadata.put("catalog_release",RELEASE);metadata.put("price_basis","SAMPLE");metadata.put("verified_at",p.get("verified_at"));metadata.put("region",p.get("region"));metadata.put("notes",p.get("notes"));metadata.put("sources",p.get("sources"));
            var attribution=new ArrayList<Map<String,Object>>();for(Object image:Input.list(p,"images",1,4)){var f=new LinkedHashMap<>(Input.object(image));f.remove("resource");attribution.add(f);}metadata.put("images",attribution);
            result.add(new Product(p,List.copyOf(variants),List.copyOf(assets),JsonCodec.write(metadata)));
        }
        return List.copyOf(result);
    }
    static void seed(Database db,FileStorage storage)throws Exception {
        var products=load();
        for(String[] category:List.of(new String[]{"everyday","Dùng mỗi ngày"},new String[]{"creative","Sáng tạo"},new String[]{"performance","Hiệu năng"}))
            db.with(c->{Sql.update(c,"INSERT INTO st_categories(slug,name) VALUES(?,?) ON CONFLICT(slug) DO NOTHING",category[0],category[1]);return null;});
        int index=0;
        for(var p:products){int model=++index;String marker="REAL-"+model+"-A";
            if(db.with(c->Sql.one(c,"SELECT id FROM st_variants WHERE sku IN (?,?)",marker,"CONCEPT-"+model+"-128"))!=null)continue;
            Sql.transaction(db,c->{
                // A concurrent explicit seed cannot create a second catalog entry.
                try(var lock=c.createStatement()){lock.execute("SELECT pg_advisory_xact_lock(72851407)");}
                if(Sql.one(c,"SELECT id FROM st_variants WHERE sku=?",marker)!=null)return null;
                List<String> keys;
                try { keys=save(storage,p.assets()); } catch(Exception failure) { throw new SQLException("Catalog storage unavailable",failure); }
                long category=((Number)Sql.one(c,"SELECT id FROM st_categories WHERE slug=?",List.of("everyday","creative","performance").get((model-1)%3)).get("id")).longValue();
                var d=p.data();long id=((Number)Sql.one(c,"INSERT INTO st_products(category_id,name,brand,description,screen,chipset,ram_gb,battery_mah,featured,active,source_metadata) VALUES(?,?,?,?,?,?,?,?,?,true,?) RETURNING id",category,d.get("name"),d.get("brand"),d.get("description"),d.get("screen"),d.get("chipset"),number(d,"ram_gb"),number(d,"battery_mah"),model<=4,p.metadata()).get("id")).longValue();
                for(int v=0;v<2;v++){var variant=p.variants().get(v);Sql.update(c,"INSERT INTO st_variants(product_id,sku,color,storage_gb,price_vnd,stock) VALUES(?,?,?,?,?,?)",id,"REAL-"+model+"-"+(v==0?"A":"B"),variant.get("color"),number(variant,"storage_gb"),4990000L+(model-1)*650000L+v*1000000L,model==11?0:23+model);}
                insertFiles(c,id,p.assets(),keys);return null;
            });
        }
    }
    static void convert(Database db,FileStorage storage)throws Exception {
        int index=0,changed=0,skipped=0,aligned=0;
        for(var p:load()){
            int model=++index;String sku="CONCEPT-"+model+"-128";
            var old=db.with(c->Sql.one(c,"SELECT p.id,p.name,p.version FROM st_products p JOIN st_variants v ON v.product_id=p.id WHERE v.sku=?",sku));
            if(old==null){if(alignSourceHashes(db,p,model))aligned++;continue;}
            if(!LEGACY.get(model-1).equals(old.get("name"))){skipped++;continue;}
            long id=((Number)old.get("id")).longValue();
            var original=db.with(c->Sql.rows(c,"SELECT sku FROM st_variants WHERE product_id=?",id));
            if(original.size()!=2||original.stream().anyMatch(v->!Set.of(sku,"CONCEPT-"+model+"-256").contains(v.get("sku")))){skipped++;continue;}
            var keys=save(storage,p.assets());
            Sql.transaction(db,c->{var locked=Sql.one(c,"SELECT version FROM st_products WHERE id=? FOR UPDATE",id);
                if(!Objects.equals(locked.get("version"),old.get("version")))throw new SQLException("Catalog changed concurrently; stop writers before conversion");
                var variants=Sql.rows(c,"SELECT id,sku FROM st_variants WHERE product_id=? ORDER BY id FOR UPDATE",id);
                for(int v=0;v<2;v++){
                    String oldSku="CONCEPT-"+model+"-"+(v==0?128:256);var variant=p.variants().get(v);
                    var row=variants.stream().filter(x->oldSku.equals(x.get("sku"))).findFirst().orElseThrow(()->new IllegalStateException("Original variant missing"));
                    Sql.update(c,"UPDATE st_variants SET sku=?,color=?,storage_gb=? WHERE id=?","REAL-"+model+"-"+(v==0?"A":"B"),variant.get("color"),number(variant,"storage_gb"),row.get("id"));
                }
                var d=p.data();Sql.update(c,"UPDATE st_products SET name=?,brand=?,description=?,screen=?,chipset=?,ram_gb=?,battery_mah=?,source_metadata=?,version=version+1,updated_at=now() WHERE id=?",d.get("name"),d.get("brand"),d.get("description"),d.get("screen"),d.get("chipset"),number(d,"ram_gb"),number(d,"battery_mah"),p.metadata(),id);
                Sql.update(c,"UPDATE st_files SET active=false WHERE product_id=? AND active",id);insertFiles(c,id,p.assets(),keys);return null;
            });changed++;
        }
        System.out.println("Real catalog converted="+changed+"; source hashes aligned="+aligned+"; customized products skipped="+skipped+"; IDs, stock, prices and order snapshots preserved");
    }
    /** One-time metadata enrichment for imports before hash-based gallery association.
     * Match an already recorded source page; preserve all existing facts, authors and media rows. */
    private static boolean alignSourceHashes(Database db,Product p,int model)throws SQLException {
        var current=db.with(c->Sql.one(c,"SELECT p.id,p.name,p.source_metadata FROM st_products p JOIN st_variants v ON v.product_id=p.id WHERE v.sku=?","REAL-"+model+"-A"));
        if(current==null||!Objects.equals(current.get("name"),p.data().get("name")))return false;
        String original=(String)current.get("source_metadata");
        var metadata=Input.object(JsonCodec.parse(original.getBytes(StandardCharsets.UTF_8)));
        if(!RELEASE.equals(metadata.get("catalog_release")))return false;
        var known=Input.list(p.data(),"images",1,4);var images=new ArrayList<Map<String,Object>>();boolean changed=false;
        for(Object raw:Input.list(metadata,"images",1,4)){
            var image=new LinkedHashMap<>(Input.object(raw));
            for(Object expected:known){var source=Input.object(expected);if(Objects.equals(source.get("source_page"),image.get("source_page"))&&!Objects.equals(source.get("sha256"),image.get("sha256"))){image.put("sha256",source.get("sha256"));changed=true;}}
            images.add(image);
        }
        if(!changed)return false;
        metadata.put("images",images);String enriched=JsonCodec.write(metadata);
        int rows=db.with(c->Sql.update(c,"UPDATE st_products SET source_metadata=?,version=version+1,updated_at=now() WHERE id=? AND source_metadata=?",enriched,current.get("id"),original));
        if(rows!=1)throw new SQLException("Source metadata changed concurrently; stop writers before catalog maintenance");
        return true;
    }
    private static long number(Map<String,Object> data,String key){return ((Number)data.get(key)).longValue();}
    private static List<String> save(FileStorage storage,List<Asset> assets)throws Exception {
        var keys=new ArrayList<String>();for(var asset:assets)keys.add(storage.save(asset.bytes()));return keys;
    }
    private static void insertFiles(Connection c,long product,List<Asset> assets,List<String> keys)throws SQLException {
        for(int i=0;i<assets.size();i++){var f=assets.get(i);Sql.update(c,"INSERT INTO st_files(id,product_id,kind,display_name,storage_key,mime,size_bytes,sha256,position) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),product,f.kind(),f.name(),keys.get(i),f.mime(),f.bytes().length,OrderRequest.sha256(f.bytes()),f.position());}
    }
}
