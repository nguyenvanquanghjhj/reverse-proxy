export class ApiError extends Error {constructor(status,code,message){super(message);this.status=status;this.code=code;}}
let current=null,pending=null;
export function identity(){return current;}
export async function session(force=false){if(current&&!force)return current;if(pending)return pending;pending=api('/session').then(s=>{if(typeof s.csrf!=='string'||s.csrf.length!==64||typeof s.authenticated!=='boolean')throw new Error('Phiên làm việc không hợp lệ.');current=s;return s;}).finally(()=>pending=null);return pending;}
export function setIdentity(s){current=s;}
export async function api(path,{method='GET',body,headers={}}={}){
  if(!path.startsWith('/'))throw new Error('Đường dẫn không hợp lệ.');
  const options={method,credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json',...headers},signal:AbortSignal.timeout(8000)};
  if(!['GET','HEAD'].includes(method)){const s=await session();options.headers['X-CSRF-Token']=s.csrf;}
  if(body!==undefined){if(body instanceof Blob||body instanceof ArrayBuffer){options.body=body;}else{options.body=JSON.stringify(body);options.headers['Content-Type']='application/json';}}
  let response;try{response=await fetch('/api/store'+path,options);}catch{throw new ApiError(0,'NETWORK','Chưa nhận được phản hồi. Vui lòng kiểm tra kết nối.');}
  let data;try{if(!response.headers.get('content-type')?.startsWith('application/json'))throw new Error();data=await response.json();if(!data||typeof data!=='object')throw new Error();}catch{throw new ApiError(response.ok?0:response.status,'INVALID_RESPONSE','Phản hồi chưa đầy đủ. Vui lòng kiểm tra lại kết quả.');}
  if(!response.ok)throw new ApiError(response.status,data.code||'REQUEST_FAILED',data.message||'Chưa thể xử lý yêu cầu.');return data;
}
export async function download(file){
  const r=await fetch(file.url,{credentials:'same-origin',signal:AbortSignal.timeout(8000)});if(!r.ok){let message='Tài liệu hiện chưa thể tải.';try{const error=await r.json();if(typeof error.message==='string')message=error.message;}catch{}throw new Error(message);}const b=await r.arrayBuffer();
  const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',b)),x=>x.toString(16).padStart(2,'0')).join('');
  if(b.byteLength!==file.size_bytes||hash!==file.sha256||!r.headers.get('content-type')?.startsWith(file.mime))throw new Error('Tệp chưa tải đầy đủ. Vui lòng thử lại.');
  const a=document.createElement('a'),url=URL.createObjectURL(new Blob([b],{type:file.mime}));a.href=url;a.download=file.display_name.endsWith('.pdf')?file.display_name:file.display_name+'.pdf';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
}
export const e=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export const money=n=>new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND',maximumFractionDigits:0}).format(n);
export const date=s=>new Date(s).toLocaleString('vi-VN');
export const status=s=>({CREATED:'Chờ xác nhận',CONFIRMED:'Đã xác nhận',PACKING:'Đang chuẩn bị',SHIPPED:'Đang giao hàng',DELIVERED:'Đã giao hàng',CANCELLED:'Đã hủy',PAYMENT_RECEIVED:'Đã xác nhận thu tiền'}[s]||s);
