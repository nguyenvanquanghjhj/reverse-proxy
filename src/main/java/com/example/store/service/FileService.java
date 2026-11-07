package com.example.store.service;

import com.example.shop.*;
import com.example.store.repository.Sql;
import com.example.store.storage.FileStorage;
import com.example.store.validation.Input;
import java.io.*;
import java.sql.*;
import java.util.*;

public final class FileService {
    public record Download(byte[] bytes,String mime,String name,String hash) {}
    private final Database db;private final FileStorage storage;
    public FileService(Database db,FileStorage storage){this.db=db;this.storage=storage;}
    public Map<String,Object> upload(long product,String kind,String name,String mime,byte[] bytes)throws SQLException,IOException {
        if(!Set.of("PDF","IMAGE").contains(kind))throw Input.bad("Loại tệp không hợp lệ.");
        if(kind.equals("PDF"))db.with(c->{OperationsService.admitUpload(c);return null;});
        if(name==null||!name.matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,95}")||name.contains(".."))throw Input.bad("Tên tệp chỉ gồm chữ không dấu, số, khoảng trắng và dấu gạch nối.");
        var content=storage.validate(bytes,mime,kind);return add(product,kind,name,content.bytes(),content.mime());
    }
    /** Operator-only seed asset input; HTTP upload never accepts SVG. */
    public Map<String,Object> add(long product,String kind,String name,byte[] bytes,String mime)throws SQLException,IOException {
        UUID id=UUID.randomUUID();String key=storage.save(bytes),hash=OrderRequest.sha256(bytes);
        // Uncertain commit failures retain an opaque orphan for operator audit; never delete a possibly committed file.
        try{return Sql.transaction(db,c->{if(Sql.one(c,"SELECT id FROM st_products WHERE id=? FOR UPDATE",product)==null)throw Input.missing();
            var count=Sql.one(c,"SELECT count(*) total FROM st_files WHERE product_id=? AND active",product);if(((Number)count.get("total")).intValue()>=24)throw Input.bad("Mỗi sản phẩm có tối đa 24 tệp đang sử dụng.");
            Sql.update(c,"INSERT INTO st_files(id,product_id,kind,display_name,storage_key,mime,size_bytes,sha256,position) VALUES(?,?,?,?,?,?,?,?,?)",id,product,kind,name,key,mime,bytes.length,hash,((Number)count.get("total")).intValue());
            return Map.of("id",id.toString(),"kind",kind,"display_name",name,"mime",mime,"size_bytes",bytes.length,"sha256",hash,"url","/api/store/"+(kind.equals("IMAGE")?"images/":"documents/")+id);});
        }catch(ShopException ex){try{storage.discard(key);}catch(IOException ignored){/* Audit reports the retained orphan. */}throw ex;}
    }
    public Download download(UUID id,boolean admin)throws SQLException,IOException {
        var f=db.with(c->{var found=Sql.one(c,"SELECT f.* FROM st_files f JOIN st_products p ON p.id=f.product_id JOIN st_categories c ON c.id=p.category_id WHERE f.id=? AND f.active"+(admin?"":" AND p.active AND c.active"),id);if(found!=null&&"PDF".equals(found.get("kind")))OperationsService.admitDownload(c);return found;});
        if(f==null)throw Input.missing();byte[] bytes=storage.read((String)f.get("storage_key"),((Number)f.get("size_bytes")).intValue(),(String)f.get("sha256"));
        return new Download(bytes,(String)f.get("mime"),(String)f.get("display_name"),(String)f.get("sha256"));
    }
    public void edit(UUID id,Map<String,Object> b,boolean remove)throws SQLException {
        if(!remove)Input.fields(b,"display_name","position");
        Sql.transaction(db,c->{var f=Sql.one(c,"SELECT product_id,kind FROM st_files WHERE id=? AND active",id);if(f==null)throw Input.missing();
            var p=Sql.one(c,"SELECT active FROM st_products WHERE id=? FOR UPDATE",f.get("product_id"));
            if(remove){if(f.get("kind").equals("IMAGE")&&Boolean.TRUE.equals(p.get("active"))&&((Number)Sql.one(c,"SELECT count(*) total FROM st_files WHERE product_id=? AND kind='IMAGE' AND active",f.get("product_id")).get("total")).intValue()<=1)throw Input.conflict("Hãy thêm ảnh mới hoặc ngừng bán trước khi xóa ảnh cuối cùng.");
                Sql.update(c,"UPDATE st_files SET active=false WHERE id=?",id);
            }else Sql.update(c,"UPDATE st_files SET display_name=?,position=? WHERE id=?",Input.text(b,"display_name",1,100),Input.number(b,"position",0,1000),id);
            Sql.update(c,"UPDATE st_products SET version=version+1 WHERE id=?",f.get("product_id"));return null;
        });
    }
}
