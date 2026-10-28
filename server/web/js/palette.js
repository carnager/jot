// Ctrl+K command palette: fuzzy over actions, views, labels and items.
import { h, icon, fuzzy, modKey, fill } from './util.js';
import { store } from './store.js';
import { allLabels } from './model.js';
import { ui, hooks, targets } from './ui.js';
import { relDay } from './dates.js';
import { parseDue } from './dates.js';
import { closeLayer } from './motion.js';

let open = false;
export const paletteOpen = () => open;

export function openPalette(initial = '') {
  if (open) return;
  open = true;
  const restore = document.activeElement;
  const input = h('input', { placeholder: 'Search actions, labels and notes…', autocomplete: 'off', spellcheck: 'false', value: initial });
  const listEl = h('div', { class: 'palette-list' });
  let entries = [];
  let idx = 0;

  const close = () => {
    if (!open) return;
    open = false;
    closeLayer(back);
    if (restore && document.contains(restore) && restore !== document.body) restore.focus({ preventScroll: true });
  };

  function actions() {
    const t = targets();
    const has = t.length > 0;
    const A = (label, ic, run, kbd, extra = '') => ({ group: 'Actions', label, icon: ic, run, kbd, extra });
    const list = [
      A('New note', 'plus', () => hooks.focusCapture(), 'q'),
      A('New note in editor', 'edit', () => hooks.newBlank('note'), '⇧N'),
      A('New checklist', 'list', () => hooks.newBlank('list'), '⇧L'),
      A('Go to Today', 'sun', () => hooks.go({ type: 'today' }), 'g t'),
      A('Go to Upcoming', 'upcoming', () => hooks.go({ type: 'upcoming' }), 'g u'),
      A('Go to Notes', 'bulb', () => hooks.go({ type: 'notes' }), 'g n'),
      A('Go to Done', 'done', () => hooks.go({ type: 'done' }), 'g d'),
      A('Go to Archive', 'archive', () => hooks.go({ type: 'archive' }), 'g a'),
      A('Go to Trash', 'trash', () => hooks.go({ type: 'trash' }), 'g r'),
      A('Search', 'search', () => hooks.focusSearch(), '/'),
    ];
    if (has) {
      const n = t.length > 1 ? ` (${t.length})` : '';
      list.unshift(
        A('Complete' + n, 'done', () => hooks.key('x'), 'x'),
        A('Set date…' + n, 'calendar', () => hooks.key('d'), 'd'),
        A('Tomorrow' + n, 'forward', () => hooks.key('t'), 't'),
        A('Pin / unpin' + n, 'pin', () => hooks.key('p'), 'p'),
        A('Labels…' + n, 'tag', () => hooks.key('l'), 'l'),
        A('Color…' + n, 'palette', () => hooks.key('c'), 'c'),
        A('Archive' + n, 'archive', () => hooks.key('a'), 'a'),
        A('Move to trash' + n, 'trash', () => hooks.key('Delete'), 'Del'),
      );
    }
    list.push(
      A('Select all', 'check', () => hooks.selectAll(), `${modKey} A`),
      A('Toggle dark mode', 'moon', () => hooks.cycleTheme()),
      A('Settings', 'sliders', () => hooks.settings()),
      A('Keyboard shortcuts', 'keyboard', () => hooks.showKeys(), '?'),
      A('Sync now', 'refresh', () => store.sync()),
      A('Export all data (JSON)', 'download', () => { location.href = '/api/export'; }),
      A('New label…', 'tag', () => hooks.newLabel()),
      A('Log out', 'logout', () => hooks.logout()),
    );
    return list;
  }

  function compute() {
    const q = input.value.trim();
    const out = [];
    const labelsOnly = q.startsWith('#');
    const lq = labelsOnly ? q.slice(1) : q;
    if (!labelsOnly) {
      const acts = actions().map((a) => ({ ...a, score: q ? fuzzy(q, a.label) : 0 })).filter((a) => a.score >= 0);
      if (q) acts.sort((a, b) => b.score - a.score);
      out.push(...acts.slice(0, q ? 6 : 12));
    }
    const labels = allLabels().map((l) => ({
      group: 'Labels', label: '#' + l.name, icon: 'tag', extra: l.count ? String(l.count) : '',
      run: () => hooks.go({ type: 'label', label: l.name }), score: lq ? fuzzy(lq, l.name) : 0,
    })).filter((l) => l.score >= 0);
    if (lq) labels.sort((a, b) => b.score - a.score);
    out.push(...labels.slice(0, labelsOnly ? 50 : q ? 5 : 0));
    if (!labelsOnly) {
      let items = store.all().filter((it) => !it.trashedAt);
      let scored;
      if (q) {
        scored = items.map((it) => {
          const text = it.title || (it.kind === 'list' ? it.checks?.[0]?.text : (it.body || '').split('\n')[0]) || '';
          let s = fuzzy(q, text);
          if (s < 0) {
            const body = it.kind === 'list' ? it.checks.map((c) => c.text).join(' ') : it.body;
            s = (body || '').toLowerCase().includes(q.toLowerCase()) ? 50 : -1;
          }
          return { it, text, score: s - (it.archived ? 30 : 0) - (it.done ? 30 : 0) };
        }).filter((x) => x.score >= 0).sort((a, b) => b.score - a.score).slice(0, 30);
      } else {
        scored = items.sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 8).map((it) => ({ it, text: it.title || (it.body || '').split('\n')[0] || it.checks?.[0]?.text || 'Untitled', score: 0 }));
      }
      out.push(...scored.map(({ it, text }) => {
        const p = parseDue(it.due);
        return {
          group: q ? 'Notes & tasks' : 'Recent',
          label: text || 'Untitled',
          icon: it.kind === 'list' ? 'list' : p ? 'calendar' : 'text',
          extra: [it.archived ? 'Archived' : it.done ? 'Done' : '', p ? relDay(p.day) : ''].filter(Boolean).join(' · '),
          run: () => hooks.reveal(it.id),
        };
      }));
    }
    entries = out;
    idx = 0;
    render();
  }

  function render() {
    const nodes = [];
    let lastGroup = null;
    entries.forEach((e, i) => {
      if (e.group !== lastGroup) { nodes.push(h('div', { class: 'palette-group' }, e.group)); lastGroup = e.group; }
      nodes.push(h('button', {
        class: ['menu-item', i === idx && 'active'],
        'data-i': i,
        onmousemove: () => { if (idx !== i) { idx = i; mark(); } },
        onclick: () => pick(i),
      }, icon(e.icon, 16), h('span', { class: 'grow' }, e.label), e.extra ? h('span', { class: 'sub' }, e.extra) : null, e.kbd ? h('kbd', null, e.kbd) : null));
    });
    if (!entries.length) nodes.push(h('div', { class: 'pop-hint', style: { padding: '14px' } }, 'No matches. Press Enter to capture it as a new note.'));
    fill(listEl, ...nodes);
  }
  function mark() {
    listEl.querySelectorAll('.menu-item').forEach((b) => b.classList.toggle('active', +b.dataset.i === idx));
    listEl.querySelector('.menu-item.active')?.scrollIntoView({ block: 'nearest' });
  }
  function pick(i) {
    const e = entries[i];
    close();
    if (e) e.run();
    else if (input.value.trim()) hooks.focusCapture(input.value.trim());
  }

  input.addEventListener('input', compute);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown' || (e.ctrlKey && e.key === 'n')) { e.preventDefault(); idx = Math.min(entries.length - 1, idx + 1); mark(); }
    else if (e.key === 'ArrowUp' || (e.ctrlKey && e.key === 'p')) { e.preventDefault(); idx = Math.max(0, idx - 1); mark(); }
    else if (e.key === 'Enter') { e.preventDefault(); pick(idx); }
    else if (e.key === 'Escape' || ((e.ctrlKey || e.metaKey) && e.key === 'k')) { e.preventDefault(); e.stopPropagation(); close(); }
  });

  const back = h('div', { class: 'modal-back', onmousedown: (e) => { if (e.target === back) close(); } },
    h('div', { class: 'modal', role: 'dialog', 'aria-label': 'Command palette' },
      h('div', { class: 'palette-input' }, icon('search', 18), input),
      listEl,
      h('div', { class: 'palette-foot' },
        h('span', null, h('kbd', null, '↑'), h('kbd', null, '↓'), 'navigate'),
        h('span', null, h('kbd', null, '↵'), 'run'),
        h('span', null, h('kbd', null, '#'), 'labels only'),
        h('span', null, h('kbd', null, 'Esc'), 'close'))));
  document.getElementById('layer').append(back);
  compute();
  input.focus();
  input.setSelectionRange(input.value.length, input.value.length);
}
