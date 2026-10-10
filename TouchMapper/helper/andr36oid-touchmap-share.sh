#!/system/bin/sh
# shellcheck shell=ksh
#
# andr36oid-touchmap-share: the root half of "Save layouts to the SD card" and "Load layouts
# from the SD card" in Settings > Touch controls. The app runs as the system user, which
# can't read or write EASYROMS, so this copies the files for it.
#
# The app writes "export" or "import" into $DIR/request and starts us with ctl.start.
#   export: every layout in $LAYOUTS goes to EASYROMS/touchmap/<package>.json
#   import: EASYROMS/touchmap/*.json are copied to $DIR/in (owned by system), same names; the
#           app reads, checks and adds them itself. Nothing here parses the files.
# We end with one line in $DIR/status:
#   done export N | done import N SKIPPED | error nocard | error none | error empty
#   | error copy | error request
#
# TOUCHMAP_* variables are for testing on a PC (tests/run-tests.sh):
#   TOUCHMAP_TEST=1, TOUCHMAP_DIR, TOUCHMAP_LAYOUTS, TOUCHMAP_EASYROMS ("" = not mounted)

DIR=${TOUCHMAP_DIR:-/data/misc/andr36oid-touchmap}
LAYOUTS=${TOUCHMAP_LAYOUTS:-/data/system/andr36oid/touchmap}
EASYROMS_ID="public:179,7"
FOLDER=touchmap
MAX_FILES=200
MAX_BYTES=65536

log() {
	[ -x /system/bin/log ] && /system/bin/log -t andr36oid-touchmap "$*"
	return 0
}

testing() {
	[ -n "$TOUCHMAP_TEST" ]
}

status() {
	echo "$*" >"$DIR/status.tmp"
	mv -f "$DIR/status.tmp" "$DIR/status"
	log "$*"
}

# The mounted EASYROMS folder, or nothing
easyroms() {
	local id state uuid
	if testing; then
		[ -n "$TOUCHMAP_EASYROMS" ] && [ -d "$TOUCHMAP_EASYROMS" ] && echo "$TOUCHMAP_EASYROMS"
		return 0
	fi
	sm list-volumes public </dev/null 2>/dev/null | while read -r id state uuid; do
		[ "$id" = "$EASYROMS_ID" ] && [ "$state" = mounted ] && [ -n "$uuid" ] &&
			[ -d "/mnt/media_rw/$uuid" ] && echo "/mnt/media_rw/$uuid"
	done
	return 0
}

# A plain layout file we are willing to copy: a sane name (no macOS "._" files, no
# spaces or slashes), a regular file, not a link, not empty and not too big.
usable() {
	local path=$1 name size
	name=${path##*/}
	case $name in
	.* | *[!A-Za-z0-9._-]*) return 1 ;;
	?*.json | ?*.JSON) ;;
	*) return 1 ;;
	esac
	[ -f "$path" ] && [ ! -L "$path" ] || return 1
	size=$(wc -c <"$path" 2>/dev/null) || return 1
	size=${size##* }
	[ -n "$size" ] && [ "$size" -gt 0 ] && [ "$size" -le "$MAX_BYTES" ]
}

do_export() {
	local card out f n=0
	card=$(easyroms)
	[ -n "$card" ] || { status "error nocard"; return; }
	out=$card/$FOLDER
	for f in "$LAYOUTS"/*.json; do
		usable "$f" || continue
		mkdir -p "$out" || { status "error copy"; return; }
		if ! cp "$f" "$out/.${f##*/}.tmp" || ! mv -f "$out/.${f##*/}.tmp" "$out/${f##*/}"; then
			rm -f "$out/.${f##*/}.tmp"
			status "error copy"
			return
		fi
		n=$((n + 1))
	done
	[ "$n" -gt 0 ] || { status "error none"; return; }
	testing || sync
	status "done export $n"
}

do_import() {
	local card in f n=0 skipped=0
	card=$(easyroms)
	[ -n "$card" ] || { status "error nocard"; return; }
	in=$DIR/in
	rm -rf "$in"
	mkdir -p "$in" || { status "error copy"; return; }
	for f in "$card/$FOLDER"/*.json "$card/$FOLDER"/*.JSON; do
		[ -e "$f" ] || [ -L "$f" ] || continue
		if [ "$n" -ge "$MAX_FILES" ] || ! usable "$f"; then
			skipped=$((skipped + 1))
			continue
		fi
		cp "$f" "$in/${f##*/}" || { status "error copy"; return; }
		n=$((n + 1))
	done
	if ! testing; then
		chown -R system:system "$in"
		chmod 0700 "$in"
		chmod 0600 "$in"/* 2>/dev/null
	fi
	[ "$n" -gt 0 ] || { status "error empty"; return; }
	status "done import $n $skipped"
}

[ -n "$DIR" ] && [ -d "$DIR" ] || exit 1
request=$(cat "$DIR/request" 2>/dev/null)
rm -f "$DIR/request"
case $request in
export) do_export ;;
import) do_import ;;
*) status "error request" ;;
esac
exit 0
