import * as api from './api.js';
import {OrderFlow, validatePayload} from './order-state.js';

const $ = id => document.getElementById(id);
const node = (tag, text, className) => {
  const result=document.createElement(tag);
  if (text !== undefined) result.textContent=text;
  if (className) result.className=className;
  return result;
};
const money = value => new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND',maximumFractionDigits:0}).format(value);
const flow=new OrderFlow(), cart=new Map(), invalidQuantities=new Set();
let products=[], loading=null, detailVersion=0, detailOpener=null;

function fieldError(inputId, errorId, text) {
  $(inputId).setAttribute('aria-invalid',String(Boolean(text)));
  $(errorId).textContent=text; $(errorId).hidden=!text;
}
function nameFor(id) { return flow.intent?.lines.find(line => line.productId === id)?.name || products.find(p => p.id === id)?.name || `Sản phẩm ${id}`; }
function estimatedTotal() { return [...cart.values()].reduce((sum,item) => sum+item.priceVnd*item.quantity,0); }
function orderDetails(order, includeId=false) {
  const box=node('div',undefined,'order-details');
  if (includeId) box.append(node('p','Mã đơn: '+order.orderId,'order-code'));
  box.append(node('p','Tổng tiền: '+money(order.totalVnd)),node('p','Trạng thái: Đã tạo'),
    node('p',new Date(order.createdAt).toLocaleString('vi-VN'),'muted'));
  const list=node('ul');
  for (const item of order.items) list.append(node('li',`${nameFor(item.productId)} × ${item.quantity} · ${money(item.lineTotalVnd)}`));
  box.append(list); return box;
}

function renderOrder() {
  const phase=flow.phase, busy=phase === 'SUBMITTING', editable=flow.editable;
  $('order-panel').dataset.orderState=phase;
  $('customer').disabled=!editable;
  $('cart').querySelectorAll('input,button').forEach(control => control.disabled=!editable);
  $('catalog').querySelectorAll('[data-product-control]').forEach(control => control.disabled=!editable || control.dataset.sold === 'true');
  $('submit').disabled=busy || phase === 'CONFIRMED' || (flow.ambiguous && !flow.intent)
    || flow.error?.code === 'IDEMPOTENCY_CONFLICT' || (editable && !cart.size);
  $('submit').setAttribute('aria-busy',String(busy));
  $('submit').textContent=busy ? 'Đang xử lý đơn hàng…' : phase === 'CONFIRMED' ? 'Đã xác nhận đơn' : flow.ambiguous ? 'Kiểm tra lại đơn' : 'Đặt hàng';
  const step={IDLE:'Chọn sản phẩm',VALIDATING:'Kiểm tra thông tin',SUBMITTING:'Đang xử lý',CONFIRMED:'Đã xác nhận',BUSINESS_ERROR:'Cần điều chỉnh',UNKNOWN_RESULT:'Cần kiểm tra lại',NETWORK_ERROR:'Chưa gửi được đơn'};
  $('order-step').textContent=step[phase];
  const quantity=[...cart.values()].reduce((sum,item) => sum+item.quantity,0), total=estimatedTotal();
  $('cart-count').textContent=String(quantity); $('estimate').textContent=money(total);
  $('cart-shortcut-total').textContent=money(total); $('cart-shortcut').hidden=!quantity || phase === 'CONFIRMED';
  document.body.classList.toggle('has-cart',quantity>0 && phase !== 'CONFIRMED');
  $('new-order').hidden=!flow.intent && !flow.ambiguous; $('new-order').disabled=busy;
  const result=$('order-result'); result.replaceChildren(); result.className='order-result';
  $('confirmed-order').replaceChildren(); $('confirmed-order').hidden=true;
  if (busy) result.append(node('strong','Đang xử lý đơn hàng…'),node('p','Bạn không cần bấm thêm lần nữa.'));
  else if (phase === 'CONFIRMED') {
    result.append(node('strong','Đặt hàng thành công'),node('p','Mã đơn: '+flow.order.orderId,'order-code'));
    $('confirmed-order').hidden=false; $('confirmed-order').append(orderDetails(flow.order));
    if (flow.order.totalVnd !== flow.intent.estimate) result.append(node('p','Giá sản phẩm đã thay đổi. Tổng tiền bên dưới là giá đã được xác nhận.'));
    $('lookup-id').value=flow.order.orderId;
  } else if (flow.ambiguous) {
    result.classList.add('uncertain');result.append(node('strong','Chưa xác định kết quả đơn hàng'));
    result.append(node('p',flow.intent && flow.error?.code !== 'IDEMPOTENCY_CONFLICT'
      ? 'Đơn có thể đã được tạo. Chọn Kiểm tra lại đơn để nhận kết quả.' : api.message(flow.error)));
    if (flow.error?.code === 'OFFLINE') result.append(node('p',api.message(flow.error)));
  } else if (flow.error) {
    result.classList.add('error'); result.append(node('strong',phase === 'NETWORK_ERROR' ? 'Chưa gửi được đơn hàng' : 'Chưa thể đặt hàng'),node('p',api.message(flow.error)));
  }
}

function renderCart() {
  $('cart').replaceChildren();
  if (!cart.size) $('cart').append(node('p','Chưa có sản phẩm. Hãy chọn một món ở danh sách sản phẩm.','empty'));
  for (const [id,item] of cart) {
    const row=node('div',undefined,'cart-row'), edit=node('div',undefined,'cart-edit');
    row.append(node('strong',item.name),node('span',money(item.priceVnd*item.quantity),'cart-line-price'));
    const label=node('label','Số lượng '+item.name);label.htmlFor='cart-quantity-'+id;label.className='small';
    const quantity=node('input');quantity.id=label.htmlFor;quantity.type='number';quantity.min='1';quantity.max='100';quantity.value=String(item.quantity);
    const error=node('p','','field-error');error.id='cart-error-'+id;error.hidden=true;quantity.setAttribute('aria-describedby',error.id);
    quantity.addEventListener('input',() => {
      const value=Number(quantity.value);
      const available=products.find(p => p.id === id)?.stockQuantity ?? 100;
      const valid=Number.isInteger(value) && value>=1 && value<=Math.min(100,available);
      quantity.setAttribute('aria-invalid',String(!valid));error.hidden=valid;
      error.textContent=valid ? '' : 'Số lượng phải từ 1 đến 100 và trong tồn kho.';
      if (valid) {invalidQuantities.delete(id);item.quantity=value;row.querySelector('.cart-line-price').textContent=money(item.priceVnd*value);}
      else invalidQuantities.add(id);
      renderOrder();
    });
    const remove=node('button','Bỏ','button secondary');remove.type='button';remove.setAttribute('aria-label','Bỏ '+item.name+' khỏi đơn');
    remove.onclick=() => {if (!flow.editable) return;cart.delete(id);invalidQuantities.delete(id);renderCart();renderOrder();};
    edit.append(label,quantity,remove); row.append(edit,error); $('cart').append(row);
  }
}
function visual(sku) {
  const kind=sku.startsWith('PEN') ? 'pen' : sku.startsWith('BAG') ? 'bag' : sku.startsWith('KIT') ? 'kit' : 'note';
  const paths={note:'M17 8h22v39H17z M11 8h6v39h-6z M23 17h10 M23 24h10 M23 31h7',
    pen:'M14 42l4-12 22-22 8 8-22 22z M18 30l8 8 M36 12l8 8 M14 42l12-4',
    bag:'M10 22h36l-3 28H13z M20 22v-7a8 8 0 0 1 16 0v7',
    kit:'M8 22l20-12 20 12-20 12z M8 22v22l20 12 20-12V22 M28 34v22 M18 16l20 12'};
  const box=node('div',undefined,'product-visual visual-'+kind);box.setAttribute('aria-hidden','true');
  const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 56 64');
  const path=document.createElementNS(svg.namespaceURI,'path');path.setAttribute('d',paths[kind]);svg.append(path);box.append(svg);return box;
}
function renderProducts() {
  $('catalog').replaceChildren();
  if (!products.length) {$('catalog').append(node('p','Chưa có sản phẩm để hiển thị. Hãy quay lại sau.','empty'));return;}
  for (const product of products) {
    const card=node('article',undefined,'card');card.dataset.productId=String(product.id);
    const body=node('div',undefined,'product-body');body.append(node('h3',product.name),node('div',money(product.priceVnd),'price'),
      node('p',product.stockQuantity ? `Còn ${product.stockQuantity} sản phẩm` : 'Sản phẩm hiện đã hết hàng.','stock'+(product.stockQuantity ? '' : ' empty')));
    const detail=node('button','Chi tiết & tài liệu','detail');detail.type='button';detail.onclick=() => showDetail(product.id,detail);body.append(detail);
    const actions=node('div',undefined,'product-actions'),field=node('div',undefined,'quantity-field');
    const label=node('label','Số lượng');label.htmlFor='quantity-'+product.id;
    const quantity=node('input');quantity.id=label.htmlFor;quantity.type='number';quantity.min='1';quantity.max=String(Math.min(100,product.stockQuantity));quantity.value='1';quantity.setAttribute('aria-label','Số lượng '+product.name);
    quantity.dataset.productControl='true';quantity.dataset.sold=String(product.stockQuantity===0);
    field.append(label,quantity);
    const add=node('button','Thêm vào đơn','button primary');add.type='button';add.dataset.productControl='true';add.dataset.sold=String(product.stockQuantity===0);
    add.onclick=() => {
      if (!flow.editable) return;
      const count=Number(quantity.value),existing=cart.get(product.id)?.quantity || 0;
      const valid=Number.isInteger(count) && count>=1 && count+existing<=Math.min(100,product.stockQuantity);
      quantity.setAttribute('aria-invalid',String(!valid));
      if (!valid) {$('catalog-state').textContent='Số lượng phải từ 1 đến 100 và trong tồn kho.';quantity.focus();return;}
      if (!cart.has(product.id) && cart.size>=20) {$('catalog-state').textContent='Mỗi đơn có tối đa 20 loại sản phẩm.';return;}
      cart.set(product.id,{...product,quantity:existing+count});invalidQuantities.delete(product.id);
      $('catalog-state').textContent='Đã thêm '+product.name+' vào đơn.';renderCart();renderOrder();
    };
    actions.append(field,add);body.append(actions);card.append(visual(product.sku),body);$('catalog').append(card);
  }
}
async function loadProducts() {
  if (loading) return loading;
  $('reload').disabled=true;$('catalog').setAttribute('aria-busy','true');
  loading=(async () => {
    try {
      products=await api.products();
      if (flow.editable) for (const [id,line] of cart) {
        const current=products.find(p => p.id===id);
        if (current) {line.priceVnd=current.priceVnd;line.name=current.name;}
      }
      renderProducts();renderCart();renderOrder();$('catalog-state').textContent=products.length ? '' : 'Chưa có sản phẩm.';
      return true;
    } catch (error) {
      $('catalog-state').textContent=api.message(error)+' Chọn Làm mới để tải lại.';
      if (!products.length) $('catalog').replaceChildren(node('p','Chưa tải được sản phẩm.','empty'));
      return false;
    } finally {$('reload').disabled=false;$('catalog').setAttribute('aria-busy','false');loading=null;}
  })();
  return loading;
}

$('customer').addEventListener('input',() => fieldError('customer','customer-error',''));
$('reload').onclick=loadProducts;
$('order-form').addEventListener('submit',async event => {
  event.preventDefault();
  if (flow.phase==='SUBMITTING' || flow.phase==='CONFIRMED') return;
  let payload;
  if (flow.editable) {
    const name=$('customer').value.trim();
    const valid=name && [...name].length<=80 && !/[\u0000-\u001f\u007f-\u009f]/.test(name);
    fieldError('customer','customer-error',valid ? '' : 'Nhập tên từ 1 đến 80 ký tự, không chứa ký tự điều khiển.');
    if (!valid) {$('customer').focus();return;}
    if (!cart.size || invalidQuantities.size) {$('order-result').textContent='Vui lòng kiểm tra sản phẩm và số lượng trong đơn.';$('order-result').focus();return;}
    payload={customerName:name,items:[...cart].map(([productId,line]) => ({productId,quantity:line.quantity}))};
    try {validatePayload(payload);} catch (error) {$('order-result').textContent=api.message(error);return;}
  }
  if (!navigator.onLine) {flow.offline();renderOrder();return;}
  const intent=flow.prepare(payload,[...cart].map(([productId,line]) => ({productId,name:line.name,priceVnd:line.priceVnd})),estimatedTotal());
  renderOrder();if (!intent) return;
  try {flow.confirmed(await api.createOrder(intent));renderOrder();void loadProducts();}
  catch (error) {flow.failed(error);renderOrder();if (error.kind==='business') void loadProducts();}
});
function restart() {
  if (!flow.restart()) {renderOrder();return;}
  cart.clear();invalidQuantities.clear();$('customer').value='';fieldError('customer','customer-error','');renderCart();renderOrder();$('customer').focus();
}
$('new-order').onclick=() => {if (flow.ambiguous) {$('restart-confirm').showModal();$('keep-order').focus();} else restart();};
$('keep-order').onclick=() => $('restart-confirm').close();
$('discard-order').onclick=() => {$('restart-confirm').close();restart();};
$('lookup-form').addEventListener('submit',async event => {
  event.preventDefault();if ($('lookup').disabled) return;
  const id=$('lookup-id').value.trim();fieldError('lookup-id','lookup-error',api.isUuid(id) ? '' : 'Nhập mã đơn đầy đủ đã nhận sau khi đặt hàng.');
  if (!api.isUuid(id)) {$('lookup-id').focus();return;}
  $('lookup').disabled=true;$('lookup-result').className='lookup-result';$('lookup-result').textContent='Đang tìm đơn hàng…';
  try {$('lookup-result').replaceChildren(orderDetails(await api.order(id),true));}
  catch (error) {$('lookup-result').classList.add('error');$('lookup-result').textContent=api.message(error);}
  finally {$('lookup').disabled=false;}
});

async function showDetail(id,opener) {
  const version=++detailVersion;detailOpener=opener;
  $('detail-name').textContent='Đang tải thông tin…';$('detail-info').textContent='';$('documents').textContent='Đang tải tài liệu…';
  if (!$('detail').open) $('detail').showModal();
  try {
    const product=await api.product(id);if (version!==detailVersion) return;
    $('detail-name').textContent=product.name;$('detail-info').textContent=money(product.priceVnd)+' · '+(product.stockQuantity ? `Còn ${product.stockQuantity} sản phẩm` : 'Hiện đã hết hàng');
    const docs=await api.documents(id);if (version!==detailVersion) return;
    $('documents').replaceChildren();
    if (!docs.length) {$('documents').append(node('p','Sản phẩm này chưa có tài liệu.','muted'));return;}
    for (const doc of docs) {
      const row=node('div',undefined,'doc'),button=node('button','Tải xuống','button secondary'),feedback=node('p','','message');
      feedback.setAttribute('role','status');button.type='button';
      row.append(node('strong',doc.displayName),node('span',(doc.sizeBytes/1024).toLocaleString('vi-VN',{maximumFractionDigits:1})+' KB','doc-size'),button,feedback);
      button.onclick=async () => {
        button.disabled=true;button.textContent='Đang tải…';feedback.className='message';feedback.textContent='Đang chuẩn bị tài liệu.';
        try {
          const bytes=await api.download(doc),url=URL.createObjectURL(new Blob([bytes],{type:'application/pdf'}));
          const link=node('a');link.href=url;link.download=doc.displayName;link.click();setTimeout(() => URL.revokeObjectURL(url),30000);
          feedback.textContent='Đã chuyển tài liệu cho trình duyệt lưu.';
        } catch (error) {feedback.classList.add('error');feedback.textContent=api.message(error);}
        finally {button.disabled=false;button.textContent='Tải xuống';}
      };
      $('documents').append(row);
    }
  } catch (error) {if (version===detailVersion) {$('detail-name').textContent='Chưa thể xem sản phẩm';$('documents').textContent=api.message(error);}}
}
$('close-detail').onclick=() => $('detail').close();
$('detail').addEventListener('close',() => {detailVersion++;detailOpener?.focus();});

if (flow.intent) {
  const payload=JSON.parse(flow.intent.body);$('customer').value=payload.customerName;
  for (const item of payload.items) {
    const line=flow.intent.lines.find(line => line.productId===item.productId);
    cart.set(item.productId,{id:item.productId,name:line?.name || 'Sản phẩm đã chọn',priceVnd:line?.priceVnd || 0,quantity:item.quantity});
  }
}
renderCart();renderOrder();void loadProducts();
if (flow.phase==='CONFIRMED') {
  const id=flow.order.orderId;
  api.order(id).then(order => {
    if (flow.phase==='CONFIRMED' && flow.order.orderId===id) {flow.order=order;renderOrder();}
  }).catch(() => {
    if (flow.phase==='CONFIRMED' && flow.order.orderId===id) $('order-result').append(node('p','Đây là lần xác nhận gần nhất. Chưa thể cập nhật thông tin đơn lúc này.'));
  });
}
