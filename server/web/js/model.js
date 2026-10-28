// View queries and item helpers (SPEC §1).
import { liveChecks, pruneTombstones } from './fieldmerge.js';
import { store, isEmpty } from './store.js';
import { parseDue, todayNum, nowString, isOverdue } from './dates.js';
import { ulid, shortId, norm } from './util.js';

export const isTask = (it) => !!it.due || !!it.done;
const live = (it) => !it.deleted && !it.trashedAt;
// Task lists never show items without any text (old clients could leave such items behind).
const liveTask = (it) => live(it) && !isEmpty(it);

export function newItem(fields = {}) {
  const now = Date.now();
  return {
    id: ulid(now),
    kind: 'note',
    title: '',
    body: '',
    checks: [],
    color: '',
    labels: [],
    pinned: false,
    archived: false,
    done: false,
    doneAt: 0,
    due: '',
    remind: true,
    snoozedUntil: '',
    repeat: null,
    sort: now,
    createdAt: now,
    updatedAt: now,
    trashedAt: 0,
    deleted: false,
    ...fields,
    checks: (fields.checks || []).map((c) => ({ id: shortId(), indent: 0, done: false, ...c })),
  };
}

function dueKey(it) {
  const p = parseDue(it.due);
  if (!p) return Infinity;
  return p.day * 1440 + (p.min ?? -1);
}
const bySortDesc = (a, b) => (b.sort || 0) - (a.sort || 0);
const byDue = (a, b) => dueKey(a) - dueKey(b) || bySortDesc(a, b);

export function todayItems() {
  const t = todayNum();
  const items = store.all().filter((it) => liveTask(it) && !it.archived && !it.done && it.due && parseDue(it.due)?.day <= t);
  const now = new Date();
  const overdue = items.filter((it) => parseDue(it.due).day < t).sort(byDue);
  const today = items.filter((it) => parseDue(it.due).day === t).sort(byDue);
  return { overdue, today, now };
}

export function upcomingGroups() {
  const t = todayNum();
  const items = store.all().filter((it) => liveTask(it) && !it.archived && !it.done && it.due && parseDue(it.due)?.day > t).sort(byDue);
  const groups = [];
  let cur = null;
  for (const it of items) {
    const d = parseDue(it.due).day;
    if (!cur || cur.day !== d) { cur = { day: d, items: [] }; groups.push(cur); }
    cur.items.push(it);
  }
  return groups;
}

export function notesItems(label = null) {
  const items = store.all().filter((it) => live(it) && !it.archived && !it.done && (!label || it.labels?.includes(label)));
  return {
    pinned: items.filter((it) => it.pinned).sort(bySortDesc),
    others: items.filter((it) => !it.pinned).sort(bySortDesc),
  };
}

export const doneItems = () => store.all().filter((it) => live(it) && it.done).sort((a, b) => (b.doneAt || 0) - (a.doneAt || 0));
export const archiveItems = () => store.all().filter((it) => live(it) && it.archived && !it.done).sort((a, b) => b.updatedAt - a.updatedAt);
export const trashItems = () => store.all().filter((it) => !it.deleted && it.trashedAt).sort((a, b) => b.trashedAt - a.trashedAt);

export function searchItems(q) {
  const terms = norm(q).split(/\s+/).filter(Boolean);
  if (!terms.length) return [];
  return store.all().filter((it) => {
    if (it.trashedAt) return false;
    const hay = norm([it.title, it.body, ...liveChecks(it).map((c) => c.text), ...(it.labels || []).map((l) => '#' + l + ' ' + l)].join('\n'));
    return terms.every((t) => hay.includes(t));
  }).sort((a, b) => (a.archived - b.archived) || (a.done - b.done) || (b.pinned - a.pinned) || bySortDesc(a, b));
}

export function allLabels() {
  const counts = new Map();
  for (const it of store.all()) {
    if (it.trashedAt) continue;
    for (const l of it.labels || []) {
      if (!counts.has(l)) counts.set(l, 0);
      if (!it.archived && !it.done) counts.set(l, counts.get(l) + 1);
    }
  }
  return [...counts.entries()].sort((a, b) => a[0].localeCompare(b[0])).map(([name, count]) => ({ name, count }));
}

export function counts() {
  const { overdue, today } = todayItems();
  const t = todayNum();
  let upcoming = 0;
  for (const it of store.all()) {
    if (liveTask(it) && !it.archived && !it.done && it.due && parseDue(it.due)?.day > t) upcoming++;
  }
  return { today: overdue.length + today.length, overdue: overdue.length, upcoming };
}

export function overdueCount() {
  const now = new Date();
  let n = 0;
  for (const it of store.all()) if (liveTask(it) && !it.archived && !it.done && it.due && isOverdue(it.due, now)) n++;
  return n;
}

export function normLabel(s) {
  return norm(s).replace(/^#/, '').trim().replace(/\s+/g, '-').replace(/[^\p{L}\p{N}_-]/gu, '');
}

/** Move trash older than 30 days to deleted. */
export function purgeTrash() {
  const cutoff = Date.now() - 30 * 86400000;
  const old = store.all().filter((it) => it.trashedAt && it.trashedAt < cutoff);
  if (old.length) store.updateMany(old.map((i) => i.id), { deleted: true });
  // Check tombstones older than 30 days are dropped (§2b); stamp() removes them on write.
  const stale = store.all().filter((it) => pruneTombstones(it));
  if (stale.length) store.updateMany(stale.map((i) => i.id), (it) => ({ checks: pruneTombstones(it) }));
}

export { nowString };

/** Today's progress: tasks finished today vs. still open today (including overdue). */
export function todayProgress() {
  const { overdue, today } = todayItems();
  const start = new Date(); start.setHours(0, 0, 0, 0);
  const t0 = start.getTime();
  let done = 0;
  for (const it of store.all()) if (!it.trashedAt && it.doneAt >= t0 && !isEmpty(it)) done++;
  return { done, open: overdue.length + today.length, total: done + overdue.length + today.length };
}

/** Split today's items into All day / Morning / Afternoon / Evening. */
export function dayParts(items) {
  const parts = { allday: [], morning: [], afternoon: [], evening: [] };
  for (const it of items) {
    const p = parseDue(it.due);
    if (p.min == null) parts.allday.push(it);
    else if (p.min < 12 * 60) parts.morning.push(it);
    else if (p.min < 17 * 60) parts.afternoon.push(it);
    else parts.evening.push(it);
  }
  return parts;
}

/** Item counts per day for the next `n` days (Upcoming week strip). Index 0 = today. */
export function dayCounts(n = 14) {
  const t = todayNum();
  const counts = new Array(n).fill(0);
  for (const it of store.all()) {
    if (!liveTask(it) || it.archived || it.done || !it.due) continue;
    const d = parseDue(it.due).day - t;
    if (d >= 0 && d < n) counts[d]++;
  }
  return counts;
}

/** Open (not done/archived/trashed) items due on one day, in due order. */
export function itemsOnDay(day) {
  return store.all().filter((it) => liveTask(it) && !it.archived && !it.done && it.due && parseDue(it.due)?.day === day).sort(byDue);
}
