#!/bin/bash
# Host test of Emulator files: the table, the matching (plain Java, no android.*) and
# the root helper doing the scan and the renames in a temp dir.
#   BiosCheck/tests/run-tests.sh
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
SRC=$HERE/src/org/andr36oid/bioscheck
"$JDK/javac" -encoding UTF-8 -d "$T" "$SRC/BiosTable.java" "$SRC/Scan.java" \
	"$SRC/Report.java" "$HERE/tests/ReportTest.java"
for sh in dash bash mksh; do
	command -v $sh >/dev/null || continue
	"$JDK/java" -cp "$T" org.andr36oid.bioscheck.ReportTest \
		"$HERE/helper/andr36oid-bioscheck.sh" "$HERE/res/raw/bios_table.txt" $sh
done
