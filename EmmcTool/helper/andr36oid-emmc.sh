#!/system/bin/sh
# shellcheck shell=ksh
#
# andr36oid-emmc: the root half of Settings > System > Built-in eMMC.
#
#   info   writes what the built-in eMMC is and what is on it to $DIR/info: the chip
#          (sysfs), its partitions with their names, file system types and labels
#          (blkid) and its boot areas
#   dump   copies parts of it to image files in "<dest>/eMMC backup/<date-time>/"
#   speed  read speed: 1 MB blocks in a row and 4 KB blocks at random places
#          (andr36oid-emmcspeed, read-only, O_DIRECT); the result stays in
#          $DIR/result-speed
#
# The eMMC is the one on ff390000.dwmmc, switched on read-only by Kernel Tweaks >
# Built-in eMMC storage (the kernel refuses every write to it). This only reads it.
#
# The app writes $DIR/request (action=, items=<block names, space separated>,
# dest=<folder>, dest_name=, split_mb=<0, or the largest file a FAT32 card takes, in MB>,
# keep_free_mb=<room to leave>) and starts us with ctl.start. We write key=value
# lines to $DIR/status: state=running|done|failed|stopped, item, done_mb, total_mb,
# folder, started and now (seconds), dest_name (the request's, for the app to show) and
# on failure error=noemmc|nospace|dest|read. $DIR/stop asks a
# running dump to stop.

# The host test (tests/run-tests.sh) points these at a temp dir. init starts services
# with a clean environment, so on the console they always have the default values.
DIR=${EMMC_DIR:-/data/misc/andr36oid-emmc}
SYS=${EMMC_SYS:-/sys}
DEVDIR=${EMMC_DEVDIR:-/dev/block}
STATUS=$DIR/status
STOP=$DIR/stop
CHUNK_MB=64
SPEED_SECONDS=${EMMC_SPEED_SECONDS:-8}
EMMCSPEED=${EMMC_EMMCSPEED:-/system/bin/andr36oid-emmcspeed}
WAKELOCK=andr36oid-emmc

log() {
	/system/bin/log -t andr36oid-emmc "$*" 2>/dev/null
}

req() {
	sed -n "s/^$1=//p" "$DIR/request" 2>/dev/null | head -n 1
}

# status <key=value>... : replaces the status file
status() {
	{
		echo "action=$ACTION"
		echo "dest_name=$(req dest_name)"
		echo "folder=$FOLDER"
		echo "started=$STARTED"
		echo "now=$(date +%s)"
		for kv in "$@"; do echo "$kv"; done
	} >"$STATUS.tmp"
	chown system:system "$STATUS.tmp" 2>/dev/null
	chmod 0660 "$STATUS.tmp"
	mv -f "$STATUS.tmp" "$STATUS"
}

finish() {
	status "$@"
	cp -f "$STATUS" "$DIR/result-$ACTION"
	chown system:system "$DIR/result-$ACTION" 2>/dev/null
	chmod 0660 "$DIR/result-$ACTION"
	echo "$WAKELOCK" 2>/dev/null >"$SYS/power/wake_unlock"
	exit 0
}

# the eMMC's block name (mmcblk2), empty when it isn't there
emmc_disk() {
	for d in "$SYS"/block/mmcblk*; do
		# its boot areas (mmcblk2boot0) hang off the same controller
		case ${d##*/} in *boot*) continue ;; esac
		case $(readlink -f "$d") in */ff390000.dwmmc/*) echo "${d##*/}"; return ;; esac
	done
}

# sectors <block name> : its size in 512-byte sectors
sectors() {
	if [ -e "$SYS/block/$1/size" ]; then
		cat "$SYS/block/$1/size"
	else
		cat "$SYS/block/$DISK/$1/size" 2>/dev/null
	fi
}

# mb <sectors> : rounded up to whole MB
mb() {
	echo $((($1 + 2047) / 2048))
}

free_mb() {
	local a s
	# shellcheck disable=SC2046 # split the two numbers into $1 $2
	set -- $(stat -f -c '%a %S' "$1" 2>/dev/null)
	a=$1 s=$2
	if [ -z "$a" ] || [ -z "$s" ]; then echo 0; return; fi
	if [ "$s" -ge 1048576 ]; then
		echo $((a * (s / 1048576)))
	else
		echo $((a / (1048576 / s)))
	fi
}

do_info() {
	local f=$DIR/info.tmp dev p pn
	{
		if [ -z "$DISK" ]; then
			echo "state=none"
		else
			dev=$SYS/block/$DISK/device
			echo "state=ok"
			echo "disk=$DISK"
			echo "sectors=$(sectors "$DISK")"
			for k in name manfid oemid date fwrev hwrev rev serial cid life_time pre_eol_info; do
				[ -r "$dev/$k" ] && echo "$k=$(tr -d '\n' <"$dev/$k" 2>/dev/null)"
			done
			for p in "$SYS/block/$DISK/$DISK"p*; do
				[ -d "$p" ] || continue
				pn=${p##*/}
				# part=<block name>|<start>|<sectors>|<name>|<type>|<label>
				echo "part=$pn|$(cat "$p/start" 2>/dev/null)|$(cat "$p/size" 2>/dev/null)|$(
					sed -n 's/^PARTNAME=//p' "$p/uevent" 2>/dev/null)|$(
					blkid_tag "$pn" TYPE)|$(blkid_tag "$pn" LABEL)"
			done
			for p in "$SYS"/block/"$DISK"boot*; do
				[ -d "$p" ] && echo "boot=${p##*/}|$(cat "$p/size" 2>/dev/null)"
			done
		fi
	} >"$f"
	chown system:system "$f" 2>/dev/null
	chmod 0660 "$f"
	mv -f "$f" "$DIR/info"
}

# blkid_tag <block name> <TYPE|LABEL>
blkid_tag() {
	timeout 5 blkid "$DEVDIR/$1" </dev/null 2>/dev/null |
		sed -n "s/.* $2=\"\([^\"|]*\)\".*/\1/p" | head -n 1
}

# file_name <block name> : the image file's name, without .img
file_name() {
	local n
	case $1 in
	"$DISK") echo "emmc-whole" ;;
	"$DISK"boot*) echo "emmc-${1#"$DISK"}" ;;
	*)
		n=$(sed -n 's/^PARTNAME=//p' "$SYS/block/$DISK/$1/uevent" 2>/dev/null |
			tr -c 'A-Za-z0-9_.\n-' '_')
		echo "emmc-${1#"$DISK"}${n:+-$n}" ;;
	esac
}

# copy <block name> : one part into $FOLDER, in pieces of SPLIT MB when SPLIT isn't 0
copy() {
	local item=$1 total off=0 piece=0 out name n seek got
	total=$(mb "$(sectors "$item")")
	name=$FOLDER/$(file_name "$item").img
	while [ "$off" -lt "$total" ]; do
		if [ -e "$STOP" ]; then
			rm -f "$STOP"
			finish "state=stopped" "item=$item" "done_mb=$DONE" "total_mb=$ALL"
		fi
		n=$CHUNK_MB
		[ $((off + n)) -gt "$total" ] && n=$((total - off))
		if [ "$SPLIT" -gt 0 ]; then
			piece=$((off / SPLIT))
			out=$name.$(printf '%03d' $((piece + 1)))
			# a piece ends at a multiple of SPLIT
			[ $((off + n)) -gt $(((piece + 1) * SPLIT)) ] && n=$(((piece + 1) * SPLIT - off))
			seek=$((off - piece * SPLIT))
		else
			out=$name
			seek=$off
		fi
		# whole MB records read: fewer than asked before the end is a read error
		got=$(dd if="$DEVDIR/$item" of="$out" bs=1048576 skip="$off" seek="$seek" \
			count="$n" conv=notrunc 2>&1 | sed -n 's/^\([0-9]*\)+[0-9]* records in.*/\1/p')
		if [ "$(free_mb "$FOLDER")" -lt 1 ]; then
			log "copying $item: no space left at $off MB"
			finish "state=failed" "error=nospace" "item=$item" "done_mb=$DONE" "total_mb=$ALL"
		fi
		if [ "${got:-0}" -lt "$n" ] && [ $((off + n)) -lt "$total" ]; then
			log "copying $item failed at $off MB"
			finish "state=failed" "error=read" "item=$item" "done_mb=$DONE" "total_mb=$ALL"
		fi
		off=$((off + n))
		DONE=$((DONE + n))
		status "state=running" "item=$item" "done_mb=$DONE" "total_mb=$ALL"
	done
}

do_dump() {
	local dest items keep item stamp
	dest=$(req dest)
	items=$(req items)
	SPLIT=$(req split_mb)
	keep=$(req keep_free_mb)
	SPLIT=${SPLIT:-0}
	keep=${keep:-0}
	FOLDER=
	[ -n "$DISK" ] || finish "state=failed" "error=noemmc"
	ALL=0
	for item in $items; do
		case $item in "$DISK" | "$DISK"p* | "$DISK"boot*) ;; *) finish "state=failed" "error=noemmc" ;; esac
		[ -e "$DEVDIR/$item" ] || finish "state=failed" "error=noemmc"
		ALL=$((ALL + $(mb "$(sectors "$item")")))
	done
	[ -d "$dest" ] || finish "state=failed" "error=dest"
	if [ $(($(free_mb "$dest") - keep)) -lt "$ALL" ]; then
		finish "state=failed" "error=nospace" "done_mb=0" "total_mb=$ALL"
	fi
	stamp=$(date +%Y%m%d-%H%M%S)
	FOLDER="$dest/eMMC backup/$stamp"
	mkdir -p "$FOLDER" || finish "state=failed" "error=dest"
	echo "$WAKELOCK" 2>/dev/null >"$SYS/power/wake_lock"
	do_info
	cp -f "$DIR/info" "$FOLDER/emmc-info.txt"
	DONE=0
	status "state=running" "done_mb=0" "total_mb=$ALL"
	for item in $items; do
		copy "$item"
	done
	# internal storage wants its files owned by media_rw, like everything there
	case $dest in /data/media/*) chown -R 1023:1023 "$dest/eMMC backup" 2>/dev/null ;; esac
	sync
	log "copied $items ($ALL MB) to $FOLDER"
	finish "state=done" "done_mb=$DONE" "total_mb=$ALL"
}

do_speed() {
	local out
	[ -n "$DISK" ] || finish "state=failed" "error=noemmc"
	echo "$WAKELOCK" 2>/dev/null >"$SYS/power/wake_lock"
	status "state=running" "seconds=$((SPEED_SECONDS * 2))"
	out=$(timeout -k 5 $((SPEED_SECONDS * 2 + 30)) "$EMMCSPEED" "$DEVDIR/$DISK" \
		"$SPEED_SECONDS" 2>/dev/null)
	case $out in
	seq_bytes=*)
		# shellcheck disable=SC2086 # one key=value per line
		finish "state=done" $out ;;
	*)
		log "speed test failed: $out"
		finish "state=failed" "error=read" ;;
	esac
}

ACTION=$(req action)
STARTED=$(date +%s)
FOLDER=
DISK=$(emmc_disk)
rm -f "$STOP"
case $ACTION in
info) do_info ;;
dump) do_dump ;;
speed) do_speed ;;
*) log "unknown request: $ACTION" ;;
esac
