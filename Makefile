.PHONY: server test web-test android android-release run deploy backup-install

server:
	cd server && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o ../bin/jot .

test: web-test
	cd server && go test ./...
	cd android && ./gradlew --quiet testDebugUnitTest

web-test:
	node server/web/test/run.mjs

android:
	cd android && ./gradlew assembleDebug

android-release:
	cd android && ./gradlew assembleRelease

run: server
	./bin/jot serve --data ./data --web-dir server/web

# proteus runs the Arch package (repo.mooned.de) as a user service on 127.0.0.1:5566 behind Caddy
# (jot.mooned.de). Commit first: the package builds the committed state.
deploy:
	~/Code/archrepo/publish jot
	ssh -t proteus 'sudo pacman -Sy --needed jot && systemctl --user restart jot && systemctl --user is-active jot'

# Nightly restic backup of ~/.local/share/jot to sftp:scorpia:Backup/Jot/restic (03:15).
# Creates the restic password on proteus once; keep a copy of it somewhere safe.
backup-install:
	ssh proteus 'mkdir -p ~/.config/jot-backup && chmod 700 ~/.config/jot-backup && { [ -f ~/.config/jot-backup/restic-password ] || (umask 077; head -c 32 /dev/urandom | base64 > ~/.config/jot-backup/restic-password); }'
	scp -q deploy/backup.sh proteus:.local/bin/jot-backup
	scp -q deploy/jot-backup.service deploy/jot-backup.timer proteus:.config/systemd/user/
	ssh proteus 'systemctl --user daemon-reload && systemctl --user start jot-backup.service && journalctl --user -u jot-backup -n 3 --no-pager -o cat && systemctl --user enable --now jot-backup.timer'
