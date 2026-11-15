package com.example.store;

import com.example.shop.*;
import com.example.proxy.util.AppLogger;
import com.example.store.controller.*;
import com.example.store.repository.CatalogRepository;
import com.example.store.security.SessionService;
import com.example.store.service.*;
import com.example.store.storage.FileStorage;
import com.example.store.validation.Input;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.sql.*;
import java.util.*;

/** Full store module hosted by each existing backend listener. No second server, no synthetic work. */
public final class StoreApplication {
    private record Asset(byte[] bytes,String mime) {}
    private final Map<String,Asset> assets=new HashMap<>();private final StoreController controller;private final OperationsService operations;
    private static final AppLogger LOG=AppLogger.of(StoreApplication.class);
    public static boolean enabled(){String value=System.getenv().getOrDefault("STORE_ENABLED","false");if(!Set.of("true","false").contains(value))throw new IllegalArgumentException("STORE_ENABLED=true|false");return value.equals("true");}
    public StoreApplication(ShopConfig config)throws IOException {
        var db=new Database(config);
        try{StoreAdmin.check(db);}catch(SQLException e){throw new IOException("Store database migration required",e);}
        var storage=new FileStorage(config);operations=new OperationsService(db,storage);
        var sessions=new SessionService(db);controller=new StoreController(new CatalogRepository(db),new CatalogService(db),new OrderService(db),new FileService(db,storage),sessions,operations);
        for(String file:List.of("index.html","store.css","shop.css","app.js","site.js","api.js","cart.js","checkout.js","views.js","admin.js","operations.js"))try(var in=getClass().getResourceAsStream("/web/store/"+file)){
            if(in==null)throw new IOException("Store resource missing: "+file);byte[] bytes=in.readNBytes(262145);if(bytes.length>262144)throw new IOException("Store resource exceeds budget");
            assets.put(file,new Asset(bytes,file.endsWith(".css")?"text/css; charset=utf-8":file.endsWith(".js")?"text/javascript; charset=utf-8":"text/html; charset=utf-8"));}
    }
    public Map<String,Object> dependencies(){return operations.dependencies();}
    public static boolean matches(String path){return path.equals("/")||path.equals("/shop")||path.startsWith("/shop/")||path.equals("/admin")||path.startsWith("/admin/")||path.startsWith("/store/assets/")||path.equals("/api/store")||path.startsWith("/api/store/");}
    public void handle(HttpExchange e)throws IOException {
        var h=e.getResponseHeaders();h.set("Cache-Control","no-store");h.set("X-Content-Type-Options","nosniff");h.set("Referrer-Policy","no-referrer");h.set("X-Frame-Options","DENY");
        h.set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        try {
            String path=e.getRequestURI().getPath();
            if(path.startsWith("/api/store")){controller.handle(e);return;}
            Http.method(e,"GET","HEAD");
            if(path.equals("/admin")||path.startsWith("/admin/")){var s=controller.session(e);if(s==null||s.userId()==null){h.set("Location","/shop/login?next=admin");e.sendResponseHeaders(303,-1);return;}SessionService.admin(s);}
            Asset asset=path.startsWith("/store/assets/")?assets.get(path.substring(14)):assets.get("index.html");if(asset==null)throw Input.missing();
            Http.send(e,200,asset.bytes(),asset.mime());
        }catch(ShopException ex){error(e,ex.status,ex.code,ex.getMessage());}
        catch(SQLException ex){String code=ex.getSQLState()==null?"":ex.getSQLState();if(code.equals("23505"))error(e,409,"DUPLICATE","SKU, danh mục hoặc email đã được sử dụng.");else if(code.startsWith("23"))error(e,422,"DATA_CONSTRAINT","Dữ liệu không hợp lệ hoặc đang được sử dụng.");else error(e,503,"DATABASE_UNAVAILABLE","Cửa hàng tạm thời chưa thể xử lý. Vui lòng kiểm tra lại kết quả trước khi thử lại.");}
        catch(NoSuchFileException ex){error(e,404,"FILE_MISSING","Tệp này hiện không có sẵn.");}
        catch(IOException ex){if(e.getResponseCode()!=-1)throw ex;error(e,503,"STORAGE_UNAVAILABLE","Tệp hiện chưa thể truy cập. Vui lòng thử lại sau.");}
        catch(RuntimeException ex){error(e,500,"STORE_ERROR","Chưa thể xử lý yêu cầu. Vui lòng kiểm tra lại kết quả.");}
    }
    private static void error(HttpExchange e,int status,String code,String message)throws IOException {
        if(Set.of("SALES_PAUSED","DOWNLOADS_PAUSED","DOCUMENT_UPLOAD_PAUSED","DATABASE_UNAVAILABLE","STORAGE_UNAVAILABLE","FILE_MISSING").contains(code))e.getResponseHeaders().set("X-Service-Outcome",code);
        // Drain bounded ingress before responding; do not log payloads, tokens, credentials or personal data.
        e.getRequestBody().readNBytes(1048577);LOG.warn("event=store_request_failed status="+status+" code="+code);
        Http.json(e,status,Map.of("code",code,"message",message));
    }
}
