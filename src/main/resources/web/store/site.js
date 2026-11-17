import {
  api,
  session,
  setIdentity,
  download,
  e,
  money,
  date,
  status,
} from "./api.js";
import * as checkout from "./checkout.js";

const BASE = "/admin/shop";
const PAYMENT = {
  UNPAID: "Chưa thanh toán (trả khi nhận hàng)",
  PAID: "Đã thanh toán",
};
const PAGE_SIZE = 24,
  ORDER_PAGE_SIZE = 20;
const $ = (root, selector) => root.querySelector(selector);
const storage = (gb) => (gb >= 1024 ? gb / 1024 + " TB" : gb + " GB");
const kib = (bytes) => Math.max(1, Math.round(bytes / 1024)) + " KB";
const length = (text) => [...text].length;
const link = (url, label) =>
  typeof url === "string" && url.startsWith("https://")
    ? `<a class="text-link" href="${e(url)}" target="_blank" rel="noopener noreferrer">${e(label)}</a>`
    : e(label);
const notice = (message, title = "", kind = "error") =>
  `<div class="notice ${kind}">${title ? `<strong>${e(title)}</strong> ` : ""}${e(message)}</div>`;

function field(label, name, type, attrs = "", hint = "", optional = false) {
  return `<div class="shop-field"><label><span>${label}${optional ? ' <span class="muted">(không bắt buộc)</span>' : ""}</span><input id="f-${name}" name="${name}" type="${type}" ${attrs}></label>${hint ? `<p class="field-hint">${hint}</p>` : ""}<p class="shop-error" id="e-${name}" hidden></p></div>`;
}
function area(label, name, attrs = "", optional = false) {
  return `<div class="shop-field"><label><span>${label}${optional ? ' <span class="muted">(không bắt buộc)</span>' : ""}</span><textarea id="f-${name}" name="${name}" ${attrs}></textarea></label><p class="shop-error" id="e-${name}" hidden></p></div>`;
}
function setError(root, name, message) {
  const input = $(root, `[name="${name}"]`),
    text = $(root, `#e-${name}`);
  if (!input || !text) return;
  text.hidden = !message;
  text.textContent = message || "";
  if (message) {
    input.setAttribute("aria-invalid", "true");
    input.setAttribute("aria-describedby", text.id);
  } else {
    input.removeAttribute("aria-invalid");
    input.removeAttribute("aria-describedby");
  }
}

// ---- Khung trang (cùng header và thanh bên với khu quản trị) ----
function topbar(user) {
  return `<header class="admin-top"><a class="wordmark" href="${BASE}">mộc<span aria-hidden="true">•</span></a><span class="admin-top-label">CỬA HÀNG</span><nav aria-label="Tài khoản">${user?.role === "ADMIN" ? '<a href="/admin">Quản trị ↗</a>' : ""}${user ? `<span>${e(user.name)}</span><button type="button" id="logout" class="secondary">Đăng xuất</button>` : ""}</nav></header>`;
}
function frame(user, active) {
  if (!user)
    return (
      topbar(null) +
      `<main id="main" tabindex="-1"><div class="shop-center" id="view"><div class="shop-loading" role="status">Đang tải…</div></div></main>`
    );
  const links = [
    [BASE, "Sản phẩm", "list"],
    [BASE + "/orders", "Đơn của tôi", "orders"],
  ];
  if (user.role === "ADMIN") links.push(["/admin", "Quản trị", "admin"]);
  return (
    topbar(user) +
    `<main id="main" tabindex="-1"><div class="admin-layout"><aside class="admin-nav shop-nav"><p class="eyebrow">MỘC / CỬA HÀNG</p>${links.map(([href, label, key]) => `<a href="${href}"${key === active ? ' aria-current="page"' : ""}>${label} ↗</a>`).join("")}</aside><div class="admin-content" id="view"><div class="shop-loading" role="status">Đang tải…</div></div></div></main>`
  );
}
function parse(path) {
  const rest = path.slice(BASE.length).replace(/\/+$/, "");
  let m;
  if (rest === "") return { name: "list" };
  if (rest === "/login") return { name: "login" };
  if (rest === "/register") return { name: "register" };
  if (rest === "/orders") return { name: "orders" };
  if ((m = rest.match(/^\/orders\/([0-9a-fA-F-]{36})$/)))
    return { name: "order", id: m[1].toLowerCase() };
  if ((m = rest.match(/^\/products\/([1-9][0-9]{0,17})$/)))
    return { name: "product", id: m[1] };
  if (rest === "/products") return { name: "list" };
  return { name: "missing" };
}
function afterLogin(url, s) {
  const next = url.searchParams.get("next") || "";
  if (next === "admin") return s?.user?.role === "ADMIN" ? "/admin" : BASE;
  if (next.startsWith(BASE) && !next.startsWith("//")) return next;
  return BASE;
}

export async function render(app, ctx) {
  const { path, url, go } = ctx,
    route = parse(path);
  const s = await session(),
    user = s.user || null;
  const isAuth = route.name === "login" || route.name === "register";
  if (isAuth && user) {
    go(afterLogin(url, s), true);
    return;
  }
  if (!isAuth && !user) {
    go(
      BASE +
        "/login" +
        (route.name === "list" && !url.search
          ? ""
          : "?next=" + encodeURIComponent(path + url.search)),
      true,
    );
    return;
  }
  app.innerHTML = frame(
    user,
    route.name === "orders" || route.name === "order" ? "orders" : "list",
  );
  const view = $(app, "#view"),
    logout = $(app, "#logout");
  if (logout)
    logout.onclick = async () => {
      logout.disabled = true;
      try {
        await api("/auth/logout", { method: "POST" });
        setIdentity(null);
        await session(true);
        go(BASE + "/login");
      } catch (error) {
        ctx.toast(error.message, true);
        logout.disabled = false;
      }
    };
  try {
    const page = await {
      login: () => authPage(ctx, false),
      register: () => authPage(ctx, true),
      list: () => listPage(ctx),
      product: () => productPage(ctx, route.id, user),
      orders: () => ordersPage(ctx),
      order: () => orderPage(ctx, route.id),
      missing: () => missingPage(),
    }[route.name]();
    if (!ctx.current()) return;
    view.innerHTML = page.html;
    page.bind?.(view);
    document.title = page.title + " — Mộc";
    $(app, "#main").focus({ preventScroll: true });
    window.scrollTo(0, 0);
  } catch (error) {
    if (!ctx.current()) return;
    if (error.status === 401) {
      setIdentity(null);
      await session(true).catch(() => {});
      go(BASE + "/login?next=" + encodeURIComponent(path + url.search), true);
      return;
    }
    view.innerHTML =
      error.status === 404
        ? missingPage().html
        : `<div class="empty"><h2>Chưa mở được trang này</h2><p>${e(error.message)}</p><div class="actions"><button type="button" id="retry-page">Thử lại</button><a class="button secondary" href="${BASE}">Về danh sách sản phẩm</a></div></div>`;
    const retry = $(view, "#retry-page");
    if (retry) retry.onclick = () => go(path + url.search, true);
    document.title = "Có lỗi — Mộc";
  }
}
function missingPage() {
  return {
    title: "Không tìm thấy",
    html: `<div class="empty"><h2>Không tìm thấy trang</h2><p>Địa chỉ này không tồn tại hoặc sản phẩm đã ngừng bán.</p><a class="button" href="${BASE}">Về danh sách sản phẩm</a></div>`,
  };
}

// ---- Đăng nhập và đăng ký ----
function authPage(ctx, register) {
  const next = ctx.url.searchParams.get("next"),
    qs = next ? "?next=" + encodeURIComponent(next) : "";
  const title = register ? "Tạo tài khoản" : "Đăng nhập";
  return {
    title,
    html: `<section class="panel shop-auth"><p class="eyebrow">TÀI KHOẢN</p><h1>${title}</h1>
    <div class="shop-tabs"><a href="${BASE}/login${qs}"${register ? "" : ' aria-current="page"'}>Đăng nhập</a><a href="${BASE}/register${qs}"${register ? ' aria-current="page"' : ""}>Tạo tài khoản</a></div>
    <form id="auth-form" novalidate>
      ${register ? field("Họ tên", "name", "text", 'autocomplete="name" maxlength="80"') : ""}
      ${field("Email", "email", "email", 'autocomplete="email" maxlength="254"')}
      ${field("Mật khẩu", "password", "password", `autocomplete="${register ? "new-password" : "current-password"}" maxlength="128"`, register ? "Từ 12 đến 128 ký tự." : "")}
      <div id="form-error" role="alert"></div>
      <button class="full" type="submit">${title}</button>
    </form></section>`,
    bind(root) {
      const form = $(root, "#auth-form"),
        button = $(form, "button[type=submit]"),
        box = $(root, "#form-error");
      form.onsubmit = async (event) => {
        event.preventDefault();
        if (button.disabled) return;
        const data = Object.fromEntries(new FormData(form));
        box.innerHTML = "";
        const problems = {};
        if (register && length((data.name || "").trim()) < 1)
          problems.name = "Nhập họ tên của bạn.";
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test((data.email || "").trim()))
          problems.email = "Nhập email đúng dạng ten@vidu.com.";
        if (
          length(data.password || "") < 12 ||
          length(data.password || "") > 128
        )
          problems.password = "Mật khẩu cần từ 12 đến 128 ký tự.";
        for (const name of ["name", "email", "password"])
          if (form.elements[name]) setError(form, name, problems[name]);
        const first = Object.keys(problems)[0];
        if (first) {
          form.elements[first].focus();
          return;
        }
        const body = { email: data.email.trim(), password: data.password };
        if (register) body.name = data.name.trim();
        button.disabled = true;
        button.textContent = "Đang xử lý…";
        try {
          const result = await api(
            "/auth/" + (register ? "register" : "login"),
            { method: "POST", body },
          );
          setIdentity(result);
          ctx.go(afterLogin(ctx.url, result));
        } catch (error) {
          box.innerHTML = notice(error.message);
          button.disabled = false;
          button.textContent = title;
        }
      };
    },
  };
}

// ---- Danh sách sản phẩm và tìm kiếm ----
function listPage(ctx) {
  const initial = (ctx.url.searchParams.get("q") || "").slice(0, 100);
  return {
    title: "Sản phẩm",
    html: `<div class="page-heading"><p class="eyebrow">CỬA HÀNG</p><h1>Sản phẩm</h1><p>Chọn một sản phẩm để xem thông số, tải tài liệu và đặt hàng.</p></div>
    <form class="inline-form" id="search-form" role="search"><label>Tìm theo tên sản phẩm<input id="search" type="search" name="q" value="${e(initial)}" placeholder="Ví dụ: Pixel" maxlength="100" autocomplete="off"></label></form>
    <p class="shop-status" id="status" role="status" aria-live="polite"></p><div id="results"></div>`,
    bind(root) {
      const input = $(root, "#search"),
        statusLine = $(root, "#status"),
        results = $(root, "#results");
      let term = initial.trim(),
        sequence = 0,
        timer;
      const urlFor = () =>
        term ? BASE + "?q=" + encodeURIComponent(term) : BASE;
      async function load(page = 1) {
        const mine = ++sequence;
        statusLine.textContent = "Đang tải…";
        const params = new URLSearchParams({
          limit: String(PAGE_SIZE),
          page: String(page),
          sort: "name",
        });
        if (term) params.set("q", term);
        try {
          const data = await api("/products?" + params);
          if (mine !== sequence || !ctx.current()) return;
          show(data, page);
        } catch (error) {
          if (mine !== sequence || !ctx.current()) return;
          statusLine.textContent = "";
          results.innerHTML = `<div class="notice error"><strong>Chưa tải được sản phẩm.</strong> ${e(error.message)}<div class="shop-notice-row"><button type="button" class="secondary" id="retry">Thử lại</button></div></div>`;
          $(results, "#retry").onclick = () => load(page);
        }
      }
      function show(data, page) {
        const items = data.items || [],
          total = Number(data.total) || 0;
        statusLine.textContent = !items.length
          ? term
            ? `Không có sản phẩm nào tên “${term}”. Thử từ khóa ngắn hơn.`
            : "Chưa có sản phẩm nào."
          : term
            ? `${total} sản phẩm khớp “${term}”`
            : `${total} sản phẩm`;
        const pages = Math.ceil(total / PAGE_SIZE);
        results.innerHTML =
          (items.length
            ? `<div class="panel shop-items">${items.map((p) => `<a class="shop-item" href="${BASE}/products/${p.id}"><span class="shop-thumb">${p.image_url ? `<img src="${e(p.image_url)}" alt="" width="72" height="72" loading="lazy">` : ""}</span><span><span class="shop-item-name">${e(p.name)}</span><span class="shop-item-brand">${e(p.brand)}</span></span><span class="shop-price"><small>Từ</small>${money(p.price_vnd)}${Number(p.stock) > 0 ? "" : "<small>Tạm hết hàng</small>"}</span></a>`).join("")}</div>`
            : "") +
          (pages > 1
            ? `<div class="shop-pager"><button type="button" class="secondary" id="prev"${page <= 1 ? " disabled" : ""}>Trang trước</button><span class="muted">Trang ${page} / ${pages}</span><button type="button" class="secondary" id="next"${page >= pages ? " disabled" : ""}>Trang sau</button></div>`
            : "");
        const prev = $(results, "#prev"),
          next = $(results, "#next");
        if (prev)
          prev.onclick = () => {
            load(page - 1);
            window.scrollTo(0, 0);
          };
        if (next)
          next.onclick = () => {
            load(page + 1);
            window.scrollTo(0, 0);
          };
      }
      input.oninput = () => {
        clearTimeout(timer);
        timer = setTimeout(() => {
          term = input.value.trim();
          history.replaceState({}, "", urlFor());
          load(1);
        }, 250);
      };
      $(root, "#search-form").onsubmit = (event) => {
        event.preventDefault();
        clearTimeout(timer);
        term = input.value.trim();
        history.replaceState({}, "", urlFor());
        load(1);
      };
      load(1);
    },
  };
}

// ---- Chi tiết sản phẩm và đặt hàng ----
async function productPage(ctx, id, user) {
  const [p, availability, policy] = await Promise.all([
    api("/products/" + id),
    api("/availability").catch(() => null),
    api("/policy").catch(() => null),
  ]);
  const variants = (p.variants || []).filter((v) => v.active),
    files = p.files || [];
  const images = files.filter((f) => f.kind === "IMAGE"),
    pdfs = files.filter((f) => f.kind === "PDF");
  const meta = p.source_metadata || null,
    credit = (file) =>
      (meta?.images || []).find((i) => i.sha256 === file.sha256) || null;
  const shipping = Number(policy?.shipping_vnd ?? 30000),
    salesOpen = availability ? availability.sales_enabled !== false : true,
    downloadsOpen = availability
      ? availability.downloads_enabled !== false
      : true;
  const caption = (file) => {
    const c = credit(file);
    return c
      ? `${e(c.caption)}. Ảnh của ${e(c.author)}, ${link(c.license_url, c.license)}.`
      : "";
  };

  let flow = checkout.flow(),
    intent = flow.intent;
  if (intent?.state === "CONFIRMED") {
    try {
      checkout.fresh();
    } catch {
      /* Đơn đã xác nhận vẫn xem được ở Đơn của tôi. */
    }
    intent = null;
  }
  const unknown = intent?.state === "UNKNOWN",
    corrupt = flow.corrupt;
  const guest = checkout.secret();
  const firstOpen = variants.find((v) => Number(v.stock) > 0);
  const specs = [
    ["Màn hình", p.screen],
    ["Chip", p.chipset],
    ["RAM", p.ram_gb + " GB"],
    ["Pin", Number(p.battery_mah).toLocaleString("vi-VN") + " mAh"],
    ["Hãng", p.brand],
  ];

  const banner = corrupt
    ? `<div class="notice warning"><strong>Không đọc được thông tin đơn trước đó.</strong> Hãy xem mục Đơn của tôi trước khi đặt đơn mới.<div class="shop-notice-row"><a class="text-link" href="${BASE}/orders">Xem đơn của tôi</a><button type="button" class="secondary" id="reset-order">Đặt đơn mới</button></div></div>`
    : unknown
      ? `<div class="notice warning"><strong>Đơn trước chưa rõ kết quả.</strong> Kết nối bị gián đoạn khi đặt hàng. Bấm kiểm tra lại để xác nhận. Hệ thống không tạo đơn trùng.<div class="shop-notice-row"><button type="button" id="retry-order">Kiểm tra lại đơn</button><button type="button" class="text-button" id="drop-order">Bỏ qua và đặt đơn mới</button></div></div>`
      : !salesOpen
        ? `<div class="notice warning"><strong>Cửa hàng đang tạm ngừng nhận đơn mới.</strong> Bạn vẫn có thể xem sản phẩm.</div>`
        : "";
  const locked = corrupt || unknown || !salesOpen;

  const html = `<a class="text-link shop-back" href="${BASE}">← Tất cả sản phẩm</a>
  <div class="shop-order">
  <section class="panel shop-info">
    <h1>${e(p.name)}</h1><p class="muted">${e(p.brand)}</p>
    <div class="shop-doc">${pdfs.length ? pdfs.map((f, i) => `<button type="button" class="secondary" data-file="${i}"${downloadsOpen ? "" : " disabled"}>${pdfs.length > 1 ? "Tải " + e(f.display_name) : "Tải thông số (PDF)"}</button><span class="muted shop-num">${kib(f.size_bytes)}</span>`).join("") + (downloadsOpen ? "" : '<span class="muted">Đang tạm ngừng tải tài liệu.</span>') : '<span class="muted">Sản phẩm này chưa có tài liệu thông số.</span>'}</div>
    <div id="doc-error" role="alert"></div>
    ${images.length ? `<div class="shop-photo"><img id="photo" src="${e(images[0].url)}" alt="${e(p.name)}" width="640" height="480"></div>${images.length > 1 ? `<div class="shop-thumbs">${images.map((f, i) => `<button type="button" data-photo="${i}" aria-label="Xem ảnh ${i + 1}" aria-pressed="${i === 0}"><img src="${e(f.url)}" alt="" width="56" height="56"></button>`).join("")}</div>` : ""}<p class="shop-caption" id="caption">${caption(images[0])}</p>` : '<div class="shop-photo"><span class="image-fallback">Chưa có ảnh</span></div>'}
    <div class="shop-specs"><h2>Thông số</h2><dl>${specs.map(([k, v]) => `<div><dt>${e(k)}</dt><dd>${e(v)}</dd></div>`).join("")}</dl></div>
    <p class="shop-fine">${meta?.region ? `Cấu hình tham chiếu: ${e(meta.region)}. ` : ""}Thông số theo nguồn của hãng. Giá và tồn kho là dữ liệu mẫu của đồ án, không phải báo giá bán lẻ.</p>
    ${meta ? `<details><summary>Nguồn ảnh và thông số</summary><ul>${(meta.sources || []).map((s, i) => `<li>${link(s.url, "Thông số từ hãng, nguồn " + (i + 1))}</li>`).join("")}${(meta.images || []).map((i) => `<li>${e(i.caption)}. Ảnh của ${e(i.author)}, ${link(i.license_url, i.license)}, ${link(i.source_page, "Wikimedia Commons")}.</li>`).join("")}</ul></details>` : ""}
  </section>
  <form class="panel shop-panel" id="order-form" novalidate aria-labelledby="order-title">
    <h2 id="order-title">Đặt hàng</h2>${banner}
    <fieldset><legend>Phiên bản</legend>${variants.length ? variants.map((v) => `<label class="shop-choice"><input type="radio" name="variant" value="${v.id}"${v === firstOpen ? " checked" : ""}${Number(v.stock) > 0 ? "" : " disabled"}><span><b>${e(v.color)}, ${storage(v.storage_gb)}</b><small>${Number(v.stock) > 0 ? "Còn " + v.stock + " máy" : "Hết hàng"}</small></span><span class="shop-price">${money(v.price_vnd)}</span></label>`).join("") : '<p class="muted">Sản phẩm này chưa mở bán.</p>'}</fieldset>
    ${field("Số lượng", "quantity", "number", 'class="shop-qty" inputmode="numeric" min="1" max="20" step="1" value="1"')}
    ${field("Người nhận", "recipient", "text", `autocomplete="name" maxlength="80" value="${e(user.name)}"`)}
    ${field("Số điện thoại", "phone", "tel", 'autocomplete="tel" inputmode="tel" maxlength="20"')}
    ${area("Địa chỉ nhận hàng", "address", 'rows="3" autocomplete="street-address" maxlength="300"')}
    ${area("Ghi chú", "note", 'rows="2" maxlength="500"', true)}
    <div class="shop-recap" aria-live="polite"><div><span>Tạm tính</span><span class="shop-num" id="r-sub"></span></div><div><span>Phí giao hàng</span><span class="shop-num">${money(shipping)}</span></div><div class="total"><span>Tổng cộng</span><span class="shop-num" id="r-total"></span></div></div>
    <p class="shop-pay">Thanh toán khi nhận hàng (COD).</p>
    <div id="order-error" role="alert"></div>
    <button class="full" type="submit" id="order-submit">Đặt hàng</button>
  </form></div>`;

  return {
    title: p.name,
    html,
    bind(root) {
      const form = $(root, "#order-form"),
        submit = $(root, "#order-submit"),
        errorBox = $(root, "#order-error");
      // Tài liệu PDF
      root.querySelectorAll("[data-file]").forEach(
        (button) =>
          (button.onclick = async () => {
            if (button.disabled) return;
            const label = button.textContent,
              box = $(root, "#doc-error");
            box.innerHTML = "";
            button.disabled = true;
            button.textContent = "Đang tải…";
            try {
              await download(pdfs[Number(button.dataset.file)]);
            } catch (error) {
              box.innerHTML = notice(error.message);
            } finally {
              button.disabled = false;
              button.textContent = label;
            }
          }),
      );
      // Thư viện ảnh
      root.querySelectorAll("[data-photo]").forEach(
        (button) =>
          (button.onclick = () => {
            const file = images[Number(button.dataset.photo)];
            $(root, "#photo").src = file.url;
            $(root, "#caption").innerHTML = caption(file);
            root
              .querySelectorAll("[data-photo]")
              .forEach((b) =>
                b.setAttribute("aria-pressed", String(b === button)),
              );
          }),
      );
      // Giá và số lượng
      const selected = () =>
        variants.find(
          (v) =>
            String(v.id) ===
            form.querySelector("input[name=variant]:checked")?.value,
        );
      const qty = form.elements.quantity;
      function recalc() {
        const v = selected(),
          max = v ? Math.min(20, Number(v.stock)) : 1;
        qty.max = String(Math.max(1, max));
        if (v && Number(qty.value) > max) qty.value = String(max);
        const n = Number(qty.value),
          valid = v && Number.isInteger(n) && n >= 1;
        $(root, "#r-sub").textContent = valid ? money(v.price_vnd * n) : "—";
        $(root, "#r-total").textContent = valid
          ? money(v.price_vnd * n + shipping)
          : "—";
      }
      form.addEventListener("change", recalc);
      qty.addEventListener("input", recalc);
      recalc();
      // Khóa form khi cần
      if (locked) {
        form
          .querySelectorAll("input,textarea")
          .forEach((x) => (x.disabled = true));
        submit.disabled = true;
      }
      if (!selected() && !locked) {
        submit.disabled = true;
        submit.textContent = "Hết hàng";
      }
      // Khôi phục đơn chưa rõ kết quả
      const retry = $(root, "#retry-order"),
        drop = $(root, "#drop-order"),
        reset = $(root, "#reset-order");
      if (retry)
        retry.onclick = async () => {
          retry.disabled = true;
          retry.textContent = "Đang kiểm tra…";
          try {
            const order = await checkout.submit(checkout.flow().intent.body);
            if (order) ctx.go(`${BASE}/orders/${order.id}?placed=1`);
          } catch (error) {
            ctx.toast(error.message, true);
            retry.disabled = false;
            retry.textContent = "Kiểm tra lại đơn";
          }
        };
      if (drop)
        drop.onclick = async () => {
          if (
            await ctx.confirm(
              "Đơn trước có thể đã được ghi nhận. Hãy xem mục Đơn của tôi trước. Vẫn đặt đơn mới?",
            )
          ) {
            try {
              checkout.fresh();
              ctx.go(ctx.path, true);
            } catch (error) {
              ctx.toast(error.message, true);
            }
          }
        };
      if (reset)
        reset.onclick = () => {
          try {
            checkout.fresh();
            ctx.go(ctx.path, true);
          } catch (error) {
            ctx.toast(error.message, true);
          }
        };
      // Gửi đơn
      let busy = false;
      form.onsubmit = async (event) => {
        event.preventDefault();
        if (busy || locked) return;
        errorBox.innerHTML = "";
        const v = selected(),
          n = Number(qty.value);
        const recipient = form.elements.recipient.value.trim();
        const phone = form.elements.phone.value.replace(/[\s.\-()]/g, "");
        const address = form.elements.address.value
          .replace(/\s*[\r\n]+\s*/g, ", ")
          .replace(/[\u0000-\u001f\u007f-\u009f]/g, " ")
          .trim();
        const note = form.elements.note.value
          .replace(/\s*[\r\n]+\s*/g, " ")
          .replace(/[\u0000-\u001f\u007f-\u009f]/g, " ")
          .trim();
        const problems = {};
        if (!v) problems.quantity = "Chọn một phiên bản còn hàng.";
        else if (
          !Number.isInteger(n) ||
          n < 1 ||
          n > Math.min(20, Number(v.stock))
        )
          problems.quantity = `Nhập số lượng từ 1 đến ${Math.min(20, Number(v.stock))}.`;
        if (length(recipient) < 2 || length(recipient) > 80)
          problems.recipient = "Nhập tên người nhận từ 2 đến 80 ký tự.";
        if (!/^\+?[0-9]{9,14}$/.test(phone))
          problems.phone = "Nhập số điện thoại từ 9 đến 14 chữ số.";
        if (length(address) < 10 || length(address) > 300)
          problems.address =
            "Nhập địa chỉ đầy đủ (số nhà, đường, phường, tỉnh), từ 10 ký tự.";
        if (length(note) > 500) problems.note = "Ghi chú tối đa 500 ký tự.";
        for (const name of [
          "quantity",
          "recipient",
          "phone",
          "address",
          "note",
        ])
          setError(form, name, problems[name]);
        const first = Object.keys(problems)[0];
        if (first) {
          form.elements[first].focus();
          return;
        }
        busy = true;
        submit.disabled = true;
        submit.textContent = "Đang đặt hàng…";
        try {
          const order = await checkout.submit({
            recipient,
            phone,
            address,
            note,
            payment_method: "COD",
            guest_token: guest,
            items: [{ variant_id: v.id, quantity: n }],
          });
          if (order) ctx.go(`${BASE}/orders/${order.id}?placed=1`);
        } catch (error) {
          if (!ctx.current()) return;
          if (error.status === 401) {
            setIdentity(null);
            await session(true).catch(() => {});
            ctx.go(BASE + "/login?next=" + encodeURIComponent(ctx.path), true);
            return;
          }
          if (checkout.flow().intent?.state === "UNKNOWN") {
            ctx.toast(error.message, true);
            ctx.go(ctx.path, true);
            return;
          }
          errorBox.innerHTML = notice(error.message, "Chưa đặt được hàng.");
        } finally {
          busy = false;
          if (ctx.current() && submit.isConnected) {
            submit.disabled = false;
            submit.textContent = "Đặt hàng";
          }
        }
      };
    },
  };
}

// ---- Đơn hàng ----
async function ordersPage(ctx) {
  const page = Math.max(
    1,
    Math.min(10000, Number(ctx.url.searchParams.get("page")) || 1),
  );
  const data = await api("/orders?page=" + page),
    items = data.items || [];
  const pager =
    page > 1 || items.length >= ORDER_PAGE_SIZE
      ? `<div class="shop-pager">${page > 1 ? `<a class="button secondary" href="${BASE}/orders?page=${page - 1}">Trang trước</a>` : "<span></span>"}${items.length >= ORDER_PAGE_SIZE ? `<a class="button secondary" href="${BASE}/orders?page=${page + 1}">Trang sau</a>` : "<span></span>"}</div>`
      : "";
  return {
    title: "Đơn của tôi",
    html: `<div class="page-heading"><p class="eyebrow">CỬA HÀNG</p><h1>Đơn của tôi</h1><p>Các đơn bạn đã đặt, mới nhất ở trên.</p></div>${items.length ? `<section class="panel"><div class="shop-scroll"><table><thead><tr><th>Ngày đặt</th><th>Mã đơn</th><th>Người nhận</th><th class="shop-right">Tổng tiền</th><th>Trạng thái</th></tr></thead><tbody>${items.map((o) => `<tr><td>${e(date(o.created_at))}</td><td><a class="text-link shop-num" href="${BASE}/orders/${e(o.id)}">${e(String(o.id).slice(0, 8))}</a></td><td>${e(o.recipient)}</td><td class="shop-right shop-num">${money(o.total_vnd)}</td><td><span class="badge">${e(status(o.status))}</span></td></tr>`).join("")}</tbody></table></div></section>${pager}` : `<div class="empty"><h2>Bạn chưa có đơn hàng nào</h2><p>Chọn một sản phẩm để đặt đơn đầu tiên.</p><a class="button" href="${BASE}">Xem sản phẩm</a></div>`}`,
  };
}
async function orderPage(ctx, id) {
  const o = await api("/orders/" + id),
    placed = ctx.url.searchParams.get("placed") === "1";
  const lines = o.items || [],
    history = o.history || [];
  return {
    title: "Đơn " + String(o.id).slice(0, 8),
    html: `<a class="text-link shop-back" href="${BASE}/orders">← Đơn của tôi</a>
    ${placed ? notice("Cảm ơn bạn. Cửa hàng sẽ xác nhận đơn sớm. Bạn trả tiền khi nhận hàng.", "Đã đặt hàng.", "success") : ""}
    <div class="page-heading"><p class="eyebrow">ĐƠN HÀNG</p><h1>Đơn ${e(String(o.id).slice(0, 8))}</h1><p>Đặt lúc ${e(date(o.created_at))}. <span class="badge">${e(status(o.status))}</span></p><p class="muted shop-num">Mã đơn đầy đủ: ${e(o.id)}</p></div>
    <section class="panel"><div class="shop-scroll"><table><thead><tr><th>Sản phẩm</th><th class="shop-right">Số lượng</th><th class="shop-right">Đơn giá</th><th class="shop-right">Thành tiền</th></tr></thead><tbody>${lines.map((i) => `<tr><td>${e(i.product_name)}<small>${e(i.color)}, ${storage(i.storage_gb)}</small></td><td class="shop-right shop-num">${i.quantity}</td><td class="shop-right shop-num">${money(i.unit_price_vnd)}</td><td class="shop-right shop-num">${money(i.line_total_vnd)}</td></tr>`).join("")}</tbody></table></div>
    <div class="shop-sum"><div><span>Tạm tính</span><span class="shop-num">${money(o.subtotal_vnd)}</span></div><div><span>Phí giao hàng</span><span class="shop-num">${money(o.shipping_vnd)}</span></div><div class="total"><span>Tổng cộng</span><span class="shop-num">${money(o.total_vnd)}</span></div></div></section>
    <div class="shop-cols"><section class="panel"><h2>Giao tới</h2><p>${e(o.recipient)}<br><span class="shop-num">${e(o.phone)}</span><br>${e(o.address)}</p>${o.note ? `<p class="muted">Ghi chú: ${e(o.note)}</p>` : ""}<p class="muted">Thanh toán khi nhận hàng. ${e(PAYMENT[o.payment_status] || o.payment_status)}.</p></section>
    <section class="panel"><h2>Tiến trình</h2><ol class="shop-timeline">${history.map((h) => `<li><span>${e(status(h.status))}</span><span class="muted shop-num">${e(date(h.created_at))}</span></li>`).join("")}</ol></section></div>`,
  };
}
