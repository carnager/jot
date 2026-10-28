// Repeat logic (SPEC §4). Pure, no DOM.
import { WD, parseDue, fmtDue, weekday, addMonths, dayNum, fromDayNum, daysInMonth } from './dates.js';

/** First occurrence strictly after `base` (a day number). `anchor` = original due day (for month/year day-of-month). */
export function nextAfter(base, r, anchor) {
  const n = Math.max(1, r.interval || 1);
  switch (r.freq) {
    case 'day':
      return base + n;
    case 'week': {
      const days = (r.byDay || []).map((d) => WD.indexOf(d)).filter((i) => i >= 0).sort((a, b) => a - b);
      if (!days.length) return base + 7 * n;
      const wd = weekday(base);
      for (const d of days) if (d > wd) return base + (d - wd);
      const monday = base - wd;
      return monday + 7 * n + days[0];
    }
    case 'month':
      return addMonths(base, n, r.monthDay || fromDayNum(anchor).d);
    case 'year': {
      const b = fromDayNum(base), a = fromDayNum(anchor);
      const y = b.y + n;
      return dayNum(y, a.m, Math.min(a.d, daysInMonth(y, a.m)));
    }
    default:
      return base + 1;
  }
}

/** Is the day (+min) an occurrence of the rule? Used to pick the first occurrence when creating. */
export function matches(day, r, anchor) {
  if (r.freq === 'week' && r.byDay && r.byDay.length) return r.byDay.includes(WD[weekday(day)]);
  if (r.freq === 'month' && r.monthDay) {
    const { y, m, d } = fromDayNum(day);
    return d === Math.min(r.monthDay, daysInMonth(y, m));
  }
  return true;
}

/** First occurrence not in the past, starting at `startDay` (inclusive). */
export function firstOccurrence(startDay, min, r, nowDay, nowMin) {
  let day = startDay;
  for (let i = 0; i < 800; i++) {
    const notPast = min == null ? day >= nowDay : day > nowDay || (day === nowDay && min > nowMin);
    if (notPast && matches(day, r)) return day;
    day++;
  }
  return startDay;
}

function nowParts(now) {
  const p = parseDue(now);
  return { day: p.day, min: p.min ?? 0 };
}

/**
 * Complete an item at local time `now` ("YYYY-MM-DDTHH:MM"). Returns a new item object.
 * nowMs is used for doneAt/updatedAt.
 */
export function completeItem(item, now, nowMs = Date.now()) {
  const due = parseDue(item.due);
  const r = item.repeat;
  if (!r || !due) {
    return { ...item, done: true, doneAt: nowMs, snoozedUntil: '' };
  }
  const np = nowParts(now);
  const base = r.fromCompletion ? np.day : due.day;
  let next = nextAfter(base, r, due.day);
  if (!r.fromCompletion) {
    const inPast = (d) => (due.min == null ? d <= np.day : d < np.day || (d === np.day && due.min <= np.min));
    let guard = 0;
    while (inPast(next) && guard++ < 10000) next = nextAfter(next, r, due.day);
  }
  if (r.until) {
    const u = parseDue(r.until);
    if (u && next > u.day) return { ...item, done: true, doneAt: nowMs, snoozedUntil: '' };
  }
  return {
    ...item,
    due: fmtDue(next, due.min),
    snoozedUntil: '',
    done: false,
    doneAt: nowMs,
    checks: (item.checks || []).map((c) => (c.deleted ? c : { ...c, done: false })),
  };
}
