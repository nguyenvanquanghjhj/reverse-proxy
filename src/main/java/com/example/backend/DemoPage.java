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
        String[] mottos = {"Một sắc xanh. Một điểm đến.", "Một sắc cam. Một nhịp xử lý.", "Một sắc tím. Một kết nối mới."};
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
          <title>@@NAME@@ · PBL Request Studio</title>
          <link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='9' fill='%231c2928'/%3E%3Cpath d='M9 10h14M9 16h14M9 22h8' stroke='%238ef0ca' stroke-width='3'/%3E%3C/svg%3E">
          <style>
            :root{--ink:#192d2a;--muted:#6b7873;--line:#dfe5dd;--paper:#f6f7f2;--accent:@@ACCENT@@;--soft:@@SOFT@@;--bright:@@BRIGHT@@}
            *{box-sizing:border-box}body{margin:0;color:var(--ink);background:var(--paper);font-family:'Segoe UI',Arial,sans-serif;font-size:14px;-webkit-font-smoothing:antialiased}button,input,select{font:inherit}a{color:inherit;text-decoration:none}button,a,input,select{touch-action:manipulation}button{cursor:pointer}button:disabled{cursor:default;opacity:.45}[hidden]{display:none!important}:focus-visible{outline:3px solid var(--accent);outline-offset:4px}.mono{font-family:Consolas,monospace}.muted{color:var(--muted)}
            .shell{max-width:1240px;margin:auto;padding:0 40px}header{height:86px;border-bottom:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;gap:20px}.brand{display:flex;align-items:center;gap:11px;font-size:18px;font-weight:750;letter-spacing:-.5px}.mark{width:35px;height:35px;border-radius:10px;background:var(--ink);display:grid;align-content:center;gap:4px;padding:10px}.mark i{height:3px;background:var(--bright);border-radius:2px}.mark i:last-child{width:60%}.brand span{font-weight:400;color:var(--muted)}.badge{display:inline-flex;align-items:center;gap:8px;font-size:11px;font-weight:600;border:1px solid var(--line);border-radius:30px;padding:8px 12px;background:white}.dot{width:6px;height:6px;border-radius:50%;background:var(--accent)}.header-right{display:flex;align-items:center;gap:14px}.header-right>span{font-size:11px;color:var(--muted)}
            .intro{display:flex;justify-content:space-between;align-items:end;gap:30px;padding:40px 0 30px}.eyebrow{font-size:10px;font-weight:700;letter-spacing:2px;color:var(--accent);text-transform:uppercase}h1{font-size:clamp(30px,3.7vw,46px);font-weight:650;line-height:1.15;letter-spacing:-1.8px;margin:12px 0}h1 em{font-style:normal;color:var(--accent)}.intro p{color:var(--muted);line-height:1.7;margin:0;max-width:560px}.page-origin{text-align:right;font-size:11px;line-height:1.8;flex-shrink:0}.page-origin strong{color:var(--accent);font-weight:600}.page-origin a{display:block;text-decoration:underline;text-underline-offset:4px;margin-top:5px}
            .workspace{display:grid;grid-template-columns:minmax(0,1.6fr) minmax(280px,1fr);gap:22px;align-items:start}.panel{background:white;border:1px solid var(--line);border-radius:18px;padding:25px}.section-top{display:flex;justify-content:space-between;align-items:center;gap:14px;margin-bottom:23px}.section-top h2{font-size:15px;letter-spacing:-.2px;margin:0}.step{color:var(--muted);font-size:10px;letter-spacing:1px}.types{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:9px}.type{border:1px solid var(--line);background:white;border-radius:12px;padding:17px 12px;text-align:left;display:flex;flex-direction:column;gap:8px;color:var(--ink)}.type:hover{background:var(--paper)}.type[aria-pressed=true]{background:var(--soft);border-color:var(--accent)}.type-icon{width:32px;height:32px;display:grid;place-items:center;border-radius:9px;background:var(--paper);color:var(--accent);margin-bottom:3px}.type-icon svg{width:20px;height:20px;fill:none;stroke:currentColor;stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round}.type strong{font-size:12px}.type small{font-size:10px;color:var(--muted);line-height:1.5}
            .request-config{margin-top:22px;min-height:110px}.request-config p{font-size:12px;color:var(--muted);line-height:1.7;margin:0 0 14px}.field{display:flex;align-items:center;justify-content:space-between;gap:16px;font-size:12px}.field input,.field select{padding:9px 12px;border:1px solid var(--line);border-radius:8px;background:white;color:var(--ink);max-width:165px}.field input{width:80px}.endpoint{display:flex;align-items:center;gap:10px;background:var(--paper);border:1px solid var(--line);border-radius:9px;padding:11px 13px;margin:6px 0 18px;font-size:11px;overflow-wrap:anywhere}.method{font-size:10px;font-weight:700;letter-spacing:.5px;color:var(--accent)}.send-row{display:flex;align-items:center;justify-content:space-between;gap:10px}.send{background:var(--ink);border:0;border-radius:10px;padding:13px 23px;color:white;font-size:12px;font-weight:600;display:flex;gap:26px;align-items:center}.send:hover{background:var(--accent)}.pending{font-size:11px;color:var(--muted)}.help{font-size:10px!important;line-height:1.6!important;color:var(--muted);margin:13px 0 0}
            .distribution{background:#1c302b;color:#eff5ef;border-color:#1c302b}.distribution .muted{color:#acbdb3}.distribution .section-top{margin-bottom:18px}.flow{display:flex;align-items:center;gap:10px;font-size:11px;color:#d3dfd7;margin-bottom:23px}.flow .client{background:#ffffff0e;border:1px solid #ffffff20;border-radius:7px;padding:7px 10px}.strategy{font-size:10px;color:#b5d0bd}.server{display:grid;grid-template-columns:34px 1fr auto;gap:12px;align-items:center;padding:15px 0;border-top:1px solid #ffffff14}.server-icon{width:32px;height:36px;border-radius:7px;display:grid;place-items:center;font-size:11px;font-weight:700;color:#1c302b;background:var(--server)}.server-name{font-size:12px;font-weight:600}.server-name a[href]:hover{text-decoration:underline}.server-name small{font-size:10px;color:#a8beb0;font-weight:400;margin-left:5px}.bar{height:3px;background:#ffffff10;border-radius:5px;margin-top:9px;overflow:hidden}.bar i{height:100%;background:var(--server);display:block;width:0;transition:width .2s}.server-count{font-size:19px;font-variant-numeric:tabular-nums}.distribution .help{color:#a8beb0}.distribution .section-top>span{font-size:10px;color:#a8beb0}
            .stats{display:grid;grid-template-columns:repeat(3,1fr);border-top:1px solid var(--line);margin-top:23px;padding-top:19px;gap:10px}.stats small{font-size:10px;color:var(--muted);display:block;margin-bottom:5px}.stats strong{font-size:24px;font-weight:600;letter-spacing:-1px;font-variant-numeric:tabular-nums}.stats strong small{font-size:11px;display:inline;margin-left:3px;letter-spacing:0}.stats p{font-size:10px;color:var(--muted);margin:5px 0 0}.error-text{color:#b34830}
            .result-panel{margin-top:22px;padding-top:22px}.result-panel .section-top{margin-bottom:14px}.result-badge{font-size:10px;color:var(--muted)}.result-content{font-size:12px;line-height:1.7}.placeholder{color:var(--muted);padding:10px 0}.product-list{list-style:none;padding:0;margin:0}.product-list li{display:flex;justify-content:space-between;gap:12px;padding:9px 0;border-bottom:1px solid var(--line)}.product-list li:last-child{border:0}.product-list strong{font-weight:600}.result-content code{overflow-wrap:anywhere;font-size:11px}.save-file{display:inline-flex;margin-top:12px;padding:9px 14px;border:1px solid var(--line);border-radius:8px;font-size:11px;font-weight:600}.result-content p{margin:5px 0}.result-content .help{margin-top:9px}
            .log{margin:24px 0}.log .section-top{margin-bottom:16px}.log .section-top span{font-size:11px;color:var(--muted)}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;text-align:left;min-width:560px;font-size:11px}th{font-size:9px;letter-spacing:1px;text-transform:uppercase;color:var(--muted);font-weight:500;padding:0 12px 12px;border-bottom:1px solid var(--line)}td{padding:13px 12px;border-bottom:1px solid #edf0e9;font-variant-numeric:tabular-nums}tbody tr:last-child td{border:0}.status{font-size:10px;border-radius:5px;padding:4px 7px;background:var(--soft);color:var(--accent)}.status.error{background:#fff0eb;color:#b34830}.status.waiting{background:#f2f4ef;color:var(--muted)}.empty{text-align:center;color:var(--muted);padding:27px}.log-note{font-size:10px;line-height:1.6;color:var(--muted);margin:15px 0 0}
            details{font-size:11px;color:var(--muted);border-top:1px solid var(--line);padding:17px 0}summary{cursor:pointer}.technical{display:flex;flex-wrap:wrap;gap:12px 25px;padding-top:13px;line-height:1.7}footer{display:flex;justify-content:space-between;gap:15px;color:var(--muted);font-size:10px;padding:15px 0 28px;line-height:1.6}.noscript{background:#fff0eb;padding:15px}
            @media(max-width:850px){.shell{padding:0 23px}.workspace{grid-template-columns:minmax(0,1.2fr) minmax(255px,1fr);gap:16px}.panel{padding:20px}.intro{padding-top:30px}.type{padding:13px 9px}.type strong{font-size:11px}.header-right>span{display:none}}
            @media(max-width:650px){.shell{padding:0 16px}header{height:72px}.brand{font-size:15px}.header-right .badge{font-size:10px;padding:7px 9px}.intro{display:block;padding:26px 0 22px}h1{font-size:35px}.intro p{font-size:12px}.page-origin{text-align:left;margin-top:14px}.page-origin a{display:inline;margin-left:12px}.workspace{grid-template-columns:minmax(0,1fr)}.panel{padding:20px}.type{padding:15px 10px}.type strong{font-size:12px}.request-config{min-height:95px}.distribution{margin-top:2px}.log .section-top{align-items:start;flex-direction:column;gap:6px}.log{padding:20px 12px}.log .section-top,.log-note{padding:0 8px}.send{padding:13px 18px}.stats strong{font-size:23px}footer{flex-direction:column;gap:3px}.technical{flex-direction:column;gap:5px}}
            @media(prefers-reduced-motion:reduce){*{transition:none!important}}
          </style>
        </head>
        <body data-backend="@@PORT@@" data-request="@@REQUEST@@" data-via-proxy="@@VIA@@">
        <div class="shell">
          <header><a href="/demo" class="brand"><span class="mark" aria-hidden="true"><i></i><i></i><i></i></span>PBL<span>/</span>Request Studio</a><div class="header-right"><span>Hệ điều hành &amp; Mạng máy tính</span><span class="badge"><i class="dot"></i><span id="mode">Demo HTTP</span></span></div></header>
          <main>
            <section class="intro"><div><div class="eyebrow">@@NAME@@ / BACKEND @@NUMBER@@</div><h1>Mỗi thao tác.<br><em>Một công việc khác nhau.</em></h1><p>Xem sản phẩm, tải một tệp hay đặt đơn thử.<br>Gửi request và theo dõi server nào thực sự xử lý.</p></div><div class="page-origin muted">Trang này được trả bởi <strong>B@@NUMBER@@ · :@@PORT@@</strong><br><span id="origin"></span><a id="proxy-link" hidden>Chuyển sang Proxy ↗</a></div></section>
            <div class="workspace">
              <section class="panel" aria-labelledby="request-title">
                <div class="section-top"><h2 id="request-title">Bạn muốn làm gì?</h2><span class="step">01 / GỬI REQUEST</span></div>
                <div class="types" aria-label="Loại tác vụ">
                  <button class="type" data-kind="products" aria-pressed="true"><span class="type-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="4" width="6" height="6" rx="1"/><rect x="14" y="4" width="6" height="6" rx="1"/><rect x="4" y="14" width="6" height="6" rx="1"/><rect x="14" y="14" width="6" height="6" rx="1"/></svg></span><strong>Xem sản phẩm</strong><small>Đọc dữ liệu nhỏ</small></button>
                  <button class="type" data-kind="download" aria-pressed="false"><span class="type-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12m-5-5 5 5 5-5M4 16v4h16v-4"/></svg></span><strong>Tải tệp</strong><small>Truyền nhiều dữ liệu</small></button>
                  <button class="type" data-kind="orders" aria-pressed="false"><span class="type-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 7h12l1 14H5L6 7Zm3 0V5a3 3 0 0 1 6 0v2M9 13l2 2 4-4"/></svg></span><strong>Đặt đơn thử</strong><small>Gửi dữ liệu bằng POST</small></button>
                </div>
                <div class="request-config">
                  <div id="config-products"><p>Lấy danh sách 3 sản phẩm mẫu từ backend. Kết quả trả về dưới dạng JSON, giao diện hiển thị ở bên dưới.</p></div>
                  <div id="config-download" hidden><p>Tải tệp nhị phân thật. Tăng kích thước để quan sát lượng dữ liệu và thời gian truyền.</p><label class="field">Kích thước tệp<select id="file-size"><option value="64">64 KiB</option><option value="256" selected>256 KiB</option><option value="768">768 KiB</option></select></label></div>
                  <div id="config-orders" hidden><p>Đặt thử <strong>Sổ tay PBL · 49.000 đ/cuốn</strong>. Backend kiểm tra dữ liệu và tạo mã đơn demo.</p><label class="field">Số lượng<input id="quantity" type="number" min="1" max="10" value="1" aria-label="Số lượng sổ tay"></label></div>
                </div>
                <div class="endpoint"><span class="method" id="method">GET</span><code id="endpoint">/products</code></div>
                <div class="send-row"><button class="send" id="send">Gửi request <span aria-hidden="true">↗</span></button><span class="pending" id="pending" role="status">Sẵn sàng</span></div>
                <p class="help" id="action-note">Chỉ gửi khi bạn bấm. Có thể gửi tiếp khi request trước còn chờ.</p>
                <div class="stats"><div><small>ĐÃ HOÀN TẤT</small><strong id="finished">0</strong><p id="error-count">0 lỗi</p></div><div><small>THÀNH CÔNG</small><strong id="succeeded">0</strong><p>HTTP 2xx</p></div><div><small>ĐỘ TRỄ TB.</small><strong id="average">—</strong><p>Request thành công · ms</p></div></div>
              </section>
              <aside>
                <section class="panel distribution" aria-labelledby="distribution-title"><div class="section-top"><h2 id="distribution-title">Request đi đâu?</h2><span>02 / ĐÍCH ĐẾN</span></div><div class="flow"><span class="client">Trình duyệt</span><span>→</span><span id="route-target">Proxy</span><span>→</span><span>Backend</span></div><p class="strategy" id="strategy">Strategy xuất hiện sau phản hồi từ Proxy.</p>
                  <div class="server" data-server="9001" style="--server:#a9e4c7"><span class="server-icon">01</span><div><div class="server-name"><a data-port="9001">Aurora</a><small>:9001</small></div><div class="bar"><i></i></div></div><strong class="server-count">0</strong></div>
                  <div class="server" data-server="9002" style="--server:#f6c596"><span class="server-icon">02</span><div><div class="server-name"><a data-port="9002">Ember</a><small>:9002</small></div><div class="bar"><i></i></div></div><strong class="server-count">0</strong></div>
                  <div class="server" data-server="9003" style="--server:#c6b6f1"><span class="server-icon">03</span><div><div class="server-name"><a data-port="9003">Orbit</a><small>:9003</small></div><div class="bar"><i></i></div></div><strong class="server-count">0</strong></div>
                  <p class="help">Phản hồi xác định được backend trong lần mở trang này. Không bao gồm lượt tải giao diện.</p><p class="help" id="backend-links-note"></p>
                </section>
                <section class="panel result-panel" aria-labelledby="result-title"><div class="section-top"><h2 id="result-title">Kết quả nhận được</h2><span class="result-badge" id="result-meta">03 / PHẢN HỒI</span></div><div class="result-content" id="result" aria-live="polite"><p class="placeholder">Gửi tác vụ đầu tiên để xem dữ liệu thật từ backend.</p></div></section>
              </aside>
            </div>
            <section class="panel log" aria-labelledby="log-title"><div class="section-top"><h2 id="log-title">Nhật ký request</h2><span>20 request gần nhất · riêng trang này</span></div><div class="table-wrap"><table><thead><tr><th scope="col">#</th><th scope="col">Tác vụ</th><th scope="col">Backend</th><th scope="col">Thời gian</th><th scope="col">Dữ liệu về</th><th scope="col">Kết quả</th></tr></thead><tbody id="log"><tr><td colspan="6" class="empty">Chưa gửi tác vụ nào.</td></tr></tbody></table></div><p class="log-note">Thời gian đo tại trình duyệt, gồm chờ và nhận hết response. “Đang chờ” là request của tab, không phải tổng tải backend. Đây là demo tương tác, không thay thế benchmark.</p></section>
            <details><summary>Thông tin kỹ thuật của trang này</summary><div class="technical"><span>Backend: <b>127.0.0.1:@@PORT@@</b> (nội bộ máy chủ)</span><span>PID: <b>@@PID@@</b></span><span>Capacity: <b>@@CAPACITY@@</b></span><span>Đang xử lý / chờ: <b>@@ACTIVE@@ / @@QUEUED@@</b></span><span>Delay: <b>@@DELAY@@ ms</b></span><span>Heap: <b>@@HEAP@@ MiB</b></span><span>Snapshot: <time datetime="@@TIME@@">@@TIME@@</time></span></div></details>
          </main><footer><span>PBL4 / Reverse Proxy &amp; Load Balancing</span><span>Đơn hàng mô phỏng, không thanh toán hoặc lưu bền · Tệp demo tối đa 768 KiB</span></footer>
          <noscript><p class="noscript">Bật JavaScript để gửi tác vụ. Trang này vẫn là phản hồi thật từ backend @@PORT@@.</p></noscript>
        </div>
        <script>
        (() => {
          const $ = id => document.getElementById(id);
          const via = document.body.dataset.viaProxy === 'true';
          const local = ['127.0.0.1', 'localhost', '[::1]'].includes(location.hostname);
          $('mode').textContent = via ? 'Qua Reverse Proxy' : 'Backend trực tiếp';
          $('origin').textContent = location.host + '/demo';
          $('route-target').textContent = via ? 'Proxy :' + (location.port || '80') : 'Trực tiếp';
          if (!via) {
            const url = new URL('/demo', location.href); url.port = '8080';
            $('proxy-link').href = url.href; $('proxy-link').hidden = false;
            $('strategy').textContent = 'Truy cập trực tiếp · không qua cân bằng tải';
          }
          document.querySelectorAll('[data-port]').forEach(link => {
            if (local) { const url = new URL('/demo', location.href); url.port = link.dataset.port; link.href = url.href; }
          });
          $('backend-links-note').textContent = local ? 'Bấm tên server để mở trực tiếp trên máy chủ.' : 'Backend nội bộ; máy khách chỉ truy cập qua Proxy.';
          let kind = 'products', sequence = 0, pending = 0, finished = 0, succeeded = 0, totalMs = 0, objectUrl = null;
          const counts = {9001:0,9002:0,9003:0}; const rows = [];
          const titles = {products:'Xem sản phẩm',download:'Tải tệp',orders:'Đặt đơn thử'};
          function settings() {
            return {path: kind === 'download' ? '/download?sizeKiB=' + $('file-size').value : '/' + kind,
              method:kind === 'orders' ? 'POST' : 'GET'};
          }
          function configure() {
            document.querySelectorAll('[data-kind]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.kind === kind)));
            Object.keys(titles).forEach(key => $('config-' + key).hidden = key !== kind);
            const config = settings(); $('method').textContent = config.method; $('endpoint').textContent = config.path;
            $('action-note').textContent = kind === 'download' ? 'Tệp demo được nhận hết vào bộ nhớ. Chưa hỗ trợ tải file lớn bằng streaming.' : kind === 'orders' ? 'Đơn thử, không lưu bền. Mỗi lần bấm tạo một đơn mới; không tự retry.' : 'Chỉ gửi khi bạn bấm. Có thể gửi tiếp khi request trước còn chờ.';
          }
          document.querySelectorAll('[data-kind]').forEach(button => button.addEventListener('click', () => {kind = button.dataset.kind; configure();}));
          $('file-size').addEventListener('change', configure);
          function cell(row, text) { const td = document.createElement('td'); td.textContent = text; row.appendChild(td); return td; }
          function bytesText(n) { return n < 1024 ? n + ' B' : (n / 1024).toFixed(1) + ' KiB'; }
          function update() {
            $('pending').textContent = pending ? pending + ' đang chờ / tối đa 6' : 'Sẵn sàng'; $('send').disabled = pending >= 6;
            $('finished').textContent = finished; $('succeeded').textContent = succeeded;
            $('error-count').textContent = (finished - succeeded) + ' lỗi';
            $('average').textContent = succeeded ? (totalMs / succeeded).toFixed(1) : '—';
            const known = Object.values(counts).reduce((a,b) => a+b, 0);
            document.querySelectorAll('[data-server]').forEach(server => {
              const count = counts[server.dataset.server]; server.querySelector('.server-count').textContent = count;
              server.querySelector('.bar i').style.width = (known ? 100 * count / known : 0) + '%';
            });
            $('log').replaceChildren();
            rows.forEach(item => {
              const row = document.createElement('tr'); row.dataset.request = item.id;
              cell(row, String(item.id).padStart(2,'0')); cell(row, item.label); cell(row, item.backend || '—');
              cell(row, item.ms == null ? '—' : item.ms.toFixed(1) + ' ms'); cell(row, item.bytes == null ? '—' : bytesText(item.bytes));
              const badge = document.createElement('span'); badge.className = 'status' + (item.pending ? ' waiting' : item.ok ? '' : ' error');
              badge.textContent = item.pending ? 'Đang chờ' : item.status ? 'HTTP ' + item.status : 'Lỗi mạng';
              badge.title = item.message || ''; cell(row, '').appendChild(badge); $('log').appendChild(row);
            });
          }
          function paragraph(text, className) { const p = document.createElement('p'); p.textContent = text; if (className) p.className = className; $('result').appendChild(p); }
          function renderResult(item, data, blob, fileKiB) {
            if (objectUrl) { URL.revokeObjectURL(objectUrl); objectUrl = null; }
            $('result').replaceChildren(); $('result-meta').textContent = '#' + item.id + ' · ' + (item.backend || 'Không rõ backend');
            if (!item.ok) { paragraph(item.message || 'Request không thành công.', 'error-text'); return; }
            if (item.kind === 'products') {
              const list = document.createElement('ul'); list.className = 'product-list';
              data.products.forEach(product => { const li = document.createElement('li'); const name = document.createElement('span'); name.textContent = product.name; const price = document.createElement('strong'); price.textContent = product.price.toLocaleString('vi-VN') + ' đ'; li.append(name, price); list.appendChild(li); });
              $('result').appendChild(list);
            } else if (item.kind === 'orders') {
              paragraph('Đã tạo đơn thử · ' + data.quantity + ' × ' + data.product);
              const code = document.createElement('code'); code.textContent = data.orderId; $('result').appendChild(code);
              paragraph('Tổng mô phỏng: ' + data.total.toLocaleString('vi-VN') + ' đ'); paragraph('Không thanh toán, không lưu bền.', 'help');
            } else {
              paragraph('Đã nhận đủ ' + bytesText(blob.size) + ' từ backend.');
              objectUrl = URL.createObjectURL(blob); const save = document.createElement('a'); save.className = 'save-file'; save.href = objectUrl; save.download = 'pbl4-demo-' + fileKiB + 'KiB.bin'; save.textContent = 'Lưu tệp về máy ↓'; $('result').appendChild(save);
              paragraph('Lưu từ dữ liệu đã nhận, không gửi thêm HTTP request.', 'help');
            }
          }
          $('send').addEventListener('click', async () => {
            if (pending >= 6) return;
            if (kind === 'orders' && (!$('quantity').reportValidity() || !Number.isInteger(Number($('quantity').value)))) return;
            const config = settings(), fileKiB = Number($('file-size').value);
            const item = {id:++sequence,kind,label:titles[kind] + (kind === 'download' ? ' · ' + fileKiB + ' KiB' : ''),pending:true,ok:false};
            rows.unshift(item); if (rows.length > 20) rows.pop(); pending++; update();
            const controller = new AbortController(); const timeout = setTimeout(() => controller.abort(), 8000);
            const start = performance.now(); let data = null, blob = null;
            try {
              const options = {method:config.method,cache:'no-store',signal:controller.signal};
              if (item.kind === 'orders') { options.headers = {'Content-Type':'application/x-www-form-urlencoded'}; options.body = new URLSearchParams({productId:'notebook',quantity:$('quantity').value}).toString(); }
              const response = await fetch(config.path, options);
              item.status = response.status;
              const backend = response.headers.get('X-Proxy-Backend') || response.headers.get('X-Backend') || '';
              const backendPort = backend.includes(':') ? backend.split(':').pop() : backend.replace('backend-', '');
              if (Object.hasOwn(counts, backendPort)) { item.backend = 'B' + (Number(backendPort)-9000); counts[backendPort]++; }
              const strategy = response.headers.get('X-Proxy-Strategy'); if (via && strategy) $('strategy').textContent = 'Strategy · ' + strategy;
              const buffer = await response.arrayBuffer(); item.ms = performance.now() - start; item.bytes = buffer.byteLength;
              if (!response.ok) throw new Error('HTTP ' + response.status + ': ' + new TextDecoder().decode(buffer).slice(0,180));
              if (item.kind === 'download') {
                if (buffer.byteLength !== fileKiB * 1024 || !response.headers.get('Content-Type')?.includes('application/octet-stream')) throw new Error('Dữ liệu tệp không đúng kích thước hoặc định dạng.');
                blob = new Blob([buffer], {type:'application/octet-stream'});
              } else {
                data = JSON.parse(new TextDecoder().decode(buffer));
                if (item.kind === 'products' && !Array.isArray(data.products)) throw new Error('Response không chứa danh sách sản phẩm.');
                if (item.kind === 'orders' && (!data.orderId || data.demo !== true)) throw new Error('Response không chứa đơn demo hợp lệ.');
              }
              item.ok = true; succeeded++; totalMs += item.ms;
            } catch (error) {
              item.ms ??= performance.now() - start;
              item.message = error.name === 'AbortError' ? 'Quá 8 giây chờ ở trình duyệt. Backend có thể vẫn xử lý; không tự gửi lại đơn.' : String(error.message);
            } finally { clearTimeout(timeout); item.pending = false; pending--; finished++; update(); renderResult(item, data, blob, fileKiB); }
          });
        })();
        </script></body></html>
        """;
}

