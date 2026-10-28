// Runs the shared spec vectors against the web app's pure modules: node server/web/test/run.mjs
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { parse } from '../js/quickadd.js';
import { completeItem } from '../js/repeat.js';

const here = dirname(fileURLToPath(import.meta.url));
const specDir = join(here, '..', '..', '..', 'spec');
const load = (f) => JSON.parse(readFileSync(join(specDir, f), 'utf8'));

let pass = 0, fail = 0;
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const normRepeat = (r) => r && {
  freq: r.freq, interval: r.interval || 1, byDay: r.byDay || [], monthDay: r.monthDay || 0,
  fromCompletion: !!r.fromCompletion, until: r.until || '',
};
function check(name, got, want) {
  if (eq(got, want)) { pass++; return; }
  fail++;
  console.log(`FAIL ${name}\n  got:  ${JSON.stringify(got)}\n  want: ${JSON.stringify(want)}`);
}

const qa = load('quickadd.json');
for (const c of qa.cases) {
  const r = parse(c.input, qa.now);
  const got = {
    title: r.title, kind: r.kind, due: r.due, labels: r.labels, repeat: normRepeat(r.repeat), pinned: r.pinned,
    checks: r.checks.map((x) => ({ text: x.text, done: !!x.done })),
  };
  const want = {
    title: c.title, kind: c.kind || 'note', due: c.due || '', labels: c.labels || [],
    repeat: normRepeat(c.repeat || null), pinned: !!c.pinned,
    checks: (c.checks || []).map((x) => ({ text: x.text, done: !!x.done })),
  };
  check(`quickadd ${JSON.stringify(c.input)}`, got, want);
}

const rp = load('repeat.json');
for (const c of rp.cases) {
  const item = { id: 'x', due: c.due, repeat: c.repeat, done: false, checks: [{ id: 'a', text: 'a', done: true }] };
  const r = completeItem(item, c.now, 1);
  const got = c.expect.done ? { done: r.done } : { due: r.due, done: r.done, checksReset: r.checks.every((x) => !x.done) };
  const want = c.expect.done ? { done: true } : { due: c.expect.due, done: false, checksReset: true };
  check(`repeat ${c.name}`, got, want);
}

const { run: fieldmerge, runAlerts } = await import('./fieldmerge.mjs');
await fieldmerge((name, got, want) => check('fieldmerge: ' + name, got, want));
await runAlerts((name, got, want) => check(name, got, want));

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
