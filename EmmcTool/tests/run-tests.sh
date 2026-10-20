#!/bin/bash
# Host tests of Built-in eMMC:
#   - EmmcInfoTest: reading the helper's info, chip names, wear and sizes
#   - helper-test.sh: the root helper, run with bash and (if found) mksh, with the speed
#     tool built for the host
#   EmmcTool/tests/run-tests.sh [path to mksh]
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
MKSH=${1:-$(command -v mksh || true)}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
SRC=$HERE/src/org/andr36oid/emmc
"$JDK/javac" -encoding UTF-8 -d "$T" "$SRC/EmmcInfo.java" "$HERE/tests/EmmcInfoTest.java"
"$JDK/java" -cp "$T" org.andr36oid.emmc.EmmcInfoTest
cc -std=c11 -Wall -Wextra -Werror -O2 -o "$T/emmcspeed" "$HERE/speed/emmcspeed.c"
for sh in bash $MKSH; do
	"$HERE/tests/helper-test.sh" "$sh" "$T/emmcspeed"
done
