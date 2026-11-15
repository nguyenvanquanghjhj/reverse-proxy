import {
  api,
  session,
  identity,
  setIdentity,
  download,
  e,
  money,
  date,
  status,
} from "./api.js";
import * as checkout from "./checkout.js";

const PAYMENT = {
  UNPAID: "Chưa thanh toán (trả khi nhận hàng)",
  PAID: "Đã thanh toán",
};
const PAGE_SIZE = 24,
  ORDER_PAGE_SIZE = 20;
const $ = (root, selector) => root.querySelector(selector);
const storage = (gb) => (gb >= 1024 ? gb / 1024 + " TB" : gb + " GB");
const kib = (bytes) => Math.max(1, Math.round(bytes / 1024)) + " KB";
const link = (url, label) =>
  typeof url === "string" && url.startsWith("https://")
    ? `<a href="${e(url)}" target="_blank" rel="noopener noreferrer">${e(label)}</a>`
    : e(label);
const notice = (message, title = "") =>
  `<div class="notice">${title ? `<strong>${e(title)}</strong> ` : ""}${e(message)}</div>`;

function field(label, name, type, attrs = "", hint = "", optional = false) {
  return `<div class="field"><label for="f-${name}">${label}${optional ? ' <span class="optional">(không bắt buộc)</span>' : ""}</label><input id="f-${name}" name="${name}" type="${type}" ${attrs}>${hint ? `<p class="hint">${hint}</p>` : ""}<p class="field-error" id="e-${name}" hidden></p></div>`;
}
function area(label, name, attrs = "", optional = false) {
  return `<div class="field"><label for="f-${name}">${label}${optional ? ' <span class="optional">(không bắt buộc)</span>' : ""}</label><textarea id="f-${name}" name="${name}" ${attrs}></textarea><p class="field-error" id="e-${name}" hidden></p></div>`;
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
const length = (text) => [...text].length;

// ---- Khung trang ----
function shell(user, active) {
  const nav = user
    ? `<nav class="nav" aria-label="Điều hướng chính"><a href="/shop"${active === "list" ? ' aria-current="page"' : ""}>Sản phẩm</a><a href="/shop/orders"${active === "orders" ? ' aria-current="page"' : ""}>Đơn của tôi</a>${user.role === "ADMIN" ? '<a href="/admin">Quản trị</a>' : ""}<span class="who">${e(user.name)}</span><button type="button" id="logout" class="link-button">Đăng xuất</button></nav>`
    : "";
  return `<header class="site-header"><div class="wrap bar"><a class="wordmark" href="/shop">Mộc</a>${nav}</div></header><main id="main" tabindex="-1"><div class="wrap" id="view"><div class="loading" role="status">Đang tải…</div></div></main>`;
}
function parse(path) {
  let m;
  if (path === "/shop/login") return { name: "login" };
  if (path === "/shop/register") return { name: "register" };
  if ((m = path.match(/^\/shop\/orders\/([0-9a-fA-F-]{36})$/)))
    return { name: "order", id: m[1].toLowerCase() };
  if (
    path === "/shop/orders" ||
    path === "/shop/account" ||
    path === "/shop/account/orders"
  )
    return { name: "orders" };
  if ((m = path.match(/^\/shop\/products\/([1-9][0-9]{0,17})$/)))
    return { name: "product", id: m[1] };
  if (
    [
      "/",
      "/shop",
      "/shop/",
      "/shop/products",
      "/shop/search",
      "/shop/cart",
      "/shop/checkout",
      "/shop/lookup",
    ].includes(path) ||
    path.startsWith("/shop/categories/")
  )
    return { name: "list" };
  return { name: "missing" };
}
function afterLogin(url, s) {
  const next = url.searchParams.get("next") || "";
  if (next === "admin") return s?.user?.role === "ADMIN" ? "/admin" : "/shop";
  return next.startsWith("/shop") && !next.startsWith("//") ? next : "/shop";
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
      "/shop/login" +
        (path === "/" || path === "/shop"
          ? ""
          : "?next=" + encodeURIComponent(path + url.search)),
      true,
    );
    return;
  }
  app.innerHTML = shell(
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
        go("/shop/login");
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
      go("/shop/login?next=" + encodeURIComponent(path + url.search), true);
      return;
    }
    view.innerHTML =
      error.status === 404
        ? missingPage().html
        : `<div class="empty"><h1>Chưa mở được trang này</h1><p>${e(error.message)}</p><div class="actions"><button type="button" id="retry-page">Thử lại</button><a class="button secondary" href="/shop">Về danh sách sản phẩm</a></div></div>`;
    const retry = $(view, "#retry-page");
    if (retry) retry.onclick = () => go(path + url.search, true);
    document.title = "Có lỗi — Mộc";
  }
}
function missingPage() {
  return {
    title: "Không tìm thấy",
    html: `<div class="empty"><h1>Không tìm thấy trang</h1><p>Địa chỉ này không tồn tại hoặc sản phẩm đã ngừng bán.</p><a class="button" href="/shop">Về danh sách sản phẩm</a></div>`,
  };
}

// ---- Đăng nhập và đăng ký ----
function authPage(ctx, register) {
  const next = ctx.url.searchParams.get("next"),
    qs = next ? "?next=" + encodeURIComponent(next) : "";
  const title = register ? "Tạo tài khoản" : "Đăng nhập";
  return {
    title,
    html: `<section class="auth"><h1>${title}</h1>
    <div class="tabs"><a href="/shop/login${qs}"${register ? "" : ' aria-current="page"'}>Đăng nhập</a><a href="/shop/register${qs}"${register ? ' aria-current="page"' : ""}>Tạo tài khoản</a></div>
    <form id="auth-form" novalidate>
      ${register ? field("Họ tên", "name", "text", 'autocomplete="name" maxlength="80"') : ""}
      ${field("Email", "email", "email", 'autocomplete="email" maxlength="254"')}
      ${field("Mật khẩu", "password", "password", `autocomplete="${register ? "new-password" : "current-password"}" maxlength="128"`, register ? "Từ 12 đến 128 ký tự." : "")}
      <div id="form-error" role="alert"></div>
      <button class="block" type="submit">${title}</button>
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
    html: `<div class="page-title"><h1>Sản phẩm</h1></div>
    <form class="search" id="search-form" role="search"><label class="sr-only" for="search">Tìm theo tên sản phẩm</label><input id="search" type="search" name="q" value="${e(initial)}" placeholder="Tìm theo tên, ví dụ Pixel" maxlength="100" autocomplete="off"></form>
    <p class="status" id="status" role="status" aria-live="polite"></p><div id="results"></div>`,
    bind(root) {
      const input = $(root, "#search"),
        statusLine = $(root, "#status"),
        results = $(root, "#results");
      let term = initial.trim(),
        sequence = 0,
        timer;
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
          results.innerHTML = `<div class="notice"><strong>Chưa tải được sản phẩm.</strong> ${e(error.message)}<div class="row"><button type="button" class="secondary" id="retry">Thử lại</button></div></div>`;
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
            ? `<div class="items">${items.map((p) => `<a class="item" href="/shop/products/${p.id}"><span class="thumb">${p.image_url ? `<img src="${e(p.image_url)}" alt="" width="72" height="72" loading="lazy">` : ""}</span><span><span class="item-name">${e(p.name)}</span><span class="item-meta">${e(p.brand)}</span></span><span class="item-price num"><small>Từ</small> ${money(p.price_vnd)}${Number(p.stock) > 0 ? "" : "<small>Tạm hết hàng</small>"}</span></a>`).join("")}</div>`
            : "") +
          (pages > 1
            ? `<div class="pager"><button type="button" class="secondary" id="prev"${page <= 1 ? " disabled" : ""}>Trang trước</button><span class="muted">Trang ${page} / ${pages}</span><button type="button" class="secondary" id="next"${page >= pages ? " disabled" : ""}>Trang sau</button></div>`
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
          history.replaceState(
            {},
            "",
            term ? "/shop?q=" + encodeURIComponent(term) : "/shop",
          );
          load(1);
        }, 250);
      };
      $(root, "#search-form").onsubmit = (event) => {
        event.preventDefault();
        clearTimeout(timer);
        term = input.value.trim();
        history.replaceState(
          {},
          "",
          term ? "/shop?q=" + encodeURIComponent(term) : "/shop",
        );
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
    ? `<div class="notice"><strong>Không đọc được thông tin đơn trước đó.</strong> Hãy xem mục Đơn của tôi trước khi đặt đơn mới.<div class="row"><a href="/shop/orders">Xem đơn của tôi</a><button type="button" class="secondary" id="reset-order">Đặt đơn mới</button></div></div>`
    : unknown
      ? `<div class="notice"><strong>Đơn trước chưa rõ kết quả.</strong> Kết nối bị gián đoạn khi đặt hàng. Bấm kiểm tra lại để xác nhận. Hệ thống không tạo đơn trùng.<div class="row"><button type="button" id="retry-order">Kiểm tra lại đơn</button><button type="button" class="link-button" id="drop-order">Bỏ qua và đặt đơn mới</button></div></div>`
      : !salesOpen
        ? `<div class="notice"><strong>Cửa hàng đang tạm ngừng nhận đơn mới.</strong> Bạn vẫn có thể xem sản phẩm.</div>`
        : "";
  const locked = corrupt || unknown || !salesOpen;

  const html = `<a class="back" href="/shop">← Tất cả sản phẩm</a>
  <div class="order-layout"><div>
    <h1>${e(p.name)}</h1><p class="muted">${e(p.brand)}</p>
    <div class="doc">${pdfs.length ? pdfs.map((f, i) => `<button type="button" class="secondary" data-file="${i}"${downloadsOpen ? "" : " disabled"}>${pdfs.length > 1 ? "Tải " + e(f.display_name) : "Tải thông số (PDF)"}</button><small class="num">${kib(f.size_bytes)}</small>`).join("") + (downloadsOpen ? "" : "<small>Đang tạm ngừng tải tài liệu.</small>") : "<small>Sản phẩm này chưa có tài liệu thông số.</small>"}</div>
    <div id="doc-error" role="alert"></div>
    <div class="gallery">${images.length ? `<div class="photo"><img id="photo" src="${e(images[0].url)}" alt="${e(p.name)}" width="640" height="480"></div>${images.length > 1 ? `<div class="thumbs">${images.map((f, i) => `<button type="button" data-photo="${i}" aria-label="Xem ảnh ${i + 1}" aria-pressed="${i === 0}"><img src="${e(f.url)}" alt="" width="56" height="56"></button>`).join("")}</div>` : ""}<p class="caption" id="caption">${caption(images[0])}</p>` : '<div class="photo"><span class="image-fallback">Chưa có ảnh</span></div>'}</div>
    <section class="specs"><h2>Thông số</h2><dl>${specs.map(([k, v]) => `<div><dt>${e(k)}</dt><dd>${e(v)}</dd></div>`).join("")}</dl></section>
    <p class="fine">${meta?.region ? `Cấu hình tham chiếu: ${e(meta.region)}. ` : ""}Thông số theo nguồn của hãng. Giá và tồn kho là dữ liệu mẫu của đồ án, không phải báo giá bán lẻ.</p>
    ${meta ? `<details><summary>Nguồn ảnh và thông số</summary><ul>${(meta.sources || []).map((s, i) => `<li>${link(s.url, "Thông số từ hãng, nguồn " + (i + 1))}</li>`).join("")}${(meta.images || []).map((i) => `<li>${e(i.caption)}. Ảnh của ${e(i.author)}, ${link(i.license_url, i.license)}, ${link(i.source_page, "Wikimedia Commons")}.</li>`).join("")}</ul></details>` : ""}
  </div>
  <form class="order-panel" id="order-form" novalidate aria-labelledby="order-title">
    <h2 id="order-title">Đặt hàng</h2>${banner}
    <fieldset><legend>Phiên bản</legend>${variants.length ? variants.map((v) => `<label class="choice"><input type="radio" name="variant" value="${v.id}"${v === firstOpen ? " checked" : ""}${Number(v.stock) > 0 ? "" : " disabled"}><span><b>${e(v.color)}, ${storage(v.storage_gb)}</b><small>${Number(v.stock) > 0 ? "Còn " + v.stock + " máy" : "Hết hàng"}</small></span><span class="num">${money(v.price_vnd)}</span></label>`).join("") : '<p class="muted">Sản phẩm này chưa mở bán.</p>'}</fieldset>
    ${field("Số lượng", "quantity", "number", 'class="qty" inputmode="numeric" min="1" max="20" step="1" value="1"')}
    ${field("Người nhận", "recipient", "text", `autocomplete="name" maxlength="80" value="${e(user.name)}"`)}
    ${field("Số điện thoại", "phone", "tel", 'autocomplete="tel" inputmode="tel" maxlength="20"')}
    ${area("Địa chỉ nhận hàng", "address", 'rows="3" autocomplete="street-address" maxlength="300"')}
    ${area("Ghi chú", "note", 'rows="2" maxlength="500"', true)}
    <div class="recap" aria-live="polite"><div><span>Tạm tính</span><span class="num" id="r-sub"></span></div><div><span>Phí giao hàng</span><span class="num">${money(shipping)}</span></div><div class="total"><span>Tổng cộng</span><span class="num" id="r-total"></span></div></div>
    <p class="pay">Thanh toán khi nhận hàng (COD).</p>
    <div id="order-error" role="alert"></div>
    <button class="block" type="submit" id="order-submit">Đặt hàng</button>
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
              box.innerHTML = `<div class="notice doc-error">${e(error.message)}</div>`;
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
            if (order) ctx.go("/shop/orders/" + order.id + "?placed=1");
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
          if (order) ctx.go("/shop/orders/" + order.id + "?placed=1");
        } catch (error) {
          if (!ctx.current()) return;
          if (error.status === 401) {
            setIdentity(null);
            await session(true).catch(() => {});
            ctx.go("/shop/login?next=" + encodeURIComponent(ctx.path), true);
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
      ? `<div class="pager">${page > 1 ? `<a class="button secondary" href="/shop/orders?page=${page - 1}">Trang trước</a>` : "<span></span>"}${items.length >= ORDER_PAGE_SIZE ? `<a class="button secondary" href="/shop/orders?page=${page + 1}">Trang sau</a>` : "<span></span>"}</div>`
      : "";
  return {
    title: "Đơn của tôi",
    html: `<div class="page-title"><h1>Đơn của tôi</h1></div>${items.length ? `<div class="scroll"><table class="table"><thead><tr><th>Ngày đặt</th><th>Mã đơn</th><th>Người nhận</th><th class="right">Tổng tiền</th><th>Trạng thái</th></tr></thead><tbody>${items.map((o) => `<tr><td>${e(date(o.created_at))}</td><td><a class="num" href="/shop/orders/${e(o.id)}">${e(String(o.id).slice(0, 8))}</a></td><td>${e(o.recipient)}</td><td class="right num">${money(o.total_vnd)}</td><td>${e(status(o.status))}</td></tr>`).join("")}</tbody></table></div>${pager}` : `<div class="empty"><p>Bạn chưa có đơn hàng nào.</p><a class="button" href="/shop">Xem sản phẩm</a></div>`}`,
  };
}
async function orderPage(ctx, id) {
  const o = await api("/orders/" + id),
    placed = ctx.url.searchParams.get("placed") === "1";
  const lines = o.items || [],
    history = o.history || [];
  return {
    title: "Đơn " + String(o.id).slice(0, 8),
    html: `<a class="back" href="/shop/orders">← Đơn của tôi</a>
    ${placed ? notice("Cảm ơn bạn. Cửa hàng sẽ xác nhận đơn sớm. Bạn trả tiền khi nhận hàng.", "Đã đặt hàng.") : ""}
    <div class="page-title"><h1>Đơn ${e(String(o.id).slice(0, 8))}</h1><p>Đặt lúc ${e(date(o.created_at))}. Trạng thái: <strong>${e(status(o.status))}</strong></p><p class="num">Mã đơn đầy đủ: ${e(o.id)}</p></div>
    <div class="scroll"><table class="table"><thead><tr><th>Sản phẩm</th><th class="right">Số lượng</th><th class="right">Đơn giá</th><th class="right">Thành tiền</th></tr></thead><tbody>${lines.map((i) => `<tr><td>${e(i.product_name)}<br><small>${e(i.color)}, ${storage(i.storage_gb)}</small></td><td class="right num">${i.quantity}</td><td class="right num">${money(i.unit_price_vnd)}</td><td class="right num">${money(i.line_total_vnd)}</td></tr>`).join("")}</tbody></table></div>
    <div class="sum"><div><span>Tạm tính</span><span class="num">${money(o.subtotal_vnd)}</span></div><div><span>Phí giao hàng</span><span class="num">${money(o.shipping_vnd)}</span></div><div class="total"><span>Tổng cộng</span><span class="num">${money(o.total_vnd)}</span></div></div>
    <div class="cols"><section><h2>Giao tới</h2><p>${e(o.recipient)}<br><span class="num">${e(o.phone)}</span><br>${e(o.address)}</p>${o.note ? `<p class="muted">Ghi chú: ${e(o.note)}</p>` : ""}<p class="muted">Thanh toán khi nhận hàng. ${e(PAYMENT[o.payment_status] || o.payment_status)}.</p></section>
    <section><h2>Tiến trình</h2><ol class="timeline">${history.map((h) => `<li><span>${e(status(h.status))}</span><span class="muted num">${e(date(h.created_at))}</span></li>`).join("")}</ol></section></div>`,
  };
}
