// Capture bar: one line → item, with live natural-language token highlighting.
import { h, icon, fill } from './util.js';
import { parse } from './quickadd.js';
import { nowString, parseDue, relDay, fmtTime, describeRepeat, todayNum, fmtDay, weekday, fromDayNum } from './dates.js';
import { store, isEmpty } from './store.js';
import { newItem } from './model.js';
import { ui, hooks } from './ui.js';
import { toast } from './toast.js';
import { keyedFlip, anim, after } from './motion.js';
import { ingest, imageFiles } from './images.js';

let ta, hl, chips;
let ignore = new Set();
let last = null;

export function mountCapture() {
  const host = document.getElementById('capture');
  ta = h('textarea', {
    rows: 1,
    id: 'capture-input',
    placeholder: 'Add a note or task…',
    spellcheck: 'true',
    autocomplete: 'off',
    'aria-label': 'Capture',
    oninput: update,
    onkeydown: onKey,
    onscroll: () => { hl.scrollTop = ta.scrollTop; },
    onpaste: (e) => {
      const files = imageFiles(e.clipboardData?.files);
      if (files.length) { e.preventDefault(); createImageNote(files, { useText: true }); return; }
      setTimeout(update);
    },
  });
  hl = h('div', { class: 'capture-hl', 'aria-hidden': 'true' });
  chips = h('div', { class: 'capture-chips' });
  fill(host, h('div', { class: 'capture' },
    h('div', { class: 'capture-row' },
      icon('plus', 18),
      h('div', { class: 'capture-field' }, hl, ta),
      h('div', { class: 'capture-actions' },
        h('button', { class: 'icon-btn', title: 'New checklist', onclick: () => newBlank('list') }, icon('list', 17)),
        h('button', { class: 'icon-btn', title: 'New note in editor (Shift+N)', onclick: () => newBlank('note') }, icon('edit', 17)))),
    chips));
  update();
}

export function focusCapture(prefill) {
  if (prefill != null) { ta.value = prefill; ignore = new Set(); update(); }
  ta.focus();
  ta.setSelectionRange(ta.value.length, ta.value.length);
  document.getElementById('content').scrollTo({ top: 0, behavior: 'smooth' });
}

export function refreshCapture() { if (ta) update(); }

function context() {
  const v = ui.view;
  return {
    label: v.type === 'label' && !ui.query ? v.label : null,
    today: v.type === 'today' && !ui.query,
    day: v.type === 'upcoming' && !ui.query ? ui.upcomingDay : null,
  };
}

const WD3 = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
function dayName(day) {
  const rel = relDay(day);
  return rel === 'Today' || rel === 'Tomorrow' ? rel : `${WD3[weekday(day)]} ${fromDayNum(day).d}`;
}

function update() {
  const text = ta.value;
  const ctx = context();
  ta.placeholder = ctx.day != null ? `Add to ${dayName(ctx.day)}…` : 'Add a note or task…';
  // Drop ignore entries that no longer match the text.
  for (const k of [...ignore]) {
    const [s, ...rest] = k.split(':');
    if (text.slice(+s, +s + rest.join(':').length) !== rest.join(':')) ignore.delete(k);
  }
  last = text.trim() ? parse(text, nowString(), { ignore }) : null;
  // autosize
  ta.style.height = 'auto';
  ta.style.height = ta.scrollHeight + 'px';
  renderHighlight(text);
  renderChips();
}

function renderHighlight(text) {
  const parts = [];
  let pos = 0;
  for (const t of last?.tokens || []) {
    parts.push(text.slice(pos, t.s));
    parts.push(h('mark', { class: t.type }, text.slice(t.s, t.e)));
    pos = t.e;
  }
  parts.push(text.slice(pos) + '​');
  fill(hl, ...parts);
}

function dueText(due) {
  const p = parseDue(due);
  if (!p) return '';
  return relDay(p.day) + (p.min != null ? ' ' + fmtTime(p.min, ui.settings.h12) : '');
}

function renderChips() {
  const out = [];
  const ctx = context();
  if (last) {
    const literal = (types) => {
      for (const t of last.tokens.filter((x) => types.includes(x.type))) ignore.add(`${t.s}:${t.text}`);
      update();
      ta.focus();
    };
    if (last.due) out.push(h('button', { 'data-k': 'due', class: 'chip date', title: 'Click to keep as text', onclick: () => literal(['date', 'time', ...(last.repeat ? ['repeat'] : [])]) }, icon('calendar', 12), dueText(last.due), h('span', { class: 'x' }, icon('x', 11))));
    if (last.repeat) out.push(h('button', { 'data-k': 'repeat', class: 'chip repeat', title: 'Click to keep as text', onclick: () => literal(['repeat']) }, icon('repeat', 12), describeRepeat(last.repeat), h('span', { class: 'x' }, icon('x', 11))));
    for (const l of last.labels) {
      out.push(h('button', {
        'data-k': 'l:' + l, class: 'chip label', title: 'Click to keep as text',
        onclick: () => {
          const t = last.tokens.find((x) => x.type === 'label' && x.text.slice(1).toLowerCase() === l);
          if (t) ignore.add(`${t.s}:${t.text}`);
          update(); ta.focus();
        },
      }, '#' + l, h('span', { class: 'x' }, icon('x', 11))));
    }
    if (last.pinned) out.push(h('button', { 'data-k': 'pin', class: 'chip pin', onclick: () => literal(['pin']) }, icon('pin', 12), 'Pinned', h('span', { class: 'x' }, icon('x', 11))));
    if (last.kind === 'list') out.push(h('span', { 'data-k': 'list', class: 'chip list' }, icon('list', 12), `Checklist · ${last.checks.length}`));
    if (ctx.label && !last.labels.includes(ctx.label)) out.push(h('span', { 'data-k': 'ctxl', class: 'chip label ctx', title: 'Added because you are in this label' }, '#' + ctx.label));
    if (ctx.day != null && !last.due) out.push(h('span', { 'data-k': 'ctxd', class: 'chip date ctx', title: 'Added because this day is selected' }, icon('calendar', 12), dayName(ctx.day)));
    if (ctx.today && !last.due) out.push(h('span', { 'data-k': 'ctxt', class: 'chip date ctx', title: 'Added because you are in Today' }, icon('calendar', 12), 'Today'));
    out.push(h('span', { 'data-k': 'hint', class: 'capture-hint' }, h('kbd', null, 'Enter'), 'save', h('kbd', null, '⇧Enter'), 'new line', h('kbd', null, 'Esc'), 'clear'));
  }
  const wasEmpty = !chips.childElementCount;
  if (!out.length) {
    if (wasEmpty) return;
    after(anim(chips, [{ opacity: 1 }, { opacity: 0, transform: 'translateY(-4px) scale(0.98)' }], { duration: 120, fill: 'forwards' }), () => {
      if (!last) fill(chips);
      for (const a of chips.getAnimations()) a.cancel();
    });
    return;
  }
  keyedFlip(chips, 'data-k', () => fill(chips, ...out), { exit: false, enter: !wasEmpty });
  if (wasEmpty && out.length) anim(chips, [{ opacity: 0, transform: 'translateY(-4px) scale(0.98)' }, { opacity: 1, transform: 'none' }], { duration: 160 });
}

function onKey(e) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    if (e.ctrlKey || e.metaKey) save(true);
    else save(false);
    return;
  }
  if (e.key === 'Escape') {
    e.preventDefault();
    if (ta.value) { ta.value = ''; ignore = new Set(); update(); }
    else ta.blur();
    return;
  }
  if (e.key === 'Backspace' && ta.selectionStart === ta.selectionEnd && last) {
    // Backspace right after a recognised token turns it back into literal text.
    const pos = ta.selectionStart;
    const t = last.tokens.find((x) => x.e === pos);
    if (t && t.type !== 'label') {
      e.preventDefault();
      ignore.add(`${t.s}:${t.text}`);
      update();
    }
  }
  if (e.key === 'ArrowDown' && !ta.value.includes('\n') && ta.selectionStart === ta.value.length) {
    e.preventDefault();
    ta.blur();
    hooks.moveCursor(0);
  }
}

function save(openAfter) {
  const text = ta.value;
  if (!text.trim() || !last) return;
  const ctx = context();
  const r = last;
  const labels = new Set(r.labels);
  if (ctx.label) labels.add(ctx.label);
  let due = r.due;
  if (!due && ctx.today) due = fmtDay(todayNum());
  // A selected Upcoming day is the default; an explicit date or time in the text wins.
  if (!due && ctx.day != null) due = fmtDay(ctx.day);
  const item = newItem({
    kind: r.kind,
    title: r.title,
    checks: r.checks,
    labels: [...labels].sort(),
    due,
    repeat: r.repeat,
    pinned: r.pinned,
  });
  store.put(item);
  ta.value = '';
  ignore = new Set();
  update();
  ui.cursor = item.id;
  hooks.render();
  if (openAfter) hooks.openEditor(item.id, 'body');
  else {
    const where = whereLabel(item);
    if (where) toast(`Added to ${where}`, null, { timeout: 2500 });
  }
}

/** If the new item isn't visible in the current view, say where it went. */
function whereLabel(item) {
  const v = ui.view;
  const t = todayNum();
  const p = parseDue(item.due);
  if (v.type === 'today' && p && p.day > t) return `Upcoming · ${relDay(p.day)}`;
  if (v.type === 'upcoming' && ui.upcomingDay != null && p && p.day !== ui.upcomingDay) return relDay(p.day);
  if (v.type === 'upcoming' && ui.upcomingDay == null && (!p || p.day <= t)) return p ? 'Today' : 'Notes';
  if (v.type === 'upcoming' && !p) return 'Notes';
  if (['done', 'archive', 'trash'].includes(v.type)) return p && p.day <= t ? 'Today' : p ? 'Upcoming' : 'Notes';
  return '';
}

function newBlank(kind) {
  const ctx = context();
  const text = ta.value.trim();
  const r = text ? last : null;
  const item = newItem({
    kind,
    title: r?.title || '',
    labels: [...new Set([...(r?.labels || []), ...(ctx.label ? [ctx.label] : [])])].sort(),
    due: r?.due || (ctx.today ? fmtDay(todayNum()) : ctx.day != null ? fmtDay(ctx.day) : ''),
    repeat: r?.repeat || null,
    checks: kind === 'list' ? (r?.checks?.length ? r.checks : [{ text: '' }]) : [],
  });
  // An item with no text yet is only a draft; it's saved once something is typed.
  if (isEmpty(item)) store.putDraft(item); else store.put(item);
  ta.value = '';
  ignore = new Set();
  update();
  ui.cursor = item.id;
  hooks.render();
  hooks.openEditor(item.id, kind === 'list' ? 'check' : item.title ? 'body' : 'title');
}

export { newBlank };

/**
 * New note from image files (pasted into the bar, or dropped onto the page). The capture text, if any,
 * becomes its title/labels/date like a normal capture; the view context (label, day) applies too.
 */
export async function createImageNote(files, { useText = false, open = false } = {}) {
  const refs = await ingest(files);
  if (!refs.length) return null;
  const ctx = context();
  const r = useText && ta.value.trim() ? last : null;
  const labels = new Set(r?.labels || []);
  if (ctx.label) labels.add(ctx.label);
  let due = r?.due || '';
  if (!due && ctx.day != null) due = fmtDay(ctx.day);
  const item = newItem({
    title: r?.title || '', kind: r?.kind || 'note', checks: r?.checks || [], labels: [...labels].sort(),
    due, repeat: r?.repeat || null, pinned: !!r?.pinned, images: refs,
  });
  store.put(item);
  if (r) { ta.value = ''; ignore = new Set(); update(); }
  ui.cursor = item.id;
  hooks.render();
  if (open) hooks.openEditor(item.id, 'title');
  else toast(refs.length === 1 ? 'Added a note with an image' : `Added a note with ${refs.length} images`, null, { timeout: 2500 });
  return item;
}
