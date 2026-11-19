package com.example.store.validation;

import com.example.shop.ShopException;
import java.math.BigDecimal;
import java.util.*;

/** Contract validation shared by HTTP DTOs. Unknown fields fail closed. */
public final class Input {
    private Input() {}
    private static String label(String key) {
        return switch(key) {
            case "name"->"tên";case "email"->"email";case "password"->"mật khẩu";
            case "recipient"->"người nhận";case "phone"->"số điện thoại";case "address"->"địa chỉ";case "note"->"ghi chú";
            case "brand"->"thương hiệu";case "description"->"mô tả";case "screen"->"màn hình";case "chipset"->"bộ xử lý";
            case "category_id"->"danh mục";case "ram_gb"->"RAM";case "battery_mah"->"dung lượng pin";
            case "sku"->"SKU";case "color"->"màu sắc";case "storage_gb"->"dung lượng lưu trữ";case "price_vnd"->"giá bán";case "stock"->"tồn kho";
            case "display_name"->"tên hiển thị";case "position"->"vị trí";case "variants"->"phiên bản";case "ids"->"ảnh";
            case "active"->"trạng thái hiển thị";case "featured"->"sản phẩm nổi bật";case "version"->"phiên bản dữ liệu";
            case "slug"->"đường dẫn";case "reason"->"ghi chú vận hành";case "status"->"trạng thái";case "items"->"sản phẩm đặt hàng";
            case "payment_method"->"phương thức thanh toán";case "guest_token"->"mã truy cập đơn";case "quantity"->"số lượng";
            default->"dữ liệu";
        };
    }
    public static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?> m)) throw bad("Dữ liệu không hợp lệ.");
        @SuppressWarnings("unchecked") var result=(Map<String,Object>)m; return result;
    }
    public static void fields(Map<String,Object> m,String... names) {
        if(!Set.of(names).containsAll(m.keySet())) throw bad("Có trường dữ liệu không được hỗ trợ.");
    }
    public static String text(Map<String,Object> m,String key,int min,int max) {
        if(!(m.get(key) instanceof String s))throw bad("Vui lòng kiểm tra "+label(key)+".");
        s=s.strip();int n=s.codePointCount(0,s.length());
        if(n<min || n>max || s.codePoints().anyMatch(c->Character.isISOControl(c))) throw bad("Vui lòng kiểm tra "+label(key)+".");
        return s;
    }
    public static long number(Map<String,Object> m,String key,long min,long max) {
        try { long n=((BigDecimal)m.get(key)).longValueExact();if(n<min||n>max)throw new ArithmeticException();return n; }
        catch(ClassCastException|NullPointerException|ArithmeticException e){throw bad("Giá trị "+label(key)+" không hợp lệ.");}
    }
    public static boolean bool(Map<String,Object> m,String key) {
        if(!(m.get(key) instanceof Boolean b))throw bad("Giá trị "+label(key)+" không hợp lệ.");return b;
    }
    public static List<?> list(Map<String,Object> m,String key,int min,int max) {
        if(!(m.get(key) instanceof List<?> l)||l.size()<min||l.size()>max)throw bad("Danh sách "+label(key)+" không hợp lệ.");return l;
    }
    public static UUID uuid(String text) {
        if(text==null||!text.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw bad("Mã không hợp lệ.");return UUID.fromString(text);
    }
    public static ShopException bad(String message){return new ShopException(422,"VALIDATION",message);}
    public static ShopException missing(){return new ShopException(404,"NOT_FOUND","Không tìm thấy nội dung này.");}
    public static ShopException conflict(String message){return new ShopException(409,"CONFLICT",message);}
}
