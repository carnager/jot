// Alerts and fire times (SPEC §5). Pure, no DOM.
import { parseDue, fromDayNum } from './dates.js';

export const PRESETS_TIMED = [0, 5, 15, 30, 60, 120, 1440, 2880, 10080];
export const PRESETS_ALLDAY = [0, 1440, 2880, 10080];

/** Missing or empty means [0]. Sorted, unique, non-negative integers. */
export function normAlerts(a) {
  const v = [...new Set((a || []).map((m) => Math.max(0, Math.round(+m || 0))))].sort((x, y) => x - y);
  return v.length ? v : [0];
}

export const isDefaultAlerts = (a) => { const n = normAlerts(a); return n.length === 1 && n[0] === 0; };

export function alertLabel(m, allDay = false, short = false) {
  if (!m) return allDay ? 'On the day' : 'On time';
  const suffix = short ? '' : ' before';
  if (m % 10080 === 0) { const w = m / 10080; return `${w} week${w > 1 ? 's' : ''}${suffix}`; }
  if (m % 1440 === 0) { const d = m / 1440; return `${d} day${d > 1 ? 's' : ''}${suffix}`; }
  if (m % 60 === 0) return `${m / 60} h${suffix}`;
  return `${m} min${suffix}`;
}

/** "On time · 30 min before" */
export function describeAlerts(a, allDay = false) {
  return normAlerts(a).map((m) => alertLabel(m, allDay)).join(' · ');
}

/**
 * Every fire time of an item, as { at: Date, m } (m = minutes before; snooze has m = 0).
 * `allDayMin` = the all-day reminder time in minutes after midnight. Local time zone.
 */
export function fireTimes(it, allDayMin = 9 * 60) {
  if (!it || it.remind === false || it.done || it.archived || it.trashedAt || it.deleted || !it.due) return [];
  if (it.snoozedUntil) {
    const s = parseDue(it.snoozedUntil);
    return s ? [{ at: toDate(s.day, s.min ?? allDayMin), m: 0 }] : [];
  }
  const p = parseDue(it.due);
  if (!p) return [];
  const base = toDate(p.day, p.min ?? allDayMin);
  return normAlerts(it.alerts).map((m) => ({ at: new Date(base.getTime() - m * 60000), m }));
}

function toDate(day, min) {
  const { y, m, d } = fromDayNum(day);
  return new Date(y, m - 1, d, Math.floor(min / 60), min % 60);
}

/** Text for an early alert's notification: "in 30 min", "in 2 h", "tomorrow 09:00", "Fri 09:00". */
export function leadText(due, now = new Date()) {
  const ms = due.getTime() - now.getTime();
  const min = Math.round(ms / 60000);
  if (min <= 0) return 'now';
  if (min < 60) return `in ${min} min`;
  if (min < 6 * 60) return `in ${Math.round(min / 60)} h`;
  const pad = (n) => String(n).padStart(2, '0');
  const hm = `${pad(due.getHours())}:${pad(due.getMinutes())}`;
  const d0 = new Date(now); d0.setHours(0, 0, 0, 0);
  const d1 = new Date(due); d1.setHours(0, 0, 0, 0);
  const days = Math.round((d1 - d0) / 86400000);
  if (days === 0) return `today ${hm}`;
  if (days === 1) return `tomorrow ${hm}`;
  if (days < 7) return `${['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][due.getDay()]} ${hm}`;
  return `${due.getDate()}.${due.getMonth() + 1}. ${hm}`;
}
