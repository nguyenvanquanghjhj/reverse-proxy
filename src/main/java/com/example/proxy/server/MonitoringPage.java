package com.example.proxy.server;

/** Local-only view. Live snapshots and dated, explicitly separated experiment results. */
final class MonitoringPage {
    private MonitoringPage() { }
    private static String benchmark() {
        try(var in=MonitoringPage.class.getResourceAsStream("/web/benchmark-comparison.html")) {
            if(in!=null){byte[] bytes=in.readNBytes(131073);if(bytes.length<=131072)return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);}
        }catch(java.io.IOException ignored){ }
        return "<p>Chưa có báo cáo thí nghiệm được đóng gói. Không suy ra benchmark từ metrics realtime.</p>";
    }
    static final String HTML = """
        <!doctype html><html lang="vi"><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <link rel="icon" href="data:,">
        <title>Giám sát cân bằng tải</title>
        <style>
        :root{color-scheme:light;--bg:#eef1f6;--sf:#fff;--sf2:#f5f7fb;--tx:#142033;--mu:#586880;--bd:#dae2ed;--ac:#2457d6;--ok:#0a7d63;--warn:#9a5b0c;--bad:#c0293f;--c0:#087f67;--c1:#b94b1d;--c2:#7051c2;--sh:0 1px 2px #14203308,0 8px 24px #1420330a}
        :root[data-theme=dark]{color-scheme:dark;--bg:#0a111e;--sf:#121c2d;--sf2:#192537;--tx:#eaf1fb;--mu:#9db0c8;--bd:#2a3a54;--ac:#8fb0ff;--ok:#6fe0bb;--warn:#ffc27a;--bad:#ff9aab;--c0:#6fe0bb;--c1:#ffb98a;--c2:#c7b2ff;--sh:none}
        *{box-sizing:border-box}[hidden]{display:none!important}body{margin:0;background:var(--bg);color:var(--tx);font:14px/1.55 system-ui,'Segoe UI',Roboto,Arial,sans-serif;-webkit-font-smoothing:antialiased}
        h1,h2,p{margin:0}button,a{font:inherit}a{color:var(--ac)}.mono,.num,td,strong,code{font-variant-numeric:tabular-nums}code,pre,.mono{font-family:ui-monospace,Consolas,monospace}
        button{cursor:pointer;border:1px solid var(--bd);background:var(--sf);color:var(--tx);border-radius:8px;padding:7px 12px;font-weight:600}button:hover:not(:disabled){background:var(--sf2);border-color:var(--ac)}button:disabled{opacity:.5;cursor:not-allowed}button:focus-visible,a:focus-visible,summary:focus-visible{outline:3px solid var(--ac);outline-offset:2px}
        .primary{background:var(--ac);border-color:var(--ac);color:#fff}:root[data-theme=dark] .primary{color:#0a111e}.primary:hover:not(:disabled){background:var(--ac);filter:brightness(1.1)}
        header{position:sticky;top:0;z-index:5;background:color-mix(in srgb,var(--bg) 88%,transparent);backdrop-filter:blur(10px);border-bottom:1px solid var(--bd)}
        .bar{max-width:1240px;margin:auto;padding:12px 24px;display:flex;align-items:center;gap:14px;flex-wrap:wrap}.brand{display:flex;align-items:center;gap:12px;margin-right:auto}.mark{width:38px;height:38px;border-radius:11px;display:grid;place-items:center;background:linear-gradient(135deg,var(--c0),var(--ac));color:#fff}.mark svg{width:22px;height:22px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}
        h1{font-size:18px;letter-spacing:-.3px;line-height:1.2}.sub{font-size:12px;color:var(--mu)}
        #state{display:inline-flex;align-items:center;gap:8px;margin:0;padding:5px 12px;border-radius:99px;font-size:12px;font-weight:650;border:1px solid var(--bd);background:var(--sf);max-width:420px}#state:before{content:'';width:8px;height:8px;border-radius:50%;background:var(--mu);flex:none}
        #state.ok{color:var(--ok);border-color:color-mix(in srgb,var(--ok) 40%,var(--bd))}#state.ok:before{background:var(--ok);box-shadow:0 0 0 0 var(--ok);animation:pulse 2s infinite}#state.bad{color:var(--bad);border-color:color-mix(in srgb,var(--bad) 45%,var(--bd))}#state.bad:before{background:var(--bad)}
        @keyframes pulse{70%{box-shadow:0 0 0 7px transparent}100%{box-shadow:0 0 0 0 transparent}}
        main{max-width:1240px;margin:auto;padding:22px 24px 40px}.chips{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:16px;color:var(--mu);font-size:12px}.chip{border:1px solid var(--bd);background:var(--sf);border-radius:99px;padding:3px 11px;font-weight:600;color:var(--tx)}
        .kpis{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:12px}.card{background:var(--sf);border:1px solid var(--bd);border-radius:14px;padding:16px 18px;box-shadow:var(--sh);min-width:0}.k small{display:block;font-size:11px;letter-spacing:.4px;text-transform:uppercase;color:var(--mu);font-weight:650;min-height:30px}.k strong{display:block;font-size:30px;line-height:1.15;letter-spacing:-1px;margin-top:4px}.k.good strong{color:var(--ok)}.k.warn strong{color:var(--warn)}.k.bad strong{color:var(--bad)}.k.wide{grid-column:span 1}
        .spark{display:block;width:100%;height:34px;margin-top:6px;overflow:visible}.spark path{fill:none;stroke:var(--ac);stroke-width:2;stroke-linejoin:round;stroke-linecap:round}.spark .area{fill:color-mix(in srgb,var(--ac) 14%,transparent);stroke:none}
        details.how{margin:12px 0 0;color:var(--mu);font-size:12px}details.how summary{cursor:pointer;font-weight:600;width:max-content}details.how p{margin-top:6px;max-width:80ch}
        section{margin-top:26px}.sec{display:flex;align-items:baseline;justify-content:space-between;gap:12px;margin-bottom:12px;flex-wrap:wrap}h2{font-size:16px;letter-spacing:-.2px}.share{display:flex;height:10px;border-radius:99px;overflow:hidden;background:var(--sf2);border:1px solid var(--bd);margin-bottom:14px}.share i{display:block;min-width:0;transition:flex .5s}
        #backends{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.be{position:relative;padding-top:20px;overflow:hidden}.be:before{content:'';position:absolute;inset:0 0 auto;height:4px;background:var(--bc)}.be h3{margin:0;font-size:15px;display:flex;align-items:center;gap:8px;flex-wrap:wrap}.be h3 span.dot{width:10px;height:10px;border-radius:50%;background:var(--bc)}
        .tags{display:flex;gap:6px;flex-wrap:wrap;margin:10px 0 14px}.tag{font-size:11px;font-weight:700;border-radius:6px;padding:2px 8px;border:1px solid currentColor}.tag.ok{color:var(--ok)}.tag.warn{color:var(--warn)}.tag.bad{color:var(--bad)}.tag.mu{color:var(--mu)}
        .m{margin:9px 0}.m div{display:flex;justify-content:space-between;font-size:12px;color:var(--mu)}.m b{color:var(--tx);font-weight:650}.track{height:7px;border-radius:99px;background:var(--sf2);border:1px solid var(--bd);margin-top:4px;overflow:hidden}.track i{display:block;height:100%;border-radius:99px;background:var(--bc);transition:width .5s}.track.hot i{background:var(--warn)}.track.crit i{background:var(--bad)}
        .nums{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:14px;padding-top:12px;border-top:1px solid var(--bd)}.nums small{display:block;font-size:10px;text-transform:uppercase;letter-spacing:.4px;color:var(--mu)}.nums strong{font-size:17px}
        .decide{display:grid;gap:14px}.acts{display:flex;gap:10px;flex-wrap:wrap}.pick{display:flex;align-items:center;gap:12px;flex-wrap:wrap}#decision-selection{font-size:22px;font-weight:750;letter-spacing:-.5px;padding:6px 14px;border-radius:10px;background:var(--sf2);border:1px solid var(--bd)}#decision-note,#decision-request,#decision-explanation,#decision-formula{color:var(--mu);font-size:13px}#decision-formula{font-family:ui-monospace,Consolas,monospace;font-size:12px;color:var(--tx)}#decision-explanation{color:var(--tx)}
        .tw{overflow:auto;border:1px solid var(--bd);border-radius:10px}table{width:100%;border-collapse:collapse;min-width:720px;font-size:13px}th{font-size:11px;text-transform:uppercase;letter-spacing:.4px;color:var(--mu);background:var(--sf2);text-align:left}th,td{padding:10px 12px;border-bottom:1px solid var(--bd)}tbody tr:last-child td{border-bottom:0}td:first-child{font-family:ui-monospace,Consolas,monospace}
        tr.selected{background:color-mix(in srgb,var(--ok) 11%,transparent);font-weight:650}tr.selected td:first-child{box-shadow:inset 4px 0 var(--ok)}td:last-child{background:linear-gradient(90deg,color-mix(in srgb,var(--ac) 20%,transparent) var(--w,0%),transparent var(--w,0%))}
        details.raw summary{cursor:pointer;font-weight:600;color:var(--mu)}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--sf2);border:1px solid var(--bd);border-radius:10px;padding:14px;font-size:12px;max-height:320px;overflow:auto}
        footer{margin-top:28px;display:flex;gap:12px;flex-wrap:wrap;align-items:center;color:var(--mu);font-size:12px}
        @media(max-width:1000px){.kpis{grid-template-columns:repeat(3,minmax(0,1fr))}.k.wide{grid-column:span 1}#backends{grid-template-columns:1fr}}@media(max-width:560px){.bar,main{padding-left:14px;padding-right:14px}.kpis{grid-template-columns:repeat(2,minmax(0,1fr))}.k strong{font-size:24px}}@media(prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}
        .network-flow{display:flex;align-items:center;gap:14px;padding:15px 20px;background:var(--sf);border:1px solid var(--bd);border-radius:12px;margin-bottom:20px;flex-wrap:wrap}.network-flow strong{font-size:13px}.network-flow span{color:var(--mu);font-size:12px}.resource-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.resource-grid strong{display:block;font-size:24px;margin:5px 0}.resource-grid small{display:block;color:var(--mu);font-size:12px}.timeline{padding:0;list-style:none;max-height:240px;overflow:auto;margin:12px 0 0}.timeline li{display:flex;gap:14px;border-top:1px solid var(--bd);padding:9px 0;font-size:12px}.timeline time{color:var(--mu);flex-shrink:0}.flow-arrow{font-size:20px;color:var(--ok)}.be .m{margin:7px 0}.be .tags{margin-bottom:8px}.kpis .k{padding:15px}.network-note{color:var(--mu);font-size:12px;margin-top:10px}.mono{overflow-wrap:anywhere}header #strategy{font-size:14px;border-color:var(--ac);padding:7px 14px}
        @media(max-width:650px){.resource-grid{grid-template-columns:1fr}.network-flow{gap:8px}.timeline li{flex-direction:column;gap:3px}}
        @media print{header{position:static;background:white}header button,.acts,.chips a,.raw,.how,footer{display:none!important}body{background:white;color:#111;font-size:10pt}main{max-width:none;padding:0}.card{box-shadow:none;break-inside:avoid;border:1px solid #bbb}.kpis{grid-template-columns:repeat(3,1fr)}#backends{grid-template-columns:repeat(3,1fr)}.tw{overflow:visible}table{min-width:0;width:100%;table-layout:fixed;font-size:8pt}th,td{padding:5px;overflow-wrap:anywhere}section{break-inside:avoid;margin-top:14px}.timeline{max-height:none;overflow:visible}.spark{display:none}.sub,.network-note{color:#333}}
        </style><body>
        <header><div class="bar"><div class="brand"><span class="mark"><svg viewBox="0 0 24 24"><path d="M4 12h16M8 5l4-3 4 3M12 2v20M8 19l4 3 4-3"/></svg></span><div><h1>Giám sát cân bằng tải</h1></div></div>
        <p id="state" role="status">Đang kết nối…</p><span class="chip" id="strategy-chip" title="Chiến lược cân bằng tải"><span id="strategy">—</span></span><button id="cycle-strategy" title="Mỗi lần bấm chuyển: Thích nghi → Luân phiên → Ít kết nối nhất → Thích nghi" aria-label="Đổi thuật toán theo thứ tự Thích nghi, Luân phiên, Ít kết nối nhất">Đổi thuật toán</button><button id="theme" aria-pressed="false">Giao diện tối</button><button id="refresh" aria-label="Làm mới" title="Làm mới">↻</button></div></header>
        <main>
        <div class="chips"><span>Cập nhật <b id="updated" class="mono">—</b></span><span>· <a href="http://127.0.0.1:@@DATA_PORT@@/demo">Trạm thử ↗</a></span><span class="sub">Nút “Đổi thuật toán” lần lượt chuyển: Thích nghi → Luân phiên → Ít kết nối nhất → Thích nghi.</span></div>
        <div class="kpis"><article class="card k"><small>Request dữ liệu đã bắt đầu</small><strong id="total">—</strong></article>
        <article class="card k good"><small>Hoàn tất HTTP 2xx / 3xx</small><strong id="success">—</strong></article><article class="card k" id="failed-card"><small>Thất bại HTTP / I/O</small><strong id="failed">—</strong></article>
        <article class="card k wide"><small>Request dữ liệu hoàn tất / giây</small><strong id="rps">—</strong><svg class="spark" id="spark" viewBox="0 0 200 34" preserveAspectRatio="none" aria-hidden="true"></svg></article>
        <article class="card k"><small>Bị giới hạn tần suất</small><strong id="limited">—</strong></article><article class="card k"><small>Yêu cầu đang giữ chỗ</small><strong id="inflight">—</strong></article></div>
        <details class="how"><summary>Cách đọc các số liệu</summary><p>Request dữ liệu gồm shop, API và workload demo, loại trừ /__proxy/*. RPS = chênh lệch số hoàn tất / thời gian giữa hai lần poll, gồm cả lỗi. Active là handler đã bắt đầu và chưa kết thúc; lease là yêu cầu đang giữ chỗ tới backend, không phải số TCP connection. Kết nối bị từ chối trước handler không thuộc bộ đếm này.</p><p>Latency đo từ lúc vào handler đến khi kết thúc gửi response, gồm đọc body và chờ backend. Phân vị nearest-rank trên tối đa 2.048 mẫu mới nhất, không phải toàn bộ lịch sử. p50 cần ≥2 mẫu, p95 ≥20, p99 ≥100; ngưỡng này không bảo đảm độ tin cậy thống kê. Error rate toàn thời gian gồm 4xx nghiệp vụ, 5xx và lỗi I/O.</p></details>
        <section class="network-flow" aria-label="Đường đi của request"><strong>CLIENT</strong><span class="flow-arrow">→</span><strong>HTTP LISTENER</strong><span class="flow-arrow">→</span><strong>ADMISSION · DISPATCHER</strong><span class="flow-arrow">→</span><strong>3 BACKENDS</strong><span>HTTP/1.1 · Java 21 · một socket upstream / exchange</span></section>
        <section class="card"><div class="sec"><h2>Traffic · latency</h2><span id="successful-rate" class="sub">Chưa đủ hai lần lấy mẫu để tính RPS.</span></div><p id="traffic-summary" role="status">Chưa có mẫu.</p>
        <p id="traffic-latency" class="sub"></p><p id="traffic-success-latency" class="sub"></p>
        <details><summary>Phân loại lỗi và quản trị</summary><p id="traffic-errors"></p><p>Quản trị chỉ dành cho loopback. Header X-Proxy-Action ngăn thao tác vô ý từ form; đây không phải xác thực production.</p></details>
        <details><summary>Phân bố theo workload</summary><div class="tw"><table><thead><tr><th>Nhóm cố định</th><th>Hoàn tất</th><th>Tỷ trọng</th><th>Lỗi HTTP / I/O</th><th>p95 (ms)</th><th>Mẫu gần nhất</th></tr></thead><tbody id="route-distribution"></tbody></table></div></details></section>
        <section><div class="sec"><h2>Backend · trạng thái và phân phối</h2><span class="sub">Thanh tỷ trọng: số hoàn tất tích lũy từ lúc proxy khởi động</span></div><div class="share" id="share"></div><div id="backends"></div></section>
        <section><div class="sec"><h2>Socket & tài nguyên</h2><span class="sub">Chỉ exchange dữ liệu; không gồm health/telemetry probe</span></div><div class="resource-grid"><article class="card"><small>EXCHANGE GIỮ SOCKET / ĐỈNH</small><strong id="socket-count">—</strong><small id="connection-count"></small></article><article class="card"><small>TCP CONNECT · TRUNG BÌNH / p95</small><strong id="connect-latency">—</strong><small id="first-byte"></small></article><article class="card"><small>BYTE ĐỌC / GHI QUA SOCKET</small><strong id="network-bytes">—</strong><small id="resource-admission"></small></article></div><p class="network-note">Tính từ lúc tạo socket đến cleanup, gồm cả lúc đang connect. Đo Java I/O, không phải gói tin NIC. First byte sau connect gồm ghi request, mạng và backend; chưa tách riêng queue, DB hay CPU. Không có connection pooling.</p></section>
        <section class="card"><div class="sec"><h2>Failure & recovery · quan sát</h2><span class="sub">Cửa sổ 80 chuyển trạng thái, lấy mẫu mỗi 2 giây trong tab này</span></div><ul id="failure-timeline" class="timeline"><li>Chưa ghi nhận chuyển trạng thái. Không phải lịch sử trước khi mở trang.</li></ul></section>
        <section class="card"><div class="sec"><h2 id="decision-title">Khám phá quyết định Adaptive</h2></div><div class="decide">
        <p class="sub">Điểm = ECT × hệ số phạt. ECT = chờ slot + dịch vụ dự báo.</p>
        <div class="acts"><button id="decision-send" class="primary">Gửi yêu cầu thử</button><button id="decision-freeze">Giữ ảnh chụp hiện tại</button></div>
        <p id="decision-request">Chưa gửi yêu cầu thử.</p>
        <p id="decision-note">Đang đọc…</p><div class="pick"><span id="decision-selection">—</span><span id="decision-explanation"></span></div><p id="decision-formula"></p>
        <div class="tw"><table><thead><tr><th>Máy chủ</th><th>Điều kiện</th><th>Dịch vụ (ms)</th><th>Chờ (ms)</th><th>ECT (ms)</th><th>Hệ số phạt</th><th>Điểm (ms)</th></tr></thead><tbody id="candidates"></tbody></table></div>
        <p class="sub">Chỉ giữ quyết định gần nhất; trace không chứa request ID.</p>
        <details class="raw" hidden><summary>JSON trace nguyên bản</summary><pre id="decision-json">—</pre></details></div></section>
        <section class="card"><div class="sec"><h2>So sánh thí nghiệm · dữ liệu đã lưu</h2><span class="sub">Tách biệt hoàn toàn với metrics realtime phía trên</span></div>%BENCHMARK_COMPARISON%</section>
        </main>
        <script>
        const el=id=>document.getElementById(id), number=v=>typeof v==='number'&&Number.isFinite(v)?v.toLocaleString('vi-VN'):'—';
        const strategy=v=>({ROUND_ROBIN:'Luân phiên',LEAST_CONNECTIONS:'Ít kết nối nhất',ADAPTIVE:'Thích nghi'}[v]||'Chưa biết');
        const state=v=>({UP:'Hoạt động',DOWN:'Ngừng',DRAINING:'Đang drain',WARMING:'Tăng tải dần',CLOSED:'Đóng',OPEN:'Mở',HALF_OPEN:'Thử phục hồi'}[v]||'Chưa biết');
        const reasons={MINIMUM_SCORE:'Điểm thấp nhất',EXPLORATION:'Thăm dò',DRAINING:'Đang dừng',MAX_INFLIGHT:'Hết giới hạn yêu cầu',NO_ELIGIBLE_BACKEND:'Không còn máy chủ hợp lệ',ELIGIBLE:'Đủ điều kiện',DOWN:'Không khỏe',INFLIGHT_LIMIT:'Hết giới hạn lease',CIRCUIT_UNAVAILABLE:'Mạch bảo vệ chưa cho phép',WORK_BUDGET:'Vượt ngân sách công việc',RELEARNING_PROBE:'Thử học lại'};
        const explanations={MINIMUM_SCORE:'Chọn điểm ECT × hệ số phạt nhỏ nhất trong tập đủ điều kiện. Nếu hòa, ưu tiên lần chọn cũ nhất rồi thứ tự cấu hình.',EXPLORATION:'Đang thăm dò máy chủ lâu nhất chưa được chọn trong tập hợp lệ. Có thể chọn điểm cao hơn; đây không phải lựa chọn minimum score.',DRAINING:'Proxy đang dừng nhận việc: không cấp lease mới.',MAX_INFLIGHT:'Đã chạm giới hạn in-flight toàn proxy: không cấp lease mới.',NO_ELIGIBLE_BACKEND:'Không còn ứng viên qua bộ lọc health, circuit, in-flight và work budget. Không cấp lease; xem lý do loại từng máy chủ bên dưới.'};
        const series=[];
        function spark(){const svg=el('spark');svg.replaceChildren();if(series.length<2)return;const max=Math.max(...series,1),step=200/39,pts=series.map((v,i)=>[(i+40-series.length)*step,32-(v/max)*30]);
          const ns='http://www.w3.org/2000/svg',line=pts.map((p,i)=>(i?'L':'M')+p[0].toFixed(1)+' '+p[1].toFixed(1)).join(' '),area=document.createElementNS(ns,'path'),ln=document.createElementNS(ns,'path');
          area.setAttribute('class','area');area.setAttribute('d',line+' L200 34 L'+pts[0][0].toFixed(1)+' 34Z');ln.setAttribute('d',line);svg.append(area,ln);}
        let dark=matchMedia('(prefers-color-scheme: dark)').matches;try{const t=sessionStorage.getItem('pbl-theme');if(t==='dark'||t==='light')dark=t==='dark';}catch(_){}
        function theme(){document.documentElement.dataset.theme=dark?'dark':'light';el('theme').setAttribute('aria-pressed',String(dark));el('theme').textContent=dark?'Giao diện sáng':'Giao diện tối';}
        theme();el('theme').addEventListener('click',()=>{dark=!dark;theme();try{sessionStorage.setItem('pbl-theme',dark?'dark':'light');}catch(_){}});
        let previous=null,lastSuccess=0,busy=false,decisionFrozen=false,sending=false,lastDecision=null;
        const observedStates=new Map(),backendPrevious=new Map(),events=[];
        async function read(path){const r=await fetch(path,{cache:'no-store',signal:AbortSignal.timeout(3500)});if(!r.ok)throw new Error('HTTP '+r.status);return r.json();}
        function connection(text,ok){el('state').textContent=text;el('state').className=ok?'ok':'bad';}
        function render(m,now){
          if(!Array.isArray(m.backends)||typeof m.requests_total!=='number')throw new Error('Số liệu không đúng định dạng');
          const traffic=m.traffic?.total;
          el('strategy').textContent=m.strategy+' · '+strategy(m.strategy);el('decision-title').textContent=m.strategy==='ADAPTIVE'?'Khám phá quyết định Adaptive':'Trace Adaptive · hiện đang dùng '+strategy(m.strategy);el('total').textContent=number(traffic?.started);el('success').textContent=number(traffic?.successful);el('failed').textContent=number(traffic?.failed);
          el('limited').textContent=number(m.rate_limit_rejected);el('inflight').textContent=number(m.backends.reduce((n,b)=>n+b.inFlight,0));
          const complete=m.backends.reduce((n,b)=>n+b.completed,0);
          const dataComplete=traffic?.completed;
          const rate=previous&&dataComplete>=previous.complete&&now>previous.time?(dataComplete-previous.complete)*1000/(now-previous.time):null;
          el('rps').textContent=rate==null?'—':rate.toLocaleString('vi-VN',{maximumFractionDigits:2});if(rate!=null){series.push(rate);if(series.length>40)series.shift();}spark();
          const successRate=previous&&now>previous.time?(traffic.successful-previous.successful)*1000/(now-previous.time):null;
          el('successful-rate').textContent='Throughput thành công: '+(successRate==null?'—':successRate.toFixed(2))+' req/s · Tổng latency gồm cả lỗi';
          previous={complete:dataComplete,successful:traffic.successful,time:now};el('backends').replaceChildren();el('share').replaceChildren();
          const h=(t,c,x)=>{const n=document.createElement(t);if(c)n.className=c;if(x!=null)n.textContent=x;return n;};
          const transport=m.transport;
          el('socket-count').textContent=transport?number(transport.activeSockets)+' / '+number(transport.peakSockets):'Chưa có dữ liệu';
          el('connection-count').textContent=transport?'Connect thành công: '+number(transport.connected)+' / '+number(transport.attempted)+' lần thử':'';
          el('connect-latency').textContent=transport?number(transport.connectMs?.mean)+' / '+number(transport.connectMs?.p95)+' ms':'—';
          el('first-byte').textContent=transport?'First byte sau connect · p95: '+number(transport.firstByteAfterConnectMs?.p95)+' ms':'';
          el('network-bytes').textContent=transport?number(transport.bytesRead)+' / '+number(transport.bytesWritten):'—';
          el('resource-admission').textContent='Ingress task: '+number(m.data_ingress_tasks)+' · Handler: '+number(traffic?.active)+' · Lease: '+number(m.backends.reduce((n,b)=>n+b.inFlight,0))+(m.downloadBulkhead?.enabled?' · PDF đang xử lý/chờ: '+number(m.downloadBulkhead.active)+' / '+number(m.downloadBulkhead.waiting)+' · Từ chối: '+number(m.downloadBulkhead.rejected):' · PDF bulkhead: tắt');
          m.backends.forEach(b=>{const current=b.state+' / '+b.circuitState,old=observedStates.get(b.id);if(old&&old!==current)events.unshift({time:new Date().toLocaleTimeString('vi-VN'),text:b.id+' · '+old+' → '+current});observedStates.set(b.id,current);});
          if(events.length){events.splice(80);el('failure-timeline').replaceChildren(...events.map(event=>{const row=h('li');row.append(h('time',null,event.time),h('span',null,event.text));return row;}));}
          el('traffic-summary').textContent='Active handler: '+number(traffic?.active)+' · Error rate: '+(typeof traffic?.errorRate==='number'?(traffic.errorRate*100).toFixed(2)+'%':'—')+' · Chưa chọn backend và thất bại: '+number(traffic?.rejectedBeforeDispatch);
          el('traffic-errors').textContent='HTTP 4xx: '+number(traffic?.http4xx)+' (400: '+number(traffic?.badRequest400)+', 409: '+number(traffic?.conflict409)+', 422: '+number(traffic?.invalidInput422)+', 429: '+number(traffic?.rateLimited429)+') · HTTP 5xx: '+number(traffic?.http5xx)+' · Timeout: '+number(m.upstream_timeouts)+' · Lỗi upstream (gồm timeout): '+number(m.upstream_errors);
          const outcomes=traffic?.outcomes||{};el('traffic-errors').textContent+=' · Chủ động tạm dừng: '+number(outcomes.SERVICE_PAUSED)+' · DB không kết nối: '+number(outcomes.DATABASE_UNAVAILABLE)+' · Storage: '+number(outcomes.STORAGE_UNAVAILABLE)+' · File thiếu: '+number(outcomes.FILE_MISSING)+' · Backend từ chối: '+number(outcomes.BACKEND_REJECTED)+' · Transport: '+number(outcomes.TRANSPORT_FAILURE)+' · Timeout upstream: '+number(outcomes.TIMEOUT);
          const quantiles=(label,lat)=>label+': p50 '+number(lat?.p50)+' · p95 '+number(lat?.p95)+' · p99 '+number(lat?.p99)+' ms · '+number(lat?.samples)+' / '+number(m.traffic?.windowCapacity)+' mẫu';
          el('traffic-latency').textContent=quantiles('Mọi kết quả',traffic?.latencyMs);el('traffic-success-latency').textContent=quantiles('Chỉ thành công',traffic?.successfulLatencyMs);
          el('route-distribution').replaceChildren();Object.entries(m.traffic?.routes||{}).forEach(([route,s])=>{const row=h('tr');for(const v of [route,number(s.completed),dataComplete?(s.completed/dataComplete*100).toFixed(1)+'%':'—',number(s.failed),number(s.latencyMs?.p95),number(s.latencyMs?.samples)])row.append(h('td',null,v));el('route-distribution').append(row);});
          const pct=v=>Math.max(0,Math.min(100,v*100));
          const meter=(label,frac,text)=>{const w=h('div','m'),r=h('div');r.append(h('span',null,label),h('b',null,text));const t=h('div','track'+(frac>.9?' crit':frac>.7?' hot':'')),f=h('i');f.style.width=pct(frac)+'%';t.append(f);w.append(r,t);return w;};
          const tone=s=>s==='UP'||s==='CLOSED'?'ok':s==='DOWN'||s==='OPEN'?'bad':'warn';
          m.backends.forEach((b,i)=>{const card=h('article','card be'),color='var(--c'+(i%3)+')';card.style.setProperty('--bc',color);
            const share=complete?b.completed/complete:0,cap=b.capacity||b.effectiveCapacity||1;
            const seg=h('i');seg.style.flex=String(Math.max(b.completed,complete?0:1));seg.style.background=color;seg.title=b.id+' · '+number(Math.round(share*100))+'%';el('share').append(seg);
            const title=h('h3');title.append(h('span','dot'),h('span','mono',b.id));
            const tags=h('div','tags');tags.append(h('span','tag '+tone(b.state),state(b.state)),h('span','tag '+tone(b.circuitState),'Mạch: '+state(b.circuitState)));if(b.telemetryFresh===false)tags.append(h('span','tag warn','Telemetry cũ'));
            card.append(title,tags,meter('Đang giữ chỗ / năng lực',b.inFlight/cap,number(b.inFlight)+' / '+number(cap)));
            if(typeof b.cpuLoad==='number')card.append(meter('CPU tiến trình',b.cpuLoad,number(Math.round(b.cpuLoad*100))+'%'));
            if(typeof b.memoryLoad==='number')card.append(meter('Heap JVM',b.memoryLoad,number(Math.round(b.memoryLoad*100))+'%'));
            const nums=h('div','nums');for(const [k,v] of [['Hoàn tất',number(b.completed)],['Lỗi',number(b.errors)],['Độ trễ EWMA',typeof b.latencyEwmaMs==='number'?number(Math.round(b.latencyEwmaMs))+' ms':'—']]){const d=h('div');d.append(h('small',null,k),h('strong',null,v));nums.append(d);}
            card.append(nums,h('p','sub','Circuit mở: '+number(b.circuitOpenCount)+' · Phục hồi sau DOWN: '+number(b.recoveries)));
            const prior=backendPrevious.get(b.id),recent=prior&&now>prior.time&&b.completed>=prior.completed?((b.completed-prior.completed)*1000/(now-prior.time)).toFixed(2):'—';
            card.append(h('p','sub','Hoàn tất gần nhất: '+recent+' req/s · Bộ đếm ở trên là tích lũy'));backendPrevious.set(b.id,{completed:b.completed,time:now});
            if(b.serviceEstimatesMsPerUnit){const details=h('details','raw'),title=h('summary',null,'Ước lượng dịch vụ theo nhóm (ms / unit)');details.append(title,h('pre',null,JSON.stringify(b.serviceEstimatesMsPerUnit,null,2)));card.append(details);}el('backends').append(card);});
          el('failed-card').className='card k'+(traffic?.failed>0?' bad':'');
          el('updated').textContent=new Date().toLocaleTimeString('vi-VN');
        }
        function clearDecision(note){el('candidates').replaceChildren();el('decision-note').textContent=note;el('decision-selection').textContent='—';el('decision-explanation').textContent='';el('decision-formula').textContent='';el('decision-json').textContent='—';lastDecision=null;}
        function freeze(value){decisionFrozen=value;el('decision-freeze').textContent=value?'Theo dõi quyết định mới':'Giữ ảnh chụp hiện tại';if(lastDecision)decision(lastDecision);}
        function decision(d){clearDecision('');lastDecision=d;
          if(!d.enabled){el('decision-note').textContent='Trace chỉ hiện khi chiến lược đang chạy là ADAPTIVE và adaptive.decision-trace.enabled=true. Nếu vừa bật tùy chọn này trong config/application.properties, hãy khởi động lại proxy.';return;}
          if(!d.lastDecision){el('decision-note').textContent='Chưa có quyết định; hãy gửi yêu cầu qua proxy.';return;}
          const x=d.lastDecision;if(!Array.isArray(x.candidates))throw new Error('Trace không đúng định dạng');
          el('decision-note').textContent=(decisionFrozen?'Ảnh chụp đã giữ':'Quyết định gần nhất')+' · Lần '+x.sequence+' · '+x.route+' / '+x.units+' đơn vị · Ngân sách '+number(x.budgetMs)+' ms · '+x.reason;
          el('decision-selection').textContent=x.selectedBackend?'Chọn '+x.selectedBackend:'Không cấp lease';
          el('decision-explanation').textContent=explanations[x.reason]||'Lý do chưa được nhận diện; xem JSON trace nguyên bản.';
          const chosen=x.candidates.find(c=>c.backend===x.selectedBackend);
          if(chosen)el('decision-formula').textContent='ECT '+number(chosen.completionMs)+' ms × hệ số phạt '+number(chosen.resourcePenalty)+' ≈ điểm '+number(chosen.rankingScoreMs)+' ms.'+(chosen.eligibility==='RELEARNING_PROBE'?' Ngoại lệ thử học lại cho máy chủ idle; không khẳng định forecast nằm trong budget.':'');
          const top=Math.max(...x.candidates.map(c=>c.rankingScoreMs||0),1);for(const c of x.candidates){const row=document.createElement('tr');row.style.setProperty('--w',(c.rankingScoreMs?Math.max(4,c.rankingScoreMs/top*100):0)+'%');if(c.backend===x.selectedBackend){row.className='selected';row.dataset.selected='true';}row.dataset.backend=c.backend;row.dataset.eligibility=c.eligibility;
            for(const v of [c.backend,reasons[c.eligibility]||'Chưa xác định',number(c.serviceMs),number(c.waitMs),number(c.completionMs),number(c.resourcePenalty),number(c.rankingScoreMs)]){const cell=document.createElement('td');cell.textContent=v;row.append(cell);}el('candidates').append(row);}
          el('decision-json').textContent=JSON.stringify(d,null,2);
        }
        let strategyRevision=0,observedStrategy=null;
        async function poll(){if(busy)return;busy=true;const revision=strategyRevision;try{const m=await read('/__proxy/metrics');if(revision!==strategyRevision)return;
          if(observedStrategy!==m.strategy){observedStrategy=m.strategy;clearDecision('Chưa có ảnh chụp cho thuật toán mới.');freeze(false);}
          render(m,performance.now());lastSuccess=performance.now();connection('Đã kết nối',true);
          if(!decisionFrozen)try{const snapshot=await read('/__proxy/decision');if(!decisionFrozen&&revision===strategyRevision)decision(snapshot);}catch(e){if(!decisionFrozen&&revision===strategyRevision)clearDecision('Không đọc được quyết định: '+e.message);}
        }catch(e){previous=null;el('rps').textContent='—';connection('Mất kết nối — dữ liệu hiển thị đã cũ. '+e.message,false);}finally{busy=false;}}
        el('refresh').addEventListener('click',poll);poll();setInterval(()=>{if(!document.hidden)void poll();},2000);
        document.addEventListener('visibilitychange',()=>{if(!document.hidden){previous=null;void poll();}});
        let changingStrategy=false;
        el('cycle-strategy').addEventListener('click',async()=>{if(changingStrategy)return;changingStrategy=true;el('cycle-strategy').disabled=true;
          try{const response=await fetch('/__proxy/strategy',{method:'POST',cache:'no-store',headers:{'X-Proxy-Action':'cycle-strategy'},signal:AbortSignal.timeout(3500)});
            const result=await response.json();if(!response.ok)throw new Error(result.error||'HTTP '+response.status);
            strategyRevision++;observedStrategy=result.strategy;previous=null;clearDecision('Đã đổi thuật toán; đang đọc ảnh chụp mới.');freeze(false);
            el('strategy').textContent=strategy(result.strategy);connection('Đã đổi thuật toán: '+strategy(result.strategy),true);await poll();
          }catch(e){connection('Không đổi được thuật toán: '+e.message,false);}
          finally{changingStrategy=false;el('cycle-strategy').disabled=false;}});
        el('decision-freeze').addEventListener('click',()=>freeze(!decisionFrozen));
        el('decision-send').disabled=Number(location.port||80)!==@@DATA_PORT@@;
        if(el('decision-send').disabled)el('decision-request').textContent='Listener quản lý chỉ đọc. Mở dashboard trên cổng dữ liệu từ liên kết trạm thử yêu cầu để gửi workload.';
        el('decision-send').addEventListener('click',async()=>{if(sending)return;sending=true;el('decision-send').disabled=true;freeze(true);
          try{const response=await fetch('/work?cost=2',{cache:'no-store',signal:AbortSignal.timeout(8000),headers:{'X-Request-Id':'explorer-'+crypto.randomUUID()}});await response.text();
            const d=await read('/__proxy/decision');decision(d);const backend=response.headers.get('X-Proxy-Backend');
            el('decision-request').textContent='GET /work?cost=2 → HTTP '+response.status+' · X-Request-Id: '+(response.headers.get('X-Request-Id')||'—')+' · Backend response: '+(backend||'Không có')+'. Snapshot được đọc sau response; không coi request ID là khóa liên kết với trace.';
          }catch(e){clearDecision('Không lấy được ảnh chụp mới: '+e.message);el('decision-request').textContent='Yêu cầu thử hoặc đọc trace thất bại; không suy ra lựa chọn từ ảnh cũ.';}
          finally{sending=false;el('decision-send').disabled=false;}});
        setInterval(()=>{if(lastSuccess&&performance.now()-lastSuccess>6000){previous=null;el('rps').textContent='—';connection('Dữ liệu đã cũ — chưa nhận được cập nhật quá 6 giây',false);}},1000);
        </script></body></html>
        """.replace("%BENCHMARK_COMPARISON%",benchmark());
}
