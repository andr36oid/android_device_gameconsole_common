#!/bin/bash
# Host test of the shared console and stick drawing (plain Java, no android.*): checks the
# layout, and with a folder also paints PNG previews into it.
#   ConsoleArt/tests/run-tests.sh [png folder]
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
SRC=$HERE/src/org/andr36oid/consoleart
"$JDK/javac" -encoding UTF-8 -d "$T" "$SRC/R36s.java" "$SRC/Painter.java" \
	"$SRC/ConsoleDrawing.java" "$SRC/StickDrawing.java" "$HERE/tests/ConsoleArtTest.java"
"$JDK/java" -Djava.awt.headless=true -cp "$T" org.andr36oid.consoleart.ConsoleArtTest "$@"
