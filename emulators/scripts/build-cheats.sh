#!/usr/bin/env bash
#
# Packs the RetroArch cheat files (.cht) from libretro-database, for the
# systems we ship cores for, into emulators/libretro/cheats.tar.gz, and writes
# emulators/libretro/NOTICE-cheats.txt. The gameconsole-emu service unpacks the
# archive into RetroArch's cheat folder, so the Cheats menu works offline.
#
# usage: build-cheats.sh [--work DIR]
#
# To update: change COMMIT (a libretro-database commit) and run this again.
# The archive is reproducible: same commit, same bytes.

set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUT=$HERE/libretro
REPO=https://github.com/libretro/libretro-database
COMMIT=d5bae90b22018ce3c9c5a8eff4141a4768c8dd5f
WORK=${TMPDIR:-/tmp}/gameconsole-cheats

# libretro-database folder names (cht/<name>), one per system with a core that
# can use them: the core takes the codes (retro_cheat_set) or the files use
# RetroArch's own memory handler and the core shows its RAM. Left out: systems
# without a core here (DS, PSP (PPSSPP app), Dreamcast, Saturn, Jaguar, ...),
# Atari 5200 (a5200 shows no RAM) and Virtual Boy (Beetle VB ignores codes).
SYSTEMS=(
	"Nintendo - Nintendo Entertainment System"
	"Nintendo - Family Computer Disk System"
	"Nintendo - Super Nintendo Entertainment System"
	"Nintendo - Satellaview"
	"Nintendo - Game Boy"
	"Nintendo - Game Boy Color"
	"Nintendo - Game Boy Advance"
	"Nintendo - Nintendo 64"
	"Nintendo - Nintendo 64 (Unreleased)"
	"Sega - Mega Drive - Genesis"
	"Sega - Master System - Mark III"
	"Sega - Game Gear"
	"Sega - SG-1000"
	"Sega - Mega-CD - Sega CD"
	"Sega - 32X"
	"Sony - PlayStation"
	"NEC - PC Engine - TurboGrafx 16"
	"NEC - PC Engine CD - TurboGrafx-CD"
	"NEC - PC Engine SuperGrafx"
	"SNK - Neo Geo Pocket"
	"SNK - Neo Geo Pocket Color"
	"Bandai - WonderSwan"
	"Bandai - WonderSwan Color"
	"Atari - 2600"
	"Atari - 7800"
	"Atari - Lynx"
	"Coleco - ColecoVision"
	"Mattel - Intellivision"
	"Microsoft - MSX"
	"Microsoft - MSX2"
	"Sinclair - ZX Spectrum +3"
	"DOS"
	"PrBoom"
	"FBNeo - Arcade Games"
)

while [ $# -gt 0 ]; do
	case $1 in
		--work) WORK=$2; shift 2 ;;
		-h|--help) sed -n '2,11p' "$0"; exit 0 ;;
		*) echo "unknown option: $1" >&2; exit 1 ;;
	esac
done

[ -n "$WORK" ] || exit 1
rm -rf "$WORK"
mkdir -p "$WORK"
git -C "$WORK" init -q db
git -C "$WORK/db" remote add origin "$REPO"
git -C "$WORK/db" config core.sparseCheckout true
printf '/cht/\n/LICENSE\n' >"$WORK/db/.git/info/sparse-checkout"
git -C "$WORK/db" fetch -q --depth 1 --filter=blob:none origin "$COMMIT"
git -C "$WORK/db" checkout -q FETCH_HEAD

mkdir -p "$WORK/stage"
for s in "${SYSTEMS[@]}"; do
	[ -d "$WORK/db/cht/$s" ] || { echo "missing in libretro-database: $s" >&2; exit 1; }
	mkdir -p "$WORK/stage/$s"
	# only the cheat files (a few folders hold stray .xml or README files)
	find "$WORK/db/cht/$s" -maxdepth 1 -type f -name '*.cht' -exec cp -t "$WORK/stage/$s" {} +
done

# fixed names, owner, modes and times, so the same commit gives the same bytes
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@0 \
	--mode='u=rwX,g=rwX,o=' --format=gnu -C "$WORK/stage" -cf - . |
	gzip -9n >"$OUT/cheats.tar.gz"

{
	cat <<EOF
Cheat files (.cht) for RetroArch, from libretro-database
  $REPO
  commit $COMMIT, folder cht/

The cheat codes are collected by the libretro community from many public
sources and from users (see the repository's README). libretro-database is
licensed under the Creative Commons Attribution-ShareAlike 4.0 International
license, reproduced below. The files are unchanged; only the folders of the
systems listed here are included:

EOF
	for s in "${SYSTEMS[@]}"; do
		printf '  %s (%s files)\n' "$s" "$(find "$WORK/stage/$s" -type f | wc -l)"
	done
	echo
	echo "----------------------------------------------------------------------"
	echo
	cat "$WORK/db/LICENSE"
} >"$OUT/NOTICE-cheats.txt"

echo "cheats.tar.gz: $(find "$WORK/stage" -type f | wc -l) files," \
	"$(du -sb --apparent-size "$WORK/stage" | cut -f1) bytes unpacked," \
	"$(stat -c %s "$OUT/cheats.tar.gz") bytes packed"
