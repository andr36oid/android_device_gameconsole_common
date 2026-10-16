#!/bin/bash
# Host tests for helper/andr36oid-apkinstall.sh: the scan list and the install loop, with
# a fake EASYROMS and a fake pm. Run: ApkInstall/tests/run-tests.sh
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
HELPER=$HERE/../helper/andr36oid-apkinstall.sh
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fails=0
check() {
	if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi
}

E=$T/easyroms
mkdir -p "$E/apk/sub" "$T/dir"
printf 'aaaa' >"$E/apk/Game One.apk"
printf 'bb' >"$E/apk/TOOL.APK"
printf 'x' >"$E/apk/readme.txt"
printf 'x' >"$E/apk/.hidden.apk"
printf 'x' >"$E/apk/sub/inner.apk"
printf 'ccc' >"$E/apk/Update.apk"
printf 'package: org.ex.update\nversion_code: 5\n' >"$E/apk/Update.yml"
printf 'ccc' >"$E/apk/Old.apk"
printf 'package: org.ex.old\nversion_code: "3"\n' >"$E/apk/Old.yml"
printf 'ccc' >"$E/apk/New.apk"
printf 'package: org.ex.new\nversion_code: 1\n' >"$E/apk/New.yml"
printf 'ccc' >"$E/apk/Unticked.apk"
printf 'package: org.ex.unticked\nversion_code: 1\nchecked: false\n' >"$E/apk/Unticked.yml"
printf 'ccc' >"$E/apk/Removed.apk"
printf 'package: org.ex.removed\nversion_code: 1\n' >"$E/apk/Removed.yml"
mkdir -p "$T/seen"; : >"$T/seen/installed.org.ex.removed"
printf 'ccc' >"$E/apk/NoVersion.apk"
printf 'package: org.ex.nover\n' >"$E/apk/NoVersion.yml"
# fake pm: reads the APK from stdin, fails for TOOL.APK like a real error would
cat >"$T/pm" <<'EOF'
#!/bin/bash
case "$*" in
"list packages --show-versioncode org.ex.update") echo "package:org.ex.update versionCode:4"; exit 0 ;;
"list packages --show-versioncode org.ex.old") echo "package:org.ex.old versionCode:3"; exit 0 ;;
"list packages"*) exit 0 ;;
esac
size=$(wc -c)
echo "$*|$size" >>"$PMLOG"
case "$*" in *" -S 2"*) echo "Failure [INSTALL_FAILED_OLDER_SDK: needs a newer Android]"; exit 1 ;; esac
echo Success
EOF
chmod +x "$T/pm"
export APKINSTALL_DIR=$T/dir APKINSTALL_EASYROMS=$E APKINSTALL_PM=$T/pm PMLOG=$T/pmlog APKINSTALL_SEEN=$T/seen

echo action=scan >"$T/dir/request"
bash "$HELPER"
check "scan status done" '[ "$(cat "$T/dir/status")" = done ]'
check "scan lists eight APKs" '[ "$(wc -l <"$T/dir/list")" = 8 ]'
check "scan has size, ask and name" 'grep -q "^4	[0-9]*	ask	Game One.apk$" "$T/dir/list"'
check "scan finds upper case .APK" 'grep -q "	ask	TOOL.APK$" "$T/dir/list"'
check "ours, older version installed: silent" 'grep -q "	silent	Update.apk$" "$T/dir/list"'
check "ours, same version installed: current" 'grep -q "	current	Old.apk$" "$T/dir/list"'
check "ours, new and ticked: silent" 'grep -q "	silent	New.apk$" "$T/dir/list"'
check "ours, new but unticked: current" 'grep -q "	current	Unticked.apk$" "$T/dir/list"'
check "ours, removed by the user before: current" 'grep -q "	current	Removed.apk$" "$T/dir/list"'
check ".yml without version_code: ask" 'grep -q "	ask	NoVersion.apk$" "$T/dir/list"'
check "scan skips hidden, other files, sub folders" '! grep -qE "hidden|readme|inner" "$T/dir/list"'

printf 'Game One.apk\nTOOL.APK\n../escape.apk\nmissing.apk\n' >"$T/dir/install"
echo action=install >"$T/dir/request"
bash "$HELPER"
check "install status done" '[ "$(cat "$T/dir/status")" = done ]'
check "install ok line" 'grep -q "^ok	Game One.apk$" "$T/dir/result"'
check "install keeps pm reason" 'grep -q "^fail	TOOL.APK	Failure \[INSTALL_FAILED_OLDER_SDK" "$T/dir/result"'
check "install refuses paths" 'grep -q "^fail	../escape.apk	missing$" "$T/dir/result"'
check "install missing file" 'grep -q "^fail	missing.apk	missing$" "$T/dir/result"'
check "pm streamed the file with its size" 'grep -q "install -r --user 0 -S 4|4" "$T/pmlog"'
check "pm ran twice" '[ "$(wc -l <"$T/pmlog")" = 2 ]'

APKINSTALL_EASYROMS="" bash -c 'echo action=scan >"$APKINSTALL_DIR/request"; bash "$0"' "$HELPER"
check "no EASYROMS: error, empty list" '[ "$(cat "$T/dir/status")" = "error noeasyroms" ] && [ ! -s "$T/dir/list" ]'

rm -rf "$E/apk"
echo action=scan >"$T/dir/request"
bash "$HELPER"
check "no apk folder: done, empty list" '[ "$(cat "$T/dir/status")" = done ] && [ ! -s "$T/dir/list" ]'

echo garbage >"$T/dir/request"
bash "$HELPER"
check "bad request" '[ "$(cat "$T/dir/status")" = "error request" ]'

[ $fails -eq 0 ] && echo "all passed" || { echo "$fails failed"; exit 1; }
