.PHONY: demo server test web-test android android-release run deploy backup-install

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

# Personal deployment settings go in local.mk (not committed), for example:
#   DEPLOY_HOST = myserver                          # ssh host running the jot user service
#   PUBLISH     = ~/Code/archrepo/publish jot       # builds and uploads the Arch package
#   BACKUP_REPO = sftp:storagebox:Backup/Jot/restic # restic repository for jot-backup
-include local.mk

# Publish the package, then upgrade it on the server (full -Syu: no partial upgrades on Arch).
deploy:
	@test -n "$(DEPLOY_HOST)" || { echo "set DEPLOY_HOST in local.mk"; exit 1; }
	$(if $(PUBLISH),$(PUBLISH))
	ssh -t $(DEPLOY_HOST) 'sudo pacman -Syu --needed jot && systemctl --user restart jot && systemctl --user is-active jot'

# Nightly restic backup of ~/.local/share/jot on DEPLOY_HOST (03:15). Creates the restic password
# there once (~/.config/jot-backup/restic-password); keep a copy of it somewhere safe.
backup-install:
	@test -n "$(DEPLOY_HOST)" -a -n "$(BACKUP_REPO)" || { echo "set DEPLOY_HOST and BACKUP_REPO in local.mk"; exit 1; }
	ssh $(DEPLOY_HOST) 'mkdir -p ~/.config/jot-backup ~/.local/bin && chmod 700 ~/.config/jot-backup && { [ -f ~/.config/jot-backup/restic-password ] || (umask 077; head -c 32 /dev/urandom | base64 > ~/.config/jot-backup/restic-password); } && echo RESTIC_REPOSITORY=$(BACKUP_REPO) > ~/.config/jot-backup/env'
	scp -q deploy/backup.sh $(DEPLOY_HOST):.local/bin/jot-backup
	scp -q deploy/jot-backup.service deploy/jot-backup.timer $(DEPLOY_HOST):.config/systemd/user/
	ssh $(DEPLOY_HOST) 'systemctl --user daemon-reload && systemctl --user start jot-backup.service && journalctl --user -u jot-backup -n 3 --no-pager -o cat && systemctl --user enable --now jot-backup.timer'

# Sample data for screenshots in a throwaway server: http://localhost:8090, login demo / demo1234.
# Dates are relative to today; run it again to refresh them.
demo: server
	rm -rf demo-data
	JOT_PASSWORD=demo1234 ./bin/jot user add demo --data demo-data
	./bin/jot seed-demo demo --data demo-data
	./bin/jot serve --data demo-data --addr :8090
