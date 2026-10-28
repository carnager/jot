# Jot

Self-hosted notes and todos: a Google Keep replacement that is also a real task manager.
It has no Google services and no push dependencies.

- **server/**: a single Go binary (SQLite, sync API, embedded web app)
- **server/web/**: the desktop-first web app (keyboard-driven, no build step)
- **android/**: a native Kotlin/Compose app. It works offline, and reminders fire locally with exact alarms.

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

### systemd
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

## Moving from Google Keep

Export Keep from https://takeout.google.com, then run:

```sh
jot import-keep me ~/Downloads/takeout-*.zip
```

This imports text notes, checklists, colors, labels, and the pinned/archived/trashed state.
Images and audio aren't imported. Running it again updates the notes instead of duplicating them.

## Backups

All data lives in `$JOT_DATA/jot.db` (SQLite in WAL mode). Use `sqlite3 jot.db ".backup backup.db"`, or
download a JSON export from the web app (Settings → Export) or from `GET /api/export`.

## Development

```sh
make run        # serves the web app from disk, so reloading the browser picks up edits
make test       # Go tests, the quick-add/repeat test vectors in JS and Kotlin
make android    # debug APK → android/app/build/outputs/apk/debug/
```
