// Shared UI state + late-bound hooks (filled in by app.js) to avoid import cycles.
export const ui = {
  view: { type: 'notes' },   // today | upcoming | notes | label | done | archive | trash | search
  query: '',
  upcomingDay: null,         // Upcoming: day number the list is filtered to (null = all)
  cursor: null,              // focused item id in the current view
  anchor: null,              // shift-selection anchor
  selected: new Set(),
  openId: null,              // item open in the editor
  order: [],                 // ids in visual order of the current view (for j/k)
  settings: {
    theme: 'system',
    motion: 'system',
    allDayTime: '09:00',
    notifications: false,
    h12: false,
  },
};

export const hooks = {
  render: () => {},
  openEditor: (_id, _focus) => {},
  closeEditor: () => {},
  go: (_view) => {},
  focusCapture: () => {},
  openDatePop: (_ids, _anchor) => {},
  openLabelPop: (_ids, _anchor) => {},
  openColorPop: (_ids, _anchor) => {},
  openItemMenu: (_id, _anchor) => {},
};

/** The ids an action applies to: the selection, else the cursor item. */
export function targets() {
  if (ui.selected.size) return [...ui.selected];
  return ui.cursor ? [ui.cursor] : [];
}

export function viewKey(v = ui.view) {
  return v.type === 'label' ? `label/${v.label}` : v.type;
}
