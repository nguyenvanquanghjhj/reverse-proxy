const KEY='moc.cart.v1';
let items=[];
function validate(value){if(!Array.isArray(value)||value.length>20)throw new Error();const ids=new Set();return value.map(l=>{
  if(!Number.isSafeInteger(l.variant_id)||l.variant_id<1||!Number.isSafeInteger(l.product_id)||l.product_id<1||!Number.isInteger(l.quantity)||l.quantity<1||l.quantity>20||!Number.isSafeInteger(l.price_vnd)||l.price_vnd<0||typeof l.name!=='string'||l.name.length>160||typeof l.sku!=='string'||typeof l.color!=='string'||!Number.isInteger(l.storage_gb)||ids.has(l.variant_id))throw new Error();ids.add(l.variant_id);return l;});}
try{items=validate(JSON.parse(localStorage.getItem(KEY)||'[]'));}catch{items=[];}
export function cart(){return structuredClone(items);}
function save(next){try{localStorage.setItem(KEY,JSON.stringify(next));}catch{throw new Error('Trình duyệt chưa lưu được giỏ hàng. Vui lòng cho phép lưu dữ liệu.');}items=next;window.dispatchEvent(new Event('cartchange'));}
export function add(product,variant,quantity){if(!Number.isInteger(quantity)||quantity<1||quantity>20)throw new Error('Số lượng từ 1 đến 20.');const next=cart(),old=next.find(x=>x.variant_id===variant.id),total=(old?.quantity||0)+quantity;
  if(!variant.active||total>variant.stock||total>20)throw new Error('Số lượng còn lại không đủ cho lựa chọn này.');
  if(old)old.quantity=total;else{if(next.length>=20)throw new Error('Giỏ hàng tối đa 20 phiên bản.');next.push({variant_id:variant.id,product_id:product.id,name:product.name,sku:variant.sku,color:variant.color,storage_gb:variant.storage_gb,price_vnd:variant.price_vnd,quantity,image:product.files.find(f=>f.kind==='IMAGE')?.url||''});}save(next);
}
export function quantity(id,n){if(!Number.isInteger(n)||n<1||n>20)throw new Error('Số lượng từ 1 đến 20.');save(items.map(x=>x.variant_id===id?{...x,quantity:n}:x));}
export function remove(id){save(items.filter(x=>x.variant_id!==id));}
export function clear(){save([]);}
export function consumed(lines){const next=cart();for(const line of lines){const old=next.find(x=>x.variant_id===line.variant_id);if(old)old.quantity-=line.quantity;}save(next.filter(x=>x.quantity>0));}
export const subtotal=()=>items.reduce((s,x)=>s+x.price_vnd*x.quantity,0);
export const count=()=>items.reduce((s,x)=>s+x.quantity,0);
export function refresh(products){const next=cart();let changed=false;next.forEach(l=>{const p=products.find(p=>p?.id===l.product_id),v=p?.variants.find(v=>v.id===l.variant_id),image=p?.files?.find(f=>f.kind==='IMAGE')?.url||'';if(v&&(l.price_vnd!==v.price_vnd||l.name!==p.name||l.color!==v.color||l.image!==image)){Object.assign(l,{price_vnd:v.price_vnd,name:p.name,color:v.color,sku:v.sku,storage_gb:v.storage_gb,image});changed=true;}});if(changed)save(next);}
window.addEventListener('storage',event=>{if(event.key===KEY)try{items=validate(JSON.parse(event.newValue||'[]'));window.dispatchEvent(new Event('cartchange'));}catch{}});
