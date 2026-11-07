package com.example.shop;

import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.security.MessageDigest;
import com.example.proxy.util.AppLogger;

/** Invoked only after DemoBackendServer acquires its normal admission and worker permits. */
public final class ShopHandler {
    private static final AppLogger log=AppLogger.of(ShopHandler.class);
    private final ProductRepository products;
    private final OrderRepository orders;
    private final DocumentRepository documents;
    private final ShopConfig config;
    public ShopHandler(ShopConfig config) throws IOException {
        this.config=config;
        var db=new Database(config);
        try { db.checkSchema(); }
        catch(SQLException e) { throw new IOException("Shop database/schema unavailable; prepare JDBC, check SHOP_DB_* and run ShopAdmin migrate",e); }
        products=new ProductRepository(db); orders=new OrderRepository(db);
        documents=new DocumentRepository(db,new SharedStorage(config.filesDir(),config.maxPdfBytes()));
        ShopPage.verifyPackaged();
    }
    public void handle(HttpExchange e) throws IOException {
        String id=Optional.ofNullable(e.getRequestHeaders().getFirst("X-Request-Id")).filter(s -> s.matches("[A-Za-z0-9._-]{1,64}")).orElseGet(() -> UUID.randomUUID().toString());
        e.getResponseHeaders().set("X-Request-Id",id);
        e.getResponseHeaders().set("Cache-Control","no-store"); e.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        try { route(e); }
        catch(ShopException ex) {
            log.warn("event=business_rejected request_id="+id+" status="+ex.status+" code="+ex.code);
            // The buffered proxy may still be writing a valid <=1 MiB HTTP request.
            // Closing an unread upload resets TCP and hides the intended 4xx behind 502.
            // Bound discard by the existing transport budget, never by an untrusted Content-Length.
            e.getRequestBody().readNBytes(1_048_577);
            json(e,ex.status,Map.of("code",ex.code,"message",ex.getMessage(),"requestId",id));
        }
        catch(SQLException ex) {
            String state=ex.getSQLState();
            boolean unavailable=state!=null && (state.startsWith("08") || state.startsWith("53") || state.startsWith("57") || state.equals("55P03") || state.equals("40001") || state.equals("40P01"));
            log.warn("event=shop_dependency_failure request_id="+id+" sql_state="+(state!=null && state.matches("[A-Z0-9]{5}")?state:"UNKNOWN"));
            json(e,unavailable?503:500,Map.of("code",unavailable?"DATA_UNAVAILABLE":"INTERNAL_ERROR","message",unavailable?"Data service unavailable; retry an uncertain order with the same key":"Unable to complete request","requestId",id));
        } catch(RuntimeException ex) {
            log.warn("event=shop_internal_error request_id="+id+" type="+ex.getClass().getSimpleName());
            json(e,500,Map.of("code","INTERNAL_ERROR","message","Unable to complete request","requestId",id));
        }
    }
    private void route(HttpExchange e) throws IOException,SQLException {
        String path=e.getRequestURI().getPath();
        String[] p=path.split("/",-1);
        if(path.equals("/shop") || path.startsWith("/shop/")) {
            method(e,"GET","HEAD");
            var asset=ShopPage.asset(path);
            if(asset==null) throw new ShopException(404,"NOT_FOUND","Shop resource not found");
            e.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
            send(e,200,asset.bytes(),asset.contentType()); return;
        }
        if(p.length==5 && p[1].equals("api") && p[2].equals("products") && p[4].equals("documents")) {
            long productId=productId(p[3]);
            method(e,"GET","HEAD","POST");
            if(e.getRequestMethod().equals("POST")) {
                String token=header(e,"X-Admin-Token");
                if(config.adminToken().isEmpty() || token==null || !MessageDigest.isEqual(config.adminToken().getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
                    throw new ShopException(403,"UPLOAD_FORBIDDEN","Upload requires an operator token");
                mime(e,"application/pdf"); String filename=SharedStorage.filename(header(e,"X-Document-Name"));
                var result=documents.upload(productId,filename,body(e,config.maxPdfBytes()));
                log.info("event=document_uploaded request_id="+e.getResponseHeaders().getFirst("X-Request-Id"));
                e.getResponseHeaders().set("Location",result.get("downloadUrl").toString()); json(e,201,result);
            } else json(e,200,Map.of("documents",documents.list(productId)));
            return;
        }
        if(p.length==5 && p[1].equals("api") && p[2].equals("documents") && p[4].equals("download")) {
            method(e,"GET","HEAD");
            if(e.getRequestHeaders().containsKey("Range")) throw new ShopException(416,"RANGE_UNSUPPORTED","Range downloads are unsupported");
            var download=documents.download(uuid(p[3]));
            log.info("event=document_download_verified request_id="+e.getResponseHeaders().getFirst("X-Request-Id"));
            String filename=SharedStorage.filename(download.document().filename());
            e.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+filename+"\"");
            e.getResponseHeaders().set("X-Document-Sha256",download.document().hash());
            send(e,200,download.bytes(),"application/pdf"); return;
        }
        if(path.equals("/api/products")) { method(e,"GET","HEAD"); json(e,200,Map.of("products",products.list())); return; }
        if(p.length==4 && p[1].equals("api") && p[2].equals("products")) {
            method(e,"GET","HEAD"); json(e,200,products.get(productId(p[3]))); return;
        }
        if(path.equals("/api/orders")) {
            method(e,"POST"); mime(e,"application/json");
            String key=OrderRequest.key(header(e,"Idempotency-Key"));
            var result=orders.create(OrderRequest.parse(body(e,16384)),key);
            log.info("event="+(result.replay()?"order_replayed":"order_created")+" request_id="+e.getResponseHeaders().getFirst("X-Request-Id"));
            e.getResponseHeaders().set("Location","/api/orders/"+result.order().get("orderId"));
            json(e,result.replay()?200:201,result.order()); return;
        }
        if(p.length==4 && p[1].equals("api") && p[2].equals("orders")) {
            method(e,"GET","HEAD"); json(e,200,orders.get(uuid(p[3]))); return;
        }
        throw new ShopException(404,"NOT_FOUND","Route not found");
    }
    static long productId(String value) {
        try { if(!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException(); return Long.parseLong(value); }
        catch(NumberFormatException e) { throw new ShopException(400,"INVALID_ID","Invalid product ID"); }
    }
    static UUID uuid(String value) {
        try { UUID id=UUID.fromString(value); if(!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException(); return id; }
        catch(IllegalArgumentException e) { throw new ShopException(400,"INVALID_ID","Invalid UUID"); }
    }
    static String header(HttpExchange e,String name) {
        var values=e.getRequestHeaders().get(name);
        if(values==null) return null;
        if(values.size()!=1) throw new ShopException(400,"INVALID_HEADER","Expected one "+name+" header");
        return values.getFirst();
    }
    static void mime(HttpExchange e,String expected) {
        String value=header(e,"Content-Type");
        if(value==null || !value.split(";",2)[0].strip().equalsIgnoreCase(expected)) throw new ShopException(415,"MEDIA_TYPE","Use "+expected);
    }
    static void method(HttpExchange e,String... methods) {
        if(!List.of(methods).contains(e.getRequestMethod())) {
            e.getResponseHeaders().set("Allow",String.join(", ",methods)); throw new ShopException(405,"METHOD_NOT_ALLOWED","Method not allowed");
        }
    }
    static byte[] body(HttpExchange e,int max) throws IOException {
        String length=header(e,"Content-Length");
        if(length!=null) {
            try { long n=Long.parseLong(length); if(n<0) throw new NumberFormatException(); if(n>max) throw new ShopException(413,"BODY_LIMIT","Request body exceeds limit"); }
            catch(NumberFormatException ex) { throw new ShopException(400,"INVALID_HEADER","Invalid Content-Length"); }
        }
        byte[] bytes=e.getRequestBody().readNBytes(max+1);
        if(bytes.length>max) throw new ShopException(413,"BODY_LIMIT","Request body exceeds limit");
        return bytes;
    }
    static void json(HttpExchange e,int status,Object data) throws IOException { send(e,status,JsonCodec.write(data).getBytes(StandardCharsets.UTF_8),"application/json; charset=utf-8"); }
    static void send(HttpExchange e,int status,byte[] bytes,String type) throws IOException {
        e.getResponseHeaders().set("Content-Type",type);
        if(e.getRequestMethod().equals("HEAD")) { e.getResponseHeaders().set("Content-Length",Integer.toString(bytes.length)); e.sendResponseHeaders(status,-1); }
        else { e.sendResponseHeaders(status,bytes.length); e.getResponseBody().write(bytes); }
    }
}
