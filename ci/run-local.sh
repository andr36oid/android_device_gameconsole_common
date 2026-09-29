#!/bin/bash
# Host checks for device/gameconsole/common, no Android tree needed. The GitHub
# workflow (.github/workflows/checks.yml) runs the same checks through this script.
#
# Usage: ci/run-local.sh [check...]
#   checks: shellcheck tests native python xml commits apksize (default: all)
#
# Settings (environment):
#   RANGE     commits to check, e.g. abc..def
#             (default: andr36oid/lineage-18.1..HEAD, or HEAD~1..HEAD without that ref)
#   BASE      commit to compare APK sizes with (default: HEAD~1, the previous commit)
#   IDENT     the one allowed author and committer (default: itskenny0 <0@kenny.cat>)
#   JDK       JDK bin folder for the Java host tests (default: the one javac is in)
#   GTEST_SRC googletest source folder for the native tests (default: the Android
#             tree's external/googletest/googletest if it's there, else the system gtest;
#             the tree is $ANDROID_BUILD_TOP, or ../../.. when run in the repo checkout)
#   SHELLCHECK_OPTS  extra shellcheck options, e.g. "-S warning"
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT" || exit 1

IDENT=${IDENT:-itskenny0 <0@kenny.cat>}
if [ -z "${RANGE:-}" ]; then
	if git rev-parse -q --verify andr36oid/lineage-18.1 >/dev/null; then
		RANGE=andr36oid/lineage-18.1..HEAD
	else
		RANGE=HEAD~1..HEAD
	fi
fi
BASE=${BASE:-HEAD~1}
if [ -z "${JDK:-}" ] && command -v javac >/dev/null; then
	JDK=$(dirname "$(readlink -f "$(command -v javac)")")
fi
export JDK

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
RESULTS=
fail_total=0

# result <check> <PASS|FAIL|SKIP> <text>
result() {
	RESULTS="$RESULTS$(printf '%-11s %-4s %s' "$1" "$2" "$3")
"
	[ "$2" = FAIL ] && fail_total=$((fail_total + 1))
}

section() { printf '\n=== %s ===\n' "$1"; }

# Every tracked *.sh through shellcheck (exclusions are the inline disable comments)
check_shellcheck() {
	section shellcheck
	command -v shellcheck >/dev/null || { result shellcheck SKIP "shellcheck not installed"; return; }
	n=$(git ls-files -z '*.sh' | tr -cd '\0' | wc -c)
	# shellcheck disable=SC2086 # options are split on purpose
	if git ls-files -z '*.sh' | xargs -0 shellcheck -f gcc ${SHELLCHECK_OPTS:-} >"$T/sc"; then
		result shellcheck PASS "$n scripts"
	else
		cat "$T/sc"
		bad=$(cut -d: -f1 "$T/sc" | sort -u | wc -l)
		result shellcheck FAIL "$(wc -l <"$T/sc") findings in $bad of $n scripts"
	fi
}

# Every tests/*.sh with a #! line that no other script in the same folder calls
# (the rest are helpers sourced or run by those).
list_tests() {
	git ls-files | grep -E '(^|/)tests/[^/]+\.sh$' | while read -r f; do
		head -c2 "$f" | grep -q '#!' || continue
		d=$(dirname "$f"); b=$(basename "$f")
		others=$(find "$d" -maxdepth 1 -name '*.sh' ! -name "$b")
		# shellcheck disable=SC2086 # file names without spaces
		if [ -n "$others" ] && grep -qF "$b" $others; then continue; fi
		echo "$f"
	done
}

check_tests() {
	section tests
	list_tests >"$T/tests"
	if [ ! -s "$T/tests" ]; then result tests SKIP "no test scripts"; return; fi
	while read -r f; do
		echo "--- $f"
		interp=$(head -n1 "$f" | sed 's/^#! *//')
		# shellcheck disable=SC2086 # "/usr/bin/env bash" is two words
		if $interp "$f" </dev/null >"$T/out" 2>&1; then
			tail -n 3 "$T/out"
			result tests PASS "$f"
		else
			cat "$T/out"
			result tests FAIL "$f"
		fi
	done <"$T/tests"
}

# Native host tests: joyMouse's cc_test_host, built with g++ and googletest.
check_native() {
	section native
	bp=joyMouse/Android.bp
	[ -f "$bp" ] || { result native SKIP "no $bp"; return; }
	command -v g++ >/dev/null || { result native SKIP "no g++"; return; }
	srcs=$(awk '/name: "libjoymouse"/{f=1} f&&/^}/{f=0} f' "$bp" | grep -o '"src/[^"]*\.cpp"' | tr -d '"')
	tests=$(awk '/name: "joyMouse_tests"/{f=1} f&&/^}/{f=0} f' "$bp" | grep -o '"tests/[^"]*\.cpp"' | tr -d '"')
	top=${ANDROID_BUILD_TOP:-$(cd "$ROOT/../../.." 2>/dev/null && pwd)}
	GTEST_SRC=${GTEST_SRC:-$top/external/googletest/googletest}
	if [ -f "$GTEST_SRC/src/gtest-all.cc" ]; then
		gtest="-I$GTEST_SRC/include -I$GTEST_SRC $GTEST_SRC/src/gtest-all.cc $GTEST_SRC/src/gtest_main.cc"
	elif [ -f /usr/include/gtest/gtest.h ]; then
		gtest="-lgtest_main -lgtest"
	else
		result native SKIP "joyMouse: no googletest (apt install libgtest-dev)"; return
	fi
	# shellcheck disable=SC2086 # lists of files and flags
	if ! (cd joyMouse && g++ -std=c++17 -O1 -Wall -Wextra -Isrc $srcs $tests $gtest -pthread \
			-o "$T/joymouse_tests") >"$T/out" 2>&1; then
		cat "$T/out"; result native FAIL "joyMouse: build"; return
	fi
	if "$T/joymouse_tests" >"$T/out" 2>&1; then
		tail -n 3 "$T/out"; result native PASS "joyMouse: $(grep -o '[0-9]* tests\? from [0-9]* test suites\? ran' "$T/out" | head -1)"
	else
		cat "$T/out"; result native FAIL "joyMouse: tests"
	fi
}

# Python generators that have a --check mode: the generated files are up to date.
check_python() {
	section python
	git ls-files '*.py' | xargs -r grep -l -e '"--check"' -e "'--check'" >"$T/py"
	if [ ! -s "$T/py" ]; then result python SKIP "no generator with --check"; return; fi
	while read -r f; do
		echo "--- $f --check"
		if python3 "$f" --check >"$T/out" 2>&1; then
			result python PASS "$f"
		else
			cat "$T/out"; result python FAIL "$f"
		fi
	done <"$T/py"
}

# XML under res/ and overlay*/ must parse.
check_xml() {
	section xml
	git ls-files '*.xml' | grep -E '(^|/)res/|^overlay[^/]*/' >"$T/xml"
	if python3 - "$T/xml" >"$T/out" 2>&1 <<'EOF'
import sys, xml.dom.minidom
bad = 0
files = open(sys.argv[1]).read().split()
for f in files:
    try:
        xml.dom.minidom.parse(f)
    except Exception as e:
        bad += 1
        print(f"{f}: {e}")
print(f"{len(files)} files, {bad} bad")
sys.exit(1 if bad else 0)
EOF
	then
		result xml PASS "$(tail -n1 "$T/out")"
	else
		cat "$T/out"; result xml FAIL "$(tail -n1 "$T/out")"
	fi
}

# Commits in RANGE: author and committer are IDENT, no AI attribution in the message.
# (The [l] and [n] only keep the words themselves out of this file for the pre-commit hook.)
check_commits() {
	section "commits $RANGE"
	if ! git rev-list "$RANGE" >"$T/revs" 2>"$T/out"; then
		cat "$T/out"; result commits FAIL "bad range $RANGE"; return
	fi
	if [ ! -s "$T/revs" ]; then result commits SKIP "no commits in $RANGE"; return; fi
	bad=0
	while read -r c; do
		who=$(git log -1 --format='%an <%ae>|%cn <%ce>' "$c")
		subj=$(git log -1 --format='%h %s' "$c")
		if [ "${who%%|*}" != "$IDENT" ]; then echo "author ${who%%|*}: $subj"; bad=$((bad + 1)); fi
		if [ "${who#*|}" != "$IDENT" ]; then echo "committer ${who#*|}: $subj"; bad=$((bad + 1)); fi
		if git log -1 --format=%B "$c" |
				grep -inE '^[[:space:]]*co-authored-by:|\bc[l]aude\b|\ba[n]thropic\b|generated with \[?c[l]aude|🤖' >"$T/ai"; then
			echo "AI attribution: $subj"; sed 's/^/    /' "$T/ai"; bad=$((bad + 1))
		fi
	done <"$T/revs"
	n=$(wc -l <"$T/revs")
	if [ "$bad" = 0 ]; then result commits PASS "$n commits"; else result commits FAIL "$bad problems in $n commits"; fi
}

# size of <rev>:<path>; LFS pointers give the real size without downloading
blob_size() {
	git cat-file -e "$1:$2" 2>/dev/null || { echo -; return; }
	if [ "$(git cat-file -s "$1:$2")" -lt 300 ] &&
			git cat-file -p "$1:$2" | head -n1 | grep -q '^version https://git-lfs'; then
		git cat-file -p "$1:$2" | sed -n 's/^size //p'
	else
		git cat-file -s "$1:$2"
	fi
}

# Report only, never fails: prebuilt APK sizes, BASE vs HEAD.
check_apksize() {
	section "apksize $BASE -> HEAD"
	if ! git rev-parse -q --verify "$BASE^{commit}" >/dev/null; then
		result apksize SKIP "no base commit $BASE"; return
	fi
	{ git ls-tree -r --name-only "$BASE"; git ls-tree -r --name-only HEAD; } | grep '\.apk$' | sort -u >"$T/apks"
	changed=0 total_old=0 total_new=0
	{
		echo "| APK | before | after | change |"
		echo "|---|---:|---:|---:|"
		while read -r f; do
			o=$(blob_size "$BASE" "$f"); n=$(blob_size HEAD "$f")
			[ "$o" = - ] || total_old=$((total_old + o))
			[ "$n" = - ] || total_new=$((total_new + n))
			[ "$o" = "$n" ] && continue
			changed=$((changed + 1))
			d=$(( ${n/-/0} - ${o/-/0} ))
			printf '| %s | %s | %s | %+d KiB |\n' "$f" "$o" "$n" $((d / 1024))
		done <"$T/apks"
		printf '| all %d APKs | %d | %d | %+d KiB |\n' "$(wc -l <"$T/apks")" "$total_old" "$total_new" \
			$(((total_new - total_old) / 1024))
	} >"$T/size"
	cat "$T/size"
	[ -n "${GITHUB_STEP_SUMMARY:-}" ] && { echo "### Prebuilt APK sizes ($BASE -> HEAD)"; cat "$T/size"; } >>"$GITHUB_STEP_SUMMARY"
	result apksize PASS "$changed changed (report only)"
}

checks=${*:-shellcheck tests native python xml commits apksize}
for c in $checks; do
	case $c in
		shellcheck|tests|native|python|xml|commits|apksize) "check_$c" ;;
		*) echo "unknown check $c"; exit 2 ;;
	esac
done

printf '\n=== summary ===\n%s' "$RESULTS"
[ "$fail_total" = 0 ]
