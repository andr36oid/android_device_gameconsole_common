#!/bin/bash
# Builds free-games.tar.gz from games.txt: downloads every game (or takes it
# from the cache folder), checks its sha256, puts it into its ROM folder and
# writes free-games-licenses.txt next to the folders (and as NOTICE.txt).
# The archive is reproducible: same input, same bytes.
#
#   ./build-free-games.sh [cache dir]     (default: /andr36oid/work/tmp/o2-free-games/cache)
#   ./build-free-games.sh --check         (rebuild in a temp dir, compare with the committed archive)
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
GAMES=$HERE/games.txt
OUT=$HERE/free-games.tar.gz
CHECK=
if [ "${1:-}" = --check ]; then
	CHECK=1
	shift
fi
CACHE=${1:-/andr36oid/work/tmp/o2-free-games/cache}
mkdir -p "$CACHE"

STAGE=$(mktemp -d)
if [ -z "$STAGE" ] || [ ! -d "$STAGE" ]; then echo "no temp dir" >&2; exit 1; fi
trap 'rm -rf "$STAGE"' EXIT

fetch() { # <url> <sha256> ; prints the cached file
	local url=$1 sum=$2 f
	f=$CACHE/$sum-${url##*/}
	if [ ! -f "$f" ]; then
		curl -sfL -o "$f.part" "$url"
		mv "$f.part" "$f"
	fi
	if [ "$(sha256sum "$f" | cut -d' ' -f1)" != "$sum" ]; then
		echo "sha256 mismatch: $url" >&2
		rm -f "$f"
		exit 1
	fi
	echo "$f"
}

LIC=$STAGE/free-games-licenses.txt
{
	echo "Free games that came with andr36oid"
	echo "==================================="
	echo
	echo "These games were put on this card the first time the console started."
	echo "Their authors allow anyone to share them for free. They are yours to"
	echo "keep or delete. The console never puts them back once deleted."
	echo
} >"$LIC"

while IFS='|' read -r folder name url sum member title authors licence _ where; do
	case $folder in ''|\#*) continue ;; esac
	f=$(fetch "$url" "$sum")
	mkdir -p "$STAGE/$folder"
	if [ "$member" = - ]; then
		cp "$f" "$STAGE/$folder/$name"
	else
		unzip -p "$f" "$member" >"$STAGE/$folder/$name"
	fi
	{
		echo "$folder/$name"
		echo "  Game:    $title"
		echo "  By:      $authors"
		echo "  Licence: $licence"
		echo "  From:    $where"
		echo
	} >>"$LIC"
done <"$GAMES"

{
	echo "The Doom game (doom folder) needs the PrBoom core; it is only copied"
	echo "when the console has a doom folder."
	echo
	echo "Licence texts"
	echo "============="
} >>"$LIC"
for l in "$HERE"/licenses/*.txt; do
	{
		echo
		echo "----- ${l##*/} -----"
		echo
		cat "$l"
	} >>"$LIC"
done

TMP_OUT=$STAGE.tar.gz
(cd "$STAGE" && find . -type f -printf '%P\n' | LC_ALL=C sort |
	tar --owner=0 --group=0 --numeric-owner --mode=u=rw,go=r \
		--mtime=@0 --format=gnu --no-recursion -cf - -T -) | gzip -9n >"$TMP_OUT"

if [ -n "$CHECK" ]; then
	if cmp -s "$TMP_OUT" "$OUT" && cmp -s "$LIC" "$HERE/NOTICE.txt"; then
		echo "free-games.tar.gz is up to date"
		rm -f "$TMP_OUT"
	else
		echo "free-games.tar.gz or NOTICE.txt differs from games.txt / licenses/" >&2
		rm -f "$TMP_OUT"
		exit 1
	fi
else
	mv "$TMP_OUT" "$OUT"
	# the same text for Settings > About > Legal
	cp "$LIC" "$HERE/NOTICE.txt"
	echo "wrote $OUT ($(stat -c %s "$OUT") bytes)"
	tar -tvzf "$OUT"
fi
