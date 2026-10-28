// Image attachments (SPEC §1 `images`, §2a): client-side downscaling, an upload queue that survives
// offline periods (IndexedDB), instant blob: previews, and <img> helpers with placeholder + retry.
import { h, icon, ulid } from './util.js';
import { store } from './store.js';
import { toast } from './toast.js';

const MAX_EDGE = 2560;
const JPEG_Q = 0.85;
const ACCEPT = /^image\/(jpeg|png|webp|gif|heic|heif|avif|bmp)$/;

// ---------- previews & upload state ----------
const previews = new Map();   // id → blob: URL (until the server has it and the item re-renders)
const pending = new Map();    // id → { blob, mime, tries, failedOnce }
let busy = false;
let retryTimer = 0;

export const isUploading = (id) => pending.has(id);
export const previewURL = (id) => previews.get(id) || null;

/** Items whose images are still uploading are held back from sync until the upload succeeds or fails once. */
store.holdBack = (it) => (it.images || []).some((img) => {
  const p = pending.get(img.id);
  return p && !p.failedOnce;
});

// ---------- IndexedDB (offline queue) ----------
let dbp = null;
function db() {
  if (dbp) return dbp;
  dbp = new Promise((resolve) => {
    try {
      const req = indexedDB.open('jot-uploads', 1);
      req.onupgradeneeded = () => req.result.createObjectStore('uploads', { keyPath: 'id' });
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => resolve(null);
    } catch { resolve(null); }
  });
  return dbp;
}
async function idb(mode, fn) {
  const d = await db();
  if (!d) return null;
  return new Promise((resolve) => {
    try {
      const tx = d.transaction('uploads', mode);
      const r = fn(tx.objectStore('uploads'));
      tx.oncomplete = () => resolve(r?.result ?? null);
      tx.onerror = () => resolve(null);
    } catch { resolve(null); }
  });
}

/** Restore queued uploads after a reload and continue. */
export async function resumeUploads() {
  const all = await idb('readonly', (s) => s.getAll());
  for (const u of all || []) {
    if (pending.has(u.id)) continue;
    pending.set(u.id, { blob: u.blob, mime: u.mime, tries: 0, failedOnce: true });
    if (!previews.has(u.id)) previews.set(u.id, URL.createObjectURL(u.blob));
  }
  pump();
}
window.addEventListener('online', () => pump());

// ---------- processing ----------
/** Downscale + re-encode (strips EXIF/GPS, applies orientation). GIFs pass through untouched. */
export async function prepareImage(file) {
  if (file.type === 'image/gif') {
    const { w, h } = await dims(file);
    return { blob: file, mime: 'image/gif', w, h };
  }
  let bmp;
  try {
    bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    bmp = await loadViaImg(file);
  }
  const scale = Math.min(1, MAX_EDGE / Math.max(bmp.width, bmp.height));
  const w = Math.max(1, Math.round(bmp.width * scale));
  const hh = Math.max(1, Math.round(bmp.height * scale));
  const canvas = typeof OffscreenCanvas !== 'undefined' ? new OffscreenCanvas(w, hh) : Object.assign(document.createElement('canvas'), { width: w, height: hh });
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#fff'; // JPEG has no alpha: flatten transparent PNGs onto white, not black
  ctx.fillRect(0, 0, w, hh);
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(bmp, 0, 0, w, hh);
  bmp.close?.();
  const blob = canvas.convertToBlob
    ? await canvas.convertToBlob({ type: 'image/jpeg', quality: JPEG_Q })
    : await new Promise((r) => canvas.toBlob(r, 'image/jpeg', JPEG_Q));
  return { blob, mime: 'image/jpeg', w, h: hh };
}

async function dims(file) {
  try {
    const b = await createImageBitmap(file);
    const r = { w: b.width, h: b.height };
    b.close?.();
    return r;
  } catch {
    const img = await loadViaImg(file);
    return { w: img.width, h: img.height };
  }
}

function loadViaImg(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => { URL.revokeObjectURL(url); resolve(img); };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('unreadable image')); };
    img.src = url;
  });
}

export const imageFiles = (list) => [...(list || [])].filter((f) => f && (ACCEPT.test(f.type) || /\.(jpe?g|png|webp|gif|heic|avif)$/i.test(f.name || '')));

/**
 * Turn files into image refs: downscale, queue the upload (IndexedDB + network) and return
 * `{ id, w, h, mime }` refs right away. Previews are available immediately via previewURL(id).
 */
export async function ingest(files) {
  const refs = [];
  for (const f of imageFiles(files)) {
    try {
      const p = await prepareImage(f);
      const id = ulid();
      previews.set(id, URL.createObjectURL(p.blob));
      pending.set(id, { blob: p.blob, mime: p.mime, tries: 0, failedOnce: false });
      idb('readwrite', (s) => s.put({ id, blob: p.blob, mime: p.mime }));
      refs.push({ id, w: p.w, h: p.h, mime: p.mime });
    } catch {
      toast(`Couldn’t read “${f.name || 'image'}”`);
    }
  }
  if (refs.length) pump();
  return refs;
}

// ---------- upload pump ----------
async function pump() {
  if (busy || !pending.size) return;
  busy = true;
  clearTimeout(retryTimer);
  let wait = 0;
  for (const [id, u] of [...pending]) {
    let res;
    try {
      res = await fetch(`/api/attachments/${encodeURIComponent(id)}`, {
        method: 'PUT', credentials: 'same-origin', headers: { 'Content-Type': u.mime }, body: u.blob,
      });
    } catch { res = null; }
    if (res && res.ok) {
      pending.delete(id);
      idb('readwrite', (s) => s.delete(id));
      document.querySelectorAll(`[data-img="${id}"]`).forEach((n) => n.classList.remove('uploading'));
      store.schedulePush(); // the item can go out now
      continue;
    }
    if (res && res.status === 401) { store.emit('auth', null); break; }
    if (res && (res.status === 413 || res.status === 400 || res.status === 415)) {
      // Never going to work: drop it from the queue and say so.
      pending.delete(id);
      idb('readwrite', (s) => s.delete(id));
      toast(res.status === 413 ? 'An image was too large to upload' : 'An image could not be uploaded');
      continue;
    }
    // Offline or server trouble: let the item sync without waiting, retry later.
    u.failedOnce = true;
    u.tries++;
    wait = Math.max(wait, Math.min(60000, 2000 * 2 ** Math.min(u.tries, 5)));
    store.schedulePush();
  }
  busy = false;
  if (pending.size) retryTimer = setTimeout(pump, wait || 2000);
}

// ---------- URLs ----------
const dpr = () => window.devicePixelRatio || 1;
/** Pick the server thumbnail width for a slot that is `cssWidth` px wide. */
export function thumbWidth(cssWidth) {
  const need = cssWidth * dpr();
  return need <= 240 ? 240 : need <= 480 ? 480 : need <= 960 ? 960 : 1600;
}
export function imageURL(id, w = 0) {
  return previews.get(id) || `/api/attachments/${encodeURIComponent(id)}${w ? `?w=${w}` : ''}`;
}

/**
 * <img> with a fixed aspect ratio, lazy loading, soft fade-in, a neutral placeholder while missing,
 * and retry with backoff on 404 (the image may still be uploading from another device).
 */
export function imgEl(ref, w, { cls = '', alt = '' } = {}) {
  const img = h('img', { class: ['att-img', cls], alt, loading: 'lazy', decoding: 'async', draggable: 'false', 'data-img': ref.id });
  if (pending.has(ref.id)) img.classList.add('uploading');
  let tries = 0;
  img.addEventListener('load', () => { img.classList.remove('missing'); img.classList.add('loaded'); });
  img.addEventListener('error', () => {
    img.classList.add('missing');
    if (tries > 8) return;
    const delay = Math.min(60000, 1500 * 2 ** tries++);
    setTimeout(() => {
      if (!img.isConnected) return;
      const base = imageURL(ref.id, w);
      img.src = base.startsWith('blob:') ? base : base + (base.includes('?') ? '&' : '?') + 'retry=' + tries;
    }, delay);
  });
  img.src = imageURL(ref.id, w);
  if (img.complete && img.naturalWidth) img.classList.add('loaded');
  return img;
}

/** Aspect ratio clamped so very tall/wide images don't take over a card. */
export function ratio(ref, min = 0.62, max = 2.4) {
  const r = ref.w && ref.h ? ref.w / ref.h : 4 / 3;
  return Math.min(max, Math.max(min, r));
}

/** Keep-style image block for cards: 1 full width, 2–4 mosaic, "+N" beyond. */
export function cardImages(it, cardWidth = 260) {
  const imgs = it.images || [];
  if (!imgs.length) return null;
  const n = Math.min(imgs.length, 4);
  const extra = imgs.length - 4;
  const tile = (ref, i, tileW) => {
    const cell = h('button', { class: 'att-tile', 'data-act': 'image', 'data-index': i, title: 'Open image', tabindex: '-1' }, imgEl(ref, thumbWidth(tileW)));
    if (i === 3 && extra > 0) cell.append(h('span', { class: 'att-more' }, `+${extra}`));
    return cell;
  };
  if (n === 1) {
    return h('div', { class: 'card-images one', style: { aspectRatio: String(ratio(imgs[0])) } }, tile(imgs[0], 0, cardWidth));
  }
  return h('div', { class: `card-images m${n}` }, imgs.slice(0, n).map((ref, i) => tile(ref, i, n === 3 && i === 0 ? cardWidth * 0.66 : cardWidth / 2)));
}

/** Small rounded thumbnail for list rows. */
export function rowThumb(it) {
  const imgs = it.images || [];
  if (!imgs.length) return null;
  return h('button', { class: 'row-thumb', 'data-act': 'image', 'data-index': 0, title: 'Open image', tabindex: '-1' },
    imgEl(imgs[0], 240),
    imgs.length > 1 ? h('span', { class: 'att-count' }, imgs.length) : null);
}

export function placeholderIcon() { return icon('image', 18); }
