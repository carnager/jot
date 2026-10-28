// Pure date helpers. Dates are handled as integer "day numbers" (days since 1970-01-01, UTC based)
// so arithmetic never trips over DST. Wire format: "YYYY-MM-DD" or "YYYY-MM-DDTHH:MM" (floating local time).

export const WD = ['mo', 'tu', 'we', 'th', 'fr', 'sa', 'su'];

const pad = (n) => String(n).padStart(2, '0');

export function dayNum(y, m, d) {
  return Math.round(Date.UTC(y, m - 1, d) / 86400000);
}

export function fromDayNum(n) {
  const t = new Date(n * 86400000);
  return { y: t.getUTCFullYear(), m: t.getUTCMonth() + 1, d: t.getUTCDate() };
}

export function weekday(n) {
  // 0 = Monday … 6 = Sunday. 1970-01-01 was a Thursday.
  return (((n + 3) % 7) + 7) % 7;
}

export function daysInMonth(y, m) {
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

export function fmtDay(n) {
  const { y, m, d } = fromDayNum(n);
  return `${y}-${pad(m)}-${pad(d)}`;
}

export function fmtMin(min) {
  return `${pad(Math.floor(min / 60))}:${pad(min % 60)}`;
}

/** "YYYY-MM-DD[THH:MM]" → { day, min } (min = null for all-day), or null. */
export function parseDue(s) {
  if (!s) return null;
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?/.exec(s);
  if (!m) return null;
  return {
    day: dayNum(+m[1], +m[2], +m[3]),
    min: m[4] != null ? +m[4] * 60 + +m[5] : null,
  };
}

export function fmtDue(day, min) {
  return min == null ? fmtDay(day) : `${fmtDay(day)}T${fmtMin(min)}`;
}

/** Local wall-clock "now" as a due-style string. */
export function nowString(date = new Date()) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function todayNum(date = new Date()) {
  return dayNum(date.getFullYear(), date.getMonth() + 1, date.getDate());
}

/** Convert a floating due (+ optional fallback time for all-day) to a JS Date in local time. */
export function dueToDate(due, allDayMin = 9 * 60) {
  const p = parseDue(due);
  if (!p) return null;
  const { y, m, d } = fromDayNum(p.day);
  const min = p.min ?? allDayMin;
  return new Date(y, m - 1, d, Math.floor(min / 60), min % 60);
}

export function addMonths(day, months, dom) {
  const { y, m, d } = fromDayNum(day);
  const idx = y * 12 + (m - 1) + months;
  const ny = Math.floor(idx / 12), nm = (idx % 12) + 1;
  const want = dom || d;
  return dayNum(ny, nm, Math.min(want, daysInMonth(ny, nm)));
}

// ---------- human formatting ----------

const WD_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const WD_LONG = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
const MON_SHORT = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
export const MON_LONG = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

export function fmtTime(min, h12 = false) {
  if (!h12) return fmtMin(min);
  const h = Math.floor(min / 60), mm = min % 60;
  const hh = h % 12 || 12;
  return `${hh}${mm ? ':' + pad(mm) : ''}${h < 12 ? 'am' : 'pm'}`;
}

/** "Today", "Tomorrow", "Yesterday", "Fri", "Fri 31 Oct", "31 Oct 2027" */
export function relDay(day, today = todayNum()) {
  const diff = day - today;
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Tomorrow';
  if (diff === -1) return 'Yesterday';
  const { y, m, d } = fromDayNum(day);
  const t = fromDayNum(today);
  if (diff > 1 && diff < 7) return WD_LONG[weekday(day)];
  const base = `${WD_SHORT[weekday(day)]} ${d} ${MON_SHORT[m - 1]}`;
  return y === t.y ? base : `${d} ${MON_SHORT[m - 1]} ${y}`;
}

export function dayHeader(day, today = todayNum()) {
  const diff = day - today;
  const { m, d } = fromDayNum(day);
  const full = `${WD_SHORT[weekday(day)]} ${d} ${MON_SHORT[m - 1]}`;
  if (diff === 0) return { main: 'Today', sub: full };
  if (diff === 1) return { main: 'Tomorrow', sub: full };
  if (diff < 7) return { main: WD_LONG[weekday(day)], sub: `${d} ${MON_SHORT[m - 1]}` };
  return { main: full, sub: fromDayNum(day).y !== fromDayNum(today).y ? String(fromDayNum(day).y) : '' };
}

export function fmtDueHuman(due, now = new Date()) {
  const p = parseDue(due);
  if (!p) return '';
  const r = relDay(p.day, todayNum(now));
  return p.min == null ? r : `${r} ${fmtMin(p.min)}`;
}

export function shortDate(day) {
  const { m, d } = fromDayNum(day);
  return `${WD_SHORT[weekday(day)]} ${d} ${MON_SHORT[m - 1]}`;
}

export function isOverdue(due, now = new Date()) {
  const p = parseDue(due);
  if (!p) return false;
  const t = todayNum(now);
  if (p.min == null) return p.day < t;
  if (p.day !== t) return p.day < t;
  return p.min < now.getHours() * 60 + now.getMinutes();
}

export function describeRepeat(r) {
  if (!r) return '';
  const n = r.interval || 1;
  const unit = { day: 'day', week: 'week', month: 'month', year: 'year' }[r.freq];
  let s;
  if (r.freq === 'week' && r.byDay && r.byDay.length) {
    const days = [...r.byDay].sort((a, b) => WD.indexOf(a) - WD.indexOf(b));
    const wk = days.join(',') === 'mo,tu,we,th,fr';
    const list = wk ? 'weekday' : days.map((d) => WD_SHORT[WD.indexOf(d)]).join(', ');
    s = n === 1 ? `Every ${list}` : `Every ${n} weeks on ${list}`;
  } else if (r.freq === 'month' && r.monthDay) {
    s = `${n === 1 ? 'Monthly' : `Every ${n} months`} on the ${ordinal(r.monthDay)}`;
  } else if (n === 1) {
    s = { day: 'Daily', week: 'Weekly', month: 'Monthly', year: 'Yearly' }[r.freq];
  } else {
    s = `Every ${n} ${unit}s`;
  }
  if (r.fromCompletion) s += ' after done';
  if (r.until) s += ` until ${shortDate(parseDue(r.until).day)}`;
  return s;
}

export function ordinal(n) {
  const s = ['th', 'st', 'nd', 'rd'], v = n % 100;
  return n + (s[(v - 20) % 10] || s[v] || s[0]);
}
