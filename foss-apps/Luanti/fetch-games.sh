#!/usr/bin/env bash
#
# Downloads the Luanti games pinned in games.lock into firstrun/luanti/<game>.zip,
# checks their sha256 and writes firstrun/luanti/NOTICE-<game>.txt.
#
# usage: fetch-games.sh

set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT=$HERE/firstrun/luanti

die() { echo "error: $*" >&2; exit 1; }

while read -r game release sha256 license source; do
	case $game in ''|\#*) continue ;; esac
	zip=$OUT/$game.zip
	url=https://content.luanti.org/packages/${release%/*}/releases/${release##*/}/download/
	if [ -f "$zip" ] && echo "$sha256  $zip" | sha256sum -c - >/dev/null 2>&1; then
		echo ">> $game already up to date"
	else
		echo ">> $game"
		curl -fsSL -o "$zip.part" "$url" || { rm -f "$zip.part"; die "$game: download failed"; }
		echo "$sha256  $zip.part" | sha256sum -c - >/dev/null || { rm -f "$zip.part"; die "$game: checksum mismatch"; }
		mv "$zip.part" "$zip"
	fi
	{
		echo "Luanti game $game ($license)"
		echo
		echo "Shipped unmodified as published on ContentDB (sha256 $sha256)."
		echo "Download: $url"
		echo "Source: $source"
		for f in $(unzip -Z1 "$zip" | grep -iE "^$game/README[^/]*$|^$game/(.*/)?(LICENSE|COPYING)[^/]*$"); do
			echo
			echo "================================================================================"
			echo "$f"
			echo
			# a few license files are Latin-1; the notice is UTF-8
			if unzip -p "$zip" "$f" | iconv -f UTF-8 -t UTF-8 >/dev/null 2>&1; then
				unzip -p "$zip" "$f"
			else
				unzip -p "$zip" "$f" | iconv -f ISO-8859-1 -t UTF-8
			fi
		done
	} >"$OUT/NOTICE-$game.txt.new"
	mv "$OUT/NOTICE-$game.txt.new" "$OUT/NOTICE-$game.txt"
done <"$HERE/games.lock"
