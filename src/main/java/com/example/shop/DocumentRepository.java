package com.example.shop;

import java.io.*;
import java.nio.file.NoSuchFileException;
import java.sql.*;
import java.util.*;

public final class DocumentRepository {
    public record Document(UUID id,long productId,String filename,String key,long size,String hash,String createdAt) {
        public Map<String,Object> json() { return Map.of("documentId",id.toString(),"productId",productId,"displayName",filename,
                "contentType","application/pdf","sizeBytes",size,"sha256",hash,"createdAt",createdAt,"downloadUrl","/api/documents/"+id+"/download"); }
    }
    public record Download(Document document,byte[] bytes) { }
    private final Database db;
    private final SharedStorage storage;
    public DocumentRepository(Database db,SharedStorage storage) { this.db=db;this.storage=storage; }
    public Map<String,Object> upload(long productId,String filename,byte[] bytes) throws SQLException {
        SharedStorage.filename(filename);
        new ProductRepository(db).get(productId);
        String key;
        try { key=storage.save(bytes); }
        catch(IOException e) { throw new ShopException(503,"STORAGE_UNAVAILABLE","Unable to store PDF"); }
        UUID id=UUID.randomUUID(); String hash=OrderRequest.sha256(bytes);
        try {
            return db.with(c -> {
                c.setAutoCommit(false);
                try {
                    ProductRepository.get(c,productId,true);
                    try(var s=c.prepareStatement("INSERT INTO product_documents(id,product_id,display_name,original_filename,storage_key,content_type,size_bytes,sha256) VALUES(?,?,?,?,?,'application/pdf',?,?)")) {
                        s.setObject(1,id);s.setLong(2,productId);s.setString(3,filename);s.setString(4,filename);s.setString(5,key);s.setLong(6,bytes.length);s.setString(7,hash);s.executeUpdate();
                    }
                    var result=get(c,id).json(); c.commit(); return result;
                } catch(SQLException | RuntimeException e) { try {c.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);} throw e; }
            });
        } catch(SQLException | RuntimeException failure) {
            // Commit can succeed while its acknowledgement is lost. Recheck before deleting bytes.
            try {
                boolean exists=db.with(c->{try(var s=c.prepareStatement("SELECT 1 FROM product_documents WHERE id=?")){s.setObject(1,id);try(var r=s.executeQuery()){return r.next();}}});
                if(!exists) storage.delete(key);
            } catch(SQLException | IOException cleanup) { failure.addSuppressed(cleanup); System.err.println("shop document requires offline storage audit"); }
            throw failure;
        }
    }
    public List<Map<String,Object>> list(long productId) throws SQLException {
        return db.with(c -> {
            ProductRepository.get(c,productId,false);
            List<Map<String,Object>> list=new ArrayList<>();
            try(var s=c.prepareStatement("SELECT * FROM product_documents WHERE product_id=? ORDER BY created_at,id LIMIT 100")) {
                s.setLong(1,productId);try(var r=s.executeQuery()){while(r.next())list.add(row(r).json());}
            } return list;
        });
    }
    public Download download(UUID id) throws SQLException {
        Document d=db.with(c->{var doc=get(c,id);ProductRepository.get(c,doc.productId(),false);return doc;});
        try { return new Download(d,storage.read(d.key(),d.size(),d.hash())); }
        catch(NoSuchFileException e){throw new ShopException(404,"DOCUMENT_FILE_MISSING","Document file is unavailable");}
        catch(IOException e){throw new ShopException(503,"DOCUMENT_INTEGRITY","Document unavailable or failed integrity check");}
    }
    private static Document get(Connection c,UUID id) throws SQLException {
        try(var s=c.prepareStatement("SELECT * FROM product_documents WHERE id=?")) {
            s.setObject(1,id);try(var r=s.executeQuery()){if(!r.next())throw new ShopException(404,"DOCUMENT_NOT_FOUND","Document not found");return row(r);}
        }
    }
    static Document row(ResultSet r) throws SQLException {
        return new Document(r.getObject("id",UUID.class),r.getLong("product_id"),r.getString("original_filename"),r.getString("storage_key"),r.getLong("size_bytes"),r.getString("sha256"),r.getTimestamp("created_at").toInstant().toString());
    }
}
