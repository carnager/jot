import { h, icon } from './util.js';
import { flipAround } from './motion.js';

const undoStack = [];

/** Show a toast. If `undo` is given (snapshot restore fn), it's also pushed onto the Ctrl+Z stack. */
export function toast(message, undo = null, { timeout = 6000 } = {}) {
  const host = document.getElementById('toasts');
  if (undo) {
    undoStack.push({ message, undo });
    if (undoStack.length > 50) undoStack.shift();
  }
  const entry = undo ? undoStack[undoStack.length - 1] : null;
  const close = () => {
    if (el.classList.contains('out')) return;
    el.classList.add('out');
    // Let the toast fade, then the remaining ones glide into place.
    setTimeout(() => flipAround([...host.children].filter((t) => t !== el), () => el.remove()), 170);
  };
  const el = h('div', { class: 'toast', role: 'status' },
    h('span', { class: 'toast-msg' }, message),
    undo && h('button', {
      class: 'toast-undo',
      onclick: () => { runUndo(entry); close(); },
    }, 'Undo'),
    h('button', { class: 'toast-x', 'aria-label': 'Dismiss', onclick: close }, icon('x', 14)),
  );
  // Keep at most 3 on screen.
  while (host.children.length >= 3) host.firstElementChild.remove();
  const others = [...host.children];
  flipAround(others, () => host.append(el));
  setTimeout(close, timeout);
}

function runUndo(entry) {
  const i = undoStack.indexOf(entry);
  if (i < 0) return;
  undoStack.splice(i, 1);
  entry.undo();
}

export function undoLast() {
  const e = undoStack.pop();
  if (!e) { toast('Nothing to undo'); return; }
  e.undo();
  toast(`Undone: ${e.message}`);
}
