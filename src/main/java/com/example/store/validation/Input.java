package com.example.store.validation;

import com.example.shop.ShopException;
import java.math.BigDecimal;
import java.util.*;

/** Contract validation shared by HTTP DTOs. Unknown fields fail closed. */
public final class Input {
    private Input() {}
    public static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?> m)) throw bad("Dữ liệu không hợp lệ.");
        @SuppressWarnings("unchecked") var result=(Map<String,Object>)m; return result;
    }
    public static void fields(Map<String,Object> m,String... names) {
        if(!Set.of(names).containsAll(m.keySet())) throw bad("Có trường dữ liệu không được hỗ trợ.");
    }
    public static String text(Map<String,Object> m,String key,int min,int max) {
        if(!(m.get(key) instanceof String s))throw bad("Vui lòng kiểm tra trường "+key+".");
        s=s.strip();int n=s.codePointCount(0,s.length());
        if(n<min || n>max || s.codePoints().anyMatch(c->Character.isISOControl(c))) throw bad("Vui lòng kiểm tra trường "+key+".");
        return s;
    }
    public static long number(Map<String,Object> m,String key,long min,long max) {
        try { long n=((BigDecimal)m.get(key)).longValueExact();if(n<min||n>max)throw new ArithmeticException();return n; }
        catch(ClassCastException|NullPointerException|ArithmeticException e){throw bad("Giá trị "+key+" không hợp lệ.");}
    }
    public static boolean bool(Map<String,Object> m,String key) {
        if(!(m.get(key) instanceof Boolean b))throw bad("Giá trị "+key+" không hợp lệ.");return b;
    }
    public static List<?> list(Map<String,Object> m,String key,int min,int max) {
        if(!(m.get(key) instanceof List<?> l)||l.size()<min||l.size()>max)throw bad("Danh sách "+key+" không hợp lệ.");return l;
    }
    public static UUID uuid(String text) {
        if(text==null||!text.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw bad("Mã không hợp lệ.");return UUID.fromString(text);
    }
    public static ShopException bad(String message){return new ShopException(422,"VALIDATION",message);}
    public static ShopException missing(){return new ShopException(404,"NOT_FOUND","Không tìm thấy nội dung này.");}
    public static ShopException conflict(String message){return new ShopException(409,"CONFLICT",message);}
}
