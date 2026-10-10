#!/bin/bash
# the case bodies and checks are single-quoted on purpose (run later, in the case)
# shellcheck disable=SC2016
# Host test for rom_nomedia (and make_rom_folders before it) in
# seed/gameconsole-emu.sh. Runs the real functions against a fake EASYROMS and a
# fake state folder under mktemp -d; nothing outside that folder is written.
# Every case runs under bash, dash, busybox ash and mksh (Android's shell) when
# they are there.
#
#   emulators/tests/rom-nomedia-test.sh [mksh binary]
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
EMU=${HERE%/*}
SCRIPT=$EMU/seed/gameconsole-emu.sh
MKSH=${1:-$(command -v mksh || true)}

T=$(mktemp -d)
if [ -z "$T" ] || [ ! -d "$T" ]; then echo "no temp dir" >&2; exit 1; fi
trap 'rm -rf "$T"' EXIT

# the script's functions only: everything before its main part
sed '/^mkdir -p "\$STATE"$/,$d' "$SCRIPT" >"$T/lib.sh"
grep -q '^rom_nomedia()' "$T/lib.sh" || { echo "rom_nomedia not found" >&2; exit 1; }
sed -n '/^[^#]/p' "$EMU/seed/rom-folders.txt" >"$T/folders.txt"

fails=0
# run_case <shell> <name> <prep> <boots> <check>
# prep runs in the test shell with $R (EASYROMS) and $S (state); then the
# script's two steps run <boots> times, each time in a new shell like at boot;
# check runs with $R, $S and $L (the log).
run_case() {
	local sh=$1 name=$2 prep=$3 boots=$4 check=$5 i=0
	local R=$T/case/media_rw/ABCD-1234 S=$T/case/state L=$T/case/log
	case $T in /tmp/*|/dev/shm/*|/var/tmp/*) ;; *) echo "odd temp dir $T" >&2; exit 1 ;; esac
	rm -rf "$T/case"
	mkdir -p "$R" "$S"
	: >"$L"
	(eval "$prep") || { echo "FAIL [$sh] $name: prep"; fails=$((fails + 1)); return; }
	cat >"$T/case.sh" <<CASE
. "$T/lib.sh"
STATE="$S"
ROM_FOLDERS="$T/folders.txt"
EASYROMS="\${EASYROMS-$R}"
NOROMS=\${NOROMS-}
log() { echo "LOG: \$*" >>"$L"; }
make_rom_folders
rom_nomedia
CASE
	while [ $i -lt "$boots" ]; do
		(cd "$T" && eval "$sh \"$T/case.sh\"") >>"$T/case/out" 2>&1
		i=$((i + 1))
	done
	if (eval "$check") >"$T/case/check" 2>&1; then
		echo "ok   [$sh] $name"
	else
		echo "FAIL [$sh] $name"
		sed 's/^/     /' "$T/case/out" "$T/case/check" "$L"
		fails=$((fails + 1))
	fi
}

# shellcheck disable=SC2317 # used in the checks (eval)
names() { sed 's/ .*//' "$T/folders.txt"; }

cases() {
	local sh=$1

	run_case "$sh" "fresh card: folders made, each with an empty .nomedia" '' 1 '
		n=0
		for f in $(names); do
			[ -f "$R/$f/.nomedia" ] && [ ! -s "$R/$f/.nomedia" ] || { echo "no .nomedia in $f"; exit 1; }
			grep -qxF "$f" "$S/rom-nomedia.ABCD-1234" || { echo "$f not noted"; exit 1; }
			n=$((n + 1))
		done
		[ "$(wc -l <"$S/rom-nomedia.ABCD-1234")" -eq $n ] && [ -f "$R/README-andr36oid.txt" ] &&
			[ ! -e "$R/.nomedia" ] && [ "$(cat "$L")" = "LOG: made the ROM folders on EASYROMS (ABCD-1234)" ]'

	run_case "$sh" "ArkOS card with games: only the listed folders that exist, games untouched" '
		mkdir -p "$R/nes/images" "$R/gba" "$R/ports" "$R/easyrpg/Game/Music"
		echo rom >"$R/nes/Game (USA).nes"; echo png >"$R/nes/images/Game.png"
		echo ogg >"$R/easyrpg/Game/Music/theme.ogg"
		(cd "$R" && find . -type f -print0 | sort -z | xargs -0 md5sum) >"$T/before"' 1 '
		[ -f "$R/nes/.nomedia" ] && [ -f "$R/gba/.nomedia" ] && [ ! -e "$R/ports/.nomedia" ] &&
			[ ! -e "$R/nes/images/.nomedia" ] && [ ! -e "$R/snes" ] && ! grep -q "couldn" "$L" &&
			(cd "$R" && find . -type f ! -name .nomedia -print0 | sort -z | xargs -0 md5sum) | cmp -s - "$T/before" &&
			if [ -d "$R/easyrpg" ] && grep -qx "easyrpg .*" "$T/folders.txt"; then [ -f "$R/easyrpg/.nomedia" ]; else true; fi &&
			[ "$(sort "$S/rom-nomedia.ABCD-1234" | tr "\n" " ")" = "$(for f in $(names); do [ -d "$R/$f" ] && echo "$f"; done | sort | tr "\n" " ")" ]'

	run_case "$sh" "second start: nothing written again" '' 1 '
		touch -d "2001-01-01" "$R/nes/.nomedia" "$S/rom-nomedia.ABCD-1234"
		cp "$S/rom-nomedia.ABCD-1234" "$T/noted"
		cd "$T" && '"$sh"' "$T/case.sh" &&
		[ "$(stat -c %Y "$R/nes/.nomedia")" = "$(date -d 2001-01-01 +%s)" ] &&
		cmp -s "$S/rom-nomedia.ABCD-1234" "$T/noted"'

	run_case "$sh" "a .nomedia the user deleted stays deleted" '' 1 '
		rm "$R/nes/.nomedia"
		cd "$T" && '"$sh"' "$T/case.sh" && [ ! -e "$R/nes/.nomedia" ] && [ -f "$R/snes/.nomedia" ]'

	run_case "$sh" "folder copied in later gets one at the next start" '
		mkdir -p "$R/nes"' 1 '
		[ ! -e "$R/gba" ] && mkdir "$R/gba" && echo g >"$R/gba/x.gba" &&
		cd "$T" && '"$sh"' "$T/case.sh" && [ -f "$R/gba/.nomedia" ] && [ -f "$R/gba/x.gba" ] &&
		grep -qxF gba "$S/rom-nomedia.ABCD-1234"'

	run_case "$sh" "the user's own .nomedia is left as it is" '
		mkdir -p "$R/nes"; echo mine >"$R/nes/.nomedia"' 1 '
		[ "$(cat "$R/nes/.nomedia")" = mine ]'

	run_case "$sh" "EASYROMS not mounted or .noroms: nothing" '' 1 '
		rm -rf "$T/case/state"/* "$R"/*
		EASYROMS= '"$sh"' "$T/case.sh"
		NOROMS=1 EASYROMS= '"$sh"' "$T/case.sh"
		[ -z "$(ls "$S")" ] && [ -z "$(ls "$R")" ]'

	run_case "$sh" "a folder that can't take it is tried again next start" '
		mkdir -p "$R/nes" "$R/snes"; ln -s "$T/case/missing/x" "$R/nes/.nomedia"' 1 '
		grep -q "couldn.t put .nomedia into nes/" "$L" && ! grep -qxF nes "$S/rom-nomedia.ABCD-1234" &&
		[ -f "$R/snes/.nomedia" ] && rm "$R/nes/.nomedia" && cd "$T" && '"$sh"' "$T/case.sh" &&
		[ -f "$R/nes/.nomedia" ] && grep -qxF nes "$S/rom-nomedia.ABCD-1234"'
}

for sh in bash dash "busybox ash" ${MKSH:+"$MKSH"}; do
	# shellcheck disable=SC2086 # "busybox ash" is two words
	command -v ${sh%% *} >/dev/null || { echo "skip $sh"; continue; }
	cases "$sh"
done

if [ $fails -eq 0 ]; then
	echo "all passed"
	exit 0
fi
echo "$fails failed"
exit 1
