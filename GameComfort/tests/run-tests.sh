#!/bin/bash
# Host test of "Continue playing": the RetroArch/PPSSPP parsers on real-format samples, the
# choice of the game, and the root helper on a fake console tree (plain Java, no android.*).
#   GameComfort/tests/run-tests.sh
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
# File names with é must reach the file system as UTF-8
export LC_ALL=C.UTF-8
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
"$JDK/javac" -encoding UTF-8 -d "$T" "$HERE"/src/org/andr36oid/gamecomfort/resume/*.java \
	"$HERE/tests/ResumeTest.java"
run() {
	"$JDK/java" -Dfile.encoding=UTF-8 -cp "$T" org.andr36oid.gamecomfort.resume.ResumeTest \
		"$HERE/helper/andr36oid-gamecomfort.sh" "$HERE/tests/samples" "$1"
}
for sh in dash bash mksh; do
	command -v $sh >/dev/null || continue
	run $sh
done
# Once more with Android's toybox stat/ls/cat, as on the console
TOYBOX=${TOYBOX:-/andr36oid/src/prebuilts/build-tools/linux-x86/bin/toybox}
if [ -x "$TOYBOX" ]; then
	mkdir "$T/toybox"
	for tool in stat ls cat mv rm mkdir; do
		ln -s "$TOYBOX" "$T/toybox/$tool"
	done
	PATH="$T/toybox:$PATH" run dash
fi
