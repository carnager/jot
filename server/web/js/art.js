// Inline SVG illustrations for empty states. Colors come from CSS variables (--art-*),
// so they follow the theme. Each is a 160×120 scene.

const S = (body) => `<svg viewBox="0 0 160 120" width="160" height="120" fill="none" aria-hidden="true">${body}</svg>`;

// soft ground shadow + sparkles shared by several scenes
const ground = '<ellipse cx="80" cy="108" rx="52" ry="6" fill="var(--art-shadow)"/>';
const sparkle = (x, y, s = 1, c = 'var(--art-accent)') =>
  `<path transform="translate(${x} ${y}) scale(${s})" d="M0-7C.8-2.2 2.2-.8 7 0 2.2.8.8 2.2 0 7-.8 2.2-2.2.8-7 0-2.2-.8-.8-2.2 0-7Z" fill="${c}"/>`;

export const ART = {
  // Today, nothing left: a sun rising over soft hills.
  clear: S(`
    ${ground}
    <circle cx="80" cy="62" r="34" fill="var(--art-soft)"/>
    <circle cx="80" cy="62" r="22" fill="var(--art-accent)"/>
    <g stroke="var(--art-accent)" stroke-width="3" stroke-linecap="round">
      <path d="M80 18v8M80 98v-2M36 62h8M116 62h8M49 31l5 5M111 31l-5 5"/>
    </g>
    <path d="M18 104c14-20 30-26 46-18 10-8 26-12 40-4 14-6 28-2 38 22z" fill="var(--art-surface)" stroke="var(--art-line)" stroke-width="1.5"/>
    <path d="M70 61l7 7 13-14" stroke="var(--art-on-accent)" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>
    ${sparkle(130, 26, 0.9)}${sparkle(28, 36, 0.6, 'var(--art-line)')}${sparkle(140, 70, 0.5, 'var(--art-line)')}`),

  // Upcoming, nothing scheduled: a calendar page with a flag.
  calendar: S(`
    ${ground}
    <rect x="40" y="26" width="80" height="74" rx="12" fill="var(--art-surface)" stroke="var(--art-line)" stroke-width="1.5"/>
    <path d="M40 38a12 12 0 0 1 12-12h56a12 12 0 0 1 12 12v8H40z" fill="var(--art-accent)"/>
    <rect x="58" y="18" width="5" height="16" rx="2.5" fill="var(--art-ink)"/><rect x="97" y="18" width="5" height="16" rx="2.5" fill="var(--art-ink)"/>
    <g fill="var(--art-soft)">
      <rect x="52" y="56" width="12" height="10" rx="3"/><rect x="74" y="56" width="12" height="10" rx="3"/><rect x="96" y="56" width="12" height="10" rx="3"/>
      <rect x="52" y="74" width="12" height="10" rx="3"/><rect x="96" y="74" width="12" height="10" rx="3"/>
    </g>
    <rect x="74" y="74" width="12" height="10" rx="3" fill="var(--art-accent)"/>
    ${sparkle(132, 30, 0.8)}${sparkle(26, 60, 0.55, 'var(--art-line)')}`),

  // Notes: two stacked cards and a pencil.
  notes: S(`
    ${ground}
    <rect x="34" y="34" width="62" height="70" rx="10" transform="rotate(-8 65 69)" fill="var(--art-soft)"/>
    <rect x="56" y="24" width="66" height="76" rx="11" fill="var(--art-surface)" stroke="var(--art-line)" stroke-width="1.5"/>
    <rect x="56" y="24" width="4" height="76" rx="2" fill="var(--art-accent)"/>
    <rect x="68" y="38" width="38" height="6" rx="3" fill="var(--art-ink)"/>
    <g fill="var(--art-line)"><rect x="68" y="52" width="44" height="4" rx="2"/><rect x="68" y="62" width="36" height="4" rx="2"/><rect x="68" y="72" width="40" height="4" rx="2"/></g>
    <g transform="rotate(38 120 70)"><rect x="114" y="40" width="10" height="44" rx="3" fill="var(--art-accent)"/><path d="M114 84h10l-5 10z" fill="var(--art-ink)"/><rect x="114" y="40" width="10" height="7" rx="2" fill="var(--art-ink)"/></g>
    ${sparkle(30, 28, 0.8)}`),

  // A label with nothing in it.
  tag: S(`
    ${ground}
    <path d="M44 40a8 8 0 0 1 8-8h38l30 30-38 38-38-38z" fill="var(--art-surface)" stroke="var(--art-line)" stroke-width="1.5" stroke-linejoin="round"/>
    <circle cx="62" cy="50" r="7" fill="var(--art-accent)"/>
    <path d="M76 66l10-16M72 60h18M70 70h18" stroke="var(--art-line)" stroke-width="3" stroke-linecap="round" transform="translate(8 6)"/>
    ${sparkle(124, 30, 0.85)}${sparkle(36, 92, 0.5, 'var(--art-line)')}`),

  // Done list empty: a ribbon / badge.
  done: S(`
    ${ground}
    <path d="M64 78l-8 28 12-6 8 10 6-26zM96 78l8 28-12-6-8 10-6-26z" fill="var(--art-soft)"/>
    <circle cx="80" cy="56" r="32" fill="var(--art-surface)" stroke="var(--art-line)" stroke-width="1.5"/>
    <circle cx="80" cy="56" r="22" fill="var(--art-accent)"/>
    <path d="M70 56l7 7 13-14" stroke="var(--art-on-accent)" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>
    ${sparkle(126, 28, 0.9)}${sparkle(32, 40, 0.6)}`),

  // Archive: an open box.
  archive: S(`
    ${ground}
    <path d="M38 54h84v42a8 8 0 0 1-8 8H46a8 8 0 0 1-8-8z" fill="var(--art-surface)" stroke="var(--art-line)" stroke-width="1.5"/>
    <path d="M34 42a6 6 0 0 1 6-6h80a6 6 0 0 1 6 6v12H34z" fill="var(--art-soft)" stroke="var(--art-line)" stroke-width="1.5"/>
    <rect x="66" y="66" width="28" height="7" rx="3.5" fill="var(--art-accent)"/>
    ${sparkle(130, 24, 0.8)}${sparkle(28, 30, 0.55, 'var(--art-line)')}`),

  // Trash: a clean bin with sparkles.
  trash: S(`
    ${ground}
    <path d="M52 40h56l-5 60a8 8 0 0 1-8 7H65a8 8 0 0 1-8-7z" fill="var(--art-surface)" stroke="var(--art-line)" stroke-width="1.5"/>
    <rect x="44" y="30" width="72" height="10" rx="5" fill="var(--art-soft)" stroke="var(--art-line)" stroke-width="1.5"/>
    <rect x="70" y="22" width="20" height="8" rx="4" fill="var(--art-line)"/>
    <g stroke="var(--art-line)" stroke-width="3" stroke-linecap="round"><path d="M70 54v38M90 54v38"/></g>
    ${sparkle(124, 46, 1)}${sparkle(36, 56, 0.7)}${sparkle(132, 80, 0.5, 'var(--art-line)')}`),

  // Search, nothing found.
  search: S(`
    ${ground}
    <circle cx="72" cy="54" r="30" fill="var(--art-surface)" stroke="var(--art-line)" stroke-width="1.5"/>
    <circle cx="72" cy="54" r="21" fill="var(--art-soft)"/>
    <path d="M94 76l22 22" stroke="var(--art-ink)" stroke-width="9" stroke-linecap="round"/>
    <path d="M94 76l22 22" stroke="var(--art-accent)" stroke-width="5" stroke-linecap="round"/>
    <path d="M66 48c0-4 3-7 7-7s7 3 7 7-7 5-7 10M73 65v.5" stroke="var(--art-ink)" stroke-width="3.5" stroke-linecap="round"/>
    ${sparkle(124, 30, 0.75)}`),
};
