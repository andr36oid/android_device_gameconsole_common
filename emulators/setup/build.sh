#!/bin/bash
# Builds prebuilt/gameconsole-setup, the setup service the image ships (see
# cmd/gameconsole-setup). Android's own Go is too old for it, so the binary is
# built here with a current Go and committed: static, arm64, reproducible (no
# paths, no build id). prebuilt/source.sha1 is the hash of the sources it was
# built from.
#
#   build.sh           run the tests, build, write prebuilt/
#   build.sh --check   only check prebuilt/ matches the sources (no Go needed);
#                      build-image.sh runs this before every build
#
# GO=/path/to/go to pick a Go (default: go in PATH, then /usr/local/go/bin/go).
set -euo pipefail
cd "$(dirname "$0")"

sources() {
	find go.mod cmd internal -type f -name '*.go' -o -type f -name go.mod |
		LC_ALL=C sort | xargs sha1sum | sha1sum | cut -d' ' -f1
}

if [ "${1:-}" = --check ]; then
	want=$(cat prebuilt/source.sha1 2>/dev/null || true)
	if [ "$(sources)" != "$want" ] || [ ! -s prebuilt/gameconsole-setup ]; then
		echo "emulators/setup: prebuilt/gameconsole-setup is out of date, run emulators/setup/build.sh" >&2
		exit 1
	fi
	exit 0
fi

GO=${GO:-$(command -v go || echo /usr/local/go/bin/go)}
"$GO" vet ./...
"$GO" test ./...
mkdir -p prebuilt
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 GOFLAGS=-trimpath \
	"$GO" build -ldflags '-s -w -buildid=' -o prebuilt/gameconsole-setup.tmp ./cmd/gameconsole-setup
mv -f prebuilt/gameconsole-setup.tmp prebuilt/gameconsole-setup
sources >prebuilt/source.sha1
"$GO" version >prebuilt/go-version.txt
ls -l prebuilt/gameconsole-setup
