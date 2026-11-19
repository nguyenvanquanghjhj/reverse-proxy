import { api, download, e } from "./api.js";
import { errorBox } from "./views.js";

const size = n => `${(n / 1024).toLocaleString("vi-VN", {maximumFractionDigits: 1})} KiB`;
const input = (name, value) => `<label>Tên hiển thị<input name="display_name" value="${e(value)}" required maxlength="100"></label>`;
const feedback = (form, message) => { form.querySelector(".form-feedback").innerHTML = message ? errorBox(message) : ""; };
let cachedPolicy, policyAt = 0, pendingPolicy;
async function uploadPolicy(fresh=false) {
  if(fresh){cachedPolicy=null;policyAt=0;}
  if(cachedPolicy&&Date.now()-policyAt<30000)return cachedPolicy;
  if(!pendingPolicy)pendingPolicy=api("/policy").then(value=>{
    if(![value.max_pdf_bytes,value.max_image_bytes].every(n=>Number.isInteger(n)&&n>0&&n<=524288))throw new Error("Chính sách tải lên không hợp lệ.");
    cachedPolicy=value;policyAt=Date.now();return value;
  }).finally(()=>pendingPolicy=null);
  return pendingPolicy;
}
function safeName(name, kind) {
  const ext = kind === "PDF" ? ".pdf" : /\.jpe?g$/i.test(name) ? ".jpg" : ".png";
  const stem = name.replace(/\.[^.]*$/, "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/[đĐ]/g, "d")
    .replace(/[^A-Za-z0-9_-]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 80);
  return (stem || (kind === "PDF" ? "tai-lieu" : "hinh-anh")) + ext;
}
export function fileManager(p) {
  const images = p.files.filter(f => f.kind === "IMAGE"), documents = p.files.filter(f => f.kind === "PDF");
  const actions = f => `<div class="form-feedback" aria-live="polite"></div><div class="actions"><button class="secondary" type="submit">Lưu tên</button><button class="text-button delete-file" type="button">Gỡ</button></div>`;
  return `<section class="panel file-manager"><h2>Hình ảnh sản phẩm</h2><p class="muted">Ảnh đầu tiên là ảnh đại diện.</p><div class="file-grid">${images.map((f, i) => `<form class="file-edit" data-file="${e(f.id)}"><img loading="lazy" decoding="async" src="${e(f.url)}" alt="${e(f.display_name)}" width="180" height="180">${i === 0 ? '<span class="badge">Ảnh đại diện</span>' : ""}${input("display_name", f.display_name)}<small>${size(f.size_bytes)}</small><div class="file-order"><button type="button" class="secondary move-image" data-step="-1" ${i === 0 ? "disabled" : ""} aria-label="Đưa ảnh ${e(f.display_name)} lên trước">Lên trước</button><button type="button" class="secondary move-image" data-step="1" ${i === images.length - 1 ? "disabled" : ""}>Xuống sau</button>${i > 0 ? '<button type="button" class="text-button make-primary">Làm đại diện</button>' : ""}</div>${actions(f)}</form>`).join("") || '<p class="muted">Chưa có hình ảnh.</p>'}</div>
  <h2>Tài liệu sản phẩm</h2><div class="document-list">${documents.map(f => `<form class="document-row file-edit" data-file="${e(f.id)}"><div class="document-heading"><span class="document-icon" aria-hidden="true">PDF</span><div><strong>${e(f.display_name)}</strong><small>${size(f.size_bytes)}</small></div><div class="actions"><button type="button" class="secondary preview-pdf">Xem PDF</button><button type="button" class="secondary download-pdf">Tải xuống</button></div></div><details><summary>Đổi tên hoặc gỡ tài liệu</summary>${input("display_name", f.display_name)}${actions(f)}</details><div class="document-feedback" role="status"></div></form>`).join("") || '<p class="muted">Chưa có tài liệu PDF.</p>'}</div>
  <details class="file-upload-panel"><summary>Thêm hình ảnh hoặc tài liệu</summary><form id="file-upload" class="form-grid"><label>Loại tệp<select name="kind"><option value="IMAGE">Hình ảnh PNG / JPEG</option><option value="PDF">Tài liệu PDF</option></select></label><label><span id="file-label">Chọn ảnh</span><input name="file" type="file" accept="image/png,image/jpeg" required aria-describedby="file-policy"></label><p id="file-policy" class="field-hint">Đang đọc giới hạn tải lên…</p><p id="selected-file" class="field-hint" role="status"></p><img id="upload-preview" alt="Ảnh đã chọn" hidden><details><summary>Tên tệp lưu trên hệ thống</summary><label>Tên tệp<input name="name" required maxlength="96" pattern="[A-Za-z0-9][A-Za-z0-9 _.\\-]{0,95}" placeholder="Chọn tệp để điền tên"></label><p class="field-hint">Chỉ dùng chữ không dấu, số, khoảng trắng, dấu chấm, gạch nối và gạch dưới. Tên hiển thị có thể đổi sau khi tải lên.</p></details><div class="form-feedback" aria-live="polite"></div><button type="submit" disabled>Tải lên</button></form></details></section>`;
}
export function bindFiles(root, p, ctx) {
  const {go, toast, confirm} = ctx, form = root.querySelector("#file-upload"), images = p.files.filter(f => f.kind === "IMAGE");
  let policy, previewUrl, disposed = false;
  const refresh = () => go(location.pathname + location.search, true);
  const clearPreview = () => { if (previewUrl) URL.revokeObjectURL(previewUrl); previewUrl = null; root.querySelector("#upload-preview").hidden = true; };
  const update = () => {
    clearPreview(); feedback(form, "");
    const pdf = form.elements.kind.value === "PDF", file = form.elements.file.files[0], preview = root.querySelector("#upload-preview");
    form.elements.file.accept = pdf ? "application/pdf" : "image/png,image/jpeg";
    root.querySelector("#file-label").textContent = pdf ? "Chọn PDF" : "Chọn ảnh";
    root.querySelector("#file-policy").textContent = policy ? pdf ? `PDF tối đa ${size(policy.max_pdf_bytes)}.` : `PNG/JPEG tối đa ${size(policy.max_image_bytes)}; ${policy.image_min_dimension}–${policy.image_max_dimension} px mỗi chiều, tối đa ${policy.image_max_pixels.toLocaleString("vi-VN")} điểm ảnh.` : "Không đọc được giới hạn tải lên. Hãy tải lại trang.";
    root.querySelector("#selected-file").textContent = file ? `${file.name} · ${size(file.size)}` : "Chưa chọn tệp.";
    form.elements.name.value = file ? safeName(file.name, pdf ? "PDF" : "IMAGE") : "";
    if (file && !pdf && ["image/png", "image/jpeg"].includes(file.type)) { previewUrl = URL.createObjectURL(file); preview.src = previewUrl; preview.hidden = false; }
  };
  const retryPolicy=document.createElement("button");retryPolicy.type="button";retryPolicy.className="secondary policy-retry";retryPolicy.textContent="Đọc lại giới hạn tải lên";retryPolicy.hidden=true;form.append(retryPolicy);
  async function loadPolicy(fresh=false) {
    retryPolicy.disabled=true;
    try{const value=await uploadPolicy(fresh);if(disposed)return;policy=value;form.querySelector("[type=submit]").disabled=false;retryPolicy.hidden=true;update();}
    catch(err){if(!disposed){feedback(form,err.message);retryPolicy.hidden=false;}}
    finally{retryPolicy.disabled=false;}
  }
  retryPolicy.onclick=()=>loadPolicy(true);loadPolicy();
  form.elements.kind.onchange = () => { form.elements.file.value = ""; update(); };
  form.elements.file.onchange = update;
  form.onsubmit = async event => {
    event.preventDefault(); const file = form.elements.file.files[0], kind = form.elements.kind.value, button = form.querySelector("[type=submit]");
    if (!file || !policy) return;
    if (!(kind === "PDF" ? ["application/pdf"] : ["image/png", "image/jpeg"]).includes(file.type)) { feedback(form, kind === "PDF" ? "Vui lòng chọn PDF." : "Vui lòng chọn ảnh PNG hoặc JPEG."); return; }
    const limit = kind === "PDF" ? policy.max_pdf_bytes : policy.max_image_bytes;
    if (file.size < 1 || file.size > limit) { feedback(form, `Tệp phải từ 1 byte đến ${size(limit)}.`); return; }
    const name = form.elements.name.value;
    if (!/^[A-Za-z0-9][A-Za-z0-9 _.-]{0,95}$/.test(name) || name.includes("..")) { feedback(form, `Tên tệp không hợp lệ. Gợi ý: ${safeName(file.name, kind)}.`); return; }
    button.disabled = true;
    try { await api(`/admin/products/${p.id}/files?kind=${kind}`, {method:"POST", body:file, headers:{"Content-Type":file.type,"X-File-Name":name}}); toast("Đã thêm tệp."); refresh(); }
    catch(err) { feedback(form, err.message); } finally { button.disabled = false; }
  };
  root.querySelectorAll(".file-edit").forEach(f => {
    const file = p.files.find(x => x.id === f.dataset.file);
    f.onsubmit = async event => { event.preventDefault(); const button = f.querySelector("[type=submit]"); button.disabled = true;
      try {await api("/admin/files/" + file.id, {method:"PATCH", body:{display_name:f.elements.display_name.value, position:file.position}});toast("Đã lưu tên.");refresh();}
      catch(err){feedback(f,err.message);}finally{button.disabled=false;}
    };
    f.querySelector(".delete-file").onclick = async () => { if(!await confirm("Gỡ tệp khỏi sản phẩm? Tệp gốc và lịch sử vẫn được giữ."))return;
      try{await api("/admin/files/"+file.id,{method:"DELETE"});toast("Đã gỡ tệp.");refresh();}catch(err){feedback(f,err.message);}
    };
    const move = async target => {const ordered = images.map(x => x.id), index = ordered.indexOf(file.id); ordered.splice(index,1);ordered.splice(target,0,file.id);
      const controls=[...root.querySelectorAll(".move-image,.make-primary")],disabled=controls.map(b=>b.disabled);controls.forEach(b=>b.disabled=true);
      try{await api(`/admin/products/${p.id}/files/order`,{method:"POST",body:{ids:ordered,version:p.version}});toast("Đã sắp xếp ảnh.");refresh();}catch(err){feedback(f,err.message);controls.forEach((b,i)=>b.disabled=disabled[i]);}
    };
    f.querySelectorAll(".move-image").forEach(b=>b.onclick=()=>move(images.findIndex(x=>x.id===file.id)+Number(b.dataset.step)));
    const primary=f.querySelector(".make-primary");if(primary)primary.onclick=()=>move(0);
    for(const [selector,preview] of [[".preview-pdf",true],[".download-pdf",false]]) {const button=f.querySelector(selector);if(button)button.onclick=async()=>{button.disabled=true;const note=f.querySelector(".document-feedback");note.textContent="Đang kiểm tra tài liệu…";try{await download(file,{preview});note.textContent=preview?"Đã mở tài liệu.":"Đã tải tài liệu.";}catch(err){note.textContent=err.message;}finally{button.disabled=false;}};}
  });
  return () => {disposed=true;clearPreview();};
}
