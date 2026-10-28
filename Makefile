.PHONY: server test web-test android android-release run deploy

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

# proteus: Caddy proxies jot.mooned.de → 127.0.0.1:5566 (user service, data in ~/.local/share/jot)
deploy: server
	scp -q bin/jot proteus:.local/bin/jot.new
	ssh proteus 'mv ~/.local/bin/jot.new ~/.local/bin/jot && systemctl --user restart jot && systemctl --user is-active jot'
