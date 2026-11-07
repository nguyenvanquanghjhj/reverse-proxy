package com.example.store;

import com.example.shop.*;
import com.example.store.repository.Sql;
import com.example.store.security.Passwords;
import com.example.store.service.FileService;
import com.example.store.storage.FileStorage;
import com.example.store.storage.ProductPdf;
import java.nio.file.*;
import java.util.*;

/** Explicit migration, verified catalog seed and admin provisioning. No startup data mutation. */
public final class StoreAdmin {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Usage: StoreAdmin migrate|seed|catalog|admin|check|stats|audit");var config=ShopConfig.environment();var db=new Database(config);
        switch(args[0]){
            case "migrate"->{ShopAdmin.main(new String[]{"migrate"});Sql.transaction(db,c->{try(var s=c.createStatement()){s.execute("SELECT pg_advisory_xact_lock(72851404)");if(Sql.one(c,"SELECT version FROM shop_schema_version WHERE version=2")==null)s.execute(Files.readString(Path.of("db/migrations/V002__store.sql")));if(Sql.one(c,"SELECT version FROM shop_schema_version WHERE version=3")==null)s.execute(Files.readString(Path.of("db/migrations/V003__store_operations.sql")));if(Sql.one(c,"SELECT version FROM shop_schema_version WHERE version=4")==null)s.execute(Files.readString(Path.of("db/migrations/V004__service_capabilities.sql")));if(Sql.one(c,"SELECT version FROM shop_schema_version WHERE version=5")==null)s.execute(Files.readString(Path.of("db/migrations/V005__real_catalog_sources.sql")));}catch(java.io.IOException e){throw new java.sql.SQLException("Migration unavailable",e);}return null;});}
            case "check"->check(db);
            case "admin"->{check(db);String email=System.getenv("STORE_ADMIN_EMAIL"),password=System.getenv("STORE_ADMIN_PASSWORD");if(email==null||!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")||email.length()>254||password==null)throw new IllegalArgumentException("Set STORE_ADMIN_EMAIL and STORE_ADMIN_PASSWORD (12..128 chars); no default credential");
                String hash=Passwords.hash(password);db.with(c->{int n=Sql.update(c,"INSERT INTO st_users(id,email,display_name,password_hash,role) VALUES(?,?,'Quản trị cửa hàng',?,'ADMIN') ON CONFLICT(email) DO NOTHING",UUID.randomUUID(),email.toLowerCase(Locale.ROOT),hash);if(n==0)throw new IllegalArgumentException("Account exists. This command never overwrites credentials or promotes a customer.");return null;});}
            case "seed"->{check(db);RealCatalog.seed(db,new FileStorage(config));}
            case "catalog"->{check(db);RealCatalog.convert(db,new FileStorage(config));}
            case "stats"->System.out.println(JsonCodec.write(db.with(c->Sql.one(c,"SELECT (SELECT count(*) FROM st_products) products,(SELECT count(*) FROM st_orders) orders,(SELECT coalesce(sum(quantity),0) FROM st_order_items) quantity,(SELECT coalesce(sum(stock),0) FROM st_variants) stock,(SELECT count(*) FROM st_users) users,(SELECT count(*) FROM st_files WHERE active) files"))));
            case "audit"->{var storage=new FileStorage(config);var files=db.with(c->Sql.rows(c,"SELECT id,storage_key,size_bytes,sha256,active FROM st_files"));int failures=0;var known=new HashSet<String>();for(var f:files){known.add((String)f.get("storage_key"));try{storage.read((String)f.get("storage_key"),((Number)f.get("size_bytes")).intValue(),(String)f.get("sha256"));}catch(java.io.IOException e){System.out.println("MISSING_OR_CORRUPT fileId="+f.get("id"));failures++;}}for(String key:storage.keys())if(!known.contains(key)){System.out.println("ORPHAN "+key);failures++;}if(failures>0)throw new IllegalStateException("Storage audit findings="+failures+"; review with writers stopped; no automatic deletion");System.out.println("Verified active and retained files="+files.size());}
            default->throw new IllegalArgumentException("Unknown store operation");
        }System.out.println("StoreAdmin "+args[0]+": OK");
    }
    public static void check(Database db)throws java.sql.SQLException {db.with(c->{if(Sql.one(c,"SELECT version FROM shop_schema_version WHERE version=5")==null)throw new java.sql.SQLException("Run StoreAdmin migrate", "55000");return null;});}
}
