#!/system/bin/sh
# First start on a freshly flashed card. The image ends right after the system
# partition, so this adds userdata (p6) and EASYROMS (p7) over the rest of the
# card, before Android mounts /data. There is no restart: andr36oid-addpart tells
# the running kernel about the two new partitions, and the by-name link for
# userdata is made here, because the kernel only names the partitions it reads from
# the table at start. userdata is left blank (first MiB zeroed) and Android formats
# it itself when it mounts it (fstab: formattable). Later starts see p6 and stop.
#
# BOOT/resize-pending is there while the job is half done: a start that finds it
# (power lost in the middle) blanks userdata and formats EASYROMS again.
# What happened goes to the kernel log and BOOT/resize-log.txt.
#
# Paths can be moved for the host test (tests/firstresize-test.sh).

BYNAME=${RESIZE_BYNAME:-/dev/block/platform/ff370000.dwmmc/by-name}
SYS=${RESIZE_SYS:-/sys/block}
DEV=${RESIZE_DEV:-/dev/block}
KMSG=${RESIZE_KMSG:-/dev/kmsg}
BOOT_MNT=${RESIZE_BOOT_MNT:-/mnt/andr36oid-resize-boot}
ADDPART=${RESIZE_ADDPART:-/system/bin/andr36oid-addpart}
BOOT=

log() {
    echo "andr36oid-resize: $*" > "$KMSG" 2>/dev/null
    [ -n "$BOOT" ] && echo "$*" >> "$BOOT/resize-log.txt"
}

boot_mount() { # <ro|rw>
    mkdir -p "$BOOT_MNT"
    mount -t vfat -o "$1,noatime" "$BYNAME/BOOT" "$BOOT_MNT" 2>/dev/null || return 1
    BOOT=$BOOT_MNT
}
boot_umount() {
    [ -n "$BOOT" ] || return 0
    sync
    umount "$BOOT_MNT"
    BOOT=
}

# userdata size. Order of choice:
#  - .noroms on BOOT: the whole card goes to userdata, no EASYROMS;
#  - userdata-size.txt on BOOT (written by the SD card flasher): a whole
#    number of GiB, or "all";
#  - otherwise half of the free space, at most 64 GiB.
# Android always gets at least 16 GiB. If that would leave EASYROMS with less
# than 1 GiB (every card of 16 GB or less), userdata gets the whole card.
# BEGIN plan_userdata
GIB=2097152
USERDATA_START=5701009
plan_userdata() {
    # $1: card size in 512-byte sectors, $2: wish ("" / "all" / GiB number)
    # Sets USERDATA_END and NOROMS. The backup GPT needs the last 34 sectors.
    _last=$(($1 - 34))
    _free=$((_last - USERDATA_START + 1))
    case "$2" in
        all) NOROMS=1 ;;
        "") _want=$((_free / 2))
            [ "$_want" -gt $((64 * GIB)) ] && _want=$((64 * GIB)) ;;
        *[!0-9]*) echo "Ignoring bad userdata size: $2"
            plan_userdata "$1" ""; return ;;
        *) _want=$(($2 * GIB)) ;;
    esac
    [ "$NOROMS" != 1 ] && [ "$_want" -lt $((16 * GIB)) ] && _want=$((16 * GIB))
    if [ "$NOROMS" != 1 ]; then
        # End userdata so that EASYROMS starts on a 1 MiB boundary
        USERDATA_END=$(( (USERDATA_START + _want) / 2048 * 2048 - 1 ))
        [ $((_last - USERDATA_END)) -lt "$GIB" ] && NOROMS=1
    fi
    [ "$NOROMS" = 1 ] && USERDATA_END=$_last
}
# END plan_userdata

# The card Android started from: the disk that holds the system partition
sysdev=$(readlink -f "$BYNAME/system") || exit 0
disk=${sysdev##*/}
disk=${disk%p[0-9]*}
[ -e "$SYS/$disk/${disk}p2" ] || exit 0

if [ -e "$SYS/$disk/${disk}p6" ]; then
    # Set up already, unless a start before was cut off in the middle
    boot_mount ro || exit 0
    if [ ! -e "$BOOT/resize-pending" ]; then
        boot_umount
        exit 0
    fi
    boot_umount
    boot_mount rw || exit 0
    log "a resize was cut off, setting up userdata and EASYROMS again"
else
    # Only on the layout the image makes: system ends right before userdata, and
    # nothing after it
    p2_end=$(( $(cat "$SYS/$disk/${disk}p2/start") + $(cat "$SYS/$disk/${disk}p2/size") - 1 ))
    if [ "$p2_end" != $((USERDATA_START - 1)) ] || [ -e "$SYS/$disk/${disk}p7" ]; then
        log "$disk has no userdata and an unknown layout (system ends at ${p2_end}s), leaving it alone"
        exit 0
    fi
    boot_mount rw || { log "BOOT doesn't mount, not resizing"; exit 0; }

    CARD_SECTORS=$(cat "$SYS/$disk/size")
    NOROMS=0
    [ -e "$BOOT/.noroms" ] && NOROMS=1
    WISH=""
    [ -f "$BOOT/userdata-size.txt" ] && WISH=$(tr -d ' \r\n' < "$BOOT/userdata-size.txt" | tr 'A-Z' 'a-z')
    WISH=${WISH%gib}; WISH=${WISH%gb}; WISH=${WISH%g}
    plan_userdata "$CARD_SECTORS" "$WISH" > /dev/null
    LAST=$((CARD_SECTORS - 34))
    log "card ${CARD_SECTORS}s, wish '$WISH', userdata ${USERDATA_START}s-${USERDATA_END}s, NOROMS=$NOROMS"

    : > "$BOOT/resize-pending"
    sync

    # The backup table moves to the end of the card first, sgdisk only places
    # partitions inside the table's area. -a 1: exactly these sectors.
    # sgdisk warns that the kernel keeps the old table; andr36oid-addpart is for that.
    out=$(sgdisk -e "$DEV/$disk" 2>&1) || { log "sgdisk -e failed: $out"; boot_umount; exit 1; }
    set -- -a 1 -n "6:$USERDATA_START:$USERDATA_END" -c 6:userdata -t 6:8300
    [ "$NOROMS" = 1 ] || set -- "$@" -n "7:$((USERDATA_END + 1)):$LAST" -c 7:EASYROMS -t 7:0700
    out=$(sgdisk "$@" "$DEV/$disk" 2>&1) || { log "sgdisk failed: $out"; boot_umount; exit 1; }

    out=$("$ADDPART" "$DEV/$disk" 6 "$USERDATA_START" "$USERDATA_END" 2>&1) ||
        { log "$out"; boot_umount; exit 1; }
    if [ "$NOROMS" != 1 ]; then
        out=$("$ADDPART" "$DEV/$disk" 7 $((USERDATA_END + 1)) "$LAST" 2>&1) ||
            { log "$out"; boot_umount; exit 1; }
    fi
fi

# ueventd makes the device nodes for the new partitions
i=0
while [ ! -b "$DEV/${disk}p6" ] && [ $i -lt 50 ]; do
    sleep 0.1
    i=$((i + 1))
done
[ -b "$DEV/${disk}p6" ] || { log "no $DEV/${disk}p6"; boot_umount; exit 1; }
[ -e "$BYNAME/userdata" ] || ln -s "$DEV/${disk}p6" "$BYNAME/userdata"

# Blank start: Android formats userdata when it finds it empty. 1 MiB covers both
# f2fs superblocks of a file system left there by something else.
dd if=/dev/zero of="$DEV/${disk}p6" bs=1048576 count=1 conv=fsync 2>/dev/null ||
    { log "could not blank userdata"; boot_umount; exit 1; }

if [ -e "$SYS/$disk/${disk}p7" ]; then
    i=0
    while [ ! -b "$DEV/${disk}p7" ] && [ $i -lt 50 ]; do
        sleep 0.1
        i=$((i + 1))
    done
    out=$(mkfs.exfat -L EASYROMS "$DEV/${disk}p7" 2>&1) ||
        { log "mkfs.exfat failed: $out"; boot_umount; exit 1; }
fi

rm -f "$BOOT/resize-pending"
log "done, userdata is formatted by Android when it mounts it"
boot_umount
exit 0
