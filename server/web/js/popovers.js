// Popovers: generic menu, date & repeat, labels, colors.
import { h, icon, COLORS, COLOR_NAMES, fuzzy, fill, BOX_SVG, labelColor } from './util.js';
import { store } from './store.js';
import { allLabels, normLabel } from './model.js';
import { parseWhen } from './quickadd.js';
import {
  parseDue, fmtDue, todayNum, nowString, weekday, fromDayNum, dayNum, daysInMonth, relDay, fmtTime, fmtMin,
  describeRepeat, MON_LONG, WD, ordinal, shortDate,
} from './dates.js';
import * as act from './actions.js';
import { ui } from './ui.js';
import { fadeOut } from './motion.js';

let current = null;

export function closePop() {
  if (!current) return;
  const c = current;
  current = null;
  fadeOut(c.el, () => c.el.remove(), { y: 0, scale: 0.96, duration: 120 });
  c.el.style.pointerEvents = 'none';
  document.removeEventListener('mousedown', c.outside, true);
  c.onClose?.();
  if (c.restoreFocus && document.contains(c.restoreFocus)) c.restoreFocus.focus({ preventScroll: true });
}

export const popOpen = () => !!current;

/** Open a popover element next to an anchor (element or {x,y}). */
export function openPop(el, anchor, { onClose, focus } = {}) {
  closePop();
  const restoreFocus = document.activeElement;
  document.getElementById('layer').append(el);
  position(el, anchor);
  const outside = (e) => { if (!el.contains(e.target)) closePop(); };
  setTimeout(() => document.addEventListener('mousedown', outside, true));
  el.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closePop(); }
  });
  current = { el, outside, onClose, restoreFocus };
  (focus || el.querySelector('input, button, [tabindex]') || el).focus({ preventScroll: true });
  return el;
}

function position(el, anchor) {
  el.style.left = '0px'; el.style.top = '0px';
  const r = anchorRect(anchor);
  const w = el.offsetWidth, ht = el.offsetHeight;
  const vw = window.innerWidth, vh = window.innerHeight;
  let x = r.left, y = r.bottom + 6;
  if (x + w > vw - 8) x = Math.max(8, r.right - w);
  if (x + w > vw - 8) x = vw - w - 8;
  if (y + ht > vh - 8) y = r.top - ht - 6;
  if (y < 8) y = Math.max(8, (vh - ht) / 2);
  el.style.left = Math.max(8, x) + 'px';
  el.style.top = y + 'px';
  // Grow out of the anchor: pick the corner nearest to it.
  const fromRight = x < r.left && Math.abs(x + w - r.right) < 2;
  el.style.transformOrigin = `${fromRight ? 'right' : 'left'} ${y < r.top ? 'bottom' : 'top'}`;
}

function anchorRect(a) {
  if (a instanceof Element && document.contains(a)) return a.getBoundingClientRect();
  if (a && 'x' in a) return { left: a.x, right: a.x, top: a.y, bottom: a.y };
  // center fallback
  const vw = window.innerWidth;
  return { left: vw / 2 - 150, right: vw / 2 + 150, top: 120, bottom: 120 };
}

/** Resolve a good anchor for item ids when triggered from the keyboard. */
export function anchorFor(ids) {
  const id = ids[0];
  const node = document.querySelector(`#view [data-id="${CSS.escape(id || '')}"]`);
  if (node) {
    const tool = node.querySelector('.row-tools, .card-tools');
    return tool || node;
  }
  return null;
}

// ---------- keyboard-navigable list helper ----------
function navList(items, { onPick, input }) {
  let idx = -1;
  const set = (i) => {
    const btns = items();
    if (!btns.length) return;
    idx = (i + btns.length) % btns.length;
    btns.forEach((b, j) => b.classList.toggle('active', j === idx));
    btns[idx].scrollIntoView({ block: 'nearest' });
  };
  const onKey = (e) => {
    if (e.key === 'ArrowDown' || (e.key === 'j' && !input) || (e.key === 'Tab' && !e.shiftKey && !input)) { e.preventDefault(); set(idx + 1); }
    else if (e.key === 'ArrowUp' || (e.key === 'k' && !input) || (e.key === 'Tab' && e.shiftKey && !input)) { e.preventDefault(); set(idx - 1); }
    else if (e.key === 'Enter') {
      const b = items()[idx];
      if (b) { e.preventDefault(); onPick ? onPick(b, e) : b.click(); }
    }
  };
  return { set, onKey, get idx() { return idx; }, reset: () => set(0) };
}

// ======================= generic menu =======================

export function menu(entries, anchor) {
  const btns = [];
  const el = h('div', { class: 'pop', tabindex: '-1', role: 'menu' },
    entries.map((e) => {
      if (e === '-') return h('div', { class: 'menu-sep' });
      if (!e) return null;
      const b = h('button', {
        class: ['menu-item', e.danger && 'danger'], role: 'menuitem',
        onclick: () => { closePop(); e.run(); },
      }, e.icon ? icon(e.icon, 16) : null, h('span', { class: 'grow' }, e.label), e.kbd ? h('kbd', null, e.kbd) : null);
      btns.push(b);
      return b;
    }));
  const nav = navList(() => btns, {});
  el.addEventListener('keydown', nav.onKey);
  openPop(el, anchor);
  nav.set(0);
  btns[0]?.focus();
  el.addEventListener('keydown', (e) => {
    // Letter shortcuts inside the menu
    const hit = entries.find((x) => x && x !== '-' && x.kbd && x.kbd.toLowerCase() === e.key.toLowerCase() && !e.ctrlKey && !e.metaKey);
    if (hit) { e.preventDefault(); closePop(); hit.run(); }
  });
  return el;
}

// ======================= date & repeat =======================

function roundUpHour(min) { return Math.min(23 * 60 + 45, Math.ceil(min / 60) * 60); }

export function datePop(ids, anchor) {
  const items = ids.map((id) => store.get(id)).filter(Boolean);
  if (!items.length) return;
  const first = items[0];
  const now = new Date();
  const today = todayNum(now);
  const nowMin = now.getHours() * 60 + now.getMinutes();
  const cur = parseDue(first.due);
  let draft = {
    day: cur?.day ?? null,
    min: cur?.min ?? null,
    repeat: first.repeat ? structuredClone(first.repeat) : null,
    remind: first.remind !== false,
  };
  let viewMonth = (() => { const d = fromDayNum(cur?.day ?? today); return { y: d.y, m: d.m }; })();

  const keepTime = cur?.min ?? null;
  const quick = [];
  if (nowMin < 21 * 60) quick.push({ label: 'Later today', icon: 'clock', day: today, min: roundUpHour(nowMin + 150) });
  if (nowMin < 19 * 60) quick.push({ label: 'Tonight', icon: 'sunset', day: today, min: 19 * 60 });
  else quick.push({ label: 'Today', icon: 'sun', day: today, min: keepTime });
  quick.push({ label: 'Tomorrow', icon: 'forward', day: today + 1, min: keepTime });
  const nextMon = today + ((7 - weekday(today)) % 7 || 7);
  quick.push({ label: 'Next week', icon: 'calendar', day: nextMon, min: keepTime });
  const wknd = weekday(today) >= 5;
  quick.push({ label: wknd ? 'Next weekend' : 'Weekend', icon: 'sofa', day: today + ((12 - weekday(today)) % 7 || 7), min: keepTime });
  if (first.due) quick.push({ label: 'No date', icon: 'x', day: null, min: null });

  const commit = (opts = {}) => {
    const due = draft.day == null ? '' : fmtDue(draft.day, draft.min);
    if (opts.undoable) act.setDue(ids, due, draft.day == null ? null : draft.repeat);
    else {
      store.updateMany(ids, (it) => ({
        due, snoozedUntil: '', repeat: draft.day == null ? null : draft.repeat, remind: draft.remind,
        done: due && it.done && !it.repeat ? false : it.done,
      }));
    }
  };

  const input = h('input', { class: 'pop-input', placeholder: 'Type a date: fri 9am, in 3 days, every mon…', autocomplete: 'off', spellcheck: 'false' });
  const hint = h('div', { class: 'pop-hint' });
  const quickBtns = quick.map((q, i) => h('button', {
    class: 'menu-item',
    onclick: () => { draft.day = q.day; draft.min = q.min; if (q.day == null) draft.repeat = null; commit({ undoable: true }); closePop(); },
  }, icon(q.icon, 15), h('span', { class: 'grow' }, q.label), h('span', { class: 'sub' }, q.day == null ? '' : shortDow(q.day) + (q.min != null ? ' ' + fmtTime(q.min, ui.settings.h12) : '')), h('kbd', null, String(i + 1))));
  const cal = h('div', { class: 'cal' });
  const timeIn = h('input', { type: 'time', class: 'grow', value: draft.min != null ? fmtMin(draft.min) : '', title: 'Time (leave empty for all-day)' });
  const remindBtn = h('button', { class: ['icon-btn', draft.remind && 'on'], title: 'Reminder on/off' }, icon(draft.remind ? 'bell' : 'belloff', 16));
  const repeatSel = h('select', { class: 'grow', title: 'Repeat' });
  const custom = h('div', { hidden: true });

  const nav = navList(() => quickBtns, { input: true });

  function shortDow(day) {
    const diff = day - today;
    if (diff === 0) return 'Today';
    if (diff < 7) return ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'][weekday(day)];
    return shortDate(day);
  }

  function renderCal() {
    const { y, m } = viewMonth;
    const first = dayNum(y, m, 1);
    const start = first - weekday(first);
    const cells = [];
    for (let i = 0; i < 42; i++) {
      const d = start + i;
      const dd = fromDayNum(d);
      if (i >= 35 && dd.m !== m) break;
      cells.push(h('button', {
        class: ['cal-day', dd.m !== m && 'other', d < today && 'past', d === today && 'today', d === draft.day && 'sel'],
        onclick: () => { draft.day = d; commit(); renderCal(); renderHint(); },
      }, dd.d));
    }
    fill(cal, 
      h('div', { class: 'cal-head' },
        h('button', { class: 'icon-btn sm', onclick: () => { viewMonth = addM(viewMonth, -1); renderCal(); } }, icon('chevleft', 16)),
        h('span', null, `${MON_LONG[m - 1]} ${y}`),
        h('button', { class: 'icon-btn sm', onclick: () => { viewMonth = addM(viewMonth, 1); renderCal(); } }, icon('chevright', 16))),
      h('div', { class: 'cal-grid' }, ['M', 'T', 'W', 'T', 'F', 'S', 'S'].map((w) => h('div', { class: 'wd' }, w)), cells));
  }
  const addM = ({ y, m }, n) => { const i = y * 12 + m - 1 + n; return { y: Math.floor(i / 12), m: (i % 12) + 1 }; };

  function repeatOptions() {
    const d = draft.day ?? today;
    const wd = WD[weekday(d)];
    const dom = fromDayNum(d).d;
    const wdName = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'][weekday(d)];
    return [
      ['none', 'Doesn’t repeat', null],
      ['day', 'Daily', { freq: 'day', interval: 1 }],
      ['weekdays', 'Every weekday (Mon–Fri)', { freq: 'week', interval: 1, byDay: ['mo', 'tu', 'we', 'th', 'fr'] }],
      ['week', `Weekly on ${wdName}`, { freq: 'week', interval: 1, byDay: [wd] }],
      ['2week', `Every 2 weeks on ${wdName}`, { freq: 'week', interval: 2, byDay: [wd] }],
      ['month', `Monthly on the ${ordinal(dom)}`, { freq: 'month', interval: 1, monthDay: dom }],
      ['year', 'Yearly', { freq: 'year', interval: 1 }],
      ['custom', 'Custom…', null],
    ];
  }
  function sameRepeat(a, b) {
    if (!a || !b) return !a && !b;
    return a.freq === b.freq && (a.interval || 1) === (b.interval || 1) && (a.byDay || []).join() === (b.byDay || []).join()
      && (a.monthDay || 0) === (b.monthDay || 0);
  }
  function renderRepeat() {
    const opts = repeatOptions();
    let selected = 'none';
    if (draft.repeat) selected = opts.find(([, , r]) => r && sameRepeat(r, draft.repeat) && !draft.repeat.fromCompletion)?.[0] || 'custom';
    fill(repeatSel, ...opts.map(([k, label]) => h('option', { value: k }, k === 'custom' && selected === 'custom' ? describeRepeat(draft.repeat) : label)));
    repeatSel.value = selected;
    custom.hidden = selected !== 'custom';
    if (selected === 'custom') renderCustom();
  }
  repeatSel.onchange = () => {
    const opt = repeatOptions().find(([k]) => k === repeatSel.value);
    if (repeatSel.value === 'custom') {
      draft.repeat = draft.repeat || { freq: 'week', interval: 1, byDay: [WD[weekday(draft.day ?? today)]] };
      custom.hidden = false;
      renderCustom();
    } else {
      draft.repeat = opt[2] ? structuredClone(opt[2]) : null;
      custom.hidden = true;
    }
    if (draft.repeat && draft.day == null) draft.day = today;
    commit(); renderCal(); renderHint();
  };
  function renderCustom() {
    const r = draft.repeat;
    const n = h('input', { type: 'number', min: 1, max: 365, value: r.interval || 1, class: 'field', style: { width: '64px' } });
    const unit = h('select', { class: 'grow' }, ['day', 'week', 'month', 'year'].map((u) => h('option', { value: u }, u + 's')));
    unit.value = r.freq;
    const after = h('input', { type: 'checkbox', checked: !!r.fromCompletion });
    const changed = () => {
      draft.repeat = { ...draft.repeat, interval: Math.max(1, +n.value || 1), freq: unit.value };
      if (unit.value !== 'week') delete draft.repeat.byDay;
      if (unit.value !== 'month') delete draft.repeat.monthDay;
      draft.repeat.fromCompletion = after.checked;
      commit(); renderCustom(); renderHint();
    };
    n.onchange = changed; unit.onchange = changed; after.onchange = changed;
    const wdRow = r.freq === 'week' ? h('div', { class: 'wd-toggles' }, WD.map((w, i) => h('button', {
      class: [(r.byDay || []).includes(w) && 'on'],
      onclick: () => {
        const set = new Set(draft.repeat.byDay || []);
        if (set.has(w)) set.delete(w); else set.add(w);
        draft.repeat.byDay = WD.filter((x) => set.has(x));
        commit(); renderCustom(); renderHint();
      },
    }, ['M', 'T', 'W', 'T', 'F', 'S', 'S'][i]))) : null;
    fill(custom, 
      h('div', { class: 'date-row' }, h('span', { style: { color: 'var(--fg-3)', fontSize: '13px' } }, 'Every'), n, unit),
      wdRow,
      h('label', { class: 'check-line' }, after, 'Count from completion date'));
  }

  function renderHint() {
    const q = input.value.trim();
    if (q) {
      const r = parseWhen(q, nowString());
      if (r.matched && (r.due || r.repeat)) {
        hint.className = 'pop-hint ok';
        const p = parseDue(r.due);
        hint.textContent = '↵ ' + (p ? relDay(p.day) + (p.min != null ? ' ' + fmtTime(p.min, ui.settings.h12) : '') : '') + (r.repeat ? ' · ' + describeRepeat(r.repeat) : '');
      } else {
        hint.className = 'pop-hint';
        hint.textContent = 'Not understood yet…';
      }
      return;
    }
    hint.className = 'pop-hint';
    if (draft.day == null) hint.textContent = 'No date';
    else hint.textContent = relDay(draft.day) + (draft.min != null ? ' ' + fmtTime(draft.min, ui.settings.h12) : ', all day') + (draft.repeat ? ' · ' + describeRepeat(draft.repeat) : '');
  }

  input.addEventListener('input', () => { renderHint(); });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && input.value.trim()) {
      e.preventDefault();
      const r = parseWhen(input.value.trim(), nowString());
      if (r.due) {
        const p = parseDue(r.due);
        draft.day = p.day; draft.min = p.min; draft.repeat = r.repeat || draft.repeat;
        if (r.repeat) draft.repeat = r.repeat;
        commit({ undoable: true });
        closePop();
      }
      return;
    }
    if (!input.value && /^[1-9]$/.test(e.key) && quickBtns[+e.key - 1]) {
      e.preventDefault();
      quickBtns[+e.key - 1].click();
      return;
    }
    nav.onKey(e);
  });
  timeIn.addEventListener('change', () => {
    if (timeIn.value) {
      const [hh, mm] = timeIn.value.split(':').map(Number);
      draft.min = hh * 60 + mm;
      if (draft.day == null) draft.day = draft.min > nowMin ? today : today + 1;
    } else draft.min = null;
    commit(); renderCal(); renderHint();
  });
  remindBtn.addEventListener('click', () => {
    draft.remind = !draft.remind;
    remindBtn.classList.toggle('on', draft.remind);
    fill(remindBtn, icon(draft.remind ? 'bell' : 'belloff', 16));
    store.updateMany(ids, { remind: draft.remind });
  });

  const el = h('div', { class: 'pop date-pop' },
    input, hint,
    h('div', { class: 'date-chips' }, quickBtns),
    h('div', { class: 'menu-sep' }),
    cal,
    h('div', { class: 'date-row' }, icon('clock', 15), timeIn, remindBtn),
    h('div', { class: 'date-row' }, icon('repeat', 15), repeatSel),
    custom);
  renderCal();
  renderRepeat();
  renderHint();
  openPop(el, anchor, { focus: input });
}

// ======================= labels =======================

export function labelPop(ids, anchor) {
  const items = () => ids.map((id) => store.get(id)).filter(Boolean);
  const input = h('input', { class: 'pop-input', placeholder: 'Filter or create label…', autocomplete: 'off', spellcheck: 'false' });
  const listEl = h('div', { class: 'menu-scroll' });
  let btns = [];
  const nav = navList(() => btns, { input: true });

  function render() {
    const q = normLabel(input.value);
    const its = items();
    const labels = allLabels().map((l) => l.name);
    let shown = labels;
    if (q) shown = labels.map((l) => [l, fuzzy(q, l)]).filter(([, s]) => s >= 0).sort((a, b) => b[1] - a[1]).map(([l]) => l);
    btns = shown.map((l) => {
      const all = its.every((it) => it.labels?.includes(l));
      const some = !all && its.some((it) => it.labels?.includes(l));
      return h('button', {
        class: 'menu-item',
        onclick: () => { act.toggleLabel(ids, l); render(); input.focus(); },
      }, h('span', { class: ['mini-box', all && 'on'], style: some ? { opacity: 0.6 } : null, html: BOX_SVG }),
        h('span', { class: 'grow' }, h('span', { class: 'hash', 'data-color': labelColor(l) }, '# '), l));
    });
    if (q && !labels.includes(q)) {
      btns.push(h('button', {
        class: 'menu-item',
        onclick: () => { act.toggleLabel(ids, q); input.value = ''; render(); input.focus(); },
      }, icon('plus', 15), h('span', { class: 'grow' }, `Create “${q}”`)));
    }
    fill(listEl, ...(btns.length ? btns : [h('div', { class: 'pop-hint' }, 'Type to create your first label')]));
    nav.set(0);
  }
  input.addEventListener('input', render);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Backspace' && !input.value) { e.preventDefault(); closePop(); return; }
    nav.onKey(e);
  });
  const el = h('div', { class: 'pop', style: { width: '260px' } }, input, listEl);
  render();
  openPop(el, anchor, { focus: input });
}

// ======================= colors =======================

export function colorPop(ids, anchor) {
  const it = store.get(ids[0]);
  let idx = Math.max(0, COLORS.indexOf(it?.color || ''));
  const sw = COLORS.map((c, i) => h('button', {
    class: ['swatch', (it?.color || '') === c && 'on'],
    'data-color': c || null,
    title: `${COLOR_NAMES[c]} (${i === 10 ? '-' : i})`,
    onclick: () => { act.setColor(ids, c); closePop(); },
  }, c === '' ? icon('x', 14) : null, h('span', { class: 'num' }, i === 10 ? '-' : i)));
  const setKb = (i) => { idx = (i + sw.length) % sw.length; sw.forEach((b, j) => b.classList.toggle('kb', j === idx)); sw[idx].focus(); };
  const el = h('div', { class: 'pop', tabindex: '-1' }, h('div', { class: 'swatches' }, sw));
  el.addEventListener('keydown', (e) => {
    if (/^[0-9]$/.test(e.key)) { e.preventDefault(); sw[+e.key].click(); }
    else if (e.key === '-') { e.preventDefault(); sw[10].click(); }
    else if (e.key === 'ArrowRight' || e.key === 'l') { e.preventDefault(); setKb(idx + 1); }
    else if (e.key === 'ArrowLeft' || e.key === 'h') { e.preventDefault(); setKb(idx - 1); }
    else if (e.key === 'ArrowDown' || e.key === 'j') { e.preventDefault(); setKb(idx + 6); }
    else if (e.key === 'ArrowUp' || e.key === 'k') { e.preventDefault(); setKb(idx - 6); }
  });
  openPop(el, anchor, { focus: sw[idx] });
  setKb(idx);
}

// ======================= prompt =======================

/** Small inline prompt popover. Resolves the entered string or null. */
export function promptPop(anchor, { placeholder = '', value = '', button = 'OK' } = {}) {
  return new Promise((resolve) => {
    let done = false;
    const input = h('input', { class: 'pop-input', placeholder, value, autocomplete: 'off', spellcheck: 'false' });
    const ok = () => { done = true; const v = input.value; closePop(); resolve(v); };
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); ok(); } });
    const el = h('div', { class: 'pop', style: { width: '280px' } }, input,
      h('div', { style: { display: 'flex', justifyContent: 'flex-end', gap: '6px' } },
        h('button', { class: 'btn sm ghost', onclick: () => closePop() }, 'Cancel'),
        h('button', { class: 'btn sm primary', onclick: ok }, button)));
    openPop(el, anchor, { focus: input, onClose: () => { if (!done) resolve(null); } });
    input.select();
  });
}
