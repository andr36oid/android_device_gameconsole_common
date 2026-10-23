#!/bin/sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SCRATCH=${FLIPPER_SCRATCH:?Set FLIPPER_SCRATCH to an authorized temporary directory}
mkdir -p "$SCRATCH"
BUILD=$(mktemp -d "$SCRATCH/flipper-tests.XXXXXX")
trap 'rm -rf "$BUILD"' EXIT HUP INT TERM
if [ -n "${KOTLINC:-}" ]; then
    COMPILER=$KOTLINC
elif command -v kotlinc >/dev/null 2>&1; then
    COMPILER=$(command -v kotlinc)
elif [ -x /andr36oid/src/external/kotlinc/bin/kotlinc ]; then
    COMPILER=/andr36oid/src/external/kotlinc/bin/kotlinc
else
    echo "FAIL: Kotlin/JVM compiler missing; set KOTLINC to a host kotlinc executable" >&2
    exit 1
fi
command -v java >/dev/null 2>&1 || { echo 'FAIL: host JDK java missing' >&2; exit 1; }
find "$HERE/src" "$HERE/tests" "$HERE/host" -name '*.kt' -print > "$BUILD/sources"
"$COMPILER" @"$BUILD/sources" -jvm-target 1.8 -include-runtime -d "$BUILD/tests.jar"
java -cp "$BUILD/tests.jar" org.andr36oid.flipper.tests.CoreTestKt
java -cp "$BUILD/tests.jar" org.andr36oid.flipper.host.BootHarnessKt "$@"
