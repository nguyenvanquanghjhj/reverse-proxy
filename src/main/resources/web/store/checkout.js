import {api,ApiError} from './api.js';
import {consumed} from './cart.js';
const KEY='moc.checkout.v1',ACCESS='moc.orders.v1';
let intent=null,corrupt=false;
try{intent=JSON.parse(sessionStorage.getItem(KEY)||'null');if(intent&&(!intent.key||!intent.body||!['SUBMITTING','UNKNOWN','BUSINESS_ERROR','CONFIRMED'].includes(intent.state)))throw new Error();if(intent?.state==='SUBMITTING')intent.state='UNKNOWN';}catch{intent=null;corrupt=true;}
export const flow=()=>({intent:structuredClone(intent),corrupt});
function persist(next){try{sessionStorage.setItem(KEY,JSON.stringify(next));}catch{throw new Error('Chưa lưu được thông tin khôi phục đơn. Vui lòng cho phép lưu dữ liệu trước khi đặt hàng.');}intent=next;}
export function token(id){try{return JSON.parse(sessionStorage.getItem(ACCESS)||'{}')[id]||null;}catch{return null;}}
export function remember(id,value){const m=JSON.parse(sessionStorage.getItem(ACCESS)||'{}');m[id]=value;const entries=Object.entries(m).slice(-40);sessionStorage.setItem(ACCESS,JSON.stringify(Object.fromEntries(entries)));}
function validOrder(o,body){return o&&typeof o.id==='string'&&/^[0-9a-f-]{36}$/.test(o.id)&&Number.isSafeInteger(o.total_vnd)&&o.total_vnd>0&&Number.isSafeInteger(o.subtotal_vnd)&&Number.isSafeInteger(o.shipping_vnd)&&o.total_vnd===o.subtotal_vnd+o.shipping_vnd&&o.payment_method==='COD'&&['UNPAID','PAID'].includes(o.payment_status)&&Array.isArray(o.items)&&o.items.length===body.items.length&&o.items.every(x=>Number.isSafeInteger(x.quantity)&&Number.isSafeInteger(x.unit_price_vnd)&&x.line_total_vnd===x.quantity*x.unit_price_vnd&&body.items.some(l=>l.variant_id===x.variant_id&&l.quantity===x.quantity))&&o.items.reduce((sum,x)=>sum+x.line_total_vnd,0)===o.subtotal_vnd;}
export async function submit(body){
  if(corrupt)throw new Error('Không đọc được đơn trước đó. Hãy tra cứu trước khi tạo yêu cầu mới.');
  if(intent?.state==='SUBMITTING')return null;if(intent?.state==='CONFIRMED')return intent.order;
  if(intent?.state==='UNKNOWN')body=intent.body;
  const wasUnknown=intent?.state==='UNKNOWN';
  const same=intent&&JSON.stringify(intent.body)===JSON.stringify(body);
  const next={key:same?intent.key:crypto.randomUUID().replaceAll('-',''),body:structuredClone(body),state:'SUBMITTING'};
  persist(next);
  let order;
  try{order=await api('/orders',{method:'POST',body:next.body,headers:{'Idempotency-Key':next.key}});if(!validOrder(order,next.body))throw new ApiError(0,'INVALID_ORDER','Kết quả đơn chưa đầy đủ.');
    persist({...next,state:'CONFIRMED',order});
  }catch(error){const business=!wasUnknown&&error instanceof ApiError&&error.status>=400&&error.status<500&&error.code!=='IDEMPOTENCY_CONFLICT';persist({...next,state:business?'BUSINESS_ERROR':'UNKNOWN',message:error.message});throw error;}
  // A local cart/storage failure after confirmation must never turn a committed order into a new intent.
  try{remember(order.id,next.body.guest_token);consumed(next.body.items);}catch{/* Confirmation retains both the order and access secret. */}
  return order;
}
export function fresh(){try{sessionStorage.removeItem(KEY);}catch{throw new Error('Không thể bắt đầu đơn mới.');}intent=null;corrupt=false;}
export function secret(){return Array.from(crypto.getRandomValues(new Uint8Array(32)),n=>n.toString(16).padStart(2,'0')).join('');}
