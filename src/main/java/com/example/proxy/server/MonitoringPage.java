package com.example.proxy.server;

/** Local-only demo view. All values come from management HTTP snapshots, never fixture counters. */
final class MonitoringPage {
    private MonitoringPage() { }
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
        </style><body>
        <header><div class="bar"><div class="brand"><span class="mark"><svg viewBox="0 0 24 24"><path d="M4 12h16M8 5l4-3 4 3M12 2v20M8 19l4 3 4-3"/></svg></span><div><h1>Giám sát cân bằng tải</h1></div></div>
        <p id="state" role="status">Đang kết nối…</p><span class="chip" id="strategy-chip" title="Chiến lược cân bằng tải"><span id="strategy">—</span></span><button id="cycle-strategy" title="Mỗi lần bấm chuyển: Thích nghi → Luân phiên → Ít kết nối nhất → Thích nghi" aria-label="Đổi thuật toán theo thứ tự Thích nghi, Luân phiên, Ít kết nối nhất">Đổi thuật toán</button><button id="theme" aria-pressed="false">Giao diện tối</button><button id="refresh" aria-label="Làm mới" title="Làm mới">↻</button></div></header>
        <main>
        <div class="chips"><span>Cập nhật <b id="updated" class="mono">—</b></span><span>· <a href="http://127.0.0.1:@@DATA_PORT@@/demo">Trạm thử ↗</a></span><span class="sub">Nút “Đổi thuật toán” lần lượt chuyển: Thích nghi → Luân phiên → Ít kết nối nhất → Thích nghi.</span></div>
        <div class="kpis" hidden><article class="card k"><small>Tổng yêu cầu tại bộ xử lý</small><strong id="total">—</strong></article>
        <article class="card k good"><small>Hoàn tất HTTP 2xx</small><strong id="success">—</strong></article><article class="card k" id="failed-card"><small>Thất bại tại bộ xử lý</small><strong id="failed">—</strong></article>
        <article class="card k wide"><small>Hoàn tất tới máy chủ / giây</small><strong id="rps">—</strong><svg class="spark" id="spark" viewBox="0 0 200 34" preserveAspectRatio="none" aria-hidden="true"></svg></article>
        <article class="card k"><small>Bị giới hạn tần suất</small><strong id="limited">—</strong></article><article class="card k"><small>Yêu cầu đang giữ chỗ</small><strong id="inflight">—</strong></article></div>
        <details class="how" hidden><summary>Cách đọc các số liệu</summary><p>Ba bộ đếm đầu gồm cả yêu cầu giám sát, không coi là lưu lượng nghiệp vụ riêng. Tốc độ tính từ chênh lệch tổng yêu cầu hoàn tất tới máy chủ và thời gian thực giữa hai lần đọc, gồm cả phản hồi lỗi; không phải tốc độ thành công.</p></details>
        <section><div class="sec"><h2>Máy chủ phía sau</h2></div><div class="share" id="share"></div><div id="backends"></div></section>
        <section class="card"><div class="sec"><h2>Khám phá quyết định Adaptive</h2></div><div class="decide">
        <p class="sub">Điểm = ECT × hệ số phạt. ECT = chờ slot + dịch vụ dự báo.</p>
        <div class="acts"><button id="decision-send" class="primary">Gửi yêu cầu thử</button><button id="decision-freeze">Giữ ảnh chụp hiện tại</button></div>
        <p id="decision-request">Chưa gửi yêu cầu thử.</p>
        <p id="decision-note">Đang đọc…</p><div class="pick"><span id="decision-selection">—</span><span id="decision-explanation"></span></div><p id="decision-formula"></p>
        <div class="tw"><table><thead><tr><th>Máy chủ</th><th>Điều kiện</th><th>Dịch vụ (ms)</th><th>Chờ (ms)</th><th>ECT (ms)</th><th>Hệ số phạt</th><th>Điểm (ms)</th></tr></thead><tbody id="candidates"></tbody></table></div>
        <p class="sub">Chỉ giữ quyết định gần nhất; trace không chứa request ID.</p>
        <details class="raw" hidden><summary>JSON trace nguyên bản</summary><pre id="decision-json">—</pre></details></div></section>
        </main>
        <script>
        const el=id=>document.getElementById(id), number=v=>typeof v==='number'&&Number.isFinite(v)?v.toLocaleString('vi-VN'):'—';
        const strategy=v=>({ROUND_ROBIN:'Luân phiên',LEAST_CONNECTIONS:'Ít kết nối nhất',ADAPTIVE:'Thích nghi'}[v]||'Chưa biết');
        const state=v=>({UP:'Hoạt động',DOWN:'Ngừng',WARMING:'Tăng tải dần',CLOSED:'Đóng',OPEN:'Mở',HALF_OPEN:'Thử phục hồi'}[v]||'Chưa biết');
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
        async function read(path){const r=await fetch(path,{cache:'no-store',signal:AbortSignal.timeout(3500)});if(!r.ok)throw new Error('HTTP '+r.status);return r.json();}
        function connection(text,ok){el('state').textContent=text;el('state').className=ok?'ok':'bad';}
        function render(m,now){
          if(!Array.isArray(m.backends)||typeof m.requests_total!=='number')throw new Error('Số liệu không đúng định dạng');
          el('strategy').textContent=strategy(m.strategy);el('total').textContent=number(m.requests_total);el('success').textContent=number(m.requests_successful);el('failed').textContent=number(m.requests_failed);
          el('limited').textContent=number(m.rate_limit_rejected);el('inflight').textContent=number(m.backends.reduce((n,b)=>n+b.inFlight,0));
          const complete=m.backends.reduce((n,b)=>n+b.completed,0);
          const rate=previous&&complete>=previous.complete&&now>previous.time?(complete-previous.complete)*1000/(now-previous.time):null;
          el('rps').textContent=rate==null?'—':rate.toLocaleString('vi-VN',{maximumFractionDigits:2});if(rate!=null){series.push(rate);if(series.length>40)series.shift();}spark();
          previous={complete,time:now};el('backends').replaceChildren();el('share').replaceChildren();
          const h=(t,c,x)=>{const n=document.createElement(t);if(c)n.className=c;if(x!=null)n.textContent=x;return n;};
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
            card.append(nums);el('backends').append(card);});
          el('failed-card').className='card k'+(m.requests_failed>0?' bad':'');
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
          const top=Math.max(...x.candidates.map(c=>c.rankingScoreMs||0),1);for(const c of x.candidates){const row=document.createElement('tr');row.style.setProperty('--w',(c.rankingScoreMs?Math.max(4,c.rankingScoreMs/top*100):0)+'%');if(c.backend===x.selectedBackend){row.className='selected';row.dataset.selected='true';}row.dataset.backend=c.backend;row.dataset.eligibility=c.eligibility;
            for(const v of [c.backend,reasons[c.eligibility]||'Chưa xác định',number(c.serviceMs),number(c.waitMs),number(c.completionMs),number(c.resourcePenalty),number(c.rankingScoreMs)]){const cell=document.createElement('td');cell.textContent=v;row.append(cell);}el('candidates').append(row);}
          el('decision-json').textContent=JSON.stringify(d,null,2);
        }
        async function poll(){if(busy)return;busy=true;try{const m=await read('/__proxy/metrics');render(m,performance.now());lastSuccess=performance.now();connection('Đã kết nối',true);
          if(!decisionFrozen)try{const snapshot=await read('/__proxy/decision');if(!decisionFrozen)decision(snapshot);}catch(e){if(!decisionFrozen)clearDecision('Không đọc được quyết định: '+e.message);}
        }catch(e){previous=null;el('rps').textContent='—';connection('Mất kết nối — dữ liệu hiển thị đã cũ. '+e.message,false);}finally{busy=false;}}
        el('refresh').addEventListener('click',poll);poll();setInterval(poll,2000);
        let changingStrategy=false;
        el('cycle-strategy').addEventListener('click',async()=>{if(changingStrategy)return;changingStrategy=true;el('cycle-strategy').disabled=true;
          try{const response=await fetch('/__proxy/strategy',{method:'POST',cache:'no-store',headers:{'X-Proxy-Action':'cycle-strategy'},signal:AbortSignal.timeout(3500)});
            const result=await response.json();if(!response.ok)throw new Error(result.error||'HTTP '+response.status);
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
        """;
}
