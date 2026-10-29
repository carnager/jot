// Jot web app entry: boot, routing, keyboard model, selection, drag & drop, login, settings, reminders.
import { liveChecks } from './fieldmerge.js';
import { h, icon, lsGet, lsSet, modKey, fill, BRAND } from './util.js';
import { store } from './store.js';
import { ui, hooks, targets } from './ui.js';
import {
  renderSidebar, renderTopbar, renderView, renderStatus, applyStates, layoutGrids, observeLayout,
  scrollCursorIntoView, isListView, viewTitle, VIEWS,
} from './views.js';
import { mountCapture, focusCapture, refreshCapture, newBlank, createImageNote } from './capture.js';
import { openLightbox, lightboxOpen } from './lightbox.js';
import { fireTimes, leadText } from './alerts.js';
import { serverSettings, loadSettings, saveSettings as saveServerSettings, ntfyTest, deviceZone, zoneList } from './settings.js';
import { ingest, resumeUploads, uploadNow, imageURL, imageFiles } from './images.js';
import { mountEditor, openEditor, closeEditor, itemMenu, editorHasFocus } from './editor.js';
import { datePop, labelPop, colorPop, closePop, popOpen, anchorFor, promptPop } from './popovers.js';
import { openPalette, paletteOpen } from './palette.js';
import { toast, undoLast } from './toast.js';
import * as act from './actions.js';
import { overdueCount, purgeTrash, normLabel, todayItems } from './model.js';
import { parseDue, fmtDue, todayNum, dueToDate, nowString } from './dates.js';
import { reduced as reducedMotion, closeLayer, fadeOut, burst } from './motion.js';

// ======================= settings / theme =======================

Object.assign(ui.settings, lsGet('jot.settings', {}));
if (serverSettings().allDayTime) ui.settings.allDayTime = serverSettings().allDayTime; // §2c: synced value wins
const saveSettings = () => lsSet('jot.settings', ui.settings);

function applyTheme() {
  if (ui.settings.motion === 'reduce') document.documentElement.dataset.motion = 'reduce';
  else delete document.documentElement.dataset.motion;
  const t = ui.settings.theme;
  if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t;
  else delete document.documentElement.dataset.theme;
}
applyTheme();

function cycleTheme() {
  const dark = ui.settings.theme === 'dark' || (ui.settings.theme === 'system' && matchMedia('(prefers-color-scheme: dark)').matches);
  ui.settings.theme = dark ? 'light' : 'dark';
  saveSettings();
  applyTheme();
}

// ======================= render loop =======================

let frame = 0;
let lastCursorIndex = 0;
function render() {
  if (frame) return;
  frame = requestAnimationFrame(() => {
    frame = 0;
    renderSidebar();
    renderTopbar();
    renderView();
    fixCursor();
    applyStates();
    renderBulkbar();
    renderStatus();
    updateTitle();
  });
}

function renderNow() {
  if (frame) { cancelAnimationFrame(frame); frame = 0; }
  frame = 0;
  render();
}

function fixCursor() {
  // Keep the cursor on a visible item; if it vanished, stay at the same position.
  for (const id of [...ui.selected]) if (!ui.order.includes(id)) ui.selected.delete(id);
  if (ui.cursor && ui.order.includes(ui.cursor)) { lastCursorIndex = ui.order.indexOf(ui.cursor); return; }
  if (ui.cursor) ui.cursor = ui.order[Math.min(lastCursorIndex, ui.order.length - 1)] || null;
}

// ======================= routing =======================

function parseHash() {
  const m = /^#\/(\w+)(?:\/(.+))?$/.exec(location.hash);
  if (!m) return null;
  if (m[1] === 'item' && m[2]) return { type: 'item', id: decodeURIComponent(m[2]) };
  if (m[1] === 'label' && m[2]) return { type: 'label', label: decodeURIComponent(m[2]) };
  if (VIEWS[m[1]]) return { type: m[1] };
  return null;
}

function hashOf(v) {
  return v.type === 'label' ? `#/label/${encodeURIComponent(v.label)}` : `#/${v.type}`;
}

function go(v, { push = true } = {}) {
  ui.view = v;
  ui.query = '';
  ui.upcomingDay = null;
  const s = document.getElementById('search');
  if (s) s.value = '';
  ui.selected.clear();
  ui.cursor = null;
  lastCursorIndex = 0;
  if (push && location.hash !== hashOf(v)) history.pushState(null, '', hashOf(v));
  lsSet('jot.lastView', v);
  document.getElementById('app').classList.remove('nav-open');
  document.getElementById('content').scrollTop = 0;
  refreshCapture();
  renderNow();
}

window.addEventListener('popstate', () => {
  const v = parseHash();
  if (v?.type === 'item') openFromLink(v.id);
  else if (v) go(v, { push: false });
});

/** The view an item naturally lives in. */
function viewFor(it) {
  const p = parseDue(it.due);
  const t = todayNum();
  if (it.trashedAt) return { type: 'trash' };
  if (it.done) return { type: 'done' };
  if (it.archived) return { type: 'archive' };
  if (p && p.day <= t) return { type: 'today' };
  if (p) return { type: 'upcoming' };
  return { type: 'notes' };
}

/** #/item/<id> (ntfy click URLs): open the item in the editor, in a view that shows it. */
function openFromLink(id) {
  const open = () => {
    const it = store.get(id);
    if (!it || it.deleted) { go(lsGet('jot.lastView', null) || { type: 'notes' }, { push: false }); history.replaceState(null, '', hashOf(ui.view)); toast('That item no longer exists'); return true; }
    go(viewFor(it), { push: false });
    history.replaceState(null, '', hashOf(ui.view));
    ui.cursor = id;
    renderNow();
    openEditor(id, null);
    requestAnimationFrame(() => scrollCursorIntoView());
    return true;
  };
  if (store.get(id)) { open(); return; }
  // Not here yet (fresh device, or the link came before the sync): wait for the next sync.
  const off = store.on('synced', () => { off(); open(); });
  store.sync();
}

/** Upcoming: show only one day (null = all days). */
function selectDay(day) {
  if (ui.view.type !== 'upcoming') go({ type: 'upcoming' });
  const t = todayNum();
  ui.upcomingDay = day == null ? null : Math.max(t, Math.min(t + 13, day));
  ui.selected.clear();
  ui.cursor = null;
  document.getElementById('content').scrollTo({ top: 0, behavior: reducedMotion() ? 'auto' : 'smooth' });
  refreshCapture();
  renderNow();
}

function search(q) {
  ui.query = q.trim();
  ui.selected.clear();
  ui.cursor = null;
  render();
}

// ======================= cursor & selection =======================

function moveCursor(delta, extend = false) {
  if (!ui.order.length) return;
  let i = ui.cursor ? ui.order.indexOf(ui.cursor) : -1;
  if (i < 0) i = delta >= 0 ? 0 : ui.order.length - 1;
  else i = Math.max(0, Math.min(ui.order.length - 1, i + delta));
  setCursor(ui.order[i], extend);
}

/** Spatial move in card grids (up/down keep the column). */
function moveSpatial(dir) {
  if (isListView() || !ui.cursor) return moveCursor(dir === 'down' ? 1 : -1);
  const nodes = [...document.querySelectorAll('#view .card[data-id]')];
  const curNode = nodes.find((n) => n.dataset.id === ui.cursor);
  if (!curNode) return moveCursor(dir === 'down' ? 1 : -1);
  const r = curNode.getBoundingClientRect();
  const cx = r.left + r.width / 2;
  let best = null, bestScore = Infinity;
  for (const n of nodes) {
    if (n === curNode) continue;
    const q = n.getBoundingClientRect();
    const dy = dir === 'down' ? q.top - r.bottom : r.top - q.bottom;
    if (dy < -4) continue;
    const dx = Math.abs(q.left + q.width / 2 - cx);
    const score = dx * 3 + dy;
    if (score < bestScore) { bestScore = score; best = n; }
  }
  if (best) setCursor(best.dataset.id);
}

function setCursor(id, extend = false) {
  if (extend) {
    if (!ui.anchor) ui.anchor = ui.cursor || id;
    const a = ui.order.indexOf(ui.anchor), b = ui.order.indexOf(id);
    ui.selected = new Set(ui.order.slice(Math.min(a, b), Math.max(a, b) + 1));
  } else {
    ui.anchor = id;
  }
  ui.cursor = id;
  applyStates();
  renderBulkbar();
  scrollCursorIntoView();
}

function toggleSelect(id) {
  if (ui.selected.has(id)) ui.selected.delete(id); else ui.selected.add(id);
  if (ui.cursor && !ui.selected.size) ui.anchor = id;
  ui.cursor = id;
  applyStates();
  renderBulkbar();
}

function selectAll() {
  ui.selected = new Set(ui.order);
  applyStates();
  renderBulkbar();
}

function clearSelection() {
  ui.selected.clear();
  applyStates();
  renderBulkbar();
}

function reveal(id) {
  const it = store.get(id);
  if (!it) return;
  // Pick a view that shows it.
  const t = todayNum();
  const p = parseDue(it.due);
  let v;
  if (it.trashedAt) v = { type: 'trash' };
  else if (it.done) v = { type: 'done' };
  else if (it.archived) v = { type: 'archive' };
  else if (p && p.day <= t && isListView()) v = { type: 'today' };
  else if (p && p.day > t && isListView()) v = { type: 'upcoming' };
  else if (ui.view.type === 'label' && it.labels.includes(ui.view.label)) v = ui.view;
  else v = isListView() && p ? ui.view : { type: 'notes' };
  if (v.type !== ui.view.type || v.label !== ui.view.label || ui.query) go(v);
  ui.cursor = id;
  renderNow();
  openEditor(id, 'body');
  requestAnimationFrame(() => scrollCursorIntoView());
}

// ======================= item actions from keys =======================

const completing = new Set();
/**
 * Completing plays in two beats: the checkbox fills and the title is struck through right away,
 * then the change is committed and the item leaves (ghost fade + neighbours glide up).
 */
function completeAnimated(ids) {
  ids = ids.filter((id) => !completing.has(id));
  if (!ids.length) return;
  const reopen = ids.every((id) => store.get(id)?.done);
  const nodes = ids.map((id) => document.querySelector(`#view [data-id="${CSS.escape(id)}"]`)).filter(Boolean);
  if (reopen || !nodes.length || reducedMotion()) { act.toggleComplete(ids); return; }
  nodes.forEach((n) => n.classList.add('completing'));
  ids.forEach((id) => completing.add(id));
  // Finishing the last thing on Today's list earns a little celebration.
  const left = todayItems();
  const remaining = [...left.overdue, ...left.today].map((i) => i.id);
  const lastOfDay = ui.view.type === 'today' && !ui.query && remaining.length > 0 && remaining.every((id) => ids.includes(id));
  const origin = nodes[0].querySelector('.check')?.getBoundingClientRect();
  setTimeout(() => {
    ids.forEach((id) => completing.delete(id));
    act.toggleComplete(ids);
    if (lastOfDay && origin) burst(origin.left + origin.width / 2, origin.top + origin.height / 2);
  }, 260);
}

function tomorrow(ids) {
  const t = todayNum() + 1;
  const items = ids.map((id) => store.get(id)).filter(Boolean);
  // Keep each item's time of day; one undo for all.
  const byTime = new Map();
  for (const it of items) {
    const p = parseDue(it.due);
    const due = fmtDue(t, p?.min ?? null);
    if (!byTime.has(due)) byTime.set(due, []);
    byTime.get(due).push(it.id);
  }
  for (const [due, group] of byTime) act.setDue(group, due);
}

function snoozeHour(ids) {
  const d = new Date(Date.now() + 3600000);
  act.snooze(ids, nowString(d));
  toast('Snoozed 1 hour');
}

function moveInOrder(dir) {
  if (!['notes', 'label'].includes(ui.view.type) || ui.query || !ui.cursor) return;
  const it = store.get(ui.cursor);
  const i = ui.order.indexOf(ui.cursor);
  const j = i + dir;
  const other = store.get(ui.order[j]);
  if (!other || other.pinned !== it.pinned) return;
  const a = it.sort, b = other.sort;
  store.update(it.id, { sort: b });
  store.update(other.id, { sort: a === b ? b - dir : a });
}

const ACTIONS = {
  x: (ids) => completeAnimated(ids),
  ' ': (ids) => completeAnimated(ids),
  d: (ids) => datePop(ids, anchorFor(ids)),
  t: (ids) => tomorrow(ids),
  T: (ids) => act.setDue(ids, fmtDue(todayNum(), null)),
  s: (ids) => snoozeHour(ids),
  p: (ids) => act.togglePin(ids),
  a: (ids) => act.toggleArchive(ids),
  e: (ids) => act.toggleArchive(ids),
  l: (ids) => labelPop(ids, anchorFor(ids)),
  '#': (ids) => labelPop(ids, anchorFor(ids)),
  c: (ids) => colorPop(ids, anchorFor(ids)),
  '.': (ids) => itemMenu(ids[0], anchorFor(ids)),
  Delete: (ids) => {
    if (ids.every((id) => store.get(id)?.trashedAt)) act.deleteForever(ids);
    else { act.trash(ids); if (ids.includes(ui.openId)) closeEditor(); }
  },
  Backspace: (ids) => ACTIONS.Delete(ids),
};

function runKey(key) {
  const ids = targets();
  if (!ids.length || !ACTIONS[key]) return false;
  ACTIONS[key](ids);
  return true;
}

// ======================= keyboard =======================

let gPending = 0;

function isTyping(el) {
  return el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable);
}

function modalOpen() {
  return paletteOpen() || popOpen() || !!document.querySelector('.modal-back:not(.closing)');
}

document.addEventListener('keydown', (e) => {
  const mod = e.ctrlKey || e.metaKey;
  if (!document.getElementById('login').hidden || lightboxOpen()) return;

  if (mod && e.key.toLowerCase() === 'k') {
    e.preventDefault();
    closePop();
    if (!paletteOpen()) openPalette();
    return;
  }
  if (paletteOpen() || popOpen()) return;
  const modal = document.querySelector('.modal-back:not(.closing)');
  if (modal) {
    if (e.key === 'Escape') { e.preventDefault(); closeLayer(modal); }
    return;
  }

  const typing = isTyping(document.activeElement);

  if (e.key === 'Escape') {
    if (editorHasFocus() || (ui.openId && !typing)) {
      e.preventDefault();
      const id = ui.openId;
      closeEditor();
      if (id) { ui.cursor = id; applyStates(); }
      document.getElementById('content').focus({ preventScroll: true });
      return;
    }
    if (typing) return; // capture/search handle their own Esc
    if (ui.selected.size) { clearSelection(); return; }
    if (ui.view.type === 'upcoming' && ui.upcomingDay != null && !ui.query) { selectDay(null); return; }
    if (ui.query) { document.getElementById('search').value = ''; search(''); return; }
    document.getElementById('app').classList.remove('nav-open');
    return;
  }

  if (typing) {
    // Ctrl+Enter in the editor's title/body completes the item.
    if (mod && e.key === 'Enter' && editorHasFocus() && ui.openId && document.activeElement.classList.contains('ed-title')) {
      e.preventDefault();
      act.toggleComplete([ui.openId]);
    }
    return;
  }

  if (mod && e.key.toLowerCase() === 'z') { e.preventDefault(); undoLast(); return; }
  if (mod && e.key.toLowerCase() === 'a') { e.preventDefault(); selectAll(); return; }
  if (mod || e.altKey && !['ArrowUp', 'ArrowDown', 'j', 'k'].includes(e.key)) return;

  if (gPending) {
    clearTimeout(gPending);
    gPending = 0;
    const map = { t: 'today', u: 'upcoming', n: 'notes', d: 'done', a: 'archive', r: 'trash', i: 'notes' };
    if (map[e.key]) { e.preventDefault(); go({ type: map[e.key] }); return; }
    if (e.key === 'l') { e.preventDefault(); openPalette('#'); return; }
    return;
  }

  const k = e.key;
  if (e.altKey && (k === 'ArrowUp' || k === 'k')) { e.preventDefault(); moveInOrder(-1); return; }
  if (e.altKey && (k === 'ArrowDown' || k === 'j')) { e.preventDefault(); moveInOrder(1); return; }

  switch (k) {
    case 'g': gPending = setTimeout(() => { gPending = 0; }, 1200); e.preventDefault(); return;
    case '?': e.preventDefault(); showKeys(); return;
    case '[': case ']':
      if (ui.view.type === 'upcoming' && !ui.query) {
        e.preventDefault();
        const t = todayNum();
        const cur = ui.upcomingDay ?? (k === ']' ? t : t + 1);
        selectDay(cur + (k === ']' ? 1 : -1));
      }
      return;
    case '/': e.preventDefault(); focusSearch(); return;
    case 'q': case 'n': e.preventDefault(); focusCapture(); return;
    case 'N': e.preventDefault(); newBlank('note'); return;
    case 'L': e.preventDefault(); newBlank('list'); return;
    case 'j': case 'J': e.preventDefault(); moveCursor(1, e.shiftKey || k === 'J'); return;
    case 'k': case 'K': e.preventDefault(); moveCursor(-1, e.shiftKey || k === 'K'); return;
    case 'ArrowDown': e.preventDefault(); if (e.shiftKey) moveCursor(1, true); else moveSpatial('down'); return;
    case 'ArrowUp': e.preventDefault(); if (e.shiftKey) moveCursor(-1, true); else moveSpatial('up'); return;
    case 'ArrowRight': if (!isListView()) { e.preventDefault(); moveCursor(1, e.shiftKey); } return;
    case 'ArrowLeft': if (!isListView()) { e.preventDefault(); moveCursor(-1, e.shiftKey); } return;
    case 'Home': e.preventDefault(); if (ui.order.length) setCursor(ui.order[0]); return;
    case 'End': e.preventDefault(); if (ui.order.length) setCursor(ui.order[ui.order.length - 1]); return;
    case 'Enter': case 'o':
      if (ui.cursor) { e.preventDefault(); openEditor(ui.cursor, 'body'); }
      return;
    case 'X':
      // Shift+X toggles selection of the cursor item.
      if (ui.cursor) { e.preventDefault(); toggleSelect(ui.cursor); }
      return;
    default:
      if (ACTIONS[k] && targets().length) {
        e.preventDefault();
        runKey(k);
      }
  }
});

function focusSearch() {
  const s = document.getElementById('search');
  s.focus();
  s.select();
}

// ======================= mouse =======================

function onViewClick(e) {
  const itemEl = e.target.closest('[data-id]');
  const actEl = e.target.closest('[data-act]');
  if (!itemEl) return;
  const id = itemEl.dataset.id;
  if (actEl && itemEl.contains(actEl)) {
    e.stopPropagation();
    const a = actEl.dataset.act;
    const ids = ui.selected.has(id) ? [...ui.selected] : [id];
    if (!ui.selected.has(id)) { ui.cursor = id; applyStates(); }
    switch (a) {
      case 'complete': completeAnimated(ids); break;
      case 'pin': act.togglePin(ids); break;
      case 'date': datePop(ids, actEl); break;
      case 'tomorrow': tomorrow(ids); break;
      case 'color': colorPop(ids, actEl); break;
      case 'label': labelPop(ids, actEl); break;
      case 'archive': act.toggleArchive(ids); break;
      case 'more': itemMenu(id, actEl); break;
      case 'restore': act.restore(ids); break;
      case 'delforever': act.deleteForever(ids); break;
      case 'golabel': go({ type: 'label', label: actEl.dataset.label }); break;
      case 'image': openLightbox(store.get(id)?.images, +actEl.dataset.index || 0, actEl); break;
      case 'check': {
        const cid = actEl.dataset.check;
        store.update(id, (it) => ({ checks: liveChecks(it).map((c) => (c.id === cid ? { ...c, done: !c.done } : c)) }));
        break;
      }
    }
    return;
  }
  if (e.target.closest('a')) return;
  if (e.shiftKey) { e.preventDefault(); setCursor(id, true); return; }
  if (e.ctrlKey || e.metaKey) { e.preventDefault(); toggleSelect(id); return; }
  if (ui.selected.size) clearSelection();
  ui.cursor = id;
  ui.anchor = id;
  openEditor(id, null);
  applyStates();
}

// ---------- drag & drop ----------
let dragIds = null;

function setupDnD() {
  const view = document.getElementById('view');
  view.addEventListener('dragstart', (e) => {
    const node = e.target.closest?.('[data-id]');
    if (!node || e.target.closest('.ed-check')) return;
    const id = node.dataset.id;
    dragIds = ui.selected.has(id) ? [...ui.selected] : [id];
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', store.get(id)?.title || '');
    requestAnimationFrame(() => dragIds.forEach((d) => view.querySelector(`[data-id="${CSS.escape(d)}"]`)?.classList.add('dragging')));
  });
  view.addEventListener('dragend', () => {
    dragIds = null;
    document.querySelectorAll('.dragging, .drop-before, .nav-item.drop, .drop-target').forEach((n) => n.classList.remove('dragging', 'drop-before', 'drop', 'drop-target'));
  });
  view.addEventListener('dragover', (e) => {
    if (!dragIds || !['notes', 'label'].includes(ui.view.type) || ui.query) return;
    const card = e.target.closest('.card[data-id]');
    if (!card || dragIds.includes(card.dataset.id)) return;
    e.preventDefault();
    view.querySelectorAll('.drop-before').forEach((n) => n !== card && n.classList.remove('drop-before'));
    card.classList.add('drop-before');
  });
  view.addEventListener('drop', (e) => {
    const card = e.target.closest('.card[data-id]');
    if (!dragIds || !card) return;
    e.preventDefault();
    reorderBefore(dragIds, card.dataset.id);
  });

  // Day pills in the Upcoming header accept items: reschedule, keeping the time of day.
  const hero = document.getElementById('hero');
  hero.addEventListener('dragover', (e) => {
    const pill = e.target.closest('[data-drop-day]');
    if (!dragIds || !pill) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    hero.querySelectorAll('.drop-target').forEach((n) => n !== pill && n.classList.remove('drop-target'));
    pill.classList.add('drop-target');
  });
  hero.addEventListener('dragleave', (e) => {
    const pill = e.target.closest('[data-drop-day]');
    if (pill && !pill.contains(e.relatedTarget)) pill.classList.remove('drop-target');
  });
  hero.addEventListener('drop', (e) => {
    const pill = e.target.closest('[data-drop-day]');
    if (!dragIds || !pill) return;
    e.preventDefault();
    pill.classList.remove('drop-target');
    act.moveToDay(dragIds, +pill.dataset.dropDay);
  });

  const side = document.getElementById('sidebar');
  side.addEventListener('dragover', (e) => {
    const t = e.target.closest('[data-drop]');
    if (!dragIds || !t) return;
    e.preventDefault();
    side.querySelectorAll('.drop').forEach((n) => n !== t && n.classList.remove('drop'));
    t.classList.add('drop');
  });
  side.addEventListener('dragleave', (e) => {
    const t = e.target.closest('[data-drop]');
    if (t && !t.contains(e.relatedTarget)) t.classList.remove('drop');
  });
  side.addEventListener('drop', (e) => {
    const t = e.target.closest('[data-drop]');
    if (!dragIds || !t) return;
    e.preventDefault();
    const ids = dragIds;
    const d = t.dataset.drop;
    if (d.startsWith('label:')) {
      const l = d.slice(6);
      store.updateMany(ids, (it) => ({ labels: [...new Set([...(it.labels || []), l])].sort() }));
      toast(`Added #${l}`);
    } else if (d === 'today') act.setDue(ids, fmtDue(todayNum(), null));
    else if (d === 'upcoming') act.setDue(ids, fmtDue(todayNum() + 1, null));
    else if (d === 'archive') { if (!ids.every((id) => store.get(id)?.archived)) act.toggleArchive(ids); }
    else if (d === 'trash') act.trash(ids);
    else if (d === 'done') { if (!ids.every((id) => store.get(id)?.done)) act.toggleComplete(ids); }
    else if (d === 'notes') store.updateMany(ids, { archived: false, trashedAt: 0 });
    t.classList.remove('drop');
  });
}

// ---------- image files dropped from the desktop ----------
const hasFiles = (e) => [...(e.dataTransfer?.types || [])].includes('Files');

function setupFileDrop() {
  const overlay = h('div', { class: 'drop-overlay', hidden: true },
    h('div', { class: 'drop-card' }, icon('imageplus', 28), h('b', null, 'Drop images to create a note'), h('span', null, 'or drop them onto a card to add them there')));
  document.body.append(overlay);
  let hideTimer = 0;
  const view = document.getElementById('view');
  document.addEventListener('dragover', (e) => {
    if (!hasFiles(e) || dragIds) return;
    e.preventDefault();
    clearTimeout(hideTimer);
    const card = e.target.closest?.('#view .card[data-id], #view .row[data-id]');
    view.querySelectorAll('.file-over').forEach((n) => n !== card && n.classList.remove('file-over'));
    if (card) { card.classList.add('file-over'); e.dataTransfer.dropEffect = 'copy'; }
    const overEditor = e.target.closest?.('#editor');
    overlay.hidden = !!card || !!overEditor;
    hideTimer = setTimeout(() => { overlay.hidden = true; view.querySelectorAll('.file-over').forEach((n) => n.classList.remove('file-over')); }, 150);
  });
  document.addEventListener('drop', async (e) => {
    if (!hasFiles(e) || dragIds || e.defaultPrevented) return;
    e.preventDefault();
    overlay.hidden = true;
    const card = e.target.closest?.('#view .card[data-id], #view .row[data-id]');
    view.querySelectorAll('.file-over').forEach((n) => n.classList.remove('file-over'));
    if (card) {
      const id = card.dataset.id;
      const refs = await ingest(e.dataTransfer.files);
      if (refs.length) { act.addImages(id, refs); toast(refs.length === 1 ? 'Image added' : `${refs.length} images added`); }
      return;
    }
    createImageNote(e.dataTransfer.files, { open: true });
  });
}

function reorderBefore(ids, targetId) {
  const target = store.get(targetId);
  const i = ui.order.indexOf(targetId);
  // previous neighbour in the same pinned group (higher sort = earlier)
  let prev = null;
  for (let j = i - 1; j >= 0; j--) {
    const p = store.get(ui.order[j]);
    if (ids.includes(p.id)) continue;
    if (p.pinned === target.pinned) prev = p;
    break;
  }
  const hi = prev ? prev.sort : target.sort + 2000 * ids.length;
  const lo = target.sort;
  const step = (hi - lo) / (ids.length + 1);
  // keep the dragged items' relative order
  const ordered = [...ids].sort((a, b) => ui.order.indexOf(a) - ui.order.indexOf(b));
  ordered.forEach((id, k) => store.update(id, { sort: hi - step * (k + 1), pinned: target.pinned }));
}

// ======================= bulk bar =======================

function renderBulkbar() {
  const bar = document.getElementById('bulkbar');
  const n = ui.selected.size;
  if (!n) {
    if (!bar.hidden && !bar.classList.contains('leaving')) {
      bar.classList.add('leaving');
      fadeOut(bar, () => { if (bar.classList.contains('leaving')) { bar.hidden = true; bar.classList.remove('leaving'); } }, { y: 8, scale: 0.98 });
    }
    return;
  }
  const wasHidden = bar.hidden || bar.classList.contains('leaving');
  bar.classList.remove('leaving');
  for (const a of bar.getAnimations()) a.cancel();
  const ids = () => [...ui.selected];
  const inTrash = ids().every((id) => store.get(id)?.trashedAt);
  const b = (ic, title, fn) => h('button', { class: 'icon-btn', title, onclick: (e) => fn(e.currentTarget) }, icon(ic, 17));
  fill(bar, 
    h('span', { class: 'n' }, `${n} selected`),
    ...(inTrash ? [
      b('restore', 'Restore', () => act.restore(ids())),
      b('trash', 'Delete forever', () => act.deleteForever(ids())),
    ] : [
      b('done', 'Complete (x)', () => completeAnimated(ids())),
      b('calendar', 'Date (d)', (el) => datePop(ids(), el)),
      b('forward', 'Tomorrow (t)', () => tomorrow(ids())),
      b('tag', 'Labels (l)', (el) => labelPop(ids(), el)),
      b('palette', 'Color (c)', (el) => colorPop(ids(), el)),
      b('pin', 'Pin (p)', () => act.togglePin(ids())),
      b('archive', 'Archive (a)', () => act.toggleArchive(ids())),
      b('trash', 'Trash (Del)', () => act.trash(ids())),
    ]),
    h('span', { class: 'sep' }),
    b('x', 'Clear selection (Esc)', () => clearSelection()));
  bar.hidden = false;
  if (wasHidden) { bar.style.animation = 'none'; void bar.offsetWidth; bar.style.animation = ''; }
}

// ======================= title, favicon, reminders =======================

let lastBadge = -1;
function updateTitle() {
  const n = overdueCount();
  const base = ui.query ? 'Search' : viewTitle();
  document.title = (n ? `(${n}) ` : '') + `${base} · Jot`;
  if (n === lastBadge) return;
  lastBadge = n;
  const dot = n ? '<circle cx="52" cy="12" r="11" fill="#e5483b" stroke="#fff" stroke-width="3"/>' : '';
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="#f5b81c"/><path d="M20 33.5 28 41.5 45 23" fill="none" stroke="#2b2100" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"/>${dot}</svg>`;
  document.getElementById('favicon').href = 'data:image/svg+xml,' + encodeURIComponent(svg);
}

const NOTIFIED_KEY = 'jot.notified';
function checkReminders() {
  if (!ui.settings.notifications || !('Notification' in window) || Notification.permission !== 'granted') return;
  const now = Date.now();
  const [hh, mm] = (ui.settings.allDayTime || '09:00').split(':').map(Number);
  const allDayMin = hh * 60 + mm;
  const seen = new Set(lsGet(NOTIFIED_KEY, []));
  let changed = false;
  for (const it of store.all()) {
    for (const f of fireTimes(it, allDayMin)) {
      const t = f.at.getTime();
      if (t > now || t < now - 60 * 60000) continue;
      const key = `${it.id}|${t}`;
      if (seen.has(key)) continue;
      seen.add(key);
      changed = true;
      const preview = it.kind === 'list'
        ? liveChecks(it).filter((c) => !c.done).slice(0, 4).map((c) => '☐ ' + c.text).join('\n')
        : (it.body || '').slice(0, 140);
      // An early alert says how far away the item is ("in 30 min", "tomorrow 09:00").
      const lead = f.m ? leadText(new Date(t + f.m * 60000)) : '';
      const title = it.title || preview.split('\n')[0] || 'Reminder';
      const body = [lead && lead[0].toUpperCase() + lead.slice(1), it.title ? preview : ''].filter(Boolean).join('\n');
      try {
        const n = new Notification(title, { body, tag: `${it.id}|${t}`, icon: '/icon.svg', renotify: true });
        n.onclick = () => { window.focus(); reveal(it.id); n.close(); };
      } catch { /* some browsers only allow notifications from a service worker */ }
    }
  }
  if (changed) lsSet(NOTIFIED_KEY, [...seen].slice(-500));
}

// ======================= modals =======================

function modal(content, { width = 600 } = {}) {
  document.querySelector('.modal-back')?.remove();
  const back = h('div', { class: 'modal-back', onmousedown: (e) => { if (e.target === back) closeLayer(back); } },
    h('div', { class: 'modal', role: 'dialog', style: { maxWidth: width + 'px' } }, content));
  document.getElementById('layer').append(back);
  return back;
}

function showKeys() {
  const K = (keys, label) => h('div', { class: 'key-row' }, h('span', null, label), h('span', { class: 'keys' }, keys.map((k) => h('kbd', null, k))));
  modal(h('div', { class: 'modal-body' },
    h('h2', null, 'Keyboard shortcuts'),
    h('p', { class: 'lead' }, 'Everything is one or two keys away. Actions apply to the selection, or to the focused item.'),
    h('div', { class: 'keys-grid' },
      h('h4', null, 'Anywhere'),
      K(['q'], 'Capture (also n)'), K([modKey, 'K'], 'Command palette'),
      K(['/'], 'Search'), K(['?'], 'This help'),
      K(['⇧', 'N'], 'New note in editor'), K(['⇧', 'L'], 'New checklist'),
      K([modKey, 'Z'], 'Undo'), K(['Esc'], 'Close / clear'),
      h('h4', null, 'Go to'),
      K(['g', 't'], 'Today'), K(['g', 'u'], 'Upcoming'),
      K(['g', 'n'], 'Notes'), K(['g', 'l'], 'Label…'),
      K(['g', 'd'], 'Done'), K(['g', 'a'], 'Archive'),
      K(['g', 'r'], 'Trash'), K(['[', ']'], 'Upcoming: previous / next day'),
      h('h4', null, 'Move & select'),
      K(['j', 'k'], 'Next / previous'), K(['←', '↑', '→', '↓'], 'Move in grid'),
      K(['⇧', 'j'], 'Extend selection'), K(['⇧', 'X'], 'Toggle select'),
      K([modKey, 'A'], 'Select all'), K(['Alt', '↑'], 'Move note up/down'),
      K(['Enter'], 'Open in editor'), K(['.'], 'Item menu'),
      h('h4', null, 'Act on items'),
      K(['x'], 'Complete (Space)'), K(['d'], 'Date & repeat'),
      K(['t'], 'Tomorrow'), K(['⇧', 'T'], 'Today'),
      K(['s'], 'Snooze 1 hour'), K(['p'], 'Pin'),
      K(['l'], 'Labels (#)'), K(['c'], 'Color'),
      K(['a'], 'Archive (e)'), K(['Del'], 'Trash'),
      h('h4', null, 'Capture syntax'),
      K(['#label'], 'Add label'), K(['fri 9am'], 'Due date & time'),
      K(['every mon'], 'Repeat'), K(['!'], 'Pin (at the end)'),
      K(['a:', 'b, c'], 'Checklist'), K(['⇧', 'Enter'], 'New line → checklist'),
      h('h4', null, 'In the editor'),
      K(['Enter'], 'New checklist item'), K(['Tab'], 'Indent item'),
      K([modKey, 'Enter'], 'Check item / complete'), K(['Alt', '↑'], 'Move item'),
    )), { width: 720 });
}

/** Settings → Reminders (§2c): synced with the server, so every device and the ntfy push agree. */
function remindersSection() {
  const set = serverSettings();
  const msg = h('div', { class: 'set-msg', 'aria-live': 'polite' });
  const say = (text, kind = '') => { msg.className = 'set-msg ' + kind; msg.textContent = text; };
  const save = async (patch, okText = 'Saved') => {
    say('Saving…');
    const r = await saveServerSettings(patch);
    say(r.ok ? okText : r.error, r.ok ? 'ok' : 'err');
    return r;
  };
  const time = h('input', {
    type: 'time', class: 'field', value: set.allDayTime || ui.settings.allDayTime || '09:00',
    onchange: (e) => save({ allDayTime: e.target.value || '09:00' }).then(() => { ui.settings.allDayTime = serverSettings().allDayTime || ui.settings.allDayTime; render(); }),
  });
  const zones = zoneList();
  const tz = h('input', {
    class: 'field tz', value: set.timezone || '', placeholder: deviceZone() || 'Europe/Berlin', list: 'tz-list', spellcheck: 'false',
    onchange: (e) => e.target.value.trim() && save({ timezone: e.target.value.trim() }),
  });
  const dz = deviceZone();
  const useDevice = h('button', {
    class: 'btn sm', title: 'Use the time zone of this browser',
    onclick: () => { tz.value = dz; save({ timezone: dz }, `Time zone set to ${dz}`); },
  }, 'Use this device’s zone');
  const url = h('input', {
    class: 'field wide', type: 'url', value: set.ntfy?.url || '', placeholder: 'https://ntfy.sh/your-secret-topic', spellcheck: 'false',
    onchange: (e) => save({ ntfy: { url: e.target.value.trim() } }, e.target.value.trim() ? 'ntfy topic saved' : 'ntfy push turned off'),
  });
  const token = h('input', {
    class: 'field wide', type: 'password', value: set.ntfy?.token || '', placeholder: 'Access token (optional)', autocomplete: 'off',
    onchange: (e) => save({ ntfy: { token: e.target.value.trim() } }),
  });
  const test = h('button', {
    class: 'btn sm',
    onclick: async () => {
      if (url.value.trim() !== (serverSettings().ntfy?.url || '')) {
        const r = await save({ ntfy: { url: url.value.trim(), token: token.value.trim() } });
        if (!r.ok) return;
      }
      test.disabled = true;
      say('Sending a test message…');
      const r = await ntfyTest();
      test.disabled = false;
      say(r.ok ? 'Test sent. Check your ntfy app.' : `Test failed: ${r.error}`, r.ok ? 'ok' : 'err');
    },
  }, icon('bell', 14), 'Send test');
  return h('div', { class: 'settings-group' },
    h('div', { class: 'settings-h' }, 'Reminders'),
    h('div', { class: 'settings-row' },
      h('div', null, h('div', { class: 't' }, 'All-day reminder time'), h('div', { class: 'd' }, 'When items without a time remind you. Shared by all devices.')),
      time),
    h('div', { class: 'settings-row' },
      h('div', null, h('div', { class: 't' }, 'Time zone'), h('div', { class: 'd' }, 'Used for push reminders from the server.')),
      h('div', { class: 'row-ctl' }, tz, dz && dz !== set.timezone ? useDevice : null,
        zones.length ? h('datalist', { id: 'tz-list' }, zones.map((z) => h('option', { value: z }))) : null)),
    h('div', { class: 'settings-row col' },
      h('div', null,
        h('div', { class: 't' }, 'Push via ntfy'),
        h('div', { class: 'd' }, 'The server sends every reminder to this ntfy topic, with Done and Snooze buttons. Good for desktops and devices without Jot.'),
        h('div', { class: 'd warn' }, icon('bell', 12), 'Don’t subscribe on the phone that has the Jot app, or you’ll get every reminder twice.')),
      h('div', { class: 'ntfy-ctl' }, url, token, h('div', { class: 'ntfy-actions' }, test, msg))));
}

/** Settings → Illustrations (§2d): only when the server has an OpenAI key. */
function illustrationsSection() {
  const set = serverSettings();
  if (!set.artAvailable) return null;
  const art = set.art || {};
  const msg = h('div', { class: 'set-msg', 'aria-live': 'polite' });
  const save = async (patch, okText) => {
    msg.className = 'set-msg'; msg.textContent = 'Saving…';
    const r = await saveServerSettings({ art: patch });
    msg.className = 'set-msg ' + (r.ok ? 'ok' : 'err'); msg.textContent = r.ok ? okText : r.error;
  };
  const seg = h('div', { class: 'seg' }, [[false, 'Off'], [true, 'On']].map(([v, label]) => h('button', {
    class: [!!art.auto === v && 'on'],
    onclick: (e) => {
      seg.querySelectorAll('button').forEach((b) => b.classList.remove('on'));
      e.currentTarget.classList.add('on');
      save({ auto: v }, v ? 'New notes get a picture' : 'Automatic pictures off');
    },
  }, label)));
  const style = h('textarea', {
    class: 'field wide art-style', value: art.style || '', spellcheck: 'false',
    placeholder: 'A cheerful flat vector illustration with clean shapes, bright saturated colors and soft light',
    onchange: (e) => save({ style: e.target.value.trim() }, 'Style saved'),
  });
  // Characters (§2d): recurring figures, each with a reference picture. The server picks the ones that fit a note.
  const chars = (art.characters || []).map((c) => ({ ...c }));
  const list = h('div', { class: 'char-list' });
  const saveChars = (okText = 'Characters saved') => save({ characters: chars.filter((c) => c.image) }, okText);
  const pick = (onFile) => {
    const input = h('input', { type: 'file', accept: 'image/*', style: { display: 'none' } });
    input.onchange = () => { const f = imageFiles(input.files)[0]; input.remove(); if (f) onFile(f); };
    document.body.append(input);
    input.click();
  };
  const upload = async (f) => {
    msg.className = 'set-msg'; msg.textContent = 'Uploading…';
    try { return await uploadNow(f); } catch (e) { msg.className = 'set-msg err'; msg.textContent = e.message; return null; }
  };
  const drawList = (focusLast = false) => {
    fill(list, ...chars.map((c, i) => h('div', { class: 'char-row' },
      h('button', {
        class: 'char-pic', title: 'Change picture',
        onclick: () => pick(async (f) => { const id = await upload(f); if (id) { c.image = id; drawList(); saveChars('Picture changed'); } }),
      }, h('img', { src: imageURL(c.image, 240), alt: '' })),
      h('div', { class: 'char-fields' },
        h('input', {
          class: 'field', value: c.name || '', placeholder: 'Name, e.g. Bear', maxlength: '40',
          onchange: (e) => { c.name = e.target.value.trim(); saveChars(); },
        }),
        h('input', {
          class: 'field wide', value: c.about || '', placeholder: 'Who they are and when they show up, e.g. calm, cosy: family, home, food',
          maxlength: '300', onchange: (e) => { c.about = e.target.value.trim(); saveChars(); },
        })),
      h('button', {
        class: 'icon-btn sm', title: 'Remove character',
        onclick: () => { chars.splice(i, 1); drawList(); saveChars('Character removed'); },
      }, icon('x', 15)))),
    chars.length < 6 ? h('button', {
      class: 'btn sm char-add',
      onclick: () => pick(async (f) => {
        const id = await upload(f);
        if (!id) return;
        chars.push({ name: '', about: '', image: id });
        drawList(true);
        saveChars('Character added. Give it a name.');
      }),
    }, icon('imageplus', 14), 'Add character') : null);
    if (focusLast) list.querySelectorAll('.char-row:last-of-type input')[0]?.focus();
  };
  drawList();

  return h('div', { class: 'settings-group' },
    h('div', { class: 'settings-h' }, 'Illustrations'),
    h('div', { class: 'settings-row' },
      h('div', null, h('div', { class: 't' }, 'Paint pictures for new notes'),
        h('div', { class: 'd' }, 'A few seconds after you add a note without an image, the server paints one. Remove it and it stays gone.'),
        h('div', { class: 'd warn' }, icon('sparkles', 12), 'The note’s text is sent to OpenAI to make the picture.')),
      seg),
    h('div', { class: 'settings-row col' },
      h('div', null, h('div', { class: 't' }, 'Style'),
        h('div', { class: 'd' }, 'How every picture should look, so the cards match. Leave empty for the default. Repaint a single note from its editor (✦).')),
      h('div', { class: 'ntfy-ctl' }, style)),
    h('div', { class: 'settings-row col' },
      h('div', null, h('div', { class: 't' }, 'Characters'),
        h('div', { class: 'd' }, 'Recurring figures for the pictures. Each note gets the ones that fit it (one, a few or all), going by their descriptions. Best: the character alone on a plain background.')),
      h('div', { class: 'ntfy-ctl' }, list, msg)));
}

function settings() {
  const s = ui.settings;
  const seg = (key, opts) => h('div', { class: 'seg' }, opts.map(([v, label]) => h('button', {
    class: [s[key] === v && 'on'],
    onclick: (e) => {
      s[key] = v; saveSettings(); applyTheme(); render();
      e.currentTarget.parentElement.querySelectorAll('button').forEach((b) => b.classList.remove('on'));
      e.currentTarget.classList.add('on');
    },
  }, label)));
  const perm = 'Notification' in window ? Notification.permission : 'unsupported';
  const notifBtn = h('button', {
    class: ['btn sm', s.notifications && perm === 'granted' ? '' : 'primary'],
    onclick: async () => {
      if (!('Notification' in window)) return;
      if (s.notifications && Notification.permission === 'granted') {
        s.notifications = false;
      } else {
        const p = await Notification.requestPermission();
        s.notifications = p === 'granted';
        if (p !== 'granted') toast('Notifications are blocked in your browser settings');
      }
      saveSettings();
      settings();
    },
  }, s.notifications && perm === 'granted' ? 'Turn off' : 'Enable');
  const rem = remindersSection();
  const last = store.lastSyncAt ? new Date(store.lastSyncAt).toLocaleTimeString() : 'never';
  modal(h('div', { class: 'modal-body' },
    h('h2', null, 'Settings'),
    h('p', { class: 'lead' }, 'Appearance is stored in this browser. Reminders are shared by all your devices.'),
    h('div', { class: 'settings-row' },
      h('div', null, h('div', { class: 't' }, 'Theme')),
      seg('theme', [['system', 'System'], ['light', 'Light'], ['dark', 'Dark']])),
    h('div', { class: 'settings-row' },
      h('div', null, h('div', { class: 't' }, 'Animations'), h('div', { class: 'd' }, 'System follows your OS “reduce motion” setting.')),
      seg('motion', [['system', 'System'], ['reduce', 'Reduced']])),
    h('div', { class: 'settings-row' },
      h('div', null, h('div', { class: 't' }, 'Time format')),
      h('div', { class: 'seg' }, [[false, '24h'], [true, '12h']].map(([v, label]) => h('button', {
        class: [s.h12 === v && 'on'],
        onclick: (e) => {
          s.h12 = v; saveSettings(); render();
          e.currentTarget.parentElement.querySelectorAll('button').forEach((b) => b.classList.remove('on'));
          e.currentTarget.classList.add('on');
        },
      }, label)))),
    h('div', { class: 'settings-row' },
      h('div', null, h('div', { class: 't' }, 'Desktop notifications'),
        h('div', { class: 'd' }, perm === 'unsupported' ? 'Not supported by this browser.' : 'Reminders pop up while a Jot tab is open. Your phone handles them otherwise.')),
      perm === 'unsupported' ? null : notifBtn),
    rem,
    illustrationsSection(),
    h('div', { class: 'settings-row' },
      h('div', null, h('div', { class: 't' }, 'Sync'), h('div', { class: 'd' }, `Last synced ${last}${store.dirty.size ? ` · ${store.dirty.size} pending` : ''}`)),
      h('button', { class: 'btn sm', onclick: () => store.sync().then(settings) }, icon('refresh', 14), 'Sync now')),
    h('div', { class: 'settings-row' },
      h('div', null, h('div', { class: 't' }, 'Account'), h('div', { class: 'd' }, `Signed in as ${store.user}`)),
      h('div', { style: { display: 'flex', gap: '6px' } },
        h('a', { class: 'btn sm', href: '/api/export', download: '' }, icon('download', 14), 'Export'),
        h('button', { class: 'btn sm danger', onclick: () => logout() }, icon('logout', 14), 'Log out'))),
  ), { width: 560 });
}

// ======================= labels =======================

async function newLabel() {
  const name = normLabel(await promptPop(document.querySelector('.nav-section button'), { placeholder: 'Label name', button: 'Create' }) || '');
  if (name) go({ type: 'label', label: name });
}

async function renameLabel(from) {
  const anchor = document.querySelector('.view-title .title-actions');
  const to = normLabel(await promptPop(anchor, { value: from, button: 'Rename' }) || '');
  if (!to || to === from) return;
  act.renameLabel(from, to);
  go({ type: 'label', label: to });
}

function deleteLabel(name) {
  act.deleteLabel(name);
  go({ type: 'notes' });
}

// ======================= login / boot =======================

function showLogin(message = '', prefill = store.user) {
  store.stopEvents();
  document.getElementById('app').hidden = true;
  const host = document.getElementById('login');
  const user = h('input', { class: 'field', name: 'username', autocomplete: 'username', autocapitalize: 'none', spellcheck: 'false', value: prefill || '' });
  const pass = h('input', { class: 'field', name: 'password', type: 'password', autocomplete: 'current-password' });
  const err = h('div', { class: 'login-err' }, message);
  const btn = h('button', { class: 'btn primary', type: 'submit' }, 'Sign in');
  const form = h('form', {
    class: 'login-card',
    onsubmit: async (e) => {
      e.preventDefault();
      err.textContent = '';
      btn.disabled = true;
      btn.textContent = 'Signing in…';
      try {
        const res = await fetch('/api/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'same-origin',
          body: JSON.stringify({ username: user.value.trim(), password: pass.value, device: 'Web · ' + browserName() }),
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.error || 'Sign-in failed');
        if (store.user !== data.username) store.reset(data.username);
        host.hidden = true;
        start();
      } catch (ex) {
        err.textContent = ex.message === 'Failed to fetch' ? 'Server not reachable' : ex.message;
        pass.select();
      } finally {
        btn.disabled = false;
        btn.textContent = 'Sign in';
      }
    },
  },
  h('div', { class: 'brand' }, h('span', { class: 'brand-mark', html: BRAND }), h('span', { class: 'brand-name' }, 'Jot')),
  h('p', null, 'Notes and tasks, fast.'),
  h('label', null, 'Username'), user,
  h('label', null, 'Password'), pass,
  btn, err);
  fill(host, form);
  host.hidden = false;
  (user.value ? pass : user).focus();
}

function browserName() {
  const ua = navigator.userAgent;
  if (/Firefox\//.test(ua)) return 'Firefox';
  if (/Edg\//.test(ua)) return 'Edge';
  if (/Chrome\//.test(ua)) return 'Chrome';
  if (/Safari\//.test(ua)) return 'Safari';
  return 'Browser';
}

async function logout() {
  try {
    await fetch('/api/logout', { method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'same-origin', body: '{}' });
  } catch { /* offline: still drop local state */ }
  closeLayer(document.querySelector('.modal-back'));
  closeEditor();
  const name = store.user;
  store.reset('');
  showLogin('', name);
}

let started = false;
function start() {
  document.getElementById('app').hidden = false;
  document.getElementById('login').hidden = true;
  if (!started) {
    started = true;
    mountCapture();
    mountEditor();
    observeLayout();
    setupDnD();
    setupFileDrop();
    document.getElementById('view').addEventListener('click', onViewClick);
    document.getElementById('scrim').addEventListener('click', () => document.getElementById('app').classList.remove('nav-open'));
    store.on('change', () => render());
    store.on('status', () => renderStatus());
    store.on('auth', () => showLogin('Your session has ended. Please sign in again.'));
    store.on('error', (msg) => toast('Sync error: ' + msg));
    store.on('synced', () => { purgeTrash(); checkReminders(); });
    window.addEventListener('focus', () => store.sync());
    window.addEventListener('online', () => store.sync());
    setInterval(() => { checkReminders(); render(); }, 30000);
    setInterval(() => { if (document.visibilityState === 'visible') store.sync(); }, 5 * 60000);
  }
  const fromHash = parseHash();
  const link = fromHash?.type === 'item' ? fromHash.id : null;
  const v = (link ? null : fromHash) || lsGet('jot.lastView', null) || { type: 'notes' };
  ui.view = VIEWS[v.type] || v.type === 'label' ? v : { type: 'notes' };
  if (!fromHash || link) history.replaceState(null, '', hashOf(ui.view));
  renderNow();
  if (link) openFromLink(link);
  loadSettings().then(() => render());
  store.sync();
  store.startEvents();
  resumeUploads();
  checkReminders();
}

Object.assign(hooks, {
  render, go, search, moveCursor, selectDay, focusCapture, newBlank, focusSearch, reveal, selectAll,
  openEditor, closeEditor, applyStates,
  palette: () => openPalette(), settings, showKeys, cycleTheme, logout, newLabel, renameLabel, deleteLabel,
  key: (k) => runKey(k),
  openDatePop: datePop, openLabelPop: labelPop, openColorPop: colorPop, openItemMenu: itemMenu,
});

async function boot() {
  const cached = store.load();
  if (cached && store.user) {
    // Instant start from cache; the first sync tells us if the session is still valid.
    start();
    return;
  }
  try {
    const res = await fetch('/api/me', { credentials: 'same-origin' });
    if (res.ok) {
      const me = await res.json();
      if (store.user !== me.username) store.reset(me.username);
      start();
      return;
    }
  } catch {
    if (store.user) { start(); return; }
  }
  showLogin();
}

window.addEventListener('resize', () => requestAnimationFrame(layoutGrids));
boot();
