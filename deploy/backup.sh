#!/bin/bash
# Nightly Jot backup: a consistent SQLite snapshot (plus attachments, once they exist)
# into a restic repository on the storage box. Runs as the jot.service user on proteus.
#
#   JOT_DATA          data directory (default ~/.local/share/jot)
#   RESTIC_REPOSITORY default sftp:scorpia:Backup/Jot/restic
#   RESTIC_PASSWORD_FILE default ~/.config/jot-backup/restic-password
#
# Restore: restic -r sftp:scorpia:Backup/Jot/restic restore latest --target /tmp/jot-restore
set -euo pipefail

data="${JOT_DATA:-$HOME/.local/share/jot}"
export RESTIC_REPOSITORY="${RESTIC_REPOSITORY:-sftp:scorpia:Backup/Jot/restic}"
export RESTIC_PASSWORD_FILE="${RESTIC_PASSWORD_FILE:-$HOME/.config/jot-backup/restic-password}"

stage="$(mktemp -d "${TMPDIR:-/tmp}/jot-backup.XXXXXX")"
trap 'rm -rf "$stage"' EXIT

# .backup takes a consistent copy while the server keeps running (WAL mode).
sqlite3 "$data/jot.db" ".backup '$stage/jot.db'"
[ "$(sqlite3 "$stage/jot.db" 'PRAGMA integrity_check;')" = ok ] || { echo "snapshot failed integrity check" >&2; exit 1; }

paths=("$stage/jot.db")
[ -d "$data/attachments" ] && paths+=("$data/attachments")

restic snapshots --quiet >/dev/null 2>&1 || restic init --quiet
restic backup --quiet --tag jot --host proteus "${paths[@]}"
restic forget --quiet --tag jot --keep-daily 14 --keep-weekly 8 --keep-monthly 12 --prune
echo "jot backup ok: $(sqlite3 "$stage/jot.db" 'SELECT COUNT(*) FROM items') items"
