package com.example.store.security;

import com.example.shop.*;
import com.example.store.repository.Sql;
import com.example.store.validation.Input;
import com.sun.net.httpserver.HttpExchange;
import java.sql.*;
import java.util.*;

/** Shared persistent sessions, rotation on login, immediate shared revocation on logout. */
public final class SessionService {
    public record Session(String hash,String csrf,String userId,String name,String email,String role) {
        public boolean admin(){return "ADMIN".equals(role);}
        public Map<String,Object> view(){var m=new LinkedHashMap<String,Object>();m.put("csrf",csrf);m.put("authenticated",userId!=null);
            m.put("user",userId==null?null:Map.of("id",userId,"name",name,"email",email,"role",role));return m;}
    }
    private final Database db;private final boolean secure;private final String origin;
    private final String dummy=Passwords.hash("unused-dummy-password-9f7a");
    public SessionService(Database db){this.db=db;origin=System.getenv().getOrDefault("STORE_ORIGIN","http://127.0.0.1:8080");
        if(!origin.matches("https?://[A-Za-z0-9.\\[\\]:-]+"))throw new IllegalArgumentException("STORE_ORIGIN must be an exact origin");secure=origin.startsWith("https://");}
    public Session resolve(HttpExchange e,boolean create)throws SQLException {
        String token=null;String cookies=e.getRequestHeaders().getFirst("Cookie");
        if(cookies!=null)for(String part:cookies.split(";")){String[] kv=part.strip().split("=",2);if(kv.length==2&&kv[0].equals("st_session")&&kv[1].matches("[0-9a-f]{64}"))token=kv[1];}
        if(token!=null){String hash=Passwords.sha(token);var row=db.with(c->Sql.one(c,"SELECT s.token_hash,s.csrf,u.id,u.display_name,u.email,u.role FROM st_sessions s LEFT JOIN st_users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>now()",hash));
            if(row!=null)return session(row);}
        return create?issue(e,null):null;
    }
    private static Session session(Map<String,Object> r){return new Session((String)r.get("token_hash"),(String)r.get("csrf"),(String)r.get("id"),(String)r.get("display_name"),(String)r.get("email"),(String)r.get("role"));}
    private Session issue(HttpExchange e,UUID user)throws SQLException {
        String token=Passwords.token(),hash=Passwords.sha(token),csrf=Passwords.token();
        db.with(c->{Sql.update(c,"DELETE FROM st_sessions WHERE expires_at<now()");Sql.update(c,"INSERT INTO st_sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '12 hours')",hash,user,csrf);return null;});
        e.getResponseHeaders().add("Set-Cookie","st_session="+token+"; Path=/; HttpOnly; SameSite=Lax; Max-Age=43200"+(secure?"; Secure":""));
        var row=db.with(c->Sql.one(c,"SELECT s.token_hash,s.csrf,u.id,u.display_name,u.email,u.role FROM st_sessions s LEFT JOIN st_users u ON u.id=s.user_id WHERE s.token_hash=?",hash));return session(row);
    }
    public void csrf(HttpExchange e,Session s){
        String sentOrigin=e.getRequestHeaders().getFirst("Origin");
        if(s==null||!Passwords.equal(s.csrf(),e.getRequestHeaders().getFirst("X-CSRF-Token"))||(sentOrigin!=null&&!origin.equals(sentOrigin)))
            throw new ShopException(403,"CSRF","Phiên làm việc đã thay đổi. Vui lòng tải lại trang.");
    }
    public static void customer(Session s){if(s==null||s.userId()==null)throw new ShopException(401,"AUTH_REQUIRED","Vui lòng đăng nhập.");}
    public static void admin(Session s){customer(s);if(!s.admin())throw new ShopException(403,"FORBIDDEN","Bạn không có quyền quản trị.");}
    public Session authenticate(HttpExchange e,Session old,Map<String,Object> body,boolean register)throws SQLException {
        Input.fields(body,register?new String[]{"email","password","name"}:new String[]{"email","password"});
        String email=Input.text(body,"email",3,254).toLowerCase(Locale.ROOT);
        if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw Input.bad("Email không hợp lệ.");
        if(!(body.get("password") instanceof String password)||password.length()<12||password.length()>128||password.codePoints().anyMatch(Character::isISOControl))throw Input.bad("Mật khẩu cần từ 12 đến 128 ký tự, không gồm ký tự điều khiển.");
        // One shared budget per email and one global budget; independent of spoofable forwarded IP headers.
        for(String identity:List.of(email,"ALL-STORE-LOGIN")){
            var count=db.with(c->Sql.one(c,"INSERT INTO st_login_limits(identity_hash,attempts) VALUES(?,1) ON CONFLICT(identity_hash) DO UPDATE SET attempts=CASE WHEN st_login_limits.window_start<now()-interval '5 minutes' THEN 1 ELSE st_login_limits.attempts+1 END,window_start=CASE WHEN st_login_limits.window_start<now()-interval '5 minutes' THEN now() ELSE st_login_limits.window_start END RETURNING attempts",Passwords.sha(identity)));
            if(((Number)count.get("attempts")).intValue()>(identity.equals(email)?10:150))throw new ShopException(429,"LOGIN_LIMIT","Bạn đã thử quá nhiều lần. Vui lòng đợi 5 phút.");
        }
        UUID user;
        if(register){user=UUID.randomUUID();String name=Input.text(body,"name",1,80),hash=Passwords.hash(password);
            try{UUID id=user;db.with(c->{Sql.update(c,"INSERT INTO st_users(id,email,display_name,password_hash,role) VALUES(?,?,?,?,'CUSTOMER')",id,email,name,hash);return null;});}
            catch(SQLException ex){if("23505".equals(ex.getSQLState()))throw Input.conflict("Không thể đăng ký email này.");throw ex;}
        }else{
            var r=db.with(c->Sql.one(c,"SELECT id,password_hash FROM st_users WHERE email=?",email));
            if(!Passwords.verify(password,r==null?dummy:(String)r.get("password_hash"))||r==null)throw new ShopException(401,"LOGIN_FAILED","Email hoặc mật khẩu chưa đúng.");
            user=UUID.fromString((String)r.get("id"));
        }
        // Delete old anonymous/authenticated token before issuing the new identity.
        revoke(old);return issue(e,user);
    }
    public void logout(HttpExchange e,Session s)throws SQLException {revoke(s);e.getResponseHeaders().add("Set-Cookie","st_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"+(secure?"; Secure":""));}
    private void revoke(Session s)throws SQLException {if(s!=null)db.with(c->{Sql.update(c,"DELETE FROM st_sessions WHERE token_hash=?",s.hash());return null;});}
}
