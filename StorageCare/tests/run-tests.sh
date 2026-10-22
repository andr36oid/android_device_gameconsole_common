#!/bin/bash
# Host test of Free up space: the rules, the space warnings, and the engine (plain Java, no
# android.*) run through the real root helper script, with java in place of app_process,
# on a fake internal storage and EASYROMS in a temp dir.
#   StorageCare/tests/run-tests.sh
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
SRC=$HERE/src/org/andr36oid/storagecare
"$JDK/javac" -encoding UTF-8 -d "$T" "$SRC/Rules.java" "$SRC/Space.java" "$SRC/Dupes.java" \
	"$SRC/Scan.java" "$SRC/Health.java" "$SRC/Engine.java" "$HERE/tests/EngineTest.java"
for sh in dash bash mksh busybox; do
	command -v $sh >/dev/null || continue
	[ $sh = busybox ] && sh="busybox sh"
	"$JDK/java" -cp "$T" org.andr36oid.storagecare.EngineTest \
		"$HERE/helper/andr36oid-storagecare.sh" "$sh" "$T" "$JDK/java"
done

bash "$HERE/../easyroms/tests/helpers-test.sh" StorageCare
