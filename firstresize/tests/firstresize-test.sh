#!/bin/bash
# End-to-end test for ../andr36oid-firstresize.sh on a Linux PC, as root: a sparse
# 64 GB "card" on a loop device with the image's partition layout, its system
# partition mounted like on the console, then the script runs with its paths moved.
# Needs sgdisk, mkfs.vfat, mkfs.ext4, mkfs.exfat (exfatprogs), losetup, gcc and udev.
# Run: sudo bash tests/firstresize-test.sh
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d)
LOOP=
fails=0
cleanup() {
    umount "$T/sys-mnt" "$T/boot" 2>/dev/null
    [ -n "$LOOP" ] && losetup -d "$LOOP"
    rm -rf "$T"
}
trap cleanup EXIT
gcc -Wall -O2 -o "$T/addpart" "$HERE/andr36oid-addpart.c" || exit 1
# Android's sgdisk (external/gptfdisk, android_popt.cc) only takes long options and
# quietly skips short ones, so the script gets an sgdisk that refuses them
mkdir -p "$T/bin"
cat > "$T/bin/sgdisk" <<'S'
#!/bin/sh
for a in "$@"; do
    case $a in --*) ;; -*) echo "sgdisk: short option $a, Android's sgdisk ignores it" >&2; exit 0 ;; esac
done
exec /usr/sbin/sgdisk "$@"
S
chmod +x "$T/bin/sgdisk"

check() { # <what> <command...>
    if "${@:2}" >/dev/null 2>&1; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi
}

# new_card <GB> : the image's table (mkimg.sh) in a 3 GiB image, then the card is
# made bigger, so the backup table sits in the middle like on a flashed card
new_card() {
    [ -n "$LOOP" ] && { umount "$T/sys-mnt" 2>/dev/null; losetup -d "$LOOP"; }
    rm -f "$T/card"
    truncate -s 3G "$T/card"
    sgdisk -a 1 -o \
        -n 1:32768:557055 -c 1:BOOT -n 2:1081344:5701008 -c 2:system \
        -n 3:64:16383 -c 3:idbloader -n 4:16384:24575 -c 4:uboot -n 5:24576:32767 -c 5:trust \
        -n 8:557056:884735 -c 8:vendor -n 9:884736:917503 -c 9:metadata \
        -n 10:917504:925695 -c 10:misc -n 11:925696:1081343 -c 11:cache "$T/card" >/dev/null
    truncate -s "${1}000000000" "$T/card"
    # old data where userdata will go
    printf 'old card data' | dd of="$T/card" bs=512 seek=5701009 conv=notrunc status=none
    LOOP=$(losetup -f --show -P "$T/card")
    udevadm settle
    mkfs.vfat -n BOOT "${LOOP}p1" >/dev/null
    mkfs.ext4 -q -F "${LOOP}p2"
    mkdir -p "$T/sys-mnt" "$T/byname"
    mount "${LOOP}p2" "$T/sys-mnt"
    rm -f "$T/byname"/*
    ln -s "${LOOP}p2" "$T/byname/system"
    ln -s "${LOOP}p1" "$T/byname/BOOT"
}
run() {
    PATH=$T/bin:$PATH RESIZE_BYNAME=$T/byname RESIZE_DEV=/dev RESIZE_SYS=/sys/block RESIZE_KMSG=$T/kmsg \
        RESIZE_BOOT_MNT=$T/boot RESIZE_ADDPART=$T/addpart sh "$HERE/andr36oid-firstresize.sh"
    echo "  (exit $?, log: $(tail -1 "$T/kmsg" 2>/dev/null))"
}
boot() { # <cmd> : runs a command with BOOT mounted at $T/boot
    mount "${LOOP}p1" "$T/boot" && eval "$1"; local r=$?; umount "$T/boot"; return $r
}
part_is() { # <n> <first> <last> <name>
    sgdisk -i "$1" "$T/card" | grep -q "First sector: $2 " &&
    sgdisk -i "$1" "$T/card" | grep -q "Last sector: $3 " &&
    sgdisk -i "$1" "$T/card" | grep -q "Partition name: '$4'"
}
blank() { [ "$(dd if="$1" bs=1048576 count=1 status=none | tr -d '\0' | wc -c)" = 0 ]; }

echo "== 64 GB card, default sizes"
new_card 64
d=${LOOP##*/}
run
SECT=$((64000000000 / 512))
END=$(( (5701009 + (SECT - 34 - 5701009 + 1) / 2) / 2048 * 2048 - 1 ))
check "userdata p6 in the table" part_is 6 5701009 $END userdata
check "EASYROMS p7 to the end" part_is 7 $((END + 1)) $((SECT - 34)) EASYROMS
check "backup table at the end" sh -c "sgdisk -v '$T/card' | grep -q 'No problems found'"
check "kernel has p6" test -e /sys/block/$d/${d}p6
check "kernel has p7" test -e /sys/block/$d/${d}p7
check "p6 size in the kernel" test "$(cat /sys/block/$d/${d}p6/size)" = $((END - 5701009 + 1))
check "by-name/userdata" test "$(readlink "$T/byname/userdata")" = "/dev/${d}p6"
check "userdata blanked" blank "/dev/${d}p6"
check "EASYROMS is exFAT" sh -c "blkid -o export /dev/${d}p7 | grep -qx 'TYPE=exfat'"
check "EASYROMS label" sh -c "blkid -o export /dev/${d}p7 | grep -qx 'LABEL=EASYROMS'"
check "system still mounted" mountpoint -q "$T/sys-mnt"
check "no resize-pending" boot "test ! -e $T/boot/resize-pending"
check "resize-log.txt" boot "grep -q 'done' $T/boot/resize-log.txt"

echo "== next start: nothing to do"
printf 'Android data' | dd of="/dev/${d}p6" conv=notrunc,fsync status=none
lines=$(boot "wc -l < $T/boot/resize-log.txt")
run
check "userdata untouched" test "$(dd if=/dev/${d}p6 bs=12 count=1 status=none)" = "Android data"
check "no new log lines" boot "test \$(wc -l < $T/boot/resize-log.txt) = $lines"

echo "== start after a cut-off resize (resize-pending on BOOT)"
boot ": > $T/boot/resize-pending"
run
check "userdata blanked again" blank "/dev/${d}p6"
check "pending removed" boot "test ! -e $T/boot/resize-pending"

echo "== 64 GB card, flasher wish of 20 GiB"
new_card 64
d=${LOOP##*/}
boot "echo 20GiB > $T/boot/userdata-size.txt"
run
END=$(( (5701009 + 20 * 2097152) / 2048 * 2048 - 1 ))
check "userdata 20 GiB" part_is 6 5701009 $END userdata
check "EASYROMS after it" part_is 7 $((END + 1)) $((SECT - 34)) EASYROMS

echo "== 64 GB card with .noroms"
new_card 64
d=${LOOP##*/}
boot ": > $T/boot/.noroms"
run
check "userdata to the end" part_is 6 5701009 $((SECT - 34)) userdata
check "no p7" sh -c "! sgdisk -i 7 '$T/card' | grep -q 'Partition name'"
check "kernel has no p7" test ! -e /sys/block/$d/${d}p7
check "userdata blanked" blank "/dev/${d}p6"

echo "== 16 GB card: no EASYROMS"
new_card 16
d=${LOOP##*/}
run
check "userdata to the end" part_is 6 5701009 $((16000000000 / 512 - 34)) userdata
check "no p7" test ! -e /sys/block/$d/${d}p7

echo "== card with another layout is left alone"
new_card 64
d=${LOOP##*/}
sgdisk -d 2 -n 2:1081344:5000000 -c 2:system "$T/card" >/dev/null
losetup -d "$LOOP"; LOOP=$(losetup -f --show -P "$T/card"); udevadm settle
d=${LOOP##*/}
ln -sf "${LOOP}p2" "$T/byname/system"; ln -sf "${LOOP}p1" "$T/byname/BOOT"
run
check "no p6" test ! -e /sys/block/$d/${d}p6

echo
[ $fails = 0 ] && echo "all passed" || echo "$fails failed"
[ $fails = 0 ]
