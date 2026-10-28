// Item actions. Every destructive or completing action goes through `withUndo`.
import { liveChecks } from './fieldmerge.js';
import { store } from './store.js';
import { toast } from './toast.js';
import { completeItem } from './repeat.js';
import { nowString, parseDue, shortDate, fmtDueHuman, fmtMin, fmtDue, relDay, weekday, fromDayNum } from './dates.js';
import { newItem } from './model.js';
import { shortId } from './util.js';

/** Run `fn`, then show a toast whose Undo restores the snapshots. `fn` may return the message. */
function withUndo(ids, message, fn) {
  const snaps = store.snapshot(ids);
  const ret = fn();
  toast(typeof ret === 'string' ? ret : message, () => store.restore(snaps));
}

const plural = (n, one, many = one + 's') => `${n} ${n === 1 ? one : many}`;

export function toggleComplete(ids) {
  const items = ids.map((id) => store.get(id)).filter(Boolean);
  if (!items.length) return;
  const allDone = items.every((it) => it.done);
  if (allDone) {
    withUndo(ids, items.length === 1 ? 'Marked as not done' : `${plural(items.length, 'item')} reopened`, () => {
      store.updateMany(ids, { done: false });
    });
    return;
  }
  const now = nowString();
  withUndo(ids, '', () => {
    const results = [];
    for (const it of items) {
      if (it.done) continue;
      const next = completeItem(it, now);
      store.put(next, { silent: true });
      results.push(next);
    }
    store.emit('change', { ids });
    if (results.length === 1) {
      const r = results[0];
      return r.done ? `Completed “${trim(r.title || 'item')}”` : `Next: ${nextLabel(r.due)}`;
    }
    return `Completed ${plural(results.length, 'item')}`;
  });
}

function nextLabel(due) {
  const p = parseDue(due);
  if (!p) return '';
  return shortDate(p.day) + (p.min != null ? ` ${fmtMin(p.min)}` : '');
}

const trim = (s, n = 40) => (s.length > n ? s.slice(0, n - 1) + '…' : s);

export function setDue(ids, due, repeat) {
  const items = ids.map((id) => store.get(id)).filter(Boolean);
  const msg = due ? `Due ${fmtDueHuman(due)}` : 'Date removed';
  withUndo(ids, items.length > 1 ? `${plural(items.length, 'item')}: ${msg.toLowerCase()}` : msg, () => {
    store.updateMany(ids, (it) => {
      const p = { due, snoozedUntil: '' };
      if (repeat !== undefined) p.repeat = repeat;
      if (!due) p.repeat = null;
      if (due && it.done) p.done = false;
      return p;
    });
  });
}

export function setRepeat(ids, repeat) {
  store.updateMany(ids, (it) => {
    const p = { repeat };
    if (repeat && !it.due) {
      const d = new Date();
      p.due = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    }
    return p;
  });
}

export function togglePin(ids) {
  const items = ids.map((id) => store.get(id)).filter(Boolean);
  const pin = !items.every((it) => it.pinned);
  store.updateMany(ids, { pinned: pin });
  toast(pin ? (items.length > 1 ? `Pinned ${plural(items.length, 'item')}` : 'Pinned') : 'Unpinned');
}

export function setColor(ids, color) {
  store.updateMany(ids, { color });
}

export function toggleLabel(ids, label) {
  const items = ids.map((id) => store.get(id)).filter(Boolean);
  const has = items.every((it) => it.labels?.includes(label));
  store.updateMany(ids, (it) => {
    const set = new Set(it.labels || []);
    if (has) set.delete(label); else set.add(label);
    return { labels: [...set].sort() };
  });
  return !has;
}

export function toggleArchive(ids) {
  const items = ids.map((id) => store.get(id)).filter(Boolean);
  const archive = !items.every((it) => it.archived);
  withUndo(ids, archive ? (items.length > 1 ? `Archived ${plural(items.length, 'item')}` : 'Archived') : 'Unarchived', () => {
    store.updateMany(ids, archive ? { archived: true, pinned: false } : { archived: false });
  });
}

export function trash(ids) {
  const n = ids.length;
  withUndo(ids, n > 1 ? `Moved ${plural(n, 'item')} to trash` : 'Moved to trash', () => {
    store.updateMany(ids, { trashedAt: Date.now(), pinned: false });
  });
}

export function restore(ids) {
  withUndo(ids, ids.length > 1 ? `Restored ${plural(ids.length, 'item')}` : 'Restored', () => {
    store.updateMany(ids, { trashedAt: 0 });
  });
}

export function deleteForever(ids) {
  withUndo(ids, ids.length > 1 ? `Deleted ${plural(ids.length, 'item')} forever` : 'Deleted forever', () => {
    store.updateMany(ids, { deleted: true });
  });
}

export function snooze(ids, until) {
  store.updateMany(ids, { snoozedUntil: until });
}

export function duplicate(id) {
  const it = store.get(id);
  if (!it) return null;
  const { id: _id, rev: _rev, createdAt: _c, updatedAt: _u, sort: _s, ...rest } = structuredClone(it);
  const copy = newItem({
    ...rest,
    checks: liveChecks(it).map(({ id: _cid, ...c }) => c),
    done: false, doneAt: 0, trashedAt: 0, archived: false,
  });
  store.put(copy);
  toast('Copy created', () => store.update(copy.id, { deleted: true }));
  return copy;
}

export function renameLabel(from, to) {
  const ids = store.all().filter((it) => it.labels?.includes(from)).map((it) => it.id);
  withUndo(ids, `Renamed #${from} to #${to}`, () => {
    store.updateMany(ids, (it) => ({ labels: [...new Set(it.labels.map((l) => (l === from ? to : l)))].sort() }));
  });
}

export function deleteLabel(name) {
  const ids = store.all().filter((it) => it.labels?.includes(name)).map((it) => it.id);
  withUndo(ids, `Removed label #${name}`, () => {
    store.updateMany(ids, (it) => ({ labels: it.labels.filter((l) => l !== name) }));
  });
}

export function convertKind(id) {
  const it = store.get(id);
  if (!it) return;
  if (it.kind === 'list') {
    const body = liveChecks(it).map((c) => (c.done ? '[x] ' : '') + c.text).join('\n');
    store.update(id, { kind: 'note', body: [it.body, body].filter(Boolean).join('\n'), checks: [] });
  } else {
    const lines = (it.body || '').split('\n').filter((l) => l.trim());
    const checks = lines.map((l) => {
      const m = /^\s*(?:[-*•]\s+|\[( |x|X)?\]\s*)?(.*)$/.exec(l);
      return { id: shortId(), text: m[2], done: m[1] === 'x' || m[1] === 'X', indent: 0 };
    });
    store.update(id, { kind: 'list', body: '', checks });
  }
}

/** Reschedule to another day, keeping each item's time of day. */
export function moveToDay(ids, day) {
  const items = ids.map((id) => store.get(id)).filter(Boolean);
  if (!items.length) return;
  const { d } = fromDayNum(day);
  const rel = relDay(day);
  const name = ['Today', 'Tomorrow', 'Yesterday'].includes(rel) ? rel : `${['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'][weekday(day)]} ${d}`;
  withUndo(ids, items.length > 1 ? `Moved ${plural(items.length, 'item')} to ${name}` : `Moved to ${name}`, () => {
    store.updateMany(ids, (it) => ({ due: fmtDue(day, parseDue(it.due)?.min ?? null), snoozedUntil: '', done: it.repeat ? it.done : false }));
  });
}

/** Append image refs (already queued for upload) to an item. */
export function addImages(id, refs) {
  if (!refs.length) return;
  store.update(id, (it) => ({ images: [...(it.images || []), ...refs] }));
}

export function removeImage(id, imageId) {
  withUndo([id], 'Image removed', () => {
    store.update(id, (it) => ({ images: (it.images || []).filter((x) => x.id !== imageId) }));
  });
}
