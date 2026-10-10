#!/bin/bash
# Host tests of SD card check:
#   - CardIdTest: card ID parsing, fake card signs, speed numbers and the advice
#   - helper-test.sh: the root helper, run with bash and (if found) mksh, with the small
#     block tool built for the host
#   CardCheck/tests/run-tests.sh [path to mksh]
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
MKSH=${1:-$(command -v mksh || true)}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
SRC=$HERE/src/org/andr36oid/cardcheck
"$JDK/javac" -encoding UTF-8 -d "$T" "$SRC/CardId.java" "$SRC/Stats.java" \
	"$SRC/Verdict.java" "$HERE/tests/CardIdTest.java"
"$JDK/java" -cp "$T" org.andr36oid.cardcheck.CardIdTest
cc -std=c11 -Wall -Wextra -Werror -O2 -o "$T/cardspeed" "$HERE/speed/cardspeed.c"
for sh in bash $MKSH; do
	# the host can't write /sys/power or drop caches; the helper goes on without them
	"$HERE/tests/helper-test.sh" "$sh" "$T/cardspeed" 2> >(grep -v 'Read-only file system\|Permission denied' >&2)
done
