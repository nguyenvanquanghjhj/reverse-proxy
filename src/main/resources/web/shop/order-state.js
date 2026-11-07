import {ApiError, isUuid, validOrder} from './api.js';

// One durable intent per tab. Save SUBMITTING before returning anything that may be sent.
// Reload never sends a POST. It restores an uncertain outcome for an explicit same-key retry.
export const STORAGE_KEY = 'pbl4.shop.intent.v1';
export const STATES = Object.freeze(['IDLE','VALIDATING','SUBMITTING','CONFIRMED','BUSINESS_ERROR','UNKNOWN_RESULT','NETWORK_ERROR']);
export function browserStore() {
  return {
    read: () => sessionStorage.getItem(STORAGE_KEY),
    write: value => {
      const raw = JSON.stringify(value);
      if (new TextEncoder().encode(raw).length > 32768) throw new Error('Saved intent limit');
      sessionStorage.setItem(STORAGE_KEY, raw);
    },
    clear: () => sessionStorage.removeItem(STORAGE_KEY)
  };
}
export function validatePayload(value) {
  const name = typeof value?.customerName === 'string' ? value.customerName.trim() : '';
  if (!name || [...name].length > 80 || /[\u0000-\u001f\u007f-\u009f]/.test(name)
      || !Array.isArray(value?.items) || !value.items.length || value.items.length > 20) throw new ApiError('INVALID_ORDER',422,'business');
  const ids = new Set();
  const items = value.items.map(item => {
    if (!Number.isSafeInteger(item.productId) || item.productId < 1 || ids.has(item.productId)
        || !Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > 100) throw new ApiError('INVALID_ORDER',422,'business');
    ids.add(item.productId); return {productId:item.productId, quantity:item.quantity};
  }).sort((a,b) => a.productId-b.productId);
  return {customerName:name, items};
}
function validIntent(intent) {
  if (!intent || !isUuid(intent.key) || typeof intent.body !== 'string' || intent.body.length > 16384
      || !Number.isSafeInteger(intent.estimate) || intent.estimate < 0
      || !Array.isArray(intent.lines) || intent.lines.length > 20) return false;
  try {
    validatePayload(JSON.parse(intent.body));
    return intent.lines.every(line => Number.isSafeInteger(line.productId) && line.productId > 0
      && typeof line.name === 'string' && line.name.length <= 200
      && Number.isSafeInteger(line.priceVnd) && line.priceVnd >= 0);
  } catch (_) { return false; }
}

export class OrderFlow {
  constructor(store = browserStore(), uuid = () => crypto.randomUUID()) {
    this.store=store; this.uuid=uuid; this.phase='IDLE'; this.intent=null; this.order=null; this.error=null;
    try {
      const raw=store.read();
      if (!raw) return;
      if (typeof raw !== 'string' || new TextEncoder().encode(raw).length > 32768) throw new Error();
      const saved=JSON.parse(raw);
      if (saved.version !== 1 || !STATES.includes(saved.phase) || !validIntent(saved.intent)) throw new Error();
      this.intent=saved.intent;
      if (saved.phase === 'CONFIRMED' && validOrder(saved.order)) { this.phase='CONFIRMED'; this.order=saved.order; }
      else if (saved.phase === 'BUSINESS_ERROR') { this.phase='BUSINESS_ERROR'; this.error=new ApiError(saved.error?.code || 'INVALID_ORDER',saved.error?.status || 422,'business'); }
      else { this.phase='UNKNOWN_RESULT'; this.error=new ApiError('CONNECTION_UNCERTAIN'); }
    } catch (_) { this.phase='UNKNOWN_RESULT'; this.error=new ApiError('SAVED_ORDER_INVALID'); }
  }
  get ambiguous() { return this.phase === 'UNKNOWN_RESULT' || this.phase === 'SUBMITTING' || this.error?.code === 'IDEMPOTENCY_CONFLICT'; }
  get editable() { return !this.ambiguous && this.phase !== 'CONFIRMED'; }
  snapshot(phase = this.phase) {
    return {version:1, phase, intent:this.intent, order:this.order,
      error:this.error ? {code:this.error.code,status:this.error.status,kind:this.error.kind} : null};
  }
  prepare(payload, lines, estimate) {
    if (this.phase === 'SUBMITTING' || this.phase === 'CONFIRMED' || this.error?.code === 'IDEMPOTENCY_CONFLICT') return null;
    if (this.phase === 'UNKNOWN_RESULT' && !this.intent) return null;
    const previous=this.intent;
    if (!this.ambiguous) {
      this.phase='VALIDATING';
      try {
        const body=JSON.stringify(validatePayload(payload));
        if (!Number.isSafeInteger(estimate) || estimate < 0) throw new ApiError('INVALID_ORDER',422,'business');
        this.intent={key:this.intent?.body === body ? this.intent.key : this.uuid(),body,lines,estimate};
      } catch (error) { this.phase='BUSINESS_ERROR'; this.error=error; return null; }
    }
    try { this.store.write(this.snapshot('SUBMITTING')); }
    catch (_) {
      this.intent=previous; this.phase=this.ambiguous ? 'UNKNOWN_RESULT' : 'NETWORK_ERROR'; this.error=new ApiError('BROWSER_STORAGE'); return null;
    }
    this.phase='SUBMITTING'; this.error=null;
    return {key:this.intent.key,body:this.intent.body};
  }
  confirmed(order) {
    if (this.phase !== 'SUBMITTING' || !validOrder(order)) throw new ApiError('INVALID_RESPONSE',200,'protocol');
    this.order=order; this.phase='CONFIRMED'; this.error=null;
    try { this.store.write(this.snapshot()); } catch (_) { /* Existing SUBMITTING survives reload; replay is still safe. */ }
  }
  failed(error) {
    if (this.phase !== 'SUBMITTING') throw new Error('No active order attempt');
    this.error=error;
    this.phase=error.kind === 'business' ? 'BUSINESS_ERROR' : 'UNKNOWN_RESULT';
    try { this.store.write(this.snapshot()); }
    catch (_) { this.phase='UNKNOWN_RESULT'; /* The persisted SUBMITTING is conservative. */ }
  }
  offline() {
    if (this.phase === 'CONFIRMED' || this.phase === 'SUBMITTING') return;
    this.phase=this.ambiguous ? 'UNKNOWN_RESULT' : 'NETWORK_ERROR'; this.error=new ApiError('OFFLINE');
  }
  restart() {
    if (this.phase === 'SUBMITTING') return false;
    try { this.store.clear(); }
    catch (_) { this.error=new ApiError('BROWSER_STORAGE'); return false; }
    this.phase='IDLE'; this.intent=null; this.order=null; this.error=null; return true;
  }
}
