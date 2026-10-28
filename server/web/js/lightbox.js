// Full-screen image viewer: opens from the thumbnail's position, ←/→ through the item's images,
// click or wheel to zoom (drag to pan when zoomed), download, Esc to close.
import { h, icon } from './util.js';
import { imageURL } from './images.js';
import { reduced, EASE, after } from './motion.js';

let state = null;
export const lightboxOpen = () => !!state;

export function openLightbox(images, index = 0, fromEl = null) {
  if (!images?.length) return;
  if (state) close(true);
  const img = h('img', { class: 'lb-img', alt: '', draggable: 'false' });
  const stage = h('div', { class: 'lb-stage' }, img);
  const counter = h('span', { class: 'lb-count' });
  const dl = h('a', { class: 'lb-btn', title: 'Download original', download: '' }, icon('download', 18));
  const prev = h('button', { class: 'lb-nav prev', title: 'Previous (←)', onclick: (e) => { e.stopPropagation(); go(-1); } }, icon('chevleft', 26));
  const next = h('button', { class: 'lb-nav next', title: 'Next (→)', onclick: (e) => { e.stopPropagation(); go(1); } }, icon('chevright', 26));
  const root = h('div', { class: 'lightbox', role: 'dialog', 'aria-label': 'Image viewer' },
    h('div', { class: 'lb-back' }),
    stage,
    h('div', { class: 'lb-bar' },
      counter,
      h('span', { class: 'lb-spacer' }),
      h('button', { class: 'lb-btn', title: 'Zoom (click the image or scroll)', onclick: (e) => { e.stopPropagation(); toggleZoom(); } }, icon('zoomin', 18)),
      dl,
      h('button', { class: 'lb-btn', title: 'Close (Esc)', onclick: (e) => { e.stopPropagation(); close(); } }, icon('x', 20))),
    images.length > 1 ? prev : null,
    images.length > 1 ? next : null);
  document.body.append(root);
  document.body.classList.add('lb-lock');

  state = { images, index, root, img, stage, counter, dl, fromEl, zoom: 1, tx: 0, ty: 0 };
  show(index, true);

  root.addEventListener('click', (e) => { if (e.target === root || e.target.classList.contains('lb-back') || e.target === stage) close(); });
  img.addEventListener('click', (e) => { e.stopPropagation(); if (!state.dragged) toggleZoom(e); });
  stage.addEventListener('wheel', onWheel, { passive: false });
  img.addEventListener('pointerdown', onPointerDown);
  document.addEventListener('keydown', onKey, true);
}

function show(i, first = false) {
  const s = state;
  s.index = (i + s.images.length) % s.images.length;
  const ref = s.images[s.index];
  s.zoom = 1; s.tx = 0; s.ty = 0;
  apply(false);
  s.counter.textContent = s.images.length > 1 ? `${s.index + 1} / ${s.images.length}` : '';
  const url = imageURL(ref.id); // the original
  s.dl.href = url;
  s.dl.setAttribute('download', `jot-${ref.id}.${(ref.mime || 'image/jpeg').split('/')[1].replace('jpeg', 'jpg')}`);
  s.img.classList.remove('ready');
  // Intrinsic size from the ref, so the image has its final box before it loads (and can fly in).
  if (ref.w && ref.h) { s.img.width = ref.w; s.img.height = ref.h; } else { s.img.removeAttribute('width'); s.img.removeAttribute('height'); }
  s.img.onload = () => s.img.classList.add('ready');
  s.img.onerror = () => s.img.classList.add('ready', 'missing');
  s.img.src = url;
  if (first) flyIn(); else if (!reduced()) s.img.animate([{ opacity: 0, transform: 'scale(0.98)' }, { opacity: 1, transform: 'none' }], { duration: 180, easing: EASE });
}

/** Animate from the thumbnail rectangle to the image's final place. */
function flyIn() {
  const s = state;
  s.root.querySelector('.lb-back').animate?.([{ opacity: 0 }, { opacity: 1 }], { duration: reduced() ? 0 : 220, easing: EASE });
  const from = s.fromEl?.getBoundingClientRect();
  if (reduced() || !from || !from.width) return;
  requestAnimationFrame(() => {
    const to = s.img.getBoundingClientRect();
    if (!to.width) return;
    const sx = from.width / to.width, sy = from.height / to.height;
    s.img.animate([
      { transform: `translate(${from.left - to.left}px, ${from.top - to.top}px) scale(${sx}, ${sy})`, borderRadius: '12px', opacity: 0.6 },
      { transform: 'none', borderRadius: '4px', opacity: 1 },
    ], { duration: 280, easing: EASE });
  });
}

function close(instant = false) {
  const s = state;
  if (!s) return;
  state = null;
  document.removeEventListener('keydown', onKey, true);
  document.body.classList.remove('lb-lock');
  const done = () => s.root.remove();
  if (instant || reduced()) { done(); return; }
  const to = s.fromEl?.isConnected ? s.fromEl.getBoundingClientRect() : null;
  const r = s.img.getBoundingClientRect();
  s.root.querySelector('.lb-back').animate([{ opacity: 1 }, { opacity: 0 }], { duration: 200, easing: EASE, fill: 'forwards' });
  s.root.querySelectorAll('.lb-bar, .lb-nav').forEach((n) => n.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 120, fill: 'forwards' }));
  const frames = to && to.width && s.zoom === 1
    ? [{ transform: 'none', opacity: 1 }, { transform: `translate(${to.left - r.left}px, ${to.top - r.top}px) scale(${to.width / r.width}, ${to.height / r.height})`, opacity: 0.4 }]
    : [{ opacity: 1, transform: s.img.style.transform || 'none' }, { opacity: 0, transform: 'scale(0.96)' }];
  after(s.img.animate(frames, { duration: 230, easing: EASE, fill: 'forwards' }), done);
}

function go(d) { if (state && state.images.length > 1) show(state.index + d); }

// ---------- zoom & pan ----------
function apply(animate = true) {
  const s = state;
  const t = `translate(${s.tx}px, ${s.ty}px) scale(${s.zoom})`;
  if (animate && !reduced()) s.img.animate([{ transform: s.img.style.transform || 'none' }, { transform: t }], { duration: 180, easing: EASE });
  s.img.style.transform = t;
  s.root.classList.toggle('zoomed', s.zoom > 1);
}

function toggleZoom(e) {
  const s = state;
  if (s.zoom > 1) { s.zoom = 1; s.tx = 0; s.ty = 0; apply(); return; }
  s.zoom = 2.4;
  if (e) {
    // Zoom towards the clicked point.
    const r = s.img.getBoundingClientRect();
    const cx = e.clientX - (r.left + r.width / 2), cy = e.clientY - (r.top + r.height / 2);
    s.tx = -cx * (s.zoom - 1); s.ty = -cy * (s.zoom - 1);
    clampPan();
  }
  apply();
}

function onWheel(e) {
  e.preventDefault();
  const s = state;
  const before = s.zoom;
  s.zoom = Math.min(5, Math.max(1, s.zoom * (e.deltaY < 0 ? 1.15 : 1 / 1.15)));
  if (s.zoom === 1) { s.tx = 0; s.ty = 0; }
  else {
    const r = s.img.getBoundingClientRect();
    const cx = e.clientX - (r.left + r.width / 2), cy = e.clientY - (r.top + r.height / 2);
    const k = s.zoom / before;
    s.tx -= cx * (k - 1); s.ty -= cy * (k - 1);
    clampPan();
  }
  apply(false);
}

function clampPan() {
  const s = state;
  const base = { w: s.img.offsetWidth, h: s.img.offsetHeight };
  const mx = (base.w * s.zoom - base.w) / 2 + 40, my = (base.h * s.zoom - base.h) / 2 + 40;
  s.tx = Math.max(-mx, Math.min(mx, s.tx));
  s.ty = Math.max(-my, Math.min(my, s.ty));
}

function onPointerDown(e) {
  const s = state;
  s.dragged = false;
  if (s.zoom === 1) return;
  e.preventDefault();
  const sx = e.clientX, sy = e.clientY, tx = s.tx, ty = s.ty;
  const move = (ev) => {
    if (!state) return;
    if (Math.abs(ev.clientX - sx) + Math.abs(ev.clientY - sy) > 4) s.dragged = true;
    s.tx = tx + ev.clientX - sx; s.ty = ty + ev.clientY - sy;
    clampPan();
    apply(false);
  };
  const up = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); setTimeout(() => { if (state) state.dragged = false; }, 0); };
  window.addEventListener('pointermove', move);
  window.addEventListener('pointerup', up);
}

function onKey(e) {
  if (!state) return;
  const k = e.key;
  if (k === 'Escape') close();
  else if (k === 'ArrowLeft' || k === 'k' || k === 'h') go(-1);
  else if (k === 'ArrowRight' || k === 'j' || k === 'l' || k === ' ') go(1);
  else if (k === '+' || k === '=' || k === 'z') toggleZoom();
  else if (k === '0' || k === '-') { state.zoom = 1; state.tx = 0; state.ty = 0; apply(); }
  else return; // let Ctrl+C etc. through
  e.preventDefault();
  e.stopPropagation();
}
