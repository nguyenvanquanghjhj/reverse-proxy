package com.example.store.controller;

import com.example.shop.*;
import com.example.store.validation.Input;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Http {
    private Http() {}
    public static void method(HttpExchange e,String... allowed){if(!List.of(allowed).contains(e.getRequestMethod())){e.getResponseHeaders().set("Allow",String.join(", ",allowed));throw new ShopException(405,"METHOD","Thao tác không được hỗ trợ.");}}
    public static String header(HttpExchange e,String key){var v=e.getRequestHeaders().get(key);if(v==null)return null;if(v.size()!=1)throw Input.bad("Header không hợp lệ.");return v.getFirst();}
    public static byte[] body(HttpExchange e,int limit)throws IOException {byte[] b=e.getRequestBody().readNBytes(limit+1);if(b.length>limit)throw new ShopException(413,"BODY_LIMIT","Tệp hoặc dữ liệu vượt giới hạn cho phép.");return b;}
    public static Map<String,Object> jsonBody(HttpExchange e)throws IOException {if(!"application/json".equals(mime(e)))throw new ShopException(415,"MEDIA_TYPE","Dữ liệu cần ở định dạng JSON.");return Input.object(JsonCodec.parse(body(e,16384)));}
    public static String mime(HttpExchange e){String t=header(e,"Content-Type");return t==null?"":t.split(";",2)[0].strip().toLowerCase(Locale.ROOT);}
    public static Map<String,String> query(HttpExchange e){var q=new LinkedHashMap<String,String>();String raw=e.getRequestURI().getRawQuery();if(raw!=null){if(raw.length()>2048)throw Input.bad("Truy vấn quá dài.");try{for(String item:raw.split("&")){String[] p=item.split("=",2);String k=URLDecoder.decode(p[0],StandardCharsets.UTF_8);if(q.putIfAbsent(k,p.length>1?URLDecoder.decode(p[1],StandardCharsets.UTF_8):"")!=null)throw Input.bad("Tham số bị trùng.");}}catch(IllegalArgumentException ex){throw Input.bad("Truy vấn không hợp lệ.");}}return q;}
    public static long id(String raw){try{if(!raw.matches("[1-9][0-9]{0,17}"))throw new NumberFormatException();return Long.parseLong(raw);}catch(NumberFormatException ex){throw Input.missing();}}
    public static void json(HttpExchange e,int status,Object value)throws IOException {send(e,status,JsonCodec.write(value).getBytes(StandardCharsets.UTF_8),"application/json; charset=utf-8");}
    public static void send(HttpExchange e,int status,byte[] body,String mime)throws IOException {e.getResponseHeaders().set("Content-Type",mime);if(e.getRequestMethod().equals("HEAD")){e.getResponseHeaders().set("Content-Length",Integer.toString(body.length));e.sendResponseHeaders(status,-1);}else{e.sendResponseHeaders(status,body.length);e.getResponseBody().write(body);}}
}
