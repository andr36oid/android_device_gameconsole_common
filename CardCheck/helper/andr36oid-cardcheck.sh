#!/system/bin/sh
# shellcheck shell=ksh
#
# andr36oid-cardcheck: the root half of Settings > Storage > SD card check.
#
#   speed    writes and reads back a 256 MB file, the app works out MB/s; then random
#            4 KB reads and writes inside it (andr36oid-cardspeed, O_DIRECT) for the
#            A1/A2 kind of speed. Also looks up how fast the console talks to the card
#            (the kernel's "new ... card" line). Needs 1 GB free.
#   full     fills the free space with random data in 16 MB files, reads it all
#            back and compares checksums, then deletes it (the idea of f3 by
#            Michel Machado, done with dd and md5sum). Fake cards claim more space
#            than they have, and lose or mangle what is written past the real end.
#   resume   goes on with a paused full check (the test data stayed on the card)
#   delete   deletes the test data of a paused full check
#   cleanup  deletes speed test files left behind (power loss, reboot)
#
# Fake cards of the wrap-around kind write past their real end onto their own
# beginning, where the bootloader and partition table live. So before filling
# anything, a read-only probe looks for the card's first 4 KB showing up again every
# 256 MB. During the fill, the first 4 KB are read back after every file, and a
# backup of the first 64 MB is put back if they ever change.
#
# The app writes $DIR/request (action=, path=/mnt/media_rw/<uuid>, disk=<maj>:<min>,
# disk_mb=<size of the whole card>)
# and starts us with ctl.start. A boot card without EASYROMS (the whole card is
# userdata) is tested in path=$DIR/userdata instead, uuid "userdata".
# We write key=value lines to $DIR/status; the times are "seconds.nanoseconds"
# strings because mksh only does 32-bit integers.
# $DIR/stop asks a running check to stop; if it holds "pause", a full check keeps its
# test data and $DIR/progress-<uuid> so it can be resumed. Nothing here runs at boot.

# The host test (tests/run-tests.sh) points these at a temp dir. init starts services
# with a clean environment, so on the console they always have the default values.
DIR=${CARDCHECK_DIR:-/data/misc/andr36oid-cardcheck}
MEDIA=${CARDCHECK_MEDIA:-/mnt/media_rw}
SYS=${CARDCHECK_SYS:-/sys}
DEVDIR=${CARDCHECK_DEVDIR:-/dev/block}
STATUS=$DIR/status
USERDATA=$DIR/userdata
STOP=$DIR/stop
FILE_MB=16
FILES_PER_DIR=64
SPEED_MB=256
SPEED_FREE_MB=1024
RAND_SECONDS=${CARDCHECK_RAND_SECONDS:-8}
CARDSPEED=${CARDCHECK_CARDSPEED:-/system/bin/andr36oid-cardspeed}
KEEP_FREE_MB=64
# Android itself runs from userdata and needs room while a size check fills it
USERDATA_KEEP_FREE_MB=1024
HEAD_MB=64
PROBE_STEP_MB=256
WAKELOCK=andr36oid-cardcheck
ZERO4K_MD5=620f0b67a91f7f74151bc5be745b7110

log() {
	/system/bin/log -t andr36oid-cardcheck "$*" 2>/dev/null
}

now() {
	date +%s.%N
}

# status <key=value>... : replaces the status file; the common keys come first
status() {
	{
		echo "action=$ACTION"
		echo "uuid=$UUID"
		echo "started=$STARTED"
		echo "now=$(now)"
		for kv in "$@"; do echo "$kv"; done
	} >"$STATUS.tmp"
	chown system:system "$STATUS.tmp" 2>/dev/null
	chmod 0660 "$STATUS.tmp"
	mv -f "$STATUS.tmp" "$STATUS"
}

# finish <key=value>... : final status, also kept per card as result-<action>-<uuid>
finish() {
	status "$@"
	cp -f "$STATUS" "$DIR/result-$ACTION-$UUID"
	chown system:system "$DIR/result-$ACTION-$UUID" 2>/dev/null
	chmod 0660 "$DIR/result-$ACTION-$UUID"
	echo "$WAKELOCK" 2>/dev/null >/sys/power/wake_unlock
	am broadcast -a org.andr36oid.cardcheck.FINISHED \
		-n org.andr36oid.cardcheck/.FinishedReceiver >/dev/null 2>&1
	exit 0
}

req() {
	sed -n "s/^$1=//p" "$DIR/request" 2>/dev/null | head -n 1
}

free_mb() {
	local a s
	# shellcheck disable=SC2046 # split the two numbers into $1 $2
	set -- $(stat -f -c '%a %S' "$ROOT" 2>/dev/null)
	a=$1 s=$2
	if [ -z "$a" ] || [ -z "$s" ]; then echo 0; return; fi
	if [ "$s" -ge 1048576 ]; then
		echo $((a * (s / 1048576)))
	else
		echo $((a / (1048576 / s)))
	fi
}

# head_md5 : checksum of the card's first 4 KB as the card has it now (not the cache)
head_md5() {
	blockdev --flushbufs "$DEV" 2>/dev/null
	dd if="$DEV" bs=4096 count=1 2>/dev/null | md5sum | cut -d' ' -f1
}

# cleanup_files : deletes this card's size check test data and its progress
cleanup_files() {
	rm -rf "$TD"
	rm -f "$SUMS" "$HEAD" "$HEAD.tmp" "$PROGRESS" "$STOP" "$DIR/sums" "$DIR/head.bin"
	sync
}

cleanup_speed() {
	rm -rf "$SD"
	rm -f "$TD/speed.bin"
	sync
}

# file_of <n> : the n-th test file, relative to $TD
file_of() {
	printf 'd%03d/f%03d.bin' $(($1 / FILES_PER_DIR)) $(($1 % FILES_PER_DIR))
}

prog() {
	sed -n "s/^$1=//p" "$PROGRESS" 2>/dev/null | head -n 1
}

# save_progress : what a paused or cut off size check needs to go on
save_progress() {
	{
		echo "phase=$PHASE"
		echo "total_mb=$TOTAL"
		echo "used_mb=$USED_MB"
		echo "ref=$REF"
		echo "written_mb=$done_mb"
		echo "checked_mb=$checked_mb"
		echo "bad_mb=$bad_mb"
		echo "first_bad=$first_bad"
	} >"$PROGRESS.tmp" || return
	# init starts us with umask 077; the app (uid system) must be able to read it
	chown system:system "$PROGRESS.tmp" 2>/dev/null
	chmod 0660 "$PROGRESS.tmp"
	mv -f "$PROGRESS.tmp" "$PROGRESS"
}

# check_stop : the app asked to stop (delete the test data) or to pause (keep it)
check_stop() {
	[ -e "$STOP" ] || return 0
	if [ "$(cat "$STOP" 2>/dev/null)" = pause ]; then
		rm -f "$STOP"
		save_progress
		log "size check on $UUID paused: $done_mb MB written, $checked_mb MB checked of $TOTAL MB"
		finish state=paused "phase=$PHASE" "written_mb=$done_mb" "checked_mb=$checked_mb" \
			"total_mb=$TOTAL"
	fi
	cleanup_files
	finish state=stopped
}

# put_head_back <written_mb> : the card's start changed, so it wraps around
put_head_back() {
	log "the card's first 4 KB changed after $1 MB: it wraps around, putting them back"
	dd if="$HEAD" of="$DEV" bs=1048576 conv=fsync,notrunc 2>/dev/null
	blockdev --flushbufs "$DEV" 2>/dev/null
	restored=yes
	[ "$(head_md5)" = "$REF" ] || restored=no
	cleanup_files
	finish state=done result=wraps "real_mb=$((USED_MB + $1))" "good_mb=$1" \
		"restored=$restored" "total_mb=$TOTAL"
}

# fill_and_verify : writes test files up to $TOTAL MB, then reads them all back. Goes
# on from $done_mb and $checked_mb, so it serves a new check and a resumed one.
fill_and_verify() {
	local i f d sum size got want base skip
	PHASE="write"
	i=$((done_mb / FILE_MB))
	base=$done_mb
	save_progress
	while [ "$done_mb" -lt "$TOTAL" ]; do
		check_stop
		f=$(file_of "$i")
		d=${f%/*}
		mkdir -p "$TD/$d"
		sum=$(dd if=/dev/urandom bs=1048576 count=$FILE_MB 2>/dev/null | tee "$TD/$f" | md5sum | cut -d' ' -f1)
		sync
		size=$(stat -c %s "$TD/$f" 2>/dev/null)
		if [ "$size" != $((FILE_MB * 1048576)) ]; then
			log "writing $f failed after $done_mb MB (size ${size:-none})"
			cleanup_files
			finish state=done result=write_error "good_mb=$done_mb" "total_mb=$TOTAL" \
				"used_mb=$USED_MB"
		fi
		[ "$(head_md5)" = "$REF" ] || put_head_back "$done_mb"
		echo "$f $sum" >>"$SUMS"
		done_mb=$((done_mb + FILE_MB))
		i=$((i + 1))
		save_progress
		status state=running phase=write "done_mb=$done_mb" "total_mb=$TOTAL" "base_mb=$base"
	done

	# read it all back, from the card and not from the page cache
	PHASE="verify"
	save_progress
	sync
	echo 3 2>/dev/null >/proc/sys/vm/drop_caches
	READ0=$(now)
	base=$checked_mb
	skip=$((checked_mb / FILE_MB))
	while read -r f want; do
		if [ "$skip" -gt 0 ]; then
			skip=$((skip - 1))
			continue
		fi
		check_stop
		got=$(md5sum "$TD/$f" 2>/dev/null | cut -d' ' -f1)
		if [ "$got" != "$want" ]; then
			bad_mb=$((bad_mb + FILE_MB))
			[ "$first_bad" -lt 0 ] && first_bad=$checked_mb
		fi
		checked_mb=$((checked_mb + FILE_MB))
		save_progress
		status state=running phase=verify "done_mb=$checked_mb" "total_mb=$TOTAL" \
			"bad_mb=$bad_mb" "read0=$READ0" "base_mb=$base"
	done <"$SUMS"

	cleanup_files
	if [ "$bad_mb" -eq 0 ]; then
		log "full check on $UUID passed: $TOTAL MB"
		finish state=done result=pass "total_mb=$TOTAL" "used_mb=$USED_MB"
	fi
	log "full check on $UUID failed: $bad_mb of $TOTAL MB bad, first at $first_bad MB"
	finish state=done result=bad_data "bad_mb=$bad_mb" "good_mb=$first_bad" "total_mb=$TOTAL" \
		"used_mb=$USED_MB"
}

# bus_mode : how the kernel set the card up ("ultra_high_speed_SDR104_SDXC"), from its
# "mmc0: new ... card at address 59b4" line; empty when that line has scrolled away
bus_mode() {
	local card host addr line
	card=$(readlink "$SYS/dev/block/$DISK/device" 2>/dev/null)
	card=${card##*/}
	case $card in
	mmc[0-9]*:[0-9a-f]*) ;;
	*) return 0 ;;
	esac
	host=${card%%:*}
	addr=${card#*:}
	line=$(dmesg 2>/dev/null | grep "$host: new .* card at address $addr" | tail -n 1)
	if [ -z "$line" ]; then
		line=$(logcat -b kernel -d 2>/dev/null | grep "$host: new .* card at address $addr" | tail -n 1)
	fi
	[ -n "$line" ] || return 0
	line=${line##*"$host: new "}
	line=${line%% card at address*}
	echo "$line" | tr -c 'A-Za-z0-9\n' '_'
}

# is_disk <dev> : a block device (a plain file too, in the host test)
is_disk() {
	[ -b "$1" ] || { [ -n "$CARDCHECK_DIR" ] && [ -f "$1" ]; }
}

# the host test can't make a small file system to fill
if [ -n "$CARDCHECK_FREE_MB" ]; then
	free_mb() {
		echo "$CARDCHECK_FREE_MB"
	}
fi

# number <value> : the value if it is a whole number (maybe negative), else nothing
number() {
	case $1 in
	''|-|*[!0-9-]*|?*-*) ;;
	*) echo "$1" ;;
	esac
}

ACTION=$(req action)
ROOT=$(req path)
DISK=$(req disk)
DISK_MB=$(req disk_mb)
case $DISK_MB in ''|*[!0-9]*) DISK_MB=0 ;; esac
UUID=${ROOT##*/}
STARTED=$(now)
rm -f "$STOP"

case $ROOT in
"$MEDIA"/*) ;;
"$USERDATA")
	mkdir -p "$ROOT"
	chmod 0700 "$ROOT"
	KEEP_FREE_MB=$USERDATA_KEEP_FREE_MB
	;;
*) ACTION=${ACTION:-none}; UUID=none; finish state=failed error=bad_request ;;
esac
case $UUID in
*/*|.*|'') UUID=none; finish state=failed error=bad_request ;;
esac
[ -d "$ROOT" ] || finish state=failed error=not_mounted
TD=$ROOT/.andr36oid-cardcheck
SD=$ROOT/.andr36oid-cardspeed
HEAD=$DIR/head-$UUID.bin
SUMS=$DIR/sums-$UUID
PROGRESS=$DIR/progress-$UUID

DEV=
if [ -n "$DISK" ] && [ -r "$SYS/dev/block/$DISK/uevent" ]; then
	DEV=$DEVDIR/$(sed -n 's/^DEVNAME=//p' "$SYS/dev/block/$DISK/uevent")
fi

case $ACTION in
cleanup)
	cleanup_speed
	rm -f "$STATUS"
	exit 0
	;;
delete)
	ACTION=full
	cleanup_files
	log "deleted the paused size check test data on $UUID"
	finish state=stopped
	;;
speed)
	echo "$WAKELOCK" 2>/dev/null >/sys/power/wake_lock
	cleanup_speed
	# a paused size check fills the card; its test data must stay
	[ ! -s "$PROGRESS" ] || finish state=failed error=paused
	[ "$(free_mb)" -ge $SPEED_FREE_MB ] || finish state=failed error=no_space
	mkdir -p "$SD" || finish state=failed error=cant_write
	status state=running phase=write
	sync
	W0=$(now)
	if ! dd if=/dev/zero of="$SD/speed.bin" bs=1048576 count=$SPEED_MB conv=fsync 2>/dev/null; then
		cleanup_speed
		finish state=failed error=write_error
	fi
	W1=$(now)
	status state=running phase=read "w0=$W0" "w1=$W1"
	sync
	echo 3 2>/dev/null >/proc/sys/vm/drop_caches
	R0=$(now)
	if ! dd if="$SD/speed.bin" of=/dev/null bs=1048576 2>/dev/null; then
		cleanup_speed
		finish state=failed error=read_error
	fi
	R1=$(now)
	status state=running phase=random "w0=$W0" "w1=$W1" "r0=$R0" "r1=$R1"
	# rr_ops= rr_s= rw_ops= rw_s= direct=, or rand_error=; nothing if it's missing
	# the tool checks its time only between reads and writes; a hung card must not keep
	# it (and the wake lock) forever
	RAND=$(timeout -k 5 $((RAND_SECONDS * 2 + 30)) "$CARDSPEED" "$SD/speed.bin" "$RAND_SECONDS" \
		2>/dev/null | grep -E '^[a-z_]+=[0-9a-z.]+$')
	BUS=$(bus_mode)
	cleanup_speed
	log "speed test on $UUID: $SPEED_MB MB, write $W0..$W1, read $R0..$R1," \
		"small blocks: $(echo "$RAND" | tr "\n" " ")" "bus: ${BUS:-unknown}"
	# shellcheck disable=SC2086 # one key=value per line from andr36oid-cardspeed
	finish state=done "size_mb=$SPEED_MB" "w0=$W0" "w1=$W1" "r0=$R0" "r1=$R1" "bus=$BUS" $RAND
	;;
resume)
	echo "$WAKELOCK" 2>/dev/null >/sys/power/wake_lock
	ACTION=full
	PHASE=$(prog phase)
	TOTAL=$(number "$(prog total_mb)")
	USED_MB=$(number "$(prog used_mb)")
	REF=$(prog ref)
	checked_mb=$(number "$(prog checked_mb)")
	bad_mb=$(number "$(prog bad_mb)")
	first_bad=$(number "$(prog first_bad)")
	if ! { [ -f "$SUMS" ] && [ -s "$HEAD" ] && is_disk "$DEV" && [ -n "$REF" ] \
		&& [ -n "$TOTAL" ] && [ -n "$USED_MB" ] && [ -n "$checked_mb" ] \
		&& [ -n "$bad_mb" ] && [ -n "$first_bad" ]; }; then
		cleanup_files
		finish state=failed error=no_progress
	fi
	# a checksum line cut short by a power loss would get the next one appended onto it
	# and then read as bad data: keep only whole lines
	grep -E '^d[0-9]{3}/f[0-9]{3}\.bin [0-9a-f]{32}$' "$SUMS" >"$SUMS.tmp"
	mv -f "$SUMS.tmp" "$SUMS"
	done_mb=$(($(wc -l <"$SUMS") * FILE_MB))
	status state=running phase=probe
	# the start changed while the check was paused: the last file before a power cut
	# wrapped around onto it
	[ "$(head_md5)" = "$REF" ] || put_head_back "$done_mb"
	# a new backup of the start, so an update installed in the meantime stays
	if dd if="$DEV" of="$HEAD.tmp" bs=1048576 count=$HEAD_MB 2>/dev/null; then
		mv -f "$HEAD.tmp" "$HEAD"
	else
		rm -f "$HEAD.tmp"
	fi
	if [ "$PHASE" = write ]; then
		# a file cut short by a power loss goes; games may have used some space meanwhile
		rm -f "$TD/$(file_of $((done_mb / FILE_MB)))"
		FREE=$(free_mb)
		TOTAL=$((done_mb + (FREE - KEEP_FREE_MB) / FILE_MB * FILE_MB))
		[ "$TOTAL" -ge "$done_mb" ] || TOTAL=$done_mb
	fi
	log "going on with the size check on $UUID ($PHASE): $done_mb MB written," \
		"$checked_mb MB checked of $TOTAL MB"
	fill_and_verify
	;;
full)
	;;
*)
	finish state=failed error=bad_request
	;;
esac

# ---- full check ----
echo "$WAKELOCK" >/sys/power/wake_lock 2>/dev/null
cleanup_files
if ! { [ -n "$DEV" ] && [ -b "$DEV" ] && [ "$DISK_MB" -gt 0 ]; }; then
	finish state=failed error=no_disk
fi

# 1. read-only probe: does the card's start show up again further in?
status state=running phase=probe
REF=$(head_md5)
if [ "$REF" != "$ZERO4K_MD5" ]; then
	off=$PROBE_STEP_MB
	while [ "$off" -lt "$DISK_MB" ]; do
		sum=$(dd if="$DEV" bs=1048576 skip="$off" count=1 2>/dev/null | head -c 4096 | md5sum | cut -d' ' -f1)
		if [ "$sum" = "$REF" ]; then
			log "$DEV: the first 4 KB show up again at $off MB, the card wraps around"
			finish state=done result=wraps "real_mb=$off" "disk_mb=$DISK_MB"
		fi
		off=$((off + PROBE_STEP_MB))
	done
else
	log "$DEV starts with zeros, no wrap-around probe"
fi

# 2. back up the card's first 64 MB, to undo a wrap-around we didn't foresee
if ! dd if="$DEV" of="$HEAD" bs=1048576 count=$HEAD_MB 2>/dev/null; then
	cleanup_files
	finish state=failed error=no_backup
fi

# 3. fill the free space, 4. read it all back
FREE=$(free_mb)
TOTAL=$(((FREE - KEEP_FREE_MB) / FILE_MB * FILE_MB))
[ "$TOTAL" -ge $FILE_MB ] || { cleanup_files; finish state=failed error=no_space; }
USED_MB=$((DISK_MB - FREE))
: >"$SUMS"
mkdir -p "$TD" || { cleanup_files; finish state=failed error=cant_write; }
log "filling $TOTAL MB on $UUID ($DEV)"
done_mb=0 checked_mb=0 bad_mb=0 first_bad=-1
fill_and_verify
