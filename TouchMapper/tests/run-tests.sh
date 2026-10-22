#!/bin/bash
# Host tests of Touch controls' SD card sharing: the layout checker (plain Java + libcore's
# org.json from the Android tree; skipped without it) and the root helper script on a fake tree in a temp dir, under several shells.
#   TouchMapper/tests/run-tests.sh
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
JSON=${JSON:-/andr36oid/src/libcore/json/src/main/java/org/json}
T=$(mktemp -d)
[ -n "$T" ] && [ -d "$T" ] || exit 1
trap 'rm -rf "$T"' EXIT
SRC=$HERE/src/org/andr36oid/touchmapper

if [ -f "$JSON/JSONObject.java" ] && [ -x "$JDK/javac" ]; then
# Empty stand-ins for the annotations libcore's org.json uses
stub() { # stub <package> <name>
	mkdir -p "$T/stubs/${1//.//}"
	printf 'package %s;\n@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE, java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.PARAMETER, java.lang.annotation.ElementType.CONSTRUCTOR})\npublic @interface %s {}\n' \
		"$1" "$2" >"$T/stubs/${1//.//}/$2.java"
}
stub android.compat.annotation UnsupportedAppUsage
stub libcore.util NonNull
stub libcore.util Nullable
stub libcore.api CorePlatformApi
"$JDK/javac" -encoding UTF-8 -d "$T/classes" "$T"/stubs/*/*/*.java "$T"/stubs/*/*/*/*.java "$JSON"/JSON*.java "$SRC/Profile.java" \
	"$SRC/SharedLayouts.java" "$HERE/tests/SharedLayoutsTest.java"
"$JDK/java" -cp "$T/classes" org.andr36oid.touchmapper.SharedLayoutsTest "$T" "$SRC/Inputs.java"
else
	echo "skip SharedLayouts: no libcore org.json sources at $JSON (set JSON=) or no JDK"
fi

SCRIPT=$HERE/helper/andr36oid-touchmap-share.sh
pass=0
fail=0
check() {
	if [ "$1" = "$2" ]; then pass=$((pass + 1)); else fail=$((fail + 1)); echo "FAIL [$sh] $3: got '$1', want '$2'"; fi
}

run() { # run <request>
	echo "$1" >"$R/dir/request"
	TOUCHMAP_TEST=1 TOUCHMAP_DIR="$R/dir" TOUCHMAP_LAYOUTS="$R/layouts" \
		TOUCHMAP_EASYROMS="$CARD" $sh "$SCRIPT"
	cat "$R/dir/status"
}

for sh in dash bash mksh "busybox sh"; do
	command -v "${sh%% *}" >/dev/null || { echo "skip $sh"; continue; }
	R=$T/root-${sh%% *}
	mkdir -p "$R/dir" "$R/layouts" "$R/card"
	CARD=$R/card

	check "$(run export)" "error none" "export with no layouts"
	echo '{"a":1}' >"$R/layouts/com.a.game.json"
	echo '{"b":1}' >"$R/layouts/com.b.game.json"
	: >"$R/layouts/empty.json"
	echo junk >"$R/layouts/notes.txt"
	check "$(run export)" "done export 2" "export 2"
	check "$(cat "$R/card/touchmap/com.a.game.json")" '{"a":1}' "exported content"
	check "$(find "$R/card/touchmap" -name '.*' | wc -l)" "0" "no temp files left"
	check "$(test -e "$R/dir/request" && echo yes || echo no)" "no" "request removed"

	CARD="" check "$(CARD=""; run export)" "error nocard" "export without EASYROMS"
	check "$(CARD=$R/nothere; run import)" "error nocard" "import with EASYROMS folder missing"

	rm -rf "$R/card/touchmap"
	check "$(run import)" "error empty" "import with no folder"
	mkdir -p "$R/card/touchmap"
	echo '{"x":1}' >"$R/card/touchmap/com.x.game.json"
	echo '{"y":1}' >"$R/card/touchmap/COM.Y.JSON"
	echo 'mac' >"$R/card/touchmap/._com.x.game.json"
	echo 'sp' >"$R/card/touchmap/with space.json"
	: >"$R/card/touchmap/zero.json"
	head -c 70000 /dev/zero >"$R/card/touchmap/big.json"
	ln -s /etc/passwd "$R/card/touchmap/link.json"
	echo 'old' >"$R/dir/in-stale" 2>/dev/null
	mkdir -p "$R/dir/in" && echo stale >"$R/dir/in/stale.json"
	check "$(run import)" "done import 2 4" "import 2, skip 4 (the ._ file is not even listed)"
	check "$(cd "$R/dir/in" && printf '%s ' *)" "COM.Y.JSON com.x.game.json " "staged names"
	check "$(cat "$R/dir/in/com.x.game.json")" '{"x":1}' "staged content"

	check "$(run delete)" "error request" "unknown request"
	check "$(CARD=$R/card; run export)" "done export 2" "export again overwrites"
done
echo "helper: $pass passed, $fail failed"
[ "$fail" -eq 0 ]

bash "$HERE/../easyroms/tests/helpers-test.sh" TouchMapper
