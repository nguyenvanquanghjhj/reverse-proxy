package com.example.store.service;

import com.example.shop.Database;
import com.example.shop.ShopException;
import com.example.store.repository.Sql;
import com.example.store.security.SessionService;
import com.example.store.storage.FileStorage;
import com.example.store.validation.Input;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Durable business controls. These are independent of backend process health and draining. */
public final class OperationsService {
    private final Database db;
    private final FileStorage storage;

    public OperationsService(Database db, FileStorage storage) {
        this.db = db;
        this.storage = storage;
    }

    public Map<String,Object> availability() throws SQLException {
        return settings(false);
    }
    private Map<String,Object> settings(boolean admin)throws SQLException {
        return db.with(c -> Sql.one(c,
            "SELECT sales_enabled,downloads_enabled,uploads_enabled,"+(admin?"reason,":"")+"version,updated_at FROM st_operations WHERE id=1"));
    }

    public Map<String,Object> update(Map<String,Object> body, SessionService.Session actor) throws SQLException {
        SessionService.admin(actor);
        Input.fields(body, "sales_enabled", "downloads_enabled", "uploads_enabled", "reason", "version");
        boolean sales = Input.bool(body, "sales_enabled");
        boolean downloads = Input.bool(body, "downloads_enabled");
        Boolean uploads = body.containsKey("uploads_enabled") ? Input.bool(body,"uploads_enabled") : null;
        String reason = body.containsKey("reason") ? Input.text(body,"reason",0,200) : null;
        long version = Input.number(body, "version", 1, Integer.MAX_VALUE - 1);
        return Sql.transaction(db, c -> {
            // Exclusive update waits for checkouts that already hold FOR SHARE to commit/roll back.
            int changed = Sql.update(c, "UPDATE st_operations SET sales_enabled=?,downloads_enabled=?,uploads_enabled=coalesce(?,uploads_enabled),reason=coalesce(?,reason),updated_by=?,version=version+1,updated_at=now() WHERE id=1 AND version=?", sales, downloads, uploads, reason, UUID.fromString(actor.userId()),version);
            if (changed != 1) throw Input.conflict("Trạng thái vận hành đã thay đổi. Hãy tải lại trước khi lưu.");
            Sql.update(c, "INSERT INTO st_operations_history(sales_enabled,downloads_enabled,uploads_enabled,reason,version,actor_id) SELECT sales_enabled,downloads_enabled,uploads_enabled,reason,version,updated_by FROM st_operations WHERE id=1");
            return Sql.one(c, "SELECT sales_enabled,downloads_enabled,uploads_enabled,reason,version,updated_at FROM st_operations WHERE id=1");
        });
    }

    /** Called only for a NEW intent, in the same transaction as stock and order writes. */
    public static void admitSale(Connection c) throws SQLException {
        var state = Sql.one(c, "SELECT sales_enabled FROM st_operations WHERE id=1 FOR SHARE");
        if (state == null) throw new SQLException("Missing operations migration", "55000");
        if (!Boolean.TRUE.equals(state.get("sales_enabled")))
            throw new ShopException(423, "SALES_PAUSED", "Cửa hàng đang tạm ngừng nhận đơn mới. Giỏ hàng của bạn được giữ lại.");
    }

    /** File kind is determined from metadata, so the generic /files alias cannot bypass the pause. */
    public static void admitDownload(Connection c) throws SQLException {
        var state = Sql.one(c, "SELECT downloads_enabled FROM st_operations WHERE id=1");
        if (state == null) throw new SQLException("Missing operations migration", "55000");
        if (!Boolean.TRUE.equals(state.get("downloads_enabled")))
            throw new ShopException(423, "DOWNLOADS_PAUSED", "Tài liệu PDF đang tạm ngừng tải. Bạn vẫn có thể xem sản phẩm và đơn hàng.");
    }

    /** PDF uploads admitted before a pause may finish. Rejected uploads never write a file or metadata. */
    public static void admitUpload(Connection c) throws SQLException {
        var state=Sql.one(c,"SELECT uploads_enabled FROM st_operations WHERE id=1");
        if(state==null)throw new SQLException("Missing service capabilities migration","55000");
        if(!Boolean.TRUE.equals(state.get("uploads_enabled")))
            throw new ShopException(423,"DOCUMENT_UPLOAD_PAUSED","Tạm ngừng thêm tài liệu PDF. Các tài liệu đã có vẫn được giữ lại.");
    }

    /** On-demand bounded probes; no filesystem paths, SQL errors or credentials in the response. */
    public Map<String,Object> dependencies() {
        boolean database = false, files = false;Map<String,Object> settings=null;
        try {
            settings = db.with(c -> Sql.one(c,"SELECT sales_enabled,downloads_enabled,uploads_enabled,version FROM st_operations WHERE id=1"));
            database = settings!=null;
        } catch (SQLException ignored) { /* Report this dependency without changing liveness. */ }
        try { storage.probe(); files = true; } catch (IOException ignored) { /* No exception text exposed. */ }
        var result = new LinkedHashMap<String,Object>();
        result.put("database", database ? "UP" : "DOWN");
        result.put("storage", files ? "UP" : "DOWN");
        result.put("status", database && files ? "READY" : "DEGRADED");
        result.put("configuration_version",settings==null?null:settings.get("version"));
        var capabilities=new LinkedHashMap<String,Object>();
        capabilities.put("static_files","AVAILABLE");
        capabilities.put("catalog",database?"AVAILABLE":"UNAVAILABLE");
        capabilities.put("order_create",capability(database,settings,"sales_enabled"));
        capabilities.put("document_download",capability(database&&files,settings,"downloads_enabled"));
        capabilities.put("document_upload",capability(database&&files,settings,"uploads_enabled"));
        result.put("capabilities",capabilities);
        result.put("checked_at", java.time.Instant.now().toString());
        return result;
    }
    private static String capability(boolean dependency,Map<String,Object> settings,String flag){
        if(settings!=null&&!Boolean.TRUE.equals(settings.get(flag)))return "PAUSED";
        return dependency?"AVAILABLE":"UNAVAILABLE";
    }

    public Map<String,Object> dashboard() throws SQLException {
        var result = new LinkedHashMap<>(settings(true));
        result.put("dependencies", dependencies());
        result.put("history", db.with(c -> Sql.rows(c,
            "SELECT sales_enabled,downloads_enabled,uploads_enabled,reason,version,created_at FROM st_operations_history ORDER BY id DESC LIMIT 20")));
        return result;
    }
}
