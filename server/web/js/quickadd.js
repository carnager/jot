// Quick-add natural-language parser (SPEC §3). Pure, no DOM. English + German.
import { WD, dayNum, fromDayNum, weekday, daysInMonth, parseDue, fmtDue } from './dates.js';
import { firstOccurrence } from './repeat.js';

const WDAY = {
  mon: 0, monday: 0, montag: 0,
  tue: 1, tues: 1, tuesday: 1, dienstag: 1,
  wed: 2, wednesday: 2, mittwoch: 2,
  thu: 3, thur: 3, thurs: 3, thursday: 3, donnerstag: 3,
  fri: 4, friday: 4, freitag: 4,
  sat: 5, saturday: 5, samstag: 5,
  sun: 6, sunday: 6, sonntag: 6,
};
// Two-letter German forms: only with a following time or a preceding "am"/"on".
const WDAY2 = { mo: 0, di: 1, mi: 2, do: 3, fr: 4, sa: 5, so: 6 };
// Plural forms mean "every": "montags", "mondays".
const WDAY_PLURAL = {
  mondays: 0, montags: 0, tuesdays: 1, dienstags: 1, wednesdays: 2, mittwochs: 2,
  thursdays: 3, donnerstags: 3, fridays: 4, freitags: 4, saturdays: 5, samstags: 5, sundays: 6, sonntags: 6,
};
const MONTHS = {
  jan: 1, january: 1, januar: 1, 'jänner': 1, jaenner: 1,
  feb: 2, february: 2, februar: 2,
  mar: 3, march: 3, 'märz': 3, maerz: 3, mrz: 3,
  apr: 4, april: 4,
  may: 5, mai: 5,
  jun: 6, june: 6, juni: 6,
  jul: 7, july: 7, juli: 7,
  aug: 8, august: 8,
  sep: 9, sept: 9, september: 9,
  oct: 10, october: 10, okt: 10, oktober: 10,
  nov: 11, november: 11,
  dec: 12, december: 12, dez: 12, dezember: 12,
};
const PREFIX = new Set(['on', 'am', 'at', 'um', 'by', 'bis', '@', 'due']);
const EVERY = new Set(['every', 'each', 'jeden', 'jede', 'jedes', 'jeder', 'alle']);
const AND = new Set([',', 'and', 'und', '&', '+']);
const UNIT = {
  day: 'day', days: 'day', d: 'day', tag: 'day', tage: 'day', tagen: 'day',
  week: 'week', weeks: 'week', w: 'week', woche: 'week', wochen: 'week',
  month: 'month', months: 'month', monat: 'month', monate: 'month', monaten: 'month',
  year: 'year', years: 'year', y: 'year', jahr: 'year', jahre: 'year', jahren: 'year',
  hour: 'hour', hours: 'hour', h: 'hour', hr: 'hour', hrs: 'hour', stunde: 'hour', stunden: 'hour', std: 'hour',
  min: 'min', mins: 'min', minute: 'min', minutes: 'min', minuten: 'min',
};
const EVENING = 19 * 60;

const isNum = (s) => /^\d+$/.test(s || '');
const monthOf = (s) => (s ? MONTHS[s.replace(/\.$/, '')] : undefined);

function validDate(y, m, d) {
  return m >= 1 && m <= 12 && d >= 1 && d <= daysInMonth(y, m);
}

/** Year-less date → the next occurrence on or after today. */
function nextYearless(m, d, today) {
  let y = fromDayNum(today).y;
  if (!validDate(y, m, d) && !(m === 2 && d === 29)) return null;
  for (let i = 0; i < 8; i++, y++) {
    if (validDate(y, m, d) && dayNum(y, m, d) >= today) return dayNum(y, m, d);
  }
  return null;
}

function fullYear(s) {
  const n = +s;
  return s.length === 2 ? 2000 + n : n;
}

function tokenize(line) {
  const out = [];
  const re = /[^\s,]+|,/g;
  let m;
  while ((m = re.exec(line))) out.push({ t: m[0], l: m[0].toLowerCase(), s: m.index, e: m.index + m[0].length });
  return out;
}

// ---------- matchers: each returns { n, ... } or null ----------

function matchTime(T, i, prefixed) {
  const a = T[i]?.l, b = T[i + 1]?.l;
  if (!a) return null;
  if (a === 'noon' || a === 'mittag' || a === 'mittags') return { n: 1, time: 12 * 60 };
  if (a === 'midnight' || a === 'mitternacht') return { n: 1, time: 0 };
  let m;
  const h12 = (h, mm, ap) => {
    if (h < 1 || h > 12 || mm > 59) return null;
    return ((h % 12) + (ap.startsWith('p') ? 12 : 0)) * 60 + mm;
  };
  const h24 = (h, mm) => (h <= 23 && mm <= 59 ? h * 60 + mm : null);
  if ((m = /^(\d{1,2})(?::(\d{2}))?(am|pm|a\.m\.|p\.m\.)$/.exec(a))) {
    const t = h12(+m[1], +(m[2] || 0), m[3]);
    return t == null ? null : { n: 1, time: t };
  }
  if ((m = /^(\d{1,2}):(\d{2})(?:h|uhr)?$/.exec(a))) {
    if (b === 'am' || b === 'pm') {
      const t = h12(+m[1], +m[2], b);
      return t == null ? null : { n: 2, time: t };
    }
    const t = h24(+m[1], +m[2]);
    if (t == null) return null;
    return { n: b === 'uhr' ? 2 : 1, time: t };
  }
  if ((m = /^(\d{1,2})(?:h|uhr)$/.exec(a))) {
    const t = h24(+m[1], 0);
    return t == null ? null : { n: 1, time: t };
  }
  if (isNum(a) && a.length <= 2) {
    if (b === 'am' || b === 'pm') {
      const t = h12(+a, 0, b);
      return t == null ? null : { n: 2, time: t };
    }
    if (b === 'uhr') {
      const t = h24(+a, 0);
      return t == null ? null : { n: 2, time: t };
    }
    if (prefixed) {
      const t = h24(+a, 0);
      return t == null ? null : { n: 1, time: t };
    }
  }
  return null;
}

function withEvening(T, i, res) {
  const nx = T[i + res.n]?.l;
  if (nx === 'abend' || nx === 'evening' || nx === 'night') return { ...res, n: res.n + 1, time: EVENING };
  return res;
}

function nextWeekday(today, wd, includeToday = false) {
  const cur = weekday(today);
  let diff = (wd - cur + 7) % 7;
  if (diff === 0 && !includeToday) diff = 7;
  return today + diff;
}

function matchDate(T, i, ctx, prefixed) {
  const a = T[i]?.l, b = T[i + 1]?.l, c = T[i + 2]?.l;
  if (!a) return null;
  const today = ctx.day;
  let m;
  if (a === 'today' || a === 'heute') return withEvening(T, i, { n: 1, date: today });
  if (a === 'tonight') return { n: 1, date: today, time: EVENING };
  if (a === 'tomorrow' || a === 'tmrw' || a === 'tmr' || a === 'morgen') return withEvening(T, i, { n: 1, date: today + 1 });
  if (a === 'übermorgen' || a === 'uebermorgen') return withEvening(T, i, { n: 1, date: today + 2 });
  if (a === 'weekend' || a === 'wochenende') return { n: 1, date: nextWeekday(today, 5, true) };
  if ((a === 'this' || a === 'dieses' || a === 'am') && (b === 'weekend' || b === 'wochenende')) {
    return { n: 2, date: nextWeekday(today, 5, true) };
  }
  if (/^(next|nächste|nächsten|nächster|nächstes|naechste|naechsten|naechster|kommenden|kommender|coming)$/.test(a) && b) {
    if (b === 'week' || b === 'woche') return { n: 2, date: nextWeekday(today, 0) };
    if (b === 'month' || b === 'monat') {
      const { y, m: mo } = fromDayNum(today);
      return { n: 2, date: mo === 12 ? dayNum(y + 1, 1, 1) : dayNum(y, mo + 1, 1) };
    }
    if (b === 'year' || b === 'jahr') return { n: 2, date: dayNum(fromDayNum(today).y + 1, 1, 1) };
    const wd = WDAY[b] ?? WDAY2[b];
    if (wd != null) return { n: 2, date: nextWeekday(today, wd) };
  }
  if (WDAY[a] != null) return { n: 1, date: nextWeekday(today, WDAY[a]) };
  if (WDAY2[a] != null && (prefixed || matchTime(T, i + 1, false))) {
    return { n: 1, date: nextWeekday(today, WDAY2[a]) };
  }
  // in N unit / in 2h
  if (a === 'in' && b) {
    let n = null, unit = null, used = 0;
    if (isNum(b) && UNIT[c]) { n = +b; unit = UNIT[c]; used = 3; }
    else if ((m = /^(\d+)(d|w|h|m|min)$/.exec(b))) { n = +m[1]; unit = { d: 'day', w: 'week', h: 'hour', m: 'min', min: 'min' }[m[2]]; used = 2; }
    else if ((b === 'a' || b === 'an' || b === 'einer' || b === 'einem' || b === 'one') && UNIT[c]) { n = 1; unit = UNIT[c]; used = 3; }
    if (n != null && unit) {
      if (unit === 'day') return { n: used, date: today + n };
      if (unit === 'week') return { n: used, date: today + 7 * n };
      if (unit === 'month') {
        const { y, m: mo, d } = fromDayNum(today);
        const idx = y * 12 + mo - 1 + n, ny = Math.floor(idx / 12), nm = (idx % 12) + 1;
        return { n: used, date: dayNum(ny, nm, Math.min(d, daysInMonth(ny, nm))) };
      }
      if (unit === 'year') {
        const { y, m: mo, d } = fromDayNum(today);
        return { n: used, date: dayNum(y + n, mo, Math.min(d, daysInMonth(y + n, mo))) };
      }
      const total = Math.ceil((today * 1440 + ctx.min + (unit === 'hour' ? 60 : 1) * n) / 5) * 5;
      return { n: used, date: Math.floor(total / 1440), time: total % 1440 };
    }
  }
  // ISO
  if ((m = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(a))) {
    if (validDate(+m[1], +m[2], +m[3])) return { n: 1, date: dayNum(+m[1], +m[2], +m[3]) };
    return null;
  }
  // 29.10. / 29.10 / 29.10.2026 / 29.10.26
  if ((m = /^(\d{1,2})\.(\d{1,2})(?:\.(\d{4}|\d{2})?)?$/.exec(a))) {
    const d = +m[1], mo = +m[2];
    if (m[3]) {
      const y = fullYear(m[3]);
      return validDate(y, mo, d) ? { n: 1, date: dayNum(y, mo, d) } : null;
    }
    const dt = nextYearless(mo, d, today);
    return dt == null ? null : { n: 1, date: dt };
  }
  // 10/29 (US)
  if ((m = /^(\d{1,2})\/(\d{1,2})(?:\/(\d{4}|\d{2}))?$/.exec(a))) {
    const mo = +m[1], d = +m[2];
    if (m[3]) {
      const y = fullYear(m[3]);
      return validDate(y, mo, d) ? { n: 1, date: dayNum(y, mo, d) } : null;
    }
    const dt = nextYearless(mo, d, today);
    return dt == null ? null : { n: 1, date: dt };
  }
  // 29 oct / 29. oktober [2027]
  if ((m = /^(\d{1,2})(?:\.|st|nd|rd|th)?$/.exec(a)) && monthOf(b)) {
    const d = +m[1], mo = monthOf(b);
    if (isNum(c) && c.length === 4) {
      return validDate(+c, mo, d) ? { n: 3, date: dayNum(+c, mo, d) } : null;
    }
    const dt = nextYearless(mo, d, today);
    return dt == null ? null : { n: 2, date: dt };
  }
  // oct 29 / october 29th [, 2027]
  if (monthOf(a) && b && (m = /^(\d{1,2})(?:\.|st|nd|rd|th)?$/.exec(b))) {
    const d = +m[1], mo = monthOf(a);
    let k = 2;
    if (c === ',' && isNum(T[i + 3]?.l) && T[i + 3].l.length === 4) k = 3;
    const yTok = T[i + k]?.l;
    if (isNum(yTok) && yTok.length === 4) {
      return validDate(+yTok, mo, d) ? { n: k + 1, date: dayNum(+yTok, mo, d) } : null;
    }
    const dt = nextYearless(mo, d, today);
    return dt == null ? null : { n: 2, date: dt };
  }
  return null;
}

function weekdayList(T, i) {
  // wd ((,|and) wd)*
  const days = [];
  let j = i;
  while (T[j]) {
    const w = WDAY[T[j].l] ?? WDAY2[T[j].l] ?? WDAY_PLURAL[T[j].l];
    if (w == null) break;
    days.push(w);
    j++;
    if (T[j] && AND.has(T[j].l)) {
      const nxt = T[j + 1]?.l;
      if ((WDAY[nxt] ?? WDAY2[nxt] ?? WDAY_PLURAL[nxt]) != null) { j++; continue; }
    }
    break;
  }
  return days.length ? { n: j - i, byDay: [...new Set(days)].sort((x, y) => x - y).map((d) => WD[d]) } : null;
}

const WORKDAYS = ['mo', 'tu', 'we', 'th', 'fr'];

function matchRepeat(T, i) {
  const a = T[i]?.l;
  if (!a) return null;
  let r = null, n = 0;
  if (a === 'daily' || a === 'täglich' || a === 'taeglich') { r = { freq: 'day', interval: 1 }; n = 1; }
  else if (a === 'weekdays' || a === 'werktags' || a === 'wochentags') { r = { freq: 'week', interval: 1, byDay: WORKDAYS }; n = 1; }
  else if (a === 'weekly' || a === 'wöchentlich' || a === 'woechentlich') { r = { freq: 'week', interval: 1 }; n = 1; }
  else if (a === 'biweekly' || a === 'fortnightly') { r = { freq: 'week', interval: 2 }; n = 1; }
  else if (a === 'monthly' || a === 'monatlich') { r = { freq: 'month', interval: 1 }; n = 1; }
  else if (a === 'yearly' || a === 'annually' || a === 'jährlich' || a === 'jaehrlich') { r = { freq: 'year', interval: 1 }; n = 1; }
  else if (WDAY_PLURAL[a] != null) {
    const wl = weekdayList(T, i);
    r = { freq: 'week', interval: 1, byDay: wl.byDay }; n = wl.n;
  } else if (EVERY.has(a)) {
    let j = i + 1, interval = 1;
    if (T[j]?.l === 'other' || T[j]?.l === 'zweiten') { interval = 2; j++; }
    const b = T[j]?.l, c = T[j + 1]?.l;
    let m;
    if (!b) return null;
    if (b === 'day' || b === 'tag') { r = { freq: 'day', interval }; n = j + 1 - i; }
    else if (b === 'weekday' || b === 'werktag' || b === 'wochentag') { r = { freq: 'week', interval: 1, byDay: WORKDAYS }; n = j + 1 - i; }
    else if (b === 'week' || b === 'woche') { r = { freq: 'week', interval }; n = j + 1 - i; }
    else if (b === 'month' || b === 'monat') { r = { freq: 'month', interval }; n = j + 1 - i; }
    else if (b === 'year' || b === 'jahr') { r = { freq: 'year', interval }; n = j + 1 - i; }
    else if (isNum(b) && UNIT[c] && ['day', 'week', 'month', 'year'].includes(UNIT[c])) {
      r = { freq: UNIT[c], interval: Math.max(1, +b) }; n = j + 2 - i;
    } else if ((m = /^(\d{1,2})(st|nd|rd|th|\.)$/.exec(b)) && +m[1] >= 1 && +m[1] <= 31) {
      r = { freq: 'month', interval: 1, monthDay: +m[1] }; n = j + 1 - i;
    } else {
      const wl = weekdayList(T, j);
      if (wl) { r = { freq: 'week', interval, byDay: wl.byDay }; n = j + wl.n - i; }
    }
  }
  if (!r) return null;
  const k = matchAfterDone(T, i + n);
  if (k) { r.fromCompletion = true; n += k; }
  return { n, repeat: r };
}

function matchAfterDone(T, i) {
  const a = T[i]?.l, b = T[i + 1]?.l;
  if (a === 'after' && (b === 'done' || b === 'completion' || b === 'completed' || b === 'finishing')) return 2;
  if (a === 'nach' && (b === 'erledigung' || b === 'abschluss' || b === 'erledigt')) return 2;
  return 0;
}

// ---------- main ----------

/**
 * parse(text, now = "YYYY-MM-DDTHH:MM", opts = { ignore: Set<"start:text"> })
 * → { title, kind, body, checks, labels, due, repeat, pinned, tokens: [{ s, e, type, text }] }
 */
export function parse(text, now, opts = {}) {
  const ignore = opts.ignore || new Set();
  const np = parseDue(now);
  const ctx = { day: np.day, min: np.min ?? 0 };
  const lines = text.split('\n');
  const line = lines[0];
  const T = tokenize(line);

  let date = null, time = null, repeat = null, pinned = false;
  const labels = [];
  const tokens = [];

  const span = (i, n, type) => {
    const s = T[i].s, e = T[i + n - 1].e;
    return { s, e, type, text: line.slice(s, e) };
  };
  const ignored = (sp) => ignore.has(`${sp.s}:${sp.text}`);

  let i = 0;
  while (i < T.length) {
    const tok = T[i];
    let hit = null;

    // label
    const lm = /^#([\p{L}\p{N}_-]+)$/u.exec(tok.t);
    if (lm) hit = { n: 1, type: 'label', apply: () => labels.push(lm[1].toLowerCase()) };

    // pin: trailing " !"
    if (!hit && tok.t === '!' && i === T.length - 1 && i > 0) hit = { n: 1, type: 'pin', apply: () => (pinned = true) };

    // repeat
    if (!hit && !repeat) {
      const r = matchRepeat(T, i);
      if (r) hit = { n: r.n, type: 'repeat', apply: () => (repeat = r.repeat) };
    }
    if (!hit && repeat && !repeat.fromCompletion) {
      const k = matchAfterDone(T, i);
      if (k) hit = { n: k, type: 'repeat', apply: () => (repeat.fromCompletion = true) };
    }

    // prefixed date/time: "am freitag", "um 10", "bis 15.1."
    if (!hit && PREFIX.has(tok.l) && T[i + 1]) {
      const d = date == null ? matchDate(T, i + 1, ctx, true) : null;
      if (d) hit = { n: d.n + 1, type: 'date', apply: () => { date = d.date; if (d.time != null && time == null) time = d.time; } };
      else if (time == null) {
        const t = matchTime(T, i + 1, true);
        if (t) hit = { n: t.n + 1, type: 'time', apply: () => (time = t.time) };
      }
    }

    if (!hit && date == null) {
      const d = matchDate(T, i, ctx, false);
      if (d) hit = { n: d.n, type: 'date', apply: () => { date = d.date; if (d.time != null && time == null) time = d.time; } };
    }
    if (!hit && time == null) {
      const t = matchTime(T, i, false);
      if (t) hit = { n: t.n, type: 'time', apply: () => (time = t.time) };
    }

    if (hit) {
      const sp = span(i, hit.n, hit.type);
      if (!ignored(sp)) {
        hit.apply();
        tokens.push(sp);
        i += hit.n;
        continue;
      }
    }
    i++;
  }

  // Title: the first line with the recognised spans removed.
  let title = '';
  let last = 0;
  for (const t of tokens) {
    title += line.slice(last, t.s);
    last = t.e;
  }
  title += line.slice(last);
  title = title.replace(/\s+/g, ' ').replace(/\s+,/g, ',').replace(/^[\s,]+|[\s,]+$/g, '');

  let kind = 'note';
  let checks = [];
  const rest = lines.slice(1).filter((l) => l.trim() !== '');
  if (rest.length) {
    kind = 'list';
    checks = rest.map((l) => {
      const m = /^\s*(?:[-*•]\s+|\[( |x|X)?\]\s*)?(.*)$/.exec(l);
      return { text: m[2].trim(), done: m[1] === 'x' || m[1] === 'X' };
    }).filter((c) => c.text);
  } else {
    const cm = /^([^:]+):\s*(.+)$/.exec(title);
    if (cm && cm[2].includes(',')) {
      const parts = cm[2].split(',').map((s) => s.trim()).filter(Boolean);
      if (parts.length >= 2) {
        kind = 'list';
        title = cm[1].trim();
        checks = parts.map((t) => ({ text: t, done: false }));
      }
    }
  }

  // Due
  let due = '';
  if (date != null) {
    due = fmtDue(date, time);
  } else if (repeat) {
    const first = firstOccurrence(ctx.day, time, repeat, ctx.day, ctx.min);
    due = fmtDue(first, time);
  } else if (time != null) {
    due = fmtDue(time > ctx.min ? ctx.day : ctx.day + 1, time);
  }

  return {
    title,
    kind,
    checks,
    labels: [...new Set(labels)].sort(),
    due,
    repeat,
    pinned,
    tokens,
  };
}

/** Parse just a date expression (for the date popover's text field). */
export function parseWhen(text, now) {
  const r = parse('x ' + text, now);
  return { due: r.due, repeat: r.repeat, matched: r.tokens.some((t) => t.type !== 'label') };
}
