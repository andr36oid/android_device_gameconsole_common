#!/bin/bash
# Host test of the low battery warning levels (LowBattery.java, plain Java, no android.*).
#   GameComfort/tests/run-lowbattery-tests.sh
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
"$JDK/javac" -encoding UTF-8 -d "$T" "$HERE/src/org/andr36oid/gamecomfort/LowBattery.java" \
	"$HERE/tests/LowBatteryTest.java"
"$JDK/java" -cp "$T" org.andr36oid.gamecomfort.LowBatteryTest
