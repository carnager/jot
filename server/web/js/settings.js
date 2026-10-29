// Server-side user settings (SPEC §2c): timezone, all-day reminder time, ntfy push, illustrations. Cached for offline starts.
import { lsGet, lsSet } from './util.js';
import { ui } from './ui.js';

const KEY = 'jot.serverSettings';
let cur = lsGet(KEY, null);
apply();

export const serverSettings = () => cur || {};

function apply() {
  // The web app's own all-day time comes from the synced settings.
  if (cur?.allDayTime) ui.settings.allDayTime = cur.allDayTime;
}

async function errorOf(res) {
  try { return (await res.json()).error || res.statusText; } catch { return res.statusText || `HTTP ${res.status}`; }
}

export async function loadSettings() {
  try {
    const res = await fetch('/api/settings', { credentials: 'same-origin' });
    if (res.ok) {
      cur = await res.json();
      lsSet(KEY, cur);
      apply();
    }
  } catch { /* offline: keep the cached copy */ }
  return serverSettings();
}

/** PUT the whole object with `patch` applied (unknown keys are kept). Resolves { ok } or { error }. */
export async function saveSettings(patch) {
  const next = { ...serverSettings(), ...patch };
  if (patch.ntfy) next.ntfy = { ...(serverSettings().ntfy || {}), ...patch.ntfy };
  if (patch.art) next.art = { ...(serverSettings().art || {}), ...patch.art };
  try {
    const res = await fetch('/api/settings', {
      method: 'PUT', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(next),
    });
    if (!res.ok) return { error: await errorOf(res) };
    cur = await res.json();
    lsSet(KEY, cur);
    apply();
    return { ok: true };
  } catch {
    return { error: 'Offline — settings could not be saved' };
  }
}

export async function ntfyTest() {
  try {
    const res = await fetch('/api/settings/ntfy-test', {
      method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{}',
    });
    return res.ok ? { ok: true } : { error: await errorOf(res) };
  } catch {
    return { error: 'Server not reachable' };
  }
}

/** Ask the server to paint a picture for an item (SPEC §2d). It arrives later through sync. */
export async function illustrate(id) {
  try {
    const res = await fetch(`/api/items/${encodeURIComponent(id)}/art`, {
      method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{}',
    });
    return res.ok ? { ok: true } : { error: await errorOf(res) };
  } catch {
    return { error: 'Server not reachable' };
  }
}

export const deviceZone = () => { try { return Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch { return ''; } };
export function zoneList() {
  try { return Intl.supportedValuesOf('timeZone'); } catch { return []; }
}
