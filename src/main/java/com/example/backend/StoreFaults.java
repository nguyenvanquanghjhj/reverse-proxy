package com.example.backend;

import com.example.shop.JsonCodec;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit local/test fixture; disabled by default, never a customer-facing control. */
final class StoreFaults {
    private record Fault(int delay,String scope,boolean pdfFailure){}
    private final AtomicReference<Fault> fault=new AtomicReference<>(new Fault(0,"BUSINESS",false));
    private final String token;
    StoreFaults(){
        String enabled=System.getenv().getOrDefault("STORE_FAULTS_ENABLED","false");
        if(!Set.of("true","false").contains(enabled))throw new IllegalArgumentException("Invalid store fault switch");
        token=enabled.equals("true")?System.getenv("STORE_TEST_FAULT_TOKEN"):null;
        if(enabled.equals("true")&&(!"test".equals(System.getenv("STORE_ENV"))||token==null||!token.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Store fault controls require test environment and a private token");
    }
    void control(HttpExchange e)throws IOException {
        if(token==null){send(e,404,Map.of("code","NOT_FOUND"));return;}
        String supplied=e.getRequestHeaders().getFirst("X-Test-Token");
        if(!e.getRemoteAddress().getAddress().isLoopbackAddress()||e.getRequestHeaders().containsKey("X-Forwarded-For")||supplied==null
                ||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))){send(e,403,Map.of("code","FORBIDDEN"));return;}
        if(!e.getRequestMethod().equals("POST")){e.getResponseHeaders().set("Allow","POST");send(e,405,Map.of("code","METHOD"));return;}
        if(e.getRequestBody().readNBytes(1).length!=0){send(e,400,Map.of("code","BODY"));return;}
        try{
            var q=com.example.store.controller.Http.query(e);
            if(!Set.of("delay_ms","scope","pdf_failure").containsAll(q.keySet()))throw new IllegalArgumentException();
            int delay=Integer.parseInt(q.getOrDefault("delay_ms","0"));String scope=q.getOrDefault("scope","BUSINESS"),fail=q.getOrDefault("pdf_failure","false");
            if(delay<0||delay>5000||!Set.of("BUSINESS","PDF","ORDERS").contains(scope)||!Set.of("true","false").contains(fail))throw new IllegalArgumentException();
            fault.set(new Fault(delay,scope,Boolean.parseBoolean(fail)));
            send(e,200,Map.of("delay_ms",delay,"scope",scope,"pdf_failure",Boolean.parseBoolean(fail)));
        }catch(IllegalArgumentException|com.example.shop.ShopException invalid){send(e,400,Map.of("code","INVALID_FAULT"));}
    }
    boolean before(HttpExchange e)throws IOException,InterruptedException {
        var f=fault.get();String path=e.getRequestURI().getPath();boolean pdf=path.startsWith("/api/store/documents/"),orders=path.equals("/api/store/orders"),products=path.equals("/api/store/products")||path.startsWith("/api/store/products/");
        boolean matches=switch(f.scope){case "PDF"->pdf;case "ORDERS"->orders;default->pdf||orders||products;};
        if(matches&&f.delay>0)Thread.sleep(f.delay);
        if(pdf&&f.pdfFailure){e.getResponseHeaders().set("X-Service-Outcome","STORAGE_UNAVAILABLE");send(e,503,Map.of("code","STORAGE_UNAVAILABLE","message","Tài liệu hiện chưa thể truy cập. Vui lòng thử lại sau."));return true;}
        return false;
    }
    private static void send(HttpExchange e,int status,Map<String,Object> body)throws IOException {
        byte[] bytes=JsonCodec.write(body).getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.getResponseHeaders().set("Cache-Control","no-store");
        if(e.getRequestMethod().equals("HEAD")){e.sendResponseHeaders(status,-1);return;}
        e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);
    }
}
