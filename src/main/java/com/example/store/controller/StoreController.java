package com.example.store.controller;

import com.example.store.dto.Checkout;
import com.example.store.repository.CatalogRepository;
import com.example.store.security.SessionService;
import com.example.store.service.*;
import com.example.store.storage.FileStorage;
import com.example.store.validation.Input;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.sql.*;
import java.util.*;
import static com.example.store.controller.Http.*;

/** HTTP contracts only; services own authorization-sensitive business operations and transactions. */
public final class StoreController {
    private final CatalogRepository catalog;private final CatalogService products;private final OrderService orders;private final FileService files;private final SessionService sessions;private final OperationsService operations;
    public StoreController(CatalogRepository catalog,CatalogService products,OrderService orders,FileService files,SessionService sessions,OperationsService operations){this.catalog=catalog;this.products=products;this.orders=orders;this.files=files;this.sessions=sessions;this.operations=operations;}
    public SessionService.Session session(HttpExchange e)throws SQLException{return sessions.resolve(e,false);}
    public void handle(HttpExchange e)throws IOException,SQLException {
        String path=e.getRequestURI().getPath().substring("/api/store".length());String verb=e.getRequestMethod();boolean read=verb.equals("GET")||verb.equals("HEAD");
        if(path.equals("/session")){method(e,"GET");json(e,200,sessions.resolve(e,true).view());return;}
        // Public catalog projections contain no identity-dependent fields. Avoid a needless JDBC session
        // lookup here (measured fresh-connection setup dominates these small catalog reads).
        if(path.equals("/categories")){method(e,"GET","HEAD");json(e,200,Map.of("items",catalog.categories(false)));return;}
        if(path.equals("/products")){method(e,"GET","HEAD");json(e,200,catalog.catalog(query(e),false));return;}
        if(path.matches("/products/[1-9][0-9]*")){method(e,"GET","HEAD");json(e,200,catalog.detail(id(path.substring(10)),false));return;}
        if(path.equals("/policy")){method(e,"GET","HEAD");json(e,200,Map.of("shipping_vnd",OrderService.SHIPPING_VND,"payment_methods",List.of("COD"),"max_file_bytes",FileStorage.LIMIT));return;}
        if(path.equals("/availability")){method(e,"GET","HEAD");json(e,200,operations.availability());return;}
        var user=sessions.resolve(e,false);
        if(!read)sessions.csrf(e,user);
        if(path.equals("/auth/login")||path.equals("/auth/register")){method(e,"POST");json(e,200,sessions.authenticate(e,user,jsonBody(e),path.endsWith("register")).view());return;}
        if(path.equals("/auth/logout")){method(e,"POST");sessions.logout(e,user);json(e,200,Map.of("ok",true));return;}
        if(path.equals("/orders")){method(e,"GET","HEAD","POST");if(read)json(e,200,Map.of("items",orders.list(user,query(e),false)));
            else{var r=orders.create(Checkout.parse(jsonBody(e),user==null?null:user.userId()),header(e,"Idempotency-Key"),user);json(e,Boolean.TRUE.equals(r.get("replayed"))?200:201,r);}return;}
        if(path.startsWith("/orders/")){method(e,"GET","HEAD");json(e,200,orders.get(Input.uuid(path.substring(8)),header(e,"X-Order-Token"),user));return;}
        if(path.startsWith("/files/")||path.startsWith("/images/")||path.startsWith("/documents/")){method(e,"GET","HEAD");if(header(e,"Range")!=null)throw new com.example.shop.ShopException(416,"RANGE","Không hỗ trợ tải từng phần.");var d=files.download(Input.uuid(path.substring(path.lastIndexOf('/')+1)),user!=null&&user.admin());
            if(path.startsWith("/images/")&&!d.mime().startsWith("image/")||path.startsWith("/documents/")&&!d.mime().equals("application/pdf"))throw Input.missing();
            if(d.mime().equals("application/pdf"))e.getResponseHeaders().set("Content-Disposition","attachment; filename=\"product-document.pdf\"");
            e.getResponseHeaders().set("X-File-Sha256",d.hash());send(e,200,d.bytes(),d.mime());return;}
        if(path.equals("/admin")||path.startsWith("/admin/")){
            SessionService.admin(user);
            if(path.equals("/admin/operations")){method(e,"GET","HEAD","PUT");json(e,200,read?operations.dashboard():operations.update(jsonBody(e),user));return;}
            if(path.equals("/admin/dashboard")){method(e,"GET","HEAD");json(e,200,orders.dashboard());return;}
            if(path.equals("/admin/categories")){method(e,"GET","HEAD","POST");if(read)json(e,200,Map.of("items",catalog.categories(true)));else json(e,201,products.saveCategory(null,jsonBody(e)));return;}
            if(path.matches("/admin/categories/[1-9][0-9]*")){method(e,"PUT");json(e,200,products.saveCategory(id(path.substring(18)),jsonBody(e)));return;}
            if(path.equals("/admin/products")){method(e,"GET","HEAD","POST");if(read)json(e,200,catalog.catalog(query(e),true));else json(e,201,products.saveProduct(null,jsonBody(e)));return;}
            if(path.matches("/admin/products/[1-9][0-9]*")){long id=id(path.substring(16));method(e,"GET","HEAD","PUT");json(e,200,read?catalog.detail(id,true):products.saveProduct(id,jsonBody(e)));return;}
            if(path.matches("/admin/products/[1-9][0-9]*/files")){method(e,"POST");long product=id(path.split("/")[3]);json(e,201,files.upload(product,query(e).getOrDefault("kind",""),header(e,"X-File-Name"),mime(e),body(e,FileStorage.LIMIT)));return;}
            if(path.startsWith("/admin/files/")){method(e,"PATCH","DELETE");files.edit(Input.uuid(path.substring(13)),verb.equals("DELETE")?Map.of():jsonBody(e),verb.equals("DELETE"));json(e,200,Map.of("ok",true));return;}
            if(path.equals("/admin/orders")){method(e,"GET","HEAD");json(e,200,Map.of("items",orders.list(user,query(e),true)));return;}
            if(path.startsWith("/admin/orders/")){method(e,"GET","HEAD","PATCH");var id=Input.uuid(path.substring(14));json(e,200,read?orders.get(id,null,user):orders.transition(id,jsonBody(e),user));return;}
        }
        throw Input.missing();
    }
}
