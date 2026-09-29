#!/bin/bash
# Host test of Save backup: the engine (plain Java, no android.*) run through the real
# root helper script, with java in place of app_process, on fake saves in a temp dir.
# Then Sync saves (SyncTest): with SYNCTHING=path/to/syncthing (the PC build of the core
# version the Syncthing app has) also two real Syncthings as two consoles.
#   SaveBackup/tests/run-tests.sh
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
SRC=$HERE/src/org/andr36oid/savebackup
"$JDK/javac" -encoding UTF-8 -d "$T" "$SRC/Roots.java" "$SRC/Games.java" "$SRC/SaveFile.java" \
	"$SRC/Collector.java" "$SRC/Manifest.java" "$SRC/Archive.java" "$SRC/Restorer.java" \
	"$SRC/Index.java" "$SRC/Engine.java" "$SRC/Json.java" "$SRC/SyncApi.java" \
	"$SRC/SyncSetup.java" "$HERE/tests/EngineTest.java" "$HERE/tests/SyncTest.java"
for sh in dash bash mksh busybox; do
	command -v $sh >/dev/null || continue
	"$JDK/java" -cp "$T" org.andr36oid.savebackup.EngineTest \
		"$HERE/helper/andr36oid-savebackup.sh" $sh "$T" "$JDK/java"
done
"$JDK/java" -cp "$T" org.andr36oid.savebackup.SyncTest \
	"$HERE/helper/andr36oid-savebackup.sh" "$T" "$JDK/java" "${SYNCTHING:-}"
