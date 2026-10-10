#!/bin/bash
# Host tests for the Daijishou first-start platforms (README.md, "Daijishou platforms"):
#  1. daijishou-seed.txt is what scripts/gen-daijishou.py makes from platforms.txt,
#     rom-folders.txt, cores.lock and the official platform files
#  2. schema.sql is exactly what the shipped Daijishou.apk creates (every statement and
#     the identity hash are strings in its dex)
#  3. the seed functions of seed/gameconsole-emu.sh, run in a fake /data with a real
#     sqlite3, under bash, busybox ash, dash and mksh (MKSH=, if found), and with
#     TOYBIN= (a folder of toybox and one-true-awk links) under bash with the
#     console's tools: the library they make is one Daijishou opens (checked with checklib.py), the URI grants are well formed and match the library's
#     folders, and every "leave it alone" case leaves it alone
#
# Needs python3, and sqlite3 (the Android host build in $ANDROID_BUILD_TOP/out/host is
# used if it's there).
# Usage: tests/run-daijishou-tests.sh [path to Daijishou.apk]

set -u
HERE=$(cd "$(dirname "$0")" && pwd)
EMU=${HERE%/*}
APK=${1:-$EMU/../apps/Daijishou/Daijishou.apk}
TOP=${ANDROID_BUILD_TOP:-$(cd "$EMU/../../../.." 2>/dev/null && pwd)}
if [ -x "$TOP/out/host/linux-x86/bin/sqlite3" ]; then
	PATH=$TOP/out/host/linux-x86/bin:$PATH
fi
command -v sqlite3 >/dev/null || { echo "no sqlite3"; exit 1; }

fails=0
pass() { echo "ok   $*"; }
fail() { echo "FAIL $*"; fails=$((fails + 1)); }

# 1. generated file up to date
if python3 "$EMU/scripts/gen-daijishou.py" --check >/dev/null; then
	pass "daijishou-seed.txt matches the generator"
else
	fail "daijishou-seed.txt is out of date"
fi

# 2. schema = the APK's
if [ -f "$APK" ]; then
	if python3 "$HERE/checkschema.py" "$APK" "$EMU/daijishou/schema.sql"; then
		pass "schema.sql matches $(basename "$APK")"
	else
		fail "schema.sql doesn't match the APK"
	fi
	if python3 "$HERE/checkschema.py" "$APK" "$EMU/daijishou/widget-schema.sql"; then
		pass "widget-schema.sql matches $(basename "$APK")"
	else
		fail "widget-schema.sql doesn't match the APK"
	fi
else
	echo "skip schema check, no $APK"
fi

# 3. the shell part
run_shell() {
	local sh=$1 name=${2:-${1##*/}} out
	# shellcheck disable=SC2086 # "busybox ash" is two words
	out=$($sh "$HERE/seedtest.sh" "$EMU" 2>&1)
	local rc=$?
	# shellcheck disable=SC2001 # multi-line prefix
	echo "$out" | sed "s|^|  [$name] |"
	if [ $rc -eq 0 ]; then
		pass "seed functions under $name"
	else
		fail "seed functions under $name"
	fi
}
run_shell bash
if command -v busybox >/dev/null; then
	run_shell "busybox ash"
fi
if command -v dash >/dev/null; then
	run_shell dash
fi
# mksh is the console's shell; MKSH=/path/to/mksh if it isn't in PATH
MKSH=${MKSH:-$(command -v mksh)}
if [ -n "$MKSH" ]; then
	run_shell "$MKSH"
fi
# the console's awk (one-true-awk) and toybox, from the tree's prebuilts
TOYBIN=${TOYBIN:-}
if [ -n "$TOYBIN" ] && [ -d "$TOYBIN" ]; then
	PATH=$TOYBIN:$PATH run_shell bash "bash + toybox"
fi

if [ $fails -eq 0 ]; then
	echo "all passed"
	exit 0
fi
echo "$fails failed"
exit 1
