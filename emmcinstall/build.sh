#!/bin/bash
# Builds prebuilt/andr36oid-emmcinstall, the eMMC installer the image ships (see
# cmd/andr36oid-emmcinstall). Like emulators/setup: Android's own Go is too old,
# so it is built here with a current Go and committed: static, arm64,
# reproducible (no paths, no build id). prebuilt/source.sha1 is the hash of the
# sources it was built from.
#
#   build.sh           run the tests, build, write prebuilt/
#   build.sh --check   only check prebuilt/ matches the sources (no Go needed);
#                      build-image.sh runs this before every build
#
# Libraries are vendored (vendor/), so it builds offline: after changing
# go.mod, run go mod vendor.
#
# GO=/path/to/go to pick a Go (default: go in PATH, then /usr/local/go/bin/go).
# The tests need sgdisk (read-only use) and python3.
set -euo pipefail
cd "$(dirname "$0")"

sources() {
	find go.mod go.sum cmd internal vendor -type f -name '*.go' -o -type f -name go.mod \
		-o -type f -name go.sum -o -type f -name modules.txt |
		LC_ALL=C sort | xargs sha1sum | sha1sum | cut -d' ' -f1
}

if [ "${1:-}" = --check ]; then
	want=$(cat prebuilt/source.sha1 2>/dev/null || true)
	if [ "$(sources)" != "$want" ] || [ ! -s prebuilt/andr36oid-emmcinstall ]; then
		echo "emmcinstall: prebuilt/andr36oid-emmcinstall is out of date, run emmcinstall/build.sh" >&2
		exit 1
	fi
	exit 0
fi

GO=${GO:-$(command -v go || echo /usr/local/go/bin/go)}
"$GO" vet ./...
"$GO" test ./...
mkdir -p prebuilt
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 GOFLAGS=-trimpath \
	"$GO" build -ldflags '-s -w -buildid=' -o prebuilt/andr36oid-emmcinstall.tmp ./cmd/andr36oid-emmcinstall
mv -f prebuilt/andr36oid-emmcinstall.tmp prebuilt/andr36oid-emmcinstall
sources >prebuilt/source.sha1
"$GO" version >prebuilt/go-version.txt
ls -l prebuilt/andr36oid-emmcinstall
