// Rendering of sidebar, header and the main view (cards grid / task lists).
import { liveChecks } from './fieldmerge.js';
import { h, icon, linkify, modKey, fill, labelColor, BRAND, BOX_SVG, CHECK_SVG } from './util.js';
import { store } from './store.js';
import { ui, hooks, viewKey } from './ui.js';
import {
  todayItems, upcomingGroups, notesItems, doneItems, archiveItems, trashItems, searchItems,
  allLabels, counts, isTask, todayProgress, dayParts, dayCounts, itemsOnDay,
} from './model.js';
import {
  parseDue, todayNum, relDay, describeRepeat, isOverdue, fmtTime, shortDate, fromDayNum, weekday, MON_LONG,
} from './dates.js';
import * as act from './actions.js';
import { keyedFlip, fadeIn, bump, measure, reduced, anim } from './motion.js';
import { ART } from './art.js';
import { isDefaultAlerts, describeAlerts } from './alerts.js';
import { cardImages, rowThumb } from './images.js';

const VIEWS = {
  today: { title: 'Today', icon: 'sun', key: 't', tone: 'amber' },
  upcoming: { title: 'Upcoming', icon: 'upcoming', key: 'u', tone: 'rose' },
  notes: { title: 'Notes', icon: 'bulb', key: 'n', tone: 'yellow' },
  done: { title: 'Done', icon: 'done', key: 'd', tone: 'green' },
  archive: { title: 'Archive', icon: 'archive', key: 'a', tone: 'slate' },
  trash: { title: 'Trash', icon: 'trash', key: 'r', tone: 'slate' },
};
export { VIEWS };

export const isListView = () => ['today', 'upcoming', 'done'].includes(ui.view.type) && !ui.query;

const WD_LONG = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
const WD_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const MON_SHORT = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

// ======================= sidebar =======================


export function renderSidebar() {
  const el = document.getElementById('sidebar');
  const c = counts();
  const labels = allLabels();
  const navItem = (type, count, overdue = 0) => {
    const v = VIEWS[type];
    const active = ui.view.type === type && !ui.query;
    return h('button', {
      class: ['nav-item', active && 'active'],
      'data-drop': type,
      'data-tone': v.tone,
      onclick: () => hooks.go({ type }),
      title: `${v.title}  ·  g ${v.key}`,
    },
    h('span', { class: 'nav-ic' }, icon(v.icon, 16)),
    h('span', { class: 'label' }, v.title),
    overdue ? h('span', { class: 'count alert', 'data-ck': type + ':over', title: `${overdue} overdue` }, overdue) : null,
    count ? h('span', { class: 'count', 'data-ck': type }, count) : null);
  };
  fill(el,
    h('div', { class: 'brand' },
      h('span', { class: 'brand-mark', html: BRAND }),
      h('span', { class: 'brand-name' }, 'Jot'),
      h('span', { class: 'spacer' }),
      h('button', { class: 'icon-btn sm', title: `Command palette (${modKey}+K)`, onclick: () => hooks.palette() }, icon('command', 15)),
    ),
    h('button', { class: 'quick-find', onclick: () => hooks.palette() },
      icon('search', 15), h('span', null, 'Quick find'), h('span', { class: 'keys' }, h('kbd', null, modKey), h('kbd', null, 'K'))),
    h('nav', { class: 'nav-group' },
      navItem('today', c.today, c.overdue),
      navItem('upcoming', c.upcoming),
      navItem('notes')),
    h('div', { class: 'nav-section' },
      h('span', null, 'Labels'),
      h('button', { title: 'New label', onclick: () => hooks.newLabel() }, icon('plus', 14))),
    h('div', { class: 'nav-labels' },
      labels.length ? labels.map((l) => h('button', {
        class: ['nav-item', 'nav-label', ui.view.type === 'label' && ui.view.label === l.name && !ui.query && 'active'],
        'data-drop': 'label:' + l.name,
        onclick: () => hooks.go({ type: 'label', label: l.name }),
      },
      h('span', { class: 'hashtag', 'data-color': labelColor(l.name) }, '#'),
      h('span', { class: 'label' }, l.name),
      l.count ? h('span', { class: 'count', 'data-ck': 'label:' + l.name }, l.count) : null))
        : h('div', { class: 'nav-empty' }, 'Type ', h('b', null, '#name'), ' while capturing to add a label.')),
    h('div', { class: 'nav-section' }, h('span', null, 'Library')),
    h('nav', { class: 'nav-group' },
      navItem('done'),
      navItem('archive'),
      navItem('trash')),
    h('div', { class: 'sidebar-foot' },
      h('span', { class: 'avatar' }, (store.user || '?').slice(0, 1).toUpperCase()),
      h('span', { class: 'user', title: 'Signed in as ' + store.user }, store.user),
      h('button', { class: 'icon-btn sm', title: 'Keyboard shortcuts (?)', onclick: () => hooks.showKeys() }, icon('keyboard', 16)),
      h('button', { class: 'icon-btn sm', title: 'Toggle theme', onclick: () => hooks.cycleTheme() }, icon('moon', 16)),
      h('button', { class: 'icon-btn sm', title: 'Settings', onclick: () => hooks.settings() }, icon('sliders', 16)),
    ),
  );
  // Counts that changed slide in instead of switching hard.
  const now = new Map([...el.querySelectorAll('[data-ck]')].map((n) => [n.dataset.ck, n]));
  if (prevCounts) {
    for (const [k, n] of now) if (prevCounts.get(k) !== n.textContent) bump(n);
  }
  prevCounts = new Map([...now].map(([k, n]) => [k, n.textContent]));
}
let prevCounts = null;

// ======================= topbar + hero header =======================

let topbarBuilt = false;
let heroKey = null;
let heroObserver = null;

export function renderTopbar() {
  const el = document.getElementById('topbar');
  if (!topbarBuilt) {
    topbarBuilt = true;
    fill(el,
      h('button', { class: 'icon-btn menu-btn', 'aria-label': 'Menu', onclick: () => document.getElementById('app').classList.toggle('nav-open') }, icon('menu', 20)),
      h('div', { class: 'mini-title', id: 'mini-title' }),
      h('label', { class: 'search', title: 'Search (/)' },
        icon('search', 16),
        h('input', {
          id: 'search', type: 'search', placeholder: 'Search notes and tasks', autocomplete: 'off', spellcheck: 'false',
          oninput: (e) => hooks.search(e.target.value),
          onkeydown: (e) => {
            if (e.key === 'Escape') { e.preventDefault(); e.target.value = ''; hooks.search(''); e.target.blur(); }
            if (e.key === 'Enter' || e.key === 'ArrowDown') { e.preventDefault(); e.target.blur(); hooks.moveCursor(0); }
          },
        }),
        h('kbd', null, '/')),
      h('button', { class: 'status-pill', id: 'sync-status', title: 'Sync now', onclick: () => store.sync() }, h('span', { class: 'sync-dot', id: 'sync-dot' })),
    );
    // The small topbar title appears once the big hero title scrolls away.
    heroObserver = new IntersectionObserver(([e]) => {
      document.getElementById('topbar').classList.toggle('scrolled', !e.isIntersecting);
    }, { root: document.getElementById('content'), threshold: 0, rootMargin: '-40px 0px 0px 0px' });
    heroObserver.observe(document.getElementById('hero'));
    // Sticky day headers sit right under the sticky capture bar.
    new ResizeObserver(([e]) => {
      document.getElementById('content').style.setProperty('--sticky-top', Math.round(e.target.offsetHeight) + 'px');
    }).observe(document.getElementById('capture'));
  }
  renderHero();
  document.getElementById('content').classList.toggle('view-list', isListView());
  document.querySelector('.main').classList.toggle('view-list', isListView());
}

function heroInfo() {
  const v = ui.view;
  if (ui.query) {
    const n = searchItems(ui.query).length;
    return { title: 'Search', sub: `${n} ${n === 1 ? 'result' : 'results'} for “${ui.query}”`, icon: 'search', tone: 'slate' };
  }
  if (v.type === 'label') {
    const n = notesItems(v.label);
    const total = n.pinned.length + n.others.length;
    return {
      title: v.label, hash: labelColor(v.label), sub: `${total} ${total === 1 ? 'note' : 'notes'}`,
      actions: [
        h('button', { class: 'btn sm', title: 'Rename label', onclick: () => hooks.renameLabel(v.label) }, icon('edit', 14), 'Rename'),
        h('button', { class: 'btn sm danger-soft', title: 'Remove this label from all notes', onclick: () => hooks.deleteLabel(v.label) }, icon('trash', 14), 'Delete'),
      ],
    };
  }
  const meta = VIEWS[v.type];
  const t = todayNum();
  const { y, m, d } = fromDayNum(t);
  switch (v.type) {
    case 'today':
      return { title: 'Today', sub: `${WD_LONG[weekday(t)]}, ${d} ${MON_LONG[m - 1]}`, progress: todayProgress(), icon: meta.icon, tone: meta.tone };
    case 'upcoming': {
      const c = counts().upcoming;
      return { title: 'Upcoming', sub: c ? `${c} scheduled · ${MON_LONG[m - 1]} ${y}` : 'Nothing scheduled yet', week: true, icon: meta.icon, tone: meta.tone };
    }
    case 'notes': {
      const n = notesItems();
      const total = n.pinned.length + n.others.length;
      return { title: 'Notes', sub: `${total} ${total === 1 ? 'note' : 'notes'}${n.pinned.length ? ` · ${n.pinned.length} pinned` : ''}`, icon: meta.icon, tone: meta.tone };
    }
    case 'done': {
      const n = doneItems().length;
      return { title: 'Done', sub: n ? `${n} completed` : 'Nothing completed yet', icon: meta.icon, tone: meta.tone };
    }
    case 'archive': {
      const n = archiveItems().length;
      return { title: 'Archive', sub: n ? `${n} archived` : 'Out of sight, not deleted', icon: meta.icon, tone: meta.tone };
    }
    case 'trash': {
      const n = trashItems().length;
      return {
        title: 'Trash', sub: 'Items are deleted for good after 30 days', icon: meta.icon, tone: meta.tone,
        actions: n ? [h('button', { class: 'btn sm danger-soft', onclick: () => act.deleteForever(trashItems().map((i) => i.id)) }, icon('trash', 14), 'Empty trash')] : null,
      };
    }
  }
  return { title: '' };
}

function progressRing(p) {
  const R = 19, C = 2 * Math.PI * R;
  const frac = p.total ? p.done / p.total : 0;
  return h('div', { class: ['progress', p.total && p.done === p.total && 'complete'], title: `${p.done} of ${p.total} done today` },
    h('span', {
      class: 'ring',
      html: `<svg viewBox="0 0 48 48" width="48" height="48"><circle cx="24" cy="24" r="${R}" class="ring-track"/><circle cx="24" cy="24" r="${R}" class="ring-bar" stroke-dasharray="${C.toFixed(2)}" stroke-dashoffset="${(C * (1 - frac)).toFixed(2)}"/></svg><span class="ring-pct">${Math.round(frac * 100)}%</span>`,
    }),
    h('span', { class: 'progress-text' },
      h('b', null, `${p.done} of ${p.total}`),
      h('span', null, p.total && p.done === p.total ? 'all done' : 'done today')));
}

function weekStrip() {
  const t = todayNum();
  const n = 14;
  const cnt = dayCounts(n);
  const sel = ui.upcomingDay;
  return h('div', { class: ['week-strip', sel != null && 'has-sel'], role: 'listbox', 'aria-label': 'Pick a day' },
    cnt.map((c, i) => {
      const d = t + i;
      const { d: dom } = fromDayNum(d);
      const wd = weekday(d);
      return h('button', {
        class: ['day-pill', i === 0 && 'is-today', wd >= 5 && 'weekend', !c && 'empty', sel === d && 'selected'],
        role: 'option',
        'aria-selected': sel === d ? 'true' : 'false',
        'data-drop-day': d,
        title: `${relDay(d)}: ${c ? c + ' ' + (c === 1 ? 'item' : 'items') : 'nothing scheduled'} · click to show only this day, drop an item to move it here`,
        onclick: () => hooks.selectDay(sel === d ? null : d),
      },
      h('span', { class: 'dp-wd' }, i === 0 ? 'Today' : WD_SHORT[wd]),
      h('span', { class: 'dp-num' }, dom),
      h('span', { class: 'dp-dots' }, Array.from({ length: Math.min(c, 3) }, () => h('i'))));
    }));
}

/** "Thursday, 29 Oct · 3 tasks" bar shown while the Upcoming list is filtered to one day. */
function dayFilterBar(day, n) {
  const { d, m } = fromDayNum(day);
  const rel = relDay(day);
  const name = rel === 'Today' || rel === 'Tomorrow' ? `${rel}, ${WD_LONG[weekday(day)]} ${d} ${MON_SHORT[m - 1]}` : `${WD_LONG[weekday(day)]}, ${d} ${MON_SHORT[m - 1]}`;
  return h('div', { class: 'day-filter', 'data-k': 'dayfilter' },
    h('span', { class: 'df-dot' }),
    h('span', { class: 'df-title' }, name),
    h('span', { class: 'df-count' }, `· ${n} ${n === 1 ? 'task' : 'tasks'}`),
    h('span', { class: 'spacer' }),
    h('button', { class: 'btn xs', title: 'Show all days (Esc)', onclick: () => hooks.selectDay(null) }, 'Show all', h('kbd', null, 'Esc')));
}

function renderHero() {
  const host = document.getElementById('hero');
  const info = heroInfo();
  const key = (ui.query ? 'search' : viewKey());
  const titleEl = h('h1', { class: 'hero-title' },
    info.hash ? h('span', { class: 'hero-hash', 'data-color': info.hash }, '#') : null,
    info.title);
  const parts = [
    h('div', { class: 'hero-main' },
      info.icon ? h('span', { class: 'hero-ic', 'data-tone': info.tone }, icon(info.icon, 20)) : null,
      h('div', { class: 'hero-text' }, titleEl, h('div', { class: 'hero-sub' }, info.sub))),
    h('div', { class: 'hero-side' },
      info.progress && info.progress.total ? progressRing(info.progress) : null,
      info.actions ? h('div', { class: 'hero-actions' }, info.actions) : null),
  ];
  const wasKey = heroKey;
  heroKey = key;
  if (wasKey === key) {
    // Same view: update in place so the progress ring can animate.
    const bar = host.querySelector('.ring-bar');
    fill(host.querySelector('.hero-row'), ...parts);
    const nb = host.querySelector('.ring-bar');
    if (bar && nb && bar.getAttribute('stroke-dashoffset') !== nb.getAttribute('stroke-dashoffset')) {
      anim(nb, [{ strokeDashoffset: bar.getAttribute('stroke-dashoffset') }, { strokeDashoffset: nb.getAttribute('stroke-dashoffset') }], { duration: 500 });
    }
    const ws = host.querySelector('.week-strip');
    if (info.week && ws) ws.replaceWith(weekStrip());
  } else {
    fill(host, h('div', { class: 'hero-row' }, parts), info.week ? weekStrip() : null);
  }
  fill(document.getElementById('mini-title'), info.hash ? '#' + info.title : info.title);
}

export function renderStatus() {
  const dot = document.getElementById('sync-dot');
  const pill = document.getElementById('sync-status');
  if (!dot) return;
  dot.className = 'sync-dot ' + store.status;
  const labels = { idle: 'Synced', syncing: 'Syncing…', offline: 'Offline — changes are saved locally', error: 'Sync error' };
  pill.title = (labels[store.status] || '') + (store.dirty.size ? ` (${store.dirty.size} pending)` : '') + ' · click to sync';
  let txt = pill.querySelector('.txt');
  const show = store.status === 'offline' || store.status === 'error';
  if (show && !txt) pill.append(h('span', { class: 'txt' }, store.status === 'offline' ? 'Offline' : 'Sync error'));
  else if (!show && txt) txt.remove();
  else if (txt) txt.textContent = store.status === 'offline' ? 'Offline' : 'Sync error';
}

// ======================= item pieces =======================


/** Has more alerts than the default "on time"? Rows and cards then show a small bell. */
const extraAlerts = (it) => it.remind !== false && !isDefaultAlerts(it.alerts);
const alertTitle = (it, p) => (it.remind === false ? 'Reminders off' : 'Alerts: ' + describeAlerts(it.alerts, p.min == null));

function checkButton(it) {
  const over = !it.done && it.due && isOverdue(it.due);
  return h('button', {
    class: ['check', it.done && 'on', over && 'p-overdue'],
    'data-act': 'complete',
    'data-color': it.color || null,
    title: it.done ? 'Mark not done (x)' : it.repeat ? 'Complete — moves to the next date (x)' : 'Complete (x)',
    'aria-label': it.done ? 'Mark not done' : 'Complete',
    html: CHECK_SVG,
  });
}

function labelChip(l, cls = 'mchip label') {
  return h('span', { class: cls, 'data-act': 'golabel', 'data-label': l, title: 'Go to #' + l },
    h('span', { class: 'hash', 'data-color': labelColor(l) }, '#'), l);
}

function dueChip(it) {
  const p = parseDue(it.due);
  if (!p) return null;
  const now = new Date();
  const over = !it.done && isOverdue(it.due, now);
  const isToday = p.day === todayNum(now);
  let text = relDay(p.day);
  if (p.min != null) text = (isToday ? '' : text + ' ') + fmtTime(p.min, ui.settings.h12);
  return h('span', { class: ['mchip', over ? 'overdue' : isToday ? 'today' : 'date'], title: it.repeat ? describeRepeat(it.repeat) : '' },
    icon(it.repeat ? 'repeat' : p.min != null ? 'clock' : 'calendar', 12), text,
    it.remind === false ? icon('belloff', 11) : extraAlerts(it) ? icon('bell', 11) : null);
}

function checksPreview(it, max = 6) {
  const checks = liveChecks(it);
  const open = checks.filter((c) => !c.done);
  const done = checks.length - open.length;
  const shown = open.slice(0, max);
  return h('ul', { class: 'card-checks' },
    shown.map((c) => h('li', { class: [c.indent && 'indent'] },
      h('button', { class: 'mini-box', 'data-act': 'check', 'data-check': c.id, 'aria-label': 'Check', html: BOX_SVG }),
      h('span', null, c.text || ' '))),
    open.length > max || done ? h('li', { class: 'more' },
      open.length > max ? `+${open.length - max} more` : '',
      open.length > max && done ? ' · ' : '',
      done ? `${done} checked` : '') : null);
}

function cardBody(it) {
  if (it.kind === 'list') return checksPreview(it);
  if (!it.body) return null;
  const body = it.body.length > 1500 ? it.body.slice(0, 1500) : it.body;
  const short = body.split('\n').length < 12 && body.length < 520;
  const big = !it.title && body.length < 80 && !body.includes('\n');
  return h('div', { class: ['card-body', short && 'short', big && 'big'] }, linkify(body));
}

// ======================= card (grid) =======================

function buildCard(it) {
  const inTrash = !!it.trashedAt;
  const task = isTask(it);
  const titleEl = it.title ? h('div', { class: ['card-title', it.done && 'done'] }, it.title) : null;
  const tools = inTrash
    ? [
      h('button', { class: 'icon-btn', 'data-act': 'restore', title: 'Restore' }, icon('restore', 16)),
      h('button', { class: 'icon-btn', 'data-act': 'delforever', title: 'Delete forever' }, icon('trash', 16)),
    ]
    : [
      h('button', { class: 'icon-btn', 'data-act': 'complete', title: it.done ? 'Not done (x)' : 'Complete (x)' }, icon(it.done ? 'restore' : 'done', 16)),
      h('button', { class: 'icon-btn', 'data-act': 'date', title: 'Date & reminder (d)' }, icon('calendar', 16)),
      h('button', { class: 'icon-btn', 'data-act': 'color', title: 'Color (c)' }, icon('palette', 16)),
      h('button', { class: 'icon-btn', 'data-act': 'label', title: 'Labels (l)' }, icon('tag', 16)),
      h('button', { class: 'icon-btn', 'data-act': 'archive', title: it.archived ? 'Unarchive (a)' : 'Archive (a)' }, icon(it.archived ? 'unarchive' : 'archive', 16)),
      h('span', { class: 'spacer' }),
      h('button', { class: 'icon-btn', 'data-act': 'more', title: 'More' }, icon('more', 16)),
    ];
  const meta = [dueChip(it), it.labels?.map((l) => labelChip(l))].flat().filter(Boolean);
  return h('div', {
    class: ['card', it.pinned && 'pinned', it.images?.length && 'has-images', it.images?.length && !it.title && !it.body && !liveChecks(it).length && 'only-images'],
    'data-id': it.id,
    'data-k': it.id,
    'data-color': it.color || null,
    draggable: inTrash ? null : 'true',
  },
  cardImages(it),
  !inTrash && h('button', { class: ['icon-btn sm card-pin', it.pinned && 'on', it.images?.length && 'over-img'], 'data-act': 'pin', title: it.pinned ? 'Unpin (p)' : 'Pin (p)' }, icon('pin', 15)),
  task && it.title
    ? h('div', { class: 'card-task' }, checkButton(it), titleEl)
    : titleEl,
  cardBody(it),
  !it.title && !it.body && !liveChecks(it).length && !(it.images || []).length ? h('div', { class: 'card-body short empty-note' }, 'Empty note') : null,
  meta.length ? h('div', { class: 'card-meta' }, meta) : null,
  h('div', { class: 'card-tools' }, tools));
}

// ======================= row (list) =======================

function buildRow(it, { showDate = false } = {}) {
  const p = parseDue(it.due);
  const now = new Date();
  const over = !it.done && it.due && isOverdue(it.due, now);
  const chips = [];
  if (p && (showDate || p.min != null || over)) {
    let txt = '';
    if (showDate || over) txt = relDay(p.day);
    if (p.min != null) txt += (txt ? ' · ' : '') + fmtTime(p.min, ui.settings.h12);
    chips.push(h('span', { class: ['mchip', over ? 'overdue' : 'time'], title: alertTitle(it, p) }, icon(p.min != null ? 'clock' : 'calendar', 12), txt, extraAlerts(it) ? icon('bell', 11) : null));
  } else if (p && extraAlerts(it)) {
    chips.push(h('span', { class: 'mchip time', title: alertTitle(it, p) }, icon('bell', 11)));
  }
  if (it.repeat) chips.push(h('span', { class: 'mchip repeat', title: describeRepeat(it.repeat) }, icon('repeat', 12), describeRepeat(it.repeat)));
  if (it.snoozedUntil) {
    const s = parseDue(it.snoozedUntil);
    if (s) chips.push(h('span', { class: 'mchip snooze' }, icon('bell', 12), 'Snoozed · ' + relDay(s.day) + (s.min != null ? ' ' + fmtTime(s.min, ui.settings.h12) : '')));
  }
  if (it.kind === 'list' && liveChecks(it).length) {
    const d = liveChecks(it).filter((c) => c.done).length;
    chips.push(h('span', { class: 'mchip' }, icon('list', 12), `${d}/${liveChecks(it).length}`));
  }
  for (const l of it.labels || []) chips.push(labelChip(l));
  if (it.done && it.doneAt) chips.push(h('span', { class: 'mchip' }, icon('done', 12), 'Done ' + relDay(todayNum(new Date(it.doneAt))).toLowerCase()));
  const preview = it.kind !== 'list' && it.body ? (it.body.split('\n').find((l) => l.trim()) || '') : '';

  return h('div', { class: ['row', it.done && 'is-done'], 'data-id': it.id, 'data-k': it.id, 'data-color': it.color || null, draggable: 'true' },
    checkButton(it),
    h('div', { class: 'row-main' },
      h('div', { class: 'row-title' }, it.title || h('span', { class: 'empty' }, firstLine(it) || (it.images?.length ? 'Image' : 'Untitled'))),
      preview && it.title ? h('div', { class: 'row-preview' }, preview) : null,
      chips.length ? h('div', { class: 'row-sub' }, chips) : null),
    rowThumb(it),
    h('div', { class: 'row-tools' },
      h('button', { class: 'icon-btn', 'data-act': 'date', title: 'Reschedule (d)' }, icon('calendar', 15)),
      h('button', { class: 'icon-btn', 'data-act': 'tomorrow', title: 'Tomorrow (t)' }, icon('forward', 15)),
      h('button', { class: 'icon-btn', 'data-act': 'label', title: 'Labels (l)' }, icon('tag', 15)),
      h('button', { class: 'icon-btn', 'data-act': 'more', title: 'More' }, icon('more', 15))));
}

function firstLine(it) {
  if (it.kind === 'list') return liveChecks(it)[0]?.text || '';
  return (it.body || '').split('\n').find((l) => l.trim()) || '';
}

// ======================= keyed cache =======================

const cache = new Map(); // id → { sig, el }

function sigOf(it, mode) {
  const minute = it.due ? Math.floor(Date.now() / 60000) : 0;
  return `${mode}|${it.updatedAt}|${it.rev || 0}|${minute}|${ui.settings.h12}`;
}

function el(it, mode, opts) {
  const sig = sigOf(it, mode) + (opts?.showDate ? '|d' : '');
  const c = cache.get(it.id);
  if (c && c.sig === sig) return c.el;
  const node = mode === 'card' ? buildCard(it) : buildRow(it, opts);
  cache.set(it.id, { sig, el: node });
  return node;
}

// ======================= main view =======================

const grids = new Set();
let lastViewKey = null;

function grid(items) {
  const g = h('div', { class: 'grid' });
  g.append(...items.map((it) => { ui.order.push(it.id); return el(it, 'card'); }));
  grids.add(g);
  return g;
}

function list(items, opts) {
  const l = h('div', { class: 'list' });
  l.append(...items.map((it) => { ui.order.push(it.id); return el(it, 'row', opts); }));
  return l;
}

function section(title, opts = {}) {
  return h('div', {
    class: ['section-h', opts.tone && 'tone-' + opts.tone],
    'data-k': 'sec:' + title,
  },
  opts.icon ? icon(opts.icon, 14) : null,
  h('span', { class: 'sec-title' }, title),
  opts.count != null ? h('span', { class: 'sec-count' }, opts.count) : null,
  h('span', { class: 'sec-line' }),
  opts.right ? h('span', { class: 'right' }, opts.right) : null);
}

function dayHead(day) {
  const t = todayNum();
  const diff = day - t;
  const { y, m, d } = fromDayNum(day);
  const main = diff === 1 ? 'Tomorrow' : diff < 7 ? WD_LONG[weekday(day)] : `${WD_SHORT[weekday(day)]} ${d} ${MON_SHORT[m - 1]}`;
  const sub = diff === 1 ? `${WD_SHORT[weekday(day)]} ${d}` : diff < 7 ? `${d} ${MON_SHORT[m - 1]}` : (y !== fromDayNum(t).y ? String(y) : `in ${diff} days`);
  return h('div', { class: 'day-head', 'data-k': 'day:' + day, 'data-day': day },
    h('span', { class: 'dh-num' }, d),
    h('span', { class: 'dh-main' }, main),
    sub ? h('span', { class: 'dh-sub' }, '· ' + sub) : null);
}

function empty(art, title, text, extra = null) {
  return h('div', { class: 'empty-state', 'data-k': 'empty:' + title },
    h('div', { class: 'empty-art', html: ART[art] }),
    h('h3', null, title),
    h('p', null, text),
    extra);
}

const kbdHint = (...parts) => h('span', null, ...parts.map((p) => (typeof p === 'string' && p.startsWith('[') ? h('kbd', null, p.slice(1, -1)) : p)));

export function renderView() {
  const host = document.getElementById('view');
  // Measure before building: building re-parents the cached nodes out of the page.
  // A day filter counts as its own view, so switching days crossfades the list.
  const vk = (ui.query ? 'search:' : '') + viewKey() + (ui.view.type === 'upcoming' && ui.upcomingDay != null ? '@' + ui.upcomingDay : '');
  const before = vk === lastViewKey && !reduced() ? measure(host, 'data-k') : null;
  ui.order = [];
  grids.clear();
  const v = ui.view;
  const out = [];

  if (ui.query) {
    const res = searchItems(ui.query);
    if (!res.length) out.push(empty('search', 'Nothing matches', kbdHint('Try fewer words or a #label. Press ', '[Esc]', ' to clear the search.')));
    else {
      const live = res.filter((i) => !i.archived);
      const arch = res.filter((i) => i.archived);
      if (live.length) out.push(grid(live));
      if (arch.length) out.push(section('Archived', { icon: 'archive', count: arch.length }), grid(arch));
    }
  } else if (v.type === 'today') {
    const { overdue, today } = todayItems();
    if (!overdue.length && !today.length) {
      const p = todayProgress();
      out.push(empty('clear', p.done ? 'All clear. Nice work.' : 'All clear for today',
        p.done ? `You finished ${p.done} ${p.done === 1 ? 'thing' : 'things'} today. Enjoy the rest of it.`
          : kbdHint('Nothing due. Capture something with a date, like “call mom 5pm”. Press ', '[q]', ' to start.')));
    }
    if (overdue.length) {
      out.push(section('Overdue', {
        icon: 'flame', tone: 'danger', count: overdue.length,
        right: h('button', { class: 'btn xs', onclick: () => act.setDue(overdue.map((i) => i.id), todayStr()) }, icon('forward', 13), 'Move to today'),
      }));
      out.push(list(overdue, { showDate: true }));
    }
    const parts = dayParts(today);
    const PARTS = [
      ['allday', 'All day', 'calendar'],
      ['morning', 'Morning', 'sun'],
      ['afternoon', 'Afternoon', 'sun'],
      ['evening', 'Evening', 'moon'],
    ];
    for (const [k, title, ic] of PARTS) {
      if (!parts[k].length) continue;
      out.push(section(title, { icon: k === 'afternoon' ? 'sunset' : ic, count: parts[k].length, tone: k }));
      out.push(list(parts[k]));
    }
  } else if (v.type === 'upcoming' && ui.upcomingDay != null) {
    const day = ui.upcomingDay;
    const items = itemsOnDay(day);
    out.push(dayFilterBar(day, items.length));
    if (items.length) out.push(list(items));
    else {
      const rel = relDay(day);
      const name = rel === 'Today' || rel === 'Tomorrow' ? rel.toLowerCase() : WD_LONG[weekday(day)];
      out.push(empty('calendar', `Nothing planned for ${name}`, kbdHint('A free day. Type in the bar above to add something to it, or drag a task onto its pill. ', '[[]', ' ', '[]]', ' switch days.')));
    }
  } else if (v.type === 'upcoming') {
    const groups = upcomingGroups();
    if (!groups.length) out.push(empty('calendar', 'Nothing scheduled', kbdHint('Items with a future date land here, grouped by day. Try “dentist fri 10am” in the capture bar.')));
    for (const g of groups) {
      out.push(dayHead(g.day));
      out.push(list(g.items));
    }
  } else if (v.type === 'notes' || v.type === 'label') {
    const { pinned, others } = notesItems(v.type === 'label' ? v.label : null);
    if (!pinned.length && !others.length) {
      out.push(v.type === 'label'
        ? empty('tag', `Nothing in #${v.label} yet`, 'Notes you capture while this label is open get it automatically.')
        : empty('notes', 'Your notes live here', kbdHint('Press ', '[q]', ' and start typing. Add a ', '[#label]', ', a date like “fri 9am”, or paste a few lines to make a checklist.')));
    }
    if (pinned.length) {
      out.push(section('Pinned', { icon: 'pin', count: pinned.length, tone: 'accent' }), grid(pinned));
      if (others.length) out.push(section('Others', { count: others.length }));
    }
    if (others.length) out.push(grid(others));
  } else if (v.type === 'done') {
    const items = doneItems();
    if (!items.length) out.push(empty('done', 'Nothing completed yet', 'Finished tasks collect here, so you can look back or reopen one.'));
    else out.push(list(items, { showDate: true }));
  } else if (v.type === 'archive') {
    const items = archiveItems();
    if (!items.length) out.push(empty('archive', 'The archive is empty', kbdHint('Press ', '[a]', ' on a note to tuck it away without deleting it.')));
    else out.push(grid(items));
  } else if (v.type === 'trash') {
    const items = trashItems();
    if (!items.length) out.push(empty('trash', 'Trash is empty', 'Deleted items wait here for 30 days before they are gone for good.'));
    else out.push(grid(items));
  }

  const update = () => { fill(host, ...out); applyStates(); layoutGrids(); };
  if (vk === lastViewKey) {
    // Same view: only genuine changes move (FLIP), enter or leave (ghosts).
    keyedFlip(host, 'data-k', update, { before });
  } else {
    update();
    if (lastViewKey !== null) fadeIn(host, { y: 8, duration: 180 });
  }
  lastViewKey = vk;
  // Drop cache entries for items no longer around.
  if (cache.size > ui.order.length + 400) {
    const keep = new Set(ui.order);
    for (const id of cache.keys()) if (!keep.has(id)) cache.delete(id);
  }
}

function todayStr() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** Toggle cursor / selection / open classes without re-rendering. */
export function applyStates() {
  const host = document.getElementById('view');
  for (const node of host.querySelectorAll('[data-id]')) {
    const id = node.dataset.id;
    node.classList.toggle('cursor', id === ui.cursor);
    node.classList.toggle('selected', ui.selected.has(id));
    node.classList.toggle('open', id === ui.openId);
  }
}

// ---------- masonry ----------
const ROW = 4, GAP = 16;
export function layoutGrids() {
  const all = [];
  for (const g of grids) for (const c of g.children) all.push(c);
  const heights = all.map((c) => c.offsetHeight); // offsetHeight ignores running transforms
  all.forEach((c, i) => { c.style.gridRowEnd = `span ${Math.ceil((heights[i] + GAP) / ROW)}`; });
}

const ro = new ResizeObserver(() => requestAnimationFrame(layoutGrids));
export function observeLayout() {
  ro.observe(document.getElementById('view'));
  document.fonts?.ready.then(layoutGrids);
}

export function scrollCursorIntoView() {
  const node = document.querySelector(`#view [data-id="${CSS.escape(ui.cursor || '')}"]`);
  node?.scrollIntoView({ block: 'nearest', behavior: reduced() ? 'auto' : 'smooth' });
}

export function viewTitle(v = ui.view) {
  return v.type === 'label' ? '#' + v.label : VIEWS[v.type]?.title || '';
}

export { viewKey, shortDate };
