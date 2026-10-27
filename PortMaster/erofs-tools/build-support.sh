#!/bin/sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
: "${CC:=aarch64-linux-gnu-gcc}"
: "${STRIP:=aarch64-linux-gnu-strip}"
"$CC" -std=c11 -O2 -march=armv8-a -static -Wall -Wextra -Werror \
    -ffile-prefix-map="$here"=. -Wl,--build-id=sha1 \
    -o "$here/andr36oid-erofs-support" "$here/andr36oid-erofs-support.c"
"$STRIP" "$here/andr36oid-erofs-support"
file "$here/andr36oid-erofs-support"
