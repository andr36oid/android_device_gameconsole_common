#!/system/bin/sh
# shellcheck shell=ksh
#
# andr36oid-bioscheck: the root half of Settings > Emulator files. The app runs as
# the system user, which can't read EASYROMS, so the file work is done here and the
# app decides what to do. RetroArch reads the BIOS files straight from the bios
# folder on EASYROMS (gameconsole-emu sets its system_directory), so nothing is
# ever copied.
#
#   scan    finds EASYROMS and its bios folder (any case; makes "bios" if there is
#           none) and the installed RetroArch cores, and lists the files in the
#           bios folder and its subfolders with their MD5. A card set up with
#           .noroms has no EASYROMS partition; then the bios folder is on the
#           internal storage (gameconsole-emu points RetroArch there too).
#   rename  runs the renames the app wrote to $DIR/ops, one per line:
#             <from><TAB><to>
#           both in the bios folder, <to> right in it, never over a file that
#           exists. Then scans again.
#
# The app writes $DIR/request (action=scan|rename) and starts us with ctl.start.
# $DIR/status is one line: "running <action>", "done" or "error <code>".
# $DIR/scan, one TAB separated record per line:
#   easyroms <path> or internal <path> (no EASYROMS partition) / bios <path> /
#   core <name> / file <md5 or -> <size> <path>
# No easyroms, internal or bios line: EASYROMS is there but not mounted.
# $DIR/result, one line per rename: renamed|exists|failed <path>
#
# Paths are the ones root sees: /mnt/media_rw/<uuid> for EASYROMS, /data/media/0
# for the internal storage. The BIOSCHECK_*
# variables are for testing on a PC.

DIR=${BIOSCHECK_DIR:-/data/misc/andr36oid-bioscheck}
RA_DATA=${BIOSCHECK_RA_DATA:-/data/user/0/com.retroarch}
MEDIA=${BIOSCHECK_MEDIA:-/data/media/0}
# EASYROMS is partition 7 of the boot card; with .noroms on BOOT the first start
# gives the whole card to userdata (p6) and there is no p7
EASYROMS_ID="public:179,7"
EASYROMS_PART=${BIOSCHECK_PART:-/sys/block/mmcblk0/mmcblk0p7}
# BIOS files are small; don't hash a stray game or disc image
MAX_BYTES=67108864
TAB=$(printf '\t')

log() {
	[ -x /system/bin/log ] && /system/bin/log -t andr36oid-bioscheck "$*"
	return 0
}

# own <file> ; our files in $DIR belong to the app
own() {
	[ -n "$BIOSCHECK_DIR" ] && return 0
	chown system:system "$1" 2>/dev/null
	chmod 0660 "$1" 2>/dev/null
}

status() {
	echo "$*" >"$DIR/status.tmp"
	own "$DIR/status.tmp"
	mv -f "$DIR/status.tmp" "$DIR/status"
}

req() {
	sed -n "s/^$1=//p" "$DIR/request" 2>/dev/null | head -n 1
}

# where EASYROMS is mounted, nothing if it isn't
easyroms() {
	if [ -n "${BIOSCHECK_EASYROMS+set}" ]; then
		[ -d "$BIOSCHECK_EASYROMS" ] && echo "$BIOSCHECK_EASYROMS"
		return 0
	fi
	sm list-volumes public </dev/null 2>/dev/null | while read -r id state uuid; do
		[ "$id" = "$EASYROMS_ID" ] && [ "$state" = mounted ] && [ -n "$uuid" ] &&
			[ -d "/mnt/media_rw/$uuid" ] && echo "/mnt/media_rw/$uuid"
	done | head -n 1
}

# bios_dir <easyroms> ; the bios folder in any case (BIOS, Bios), made if missing
bios_dir() {
	local d n
	for d in "$1"/*/; do
		n=${d%/}
		n=${n##*/}
		if [ "$(echo "$n" | tr '[:upper:]' '[:lower:]')" = bios ]; then
			echo "$1/$n"
			return 0
		fi
	done
	mkdir "$1/bios" 2>/dev/null || return 0
	# owner and mode like its parent, as if the user had made it (a no-op on exFAT)
	if [ -z "$BIOSCHECK_DIR" ]; then
		chown "$(stat -c %u:%g "$1")" "$1/bios" 2>/dev/null
		chmod "$(stat -c %a "$1")" "$1/bios" 2>/dev/null
		restorecon "$1/bios" 2>/dev/null
	fi
	echo "$1/bios"
}

BIOS=
find_bios() {
	local root
	if [ -e "$EASYROMS_PART" ]; then
		root=$(easyroms)
		[ -n "$root" ] || return 1
		echo "easyroms$TAB$root"
	else
		root=$MEDIA
		echo "internal$TAB$root"
	fi
	BIOS=$(bios_dir "$root")
	[ -n "$BIOS" ] || return 1
	echo "bios$TAB$BIOS"
}

scan() {
	local f size sum core
	{
		find_bios
		for core in "$RA_DATA"/cores/*_libretro_android.so; do
			[ -f "$core" ] || continue
			core=${core##*/}
			echo "core$TAB${core%_libretro_android.so}"
		done
		[ -n "$BIOS" ] && find "$BIOS" -maxdepth 2 -type f 2>/dev/null | while IFS= read -r f; do
			case $f in *"$TAB"*) continue ;; esac
			size=$(stat -c %s "$f" 2>/dev/null || wc -c <"$f" | tr -d ' ')
			sum=-
			[ -n "$size" ] && [ "$size" -le $MAX_BYTES ] &&
				sum=$(md5sum <"$f" 2>/dev/null | cut -d' ' -f1)
			echo "file$TAB${sum:--}$TAB${size:-0}$TAB$f"
		done
	} >"$DIR/scan.tmp"
	own "$DIR/scan.tmp"
	mv -f "$DIR/scan.tmp" "$DIR/scan"
}

# rename <from> <to> ; from anywhere in the bios folder to a name right in it
rename() {
	local from=$1 to=$2
	case $from/$to in
	*/../*|*/./*|*/..|*/.) echo "failed $to"; return ;;
	esac
	case $from in "$BIOS"/*) ;; *) echo "failed $to"; return ;; esac
	if [ "${to%/*}" != "$BIOS" ] || [ ! -f "$from" ]; then
		echo "failed $to"
	elif [ -e "$to" ]; then
		echo "exists $to"
	elif mv "$from" "$to"; then
		log "renamed $from to $to"
		echo "renamed $to"
	else
		echo "failed $to"
	fi
}

renames() {
	local a b
	[ -f "$DIR/ops" ] || return 0
	find_bios >/dev/null
	while IFS="$TAB" read -r a b; do
		{ [ -z "$a" ] || [ -z "$b" ]; } && continue
		if [ -z "$BIOS" ]; then
			echo "failed $b"
		else
			rename "$a" "$b"
		fi
	done <"$DIR/ops" >"$DIR/result.tmp"
	own "$DIR/result.tmp"
	mv -f "$DIR/result.tmp" "$DIR/result"
	rm -f "$DIR/ops"
	# a PC running the tests may have a lot to sync
	[ -n "$BIOSCHECK_DIR" ] || sync
}

mkdir -p "$DIR"
ACTION=$(req action)
case $ACTION in
scan)
	status "running scan"
	scan
	status "done"
	;;
rename)
	status "running rename"
	renames
	BIOS=
	scan
	status "done"
	;;
*)
	status "error bad_request"
	exit 1
	;;
esac
exit 0
