// Right-side editor panel. Edits in place, saves on every keystroke.
import { h, icon, shortId, COLOR_NAMES, fill, labelColor, BOX_SVG, CHECK_SVG } from './util.js';
import { store, isEmpty } from './store.js';
import { ui, hooks } from './ui.js';
import { isTask } from './model.js';
import { parseDue, relDay, fmtTime, describeRepeat, isOverdue, todayNum } from './dates.js';
import * as act from './actions.js';
import { datePop, labelPop, colorPop, menu } from './popovers.js';
import { lsGet, lsSet } from './util.js';
import { keyedFlip, flipAround, anim, after, reduced, EASE_IN } from './motion.js';
import { layoutGrids } from './views.js';

let panel, id = null;
let wasNonEmpty = false; // the open item had text when the editor opened
let closing = false, closeToken = 0;
let titleEl, bodyEl, checksEl, metaEl, bannerEl, stampEl, taskCheckEl, crumbEl;
let showChecked = lsGet('jot.showChecked', true);
let dragFrom = null;

export function mountEditor() {
  panel = document.getElementById('editor');
  store.on('change', (e) => {
    if (!id) return;
    const it = store.get(id);
    if (!it || it.deleted) { closeEditor(); return; }
    if (e?.remote) syncFromStore(it);
    else renderChrome(it);
  });
  setInterval(() => { if (id && store.get(id)) renderStamp(store.get(id)); }, 30000);
}

export const editorOpenId = () => id;

export function openEditor(itemId, focus = null) {
  const it = store.get(itemId);
  if (!it) return;
  if (id && id !== itemId) discardIfEmpty();
  const switching = !panel.hidden && !closing;
  if (closing) { closing = false; closeToken++; for (const a of panel.getAnimations()) a.cancel(); }
  const visible = !panel.hidden;
  panel.inert = false;
  id = itemId;
  wasNonEmpty = !isEmpty(it);
  ui.openId = itemId;
  if (switching && !reduced()) {
    // Crossfade: the old content fades out on top while the new one fades in.
    const old = h('div', { class: 'ed-xfade', 'aria-hidden': 'true' }, ...panel.childNodes);
    old.querySelectorAll('[id]').forEach((n) => n.removeAttribute('id'));
    build(it);
    panel.append(old);
    after(anim(old, [{ opacity: 1 }, { opacity: 0 }], { duration: 160, easing: EASE_IN, fill: 'forwards' }), () => old.remove());
    for (const c of panel.children) if (c !== old) anim(c, [{ opacity: 0 }, { opacity: 1 }], { duration: 180 });
  } else {
    build(it);
  }
  if (!visible) {
    // Slide in (CSS) while the cards make room (FLIP).
    flipAround(document.querySelectorAll('#view [data-k]'), () => {
      panel.hidden = false;
      document.getElementById('app').classList.add('editing');
      layoutGrids();
    });
  }
  hooks.applyStates?.();
  // Synchronous on purpose: keystrokes right after opening must land in the new editor.
  autosizeAll();
  if (focus === 'title' || (focus && !it.title && focus !== 'check')) focusEnd(titleEl);
  else if (focus === 'body') {
    if (it.kind === 'list') focusFirstCheck(); else focusEnd(bodyEl);
  } else if (focus === 'check') focusFirstCheck(true);
}

export function closeEditor() {
  if (!id) return;
  discardIfEmpty();
  id = null;
  ui.openId = null;
  // Slide out, then give the space back to the cards (they glide into it).
  closing = true;
  panel.inert = true; // the leaving content must not take focus or clicks
  const token = ++closeToken;
  const out = anim(panel, [{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'translateX(24px)' }], { duration: 160, easing: EASE_IN, fill: 'forwards' });
  after(out, () => {
    if (token !== closeToken) return;
    closing = false;
    flipAround(document.querySelectorAll('#view [data-k]'), () => {
      panel.hidden = true;
      fill(panel);
      for (const a of panel.getAnimations()) a.cancel();
      document.getElementById('app').classList.remove('editing');
      layoutGrids();
    });
  });
  hooks.applyStates?.();
  hooks.render();
}

/** Leaving an item that has no text (and had none when opened): drop it instead of keeping an empty item. */
function discardIfEmpty() {
  const it = store.get(id);
  const openId = id;
  if (!it || it.deleted || !isEmpty(it) || wasNonEmpty) return;
  id = null; // the change event below must not re-enter closeEditor
  if (!store.discardDraft(openId)) store.update(openId, { deleted: true });
  id = openId;
}

function focusEnd(el) {
  if (!el) return;
  el.focus();
  const n = el.value.length;
  el.setSelectionRange(n, n);
}

function focusFirstCheck(last = false) {
  const tas = checksEl?.querySelectorAll('.ed-check:not(.done) textarea');
  if (!tas?.length) { addCheck(null); return; }
  focusEnd(last ? tas[tas.length - 1] : tas[0]);
}

function autosize(el) {
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = el.scrollHeight + 'px';
}
function autosizeAll() {
  autosize(titleEl);
  autosize(bodyEl);
  checksEl?.querySelectorAll('textarea').forEach(autosize);
}

const cur = () => store.get(id);
const save = (patch) => store.update(id, patch);

// ======================= build =======================

function build(it) {
  crumbEl = h('div', { class: 'crumb' });
  titleEl = h('textarea', {
    class: 'ed-title', rows: 1, placeholder: 'Title', value: it.title, spellcheck: 'true',
    oninput: () => { autosize(titleEl); save({ title: titleEl.value.replace(/\n/g, ' ') }); },
    onkeydown: (e) => {
      if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey) {
        e.preventDefault();
        if (cur().kind === 'list') focusFirstCheck(); else { bodyEl.focus(); bodyEl.setSelectionRange(0, 0); }
      }
      if (e.key === 'ArrowDown' && titleEl.selectionStart === titleEl.value.length) {
        e.preventDefault();
        if (cur().kind === 'list') focusFirstCheck(); else { bodyEl.focus(); bodyEl.setSelectionRange(0, 0); }
      }
    },
  });
  taskCheckEl = h('span');
  metaEl = h('div', { class: 'ed-meta' });
  bannerEl = h('div');
  stampEl = h('span', { class: 'stamp' });
  let content;
  if (it.kind === 'list') {
    checksEl = h('div', { class: 'ed-checklist' });
    bodyEl = null;
    content = checksEl;
  } else {
    checksEl = null;
    bodyEl = h('textarea', {
      class: 'ed-body', placeholder: 'Write a note…', value: it.body, spellcheck: 'true',
      oninput: () => { autosize(bodyEl); save({ body: bodyEl.value }); },
      onkeydown: (e) => {
        if (e.key === 'ArrowUp' && bodyEl.selectionStart === 0) { e.preventDefault(); focusEnd(titleEl); }
        if (e.key === 'Tab' && !e.shiftKey) {
          // Keep focus in the note; insert two spaces.
          e.preventDefault();
          bodyEl.setRangeText('  ', bodyEl.selectionStart, bodyEl.selectionEnd, 'end');
          save({ body: bodyEl.value });
        }
      },
    });
    content = bodyEl;
  }
  const btn = (ic, title, onclick, extra = {}) => h('button', { class: 'icon-btn', title, onclick, ...extra }, icon(ic, 17));
  fill(panel, 
    h('div', { class: 'ed-top' },
      crumbEl,
      btn('pin', 'Pin (p)', () => act.togglePin([id]), { id: 'ed-pin' }),
      btn('more', 'More', (e) => itemMenu(id, e.currentTarget)),
      btn('x', 'Close (Esc)', () => closeEditor())),
    h('div', { class: 'ed-scroll' },
      bannerEl,
      h('div', { class: 'ed-taskline' }, taskCheckEl, titleEl),
      metaEl,
      content),
    h('div', { class: 'ed-foot' },
      btn('calendar', 'Date & repeat (d)', (e) => datePop([id], e.currentTarget)),
      btn('tag', 'Labels (l)', (e) => labelPop([id], e.currentTarget)),
      btn('palette', 'Color (c)', (e) => colorPop([id], e.currentTarget)),
      btn(it.kind === 'list' ? 'text' : 'list', it.kind === 'list' ? 'Convert to note' : 'Convert to checklist', () => { act.convertKind(id); build(cur()); autosizeAll(); }),
      btn(it.archived ? 'unarchive' : 'archive', it.archived ? 'Unarchive (a)' : 'Archive (a)', () => act.toggleArchive([id]), { id: 'ed-arch' }),
      btn('trash', 'Move to trash (Del)', () => { act.trash([id]); closeEditor(); }),
      stampEl),
  );
  renderChrome(it);
  if (it.kind === 'list') renderChecks(it, null, null, true);
}

function renderChrome(it) {
  if (!panel || !it) return;
  if (it.color) panel.dataset.color = it.color; else delete panel.dataset.color;
  // crumb
  const where = it.trashedAt ? 'Trash' : it.archived ? 'Archive' : it.done ? 'Done' : isTask(it) ? 'Task' : it.kind === 'list' ? 'Checklist' : 'Note';
  fill(crumbEl, icon(it.kind === 'list' ? 'list' : 'text', 13), where,
    it.color ? h('span', { class: 'color-dot', 'data-color': it.color, title: COLOR_NAMES[it.color] }) : null);
  const pinBtn = document.getElementById('ed-pin');
  pinBtn?.classList.toggle('on', !!it.pinned);
  const arch = document.getElementById('ed-arch');
  if (arch) { fill(arch, icon(it.archived ? 'unarchive' : 'archive', 17)); arch.title = it.archived ? 'Unarchive (a)' : 'Archive (a)'; }

  // banner
  let banner = null;
  if (it.trashedAt) {
    banner = h('div', { class: 'ed-banner' }, icon('trash', 15), 'In trash', h('span', { class: 'spacer' }),
      h('button', { class: 'btn sm', onclick: () => act.restore([id]) }, 'Restore'),
      h('button', { class: 'btn sm danger', onclick: () => { act.deleteForever([id]); closeEditor(); } }, 'Delete forever'));
  } else if (it.done) {
    banner = h('div', { class: 'ed-banner' }, icon('done', 15), 'Completed' + (it.doneAt ? ' ' + relDay(todayNum(new Date(it.doneAt))).toLowerCase() : ''), h('span', { class: 'spacer' }),
      h('button', { class: 'btn sm', onclick: () => act.toggleComplete([id]) }, 'Reopen'));
  } else if (it.archived) {
    banner = h('div', { class: 'ed-banner' }, icon('archive', 15), 'Archived', h('span', { class: 'spacer' }),
      h('button', { class: 'btn sm', onclick: () => act.toggleArchive([id]) }, 'Unarchive'));
  }
  fill(bannerEl, ...(banner ? [banner] : []));

  // task checkbox
  if (isTask(it)) {
    fill(taskCheckEl, h('button', {
      class: ['check', it.done && 'on', it.due && !it.done && isOverdue(it.due) && 'p-overdue'],
      title: 'Complete (Ctrl+Enter)',
      'data-color': it.color || null,
      html: CHECK_SVG,
      onclick: () => act.toggleComplete([id]),
    }));
  } else fill(taskCheckEl);

  // meta chips
  const chips = [];
  const p = parseDue(it.due);
  if (p) {
    const over = !it.done && isOverdue(it.due);
    chips.push(h('button', { class: ['chip date', over && 'overdue'], title: 'Change date (d)', onclick: (e) => datePop([id], e.currentTarget) },
      icon('calendar', 13), relDay(p.day) + (p.min != null ? ' ' + fmtTime(p.min, ui.settings.h12) : ''),
      it.remind === false ? icon('belloff', 12) : null));
    if (it.repeat) chips.push(h('button', { class: 'chip repeat', onclick: (e) => datePop([id], e.currentTarget) }, icon('repeat', 13), describeRepeat(it.repeat)));
    if (it.snoozedUntil) {
      const s = parseDue(it.snoozedUntil);
      if (s) chips.push(h('span', { class: 'chip time' }, icon('bell', 13), 'Snoozed to ' + relDay(s.day) + (s.min != null ? ' ' + fmtTime(s.min, ui.settings.h12) : '')));
    }
  } else if (!it.trashedAt) {
    chips.push(h('button', { class: 'chip add', onclick: (e) => datePop([id], e.currentTarget) }, icon('calendar', 13), 'Add date'));
  }
  for (const l of it.labels || []) {
    chips.push(h('button', { class: 'chip label', title: 'Remove label', onclick: () => act.toggleLabel([id], l) },
      h('span', { class: 'hash', 'data-color': labelColor(l) }, '#'), l, h('span', { class: 'x' }, icon('x', 11))));
  }
  if (!it.trashedAt) chips.push(h('button', { class: 'chip add', onclick: (e) => labelPop([id], e.currentTarget) }, icon('tag', 13), (it.labels || []).length ? '' : 'Add label'));
  fill(metaEl, ...chips);
  renderStamp(it);
}

function renderStamp(it) {
  const mins = Math.round((Date.now() - it.updatedAt) / 60000);
  let s;
  if (mins < 1) s = 'Edited just now';
  else if (mins < 60) s = `Edited ${mins} min ago`;
  else if (mins < 60 * 24) s = `Edited ${Math.round(mins / 60)} h ago`;
  else s = 'Edited ' + new Date(it.updatedAt).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: new Date(it.updatedAt).getFullYear() === new Date().getFullYear() ? undefined : 'numeric' });
  stampEl.textContent = s;
  stampEl.title = 'Created ' + new Date(it.createdAt).toLocaleString();
}

/** Remote change while open: refresh fields that aren't being edited. */
function syncFromStore(it) {
  if ((it.kind === 'list') !== !!checksEl) { build(it); autosizeAll(); return; }
  if (document.activeElement !== titleEl && titleEl.value !== it.title) { titleEl.value = it.title; autosize(titleEl); }
  if (bodyEl && document.activeElement !== bodyEl && bodyEl.value !== it.body) { bodyEl.value = it.body; autosize(bodyEl); }
  if (checksEl && !checksEl.contains(document.activeElement)) renderChecks(it);
  renderChrome(it);
}

// ======================= checklist =======================

function renderChecks(it, focusId = null, caret = null, instant = false) {
  const checks = it.checks || [];
  const open = checks.filter((c) => !c.done);
  const done = checks.filter((c) => c.done);
  const row = (c) => {
    const ta = h('textarea', {
      rows: 1, value: c.text, spellcheck: 'true', 'data-tcid': c.id,
      oninput: () => { autosize(ta); updateCheck(c.id, { text: ta.value.replace(/\n/g, ' ') }); },
      onkeydown: (e) => checkKey(e, c.id, ta),
      onpaste: (e) => {
        const text = e.clipboardData.getData('text');
        if (!text.includes('\n')) return;
        e.preventDefault();
        pasteLines(c.id, ta, text);
      },
    });
    const li = h('div', { class: ['ed-check', c.done && 'done', c.indent && 'indent'], 'data-cid': c.id },
      h('span', {
        class: 'grip', title: 'Drag to reorder',
        onmousedown: () => { li.draggable = true; },
        onmouseup: () => { li.draggable = false; },
      }, icon('grip', 16)),
      h('button', { class: ['mini-box', c.done && 'on'], 'aria-label': 'Done', onclick: () => toggleCheck(c.id), html: BOX_SVG }),
      ta,
      h('button', { class: 'icon-btn sm del', title: 'Delete item', onclick: () => removeCheck(c.id) }, icon('x', 14)));
    li.addEventListener('dragstart', (e) => { dragFrom = c.id; li.classList.add('dragging'); e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', c.text); });
    li.addEventListener('dragend', () => { li.draggable = false; li.classList.remove('dragging'); dragFrom = null; checksEl.querySelectorAll('.drag-over').forEach((x) => x.classList.remove('drag-over')); });
    li.addEventListener('dragover', (e) => { if (!dragFrom || dragFrom === c.id) return; e.preventDefault(); checksEl.querySelectorAll('.drag-over').forEach((x) => x.classList.remove('drag-over')); li.classList.add('drag-over'); });
    li.addEventListener('drop', (e) => { e.preventDefault(); if (dragFrom) moveCheck(dragFrom, c.id); });
    return li;
  };
  // Items and the controls between them are keyed, so adding, removing and checking items glides.
  const draw = () => fill(checksEl,
    h('div', { class: 'ed-checks' }, open.map(row)),
    h('button', { class: 'ed-addcheck', 'data-cid': 'x:add', onclick: () => addCheck(null) }, icon('plus', 16), 'List item'),
    done.length ? h('button', {
      'data-cid': 'x:toggle',
      class: ['ed-done-toggle', !showChecked && 'collapsed'],
      onclick: () => { showChecked = !showChecked; lsSet('jot.showChecked', showChecked); renderChecks(cur()); },
    }, icon('chevdown', 15), `${done.length} checked`) : null,
    done.length && showChecked ? h('div', { class: 'ed-checks' }, done.map(row)) : null,
    done.length && showChecked ? h('button', { class: 'btn sm ghost', 'data-cid': 'x:uncheck', style: { marginTop: '4px' }, onclick: () => uncheckAll() }, icon('restore', 14), 'Uncheck all') : null,
  );
  if (instant) draw();
  else keyedFlip(checksEl, 'data-cid', () => { draw(); checksEl.querySelectorAll('textarea').forEach(autosize); });
  checksEl.querySelectorAll('textarea').forEach(autosize);
  if (focusId) {
    const ta = checksEl.querySelector(`textarea[data-tcid="${focusId}"]`);
    if (ta) { ta.focus(); const p = caret ?? ta.value.length; ta.setSelectionRange(p, p); }
  }
}

function setChecks(checks, focusId, caret) {
  const it = save({ checks });
  renderChecks(it, focusId, caret);
}

function updateCheck(cid, patch) {
  const it = cur();
  save({ checks: it.checks.map((c) => (c.id === cid ? { ...c, ...patch } : c)) });
}

function toggleCheck(cid) {
  const it = cur();
  setChecks(it.checks.map((c) => (c.id === cid ? { ...c, done: !c.done } : c)));
}

function uncheckAll() {
  const it = cur();
  setChecks(it.checks.map((c) => ({ ...c, done: false })));
}

function addCheck(afterId, text = '') {
  const it = cur();
  const checks = [...(it.checks || [])];
  const nc = { id: shortId(), text, done: false, indent: 0 };
  if (afterId) {
    const i = checks.findIndex((c) => c.id === afterId);
    nc.indent = checks[i]?.indent || 0;
    checks.splice(i + 1, 0, nc);
  } else {
    // after the last unchecked item
    let i = -1;
    checks.forEach((c, j) => { if (!c.done) i = j; });
    checks.splice(i + 1, 0, nc);
  }
  setChecks(checks, nc.id, 0);
}

function removeCheck(cid, focusPrev = false) {
  const it = cur();
  const open = it.checks.filter((c) => !c.done);
  const idx = open.findIndex((c) => c.id === cid);
  const prev = open[idx - 1] || open[idx + 1];
  setChecks(it.checks.filter((c) => c.id !== cid), focusPrev && prev ? prev.id : null);
  if (focusPrev && !prev) focusEnd(titleEl);
}

function moveCheck(fromId, beforeId) {
  const it = cur();
  const checks = [...it.checks];
  const from = checks.findIndex((c) => c.id === fromId);
  const [m] = checks.splice(from, 1);
  const to = checks.findIndex((c) => c.id === beforeId);
  checks.splice(to, 0, m);
  setChecks(checks);
}

function pasteLines(cid, ta, text) {
  const lines = text.split(/\r?\n/).map((l) => l.replace(/^\s*(?:[-*•]\s+|\[[ xX]?\]\s*)/, '')).filter((l) => l.trim());
  if (!lines.length) return;
  const it = cur();
  const checks = [...it.checks];
  const i = checks.findIndex((c) => c.id === cid);
  const before = ta.value.slice(0, ta.selectionStart), after = ta.value.slice(ta.selectionEnd);
  checks[i] = { ...checks[i], text: before + lines[0] };
  const added = lines.slice(1).map((t) => ({ id: shortId(), text: t, done: false, indent: checks[i].indent || 0 }));
  if (added.length) added[added.length - 1].text += after; else checks[i].text += after;
  checks.splice(i + 1, 0, ...added);
  setChecks(checks, (added[added.length - 1] || checks[i]).id);
}

function checkKey(e, cid, ta) {
  const it = cur();
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault();
    // Keep the keyboard flow: focus whatever now sits where the toggled item was.
    const c = it.checks.find((x) => x.id === cid);
    const same = it.checks.filter((x) => x.done === c.done);
    const i = same.findIndex((x) => x.id === cid);
    const next = same[i + 1] || same[i - 1];
    toggleCheck(cid);
    if (next) {
      const t = checksEl.querySelector(`textarea[data-tcid="${next.id}"]`);
      if (t) focusEnd(t);
    } else focusEnd(titleEl);
    return;
  }
  if (e.key === 'Enter') {
    e.preventDefault();
    const before = ta.value.slice(0, ta.selectionStart), after = ta.value.slice(ta.selectionEnd);
    if (!ta.value.trim() && (it.checks.find((c) => c.id === cid)?.indent)) {
      // Enter on an empty indented item outdents it (like most editors).
      updateCheck(cid, { indent: 0 }); renderChecks(cur(), cid); return;
    }
    const checks = it.checks.map((c) => (c.id === cid ? { ...c, text: before } : c));
    save({ checks });
    addCheck(cid, after);
    return;
  }
  if (e.key === 'Backspace' && ta.selectionStart === 0 && ta.selectionEnd === 0) {
    e.preventDefault();
    const c = it.checks.find((x) => x.id === cid);
    if (c.indent) { updateCheck(cid, { indent: 0 }); renderChecks(cur(), cid, 0); return; }
    const open = it.checks.filter((x) => x.done === c.done);
    const idx = open.findIndex((x) => x.id === cid);
    const prev = open[idx - 1];
    if (!ta.value) { removeCheck(cid, true); return; }
    if (prev) {
      // merge into previous
      const caret = prev.text.length;
      const checks = it.checks.filter((x) => x.id !== cid).map((x) => (x.id === prev.id ? { ...x, text: x.text + ta.value } : x));
      setChecks(checks, prev.id, caret);
    }
    return;
  }
  if (e.key === 'Delete' && ta.selectionStart === ta.value.length && !ta.value) { e.preventDefault(); removeCheck(cid, true); return; }
  if (e.key === 'Tab') {
    e.preventDefault();
    updateCheck(cid, { indent: e.shiftKey ? 0 : 1 });
    renderChecks(cur(), cid, ta.selectionStart);
    return;
  }
  if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
    // Single visual line: arrows always move between items. Wrapped: only from the very start/end.
    const single = ta.scrollHeight <= parseFloat(getComputedStyle(ta).lineHeight) * 1.6;
    const caretLineFirst = single || ta.selectionStart === 0;
    const atEnd = single || ta.selectionEnd === ta.value.length;
    const all = [...checksEl.querySelectorAll('textarea')];
    const i = all.indexOf(ta);
    if (e.key === 'ArrowUp' && caretLineFirst) {
      e.preventDefault();
      if (e.altKey) { moveBy(cid, -1); return; }
      if (i > 0) focusEnd(all[i - 1]); else focusEnd(titleEl);
    } else if (e.key === 'ArrowDown' && atEnd) {
      e.preventDefault();
      if (e.altKey) { moveBy(cid, 1); return; }
      if (i < all.length - 1) focusEnd(all[i + 1]);
    }
  }
}

function moveBy(cid, dir) {
  const it = cur();
  const checks = [...it.checks];
  const i = checks.findIndex((c) => c.id === cid);
  let j = i + dir;
  while (checks[j] && checks[j].done !== checks[i].done) j += dir;
  if (!checks[j]) return;
  [checks[i], checks[j]] = [checks[j], checks[i]];
  setChecks(checks, cid);
}


// ======================= item menu (shared with views) =======================

export function itemMenu(itemId, anchor) {
  const it = store.get(itemId);
  if (!it) return;
  const ids = ui.selected.size && ui.selected.has(itemId) ? [...ui.selected] : [itemId];
  if (it.trashedAt) {
    menu([
      { label: 'Restore', icon: 'restore', run: () => act.restore(ids) },
      { label: 'Delete forever', icon: 'trash', danger: true, run: () => { act.deleteForever(ids); if (ids.includes(id)) closeEditor(); } },
    ], anchor);
    return;
  }
  const p = parseDue(it.due);
  menu([
    { label: 'Open', icon: 'edit', kbd: 'o', run: () => openEditor(itemId, 'body') },
    { label: it.done ? 'Mark as not done' : 'Complete', icon: 'done', kbd: 'x', run: () => act.toggleComplete(ids) },
    { label: 'Date & repeat…', icon: 'calendar', kbd: 'd', run: () => datePop(ids, anchor) },
    p && !it.done ? { label: 'Snooze reminder 1 hour', icon: 'bell', kbd: 's', run: () => snooze1h(ids) } : null,
    '-',
    { label: it.pinned ? 'Unpin' : 'Pin', icon: 'pin', kbd: 'p', run: () => act.togglePin(ids) },
    { label: 'Labels…', icon: 'tag', kbd: 'l', run: () => labelPop(ids, anchor) },
    { label: 'Color…', icon: 'palette', kbd: 'c', run: () => colorPop(ids, anchor) },
    { label: it.kind === 'list' ? 'Convert to note' : 'Convert to checklist', icon: it.kind === 'list' ? 'text' : 'list', run: () => { act.convertKind(itemId); if (id === itemId) { build(cur()); autosizeAll(); } } },
    { label: 'Make a copy', icon: 'copy', run: () => act.duplicate(itemId) },
    { label: 'Copy text', icon: 'copy', run: () => navigator.clipboard?.writeText(itemText(it)) },
    '-',
    { label: it.archived ? 'Unarchive' : 'Archive', icon: it.archived ? 'unarchive' : 'archive', kbd: 'a', run: () => act.toggleArchive(ids) },
    { label: 'Move to trash', icon: 'trash', danger: true, kbd: '⌫', run: () => { act.trash(ids); if (ids.includes(id)) closeEditor(); } },
  ], anchor);
}

function snooze1h(ids) {
  const d = new Date(Date.now() + 3600000);
  const pad = (n) => String(n).padStart(2, '0');
  act.snooze(ids, `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`);
}

function itemText(it) {
  const parts = [it.title];
  if (it.kind === 'list') parts.push(...it.checks.map((c) => `${c.done ? '[x]' : '[ ]'} ${c.text}`));
  else if (it.body) parts.push(it.body);
  return parts.filter(Boolean).join('\n');
}

export function editorHasFocus() {
  return panel && !panel.hidden && panel.contains(document.activeElement);
}
