package com.example.proxy;

import com.example.proxy.config.ConfigLoader;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.server.ReverseProxyServer;
import com.example.proxy.util.AppLogger;

/**
 * Điểm khởi động chương trình Reverse Proxy.
 *
 * Cách chạy:
 *   java -cp target/classes com.example.proxy.Main config/application.properties
 *
 * Nếu không truyền tham số, mặc định dùng config/application.properties
 */
public class Main {

    private static final AppLogger log = AppLogger.of(Main.class);

    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "config/application.properties";

        log.info("Đang nạp cấu hình từ: " + configPath);
        ProxyConfig config = ConfigLoader.load(configPath);

        ReverseProxyServer server = new ReverseProxyServer(config);
        server.start();

        // Đảm bảo dừng server gọn gàng khi nhấn Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
