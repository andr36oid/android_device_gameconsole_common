#!/bin/bash
# Host test of the per-app profile rules (plain Java, no android.*).
#   CpuOverclock/tests/run-tests.sh
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
SRC=$HERE/src/org/andr36oid/cpuoverclock
"$JDK/javac" -encoding UTF-8 -d "$T" "$SRC/AppProfileRules.java" \
	"$HERE/tests/AppProfileRulesTest.java"
"$JDK/java" -cp "$T" org.andr36oid.cpuoverclock.AppProfileRulesTest \
	"$HERE/res/values/app_profiles.xml"
