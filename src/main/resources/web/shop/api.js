// HTTP and response validation only. No DOM, automatic retry, or optimistic order confirmation.
export class ApiError extends Error {
  constructor(code, status = 0, kind = 'network') { super(code); this.code = code; this.status = status; this.kind = kind; }
}
export const isUuid = value => typeof value === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value);
const integer = (value, min = 0) => Number.isSafeInteger(value) && value >= min;
const object = value => value && typeof value === 'object' && !Array.isArray(value);
const text = (value, max) => typeof value === 'string' && value.length > 0 && value.length <= max;
const businessStatuses = new Set([400, 401, 403, 404, 405, 409, 413, 415, 422]);

export function validProduct(value) {
  return object(value) && integer(value.id, 1) && text(value.name, 200) && text(value.sku, 80)
    && integer(value.priceVnd) && integer(value.stockQuantity) && value.status === 'ACTIVE';
}
export function validOrder(value) {
  if (!object(value) || !isUuid(value.orderId) || !integer(value.totalVnd) || value.status !== 'CREATED'
      || !text(value.createdAt, 80) || !Number.isFinite(Date.parse(value.createdAt))
      || typeof value.idempotentReplay !== 'boolean' || !Array.isArray(value.items)
      || value.items.length < 1 || value.items.length > 20) return false;
  let total = 0; const seen = new Set();
  for (const item of value.items) {
    if (!object(item) || !integer(item.productId, 1) || seen.has(item.productId)
        || !integer(item.quantity, 1) || item.quantity > 100 || !integer(item.unitPriceVnd)
        || !integer(item.lineTotalVnd) || item.unitPriceVnd * item.quantity !== item.lineTotalVnd) return false;
    seen.add(item.productId); total += item.lineTotalVnd;
    if (!Number.isSafeInteger(total)) return false;
  }
  return total === value.totalVnd;
}

async function response(path, options = {}) {
  try { return await fetch(path, {cache:'no-store', ...options, signal:AbortSignal.timeout(8000)}); }
  catch (_) { throw new ApiError('CONNECTION_UNCERTAIN'); }
}
async function json(path, options, validate) {
  const result = await response(path, options);
  let data;
  try {
    if (!(result.headers.get('Content-Type') || '').toLowerCase().includes('application/json')) throw new Error();
    data = await result.json();
  } catch (_) { throw new ApiError('INVALID_RESPONSE', result.status, 'protocol'); }
  if (!result.ok) {
    if (!object(data) || !(typeof data.code === 'string' || typeof data.error === 'string')) throw new ApiError('INVALID_RESPONSE',result.status,'protocol');
    const code = typeof data?.code === 'string' ? data.code : typeof data?.error === 'string' ? data.error : 'REQUEST_FAILED';
    throw new ApiError(code, result.status, businessStatuses.has(result.status) ? 'business' : 'unavailable');
  }
  if (!validate(data)) throw new ApiError('INVALID_RESPONSE', result.status, 'protocol');
  return data;
}

export const products = async () => (await json('/api/products', {}, d => object(d) && Array.isArray(d.products) && d.products.length <= 100 && d.products.every(validProduct))).products;
export const product = id => json('/api/products/' + encodeURIComponent(id), {}, d => validProduct(d) && d.id === id);
export const order = id => json('/api/orders/' + encodeURIComponent(id), {}, d => validOrder(d) && d.orderId.toLowerCase() === id.toLowerCase());
export const createOrder = intent => {
  const expected=JSON.parse(intent.body).items;
  return json('/api/orders', {method:'POST', headers:{'Content-Type':'application/json','Idempotency-Key':intent.key}, body:intent.body}, d =>
    validOrder(d) && d.items.length === expected.length && expected.every(item => d.items.some(line => line.productId === item.productId && line.quantity === item.quantity)));
};
export async function documents(id) {
  return (await json(`/api/products/${encodeURIComponent(id)}/documents`, {}, d => object(d) && Array.isArray(d.documents) && d.documents.length <= 100 && d.documents.every(doc =>
    object(doc) && isUuid(doc.documentId) && text(doc.displayName, 100) && !/[\r\n/\\]/.test(doc.displayName)
    && integer(doc.sizeBytes, 1) && doc.sizeBytes <= 786432 && /^[0-9a-f]{64}$/.test(doc.sha256)
    && doc.contentType === 'application/pdf' && doc.downloadUrl === `/api/documents/${doc.documentId}/download`))).documents;
}
export async function download(doc) {
  const result = await response(doc.downloadUrl);
  if (result.status !== 200) throw new ApiError('DOWNLOAD_UNAVAILABLE', result.status);
  try {
    if (!(result.headers.get('Content-Type') || '').startsWith('application/pdf')) throw new Error();
    const bytes = await result.arrayBuffer();
    const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(n => n.toString(16).padStart(2,'0')).join('');
    if (bytes.byteLength !== doc.sizeBytes || digest !== doc.sha256) throw new Error();
    return bytes;
  } catch (_) { throw new ApiError('DOWNLOAD_INCOMPLETE'); }
}

export function message(error) {
  const messages = {
    INSUFFICIENT_STOCK:'Số lượng yêu cầu vượt quá số hàng hiện có. Hãy điều chỉnh số lượng rồi thử lại.',
    PRODUCT_INACTIVE:'Sản phẩm này hiện không còn được bán. Hãy chọn sản phẩm khác.',
    PRODUCT_NOT_FOUND:'Sản phẩm này không còn trong danh sách. Hãy làm mới sản phẩm.',
    ORDER_NOT_FOUND:'Không tìm thấy đơn hàng. Hãy kiểm tra lại mã đơn.',
    INVALID_ID:'Mã đơn không đúng định dạng.',
    INVALID_ORDER:'Vui lòng kiểm tra tên và số lượng đã chọn.',
    VALIDATION:'Vui lòng kiểm tra tên và số lượng đã chọn.',
    IDEMPOTENCY_CONFLICT:'Thông tin này không khớp với đơn đã gửi. Hãy kiểm tra đơn trước khi tạo đơn khác.',
    BROWSER_STORAGE:'Trình duyệt chưa thể lưu đơn đang xử lý. Hãy cho phép dữ liệu trang rồi thử lại.',
    SAVED_ORDER_INVALID:'Chưa thể khôi phục thông tin đơn đang xử lý. Hãy tra cứu mã đơn trước khi tạo đơn khác.',
    OFFLINE:'Không thể kết nối. Hãy kiểm tra kết nối mạng rồi thử lại.',
    INVALID_RESPONSE:'Chưa nhận được kết quả hợp lệ. Vui lòng thử lại.',
    DOWNLOAD_UNAVAILABLE:'Tài liệu hiện chưa tải được. Vui lòng thử lại sau.',
    DOWNLOAD_INCOMPLETE:'Tệp tải xuống chưa đầy đủ. Vui lòng thử lại.'
  };
  return messages[error?.code] || (error?.kind === 'business' ? 'Vui lòng kiểm tra thông tin và thử lại.' : 'Không thể kết nối. Vui lòng thử lại.');
}
