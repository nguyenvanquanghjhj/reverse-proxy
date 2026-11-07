import {api,e,date} from './api.js';
import {errorBox} from './views.js';

export async function operationsView({go,toast,confirm}) {
  const state=await api('/admin/operations');
  let backends=[];
  try{const response=await fetch('/__proxy/metrics',{cache:'no-store',signal:AbortSignal.timeout(4000)});if(response.ok)backends=(await response.json()).backends||[];}catch{}
  const flag=(name,label,value)=>`<label class="check"><input type="checkbox" name="${name}" ${value?'checked':''}> ${label}</label>`;
  return {html:`<div class="page-heading"><p class="eyebrow">VẬN HÀNH CỬA HÀNG</p><h1>Trạng thái dịch vụ</h1><p>Tạm dừng một chức năng trong khi những phần khác vẫn hoạt động.</p></div>
    <section class="panel"><div class="section-heading"><h2>Kết nối dữ liệu</h2><button class="secondary" id="refresh-operations">Kiểm tra lại</button></div>
    <dl><div><dt>PostgreSQL</dt><dd>${state.dependencies.database==='UP'?'Sẵn sàng':'Chưa kết nối được'}</dd></div><div><dt>Kho tệp dùng chung</dt><dd>${state.dependencies.storage==='UP'?'Đọc và ghi được':'Chưa truy cập được'}</dd></div></dl><p class="muted">Kiểm tra lúc ${e(date(state.dependencies.checked_at))}. Đây là kết quả tại backend phục vụ yêu cầu này.</p></section>
    <section class="panel"><h2>Các máy chủ phục vụ</h2>${backends.length?`<ul>${backends.map(b=>`<li>${e(b.id)} — ${e(({UP:'Đang hoạt động',WARMING:'Đang nhận tải dần',DOWN:'Không phục vụ',DRAINING:'Đang kết thúc yêu cầu đã nhận'})[b.state]||b.state)} · ${b.inFlight} yêu cầu đang xử lý</li>`).join('')}</ul>`:'<p>Chưa lấy được trạng thái các máy chủ. Bạn vẫn có thể quản lý chức năng bên dưới.</p>'}<p class="muted">Chỉ xem trạng thái tại đây. Thao tác bảo trì máy chủ dùng công cụ vận hành riêng.</p></section>
    <section class="panel"><h2>Khả năng phục vụ</h2><dl>${Object.entries(state.dependencies.capabilities||{}).map(([key,value])=>`<div><dt>${e(({static_files:'Trang tĩnh',catalog:'Danh mục',order_create:'Tạo đơn',document_download:'Tải PDF',document_upload:'Upload PDF'})[key]||key)}</dt><dd>${e(({AVAILABLE:'Sẵn sàng',PAUSED:'Tạm dừng',UNAVAILABLE:'Chưa phục vụ được'})[value]||value)}</dd></div>`).join('')}</dl></section>
    <form id="operations-form" class="panel"><h2>Nhận đơn & tài liệu</h2>
    ${flag('sales_enabled','Nhận đơn hàng mới',state.sales_enabled)}
    <p class="muted">Khi tắt, khách vẫn xem sản phẩm, giữ giỏ hàng và tra cứu đơn. Yêu cầu kiểm tra lại một đơn đã được ghi nhận vẫn nhận đúng đơn cũ.</p>
    ${flag('downloads_enabled','Cho phép tải tài liệu PDF',state.downloads_enabled)}
    <p class="muted">Hình ảnh sản phẩm và chức năng quản lý tệp vẫn hoạt động. Lượt tải đã được tiếp nhận có thể hoàn tất.</p>
    ${flag('uploads_enabled','Cho phép thêm tài liệu PDF',state.uploads_enabled)}<p class="muted">Khi tắt, ảnh sản phẩm và PDF cũ vẫn dùng được. Upload PDF đã được tiếp nhận có thể hoàn tất.</p>
    <label>Ghi chú vận hành<input name="reason" maxlength="200" value="${e(state.reason)}" placeholder="Lý do điều chỉnh (không bắt buộc)"></label>
    <div class="form-feedback" aria-live="polite"></div><button type="submit">Lưu trạng thái vận hành</button><p class="field-hint">Thay đổi áp dụng cho cả ba backend. Nếu người khác vừa cập nhật, hãy tải lại trước khi lưu.</p></form>
    <section class="panel"><h2>Thay đổi gần đây</h2>${state.history.length?`<ol class="timeline">${state.history.map(h=>`<li><strong>${h.sales_enabled?'Nhận đơn':'Ngừng nhận đơn'} · ${h.downloads_enabled?'Cho tải PDF':'Ngừng tải PDF'} · ${h.uploads_enabled?'Cho thêm PDF':'Ngừng thêm PDF'}</strong><span>${e(date(h.created_at))} · Phiên bản ${h.version}</span>${h.reason?`<p>${e(h.reason)}</p>`:''}</li>`).join('')}</ol>`:'<p>Chưa có thay đổi vận hành.</p>'}</section>`,bind(root){
      root.querySelector('#refresh-operations').onclick=()=>go('/admin/operations',true);
      const form=root.querySelector('#operations-form');
      form.onsubmit=async event=>{
        event.preventDefault();const button=form.querySelector('[type=submit]');if(button.disabled)return;button.disabled=true;
        try {
          const body={sales_enabled:form.elements.sales_enabled.checked,downloads_enabled:form.elements.downloads_enabled.checked,uploads_enabled:form.elements.uploads_enabled.checked,reason:form.elements.reason.value,version:state.version};
          if((state.sales_enabled&&!body.sales_enabled||state.downloads_enabled&&!body.downloads_enabled||state.uploads_enabled&&!body.uploads_enabled)&&!await confirm('Áp dụng tạm dừng chức năng đã chọn cho toàn cửa hàng?'))return;
          await api('/admin/operations',{method:'PUT',body});toast('Đã lưu trạng thái vận hành.');go('/admin/operations',true);
        } catch(error){form.querySelector('.form-feedback').innerHTML=errorBox(error.message);}
        finally{button.disabled=false;}
      };
    }};
}
