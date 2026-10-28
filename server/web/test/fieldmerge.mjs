// §2b field tracking: pure stamp() rules plus the store integration (every write goes through it).
import { stamp, liveChecks, pruneTombstones, TOMBSTONE_TTL } from '../js/fieldmerge.js';
import { completeItem } from '../js/repeat.js';

export async function run(check) {
  const T0 = 1_000_000_000_000, T1 = T0 + 5000;
  const base = {
    id: 'a', kind: 'list', title: 'Trip', body: '', labels: [], color: '', pinned: false, archived: false,
    done: false, doneAt: 0, due: '2026-10-28', remind: true, snoozedUntil: '', repeat: null, sort: 1, trashedAt: 0,
    updatedAt: T0, fm: { title: T0 - 1 },
    checks: [
      { id: 'c1', text: 'Passport', done: false, indent: 0, u: T0 },
      { id: 'c2', text: 'Charger', done: false, indent: 0, u: T0 },
      { id: 'c3', text: 'Socks', done: true, indent: 0, u: T0 },
    ],
  };
  const groupsSet = (r) => Object.keys(r.fm || {}).filter((g) => r.fm[g] === T1).sort();

  let r = stamp(base, { ...base, title: 'Lisbon' }, T1);
  check('fm: title change stamps only title', groupsSet(r), ['title']);
  check('fm: existing fm entries are kept', r.fm.title, T1);

  r = stamp(base, { ...base, due: '2026-10-29' }, T1);
  check('fm: due → schedule', groupsSet(r), ['schedule']);
  r = stamp(base, { ...base, alerts: [0, 30] }, T1);
  check('fm: alerts → schedule', groupsSet(r), ['schedule']);
  r = stamp(base, { ...base, labels: ['x'], pinned: true, color: 'red', images: [{ id: 'i' }], trashedAt: 5 }, T1);
  check('fm: several groups at once', groupsSet(r), ['color', 'images', 'labels', 'pinned', 'trash']);

  r = stamp(base, { ...base }, T1);
  check('fm: no change, no stamps', groupsSet(r), []);

  r = stamp(base, { ...base, checks: base.checks.map((c) => (c.id === 'c2' ? { ...c, text: 'Charger + cable' } : c)) }, T1);
  check('checks: edit stamps u of that check only', r.checks.map((c) => c.u), [T0, T1, T0]);
  check('checks: edit is not an order change', groupsSet(r), []);

  r = stamp(base, { ...base, checks: base.checks.map((c) => (c.id === 'c1' ? { ...c, done: true } : c)) }, T1);
  check('checks: done toggle stamps u', r.checks[0].u, T1);

  r = stamp(base, { ...base, checks: base.checks.filter((c) => c.id !== 'c2') }, T1);
  check('checks: removing leaves a tombstone', r.checks.find((c) => c.id === 'c2'), { id: 'c2', deleted: true, u: T1 });
  check('checks: removing sets fm.order', groupsSet(r), ['order']);
  check('liveChecks hides tombstones', liveChecks(r).map((c) => c.id), ['c1', 'c3']);

  // Callers may pass live checks only; the tombstone must survive.
  const T2 = T1 + 1000;
  const r2 = stamp(r, { ...r, checks: liveChecks(r).map((c) => (c.id === 'c1' ? { ...c, text: 'Passport!' } : c)) }, T2);
  check('checks: tombstone kept when caller omits it', r2.checks.filter((c) => c.deleted).map((c) => [c.id, c.u]), [['c2', T1]]);
  check('checks: omitting a tombstone is no order change', r2.fm.order, T1);

  r = stamp(base, { ...base, checks: [base.checks[1], base.checks[0], base.checks[2]] }, T1);
  check('checks: reorder sets fm.order', groupsSet(r), ['order']);
  check('checks: reorder keeps u', r.checks.map((c) => c.u), [T0, T0, T0]);

  r = stamp(base, { ...base, checks: [...base.checks, { id: 'c4', text: 'Hat', done: false, indent: 0 }] }, T1);
  check('checks: add sets fm.order and u', [groupsSet(r), r.checks[3].u], [['order'], T1]);

  const old = { ...base, checks: [...base.checks, { id: 'cx', deleted: true, u: T0 - TOMBSTONE_TTL - 1 }] };
  r = stamp(old, { ...old, title: 'x' }, T1);
  check('checks: tombstones older than 30 days are dropped', r.checks.some((c) => c.id === 'cx'), false);
  check('pruneTombstones finds stale ones', pruneTombstones(old, T1)?.length, 3);
  check('pruneTombstones: nothing stale → null', pruneTombstones(base, T1), null);

  r = stamp(null, { ...base, fm: undefined, checks: [{ id: 'n1', text: 'New', done: false, indent: 0 }] }, T1);
  check('new item: checks get u, no group stamps', [r.checks[0].u, r.fm], [T1, undefined]);

  // Repeat-advance: checks reset with a fresh u, schedule stamped, tombstones untouched.
  const rep = { ...base, repeat: { freq: 'day', interval: 1 }, checks: [...base.checks, { id: 'cz', deleted: true, u: T0 }] };
  const adv = stamp(rep, completeItem(rep, '2026-10-28T14:00', T1), T1);
  check('repeat: advance stamps schedule (and order unchanged)', groupsSet(adv), ['schedule']);
  check('repeat: all live checks reset with fresh u',
    liveChecks(adv).map((c) => [c.done, c.u === T1 || !base.checks.find((b) => b.id === c.id).done ? 'ok' : 'stale']),
    [[false, 'ok'], [false, 'ok'], [false, 'ok']]);
  check('repeat: the done check got a fresh u', adv.checks.find((c) => c.id === 'c3').u, T1);
  check('repeat: tombstone untouched', adv.checks.find((c) => c.id === 'cz'), { id: 'cz', deleted: true, u: T0 });

  // Store integration: every write path goes through stamp().
  const { store } = await import('../js/store.js');
  store.put({ ...base, id: 'S1', fm: {} });
  store.update('S1', { title: 'Renamed' });
  const s1 = store.get('S1');
  check('store.update stamps fm', typeof s1.fm.title === 'number' && s1.fm.title > 0, true);
  store.update('S1', (it) => ({ checks: liveChecks(it).filter((c) => c.id !== 'c1') }));
  const s2 = store.get('S1');
  check('store keeps check tombstones', s2.checks.find((c) => c.id === 'c1')?.deleted, true);
  check('store stamps fm.order on removal', typeof s2.fm.order, 'number');
  store.updateMany(['S1'], { pinned: true });
  check('store.updateMany stamps fm', typeof store.get('S1').fm.pinned, 'number');
  const snap = store.snapshot(['S1']);
  store.update('S1', { title: 'Again' });
  const before = store.get('S1').fm.title;
  await new Promise((r) => setTimeout(r, 2));
  store.restore(snap);
  check('undo (restore) counts as a change', store.get('S1').fm.title > before && store.get('S1').title === 'Renamed', true);
}

// §5 alert helpers (fire times are local wall-clock).
export async function runAlerts(check) {
  const { fireTimes, describeAlerts, normAlerts, isDefaultAlerts, alertLabel } = await import('../js/alerts.js');
  const hm = (d) => `${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  const it = { due: '2026-10-29T14:00', alerts: [30, 0, 1440, 30] };
  check('alerts: fire times', fireTimes(it).map((f) => hm(f.at)), ['29 14:00', '29 13:30', '28 14:00']);
  check('alerts: all-day uses allDayTime', fireTimes({ due: '2026-10-29', alerts: [0, 1440] }, 8 * 60).map((f) => hm(f.at)), ['29 08:00', '28 08:00']);
  check('alerts: missing → on time', fireTimes({ due: '2026-10-29T09:00' }).map((f) => f.m), [0]);
  check('alerts: snooze is one fire time', fireTimes({ due: '2026-10-29T09:00', alerts: [0, 60], snoozedUntil: '2026-10-29T10:15' }).map((f) => hm(f.at)), ['29 10:15']);
  check('alerts: nothing when done / off', [fireTimes({ due: '2026-10-29', done: true }).length, fireTimes({ due: '2026-10-29', remind: false }).length], [0, 0]);
  check('alerts: describe', describeAlerts([30, 0]), 'On time · 30 min before');
  check('alerts: describe all-day', describeAlerts([1440, 0], true), 'On the day · 1 day before');
  check('alerts: labels', [60, 120, 2880, 10080].map((m) => alertLabel(m)), ['1 h before', '2 h before', '2 days before', '1 week before']);
  check('alerts: norm/default', [normAlerts([]), isDefaultAlerts(undefined), isDefaultAlerts([0, 5])], [[0], true, false]);
}
