package com.example.shop;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Explicit operator CLI; backend startup never migrates, seeds or resets data. Run from repo root. */
public final class ShopAdmin {
    public static void main(String[] args) throws Exception {
        if(args.length<1) throw new IllegalArgumentException("Usage: ShopAdmin migrate|seed|check|sample-pdf|audit|reset --confirm-reset");
        var config=ShopConfig.environment(); var db=new Database(config);
        switch(args[0]) {
            case "check" -> db.checkSchema();
            case "database-create" -> {
                if(args.length!=2 || !args[1].matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException("Expected safe lowercase database name; connect to existing postgres database");
                db.with(c->{
                    boolean exists;try(var s=c.prepareStatement("SELECT 1 FROM pg_database WHERE datname=?")){s.setString(1,args[1]);try(var r=s.executeQuery()){exists=r.next();}}
                    if(!exists)try(var s=c.createStatement()){s.execute("CREATE DATABASE "+args[1]);}return null;
                });
            }
            case "migrate" -> db.with(c -> {
                c.setAutoCommit(false);
                try(var s=c.createStatement()) {
                    s.execute("SELECT pg_advisory_xact_lock(72851404)");
                    s.execute("CREATE TABLE IF NOT EXISTS shop_schema_version(version INTEGER PRIMARY KEY,applied_at TIMESTAMPTZ NOT NULL DEFAULT now())");
                    boolean exists;
                    try(var r=s.executeQuery("SELECT version FROM shop_schema_version WHERE version=1")) { exists=r.next(); }
                    if(!exists) executeFile(c,"db/migrations/V001__shop.sql");
                    c.commit();
                } catch(SQLException | RuntimeException e) { c.rollback(); throw e; }
                return null;
            });
            case "seed" -> {
                db.checkSchema(); db.with(c -> { c.setAutoCommit(false); try { executeFile(c,"db/seed.sql"); c.commit(); } catch(SQLException | RuntimeException e) { c.rollback(); throw e; } return null; });
                var storage=new SharedStorage(config.filesDir(),config.maxPdfBytes()); var documents=new DocumentRepository(db,storage);
                // Explicit seed operation, serialized across operators; never run by three backend JVMs.
                new Database(config).with(c->{try(var lock=c.createStatement()) {
                    lock.execute("SELECT pg_advisory_lock(72851405)");
                    try {
                        for(String name:List.of("learning-kit-guide.pdf","learning-kit-notes.pdf")) {
                            boolean exists;
                            try(var s=c.prepareStatement("SELECT 1 FROM product_documents WHERE product_id=5 AND original_filename=?")){s.setString(1,name);try(var r=s.executeQuery()){exists=r.next();}}
                            if(!exists)documents.upload(5,name,SamplePdf.bytes(name));
                        }
                    } finally { lock.execute("SELECT pg_advisory_unlock(72851405)"); }
                }return null;});
            }
            case "sample-pdf" -> {
                Path file=Path.of(args.length>1?args[1]:"target/sample.pdf");
                Files.createDirectories(file.toAbsolutePath().getParent()); Files.write(file,SamplePdf.bytes("PBL4 product manual"));
            }
            case "reset" -> {
                if(args.length!=2 || !args[1].equals("--confirm-reset"))throw new IllegalArgumentException("Stop backends first; pass --confirm-reset for this demo database");
                var storage=new SharedStorage(config.filesDir(),config.maxPdfBytes());
                var keys=db.with(c->{List<String> result=new ArrayList<>();try(var s=c.createStatement();var r=s.executeQuery("SELECT storage_key FROM product_documents")){while(r.next())result.add(r.getString(1));}return result;});
                db.with(c->{c.setAutoCommit(false);try(var s=c.createStatement()){s.execute("TRUNCATE order_items,orders,product_documents,products RESTART IDENTITY");executeFile(c,"db/seed.sql");c.commit();}catch(SQLException e){c.rollback();throw e;}return null;});
                for(String key:keys)storage.delete(key);
                System.out.println("Reset products/orders/documents; run seed to regenerate sample PDFs; audit detects crash leftovers");
            }
            case "audit" -> {
                var storage=new SharedStorage(config.filesDir(),config.maxPdfBytes());
                var known=db.with(c->{Map<String,DocumentRepository.Document> docs=new HashMap<>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM product_documents")){while(r.next()){var d=DocumentRepository.row(r);docs.put(d.key(),d);}}return docs;});
                int problems=0;
                for(var d:known.values()) {try{storage.read(d.key(),d.size(),d.hash());}catch(java.io.IOException e){System.out.println("MISSING_OR_CORRUPT documentId="+d.id());problems++;}}
                boolean clean=args.length==2 && args[1].equals("--clean-orphans");
                try(var files=Files.list(storage.root())) {
                    for(var file:files.toList())if(!known.containsKey(file.getFileName().toString())) {
                        System.out.println("ORPHAN "+file.getFileName());problems++;
                        String name=file.getFileName().toString();
                        if(clean && !Files.isSymbolicLink(file) && Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                                && Files.getLastModifiedTime(file).toMillis()<System.currentTimeMillis()-3600000
                                && (name.matches("[0-9a-f-]{36}\\.pdf") || name.matches("\\.upload-[A-Za-z0-9-]+\\.tmp")))Files.delete(file);
                    }
                }
                System.out.println("Storage audit findings="+problems+"; stop all writers before --clean-orphans; only files older than one hour are eligible");
            }
            case "experiment-create", "experiment-drop" -> {
                if(args.length!=2 || !args[1].matches("shop_bench_[0-9a-f]{32}"))throw new IllegalArgumentException("Expected generated shop_bench_ schema name");
                db.with(c->{try(var s=c.createStatement()){s.execute(args[0].equals("experiment-create")?"CREATE SCHEMA "+args[1]:"DROP SCHEMA "+args[1]+" CASCADE");}return null;});
            }
            case "experiment-stock" -> {
                if(!config.dbUrl().matches(".*[?&]currentSchema=shop_bench_[0-9a-f]{32}(&.*)?"))throw new IllegalArgumentException("Stock preparation requires isolated benchmark schema");
                int stock=Integer.parseInt(args[1]);if(stock<1 || stock>1000000)throw new IllegalArgumentException("Stock out of range");
                db.with(c->{try(var s=c.prepareStatement("UPDATE products SET stock_quantity=?,price_vnd=49000 WHERE id=1")){s.setInt(1,stock);s.executeUpdate();}return null;});
            }
            case "stats" -> {
                String result=db.with(c->{Map<String,Object> stats=new LinkedHashMap<>();
                    try(var s=c.createStatement();var r=s.executeQuery("SELECT (SELECT count(*) FROM orders),(SELECT count(*) FROM order_items),(SELECT coalesce(sum(quantity),0) FROM order_items),(SELECT stock_quantity FROM products WHERE id=1),(SELECT count(*) FROM product_documents)")){
                        r.next();stats.put("orders",r.getLong(1));stats.put("items",r.getLong(2));stats.put("quantity",r.getLong(3));stats.put("stock",r.getLong(4));stats.put("documents",r.getLong(5));
                    }return JsonCodec.write(stats);});System.out.println(result);
            }
            default -> throw new IllegalArgumentException("Unknown command");
        }
        System.out.println("ShopAdmin "+args[0]+": OK");
    }
    private static void executeFile(Connection c,String path) throws SQLException {
        try(var s=c.createStatement()) { s.execute(Files.readString(Path.of(path))); }
        catch(java.io.IOException e) { throw new SQLException("Unable to read SQL migration",e); }
    }
}
