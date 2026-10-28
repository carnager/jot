# Jot

Self-hosted notes and todos: a Google Keep replacement that is also a real task manager.
It's built for speed, with no Google services and no push dependencies.

- **server/**: a single Go binary (SQLite, sync API, embedded web app)
- **server/web/**: the desktop-first web app (keyboard-driven, no build step)
- **android/**: a native Kotlin/Compose app. It works offline, and reminders fire locally with exact alarms.

## Features

- **One capture line**: `Dentist tomorrow 10am #health`, `Gym every mon and thu 7am` or
  `Zahnarzt morgen um 10` (English and German) becomes a task with a date, repeat and label. Plain text stays a note,
  and `Shopping: milk, eggs, bread` becomes a checklist.
- **Today / Upcoming / Notes**: an overdue section, a day grouped into morning/afternoon/evening, a week strip you can
  drag tasks onto, and a colored card grid for notes. It also has labels, pinning, archive, trash and search.
- **Repeats and reminders**: daily, weekly on chosen days, monthly, yearly, or "after done". There can be several
  alerts per task (e.g. one day before plus on time), with Done/Snooze right from the notification.
  Optionally the server can push reminders to an [ntfy](https://ntfy.sh) topic for your desktop.
- **Images**: paste, drop, camera or share sheet. They're downscaled and stripped of metadata on the device.
- **Sync** that merges per field and per checklist item, so editing the same note on two devices keeps both changes.
- **Fast everywhere**: a full keyboard model and command palette on the web. On Android: swipe to complete or
  reschedule, a Quick Settings tile, a widget and a share target.
- **Moving from Google Keep**: `jot import-keep` reads a Takeout export, images included.

See [SPEC.md](SPEC.md) for the data model, sync protocol, quick-add grammar and repeat rules.

## Quick start

```sh
make server                       # → bin/jot
./bin/jot user add me             # asks for a password
./bin/jot serve --data ./data     # http://localhost:8080
```

Put it behind a TLS reverse proxy (Caddy, nginx) for use outside your LAN. The Android app wants https.
For nginx, make sure `/api/events` isn't buffered (the server already sends `X-Accel-Buffering: no`).

```caddy
jot.example.com {
	reverse_proxy 127.0.0.1:8080
}
```

### Arch Linux
```sh
cd packaging/arch && makepkg -si   # builds from this repository on GitHub
sudo systemctl start jot           # creates /var/lib/jot
sudo -u jot JOT_DATA=/var/lib/jot jot user add me
sudo systemctl enable --now jot    # 127.0.0.1:8080, change JOT_ADDR in /etc/jot.env
```
Or run it as your own user: `jot user add me --data ~/.local/share/jot && systemctl --user enable --now jot`
(port 5566, `~/.config/jot.env`). The package includes `jot-backup` too (it needs restic and sqlite).

### systemd (manual)
```sh
sudo install -m755 bin/jot /usr/local/bin/jot
sudo install -m644 deploy/jot.service /etc/systemd/system/
sudo systemctl enable --now jot
sudo JOT_DATA=/var/lib/jot jot user add me
```

### Docker
```sh
docker build -f deploy/Dockerfile -t jot .
docker run -d -p 8080:8080 -v jot-data:/data --name jot jot
docker exec -it jot jot user add me
```

### Android
Build it with `make android-release`. Signing reads `~/.local/android/release-keys/jot.properties` or the
`JOT_ANDROID_*` environment variables, and falls back to the debug key. In the app, sign in with your server's URL.

## Moving from Google Keep

Export Keep from https://takeout.google.com, then run:

```sh
jot import-keep me ~/Downloads/takeout-*.zip
```

This imports text notes, checklists, images, colors, labels, and the pinned/archived/trashed state. Audio isn't
imported. Running it again updates the notes instead of duplicating them.

## Backups

All data lives in `$JOT_DATA` (`jot.db`, SQLite in WAL mode, plus `attachments/`).
`jot-backup` (`deploy/backup.sh`) takes a consistent snapshot into a restic repository given by `RESTIC_REPOSITORY`.
`deploy/jot-backup.{service,timer}` run it nightly. There's also a JSON export in the web app
(Settings → Export) and at `GET /api/export`.

## Development

```sh
make run        # serves the web app from disk, so reloading the browser picks up edits
make test       # Go tests, the quick-add/repeat test vectors in JS and Kotlin
make android    # debug APK → android/app/build/outputs/apk/debug/
make demo       # throwaway server with sample data for screenshots (demo / demo1234, port 8090)
```

`make deploy` and `make backup-install` read your server's details from a `local.mk` that isn't committed
(see the Makefile).

## License

MIT
