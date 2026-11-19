package com.example.proxy.server;

import com.example.web.PageTemplate;

/** Local-only view. Live snapshots and dated, explicitly separated experiment results. */
final class MonitoringPage {
    private MonitoringPage() { }
    private static String benchmark() {
        try(var in=MonitoringPage.class.getResourceAsStream("/web/benchmark-comparison.html")) {
            if(in!=null){byte[] bytes=in.readNBytes(131073);if(bytes.length<=131072)return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);}
        }catch(java.io.IOException ignored){ }
        return "<p>Chưa có báo cáo thí nghiệm được đóng gói.</p>";
    }
    static final String HTML = PageTemplate.load("/web/monitoring.html").replace("%BENCHMARK_COMPARISON%",benchmark());
}
