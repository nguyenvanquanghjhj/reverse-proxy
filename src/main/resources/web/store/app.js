import { api, session, identity, setIdentity, e } from "./api.js";
import { errorBox } from "./views.js";
import { adminView } from "./admin.js";
import { render as renderShop } from "./site.js";

const app = document.querySelector("#app");
let revision = 0,
  toastTimer;

export function toast(message, error = false) {
  const node = document.querySelector("#toast");
  clearTimeout(toastTimer);
  node.textContent = message;
  node.className = error ? "visible toast-error" : "visible";
  toastTimer = setTimeout(() => (node.className = ""), 4500);
}
export function confirm(message) {
  const dialog = document.querySelector("#confirm-dialog");
  document.querySelector("#confirm-text").textContent = message;
  return new Promise((resolve) => {
    dialog.addEventListener(
      "close",
      () => resolve(dialog.returnValue === "ok"),
      { once: true },
    );
    dialog.showModal();
  });
}
export function go(path, replace = false) {
  if (replace) history.replaceState({}, "", path);
  else history.pushState({}, "", path);
  queueMicrotask(render);
}

function adminShell() {
  return `<header class="admin-top"><a class="wordmark" href="/admin">mộc<span aria-hidden="true">•</span></a><span class="admin-top-label">KHÔNG GIAN QUẢN TRỊ</span><nav aria-label="Tài khoản quản trị"><a href="/admin/shop">Xem cửa hàng ↗</a><span>${e(identity()?.user?.name || "Khách")}</span>${identity()?.user ? '<button id="logout" class="secondary">Đăng xuất</button>' : '<a href="/admin/shop/login?next=admin">Đăng nhập</a>'}</nav></header><main id="main" tabindex="-1"><div class="shop-loading" role="status">Đang tải dữ liệu quản trị…</div></main>`;
}

async function render() {
  const version = ++revision,
    url = new URL(location.href),
    path = url.pathname;
  // Địa chỉ cũ (/ và /shop...) chuyển vào /admin/shop...
  if (path === "/" || path === "/shop" || path.startsWith("/shop/")) {
    const rest = path.replace(/^\/shop/, "").replace(/^\/$/, "");
    go("/admin/shop" + rest + url.search, true);
    return;
  }
  const ctx = {
    path,
    url,
    go,
    toast,
    confirm,
    current: () => version === revision,
  };
  if (path === "/admin/shop" || path.startsWith("/admin/shop/")) {
    try {
      await renderShop(app, ctx);
    } catch (error) {
      if (!ctx.current()) return;
      app.innerHTML = `<main id="main"><div class="empty"><h2>Chưa mở được cửa hàng</h2><p>${e(error.message)}</p><button type="button" id="retry-page">Thử lại</button></div></main>`;
      document.querySelector("#retry-page").onclick = render;
    }
    return;
  }
  app.innerHTML = adminShell();
  const logout = document.querySelector("#logout");
  if (logout)
    logout.onclick = async () => {
      logout.disabled = true;
      try {
        await api("/auth/logout", { method: "POST" });
        setIdentity(null);
        await session(true);
        go("/admin/shop/login");
      } catch (err) {
        toast(err.message, true);
        logout.disabled = false;
      }
    };
  const root = document.querySelector("#main");
  try {
    const page = await adminView(ctx);
    if (!ctx.current()) return;
    root.innerHTML = page.html;
    page.bind?.(root);
    root.dataset.route = url.pathname + url.search;
    root.focus({ preventScroll: true });
    window.scrollTo({ top: 0, behavior: "instant" });
    document.title =
      (root.querySelector("h1")?.textContent || "Quản trị") + " — Mộc";
  } catch (error) {
    if (!ctx.current()) return;
    root.innerHTML = `<div class="page-heading"><p class="eyebrow">MỘC / THÔNG BÁO</p><h1>${error.status === 404 ? "Không tìm thấy nội dung" : "Chưa thể mở trang này"}</h1></div>${errorBox(error.message)}<div class="actions"><button id="retry-page">Thử lại</button><a class="button secondary" href="/admin/shop">Về cửa hàng</a></div>`;
    root.querySelector("#retry-page").onclick = render;
  }
}
document.addEventListener("click", (event) => {
  const a = event.target.closest("a[href]");
  if (
    !a ||
    event.button !== 0 ||
    event.metaKey ||
    event.ctrlKey ||
    event.shiftKey ||
    event.altKey ||
    a.target ||
    a.download
  )
    return;
  const url = new URL(a.href, location.href);
  if (
    url.origin !== location.origin ||
    a.getAttribute("href").startsWith("#") ||
    url.pathname.startsWith("/api/") ||
    url.pathname.startsWith("/__proxy/")
  )
    return;
  event.preventDefault();
  go(url.pathname + url.search);
});
window.addEventListener("popstate", render);
try {
  await session();
} catch (error) {
  toast(error.message, true);
}
await render();
document.addEventListener(
  "error",
  (event) => {
    const img = event.target;
    if (img.tagName !== "IMG" || img.dataset.failed) return;
    img.dataset.failed = "true";
    img.hidden = true;
    const fallback = document.createElement("span");
    fallback.className = "image-fallback";
    fallback.textContent = "Ảnh đang được cập nhật";
    img.after(fallback);
  },
  true,
);
