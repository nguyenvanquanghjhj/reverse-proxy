import { api, session, e, money, status } from "./api.js";
import { empty, errorBox, orderTable, orderMarkup } from "./views.js";
import { operationsView } from "./operations.js";
import { fileManager, bindFiles } from "./files.js";

const text = (name, label, value = "", attrs = "") =>
  `<label>${label}<input name="${name}" value="${e(value)}" ${attrs}></label>`;
const feedback = (form, message) =>
  (form.querySelector(".form-feedback").innerHTML = message
    ? errorBox(message)
    : "");
const values = (form) => Object.fromEntries(new FormData(form));
function variant(v = {}) {
  return `<fieldset class="variant-editor" data-id="${v.id || ""}"><legend>Phiên bản ${v.id ? "#" + v.id : "mới"}</legend>${text("sku", "SKU", v.sku, 'required maxlength="64" pattern="[A-Za-z0-9_-]+"')}${text("color", "Màu sắc", v.color, 'required maxlength="40"')}${text("storage_gb", "Dung lượng (GB)", v.storage_gb || 128, 'required type="number" min="16" max="4096"')}${text("price_vnd", "Giá bán (₫)", v.price_vnd || 4990000, 'required type="number" min="1000" max="1000000000"')}${text("stock", "Tồn kho", v.stock ?? 0, 'required type="number" min="0" max="1000000"')}<label class="check"><input name="variant_active" type="checkbox" ${v.active !== false ? "checked" : ""}> Đang bán</label></fieldset>`;
}
export async function adminView(ctx) {
  const { path, url, go, toast, confirm } = ctx;
  const s = await session(true);
  if (s.user?.role !== "ADMIN")
    return {
      html: empty(
        "Bạn cần quyền quản trị",
        "Đăng nhập bằng tài khoản quản trị để tiếp tục.",
        "/admin/shop/login?next=admin",
        "Đăng nhập",
      ),
    };
  let content;
  if (path === "/admin" || path === "/admin/") {
    const d = await api("/admin/dashboard"),
      o = await api("/admin/orders?status=CREATED");
    content = {
      html: `<div class="page-heading"><p class="eyebrow">QUẢN TRỊ CỬA HÀNG</p><h1>Tổng quan cửa hàng</h1></div><div class="admin-kpis">${[
        ["Đơn chờ xác nhận", d.new_orders],
        ["Đơn đang xử lý", d.processing_orders],
        ["Sản phẩm đang bán", d.active_products],
        ["Đã giao", d.delivered_orders],
        ["Đã hủy", d.cancelled_orders],
        ["Tiền COD đã ghi nhận", money(d.collected_vnd)],
        ["Giá trị đơn chưa hủy", money(d.ordered_vnd)],
        ["Giá trị đơn đã giao", money(d.delivered_vnd)],
        ["Phiên bản hết hàng", d.out_of_stock],
      ]
        .map(
          ([k, v]) =>
            `<div class="panel"><p>${e(k)}</p><strong>${e(v)}</strong></div>`,
        )
        .join(
          "",
        )}</div><section class="panel"><div class="section-heading"><h2>Đơn cần xử lý</h2><a class="text-link" href="/admin/orders">Xem tất cả</a></div>${o.items.length ? orderTable(o.items, true) : '<p class="muted">Không có đơn đang chờ xác nhận.</p>'}</section>`,
    };
  } else if (path === "/admin/operations") {
    content = await operationsView(ctx);
  } else if (path === "/admin/products") {
    const q = new URLSearchParams(url.search),
      data = await api("/admin/products?" + q);
    content = {
      html: `<div class="page-heading split"><div><p class="eyebrow">DANH MỤC HÀNG HÓA</p><h1>Sản phẩm</h1><p>${data.total} sản phẩm trong cửa hàng.</p></div><a class="button" href="/admin/products/new">+ Thêm sản phẩm</a></div><form id="admin-search" class="inline-form"><label>Tìm sản phẩm<input name="q" value="${e(q.get("q"))}" maxlength="100" placeholder="Tên hoặc thương hiệu"></label><button>Tìm kiếm</button></form><section class="panel"><div class="table-wrap"><table><thead><tr><th>Sản phẩm</th><th>Giá từ</th><th>Tồn kho</th><th>Trạng thái</th><th></th></tr></thead><tbody>${data.items.map((p) => `<tr><td><div class="admin-product-cell">${p.image_url ? `<img loading="lazy" decoding="async" src="${e(p.image_url)}" alt="" width="52" height="64">` : ""}<div><strong>${e(p.name)}</strong><small>${e(p.brand)} · ${e(p.category)}</small><small>${p.variant_count} phiên bản · ${e(p.skus)}</small></div></div></td><td>${money(p.price_vnd)}</td><td>${p.stock}</td><td><span class="badge">${p.active ? "Đang bán" : "Chưa bán / Ngừng bán"}</span></td><td><a class="text-link" href="/admin/products/${p.id}">Chỉnh sửa</a></td></tr>`).join("")}</tbody></table></div>${!data.items.length ? "<p>Chưa có sản phẩm phù hợp.</p>" : ""}<nav class="pagination">${data.page > 1 ? `<a href="?${e(new URLSearchParams({ ...Object.fromEntries(q), page: data.page - 1 }))}">← Trước</a>` : ""}<span>Trang ${data.page}</span>${data.page * data.limit < data.total ? `<a href="?${e(new URLSearchParams({ ...Object.fromEntries(q), page: data.page + 1 }))}">Tiếp →</a>` : ""}</nav></section>`,
      bind(root) {
        root.querySelector("#admin-search").onsubmit = (event) => {
          event.preventDefault();
          go(
            "/admin/products?" +
              new URLSearchParams(new FormData(event.target)),
          );
        };
      },
    };
  } else if (/^\/admin\/products\/(new|\d+)$/.test(path)) {
    const id = path.split("/").pop(),
      fresh = id === "new";
    const p = fresh
        ? { variants: [{}], files: [], version: 1 }
        : await api("/admin/products/" + id),
      cats = await api("/admin/categories");
    content = {
      html: `<div class="page-heading"><p class="eyebrow">SẢN PHẨM</p><h1>${fresh ? "Thêm điện thoại" : e(p.name)}</h1><p>Lưu bản nháp, thêm hình ảnh rồi bật trạng thái đang bán.</p></div><form id="product-editor" class="panel"><div class="form-grid">${text("name", "Tên sản phẩm", p.name, 'required minlength="2" maxlength="160"')}${text("brand", "Thương hiệu", p.brand, 'required maxlength="64"')}<label>Danh mục<select name="category_id" required>${cats.items.map((c) => `<option value="${c.id}" ${c.id === p.category_id ? "selected" : ""}>${e(c.name)}${c.active ? "" : " (Đã tắt)"}</option>`).join("")}</select></label>${text("screen", "Màn hình", p.screen, 'required maxlength="120"')}${text("chipset", "Bộ xử lý", p.chipset, 'required maxlength="120"')}${text("ram_gb", "RAM (GB)", p.ram_gb || 8, 'required type="number" min="1" max="64"')}${text("battery_mah", "Pin (mAh)", p.battery_mah || 5000, 'required type="number" min="500" max="30000"')}</div><label>Mô tả<textarea name="description" rows="4" required minlength="30" maxlength="4000">${e(p.description)}</textarea></label><div class="actions"><label class="check"><input type="checkbox" name="active" ${p.active ? "checked" : ""} ${fresh ? "disabled" : ""}> Đang bán</label><label class="check"><input type="checkbox" name="featured" ${p.featured ? "checked" : ""}> Sản phẩm nổi bật</label></div><h2>Phiên bản, giá và tồn kho</h2><p class="muted">Bỏ chọn “Đang bán” để ngừng bán phiên bản; lịch sử đơn hàng vẫn được giữ.</p><div id="variants">${p.variants.map(variant).join("")}</div><button type="button" class="secondary" id="add-variant">+ Thêm phiên bản</button><div class="form-feedback" aria-live="polite"></div><div class="editor-actions"><button type="submit" data-label="Lưu sản phẩm">Lưu sản phẩm</button><a class="text-link" href="/admin/products">Về danh sách</a>${!fresh && p.active ? `<a class="text-link" href="/admin/shop/products/${p.id}">Xem trên cửa hàng</a>` : ""}</div></form>${!fresh ? fileManager(p) : '<p class="notice">Sau khi lưu, bạn có thể thêm hình ảnh và tài liệu.</p>'}`,
      bind(root) {
        const form = root.querySelector("#product-editor");
        root.querySelector("#add-variant").onclick = () => {
          if (form.querySelectorAll(".variant-editor").length >= 8) {
            toast("Tối đa 8 phiên bản.", true);
            return;
          }
          root
            .querySelector("#variants")
            .insertAdjacentHTML("beforeend", variant());
        };
        form.onsubmit = async (event) => {
          event.preventDefault();
          const submit = form.querySelector("[type=submit]");
          submit.disabled = true;
          try {
            const b = values(form);
            for (const k of ["category_id", "ram_gb", "battery_mah"])
              b[k] = Number(b[k]);
            b.active = form.elements.active.checked;
            b.featured = form.elements.featured.checked;
            b.version = p.version;
            delete b.sku;
            delete b.color;
            delete b.storage_gb;
            delete b.price_vnd;
            delete b.stock;
            delete b.variant_active;
            b.variants = [...form.querySelectorAll(".variant-editor")].map(
              (row) => {
                const get = (n) => row.querySelector(`[name="${n}"]`);
                return {
                  id: row.dataset.id ? Number(row.dataset.id) : null,
                  sku: get("sku").value,
                  color: get("color").value,
                  storage_gb: Number(get("storage_gb").value),
                  price_vnd: Number(get("price_vnd").value),
                  stock: Number(get("stock").value),
                  active: get("variant_active").checked,
                };
              },
            );
            const saved = await api(
              "/admin/products" + (fresh ? "" : "/" + id),
              { method: fresh ? "POST" : "PUT", body: b },
            );
            toast("Đã lưu sản phẩm.");
            go("/admin/products/" + saved.id, true);
          } catch (err) {
            feedback(form, err.message);
          } finally {
            submit.disabled = false;
          }
        };
        if (!fresh) return bindFiles(root, p, ctx);
      },
    };
  } else if (path === "/admin/categories") {
    const data = await api("/admin/categories");
    content = {
      html: `<div class="page-heading"><p class="eyebrow">QUẢN LÝ DANH MỤC</p><h1>Danh mục</h1></div><div class="category-admin"><form id="new-category" class="panel"><h2>Thêm danh mục</h2>${text("name", "Tên danh mục", "", 'required maxlength="80"')}${text("slug", "Đường dẫn", "", 'required maxlength="64" pattern="[a-z0-9]+(-[a-z0-9]+)*"')}<label class="check"><input type="checkbox" name="active" checked> Hiển thị</label><div class="form-feedback"></div><button type="submit">Tạo danh mục</button></form><div>${data.items.map((c) => `<form class="panel category-edit" data-id="${c.id}">${text("name", "Tên danh mục", c.name, 'required maxlength="80"')}${text("slug", "Đường dẫn", c.slug, 'required maxlength="64" pattern="[a-z0-9]+(-[a-z0-9]+)*"')}<label class="check"><input type="checkbox" name="active" ${c.active ? "checked" : ""}> Hiển thị</label><div class="form-feedback"></div><button class="secondary">Lưu thay đổi</button></form>`).join("")}</div></div>`,
      bind(root) {
        root.querySelectorAll("form").forEach(
          (form) =>
            (form.onsubmit = async (event) => {
              event.preventDefault();
              const b = values(form);
              b.active = form.elements.active.checked;
              const button = form.querySelector("button");
              button.disabled = true;
              try {
                await api(
                  "/admin/categories" +
                    (form.dataset.id ? "/" + form.dataset.id : ""),
                  { method: form.dataset.id ? "PUT" : "POST", body: b },
                );
                toast("Đã lưu danh mục.");
                go(path, true);
              } catch (err) {
                feedback(form, err.message);
              } finally {
                button.disabled = false;
              }
            }),
        );
      },
    };
  } else if (path === "/admin/documents") {
    const q = url.searchParams.get("q") || "",
      page = Number(url.searchParams.get("page") || 1),
      data = await api(
        "/admin/products?limit=8&page=" + page + "&q=" + encodeURIComponent(q),
      );
    const id = url.searchParams.get("product") || data.items[0]?.id,
      p = id ? await api("/admin/products/" + encodeURIComponent(id)) : null;
    const link = (extra) =>
      "/admin/documents?" + new URLSearchParams({ q, page, ...extra });
    content = {
      html: `<div class="page-heading"><p class="eyebrow">THƯ VIỆN SẢN PHẨM</p><h1>Hình ảnh & tài liệu</h1></div><div class="media-workspace"><aside class="media-sidebar"><form id="file-search"><label>Tìm điện thoại<input name="q" value="${e(q)}" maxlength="100" placeholder="Tên hoặc thương hiệu"></label><button class="secondary full">Tìm kiếm</button></form><p class="media-count">${data.total} sản phẩm</p><nav class="media-products" aria-label="Sản phẩm trong thư viện">${data.items.map((x) => `<a href="${e(link({ product: x.id }))}" ${String(id) === String(x.id) ? 'aria-current="page"' : ""}>${x.image_url ? `<img loading="lazy" decoding="async" src="${e(x.image_url)}" alt="" width="54" height="68">` : '<span class="media-placeholder">—</span>'}<span><strong>${e(x.name)}</strong><small>#${x.id} · ${x.image_count} ảnh · ${x.pdf_count} PDF</small></span></a>`).join("") || "<p>Không có sản phẩm phù hợp.</p>"}</nav><nav class="pagination" aria-label="Trang thư viện">${page > 1 ? `<a href="${e(link({ page: page - 1 }))}">← Trước</a>` : ""}<span>${page} / ${Math.max(1, Math.ceil(data.total / 8))}</span>${page * 8 < data.total ? `<a href="${e(link({ page: page + 1 }))}">Tiếp →</a>` : ""}</nav></aside><div class="media-detail">${p ? `<div class="media-title"><div><p class="eyebrow">ĐIỆN THOẠI #${p.id}</p><h2>${e(p.name)}</h2></div><a class="text-link" href="/admin/products/${p.id}">Sửa sản phẩm</a></div>${fileManager(p)}` : `<div class="empty"><h2>${q ? 'Không tìm thấy sản phẩm' : 'Chưa có sản phẩm'}</h2><p>${q ? 'Thử từ khóa khác.' : 'Thêm sản phẩm trước khi tải hình ảnh hoặc tài liệu.'}</p></div>`}</div></div>`,
      bind(root) {
        root.querySelector("#file-search").onsubmit = (event) => {
          event.preventDefault();
          go(
            "/admin/documents?" +
              new URLSearchParams(new FormData(event.target)),
          );
        };
        if (p) return bindFiles(root, p, ctx);
      },
    };
  } else if (path === "/admin/orders") {
    const data = await api("/admin/orders?" + url.searchParams);
    content = {
      html: `<div class="page-heading"><p class="eyebrow">VẬN HÀNH CỬA HÀNG</p><h1>Đơn hàng</h1></div><form class="filter-panel" id="orders-filter">${text("id", "Mã đơn", url.searchParams.get("id"), 'maxlength="36" placeholder="Mã đầy đủ"')}<label>Trạng thái<select name="status"><option value="">Tất cả</option>${["CREATED", "CONFIRMED", "PACKING", "SHIPPED", "DELIVERED", "CANCELLED"].map((s) => `<option value="${s}" ${url.searchParams.get("status") === s ? "selected" : ""}>${e(status(s))}</option>`).join("")}</select></label><button>Tìm đơn</button></form><section class="panel">${data.items.length ? orderTable(data.items, true) : "<p>Chưa có đơn phù hợp.</p>"}<nav class="pagination">${Number(url.searchParams.get("page") || 1) > 1 ? `<a href="?${e(new URLSearchParams({ ...Object.fromEntries(url.searchParams), page: Number(url.searchParams.get("page")) - 1 }))}">← Trước</a>` : ""}${data.items.length === 20 ? `<a href="?${e(new URLSearchParams({ ...Object.fromEntries(url.searchParams), page: Number(url.searchParams.get("page") || 1) + 1 }))}">Tiếp →</a>` : ""}</nav></section>`,
      bind(root) {
        root.querySelector("#orders-filter").onsubmit = (event) => {
          event.preventDefault();
          const q = new URLSearchParams(new FormData(event.target));
          for (const [k, v] of [...q]) if (!v) q.delete(k);
          go("/admin/orders?" + q);
        };
      },
    };
  } else if (/^\/admin\/orders\/[0-9a-f-]+$/.test(path)) {
    const id = path.split("/").pop(),
      o = await api("/admin/orders/" + id);
    const next =
      {
        CREATED: ["CONFIRMED", "CANCELLED"],
        CONFIRMED: ["PACKING", "CANCELLED"],
        PACKING: ["SHIPPED", "CANCELLED"],
        SHIPPED: ["DELIVERED"],
        DELIVERED: [],
        CANCELLED: [],
      }[o.status] || [];
    content = {
      html: `<div class="page-heading"><p class="eyebrow">CHI TIẾT ĐƠN HÀNG</p><h1>${e(status(o.status))}</h1><p class="break">${e(id)}</p></div>${orderMarkup(o)}<form class="panel order-update" id="order-update"><h2>Cập nhật đơn hàng</h2><label>Trạng thái<select name="status">${[o.status, ...next].map((s) => `<option value="${s}">${e(status(s))}</option>`).join("")}</select></label><label class="check"><input type="checkbox" name="confirm_payment" ${o.payment_status === "PAID" ? "checked disabled" : ""}> Xác nhận đã thu tiền COD (chỉ khi đã giao)</label><p class="muted">Hủy trước khi giao sẽ hoàn tồn kho một lần. Đơn đã giao hoặc đã hủy không thể quay lại trạng thái cũ.</p><div class="form-feedback"></div><button type="submit">Lưu trạng thái</button></form>`,
      bind(root) {
        const form = root.querySelector("#order-update");
        form.onsubmit = async (event) => {
          event.preventDefault();
          const b = {
            status: form.elements.status.value,
            confirm_payment: form.elements.confirm_payment.checked,
          };
          if (
            b.status === "CANCELLED" &&
            !(await confirm("Hủy đơn này và hoàn lại tồn kho?"))
          )
            return;
          const button = form.querySelector("button");
          button.disabled = true;
          try {
            await api("/admin/orders/" + id, { method: "PATCH", body: b });
            toast("Đã cập nhật đơn hàng.");
            go(path, true);
          } catch (err) {
            feedback(form, err.message);
          } finally {
            button.disabled = false;
          }
        };
      },
    };
  } else
    content = {
      html: empty(
        "Không tìm thấy trang quản trị",
        "Chọn mục cần quản lý trong thanh điều hướng.",
        "/admin",
        "Về tổng quan",
      ),
    };
  return {
    html: `<div class="admin-toolbar"><button type="button" id="admin-menu-toggle" class="secondary" aria-controls="admin-navigation" aria-expanded="true">Thu gọn menu</button></div><div class="admin-layout"><aside class="admin-nav" id="admin-navigation"><p class="eyebrow">MỘC / QUẢN TRỊ</p>${[
      ["/admin", "Tổng quan"],
      ["/admin/products", "Sản phẩm"],
      ["/admin/categories", "Danh mục"],
      ["/admin/orders", "Đơn hàng"],
      ["/admin/documents", "Hình ảnh & tài liệu"],
      ["/admin/operations", "Trạng thái dịch vụ"],
    ]
      .map(
        ([href, label]) =>
          `<a href="${href}" ${(href === "/admin" ? path === href || path === "/admin/" : path === href || path.startsWith(href + "/")) ? 'aria-current="page"' : ""}>${label}</a>`,
      )
      .join(
        "",
      )}<a href="/admin/shop">Cửa hàng</a></aside><div class="admin-content">${content.html}</div></div>`,
    bind(root) {
      const unbindFiles = content.bind?.(root);
      const unbindNavigation = bindNavigation(root);
      return () => { unbindFiles?.(); unbindNavigation(); };
    },
  };
}

function bindNavigation(root) {
  const layout = root.querySelector(".admin-layout"), sidebar = root.querySelector(".admin-nav"), toggle = root.querySelector("#admin-menu-toggle");
  const drawer = document.createElement("dialog"); drawer.className = "admin-menu-dialog"; drawer.setAttribute("aria-label", "Điều hướng quản trị");
  const close = document.createElement("button");close.type="button";close.className="secondary";close.textContent="Đóng menu";drawer.append(close);root.append(drawer);
  const mobile = matchMedia("(max-width: 800px)"), controller = new AbortController();
  let collapsed = false;try {collapsed = sessionStorage.getItem("moc-admin-menu") === "collapsed";}catch{}
  function apply() {
    if(drawer.open)drawer.close();layout.prepend(sidebar);
    sidebar.hidden = mobile.matches || collapsed;
    layout.dataset.collapsed = String(sidebar.hidden);
    toggle.textContent = mobile.matches ? "Mở menu" : collapsed ? "Mở rộng menu" : "Thu gọn menu";
    toggle.setAttribute("aria-expanded",String(!sidebar.hidden));
  }
  toggle.onclick = () => {
    if(mobile.matches) {sidebar.hidden=false;drawer.append(sidebar);drawer.showModal();toggle.setAttribute("aria-expanded","true");close.focus();}
    else {collapsed=!collapsed;try{sessionStorage.setItem("moc-admin-menu",collapsed?"collapsed":"expanded");}catch{}apply();}
  };
  close.onclick=()=>drawer.close();
  drawer.addEventListener("close",()=>{layout.prepend(sidebar);sidebar.hidden=mobile.matches||collapsed;toggle.setAttribute("aria-expanded",String(!sidebar.hidden));toggle.focus();});
  drawer.addEventListener("click",event=>{if(event.target===drawer)drawer.close();});
  drawer.addEventListener("keydown",event=>{
    if(event.key!=="Tab")return;
    const items=[close,...sidebar.querySelectorAll("a[href],button:not([disabled])")],first=items[0],last=items.at(-1);
    if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}
    else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}
  });
  mobile.addEventListener("change",apply,{signal:controller.signal});apply();
  return () => {controller.abort();if(drawer.open)drawer.close();drawer.remove();};
}
