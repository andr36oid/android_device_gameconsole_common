#!/bin/bash
# Host test of the root helper (helper/andr36oid-emmc.sh): info, a dump of the whole eMMC,
# a partition and a boot area, pieces for FAT32, not enough space, stop, and no eMMC.
#
#   tests/helper-test.sh <shell> <andr36oid-emmcspeed built for the host>
#
# The eMMC is a set of plain files in $T/dev, sysfs a tree in $T/sys whose disk links
# into a fake ff390000.dwmmc. blkid is a small script in $T/bin.
set -u
SH=$1
SPEED=$2
HERE=$(cd "$(dirname "$0")/.." && pwd)
HELPER=$HERE/helper/andr36oid-emmc.sh
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fails=0
checks=0

ok() {
	checks=$((checks + 1))
	if [ "$2" != "$3" ]; then
		echo "FAIL [$SH] $1: got '$2', want '$3'"
		fails=$((fails + 1))
	fi
}

key() { sed -n "s/^$2=//p" "$1" 2>/dev/null | head -n 1; }

DIR=$T/data
SYS=$T/sys
DEV=$T/dev
DEST=$T/media
mkdir -p "$DIR" "$DEV" "$DEST" "$T/bin" "$SYS/power" "$SYS/block"
HOST=$SYS/devices/platform/ff390000.dwmmc/mmc_host/mmc2/mmc2:0001
B=$HOST/block/mmcblk2
mkdir -p "$B/mmcblk2p1" "$B/mmcblk2p2" "$HOST/block/mmcblk2boot0"
ln -s "$HOST" "$B/device"
ln -s "$B" "$SYS/block/mmcblk2"
ln -s "$HOST/block/mmcblk2boot0" "$SYS/block/mmcblk2boot0"
# an SD card on another controller, not the eMMC
mkdir -p "$SYS/devices/platform/ff370000.dwmmc/block/mmcblk0"
ln -s "$SYS/devices/platform/ff370000.dwmmc/block/mmcblk0" "$SYS/block/mmcblk0"

# 20 MB disk: p1 "uboot" 4 MB at 1 MB, p2 "rootfs" 5 MB at 5 MB; 1 MB boot area
head -c $((20 * 1048576)) /dev/urandom >"$DEV/mmcblk2"
dd if="$DEV/mmcblk2" of="$DEV/mmcblk2p1" bs=1048576 skip=1 count=4 2>/dev/null
dd if="$DEV/mmcblk2" of="$DEV/mmcblk2p2" bs=1048576 skip=5 count=5 2>/dev/null
head -c 1048576 /dev/urandom >"$DEV/mmcblk2boot0"
cp "$DEV/mmcblk2" "$T/mmcblk2.orig"
echo 40960 >"$B/size"
printf '2048\n' >"$B/mmcblk2p1/start"
printf '8192\n' >"$B/mmcblk2p1/size"
printf 'MAJOR=179\nPARTNAME=uboot\n' >"$B/mmcblk2p1/uevent"
printf '10240\n' >"$B/mmcblk2p2/start"
printf '10240\n' >"$B/mmcblk2p2/size"
printf 'MAJOR=179\nPARTNAME=root fs\n' >"$B/mmcblk2p2/uevent"
echo 2048 >"$HOST/block/mmcblk2boot0/size"
echo 8GTF4R >"$HOST/name"
echo 0x000015 >"$HOST/manfid"
echo 03/2021 >"$HOST/date"
echo '0x01 0x02' >"$HOST/life_time"
echo 0x01 >"$HOST/pre_eol_info"

cat >"$T/bin/blkid" <<'EOF'
#!/bin/sh
case $1 in
*mmcblk2p2) echo "$1: LABEL=\"STORAGE\" UUID=\"1234\" TYPE=\"ext4\"" ;;
esac
EOF
chmod +x "$T/bin/blkid"
printf '#!/bin/sh\nexit 0\n' >"$T/bin/sync"
chmod +x "$T/bin/sync"

run() {
	printf '%s\n' "$@" >"$DIR/request"
	PATH=$T/bin:$PATH EMMC_DIR=$DIR EMMC_EMMCSPEED=${EMMC_EMMCSPEED:-} \
		EMMC_SPEED_SECONDS=${EMMC_SPEED_SECONDS:-} EMMC_SYS=$SYS EMMC_DEVDIR=$DEV "$SH" "$HELPER"
}

# info
run action=info
I=$DIR/info
ok "info state" "$(key "$I" state)" ok
ok "info disk" "$(key "$I" disk)" mmcblk2
ok "info sectors" "$(key "$I" sectors)" 40960
ok "info name" "$(key "$I" name)" 8GTF4R
ok "info life" "$(key "$I" life_time)" "0x01 0x02"
ok "info parts" "$(grep -c '^part=' "$I")" 2
ok "info p1" "$(grep '^part=mmcblk2p1|' "$I")" "part=mmcblk2p1|2048|8192|uboot||"
ok "info p2" "$(grep '^part=mmcblk2p2|' "$I")" "part=mmcblk2p2|10240|10240|root fs|ext4|STORAGE"
ok "info boot" "$(key "$I" boot)" "mmcblk2boot0|2048"

# dump whole + one partition + boot area
run action=dump "items=mmcblk2 mmcblk2p2 mmcblk2boot0" "dest=$DEST" split_mb=0 keep_free_mb=0
S=$DIR/status
ok "dump state" "$(key "$S" state)" "done"
ok "dump total" "$(key "$S" total_mb)" 26
ok "dump done" "$(key "$S" done_mb)" 26
F=$(key "$S" folder)
ok "dump folder" "${F%/*}" "$DEST/eMMC backup"
cmp -s "$F/emmc-whole.img" "$DEV/mmcblk2"
ok "whole same" $? 0
cmp -s "$F/emmc-p2-root_fs.img" "$DEV/mmcblk2p2"
ok "p2 same" $? 0
cmp -s "$F/emmc-boot0.img" "$DEV/mmcblk2boot0"
ok "boot0 same" $? 0
ok "info copied" "$(key "$F/emmc-info.txt" disk)" mmcblk2
ok "wakelock let go" "$(cat "$SYS/power/wake_unlock" 2>/dev/null)" andr36oid-emmc

# pieces of 3 MB for FAT32
rm -rf "$DEST/eMMC backup"
run action=dump items=mmcblk2 "dest=$DEST" split_mb=3 keep_free_mb=0
F=$(key "$S" folder)
ok "split state" "$(key "$S" state)" "done"
ok "split pieces" "$(find "$F" -name 'emmc-whole.img.*' | wc -l)" 7
ok "split piece size" "$(stat -c %s "$F/emmc-whole.img.001")" $((3 * 1048576))
cat "$F"/emmc-whole.img.0* | cmp -s - "$DEV/mmcblk2"
ok "split joins" $? 0

# not enough space: nothing written
rm -rf "$DEST/eMMC backup"
run action=dump items=mmcblk2 "dest=$DEST" split_mb=0 keep_free_mb=999999999
ok "nospace state" "$(key "$S" state)" failed
ok "nospace error" "$(key "$S" error)" nospace
ok "nospace nothing" "$(find "$DEST" -mindepth 1 | wc -l)" 0

# stop asked before the first chunk (the folder's date stamp asks it, once)
cat >"$T/bin/date" <<EOF
#!/bin/sh
[ "\$1" = +%Y%m%d-%H%M%S ] && : >"$DIR/stop"
/bin/date "\$@"
EOF
chmod +x "$T/bin/date"
run action=dump items=mmcblk2 "dest=$DEST" split_mb=0 keep_free_mb=0
ok "stop state" "$(key "$S" state)" stopped
ok "stop file gone" "$(ls "$DIR/stop" 2>/dev/null)" ""
rm -f "$T/bin/date"

# not a part of the eMMC
run action=dump items=mmcblk0 "dest=$DEST" split_mb=0 keep_free_mb=0
ok "other disk refused" "$(key "$S" error)" noemmc

# read speed, with the speed tool built for the host
EMMC_EMMCSPEED=$SPEED EMMC_SPEED_SECONDS=1 run action=speed
ok "speed state" "$(key "$S" state)" "done"
ok "speed result kept" "$(key "$DIR/result-speed" state)" "done"
ok "speed seq read" "$(key "$S" seq_bytes | grep -c '^[1-9][0-9]*$')" 1
ok "speed random read" "$(key "$S" rr_ops | grep -c '^[1-9][0-9]*$')" 1
ok "speed left the eMMC alone" "$(cmp -s "$DEV/mmcblk2" "$T/mmcblk2.orig"; echo $?)" 0
EMMC_EMMCSPEED=/bin/false run action=speed
ok "speed tool fails" "$(key "$S" error)" read

# no eMMC switched on
rm "$SYS/block/mmcblk2"
run action=info
ok "none state" "$(key "$I" state)" none
run action=dump items=mmcblk2 "dest=$DEST" split_mb=0 keep_free_mb=0
ok "none dump" "$(key "$S" error)" noemmc

echo "[$SH] $checks checks, $fails failed"
[ "$fails" -eq 0 ]
