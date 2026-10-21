package com.example.proxy.server;

/** Local-only demo view. All values come from management HTTP snapshots, never fixture counters. */
final class MonitoringPage {
    private MonitoringPage() { }
    static final String HTML = """
        <!doctype html><html lang="vi"><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Giám sát cân bằng tải — PBL4</title>
        <style>
        :root{font:16px system-ui;color:#172033;background:#f3f5f8}body{max-width:1120px;margin:28px auto;padding:0 20px}
        h1{font-size:28px}h2{font-size:19px}p{line-height:1.6}.muted,small{color:#526177}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}
        article,section{padding:20px;background:white;border:1px solid #d9e1eb;border-radius:10px;margin:16px 0}strong{font-size:25px}
        table{width:100%;border-collapse:collapse}th,td{padding:9px;text-align:left;border-bottom:1px solid #ddd}pre{white-space:pre-wrap;overflow-wrap:anywhere}
        .ok{color:#08745e}.bad{color:#b52d40}a{color:#245bd6}button{padding:8px 12px}#state{font-weight:600}@media(max-width:700px){.grid{grid-template-columns:1fr}table{font-size:12px}}
        </style><body>
        <h1>Giám sát cân bằng tải</h1>
        <p>Chỉ dành cho máy cục bộ. Lấy số liệu thật mỗi 2 giây; không mở cổng quản lý ra LAN.</p>
        <p id="state" role="status">Đang kết nối…</p><p class="muted">Chiến lược: <span id="strategy">—</span> · Cập nhật: <span id="updated">—</span></p>
        <div class="grid"><article>Tổng yêu cầu tại bộ xử lý<br><strong id="total">—</strong></article>
        <article>Hoàn tất HTTP 2xx<br><strong id="success">—</strong></article><article>Thất bại tại bộ xử lý<br><strong id="failed">—</strong></article></div>
        <p class="muted">Ba bộ đếm trên gồm cả yêu cầu giám sát. Không coi chúng là lưu lượng nghiệp vụ riêng.</p>
        <div class="grid"><article>Yêu cầu hoàn tất tới máy chủ / giây<br><strong id="rps">—</strong></article>
        <article>Bị giới hạn tần suất<br><strong id="limited">—</strong></article><article>Yêu cầu đang được giữ chỗ<br><strong id="inflight">—</strong></article></div>
        <p class="muted">Tốc độ tính từ chênh lệch tổng yêu cầu hoàn tất tới máy chủ và thời gian thực giữa hai lần đọc, gồm cả phản hồi lỗi; không phải tốc độ thành công.</p>
        <section><h2>Máy chủ phía sau</h2><div id="backends" class="grid"></div></section>
        <section><h2>Giải thích quyết định thích nghi gần nhất</h2><p id="decision-note">Đang đọc…</p>
        <div style="overflow:auto"><table><thead><tr><th>Máy chủ</th><th>Điều kiện</th><th>Dịch vụ (ms)</th><th>Chờ (ms)</th><th>Hệ số phạt</th><th>Điểm (ms)</th></tr></thead><tbody id="candidates"></tbody></table></div></section>
        <p><a href="http://127.0.0.1:@@DATA_PORT@@/demo">Về trạm thử yêu cầu</a> · <button id="refresh">Đọc lại ngay</button></p>
        <script>
        const el=id=>document.getElementById(id), number=v=>typeof v==='number'&&Number.isFinite(v)?v.toLocaleString('vi-VN'):'—';
        const strategy=v=>({ROUND_ROBIN:'Luân phiên',LEAST_CONNECTIONS:'Ít kết nối nhất',ADAPTIVE:'Thích nghi'}[v]||'Chưa biết');
        const state=v=>({UP:'Hoạt động',DOWN:'Ngừng',WARMING:'Tăng tải dần',CLOSED:'Đóng',OPEN:'Mở',HALF_OPEN:'Thử phục hồi'}[v]||'Chưa biết');
        const reasons={MINIMUM_SCORE:'Điểm thấp nhất',EXPLORATION:'Thăm dò',DRAINING:'Đang dừng',MAX_INFLIGHT:'Hết giới hạn yêu cầu',NO_ELIGIBLE_BACKEND:'Không còn máy chủ hợp lệ',ELIGIBLE:'Đủ điều kiện',DOWN:'Không khỏe',INFLIGHT_LIMIT:'Hết giới hạn lease',CIRCUIT_UNAVAILABLE:'Mạch bảo vệ chưa cho phép',WORK_BUDGET:'Vượt ngân sách công việc',RELEARNING_PROBE:'Thử học lại'};
        let previous=null,lastSuccess=0,busy=false;
        async function read(path){const r=await fetch(path,{cache:'no-store',signal:AbortSignal.timeout(3500)});if(!r.ok)throw new Error('HTTP '+r.status);return r.json();}
        function connection(text,ok){el('state').textContent=text;el('state').className=ok?'ok':'bad';}
        function render(m,now){
          if(!Array.isArray(m.backends)||typeof m.requests_total!=='number')throw new Error('Số liệu không đúng định dạng');
          el('strategy').textContent=strategy(m.strategy);el('total').textContent=number(m.requests_total);el('success').textContent=number(m.requests_successful);el('failed').textContent=number(m.requests_failed);
          el('limited').textContent=number(m.rate_limit_rejected);el('inflight').textContent=number(m.backends.reduce((n,b)=>n+b.inFlight,0));
          const complete=m.backends.reduce((n,b)=>n+b.completed,0);
          el('rps').textContent=previous&&complete>=previous.complete&&now>previous.time?((complete-previous.complete)*1000/(now-previous.time)).toLocaleString('vi-VN',{maximumFractionDigits:2}):'—';
          previous={complete,time:now};el('backends').replaceChildren();
          for(const b of m.backends){const card=document.createElement('article');
            for(const text of [b.id,'Trạng thái: '+state(b.state),'Mạch bảo vệ: '+state(b.circuitState),'Đã hoàn tất tới máy chủ: '+number(b.completed),'Đang được giữ chỗ: '+number(b.inFlight)]){const p=document.createElement('p');p.textContent=text;card.append(p);}el('backends').append(card);}
          el('updated').textContent=new Date().toLocaleTimeString('vi-VN');
        }
        function decision(d){el('candidates').replaceChildren();
          if(!d.enabled){el('decision-note').textContent='Chưa bật ghi quyết định. Đặt adaptive.decision-trace.enabled=true rồi khởi động lại.';return;}
          if(!d.lastDecision){el('decision-note').textContent='Chưa có quyết định; hãy gửi yêu cầu qua proxy.';return;}
          const x=d.lastDecision;el('decision-note').textContent='Lần '+x.sequence+' · '+(reasons[x.reason]||'Chưa xác định')+' · Máy chủ được chọn: '+(x.selectedBackend||'Không giữ chỗ')+'. Đây là ảnh chụp, không phải diễn biến liên tục của mọi yêu cầu.';
          for(const c of x.candidates){const row=document.createElement('tr');for(const v of [c.backend,reasons[c.eligibility]||'Chưa xác định',number(c.serviceMs),number(c.waitMs),number(c.resourcePenalty),number(c.rankingScoreMs)]){const cell=document.createElement('td');cell.textContent=v;row.append(cell);}el('candidates').append(row);}
        }
        async function poll(){if(busy)return;busy=true;try{const m=await read('/__proxy/metrics');render(m,performance.now());lastSuccess=performance.now();connection('Đã kết nối · số liệu mới nhất',true);
          try{decision(await read('/__proxy/decision'));}catch(e){el('decision-note').textContent='Không đọc được quyết định: '+e.message;el('candidates').replaceChildren();}
        }catch(e){previous=null;el('rps').textContent='—';connection('Mất kết nối — dữ liệu hiển thị đã cũ. '+e.message,false);}finally{busy=false;}}
        el('refresh').addEventListener('click',poll);poll();setInterval(poll,2000);
        setInterval(()=>{if(lastSuccess&&performance.now()-lastSuccess>6000){previous=null;el('rps').textContent='—';connection('Dữ liệu đã cũ — chưa nhận được cập nhật quá 6 giây',false);}},1000);
        </script></body></html>
        """;
}
