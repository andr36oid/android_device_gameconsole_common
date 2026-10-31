#!/bin/sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
: "${CC:=aarch64-linux-gnu-gcc}"
: "${STRIP:=aarch64-linux-gnu-strip}"
bin="$here/andr36oid-erofs-support"
# With --check (used by build-image.sh) rebuild to a temp and compare: a changed
# .c that was never regenerated into the committed prebuilt then fails the build
# instead of silently shipping a stale binary. The build is deterministic
# (prefix-map + build-id=sha1 + strip), so unchanged source reproduces byte-for-byte.
if [ "${1:-}" = --check ]; then out=$(mktemp); else out="$bin"; fi
"$CC" -std=c11 -O2 -march=armv8-a -static -Wall -Wextra -Werror \
    -ffile-prefix-map="$here"=. -Wl,--build-id=sha1 \
    -o "$out" "$here/andr36oid-erofs-support.c"
"$STRIP" "$out"
if [ "${1:-}" = --check ]; then
    if cmp -s "$out" "$bin"; then rm -f "$out"; echo "andr36oid-erofs-support prebuilt matches its source"; else
        rm -f "$out"; echo "andr36oid-erofs-support prebuilt is STALE: rerun erofs-tools/build-support.sh and commit it" >&2; exit 1; fi
else
    file "$bin"
fi
