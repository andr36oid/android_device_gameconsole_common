#!/bin/bash
# the case bodies and checks are single-quoted on purpose (run later, in the case)
# shellcheck disable=SC2016,SC2030,SC2031,SC2034
# Host tests for seed_free_games / make_rom_folders in gameconsole-emu.sh.
# Runs the real functions against a fake EASYROMS and a fake state folder
# under mktemp -d; nothing outside that folder is written.
#
#   FreeGames/tests/run-tests.sh [toybox binary]
# With a toybox binary (the Android one built for the host), tar and df are
# toybox's, like on the console. Every case runs under bash, dash and busybox ash.
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
TOP=$(cd "$HERE/../.." && pwd)
SCRIPT=$TOP/emulators/seed/gameconsole-emu.sh
ARCHIVE=$TOP/FreeGames/free-games.tar.gz
TOYBOX=${1:-}

T=$(mktemp -d)
if [ -z "$T" ] || [ ! -d "$T" ]; then echo "no temp dir" >&2; exit 1; fi
trap 'rm -rf "$T"' EXIT

# the script's functions only: everything before its main part
sed '/^mkdir -p "\$STATE"$/,$d' "$SCRIPT" >"$T/lib.sh"
grep -q '^seed_free_games()' "$T/lib.sh" || { echo "seed_free_games not found" >&2; exit 1; }

mkdir -p "$T/bin"
if [ -n "$TOYBOX" ]; then
	ln -s "$TOYBOX" "$T/bin/tar"
	ln -s "$TOYBOX" "$T/bin/df"
fi

# folder lists: without the doom folder (today) and with it (O2-31 cores)
sed -n '/^[^#]/p' "$TOP/emulators/seed/rom-folders.txt" | grep -v '^doom ' >"$T/folders-plain.txt"
{ cat "$T/folders-plain.txt"; echo "doom              Doom - RetroArch (PrBoom)"; } >"$T/folders-doom.txt"

# case body runs in a child shell; $1 = case name. Variables for the script:
# fake root $R (EASYROMS), $S (state), the archive, the folder list.
run_case() { # <shell> <name> <folder list> <archive> <prep> <check>
	local sh=$1 name=$2 folders=$3 archive=$4 prep=$5 check=$6
	local R=$T/case/root S=$T/case/state
	[ -n "$T" ] || exit 1
	rm -rf "$T/case"
	mkdir -p "$R/ABCD-1234" "$S"
	R=$R/ABCD-1234
	cat >"$T/case.sh" <<CASE
PATH=$T/bin:\$PATH
. "$T/lib.sh"
STATE="$S"
ROM_FOLDERS="$folders"
FREE_GAMES="$archive"
EASYROMS="$R"
NOROMS=
log() { echo "LOG: \$*" >>"$T/case/log"; }
$prep
CASE
	(cd "$T" && "$sh" "$T/case.sh") >"$T/case/out" 2>&1
	R_="$R" S_="$S" L_="$T/case/log"
	if (R=$R_ S=$S_ L=$L_ A=$ARCHIVE; eval "$check") >"$T/case/check" 2>&1; then
		echo "ok   [$sh] $name"
		PASS=$((PASS + 1))
	else
		echo "FAIL [$sh] $name"
		sed 's/^/     /' "$T/case/check" "$T/case/out" "$T/case/log" 2>/dev/null | head -30
		FAIL=$((FAIL + 1))
	fi
}

# helpers for the checks
same() { tar -xzOf "$A" "$1" | cmp -s - "$R/$1"; }
games_plain='gb/2048.gb|gb/Libbet and the Magic Floor.gb|gbc/Tobu Tobu Girl Deluxe.gb|gbc/uCity.gbc|nes/RHDE - Furniture Fight.nes|nes/Thwaite.nes'
all_same_plain() {
	local IFS='|' g
	for g in $games_plain; do same "$g" || { echo "differs or missing: $g"; return 1; }; done
	same free-games-licenses.txt || { echo "licences missing"; return 1; }
}
no_part() { [ -z "$(find "$R" -name '*.part')" ] || { echo "left .part files"; return 1; }; }

PASS=0 FAIL=0
head -c 5000 /dev/urandom >"$T/bad.tar.gz"
SHELLS="bash dash"
command -v busybox >/dev/null && SHELLS="$SHELLS busybox_ash"
printf '#!/bin/sh\nexec busybox ash "$@"\n' >"$T/bin/busybox_ash"
chmod +x "$T/bin/busybox_ash"
export PATH="$T/bin:$PATH"

for sh in $SHELLS; do
	run_case "$sh" "fresh card: games in their folders, licences, no doom folder = no Freedoom" \
		"$T/folders-plain.txt" "$ARCHIVE" 'make_rom_folders' \
		'all_same_plain && [ ! -e "$R/doom" ] && [ -f "$R/README-andr36oid.txt" ] &&
		 [ -e "$S/rom-folders.ABCD-1234" ] && grep -q "no doom/ folder on EASYROMS, left out freedoom1.wad" "$L" &&
		 grep -q "copied 7 files" "$L" && no_part && [ ! -e "$S/free-games.list" ]'

	run_case "$sh" "fresh card with a doom folder: Freedoom too" \
		"$T/folders-doom.txt" "$ARCHIVE" 'make_rom_folders' \
		'all_same_plain && same doom/freedoom1.wad && grep -q "copied 8 files" "$L" && no_part'

	run_case "$sh" "card with an ArkOS layout: no games, nothing touched" \
		"$T/folders-plain.txt" "$ARCHIVE" 'mkdir "$EASYROMS/nes"; echo mine >"$EASYROMS/nes/game.nes"; make_rom_folders' \
		'[ "$(find "$R" | wc -l)" = 3 ] && [ "$(cat "$R/nes/game.nes")" = mine ] && [ ! -e "$R/free-games-licenses.txt" ] &&
		 [ -e "$S/rom-folders.ABCD-1234" ]'

	run_case "$sh" "second start: a deleted game stays deleted" \
		"$T/folders-plain.txt" "$ARCHIVE" 'make_rom_folders; rm "$EASYROMS/gb/2048.gb" "$EASYROMS/free-games-licenses.txt"; make_rom_folders' \
		'[ ! -e "$R/gb/2048.gb" ] && [ ! -e "$R/free-games-licenses.txt" ] && same gbc/uCity.gbc && [ "$(grep -c "copied" "$L")" = 1 ]'

	run_case "$sh" "never overwrites a file that is there" \
		"$T/folders-plain.txt" "$ARCHIVE" 'mkdir -p "$EASYROMS/gb"; while read -r n _; do case $n in ""|\#*) continue;; esac; mkdir -p "$EASYROMS/$n"; done <"$ROM_FOLDERS"; echo mine >"$EASYROMS/gb/2048.gb"; echo mine >"$EASYROMS/free-games-licenses.txt"; seed_free_games "$EASYROMS"' \
		'[ "$(cat "$R/gb/2048.gb")" = mine ] && [ "$(cat "$R/free-games-licenses.txt")" = mine ] && same gb/"Libbet and the Magic Floor.gb" && grep -q "copied 5 files" "$L"'

	run_case "$sh" "no archive on /system (GAMECONSOLE_FREE_GAMES=false): folders only" \
		"$T/folders-plain.txt" "$T/none.tar.gz" 'make_rom_folders' \
		'[ -d "$R/gb" ] && [ -z "$(find "$R" -type f ! -name README-andr36oid.txt)" ] && ! grep -q copied "$L"'

	run_case "$sh" "broken archive: logged, no half files" \
		"$T/folders-plain.txt" "$T/bad.tar.gz" 'make_rom_folders' \
		'[ -d "$R/gb" ] && grep -q "can.t read" "$L" && [ -z "$(find "$R" -type f ! -name README-andr36oid.txt)" ] && no_part'

	run_case "$sh" "card too full: no games" \
		"$T/folders-plain.txt" "$ARCHIVE" 'df() { printf "Filesystem 1K-blocks Used Available Use%% Mounted on\n/dev/x 100 90 1000 90%% /x\n"; }; make_rom_folders' \
		'[ -d "$R/gb" ] && grep -q "only 1000 KB free" "$L" && [ -z "$(find "$R" -type f ! -name README-andr36oid.txt)" ]'

	run_case "$sh" "card made with .noroms: nothing" \
		"$T/folders-plain.txt" "$ARCHIVE" 'NOROMS=1; make_rom_folders' \
		'[ "$(find "$R" | wc -l)" = 1 ]'

	run_case "$sh" "EASYROMS not mounted: nothing, no crash" \
		"$T/folders-plain.txt" "$ARCHIVE" 'EASYROMS=; make_rom_folders; seed_free_games ""' \
		'[ "$(find "$R" | wc -l)" = 1 ] && ! grep -q copied "$L"'
done
echo "passed $PASS, failed $FAIL"
[ "$FAIL" = 0 ]
