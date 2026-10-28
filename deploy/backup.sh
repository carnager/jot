#!/bin/bash
# Nightly Jot backup: a consistent SQLite snapshot (plus attachments, once they exist)
# into a restic repository. Runs as the user that runs jot.
#
#   JOT_DATA          data directory (default ~/.local/share/jot)
#   RESTIC_REPOSITORY required, e.g. sftp:backuphost:Backup/Jot/restic
#   RESTIC_PASSWORD_FILE default ~/.config/jot-backup/restic-password
#
# Restore: restic -r <repository> restore latest --target /tmp/jot-restore
set -euo pipefail

data="${JOT_DATA:-$HOME/.local/share/jot}"
: "${RESTIC_REPOSITORY:?set RESTIC_REPOSITORY (e.g. in ~/.config/jot-backup/env)}"
export RESTIC_REPOSITORY
export RESTIC_PASSWORD_FILE="${RESTIC_PASSWORD_FILE:-$HOME/.config/jot-backup/restic-password}"

stage="$(mktemp -d "${TMPDIR:-/tmp}/jot-backup.XXXXXX")"
trap 'rm -rf "$stage"' EXIT

# .backup takes a consistent copy while the server keeps running (WAL mode).
sqlite3 "$data/jot.db" ".backup '$stage/jot.db'"
[ "$(sqlite3 "$stage/jot.db" 'PRAGMA integrity_check;')" = ok ] || { echo "snapshot failed integrity check" >&2; exit 1; }

paths=("$stage/jot.db")
[ -d "$data/attachments" ] && paths+=("$data/attachments")

restic snapshots --quiet >/dev/null 2>&1 || restic init --quiet
restic backup --quiet --tag jot --host "$(hostname)" "${paths[@]}"
restic forget --quiet --tag jot --keep-daily 14 --keep-weekly 8 --keep-monthly 12 --prune
echo "jot backup ok: $(sqlite3 "$stage/jot.db" 'SELECT COUNT(*) FROM items') items"
