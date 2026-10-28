// Field-level change tracking (SPEC §2b). Pure, no DOM. The store runs every local write through
// `stamp`, so no code path can forget to record what it changed.

export const TOMBSTONE_TTL = 30 * 86400000;

/** Field groups and the fields they cover. `order` (check order) is handled separately. */
export const GROUPS = {
  title: ['title'],
  body: ['body'],
  kind: ['kind'],
  color: ['color'],
  labels: ['labels'],
  pinned: ['pinned'],
  archived: ['archived'],
  schedule: ['done', 'doneAt', 'due', 'remind', 'alerts', 'snoozedUntil', 'repeat'],
  sort: ['sort'],
  images: ['images'],
  trash: ['trashedAt'],
};

const same = (a, b) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);

/** Checks the UI should see (tombstones hidden). */
export const liveChecks = (it) => (it?.checks || []).filter((c) => !c.deleted);

/**
 * Given the stored item `prev` (or null for a new item) and the caller's `next`, return the item to store:
 *  - fm[group] = now for every group whose fields changed,
 *  - every new or changed check gets u = now,
 *  - checks the caller dropped become tombstones {id, deleted: true, u: now}; existing tombstones are kept
 *    (callers may pass live checks only), tombstones older than 30 days are dropped,
 *  - adding, removing or reordering checks sets fm.order.
 * `updatedAt` is left to the caller (the store bumps it).
 */
export function stamp(prev, next, now = Date.now()) {
  const out = { ...next };
  // Max per group, so an older copy (e.g. an undo snapshot) can never move a stamp backwards.
  const fm = { ...(prev?.fm || {}) };
  for (const [g, t] of Object.entries(next.fm || {})) if (!(fm[g] >= t)) fm[g] = t;

  if (prev) {
    for (const [g, fields] of Object.entries(GROUPS)) {
      if (fields.some((f) => !same(prev[f], next[f]))) fm[g] = now;
    }
  }

  // ---- checks ----
  const prevChecks = prev?.checks || [];
  const prevById = new Map(prevChecks.map((c) => [c.id, c]));
  const nextIn = next.checks || [];
  const seen = new Set();
  const checks = [];
  for (const c of nextIn) {
    if (!c || seen.has(c.id)) continue;
    seen.add(c.id);
    const p = prevById.get(c.id);
    if (c.deleted) {
      // Keep tombstones as they are (or stamp a fresh one).
      checks.push(p?.deleted ? p : { id: c.id, deleted: true, u: now });
      continue;
    }
    const changed = !p || p.deleted || p.text !== c.text || !!p.done !== !!c.done || (p.indent || 0) !== (c.indent || 0);
    checks.push(changed ? { ...c, u: now } : { ...c, u: c.u ?? p.u ?? now });
  }
  let removed = false;
  for (const p of prevChecks) {
    if (seen.has(p.id)) continue;
    seen.add(p.id);
    if (p.deleted) checks.push(p);               // the caller only passed live checks
    else { checks.push({ id: p.id, deleted: true, u: now }); removed = true; }
  }
  // Drop tombstones older than 30 days.
  const kept = checks.filter((c) => !c.deleted || (c.u || 0) > now - TOMBSTONE_TTL);
  if (nextIn.length || prevChecks.length) out.checks = kept;

  if (prev) {
    const liveOrder = (arr) => arr.filter((c) => !c.deleted).map((c) => c.id).join('\u0000');
    const added = kept.some((c) => !c.deleted && (!prevById.has(c.id) || prevById.get(c.id).deleted));
    if (removed || added || liveOrder(prevChecks) !== liveOrder(kept)) fm.order = now;
  }

  if (Object.keys(fm).length) out.fm = fm;
  return out;
}

/** Remove tombstones older than 30 days without touching anything else. Returns null if nothing changed. */
export function pruneTombstones(it, now = Date.now()) {
  const checks = it.checks || [];
  const kept = checks.filter((c) => !c.deleted || (c.u || 0) > now - TOMBSTONE_TTL);
  return kept.length === checks.length ? null : kept;
}
