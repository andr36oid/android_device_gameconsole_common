#!/system/bin/sh
# shellcheck shell=ksh
#
# andr36oid-bioscheck: the root half of Settings > BIOS check. The app runs as the
# system user, which can't read EASYROMS or RetroArch's folders, so the file work is
# done here and the app only decides what to do.
#
#   scan   finds EASYROMS and its bios folder (makes "bios" if there is none),
#          RetroArch's system folder (from retroarch.cfg, else RetroArch's default)
#          and the installed cores, and lists the files there with their MD5
#   apply  runs the operations the app wrote to $DIR/ops, then scans again:
#            copy<TAB><from><TAB><to><TAB><md5>     only if <to> is missing
#            replace<TAB><from><TAB><to><TAB><md5>  also over a different file
#            rename<TAB><from><TAB><to>             only if <to> is missing
#          A copy never overwrites a different file: that's "kept" and the app
#          asks before it sends a replace.
#
# The app writes $DIR/request (action=scan|apply) and starts us with ctl.start.
# $DIR/status is one line: "running <action>", "done" or "error <code>".
# $DIR/scan, one TAB separated record per line:
#   easyroms <path> / bios <path> / system <cfg|default> <path> / core <name> /
#   file <md5 or -> <size> <path>
# $DIR/result, one line per operation: copied|same|kept|renamed|exists|failed <path>
#
# The paths are the ones root sees: /mnt/media_rw/<uuid> for EASYROMS and
# /data/media/0 for the internal storage. The BIOSCHECK_* variables are for testing
# on a PC.

DIR=${BIOSCHECK_DIR:-/data/misc/andr36oid-bioscheck}
MEDIA=${BIOSCHECK_MEDIA:-/data/media/0}
RA_DATA=${BIOSCHECK_RA_DATA:-/data/user/0/com.retroarch}
EASYROMS_ID="public:179,7"
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

md5_of() {
	md5sum <"$1" 2>/dev/null | cut -d' ' -f1
}

size_of() {
	stat -c %s "$1" 2>/dev/null || wc -c <"$1" | tr -d ' '
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
		if [ "$(echo "$n" | tr 'A-Z' 'a-z')" = bios ]; then
			echo "$1/$n"
			return 0
		fi
	done
	mkdir -p "$1/bios" 2>/dev/null && echo "$1/bios"
}

# lower <path> ; a path as apps see it, as root sees it; nothing if we can't tell
lower() {
	case $1 in
	/storage/emulated/0|/storage/emulated/0/*) echo "$MEDIA${1#/storage/emulated/0}" ;;
	/storage/self/primary|/storage/self/primary/*) echo "$MEDIA${1#/storage/self/primary}" ;;
	/sdcard|/sdcard/*) echo "$MEDIA${1#/sdcard}" ;;
	/mnt/sdcard|/mnt/sdcard/*) echo "$MEDIA${1#/mnt/sdcard}" ;;
	/storage/*/*) echo "/mnt/media_rw/${1#/storage/}" ;;
	/data/*|/mnt/media_rw/*) echo "$1" ;;
	esac
}

# RetroArch's system_directory setting, if it is set to something
cfg_system() {
	local cfg v
	for cfg in "$MEDIA/Android/data/com.retroarch/files/retroarch.cfg" "$RA_DATA/retroarch.cfg"; do
		[ -f "$cfg" ] || continue
		v=$(sed -n 's/^[ 	]*system_directory[ 	]*=[ 	]*"\(.*\)"[ 	]*$/\1/p' "$cfg" | tail -n 1)
		case $v in ''|default) return 0 ;; esac
		lower "$v"
		return 0
	done
}

# list_files <dir> <depth>
list_files() {
	local f size sum
	[ -d "$1" ] || return 0
	find "$1" -maxdepth "$2" -type f 2>/dev/null | while IFS= read -r f; do
		case $f in *"$TAB"*) continue ;; esac
		size=$(size_of "$f")
		sum=-
		[ -n "$size" ] && [ "$size" -le $MAX_BYTES ] && sum=$(md5_of "$f")
		echo "file$TAB${sum:--}$TAB${size:-0}$TAB$f"
	done
}

scan() {
	local root bios cfg def core
	{
		root=$(easyroms)
		if [ -n "$root" ]; then
			echo "easyroms$TAB$root"
			bios=$(bios_dir "$root")
			[ -n "$bios" ] && echo "bios$TAB$bios"
		fi
		cfg=$(cfg_system)
		def=$MEDIA/RetroArch/system
		[ -n "$cfg" ] && [ "$cfg" != "$def" ] && echo "system${TAB}cfg$TAB$cfg"
		echo "system${TAB}default$TAB$def"
		for core in "$RA_DATA"/cores/*_libretro_android.so; do
			[ -f "$core" ] || continue
			core=${core##*/}
			echo "core$TAB${core%_libretro_android.so}"
		done
		[ -n "$bios" ] && list_files "$bios" 2
		[ -n "$cfg" ] && [ "$cfg" != "$def" ] && list_files "$cfg" 1
		list_files "$def" 1
		# FBNeo also looks in system/fbneo
		[ -d "$def/fbneo" ] && list_files "$def/fbneo" 1
	} >"$DIR/scan.tmp"
	own "$DIR/scan.tmp"
	mv -f "$DIR/scan.tmp" "$DIR/scan"
}

# allowed <path> ; only EASYROMS, the internal storage and RetroArch's own folder
allowed() {
	case $1 in
	*/../*|*/..|*/./*) return 1 ;;
	"$MEDIA"/*|"$RA_DATA"/*) return 0 ;;
	esac
	if [ -n "${BIOSCHECK_EASYROMS+set}" ]; then
		case $1 in "$BIOSCHECK_EASYROMS"/*) return 0 ;; esac
		return 1
	fi
	case $1 in /mnt/media_rw/*/*) return 0 ;; esac
	return 1
}

# mkdirs <dir> ; makes it with the owner of the nearest folder that exists, so
# RetroArch can write there too (no-op for owners on exFAT)
mkdirs() {
	local top=$1 parent owner
	[ -d "$1" ] && return 0
	parent=${1%/*}
	while [ -n "$parent" ] && [ ! -d "$parent" ]; do
		top=$parent
		parent=${parent%/*}
	done
	mkdir -p "$1" || return 1
	[ -n "$BIOSCHECK_DIR" ] && return 0
	owner=$(stat -c %u:%g "${parent:-/}")
	chown -R "$owner" "$top" 2>/dev/null
	chmod 0775 "$top" 2>/dev/null
	restorecon -R "$top" 2>/dev/null
	return 0
}

# like_parent <file> ; same owner as its folder
like_parent() {
	[ -n "$BIOSCHECK_DIR" ] && return 0
	chown "$(stat -c %u:%g "${1%/*}")" "$1" 2>/dev/null
	chmod 0664 "$1" 2>/dev/null
	restorecon "$1" 2>/dev/null
	return 0
}

# copy <replace:0|1> <from> <to> <md5>
copy() {
	local replace=$1 from=$2 to=$3 sum=$4 have
	if [ ! -f "$from" ] || ! allowed "$from" || ! allowed "$to"; then
		echo "failed $to"
		return
	fi
	if [ -e "$to" ]; then
		have=$(md5_of "$to")
		if [ "$have" = "$(md5_of "$from")" ]; then
			echo "same $to"
			return
		fi
		if [ "$replace" != 1 ]; then
			echo "kept $to"
			return
		fi
	fi
	if mkdirs "${to%/*}" && cp "$from" "$to.bioscheck" &&
		[ "$(md5_of "$to.bioscheck")" = "$(md5_of "$from")" ] &&
		{ [ "$sum" = - ] || [ "$(md5_of "$to.bioscheck")" = "$sum" ]; } &&
		mv -f "$to.bioscheck" "$to"; then
		like_parent "$to"
		log "copied $from to $to"
		echo "copied $to"
	else
		rm -f "$to.bioscheck"
		echo "failed $to"
	fi
}

rename() {
	local from=$1 to=$2
	if [ ! -f "$from" ] || ! allowed "$from" || ! allowed "$to"; then
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

apply() {
	local op a b c
	[ -f "$DIR/ops" ] || return 0
	while IFS="$TAB" read -r op a b c; do
		case $op in
		copy) copy 0 "$a" "$b" "${c:--}" ;;
		replace) copy 1 "$a" "$b" "${c:--}" ;;
		rename) rename "$a" "$b" ;;
		esac
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
	status done
	;;
apply)
	status "running apply"
	apply
	scan
	status done
	;;
*)
	status "error bad_request"
	exit 1
	;;
esac
exit 0
