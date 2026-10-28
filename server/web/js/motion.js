// Motion helpers. Everything is transform/opacity, 150–250ms, ease-out, and turns into a no-op
// when the user prefers reduced motion.

export const EASE = 'cubic-bezier(0.2, 0.8, 0.2, 1)';
export const EASE_IN = 'cubic-bezier(0.4, 0, 1, 1)';
const mq = matchMedia('(prefers-reduced-motion: reduce)');
// The OS setting, or the in-app override (Settings → Animations).
export const reduced = () => mq.matches || document.documentElement.dataset.motion === 'reduce';

export function anim(el, frames, opts = {}) {
  if (reduced() || !el?.animate) return null;
  return el.animate(frames, { duration: 200, easing: EASE, ...opts });
}

/** Run `done` after the animation (or right away without motion). */
export function after(a, done) {
  if (!a) { done(); return; }
  let called = false;
  const once = () => { if (!called) { called = true; done(); } };
  a.onfinish = once;
  a.oncancel = once;
  // Safety net: background tabs can stall animations; never leave an overlay or ghost behind.
  const t = a.effect?.getTiming?.() || {};
  setTimeout(once, (+t.duration || 300) + (+t.delay || 0) + 250);
}

export function fadeIn(el, { y = 6, duration = 200, delay = 0 } = {}) {
  return anim(el, [
    { opacity: 0, transform: `translateY(${y}px)` },
    { opacity: 1, transform: 'none' },
  ], { duration, delay, fill: 'backwards' });
}

/** Animate an element out, then call done (which usually removes or hides it). */
export function fadeOut(el, done, { y = 4, scale = 0.98, duration = 150 } = {}) {
  after(anim(el, [
    { opacity: 1, transform: 'none' },
    { opacity: 0, transform: `translateY(${y}px) scale(${scale})` },
  ], { duration, easing: EASE_IN, fill: 'forwards' }), done);
}

function cancelMotion(el) {
  for (const a of el.getAnimations?.() || []) if (a.id === 'flip' || a.id === 'enter') a.cancel();
}

export function measure(container, attr) {
  const m = new Map();
  for (const el of container.querySelectorAll(`[${attr}]`)) {
    if (el.closest('.fx-ghost')) continue;
    m.set(el.getAttribute(attr), { el, rect: el.getBoundingClientRect() });
  }
  return m;
}

/**
 * Keyed FLIP: measure keyed children, run `update` (which changes the DOM), then
 *  - moved elements glide from their old position,
 *  - new keys fade in with a small slide,
 *  - removed keys leave a fading "ghost" in their old place.
 * Elements whose position didn't change are left alone, so unchanged re-renders never animate.
 */
export function keyedFlip(container, attr, update, { enter = true, exit = true, before = null } = {}) {
  if (reduced()) { update(); return; }
  const first = before || measure(container, attr);
  update();
  const last = measure(container, attr);
  for (const { el } of last.values()) cancelMotion(el);
  const cRect = container.getBoundingClientRect();
  let entered = 0;
  for (const [k, { el }] of last) {
    const rect = el.getBoundingClientRect();
    const f = first.get(k);
    if (f) {
      const dx = f.rect.left - rect.left, dy = f.rect.top - rect.top;
      if (Math.abs(dx) > 0.5 || Math.abs(dy) > 0.5) {
        const a = el.animate([{ transform: `translate(${dx}px, ${dy}px)` }, { transform: 'none' }], { duration: 240, easing: EASE });
        a.id = 'flip';
      }
    } else if (enter && rect.height) {
      const a = el.animate([
        { opacity: 0, transform: 'translateY(-6px) scale(0.985)' },
        { opacity: 1, transform: 'none' },
      ], { duration: 220, easing: EASE, delay: Math.min(entered++ * 18, 120), fill: 'backwards' });
      a.id = 'enter';
    }
  }
  if (!exit) return;
  for (const [k, { el, rect }] of first) {
    if (last.has(k) || !rect.height) continue;
    ghost(container, el, rect, cRect);
  }
}

/** Leave a non-interactive copy of a removed element in place and let it fade and collapse. */
function ghost(container, el, rect, cRect) {
  const g = el.cloneNode(true);
  g.removeAttribute('id');
  g.querySelectorAll('[id]').forEach((n) => n.removeAttribute('id'));
  g.classList.add('fx-ghost');
  // A ghost must never be found as a real item (queries, keyboard, drag & drop).
  for (const n of [g, ...g.querySelectorAll('[data-k], [data-cid], [data-id]')]) {
    n.removeAttribute('data-k'); n.removeAttribute('data-cid'); n.removeAttribute('data-id');
  }
  Object.assign(g.style, {
    transformOrigin: 'top center',
    position: 'absolute', margin: '0', pointerEvents: 'none', zIndex: '0',
    left: rect.left - cRect.left + 'px', top: rect.top - cRect.top + 'px',
    width: rect.width + 'px', height: rect.height + 'px', gridRowEnd: 'auto',
  });
  container.append(g);
  const a = g.animate([
    { opacity: 1, transform: 'none' },
    { opacity: 0, transform: 'scaleY(0.6) translateY(-4px)' },
  ], { duration: 200, easing: EASE_IN, fill: 'forwards' });
  after(a, () => g.remove());
}

/** FLIP plain elements (by identity) around a layout change, e.g. a side panel opening. */
export function flipAround(elements, change) {
  if (reduced()) { change(); return; }
  const list = [...elements];
  const first = new Map(list.map((el) => [el, el.getBoundingClientRect()]));
  change();
  for (const el of list) {
    if (!el.isConnected) continue;
    cancelMotion(el);
    const f = first.get(el), r = el.getBoundingClientRect();
    const dx = f.left - r.left, dy = f.top - r.top;
    if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) continue;
    const a = el.animate([{ transform: `translate(${dx}px, ${dy}px)` }, { transform: 'none' }], { duration: 240, easing: EASE });
    a.id = 'flip';
  }
}

/** Number/text change in place: the new value slides in. */
export function bump(el) {
  return anim(el, [{ opacity: 0, transform: 'translateY(-5px)' }, { opacity: 1, transform: 'none' }], { duration: 200 });
}

/** Close a modal backdrop (palette, help, settings): fade the backdrop, shrink the sheet, then remove. */
export function closeLayer(back) {
  if (!back || back.classList.contains('closing')) return;
  back.classList.add('closing');
  const sheet = back.querySelector('.modal');
  anim(sheet, [{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'scale(0.97)' }], { duration: 140, easing: EASE_IN, fill: 'forwards' });
  after(anim(back, [{ opacity: 1 }, { opacity: 0 }], { duration: 150, easing: EASE_IN, fill: 'forwards' }), () => back.remove());
}

/** A small celebratory burst of confetti from a point (viewport coordinates). */
export function burst(x, y) {
  if (reduced()) return;
  const colors = ['#f9b928', '#f0a30a', '#ffd36b', '#30a46c', '#3e7fe0', '#e5484d', '#8e4ec6'];
  const layer = document.createElement('div');
  layer.className = 'burst';
  document.body.append(layer);
  const N = 26;
  let left = N;
  for (let i = 0; i < N; i++) {
    const p = document.createElement('i');
    const c = colors[i % colors.length];
    const w = 5 + Math.random() * 4, hgt = i % 3 ? w * 0.45 : w;
    Object.assign(p.style, { left: x + 'px', top: y + 'px', width: w + 'px', height: hgt + 'px', background: c, borderRadius: i % 3 ? '2px' : '50%' });
    layer.append(p);
    const ang = (Math.PI * 2 * i) / N + Math.random() * 0.4;
    const dist = 50 + Math.random() * 70;
    const dx = Math.cos(ang) * dist, dy = Math.sin(ang) * dist - 30;
    const rot = (Math.random() - 0.5) * 540;
    const a = p.animate([
      { transform: 'translate(-50%, -50%) scale(0.4) rotate(0deg)', opacity: 1 },
      { transform: `translate(calc(-50% + ${dx * 0.75}px), calc(-50% + ${dy * 0.75}px)) scale(1) rotate(${rot * 0.6}deg)`, opacity: 1, offset: 0.55 },
      { transform: `translate(calc(-50% + ${dx}px), calc(-50% + ${dy + 46}px)) scale(0.9) rotate(${rot}deg)`, opacity: 0 },
    ], { duration: 820 + Math.random() * 260, easing: 'cubic-bezier(0.15, 0.7, 0.3, 1)', fill: 'forwards' });
    after(a, () => { if (--left === 0) layer.remove(); });
  }
}
