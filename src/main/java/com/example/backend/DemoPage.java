package com.example.backend;

import java.time.Instant;
import java.util.Locale;

/** Self-contained request playground. Network traffic is initiated only by explicit user actions. */
final class DemoPage {
    private DemoPage() { }

    static String render(int port, int capacity, int active, int queued, int delayMs,
                         long requestNo, long heapBytes, boolean viaProxy) {
        int theme = Math.floorMod(port - 9001, 3);
        String number = port >= 9001 && port <= 9003 ? "0" + (port - 9000) : Integer.toString(port);
        String[] names = {"Aurora", "Ember", "Orbit"};
        String[] colors = {"#087f67", "#b94b1d", "#7051c2"};
        String[] bright = {"#8ef0ca", "#ffbf86", "#c8b2ff"};
        String[] soft = {"#e0f4ea", "#fff0e1", "#eee8fb"};
        return PAGE.replace("@@NUMBER@@", number).replace("@@NAME@@", names[theme])
                .replace("@@ACCENT@@", colors[theme]).replace("@@BRIGHT@@", bright[theme]).replace("@@SOFT@@", soft[theme])
                .replace("@@PORT@@", Integer.toString(port))
                .replace("@@PID@@", Long.toString(ProcessHandle.current().pid()))
                .replace("@@CAPACITY@@", Integer.toString(capacity)).replace("@@ACTIVE@@", Integer.toString(active))
                .replace("@@QUEUED@@", Integer.toString(queued)).replace("@@DELAY@@", Integer.toString(delayMs))
                .replace("@@REQUEST@@", Long.toString(requestNo)).replace("@@TIME@@", Instant.now().toString())
                .replace("@@HEAP@@", String.format(Locale.ROOT, "%.1f", heapBytes / 1048576.0))
                .replace("@@VIA@@", Boolean.toString(viaProxy));
    }

    private static final String PAGE = """
        <!doctype html>
        <html lang="vi">
        <head>
        <meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <title>@@NAME@@ · Trạm thử yêu cầu PBL</title>
        <link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='8' fill='%23087f67'/%3E%3Cpath d='M8 10h16M8 16h16M8 22h10' stroke='white' stroke-width='3'/%3E%3C/svg%3E">
        <style>
        /* Palette and typography */
        :root{color-scheme:light;--bg:#f3f5f8;--surface:#fff;--surface-elevated:#f7f9fb;--text:#17263b;--muted:#526177;--border:#d9e1eb;--accent:#245bd6;--success:#08745e;--warning:#92510f;--danger:#b52d40;--shadow:0 4px 20px #17263b06;--aurora:#08745e;--ember:#a44818;--orbit:#7051c2}
        :root[data-theme=dark]{color-scheme:dark;--bg:#0d1522;--surface:#152132;--surface-elevated:#1c2a3e;--text:#edf3fc;--muted:#a9bad1;--border:#33445c;--accent:#96b7ff;--success:#7ee0be;--warning:#ffc782;--danger:#ffa5b1;--shadow:none;--aurora:#7ee0be;--ember:#ffc08c;--orbit:#c9b5ff}
        *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.55 'Segoe UI',Arial,sans-serif;-webkit-font-smoothing:antialiased}button,input,select{font:inherit}button,a,input,select{touch-action:manipulation}button{cursor:pointer}button:disabled{cursor:wait;opacity:.65}a{color:var(--accent);text-underline-offset:4px}button:focus-visible,a:focus-visible,input:focus-visible,select:focus-visible,summary:focus-visible,[tabindex]:focus-visible{outline:3px solid var(--accent);outline-offset:4px}[hidden]{display:none!important}svg{width:24px;height:24px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round;flex-shrink:0}h1,h2,h3,p{margin:0}h1{font-size:clamp(27px,3.2vw,42px);line-height:1.18;letter-spacing:-1.3px}h2{font-size:18px;letter-spacing:-.4px}h3{font-size:15px}small,.help{font-size:12px;color:var(--muted)}code,pre{font-family:Consolas,monospace}code{overflow-wrap:anywhere}.muted{color:var(--muted)}.eyebrow{font-size:11px;letter-spacing:1.6px;font-weight:700;color:var(--accent);text-transform:uppercase}.shell{max-width:1400px;margin:auto;padding:0 32px}.skip{position:absolute;left:16px;top:-100px;background:var(--surface);padding:12px;z-index:3}.skip:focus{top:12px}
        /* Header and overview */
        header{min-height:76px;display:flex;align-items:center;justify-content:space-between;gap:16px;border-bottom:1px solid var(--border)}.brand{display:flex;align-items:center;gap:10px;font-weight:700;color:var(--text);text-decoration:none}.mark{display:grid;place-items:center;background:var(--text);color:var(--surface);border-radius:10px;width:36px;height:36px}.brand small{font-weight:400}.header-meta,.badges,.section-top,.actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.header-meta{justify-content:flex-end;font-size:12px}.badge{display:inline-flex;align-items:center;gap:6px;border:1px solid var(--border);border-radius:6px;padding:4px 9px;font-size:11px;font-weight:650;background:var(--surface-elevated);overflow-wrap:anywhere}.mode{color:var(--accent)}.button{border:1px solid var(--border);background:var(--surface);color:var(--text);border-radius:8px;padding:8px 12px;text-decoration:none;font-weight:600}.button:hover{background:var(--surface-elevated)}.intro{display:flex;justify-content:space-between;align-items:center;gap:24px;padding:32px 0 24px}.intro p{color:var(--muted);margin:12px 0 16px;max-width:740px}.intro .eyebrow{margin-bottom:10px}.origin{text-align:right;flex-shrink:0}.origin strong{display:block;font-size:18px}.origin a{display:inline-block;margin-top:12px}.badges{gap:7px}
        /* Metrics and workspace */
        .metrics{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:10px;margin-bottom:22px}.metric{background:var(--surface);border:1px solid var(--border);border-radius:10px;padding:14px 16px;min-width:0}.metric strong{display:block;font-size:25px;line-height:1.4;font-variant-numeric:tabular-nums}.metric .word{font-size:16px;margin:7px 0}.workspace{display:grid;grid-template-columns:minmax(0,.9fr) minmax(0,1.3fr);gap:20px;align-items:start}.panel{background:var(--surface);border:1px solid var(--border);border-radius:14px;padding:24px;box-shadow:var(--shadow);min-width:0}.section-top{justify-content:space-between;margin-bottom:18px}.step{font-size:11px;color:var(--muted);letter-spacing:1px}.stack{display:grid;gap:20px}.types{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:9px;margin:14px 0}.type{text-align:left;border:1px solid var(--border);background:var(--surface);color:var(--text);border-radius:10px;padding:14px 11px;min-width:0;position:relative;transition:border-color .15s}.type:hover{background:var(--surface-elevated);border-color:var(--accent)}.type[aria-pressed=true]{border:2px solid var(--accent);padding:13px 10px;background:var(--surface-elevated)}.type[aria-pressed=true]:after{content:'✓';position:absolute;right:9px;top:8px;color:var(--accent);font-weight:700}.type svg{color:var(--accent);margin-bottom:8px}.type strong,.type small,.type code{display:block}.type code{font-size:11px;color:var(--accent);margin:5px 0}.type small{font-size:11px;line-height:1.5}.more{margin:18px 0;border-top:1px solid var(--border);padding-top:14px}summary{cursor:pointer;font-weight:600}.request-config{min-height:85px;background:var(--surface-elevated);border-radius:8px;padding:13px;margin:16px 0;color:var(--muted);font-size:13px}.field{display:flex;gap:12px;align-items:center;margin-top:10px;flex-wrap:wrap}input,select{min-height:36px;border:1px solid var(--border);border-radius:6px;background:var(--surface);color:var(--text);padding:5px 9px;max-width:100%}input{width:80px}.endpoint{display:flex;align-items:center;gap:12px;border:1px solid var(--border);padding:12px;border-radius:8px;background:var(--bg);margin-bottom:14px}.method{font-size:11px;font-weight:750;color:var(--success)}.send{display:flex;justify-content:center;align-items:center;gap:10px;width:100%;padding:13px;border:0;border-radius:9px;background:#245bd6;color:#fff;font-weight:650}.send:hover:not(:disabled){background:#1949b9}.send svg{width:19px;height:19px}.send-row .help{display:block;margin:10px 0}.pending{font-size:12px;color:var(--muted)}
        /* Routing and backend observations */
        .routing{border-top:3px solid var(--accent)}.flow{display:flex;align-items:center;gap:0;margin:23px 0}.node{flex:1;min-width:0;text-align:center;background:var(--surface-elevated);border:1px solid var(--border);border-radius:10px;padding:17px 8px}.node svg{display:block;margin:0 auto 8px}.node strong,.node small{display:block}.node strong{font-size:13px}.node small{font-size:11px;overflow-wrap:anywhere}.connector{flex:0 0 30px;color:var(--muted);text-align:center;font-size:22px}.selected-node{border:2px solid var(--node-color,var(--accent));color:var(--node-color,var(--accent))}.selected-node .badge{margin-top:7px;color:var(--node-color,var(--accent));font-size:10px}.route-note{font-size:12px;color:var(--muted);border-bottom:1px solid var(--border);padding-bottom:18px}.servers{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin:18px 0 12px}.server{border:1px solid var(--border);border-radius:10px;padding:12px;min-width:0;position:relative}.server[data-selected=true]{border:2px solid var(--server);padding:11px;background:var(--surface-elevated)}.server-name{font-size:14px;font-weight:700;color:var(--server)}.server-name a{color:inherit}.server .badge{font-size:10px;margin:8px 0;min-height:25px}.server[data-selected=true] .badge{color:var(--server)}.server-number{font-size:10px;color:var(--muted);letter-spacing:1px}.server dl{margin:4px 0 0;font-size:11px}.server dl div{display:flex;justify-content:space-between;gap:5px;margin-top:4px}.server dt{color:var(--muted)}.server dd{margin:0;font-variant-numeric:tabular-nums}.observed{margin-top:8px;font-size:10px;color:var(--muted)}.pulse{animation:arrive .65s ease-out 1}@keyframes arrive{0%{box-shadow:0 0 0 5px var(--border)}100%{box-shadow:0 0 0 0 transparent}}.decision{margin-top:18px;border-top:1px solid var(--border);padding-top:14px}.decision p{margin-top:9px}.decision-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:12px 0}.decision-grid div{min-width:0}.decision-grid strong{display:block;font-size:12px;overflow-wrap:anywhere}
        /* Response and session history */
        .result-panel[data-state=success]{border-left:3px solid var(--success)}.result-panel[data-state=error]{border-left:3px solid var(--danger)}.result-panel[data-state=waiting]{border-left:3px solid var(--warning)}.status{display:inline-block;font-size:11px;font-weight:650;border-radius:5px;padding:4px 8px;background:var(--surface-elevated);border:1px solid currentColor;color:var(--success)}.status.error{color:var(--danger)}.status.waiting{color:var(--warning)}.result-content p{margin:8px 0}.placeholder{text-align:center;color:var(--muted);padding:25px 10px}.result-details{display:flex;gap:8px 18px;flex-wrap:wrap;font-size:12px;color:var(--muted);margin-bottom:14px}.result-details code{width:100%}.error-text{color:var(--danger)}pre{background:var(--bg);border:1px solid var(--border);border-radius:8px;padding:14px;white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;max-height:260px;overflow:auto}.product-list{list-style:none;padding:0;margin:10px 0}.product-list li{display:flex;justify-content:space-between;gap:12px;border-bottom:1px solid var(--border);padding:9px 0}.save-file{display:inline-block;margin:8px 0;font-weight:650}.log{margin-top:20px}.table-wrap{overflow:auto}table{border-collapse:collapse;width:100%;text-align:left;font-size:12px;min-width:800px}th{color:var(--muted);font-size:11px;font-weight:600;background:var(--surface-elevated)}td,th{padding:12px 10px;border-bottom:1px solid var(--border);vertical-align:top}td small{display:block;font-size:10px;max-width:200px;overflow-wrap:anywhere}.empty{text-align:center;padding:30px}.log-note{margin-top:14px}.technical{margin-top:20px}.technical p{margin-top:10px;overflow-wrap:anywhere}footer{display:flex;justify-content:space-between;gap:16px;color:var(--muted);font-size:11px;padding:24px 0}.noscript{padding:16px;border:1px solid var(--warning)}
        .batch{margin-top:16px;padding:14px;border:1px solid var(--border);border-radius:10px;background:var(--surface-elevated)}.batch-heading{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap}.batch-copy,.batch-status{margin-top:8px}.batch-table-wrap{max-height:340px;overflow:auto;margin-top:10px;border:1px solid var(--border);border-radius:8px}.batch-table{min-width:560px}.batch-table td,.batch-table th{padding:7px 9px}.batch-table td:first-child{font-variant-numeric:tabular-nums}
        @media(min-width:1500px){.shell{padding:0 40px}.intro{padding-top:40px}}
        @media(max-width:1100px){.metrics{grid-template-columns:repeat(4,minmax(0,1fr))}.header-meta .header-observation{display:none}.workspace{grid-template-columns:minmax(0,1fr) minmax(0,1.2fr)}.panel{padding:18px}.types{gap:6px}.servers{gap:6px}.server{padding:9px}.server[data-selected=true]{padding:8px}}
        @media(max-width:850px){.workspace{grid-template-columns:minmax(0,1fr)}.intro{align-items:start}.origin{max-width:180px}.metrics{grid-template-columns:repeat(4,minmax(0,1fr))}.shell{padding:0 20px}}
        @media(max-width:540px){.shell{padding:0 12px}header{flex-wrap:wrap;padding:14px 0;gap:10px}.brand small{display:none}.header-meta{justify-content:flex-start;width:100%}.header-meta #host{flex:1}.intro{display:block;padding:24px 0 20px}.origin{text-align:left;max-width:none;margin-top:18px}.origin strong{display:inline;font-size:14px}.origin a{margin:8px 0 0 12px}.metrics{grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.metric{padding:10px 12px}.metric:last-child{grid-column:span 2}.metric strong{font-size:23px}.metric .word{margin:0}.panel{padding:16px}.types{grid-template-columns:1fr}.type{display:grid;grid-template-columns:30px 1fr;column-gap:10px;padding:12px}.type[aria-pressed=true]{padding:11px}.type svg{grid-row:span 3;margin-top:5px}.type code{margin:2px 0}.type strong{padding-right:14px}.type small{font-size:12px}.flow{gap:0}.node{padding:12px 5px}.node strong{font-size:11px}.connector{flex-basis:18px;font-size:16px}.node .badge{font-size:9px;padding:3px 5px}.servers{grid-template-columns:1fr}.server{padding:12px}.server[data-selected=true]{padding:11px}.server dl{display:grid;grid-template-columns:1fr 1fr;gap:2px 16px}.server-number{display:inline;margin-right:8px}.server-name{display:inline}.server .badge{float:right;margin:0}.server dl{clear:both;padding-top:8px}.decision-grid{grid-template-columns:1fr}.section-top{gap:7px}.section-top h2{font-size:17px}footer{flex-direction:column;gap:4px}.result-details{gap:8px}}
        @media(prefers-reduced-motion:reduce){*,*:before,*:after{animation:none!important;transition:none!important;scroll-behavior:auto!important}}
        </style>
        </head>
        <body data-backend="@@PORT@@" data-request="@@REQUEST@@" data-via-proxy="@@VIA@@">
        <a class="skip" href="#main">Đến nội dung chính</a>
        <svg hidden aria-hidden="true"><defs>
        <symbol id="icon-client" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8m-4-4v4M3 8h18"/></symbol>
        <symbol id="icon-proxy" viewBox="0 0 24 24"><path d="M4 12h16M8 5l4-3 4 3M12 2v20M8 19l4 3 4-3"/><circle cx="4" cy="12" r="2"/><circle cx="20" cy="12" r="2"/></symbol>
        <symbol id="icon-server" viewBox="0 0 24 24"><rect x="4" y="3" width="16" height="7" rx="2"/><rect x="4" y="14" width="16" height="7" rx="2"/><path d="M8 6.5h.01M8 17.5h.01M12 6.5h4m-4 11h4"/></symbol>
        <symbol id="icon-work" viewBox="0 0 24 24"><path d="m13 2-9 12h7l-1 8 10-13h-8l1-7Z"/></symbol>
        <symbol id="icon-slow" viewBox="0 0 24 24"><circle cx="12" cy="13" r="8"/><path d="M12 9v5l3 2M9 2h6m-3 0v3"/></symbol>
        <symbol id="icon-cpu" viewBox="0 0 24 24"><rect x="5" y="5" width="14" height="14" rx="2"/><path d="M9 1v4m6-4v4M9 19v4m6-4v4M1 9h4m-4 6h4m14-6h4m-4 6h4"/><rect x="9" y="9" width="6" height="6"/></symbol>
        <symbol id="icon-send" viewBox="0 0 24 24"><path d="m3 3 19 9-19 9 4-9-4-9Zm4 9h15"/></symbol>
        </defs></svg>
        <div class="shell">
        <header><a href="/demo" class="brand"><span class="mark"><svg aria-hidden="true"><use href="#icon-proxy"/></svg></span>PBL <span class="muted">/</span> Trạm thử yêu cầu <small>Thử nghiệm proxy ngược</small></a><div class="header-meta"><span id="host"></span><span id="mode" class="badge mode"></span><span class="header-observation" id="header-backend">@@NAME@@</span><span class="header-observation" id="header-strategy">—</span><button class="button" id="theme" aria-pressed="false">Giao diện tối</button></div></header>
        <main id="main">
        <section class="intro"><div><div class="eyebrow">PBL4 / Giám sát mạng</div><h1>Theo dấu từng yêu cầu.</h1><p>Gửi một tác vụ, quan sát đường đi và xác nhận máy chủ thực sự xử lý.</p><div class="badges"><span class="badge">Trang: @@NAME@@ · :@@PORT@@</span><span class="badge">PID @@PID@@</span><span class="badge">Số luồng xử lý @@CAPACITY@@</span><span class="badge">Độ trễ @@DELAY@@ ms</span><span class="badge" id="overview-strategy">Chiến lược —</span></div></div><div class="origin"><small>PHẢN HỒI TRANG TỪ</small><strong>Máy chủ @@NUMBER@@ / @@NAME@@</strong><a id="proxy-link" class="button" hidden>Đi qua proxy ↗</a><a id="monitor-link" class="button" href="/__proxy/dashboard" hidden>Giám sát máy cục bộ ↗</a></div></section>
        <section class="metrics" aria-label="Thống kê yêu cầu trong lần mở trang này"><div class="metric"><small>Đã hoàn tất</small><strong id="finished">0</strong></div><div class="metric"><small>Thành công · HTTP 2xx</small><strong id="succeeded">0</strong></div><div class="metric"><small>Thất bại</small><strong id="error-count">0</strong></div><div class="metric"><small>Độ trễ trung bình · ms</small><strong id="average">—</strong></div><div class="metric"><small>Trạng thái gần nhất</small><strong class="word" id="last-status">—</strong></div><div class="metric"><small>Máy chủ được chọn</small><strong class="word" id="selected-backend">@@NAME@@</strong></div><div class="metric"><small>Cách kết nối</small><strong class="word" id="metric-mode">—</strong></div></section>
        <div class="workspace">
        <section class="panel" aria-labelledby="request-title"><div class="section-top"><h2 id="request-title">Trạm thử yêu cầu</h2><span class="step">01 / GỬI YÊU CẦU</span></div><p class="muted">Chọn công việc để quan sát cách định tuyến.</p>
        <div class="types" role="group" aria-label="Request thử tải">
        <button class="type" data-kind="work" aria-pressed="true"><svg aria-hidden="true"><use href="#icon-work"/></svg><strong>Tác vụ nhẹ</strong><code>/work</code><small>Tác vụ nhẹ · quan sát phân phối yêu cầu.</small></button>
        <button class="type" data-kind="slow" aria-pressed="false"><svg aria-hidden="true"><use href="#icon-slow"/></svg><strong>Tác vụ chậm</strong><code>/slow</code><small>Chờ 2 giây · quan sát độ trễ phản hồi.</small></button>
        <button class="type" data-kind="compute" aria-pressed="false"><svg aria-hidden="true"><use href="#icon-cpu"/></svg><strong>Tính toán CPU</strong><code>/compute</code><small>Tính toán CPU · thử công việc nặng.</small></button>
        </div>
        <details class="more"><summary>Tác vụ ứng dụng <span class="muted">· 3 lựa chọn</span></summary><div class="types" role="group" aria-label="Tác vụ ứng dụng">
        <button class="type" data-kind="products" aria-pressed="false"><svg aria-hidden="true"><use href="#icon-server"/></svg><strong>Xem sản phẩm</strong><code>/products</code><small>GET JSON · đọc danh sách sản phẩm.</small></button>
        <button class="type" data-kind="download" aria-pressed="false"><svg aria-hidden="true"><path d="M12 3v12m-5-5 5 5 5-5M4 16v4h16v-4"/></svg><strong>Tải tệp</strong><code>/download</code><small>Tệp nhị phân · quan sát truyền dữ liệu.</small></button>
        <button class="type" data-kind="orders" aria-pressed="false"><svg aria-hidden="true"><path d="M6 7h12l1 14H5L6 7Zm3 0V5a3 3 0 0 1 6 0v2M9 13l2 2 4-4"/></svg><strong>Đặt đơn thử</strong><code>/orders</code><small>POST biểu mẫu · kiểm tra gửi dữ liệu.</small></button>
        </div></details>
        <div class="request-config"><p id="config-description"></p><label class="field" id="config-download" hidden>Kích thước tệp<select id="file-size"><option value="64">64 KiB</option><option value="256" selected>256 KiB</option><option value="768">768 KiB</option></select></label><label class="field" id="config-orders" hidden>Số lượng sổ tay<input id="quantity" type="number" min="1" max="10" step="1" required value="1"></label></div>
        <div class="endpoint"><span class="method" id="method">GET</span><code id="endpoint">/work?cost=1</code></div><div class="send-row"><button class="send" id="send"><svg aria-hidden="true"><use href="#icon-send"/></svg><span id="send-label">Gửi yêu cầu</span></button><span class="help" id="action-note" hidden>Chỉ gửi khi bạn bấm. Không tự gửi lại hoặc tự thăm dò.</span><span class="pending" id="pending" hidden role="status" aria-live="polite">Sẵn sàng · một yêu cầu mỗi lần</span></div>
        <section class="batch" id="batch-panel" hidden aria-labelledby="batch-title"><div class="batch-heading"><h3 id="batch-title">Gửi 300 request qua proxy</h3><button class="button" id="batch-send" type="button">Gửi 300 request</button></div><p class="help batch-status" id="batch-status" role="status" aria-live="polite">Chưa chạy.</p><div class="batch-table-wrap" role="region" aria-label="Kết quả 300 request, có thể cuộn" tabindex="0"><table class="batch-table"><thead><tr><th scope="col">#</th><th scope="col">Tác vụ</th><th scope="col">Máy chủ</th><th scope="col">Kết quả</th><th scope="col">Độ trễ</th></tr></thead><tbody id="batch-log"><tr><td colspan="5" class="empty">Bấm “Gửi 300 request” để bắt đầu.</td></tr></tbody></table></div></section>
        <div class="decision"><h3>Giải thích định tuyến</h3><div class="decision-grid"><div><small>Chế độ</small><strong id="decision-mode">—</strong></div><div><small>Chiến lược từ phản hồi</small><strong id="decision-strategy">—</strong></div><div><small>Máy chủ gần nhất</small><strong id="decision-backend">@@NAME@@ · phản hồi trang</strong></div><div><small>Yêu cầu gần nhất</small><strong id="decision-type">Tải trang /demo</strong></div><div><small>Đường dẫn gần nhất</small><strong id="decision-endpoint">—</strong></div></div><p class="help" id="decision-note"></p></div>
        </section>
        <div class="stack"><section class="panel routing" aria-labelledby="routing-title"><div class="section-top"><h2 id="routing-title">Yêu cầu đi đâu?</h2><span class="step">02 / ĐƯỜNG ĐI</span></div>
        <div class="flow"><div class="node"><svg aria-hidden="true"><use href="#icon-client"/></svg><strong>Máy khách</strong><small>Trình duyệt của bạn</small></div><span class="connector" aria-hidden="true">→</span><div class="node" id="proxy-node"><svg aria-hidden="true"><use href="#icon-proxy"/></svg><strong>Proxy ngược</strong><small id="proxy-host"></small></div><span class="connector" id="proxy-connector" aria-hidden="true">→</span><div class="node selected-node" id="backend-node"><svg aria-hidden="true"><use href="#icon-server"/></svg><strong id="flow-backend">@@NAME@@</strong><small id="flow-port">Máy chủ :@@PORT@@</small><span class="badge" id="flow-badge">Được chọn</span></div></div>
        <p class="route-note" id="strategy">Đang đọc thuật toán…</p>
        <div class="servers" id="servers"></div>
        </section>
        <section class="panel result-panel" id="result-panel" aria-labelledby="result-title"><div class="section-top"><h2 id="result-title">Kết quả phản hồi</h2><span id="result-meta" class="badge">03 / PHẢN HỒI</span></div><div id="result" class="result-content" aria-live="polite" aria-atomic="true"><div class="placeholder"><svg aria-hidden="true"><use href="#icon-send"/></svg><p>Chưa có yêu cầu nào.</p><small>Chọn tác vụ và gửi để xem phản hồi thật.</small></div></div><button id="copy" class="button" hidden>Sao chép phản hồi</button><span id="copy-status" class="help" role="status"></span></section></div>
        </div>
        <section class="panel log" aria-labelledby="log-title"><div class="section-top"><h2 id="log-title">Nhật ký yêu cầu</h2><span class="help" id="history-note">6 yêu cầu gần nhất · lưu trong thẻ trình duyệt / địa chỉ gốc</span></div><div class="table-wrap" role="region" aria-label="Lịch sử yêu cầu, có thể cuộn ngang" tabindex="0"><table><thead><tr><th scope="col">Kết quả</th><th scope="col">Tác vụ / thời điểm</th><th scope="col">Máy chủ</th><th scope="col">Kết nối / chiến lược</th><th scope="col">Độ trễ</th><th scope="col">Dữ liệu / lỗi</th></tr></thead><tbody id="log"></tbody></table></div><p class="help log-note">Số liệu phía trên tính trong lần mở trang này. Độ trễ gồm thời gian chờ và nhận hết phản hồi; trung bình chỉ tính yêu cầu thành công. Lịch sử giữ tối đa 6 yêu cầu hoàn tất, không gồm lượt tải trang.</p></section>
        </main><footer><span>PBL4 / Proxy ngược &amp; Cân bằng tải</span><span>Không tự thăm dò · Không tài nguyên ngoài · Đơn thử không thanh toán, không lưu bền</span></footer><noscript><p class="noscript">Bật JavaScript để gửi yêu cầu. Trang được trả bởi @@NAME@@, port @@PORT@@.</p></noscript></div>
        <script>
        (() => {
          'use strict';
          const $ = id => document.getElementById(id);
          $('monitor-link').hidden = !(@@VIA@@ && ['127.0.0.1','localhost','[::1]'].includes(location.hostname));
          const via = document.body.dataset.viaProxy === 'true', pagePort = document.body.dataset.backend;
          $('batch-panel').hidden = !via;
          const local = ['127.0.0.1','localhost','[::1]'].includes(location.hostname);
          const mode = via ? 'Qua proxy' : 'Kết nối trực tiếp';
          const names = {'9001':'Aurora','9002':'Ember','9003':'Orbit'};
          const colors = {'9001':'var(--aurora)','9002':'var(--ember)','9003':'var(--orbit)'};
          const historyKey = 'pbl-request-studio-history-v1';
          let kind = 'work', pending = false, batchRunning = false, finished = 0, succeeded = 0, totalMs = 0, objectUrl = null, copyText = '', rows = [];
          const strategyName = s => ({ROUND_ROBIN:'Luân phiên',LEAST_CONNECTIONS:'Ít kết nối nhất',ADAPTIVE:'Thích nghi'}[s] || s || '—');
          const titles = {work:'Tác vụ nhẹ',slow:'Tác vụ chậm',compute:'Tính toán CPU',products:'Xem sản phẩm',download:'Tải tệp',orders:'Đặt đơn thử'};
          const descriptions = {work:'GET nhẹ, cost = 1. Thời gian xử lý phụ thuộc độ trễ cấu hình của máy chủ.',slow:'Máy chủ chờ 2 giây. Theo dõi trạng thái đang gửi và độ trễ thực tế.',compute:'Máy chủ tính CPU với cost = 8. Đây là công việc thật, không phải số CPU giả lập.',products:'Lấy danh sách 3 sản phẩm mẫu dưới dạng JSON.',download:'Nhận đủ tệp nhị phân vào bộ nhớ trước khi lưu, tối đa 768 KiB.',orders:'Đặt thử Sổ tay PBL · 49.000 đ/cuốn. Mỗi lần gửi tạo một đơn thử mới.'};
          function element(tag, text, className) { const node = document.createElement(tag); if (text != null) node.textContent = text; if (className) node.className = className; return node; }
          function backendName(port) { return Object.hasOwn(names, port) ? names[port] : port ? 'Máy chủ :' + port : '—'; }
          function bytesText(n) { return n < 1024 ? n + ' B' : (n / 1024).toFixed(1) + ' KiB'; }
          function storageNotice() { $('history-note').textContent = '6 yêu cầu gần nhất · không thể lưu lịch sử trong thẻ'; }
          const shortText = (value, limit) => typeof value === 'string' && value.length <= limit;
          const validPort = value => typeof value === 'string' && String(Number(value)) === value && Number.isInteger(Number(value)) && Number(value) > 0 && Number(value) <= 65535;
          function validHistory(item) {
            return item && Object.hasOwn(titles,item.kind)
              && shortText(item.time,40) && Number.isFinite(Date.parse(item.time))
              && Number.isFinite(item.ms) && item.ms >= 0 && typeof item.ok === 'boolean'
              && shortText(item.path,100) && item.path.startsWith('/' + item.kind)
              && item.method === (item.kind === 'orders' ? 'POST' : 'GET')
              && ['Qua proxy','Kết nối trực tiếp'].includes(item.mode)
              && (item.port === null || validPort(item.port))
              && (item.strategy === null || shortText(item.strategy,100))
              && (item.status == null || Number.isInteger(item.status) && item.status >= 100 && item.status <= 599)
              && (!item.ok || item.status >= 200 && item.status < 300)
              && (item.bytes == null || Number.isSafeInteger(item.bytes) && item.bytes >= 0)
              && (item.message == null || shortText(item.message,500));
          }
          try { const saved = JSON.parse(sessionStorage.getItem(historyKey) || '[]'); if (Array.isArray(saved)) rows = saved.map(item => item && ({...item, mode:({'Through Proxy':'Qua proxy','Direct Backend':'Kết nối trực tiếp'}[item.mode] || item.mode)})).filter(validHistory).slice(0,6); } catch (_) { storageNotice(); }
          let dark = matchMedia('(prefers-color-scheme: dark)').matches;
          try { const saved = sessionStorage.getItem('pbl-theme'); if (saved === 'dark' || saved === 'light') dark = saved === 'dark'; } catch (_) { }
          function setTheme() { document.documentElement.dataset.theme = dark ? 'dark' : 'light'; $('theme').setAttribute('aria-pressed',String(dark)); $('theme').textContent = dark ? 'Giao diện sáng' : 'Giao diện tối'; }
          setTheme(); $('theme').addEventListener('click', () => { dark = !dark; setTheme(); try { sessionStorage.setItem('pbl-theme',dark ? 'dark' : 'light'); } catch (_) { } });
          $('host').textContent = location.host; $('mode').textContent = mode; $('metric-mode').textContent = via ? 'Qua proxy' : 'Trực tiếp'; $('decision-mode').textContent = mode;
          $('decision-endpoint').textContent = location.origin + '/demo'; $('proxy-host').textContent = location.host;
          $('proxy-node').hidden = !via; $('proxy-connector').hidden = !via;
          $('decision-note').textContent = via ? 'Xem điểm lựa chọn chi tiết tại trang giám sát cục bộ khi đã bật ghi quyết định.' : 'Yêu cầu đi thẳng tới máy chủ trong địa chỉ, không qua cân bằng tải.';
          if (!via) { const url = new URL('/demo',location.href); url.port = '8080'; $('proxy-link').href = url.href; $('proxy-link').hidden = false; }
          const ports = Object.keys(names); if (!ports.includes(pagePort)) { ports.push(pagePort); names[pagePort] = '@@NAME@@'; colors[pagePort] = 'var(--accent)'; }
          ports.forEach((port,index) => {
            const card = element('article',null,'server'); card.dataset.server = port; card.style.setProperty('--server',colors[port]);
            card.append(element('div','MÁY CHỦ ' + (index + 1).toString().padStart(2,'0'),'server-number'));
            const title = element('div',null,'server-name'), link = element('a',names[port]);
            if (local) { const url = new URL('/demo',location.href); url.port = port; link.href = url.href; }
            title.append(link,element('small',' :' + port)); card.append(title,element('span',port === pagePort ? 'Được chọn' : 'Sẵn sàng','badge'));
            card.append(element('dl')); $('servers').append(card);
          });
          function renderBackendStats(backend) {
            const card = document.querySelector('[data-server="' + backend.port + '"]'); if (!card) return;
            const fmt = value => Number.isFinite(value) ? value.toLocaleString('vi-VN') : 'Đang đọc…';
            const heap = Number.isFinite(backend.heapUsedBytes) ? (backend.heapUsedBytes / 1048576).toFixed(1) + ' MiB' : 'Đang đọc…';
            const rows = [['PID',fmt(backend.backendPid)],['Số luồng xử lý',fmt(backend.backendCapacity ?? backend.capacity)],
              ['Đang xử lý / chờ',fmt(backend.remoteActiveRequests) + ' / ' + fmt(backend.remoteQueuedRequests)],
              ['Độ trễ tác vụ nhẹ',fmt(backend.configuredDelayMs) + ' ms'],
              ['Tổng yêu cầu',fmt(backend.backendReceivedRequests ?? backend.completed)],['Bộ nhớ JVM',heap]];
            const dl = card.querySelector('dl'); dl.replaceChildren(); rows.forEach(([key,value]) => { const row = element('div'); row.append(element('dt',key),element('dd',value)); dl.append(row); });
          }
          async function refreshBackendStats(tries = 8) {
            if (!via) return;
            let incomplete = false;
            try {
              const response = await fetch('/__proxy/metrics',{cache:'no-store',signal:AbortSignal.timeout(2500)});
              if (!response.ok) throw new Error('HTTP ' + response.status);
              const snapshot = await response.json(); if (!Array.isArray(snapshot.backends)) throw new Error('Dữ liệu máy chủ không hợp lệ');
              const runtimeByPort = new Map((snapshot.backendRuntime || []).map(backend => [String(backend.port),backend]));
              snapshot.backends.forEach(backend => renderBackendStats({...backend,...runtimeByPort.get(String(backend.port))}));
              if (snapshot.strategy) {
                const label = strategyName(snapshot.strategy); $('header-strategy').textContent = label;
                $('overview-strategy').textContent = 'Chiến lược · ' + label; $('decision-strategy').textContent = label;
                $('strategy').textContent = 'Thuật toán: ' + label;
              }
              incomplete = snapshot.backends.some(backend => {
                const runtime = runtimeByPort.get(String(backend.port));
                return !runtime || !Number.isFinite(runtime.backendPid) || !Number.isFinite(runtime.heapUsedBytes);
              });
            } catch (_) { incomplete = true; }
            if (incomplete && tries > 0) setTimeout(() => refreshBackendStats(tries - 1),300);
          }
          const pageStats = {port:Number(pagePort),backendPid:Number('@@PID@@'),backendCapacity:Number('@@CAPACITY@@'),
            remoteActiveRequests:Number('@@ACTIVE@@'),remoteQueuedRequests:Number('@@QUEUED@@'),configuredDelayMs:Number('@@DELAY@@'),
            backendReceivedRequests:Number('@@REQUEST@@'),heapUsedBytes:Number('@@HEAP@@') * 1048576};
          ports.forEach(port => { if (port === pagePort) renderBackendStats(pageStats); });
          function routing(port, strategy, initial = false) {
            const name = backendName(port), color = colors[port] || 'var(--accent)';
            $('selected-backend').textContent = name; $('header-backend').textContent = name; $('flow-backend').textContent = name === '—' ? 'Chưa xác định' : name;
            $('flow-port').textContent = port ? 'Máy chủ :' + port : 'Chưa xác định'; $('flow-badge').textContent = port ? 'Được chọn' : 'Chưa xác định';
            $('backend-node').style.setProperty('--node-color',color); $('decision-backend').textContent = name;
            const strategyText = via ? strategyName(strategy) : 'Không áp dụng';
            $('header-strategy').textContent = via ? strategyText : ''; $('overview-strategy').textContent = 'Chiến lược · ' + strategyText; $('decision-strategy').textContent = strategyText;
            $('strategy').textContent = via ? strategy ? 'Thuật toán: ' + strategyName(strategy) : 'Đang đọc thuật toán…' : 'Kết nối trực tiếp · Không qua proxy.';
            document.querySelectorAll('[data-server]').forEach(card => { const p = card.dataset.server; card.dataset.selected = String(p === port); card.querySelector('.badge').textContent = p === port ? 'Được chọn' : 'Sẵn sàng'; });
            if (!initial) { $('backend-node').classList.remove('pulse'); void $('backend-node').offsetWidth; $('backend-node').classList.add('pulse'); }
          }
          routing(pagePort,null,true); refreshBackendStats();
          function settings() { return {path:kind === 'work' ? '/work?cost=1' : kind === 'compute' ? '/compute?cost=8' : kind === 'download' ? '/download?sizeKiB=' + $('file-size').value : '/' + kind,method:kind === 'orders' ? 'POST' : 'GET'}; }
          function configure() {
            document.querySelectorAll('[data-kind]').forEach(button => button.setAttribute('aria-pressed',String(button.dataset.kind === kind)));
            $('config-description').textContent = descriptions[kind]; $('config-download').hidden = kind !== 'download'; $('config-orders').hidden = kind !== 'orders';
            const config = settings(); $('method').textContent = config.method; $('endpoint').textContent = config.path;
            $('action-note').textContent = kind === 'orders' ? 'Đơn thử không lưu bền. Không tự gửi lại khi lỗi hoặc hết thời gian chờ.' : 'Chỉ gửi khi bạn bấm. Không tự gửi lại hoặc tự thăm dò.';
          }
          document.querySelectorAll('[data-kind]').forEach(button => button.addEventListener('click',() => {kind = button.dataset.kind; configure();})); $('file-size').addEventListener('change',configure); configure();
          function statusText(item) { return item.status ? 'HTTP ' + item.status : 'Lỗi mạng'; }
          function renderHistory() {
            $('log').replaceChildren();
            if (!rows.length) { const tr = element('tr'), td = element('td','Chưa có yêu cầu. Bắt đầu từ Trạm thử yêu cầu phía trên.','empty'); td.colSpan = 6; tr.append(td); $('log').append(tr); }
            rows.forEach(item => {
              const tr = element('tr'); const cells = Array.from({length:6},() => element('td')); cells[0].append(element('span',(item.ok ? '✓ ' : '✕ ') + statusText(item),'status' + (item.ok ? '' : ' error')));
              cells[1].append(element('div',titles[item.kind]),element('small',new Date(item.time).toLocaleString('vi-VN')),element('small',item.path));
              cells[2].textContent = backendName(item.port); cells[3].append(element('div',item.mode),element('small',item.mode === 'Kết nối trực tiếp' ? 'Không áp dụng' : strategyName(item.strategy)));
              cells[4].textContent = item.ms.toFixed(1) + ' ms'; cells[5].textContent = item.bytes == null ? '—' : bytesText(item.bytes); if (item.message) cells[5].append(element('small',item.message,'error-text'));
              tr.append(...cells); $('log').append(tr);
            });
          }
          renderHistory();
          function update() {
            $('send').disabled = pending || batchRunning; $('send-label').textContent = pending ? 'Đang gửi yêu cầu…' : 'Gửi yêu cầu'; $('send').setAttribute('aria-busy',String(pending || batchRunning)); $('batch-send').disabled = pending || batchRunning; $('batch-send').textContent = batchRunning ? 'Đang gửi…' : 'Gửi 300 request';
            $('pending').textContent = pending ? 'Đang chờ phản hồi · không gửi lặp' : batchRunning ? 'Đang chạy 300 request' : 'Sẵn sàng · một yêu cầu mỗi lần';
            document.querySelectorAll('[data-kind],#file-size,#quantity').forEach(control => { control.disabled = batchRunning || pending; });
            $('finished').textContent = finished; $('succeeded').textContent = succeeded; $('error-count').textContent = finished - succeeded; $('average').textContent = succeeded ? (totalMs / succeeded).toFixed(1) : '—';
          }
          function releaseDownload() { if (objectUrl) { URL.revokeObjectURL(objectUrl); objectUrl = null; } }
          // Keep the URL when the browser caches this page for Back navigation.
          window.addEventListener('pagehide', event => { if (!event.persisted) releaseDownload(); });
          function renderResult(item,data,blob,fileKiB,raw) {
            releaseDownload();
            $('result').replaceChildren(); $('copy-status').textContent = ''; $('copy').hidden = true;
            $('result-panel').dataset.state = item.ok ? 'success' : 'error'; $('result-meta').className = 'status' + (item.ok ? '' : ' error');
            $('result-meta').textContent = (item.ok ? '✓ Thành công · ' : '✕ ') + statusText(item);
            const meta = element('div',null,'result-details'); meta.append(element('span',backendName(item.port)),element('span',item.mode),element('span',via ? strategyName(item.strategy) : 'Chiến lược · Không áp dụng'),element('span',item.ms.toFixed(1) + ' ms'),element('code',item.method + ' ' + item.path)); $('result').append(meta);
            if (!item.ok) { $('result').append(element('h3',item.status === 503 ? 'Máy chủ / dịch vụ không sẵn sàng' : item.status ? item.status >= 400 ? 'Lỗi HTTP' : 'Phản hồi không hợp lệ' : 'Không nhận được phản hồi'),element('p',item.message,'error-text')); }
            else if (item.kind === 'products') { const list = element('ul',null,'product-list'); data.products.forEach(product => {const li = element('li'); li.append(element('span',product.name),element('strong',Number(product.price).toLocaleString('vi-VN') + ' đ')); list.append(li);}); $('result').append(list); }
            else if (item.kind === 'orders') { $('result').append(element('p','Đã tạo đơn thử · ' + data.quantity + ' × ' + data.product),element('code',data.orderId),element('p','Tổng mô phỏng: ' + Number(data.total).toLocaleString('vi-VN') + ' đ'),element('p','Không thanh toán, không lưu bền.','help')); }
            else if (item.kind === 'download') { $('result').append(element('p','Đã nhận đủ ' + bytesText(blob.size) + ' từ máy chủ.')); objectUrl = URL.createObjectURL(blob); const save = element('a','Lưu tệp về máy ↓','save-file'); save.href = objectUrl; save.download = 'pbl4-demo-' + fileKiB + 'KiB.bin'; $('result').append(save,element('p','Lưu từ dữ liệu đã nhận, không gửi thêm yêu cầu HTTP.','help')); }
            copyText = raw || item.message || ''; if (raw) $('result').append(element('pre',raw));
            $('copy').hidden = !copyText || !navigator.clipboard?.writeText;
          }
          $('copy').addEventListener('click',async () => { try { await navigator.clipboard.writeText(copyText); $('copy-status').textContent = ' Đã sao chép.'; } catch (_) { $('copy-status').textContent = ' Không thể sao chép; hãy chọn văn bản phản hồi.'; } });
          $('send').addEventListener('click',async () => {
            if (pending || batchRunning) return;
            if (kind === 'orders' && (!$('quantity').reportValidity() || !Number.isInteger(Number($('quantity').value)))) return;
            const config = settings(), fileKiB = Number($('file-size').value), quantity = $('quantity').value;
            const item = {kind,path:config.path,method:config.method,time:new Date().toISOString(),mode,ok:false,port:null,strategy:null};
            releaseDownload();
            pending = true; update(); $('result-panel').dataset.state = 'waiting'; $('result-meta').className = 'status waiting'; $('result-meta').textContent = 'Đang gửi'; $('result').replaceChildren(element('p','Đang chờ ' + item.method + ' ' + item.path + '…','placeholder')); $('copy').hidden = true; $('copy-status').textContent = '';
            const controller = new AbortController(), timeout = setTimeout(() => controller.abort(),8000), start = performance.now(); let data = null, blob = null, raw = '';
            try {
              const options = {method:config.method,cache:'no-store',signal:controller.signal};
              if (item.kind === 'orders') { options.headers = {'Content-Type':'application/x-www-form-urlencoded'}; options.body = new URLSearchParams({productId:'notebook',quantity}).toString(); }
              const response = await fetch(config.path,options); item.status = response.status;
              const backend = response.headers.get('X-Proxy-Backend') || response.headers.get('X-Backend') || '';
              const port = backend.includes(':') ? backend.split(':').pop() : backend.startsWith('backend-') ? backend.slice(8) : '';
              if (validPort(port)) item.port = port;
              item.strategy = via ? response.headers.get('X-Proxy-Strategy') : null;
              const buffer = await response.arrayBuffer(); item.ms = performance.now() - start; item.bytes = buffer.byteLength;
              if (item.kind !== 'download' || !response.ok) raw = new TextDecoder().decode(buffer);
              if (!response.ok) throw new Error('HTTP ' + response.status + ': ' + raw.slice(0,180));
              if (item.kind === 'download') { if (buffer.byteLength !== fileKiB * 1024 || !response.headers.get('Content-Type')?.includes('application/octet-stream')) throw new Error('Dữ liệu tệp không đúng kích thước hoặc định dạng.'); blob = new Blob([buffer],{type:'application/octet-stream'}); }
              else {
                data = JSON.parse(raw); raw = JSON.stringify(data,null,2);
                if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('Phản hồi không chứa đối tượng JSON hợp lệ.');
                if (item.kind === 'products' && (!Array.isArray(data.products) || !data.products.every(product => product && typeof product.name === 'string' && Number.isFinite(product.price) && product.price >= 0))) throw new Error('Phản hồi không chứa danh sách sản phẩm hợp lệ.');
                if (item.kind === 'orders' && (typeof data.orderId !== 'string' || !data.orderId || data.demo !== true || typeof data.product !== 'string' || !Number.isInteger(data.quantity) || data.quantity < 1 || data.quantity > 10 || !Number.isFinite(data.total) || data.total < 0)) throw new Error('Phản hồi không chứa đơn thử hợp lệ.');
              }
              item.ok = true; succeeded++; totalMs += item.ms;
            } catch (error) { item.ms ??= performance.now() - start; item.message = error.name === 'AbortError' ? 'Quá 8 giây chờ ở trình duyệt. Máy chủ có thể vẫn xử lý; không tự gửi lại đơn.' : String(error.message); }
            finally {
              clearTimeout(timeout); pending = false; finished++; update(); $('last-status').textContent = statusText(item);
              rows.unshift(item); rows = rows.slice(0,6); try { sessionStorage.setItem(historyKey,JSON.stringify(rows)); } catch (_) { storageNotice(); }
              routing(item.port,item.strategy); $('decision-type').textContent = titles[item.kind]; $('decision-endpoint').textContent = item.method + ' ' + location.origin + item.path; refreshBackendStats();
              renderHistory(); renderResult(item,data,blob,fileKiB,raw);
            }
          });
          const batchCycle = [{kind:'work',path:'/work?cost=1'},{kind:'slow',path:'/slow?cost=1'},{kind:'compute',path:'/compute?cost=8'},{kind:'work',path:'/work?cost=1'},{kind:'compute',path:'/compute?cost=8'},{kind:'work',path:'/work?cost=1'},{kind:'work',path:'/work?cost=1'},{kind:'compute',path:'/compute?cost=8'},{kind:'work',path:'/work?cost=1'},{kind:'work',path:'/work?cost=1'}];
          let batchRouteItem = null, batchRouteQueued = false, batchSuccessStart = 0;
          function showBatchRoute(item) { if (!item) return; routing(item.port,item.strategy); $('decision-type').textContent = titles[item.kind]; $('decision-endpoint').textContent = 'GET ' + location.origin + item.path; }
          function queueBatchRoute(item) { batchRouteItem = item; if (batchRouteQueued) return; batchRouteQueued = true; requestAnimationFrame(() => { batchRouteQueued = false; showBatchRoute(batchRouteItem); }); }
          function makeBatchRow(index, spec) {
            const tr = element('tr'), cells = Array.from({length:5},() => element('td'));
            cells[0].textContent = String(index + 1); cells[1].textContent = titles[spec.kind]; cells[2].textContent = '—';
            const status = element('span','Chờ gửi','status waiting'); cells[3].append(status); cells[4].textContent = '—';
            tr.append(...cells); $('batch-log').append(tr); return {tr,cells,status};
          }
          function batchProgress(started, completed, total, done = false) {
            const successful = succeeded - batchSuccessStart;
            $('batch-status').textContent = done
              ? 'Xong · ' + completed + '/' + total + ' request đã kết thúc · ' + successful + ' thành công · ' + (completed - successful) + ' lỗi.'
              : 'Đang gửi ' + started + '/' + total + ' · hoàn tất ' + completed + '/' + total + '.';
          }
          $('batch-send').addEventListener('click',async () => {
            if (!via || pending || batchRunning) return;
            const total = 300, ratePerSecond = 60, maxWorkers = 64, specs = Array.from({length:total},(_,index) => batchCycle[index % batchCycle.length]);
            const log = $('batch-log'), resultRows = new Array(total); let next = 0, completed = 0, started = 0, lastItem = null;
            log.replaceChildren(); specs.forEach((spec,index) => { resultRows[index] = makeBatchRow(index,spec); });
            batchRunning = true; batchSuccessStart = succeeded; update(); batchProgress(0,0,total); $('batch-status').scrollIntoView({block:'nearest'});
            const launchAt = performance.now();
            async function worker() {
              while (next < total) {
                const index = next++, spec = specs[index], row = resultRows[index];
                const wait = launchAt + index * (1000 / ratePerSecond) - performance.now(); if (wait > 0) await new Promise(resolve => setTimeout(resolve,wait));
                started++; row.status.textContent = 'Đang gửi'; batchProgress(started,completed,total);
                const item = {kind:spec.kind,path:spec.path,method:'GET',time:new Date().toISOString(),mode,ok:false,port:null,strategy:null};
                const controller = new AbortController(), timeout = setTimeout(() => controller.abort(),8000), start = performance.now();
                try {
                  const response = await fetch(spec.path,{method:'GET',cache:'no-store',signal:controller.signal}); item.status = response.status;
                  const backend = response.headers.get('X-Proxy-Backend') || response.headers.get('X-Backend') || '', port = backend.includes(':') ? backend.split(':').pop() : backend.startsWith('backend-') ? backend.slice(8) : '';
                  if (validPort(port)) item.port = port;
                  item.strategy = via ? response.headers.get('X-Proxy-Strategy') : null;
                  const buffer = await response.arrayBuffer(); item.ms = performance.now() - start; item.bytes = buffer.byteLength; item.ok = response.ok;
                  if (item.ok) { succeeded++; totalMs += item.ms; } else item.message = 'HTTP ' + response.status;
                } catch (error) {
                  item.ms = performance.now() - start; item.message = error.name === 'AbortError' ? 'Quá 8 giây chờ ở trình duyệt.' : String(error.message);
                } finally {
                  clearTimeout(timeout); finished++; completed++; lastItem = item; update(); $('last-status').textContent = statusText(item);
                  row.tr.dataset.state = item.ok ? 'success' : 'error'; row.cells[2].textContent = backendName(item.port); row.status.textContent = statusText(item); row.status.className = 'status' + (item.ok ? '' : ' error'); row.cells[4].textContent = item.ms.toFixed(1) + ' ms';
                  rows.unshift(item); rows = rows.slice(0,6); queueBatchRoute(item); batchProgress(started,completed,total);
                }
              }
            }
            try { await Promise.all(Array.from({length:maxWorkers},() => worker())); }
            finally {
              batchRunning = false; update(); batchProgress(started,completed,total,true); refreshBackendStats();
              try { sessionStorage.setItem(historyKey,JSON.stringify(rows)); } catch (_) { storageNotice(); }
              renderHistory(); showBatchRoute(lastItem);
            }
          });
        })();
        </script></body></html>
        """;
}
