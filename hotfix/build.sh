#!/bin/bash
# Builds the static arm64 unpacker. Like emulators/setup, prebuilt/source.sha1
# records the sources; --check verifies the prebuilt without needing Go.
set -euo pipefail
cd "$(dirname "$0")"

sources() {
	find go.mod cmd internal -type f -name '*.go' -o -type f -name go.mod |
		LC_ALL=C sort | xargs sha1sum | sha1sum | cut -d' ' -f1
}

if [ "${1:-}" = --check ]; then
	if [ "$(sources)" != "$(cat prebuilt/source.sha1 2>/dev/null || true)" ] ||
		[ ! -s prebuilt/andr36oid-hotfix-unpack ]; then
		echo "hotfix: prebuilt/ is out of date, run hotfix/build.sh" >&2
		exit 1
	fi
	exit 0
fi

GO=${GO:-$(command -v go || echo /usr/local/go/bin/go)}
"$GO" vet ./...
"$GO" test ./...
mkdir -p prebuilt
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 GOFLAGS=-trimpath \
	"$GO" build -ldflags '-s -w -buildid=' -o prebuilt/andr36oid-hotfix-unpack ./cmd/andr36oid-hotfix-unpack
sources >prebuilt/source.sha1
"$GO" version >prebuilt/go-version.txt
ls -l prebuilt/andr36oid-hotfix-unpack
