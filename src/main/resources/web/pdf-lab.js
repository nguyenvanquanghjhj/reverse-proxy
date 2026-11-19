/* Incremental SHA-256: a fixed 64-byte block and 64-word schedule per download. */
class PdfSha256 {
  constructor() {this.h=new Uint32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]);this.block=new Uint8Array(64);this.w=new Uint32Array(64);this.used=0;this.bytes=0;}
  update(data) {this.bytes+=data.length;for(let i=0;i<data.length;){const n=Math.min(64-this.used,data.length-i);this.block.set(data.subarray(i,i+n),this.used);this.used+=n;i+=n;if(this.used===64){this.compress();this.used=0;}}return this;}
  compress() {
    const k=PdfSha256.k,w=this.w,b=this.block,r=(v,n)=>(v>>>n)|(v<<(32-n));
    for(let i=0;i<16;i++)w[i]=(b[i*4]<<24)|(b[i*4+1]<<16)|(b[i*4+2]<<8)|b[i*4+3];
    for(let i=16;i<64;i++){const x=w[i-15],y=w[i-2];w[i]=(w[i-16]+(r(x,7)^r(x,18)^(x>>>3))+w[i-7]+(r(y,17)^r(y,19)^(y>>>10)))>>>0;}
    let [a,c,d,e,f,g,h,j]=this.h;
    for(let i=0;i<64;i++){const t1=(j+(r(f,6)^r(f,11)^r(f,25))+((f&g)^(~f&h))+k[i]+w[i])>>>0,t2=((r(a,2)^r(a,13)^r(a,22))+((a&c)^(a&d)^(c&d)))>>>0;j=h;h=g;g=f;f=(e+t1)>>>0;e=d;d=c;c=a;a=(t1+t2)>>>0;}
    [a,c,d,e,f,g,h,j].forEach((v,i)=>this.h[i]=(this.h[i]+v)>>>0);
  }
  hex() {const length=this.bytes;this.block[this.used++]=0x80;if(this.used>56){this.block.fill(0,this.used);this.compress();this.used=0;}this.block.fill(0,this.used);const view=new DataView(this.block.buffer);view.setUint32(56,Math.floor(length/0x20000000));view.setUint32(60,(length*8)>>>0);this.compress();return [...this.h].map(v=>v.toString(16).padStart(8,'0')).join('');}
}
PdfSha256.k=new Uint32Array([0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2]);
(() => {
  const panel=document.querySelector('#pdf-lab');if(!panel)return;
  const $=id=>document.getElementById(id);let status,running=false,polling;
  async function refresh() {
    try {const response=await fetch('/__proxy/pdf-lab',{cache:'no-store',signal:AbortSignal.timeout(4000)});if(!response.ok)throw new Error('Chỉ dùng tại máy Proxy, qua cổng dữ liệu.');status=await response.json();
      $('pdf-source').replaceChildren(new Option(status.name,status.source_id));$('pdf-provenance').textContent='Nguồn: '+status.source+' · Giấy phép: '+status.license;
      const r=status.receipt;$('pdf-state').textContent=!status.enabled?'Đang tắt. Chỉ bật PDF lab và streaming trong môi trường thử nghiệm riêng.':r.state==='READY'?`Đã nhập ${r.size_bytes.toLocaleString('vi-VN')} byte trong ${r.import_ms.toFixed(1)} ms. Thời gian nhập từ Internet được tách khỏi các lượt tải bên dưới.`:r.state==='FAILED'?r.error:r.state==='IMPORTING'?'Đang nhập PDF từ nguồn HTTPS…':'Chưa nhập tài liệu.';
      $('pdf-import').disabled=!status.enabled||status.busy||running;$('pdf-test').disabled=!status.enabled||r.state!=='READY'||running;
      if(status.busy&&!polling)polling=setTimeout(()=>{polling=null;refresh();},800);
    } catch(error){$('pdf-state').textContent=error.message;$('pdf-import').disabled=true;$('pdf-test').disabled=true;}
  }
  $('pdf-import').onclick=async()=>{try{const r=await fetch('/__proxy/pdf-lab',{method:'POST',headers:{'X-Proxy-Action':'import-pdf','X-PDF-Source':$('pdf-source').value},signal:AbortSignal.timeout(4000)});if(!r.ok)throw new Error('Không nhập được: HTTP '+r.status+'. Nếu có token operator, dùng lệnh PdfLab import tại máy Proxy.');await refresh();}catch(error){$('pdf-state').textContent=error.message;}};
  $('pdf-test').onclick=async()=>{
    if(running||status?.receipt.state!=='READY')return;running=true;$('pdf-test').disabled=true;$('pdf-import').disabled=true;
    const expected=status.receipt,count=Number($('pdf-concurrency').value);$('pdf-results').replaceChildren();
    try {await Promise.all(Array.from({length:count},async(_,i)=>{
      const row=document.createElement('tr');$('pdf-results').append(row);let bytes=0,code=0,backend='—',reader;const start=performance.now(),controller=new AbortController(),timer=setTimeout(()=>controller.abort(),30000);
      try {const r=await fetch(expected.path,{cache:'no-store',signal:controller.signal});code=r.status;backend=r.headers.get('X-Proxy-Backend')||'—';if(!r.ok||!r.headers.get('Content-Type')?.startsWith('application/pdf'))throw new Error('Phản hồi không phải PDF hợp lệ');
        const hash=new PdfSha256();reader=r.body.getReader();for(;;){const {done,value}=await reader.read();if(done)break;bytes+=value.length;if(bytes>status.max_bytes)throw new Error('Vượt giới hạn thử tải');hash.update(value);}
        if(bytes!==expected.size_bytes||hash.hex()!==expected.sha256||r.headers.get('ETag')!=='"'+expected.sha256+'"')throw new Error('Không khớp kích thước, SHA-256 hoặc ETag');
        finish('PASS · SHA-256, byte count và ETag khớp');
      }catch(error){await reader?.cancel().catch(()=>{});finish('FAIL · '+error.message);}finally{clearTimeout(timer);reader?.releaseLock();}
      function finish(result){const ms=performance.now()-start;for(const value of [i+1,status.name,backend,code,ms.toFixed(1),bytes,(bytes/1048576/(ms/1000)).toFixed(2),result]){const td=document.createElement('td');td.textContent=value;row.append(td);}}
    }));}finally{running=false;refresh();}
  };
  refresh();
})();
