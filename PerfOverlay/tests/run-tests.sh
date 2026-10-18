#!/bin/bash
# Host test of the app frame rate's dump parsing (LayerFrames.java, plain Java, no android.*).
#   PerfOverlay/tests/run-tests.sh
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
"$JDK/javac" -encoding UTF-8 -d "$T" "$HERE/src/org/andr36oid/perfoverlay/LayerFrames.java" \
	"$HERE/tests/LayerFramesTest.java"
"$JDK/java" -cp "$T" org.andr36oid.perfoverlay.LayerFramesTest
