#!/system/bin/sh
# shellcheck shell=ksh
#
# andr36oid-cardcheck: the root half of Settings > Storage > SD card check.
#
#   speed    writes and reads back a 256 MB file, the app works out MB/s
#   full     fills the free space with random data in 16 MB files, reads it all
#            back and compares checksums, then deletes it (the idea of f3 by
#            Michel Machado, done with dd and md5sum). Fake cards claim more space
#            than they have, and lose or mangle what is written past the real end.
#   cleanup  deletes test files left behind (power loss, reboot)
#
# Fake cards of the wrap-around kind write past their real end onto their own
# beginning, where the bootloader and partition table live. So before filling
# anything, a read-only probe looks for the card's first 4 KB showing up again every
# 256 MB. During the fill, the first 4 KB are read back after every file, and a
# backup of the first 64 MB is put back if they ever change.
#
# The app writes $DIR/request (action=, path=/mnt/media_rw/<uuid>, disk=<maj>:<min>,
# disk_mb=<size of the whole card>)
# and starts us with ctl.start. We write key=value lines to $DIR/status; the times
# are "seconds.nanoseconds" strings because mksh only does 32-bit integers.
# $DIR/stop asks a running check to stop.

DIR=/data/misc/andr36oid-cardcheck
STATUS=$DIR/status
STOP=$DIR/stop
HEAD=$DIR/head.bin
SUMS=$DIR/sums
FILE_MB=16
FILES_PER_DIR=64
SPEED_MB=256
KEEP_FREE_MB=64
HEAD_MB=64
PROBE_STEP_MB=256
WAKELOCK=andr36oid-cardcheck
ZERO4K_MD5=620f0b67a91f7f74151bc5be745b7110

log() {
	/system/bin/log -t andr36oid-cardcheck "$*"
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
	chown system:system "$STATUS.tmp"
	chmod 0660 "$STATUS.tmp"
	mv -f "$STATUS.tmp" "$STATUS"
}

# finish <key=value>... : final status, also kept per card as result-<action>-<uuid>
finish() {
	status "$@"
	cp -f "$STATUS" "$DIR/result-$ACTION-$UUID"
	chown system:system "$DIR/result-$ACTION-$UUID"
	chmod 0660 "$DIR/result-$ACTION-$UUID"
	echo "$WAKELOCK" >/sys/power/wake_unlock 2>/dev/null
	am broadcast -a org.andr36oid.cardcheck.FINISHED \
		-n org.andr36oid.cardcheck/.FinishedReceiver >/dev/null 2>&1
	exit 0
}

req() {
	sed -n "s/^$1=//p" "$DIR/request" 2>/dev/null | head -n 1
}

free_mb() {
	local a s
	set -- $(stat -f -c '%a %S' "$ROOT" 2>/dev/null)
	a=$1 s=$2
	[ -n "$a" ] && [ -n "$s" ] || { echo 0; return; }
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

cleanup_files() {
	rm -rf "$ROOT/.andr36oid-cardcheck"
	rm -f "$SUMS" "$HEAD" "$STOP"
	sync
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
/mnt/media_rw/*) ;;
*) ACTION=${ACTION:-none}; UUID=none; finish state=failed error=bad_request ;;
esac
case $UUID in
*/*|.*|'') UUID=none; finish state=failed error=bad_request ;;
esac
[ -d "$ROOT" ] || finish state=failed error=not_mounted
TD=$ROOT/.andr36oid-cardcheck

DEV=
if [ -n "$DISK" ] && [ -r "/sys/dev/block/$DISK/uevent" ]; then
	DEV=/dev/block/$(sed -n 's/^DEVNAME=//p' "/sys/dev/block/$DISK/uevent")
fi

case $ACTION in
cleanup)
	cleanup_files
	rm -f "$STATUS"
	exit 0
	;;
speed)
	echo "$WAKELOCK" >/sys/power/wake_lock 2>/dev/null
	cleanup_files
	[ "$(free_mb)" -gt $((SPEED_MB + KEEP_FREE_MB)) ] || finish state=failed error=no_space
	mkdir -p "$TD" || finish state=failed error=cant_write
	status state=running phase=write
	sync
	W0=$(now)
	if ! dd if=/dev/zero of="$TD/speed.bin" bs=1048576 count=$SPEED_MB conv=fsync 2>/dev/null; then
		cleanup_files
		finish state=failed error=write_error
	fi
	W1=$(now)
	status state=running phase=read "w0=$W0" "w1=$W1"
	sync
	echo 3 >/proc/sys/vm/drop_caches
	R0=$(now)
	if ! dd if="$TD/speed.bin" of=/dev/null bs=1048576 2>/dev/null; then
		cleanup_files
		finish state=failed error=read_error
	fi
	R1=$(now)
	cleanup_files
	log "speed test on $UUID: $SPEED_MB MB, write $W0..$W1, read $R0..$R1"
	finish state=done "size_mb=$SPEED_MB" "w0=$W0" "w1=$W1" "r0=$R0" "r1=$R1"
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
[ -n "$DEV" ] && [ -b "$DEV" ] && [ "$DISK_MB" -gt 0 ] || finish state=failed error=no_disk

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

# 3. fill the free space
FREE=$(free_mb)
TOTAL=$(((FREE - KEEP_FREE_MB) / FILE_MB * FILE_MB))
[ "$TOTAL" -ge $FILE_MB ] || { cleanup_files; finish state=failed error=no_space; }
USED_MB=$((DISK_MB - FREE))
: >"$SUMS"
mkdir -p "$TD" || { cleanup_files; finish state=failed error=cant_write; }
log "filling $TOTAL MB on $UUID ($DEV)"

done_mb=0 i=0
while [ "$done_mb" -lt "$TOTAL" ]; do
	if [ -e "$STOP" ]; then
		cleanup_files
		finish state=stopped "written_mb=$done_mb" "total_mb=$TOTAL"
	fi
	d=$(printf 'd%03d' $((i / FILES_PER_DIR)))
	f=$d/$(printf 'f%03d' $((i % FILES_PER_DIR))).bin
	mkdir -p "$TD/$d"
	sum=$(dd if=/dev/urandom bs=1048576 count=$FILE_MB 2>/dev/null | tee "$TD/$f" | md5sum | cut -d' ' -f1)
	sync
	size=$(stat -c %s "$TD/$f" 2>/dev/null)
	if [ "$size" != $((FILE_MB * 1048576)) ]; then
		log "writing $f failed after $done_mb MB (size ${size:-none})"
		cleanup_files
		finish state=done result=write_error "good_mb=$done_mb" "total_mb=$TOTAL" "used_mb=$USED_MB"
	fi
	if [ "$(head_md5)" != "$REF" ]; then
		log "the card's first 4 KB changed after $done_mb MB: it wraps around, putting them back"
		dd if="$HEAD" of="$DEV" bs=1048576 conv=fsync 2>/dev/null
		blockdev --flushbufs "$DEV" 2>/dev/null
		restored=yes
		[ "$(head_md5)" = "$REF" ] || restored=no
		cleanup_files
		finish state=done result=wraps "real_mb=$((USED_MB + done_mb))" "good_mb=$done_mb" \
			"restored=$restored" "total_mb=$TOTAL"
	fi
	echo "$f $sum" >>"$SUMS"
	done_mb=$((done_mb + FILE_MB))
	i=$((i + 1))
	status state=running phase=write "done_mb=$done_mb" "total_mb=$TOTAL"
done

# 4. read it all back, from the card and not from the page cache
sync
echo 3 >/proc/sys/vm/drop_caches
READ0=$(now)
checked_mb=0 bad_mb=0 first_bad=-1
while read -r f want; do
	if [ -e "$STOP" ]; then
		cleanup_files
		finish state=stopped "written_mb=$TOTAL" "checked_mb=$checked_mb" "total_mb=$TOTAL"
	fi
	got=$(md5sum "$TD/$f" 2>/dev/null | cut -d' ' -f1)
	if [ "$got" != "$want" ]; then
		bad_mb=$((bad_mb + FILE_MB))
		[ "$first_bad" -lt 0 ] && first_bad=$checked_mb
	fi
	checked_mb=$((checked_mb + FILE_MB))
	status state=running phase=verify "done_mb=$checked_mb" "total_mb=$TOTAL" \
		"bad_mb=$bad_mb" "read0=$READ0"
done <"$SUMS"

cleanup_files
if [ "$bad_mb" -eq 0 ]; then
	log "full check on $UUID passed: $TOTAL MB"
	finish state=done result=pass "total_mb=$TOTAL" "used_mb=$USED_MB"
fi
log "full check on $UUID failed: $bad_mb of $TOTAL MB bad, first at $first_bad MB"
finish state=done result=bad_data "bad_mb=$bad_mb" "good_mb=$first_bad" "total_mb=$TOTAL" \
	"used_mb=$USED_MB"
