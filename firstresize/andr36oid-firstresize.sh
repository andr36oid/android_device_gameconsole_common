#!/system/bin/sh
# First start on a freshly flashed card. The image ends right after the system
# partition, so this adds userdata (p6) and EASYROMS (p7) over the rest of the
# card, before Android mounts /data, while the boot animation shows "Resizing SD
# card ..." (sys.andr36oid.resizing, cleared by init at post-fs-data). No restart:
# andr36oid-addpart tells the running kernel about the two new partitions, and the
# by-name link for userdata is made here, because the kernel only names the
# partitions it reads from the table at start. userdata is left blank (first MiB zeroed) and Android formats
# it itself when it mounts it (fstab: formattable). Later starts see p6 and stop.
#
# BOOT/resize-pending is there while the job is half done: a start that finds it
# (power lost in the middle) blanks userdata and formats EASYROMS again.
# What happened goes to the kernel log and BOOT/resize-log.txt.
#
# AUTOAPK, the bundled apps: the image has it as p12 (FAT32) right after system,
# where userdata goes. The first start makes it again as p13 (exFAT) at the end of
# userdata's space, copies the files over, and only then sets up userdata. Every
# start mounts it read-only at /mnt/andr36oid-apps for the setup's app page and the
# installer (gameconsole-emu.sh). Once the installer is done with it, it leaves
# /metadata/andr36oid/apps-done, and the next start removes p13 and grows userdata
# over it (resize.f2fs), "Reclaiming SD card space ..." on the boot animation. Only
# when fsck finds userdata fine, otherwise nothing changes and the start after tries
# again. BOOT/reclaim-pending is there while that is under way.
#
# Paths can be moved for the host test (tests/firstresize-test.sh).

BYNAME=${RESIZE_BYNAME:-/dev/block/platform/ff370000.dwmmc/by-name}
SYS=${RESIZE_SYS:-/sys/block}
DEV=${RESIZE_DEV:-/dev/block}
KMSG=${RESIZE_KMSG:-/dev/kmsg}
BOOT_MNT=${RESIZE_BOOT_MNT:-/mnt/andr36oid-resize-boot}
ADDPART=${RESIZE_ADDPART:-/system/bin/andr36oid-addpart}
APPS_MNT=${RESIZE_APPS_MNT:-/mnt/andr36oid-apps}
APPS_DONE=${RESIZE_APPS_DONE:-/metadata/andr36oid/apps-done}
RESIZE_F2FS=${RESIZE_F2FS:-/system/bin/resize.f2fs}
FSCK_F2FS=${RESIZE_FSCK_F2FS:-/system/bin/fsck.f2fs}
TMP_MNT=${RESIZE_TMP_MNT:-/mnt/andr36oid-resize-apps}
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

APPS_SECTORS=4194304    # 2 GiB, as in the image (mkimg.sh)

part_first() { cat "$SYS/$disk/${disk}p$1/start"; }
part_last() { echo $(( $(part_first "$1") + $(cat "$SYS/$disk/${disk}p$1/size") - 1 )); }

wait_node() { # <number>
    i=0
    while [ ! -b "$DEV/${disk}p$1" ] && [ $i -lt 50 ]; do
        sleep 0.1
        i=$((i + 1))
    done
    [ -b "$DEV/${disk}p$1" ]
}

# AUTOAPK read-only at $APPS_MNT, readable by everyone (the setup's app page is an app)
apps_mount() {
    [ -e "$SYS/$disk/${disk}p13" ] || return 0
    wait_node 13 || return 0
    mkdir -p "$APPS_MNT"
    mountpoint -q "$APPS_MNT" 2>/dev/null && return 0
    out=$(mount -t exfat -o ro,nosuid,nodev,noexec,uid=0,gid=0,fmask=0022,dmask=0022 \
        "$DEV/${disk}p13" "$APPS_MNT" 2>&1) || log "AUTOAPK doesn't mount: $out"
}

# apps_move ; first start: AUTOAPK (p12, right after system) is made again as p13 at
# the end of userdata's space, USERDATA_END moves down. Returns 1 if that didn't work;
# the card is set up without the apps then.
apps_move() {
    APPS_START=$(( (USERDATA_END + 1 - APPS_SECTORS) / 2048 * 2048 ))
    APPS_END=$USERDATA_END
    if [ "$APPS_START" -le $((USERDATA_START + APPS_SECTORS)) ]; then
        log "no room for AUTOAPK behind userdata"
        return 1
    fi
    if [ -e "$SYS/$disk/${disk}p13" ]; then
        # left by a start that was cut off
        "$ADDPART" -d "$DEV/$disk" 13 > /dev/null 2>&1
    fi
    sgdisk --delete=13 "$DEV/$disk" > /dev/null 2>&1
    out=$(sgdisk --set-alignment=1 --new="13:$APPS_START:$APPS_END" --change-name=13:AUTOAPK \
        --typecode=13:0700 "$DEV/$disk" 2>&1) || { log "sgdisk for AUTOAPK failed: $out"; return 1; }
    out=$("$ADDPART" "$DEV/$disk" 13 "$APPS_START" "$APPS_END" 2>&1) || { log "$out"; return 1; }
    wait_node 13 || { log "no $DEV/${disk}p13"; return 1; }
    out=$(mkfs.exfat -L AUTOAPK "$DEV/${disk}p13" 2>&1) || { log "mkfs.exfat AUTOAPK failed: $out"; return 1; }
    mkdir -p "$TMP_MNT/old" "$TMP_MNT/new"
    out=$(mount -t vfat -o ro,utf8 "$DEV/${disk}p12" "$TMP_MNT/old" 2>&1) ||
        { log "the image's AUTOAPK doesn't mount: $out"; return 1; }
    out=$(mount -t exfat "$DEV/${disk}p13" "$TMP_MNT/new" 2>&1) ||
        { log "the new AUTOAPK doesn't mount: $out"; umount "$TMP_MNT/old"; return 1; }
    # cp's own errors are the check: no read-back, that doubled the time. A damaged
    # APK is refused by the package manager when it's installed.
    ok=1
    t0=$(date +%s)
    cp -R "$TMP_MNT/old/." "$TMP_MNT/new/" 2>/dev/null || ok=0
    sync
    t1=$(date +%s)
    files=$(cd "$TMP_MNT/old" && find . -type f | wc -l)
    umount "$TMP_MNT/new"
    umount "$TMP_MNT/old"
    [ $ok = 1 ] || { log "copying AUTOAPK failed"; return 1; }
    log "AUTOAPK: $files files moved to ${APPS_START}s-${APPS_END}s in $((t1 - t0)) s"
    USERDATA_END=$((APPS_START - 1))
}

# f2fs_ok ; userdata's file system is fine: a fixing pass, then a read-only one that
# must find nothing. fsck.f2fs exits 0 when it found and fixed errors too, and 1 only
# when it can't read the file system, so the second pass is judged by its output.
# The last start may have been a crash or a pulled battery, not a clean shutdown.
f2fs_ok() {
    out=$("$FSCK_F2FS" -f "$DEV/${disk}p6" < /dev/null 2>&1)
    r=$?
    log "fsck.f2fs: exit $r, $(echo "$out" | grep -c 'Fail\|Error\|FIX') problem lines"
    [ $r = 0 ] || return 1
    out=$("$FSCK_F2FS" -f --dry-run "$DEV/${disk}p6" < /dev/null 2>&1)
    r=$?
    if [ $r != 0 ] || echo "$out" | grep -q '\[Fail\]'; then
        log "fsck.f2fs still finds problems (exit $r): $(echo "$out" | grep '\[Fail\]' | head -3)"
        return 1
    fi
}

# apps_reclaim ; userdata grows over AUTOAPK once the apps are installed. Only after
# fsck finds userdata fine, anything off and it backs out with nothing changed and
# tries again next start. Then the partition table and the kernel, then the file
# system (resize.f2fs). A start cut off in the middle (BOOT/reclaim-pending) does the
# file system part again, after another fsck.
apps_reclaim() {
    first=$(part_first 6)
    if [ -e "$SYS/$disk/${disk}p13" ] && [ "$(part_last 6)" != $(( $(part_first 13) - 1 )) ]; then
        log "AUTOAPK isn't right behind userdata, leaving both alone"
        return 1
    fi
    f2fs_ok || { log "userdata needs a look first, AUTOAPK stays for now"; return 1; }
    if [ -e "$SYS/$disk/${disk}p13" ]; then
        last=$(part_last 13)
        : > "$BOOT/reclaim-pending"
        sync
        out=$(sgdisk --delete=13 --delete=6 --set-alignment=1 --new="6:$first:$last" \
            --change-name=6:userdata --typecode=6:8300 --attributes=6:set:63 "$DEV/$disk" 2>&1) ||
            { log "sgdisk failed: $out"; rm -f "$BOOT/reclaim-pending"; return 1; }
        sgdisk --print "$DEV/$disk" 2>/dev/null | grep -q " $first *$last .* userdata\$" ||
            { log "userdata isn't ${first}s-${last}s in the table after sgdisk: $out"; return 1; }
        out=$("$ADDPART" -d "$DEV/$disk" 13 2>&1) || { log "$out"; return 1; }
        out=$("$ADDPART" -r "$DEV/$disk" 6 "$first" "$last" 2>&1) || { log "$out"; return 1; }
        log "AUTOAPK removed, userdata now ${first}s-${last}s"
    fi
    out=$("$RESIZE_F2FS" "$DEV/${disk}p6" < /dev/null 2>&1)
    r=$?
    log "resize.f2fs: exit $r, $(echo "$out" | tail -1)"
    [ $r = 0 ] || return 1
    rm -f "$BOOT/reclaim-pending" "$APPS_DONE"
    log "userdata has AUTOAPK's space now"
}

if [ -e "$SYS/$disk/${disk}p6" ]; then
    # Set up already, unless a start before was cut off in the middle
    boot_mount ro || { apps_mount; exit 0; }
    if [ -e "$BOOT/reclaim-pending" ] ||
            { [ -e "$SYS/$disk/${disk}p13" ] && [ -e "$APPS_DONE" ]; }; then
        boot_umount
        boot_mount rw || exit 0
        setprop sys.andr36oid.resizing 2 2>/dev/null
        log "the apps are installed, giving AUTOAPK's space to userdata"
        apps_reclaim
        boot_umount
        apps_mount
        exit 0
    fi
    if [ ! -e "$BOOT/resize-pending" ]; then
        boot_umount
        apps_mount
        exit 0
    fi
    boot_umount
    boot_mount rw || exit 0
    setprop sys.andr36oid.resizing 1 2>/dev/null
    log "a resize was cut off, setting up userdata and EASYROMS again"
else
    # Only on the layout the image makes: system ends right before userdata, and
    # nothing after it but the image's AUTOAPK right behind system
    p2_end=$(( $(cat "$SYS/$disk/${disk}p2/start") + $(cat "$SYS/$disk/${disk}p2/size") - 1 ))
    if [ "$p2_end" != $((USERDATA_START - 1)) ] || [ -e "$SYS/$disk/${disk}p7" ] ||
            { [ -e "$SYS/$disk/${disk}p12" ] && [ "$(part_first 12)" != "$USERDATA_START" ]; }; then
        log "$disk has no userdata and an unknown layout (system ends at ${p2_end}s), leaving it alone"
        exit 0
    fi
    boot_mount rw || { log "BOOT doesn't mount, not resizing"; exit 0; }
    setprop sys.andr36oid.resizing 1 2>/dev/null

    CARD_SECTORS=$(cat "$SYS/$disk/size")
    NOROMS=0
    [ -e "$BOOT/.noroms" ] && NOROMS=1
    WISH=""
    [ -f "$BOOT/userdata-size.txt" ] && WISH=$(tr -d ' \r\n' < "$BOOT/userdata-size.txt" | tr 'A-Z' 'a-z')
    WISH=${WISH%gib}; WISH=${WISH%gb}; WISH=${WISH%g}
    plan_userdata "$CARD_SECTORS" "$WISH" > /dev/null
    LAST=$((CARD_SECTORS - 34))
    # EASYROMS starts where userdata was planned to end, also when AUTOAPK takes the
    # end of userdata's space for a while
    ROMS_START=$((USERDATA_END + 1))
    log "card ${CARD_SECTORS}s, wish '$WISH', userdata ${USERDATA_START}s-${USERDATA_END}s, NOROMS=$NOROMS"

    : > "$BOOT/resize-pending"
    sync

    # The image's AUTOAPK sits where userdata goes: moved first
    if [ -e "$SYS/$disk/${disk}p12" ] && [ "$(part_first 12)" = "$USERDATA_START" ]; then
        sgdisk --move-second-header "$DEV/$disk" > /dev/null 2>&1
        if ! apps_move; then
            log "setting up without the bundled apps"
            [ -e "$SYS/$disk/${disk}p13" ] && "$ADDPART" -d "$DEV/$disk" 13 > /dev/null 2>&1
            sgdisk --delete=13 "$DEV/$disk" > /dev/null 2>&1
        fi
        out=$(sgdisk --delete=12 "$DEV/$disk" 2>&1) || { log "sgdisk --delete=12 failed: $out"; boot_umount; exit 1; }
        out=$("$ADDPART" -d "$DEV/$disk" 12 2>&1) || { log "$out"; boot_umount; exit 1; }
    elif [ -e "$SYS/$disk/${disk}p13" ] && [ "$(part_first 13)" -gt "$USERDATA_START" ] &&
            [ "$(part_last 13)" -le "$USERDATA_END" ]; then
        # A start cut off after AUTOAPK was moved: userdata ends before it
        USERDATA_END=$(( $(part_first 13) - 1 ))
        log "AUTOAPK was moved already, userdata ends at ${USERDATA_END}s"
    fi

    # The backup table moves to the end of the card first, sgdisk only places
    # partitions inside the table's area. Alignment 1: exactly these sectors.
    # sgdisk warns that the kernel keeps the old table; andr36oid-addpart is for that.
    # Android's sgdisk only knows the long options, and ignores what it doesn't know.
    # Bit 63 on userdata: Windows gives it no drive letter, so no "format disk?" popup.
    out=$(sgdisk --move-second-header "$DEV/$disk" 2>&1) ||
        { log "sgdisk --move-second-header failed: $out"; boot_umount; exit 1; }
    set -- --set-alignment=1 --new="6:$USERDATA_START:$USERDATA_END" \
        --change-name=6:userdata --typecode=6:8300 --attributes=6:set:63
    [ "$NOROMS" = 1 ] || set -- "$@" --new="7:$ROMS_START:$LAST" \
        --change-name=7:EASYROMS --typecode=7:0700
    out=$(sgdisk "$@" "$DEV/$disk" 2>&1) || { log "sgdisk failed: $out"; boot_umount; exit 1; }
    sgdisk --print "$DEV/$disk" 2>/dev/null | grep -q " $USERDATA_START .* userdata\$" ||
        { log "userdata isn't in the table after sgdisk: $out"; boot_umount; exit 1; }

    out=$("$ADDPART" "$DEV/$disk" 6 "$USERDATA_START" "$USERDATA_END" 2>&1) ||
        { log "$out"; boot_umount; exit 1; }
    if [ "$NOROMS" != 1 ]; then
        out=$("$ADDPART" "$DEV/$disk" 7 "$ROMS_START" "$LAST" 2>&1) ||
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
apps_mount
exit 0
