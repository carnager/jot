// Item store with offline cache and sync (SPEC §2).
import { lsGet, lsSet } from './util.js';
import { stamp } from './fieldmerge.js';

const CACHE_KEY = 'jot.cache.v1';

/** No text anywhere: such items are never worth keeping. */
export function isEmpty(it) {
  return !(it.title || '').trim() && !(it.body || '').trim() && !(it.checks || []).some((c) => (c.text || '').trim())
    && !(it.images || []).length;
}
const PUSH_DELAY = 400;

class Store {
  constructor() {
    this.items = new Map();
    this.dirty = new Set();
    this.drafts = new Set();   // brand-new items that stay in memory until they get some text
    this.gen = new Map();      // id → local change counter (detects edits during an in-flight sync)
    this.since = 0;
    this.user = '';
    this.listeners = {};
    this.status = 'idle';      // idle | syncing | offline | error
    this.inflight = null;
    this.again = false;
    this.pushTimer = 0;
    this.retryDelay = 2000;
    this.retryTimer = 0;
    this.es = null;
    this.lastSyncAt = 0;
  }

  on(ev, fn) { (this.listeners[ev] ||= []).push(fn); return () => this.off(ev, fn); }
  off(ev, fn) { this.listeners[ev] = (this.listeners[ev] || []).filter((f) => f !== fn); }
  emit(ev, data) { for (const f of this.listeners[ev] || []) f(data); }

  // ---------- cache ----------
  load() {
    const c = lsGet(CACHE_KEY, null);
    if (!c) return false;
    this.user = c.user || '';
    this.since = c.since || 0;
    for (const it of c.items || []) this.items.set(it.id, it);
    for (const id of c.dirty || []) this.dirty.add(id);
    return this.items.size > 0 || !!this.user;
  }

  persistSoon() {
    if (this.persistTimer) return;
    this.persistTimer = setTimeout(() => { this.persistTimer = 0; this.persist(); }, 250);
  }
  persist() {
    const items = [...this.items.values()].filter((it) => !this.drafts.has(it.id));
    lsSet(CACHE_KEY, { user: this.user, since: this.since, items, dirty: [...this.dirty] });
  }

  reset(user = '') {
    this.items.clear(); this.dirty.clear(); this.gen.clear(); this.drafts.clear();
    this.since = 0; this.user = user;
    this.persist();
    this.emit('change', { remote: true });
  }

  // ---------- reads ----------
  get(id) { return this.items.get(id); }
  /** Live items. Drafts are left out: they only exist inside the editor. */
  all() {
    const out = [];
    for (const it of this.items.values()) if (!it.deleted && !this.drafts.has(it.id)) out.push(it);
    return out;
  }

  isDraft(id) { return this.drafts.has(id); }

  /** Hold a new, still empty item in memory only. It is never synced or cached unless it gets text. */
  putDraft(item) {
    this.items.set(item.id, item);
    this.drafts.add(item.id);
    this.emit('change', { ids: [item.id] });
    return item;
  }

  /** Forget a draft completely (it never left this tab). */
  discardDraft(id) {
    if (!this.drafts.delete(id)) return false;
    this.items.delete(id);
    this.emit('change', { ids: [id] });
    return true;
  }

  // ---------- writes ----------
  /** Replace/insert a full item (local change). */
  put(item, { silent = false } = {}) {
    const prev = this.items.get(item.id);
    const now = Date.now();
    // §2b: record which field groups changed (fm), stamp checks (u) and keep check tombstones.
    // One timestamp for updatedAt and every stamp, so the server sees updatedAt == max(fm, checks.u) (SPEC §2b).
    const ts = Math.max(now, (prev?.updatedAt || 0) + 1);
    const next = { ...stamp(this.drafts.has(item.id) ? null : prev, item, ts), updatedAt: ts };
    if (this.drafts.has(item.id)) {
      if (isEmpty(next) && !next.deleted) {
        // Still nothing typed: keep it in memory only.
        this.items.set(item.id, next);
        if (!silent) this.emit('change', { ids: [item.id] });
        return next;
      }
      this.drafts.delete(item.id);
      if (next.deleted) { this.items.delete(item.id); if (!silent) this.emit('change', { ids: [item.id] }); return next; }
    }
    this.items.set(item.id, next);
    this.dirty.add(item.id);
    this.gen.set(item.id, (this.gen.get(item.id) || 0) + 1);
    this.persistSoon();
    this.schedulePush();
    if (!silent) this.emit('change', { ids: [item.id] });
    return next;
  }

  update(id, patch) {
    const cur = this.items.get(id);
    if (!cur) return null;
    const p = typeof patch === 'function' ? patch(cur) : patch;
    if (!p) return cur;
    return this.put({ ...cur, ...p });
  }

  updateMany(ids, patch) {
    const out = [];
    for (const id of ids) {
      const cur = this.items.get(id);
      if (!cur) continue;
      const p = typeof patch === 'function' ? patch(cur) : patch;
      if (p) out.push(this.put({ ...cur, ...p }, { silent: true }));
    }
    this.emit('change', { ids });
    return out;
  }

  snapshot(ids) {
    return ids.map((id) => this.items.get(id)).filter(Boolean).map((it) => structuredClone(it));
  }

  restore(snaps) {
    for (const s of snaps) this.put(s, { silent: true });
    this.emit('change', { ids: snaps.map((s) => s.id) });
  }

  // ---------- sync ----------
  schedulePush() {
    clearTimeout(this.pushTimer);
    this.pushTimer = setTimeout(() => this.sync(), PUSH_DELAY);
  }

  setStatus(s) {
    if (this.status !== s) { this.status = s; this.emit('status', s); }
  }

  async sync() {
    if (!this.user) return;
    if (this.inflight) { this.again = true; return this.inflight; }
    clearTimeout(this.pushTimer);
    this.inflight = this._sync().finally(() => {
      this.inflight = null;
      if (this.again) { this.again = false; this.sync(); }
    });
    return this.inflight;
  }

  async _sync() {
    // Items whose images are still uploading wait (SPEC §2a: upload first, then push the item).
    const ids = [...this.dirty].filter((id) => { const it = this.items.get(id); return !it || !this.holdBack?.(it); });
    const sentGen = new Map(ids.map((id) => [id, this.gen.get(id) || 0]));
    const changes = ids.map((id) => this.items.get(id)).filter(Boolean);
    this.setStatus('syncing');
    let res;
    try {
      res = await fetch('/api/sync', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ since: this.since, changes }),
      });
    } catch {
      this.setStatus('offline');
      this.scheduleRetry();
      return;
    }
    if (res.status === 401) {
      this.setStatus('idle');
      this.emit('auth', null);
      return;
    }
    if (!res.ok) {
      let msg = res.statusText;
      try { msg = (await res.json()).error || msg; } catch { /* not json */ }
      this.setStatus('error');
      this.emit('error', msg);
      this.scheduleRetry();
      return;
    }
    const data = await res.json();
    this.retryDelay = 2000;
    const changed = [];
    for (const it of data.items || []) {
      const g = this.gen.get(it.id) || 0;
      const changedDuring = this.dirty.has(it.id) && (!sentGen.has(it.id) || sentGen.get(it.id) !== g);
      if (changedDuring) continue; // local edit while the request was in flight wins; it's pushed next round
      const cur = this.items.get(it.id);
      if (!cur || cur.updatedAt !== it.updatedAt || cur.rev !== it.rev || JSON.stringify(cur) !== JSON.stringify(it)) changed.push(it.id);
      this.items.set(it.id, it);
    }
    for (const id of ids) {
      if ((this.gen.get(id) || 0) === sentGen.get(id)) this.dirty.delete(id);
    }
    if (typeof data.rev === 'number' && data.rev > this.since) this.since = data.rev;
    this.lastSyncAt = Date.now();
    this.persist();
    const waiting = [...this.dirty].some((id) => !ids.includes(id));
    this.setStatus(this.dirty.size ? 'syncing' : 'idle');
    // Held-back items are pushed by the upload pump; don't spin on them here.
    if (this.dirty.size && !waiting) this.schedulePush();
    if (changed.length) this.emit('change', { ids: changed, remote: true });
    this.emit('synced', null);
  }

  scheduleRetry() {
    clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(() => this.sync(), this.retryDelay);
    this.retryDelay = Math.min(this.retryDelay * 2, 60000);
  }

  // ---------- live updates ----------
  startEvents() {
    this.stopEvents();
    if (!window.EventSource) return;
    const es = new EventSource('/api/events', { withCredentials: true });
    es.addEventListener('rev', (e) => {
      const rev = +e.data;
      if (rev > this.since || this.dirty.size) this.sync();
    });
    es.onopen = () => { if (this.status === 'offline') this.sync(); };
    es.onerror = () => {
      // EventSource reconnects on its own; a 401 closes it for good, so probe auth once.
      if (es.readyState === EventSource.CLOSED) {
        this.es = null;
        setTimeout(() => { if (this.user) this.sync().then(() => this.user && !this.es && this.startEvents()); }, 5000);
      }
    };
    this.es = es;
  }
  stopEvents() {
    if (this.es) { this.es.close(); this.es = null; }
  }
}

export const store = new Store();
