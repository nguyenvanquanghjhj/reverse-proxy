package com.example.proxy.server;

/** Local-only demo view. All values come from management HTTP snapshots, never fixture counters. */
final class MonitoringPage {
    private MonitoringPage() { }
    static final String HTML = """
        <!doctype html><html lang="vi"><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <link rel="icon" href="data:,">
        <title>Giám sát cân bằng tải — PBL4</title>
        <style>
        :root{font:16px system-ui;color:#172033;background:#f3f5f8}body{max-width:1120px;margin:28px auto;padding:0 20px}
        h1{font-size:28px}h2{font-size:19px}p{line-height:1.6}.muted,small{color:#526177}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}
        article,section{padding:20px;background:white;border:1px solid #d9e1eb;border-radius:10px;margin:16px 0}strong{font-size:25px}
        table{width:100%;border-collapse:collapse}th,td{padding:9px;text-align:left;border-bottom:1px solid #ddd}pre{white-space:pre-wrap;overflow-wrap:anywhere}
        .ok{color:#08745e}.bad{color:#b52d40}a{color:#245bd6}button{padding:8px 12px}#state{font-weight:600}@media(max-width:700px){.grid{grid-template-columns:1fr}table{font-size:12px}}
        .selected{background:#e7f6ef;font-weight:600}.decision-actions{display:flex;gap:10px;flex-wrap:wrap}button:disabled{opacity:.55}#decision-selection{font-size:21px;font-weight:700}#decision-json{font-size:13px}
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
        <section><h2>Khám phá quyết định Adaptive</h2>
        <p>ECT = chờ slot + dịch vụ dự báo. Điểm = ECT × hệ số phạt. Các giá trị lấy từ trace thật, không phải độ trễ chắc chắn.</p>
        <div class="decision-actions"><button id="decision-send">Gửi /work?cost=2 và giữ ảnh chụp</button><button id="decision-freeze">Giữ ảnh chụp hiện tại</button></div>
        <p id="decision-request" class="muted">Không có yêu cầu thử nào được tự động gửi.</p>
        <p id="decision-note">Đang đọc…</p><p id="decision-selection">—</p><p id="decision-explanation"></p><p id="decision-formula"></p>
        <div style="overflow:auto"><table><thead><tr><th>Máy chủ</th><th>Điều kiện</th><th>Dịch vụ (ms)</th><th>Chờ (ms)</th><th>ECT (ms)</th><th>Hệ số phạt</th><th>Điểm (ms)</th></tr></thead><tbody id="candidates"></tbody></table></div>
        <p class="muted">Chỉ giữ quyết định gần nhất; trace không chứa request ID. Traffic khác có thể ghi đè giữa lúc nhận response và đọc trace. Số hiển thị được làm tròn; máy chủ bị loại có thể không có forecast.</p>
        <details><summary>JSON trace nguyên bản</summary><pre id="decision-json">—</pre></details></section>
        <p><a href="http://127.0.0.1:@@DATA_PORT@@/demo">Về trạm thử yêu cầu</a> · <button id="refresh">Đọc lại ngay</button></p>
        <script>
        const el=id=>document.getElementById(id), number=v=>typeof v==='number'&&Number.isFinite(v)?v.toLocaleString('vi-VN'):'—';
        const strategy=v=>({ROUND_ROBIN:'Luân phiên',LEAST_CONNECTIONS:'Ít kết nối nhất',ADAPTIVE:'Thích nghi'}[v]||'Chưa biết');
        const state=v=>({UP:'Hoạt động',DOWN:'Ngừng',WARMING:'Tăng tải dần',CLOSED:'Đóng',OPEN:'Mở',HALF_OPEN:'Thử phục hồi'}[v]||'Chưa biết');
        const reasons={MINIMUM_SCORE:'Điểm thấp nhất',EXPLORATION:'Thăm dò',DRAINING:'Đang dừng',MAX_INFLIGHT:'Hết giới hạn yêu cầu',NO_ELIGIBLE_BACKEND:'Không còn máy chủ hợp lệ',ELIGIBLE:'Đủ điều kiện',DOWN:'Không khỏe',INFLIGHT_LIMIT:'Hết giới hạn lease',CIRCUIT_UNAVAILABLE:'Mạch bảo vệ chưa cho phép',WORK_BUDGET:'Vượt ngân sách công việc',RELEARNING_PROBE:'Thử học lại'};
        const explanations={MINIMUM_SCORE:'Chọn điểm ECT × hệ số phạt nhỏ nhất trong tập đủ điều kiện. Nếu hòa, ưu tiên lần chọn cũ nhất rồi thứ tự cấu hình.',EXPLORATION:'Đang thăm dò máy chủ lâu nhất chưa được chọn trong tập hợp lệ. Có thể chọn điểm cao hơn; đây không phải lựa chọn minimum score.',DRAINING:'Proxy đang dừng nhận việc: không cấp lease mới.',MAX_INFLIGHT:'Đã chạm giới hạn in-flight toàn proxy: không cấp lease mới.',NO_ELIGIBLE_BACKEND:'Không còn ứng viên qua bộ lọc health, circuit, in-flight và work budget. Không cấp lease; xem lý do loại từng máy chủ bên dưới.'};
        let previous=null,lastSuccess=0,busy=false,decisionFrozen=false,sending=false,lastDecision=null;
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
        function clearDecision(note){el('candidates').replaceChildren();el('decision-note').textContent=note;el('decision-selection').textContent='—';el('decision-explanation').textContent='';el('decision-formula').textContent='';el('decision-json').textContent='—';lastDecision=null;}
        function freeze(value){decisionFrozen=value;el('decision-freeze').textContent=value?'Theo dõi quyết định mới':'Giữ ảnh chụp hiện tại';if(lastDecision)decision(lastDecision);}
        function decision(d){clearDecision('');lastDecision=d;
          if(!d.enabled){el('decision-note').textContent='Chưa bật trace Adaptive hoặc chiến lược hiện tại không phải ADAPTIVE. Bật adaptive.decision-trace.enabled=true rồi restart ADAPTIVE.';return;}
          if(!d.lastDecision){el('decision-note').textContent='Chưa có quyết định; hãy gửi yêu cầu qua proxy.';return;}
          const x=d.lastDecision;if(!Array.isArray(x.candidates))throw new Error('Trace không đúng định dạng');
          el('decision-note').textContent=(decisionFrozen?'Ảnh chụp đã giữ':'Quyết định gần nhất')+' · Lần '+x.sequence+' · '+x.route+' / '+x.units+' đơn vị · Ngân sách '+number(x.budgetMs)+' ms · '+x.reason;
          el('decision-selection').textContent=x.selectedBackend?'Chọn '+x.selectedBackend:'Không cấp lease';
          el('decision-explanation').textContent=explanations[x.reason]||'Lý do chưa được nhận diện; xem JSON trace nguyên bản.';
          const chosen=x.candidates.find(c=>c.backend===x.selectedBackend);
          if(chosen)el('decision-formula').textContent='ECT '+number(chosen.completionMs)+' ms × hệ số phạt '+number(chosen.resourcePenalty)+' ≈ điểm '+number(chosen.rankingScoreMs)+' ms.'+(chosen.eligibility==='RELEARNING_PROBE'?' Ngoại lệ thử học lại cho máy chủ idle; không khẳng định forecast nằm trong budget.':'');
          for(const c of x.candidates){const row=document.createElement('tr');if(c.backend===x.selectedBackend){row.className='selected';row.dataset.selected='true';}row.dataset.backend=c.backend;row.dataset.eligibility=c.eligibility;
            for(const v of [c.backend,reasons[c.eligibility]||'Chưa xác định',number(c.serviceMs),number(c.waitMs),number(c.completionMs),number(c.resourcePenalty),number(c.rankingScoreMs)]){const cell=document.createElement('td');cell.textContent=v;row.append(cell);}el('candidates').append(row);}
          el('decision-json').textContent=JSON.stringify(d,null,2);
        }
        async function poll(){if(busy)return;busy=true;try{const m=await read('/__proxy/metrics');render(m,performance.now());lastSuccess=performance.now();connection('Đã kết nối · số liệu mới nhất',true);
          if(!decisionFrozen)try{const snapshot=await read('/__proxy/decision');if(!decisionFrozen)decision(snapshot);}catch(e){if(!decisionFrozen)clearDecision('Không đọc được quyết định: '+e.message);}
        }catch(e){previous=null;el('rps').textContent='—';connection('Mất kết nối — dữ liệu hiển thị đã cũ. '+e.message,false);}finally{busy=false;}}
        el('refresh').addEventListener('click',poll);poll();setInterval(poll,2000);
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
        """;
}
