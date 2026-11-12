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
# installer (gameconsole-setup). Once the installer is done with it, it leaves
# /metadata/andr36oid/apps-done, and the next start removes p13 and grows userdata
# over it (resize.f2fs), "Cleaning up first boot partitions ..." on the boot animation. Only
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
FSYNC=${RESIZE_FSYNC:-/system/bin/andr36oid-fsync}
SUPPORT=${RESIZE_SUPPORT:-/system/bin/andr36oid-erofs-support}
CLASS_SYS=${RESIZE_CLASS_SYS:-/sys/class/block}
APPS_MNT=${RESIZE_APPS_MNT:-/mnt/andr36oid-apps}
APPS_DONE=${RESIZE_APPS_DONE:-/metadata/andr36oid/apps-done}
RESIZE_F2FS=${RESIZE_F2FS:-/system/bin/resize.f2fs}
FSCK_F2FS=${RESIZE_FSCK_F2FS:-/system/bin/fsck.f2fs}
TMP_MNT=${RESIZE_TMP_MNT:-/mnt/andr36oid-resize-apps}
BOOT=

log() {
    echo "andr36oid-resize: $*" > "$KMSG" 2>/dev/null
    if [ -n "$BOOT" ]; then
        echo "$*" >> "$BOOT/resize-log.txt"
        # Flush just this vfat file (not a global sync) so the last marker
        # survives a cold power-off during a hang - the only post-mortem on
        # a no-ADB, no-UART board. See andr36oid-fsync.c.
        [ -x "$FSYNC" ] && "$FSYNC" "$BOOT/resize-log.txt" 2>/dev/null || :
    fi
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
# system ends at 5701008s; userdata starts on the next 1 MiB boundary (5701632 = 2784 * 2048).
# Cards flashed from images before the alignment have AUTOAPK (and so userdata) at
# 5701009 instead: the layout check below takes the start from the card's own table.
SYSTEM_END=5701008
USERDATA_START=5701632
OLD_USERDATA_START=5701009
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

# Only on a start from the SD card. andr36oid installed on the built-in eMMC got its
# partitions from the installer, and an andr36oid card in the slot is just a card there.
case " $(cat "${RESIZE_CMDLINE:-/proc/cmdline}" 2>/dev/null) " in
*" androidboot.boot_devices=ff390000.dwmmc "*) exit 0 ;;
esac

# The card Android started from: the disk that holds the system partition
sysdev=$(readlink -f "$BYNAME/system") || exit 0
disk=${sysdev##*/}
disk=${disk%p[0-9]*}
[ -e "$SYS/$disk/${disk}p2" ] || exit 0

layout_refuse() {
    setprop sys.andr36oid.storage_layout_error 1 2>/dev/null || :
    log "Unsupported partition table: this card appears to have been written with a tool that changed the partition table. Rewrite it with the andr36oid SD flasher or a raw image writer. No partitions were repaired or formatted."
    # Remount only our BOOT mount to persist the explanation, including when a
    # later prepared-layout check failed while the inventory was mounted ro.
    [ -z "$BOOT" ] || boot_umount
    if boot_mount rw; then
        log "Partition table mismatch: rewrite this card with the andr36oid SD flasher or a raw writer."
        [ -z "${LAYOUT_DETAILS:-}" ] || log "$LAYOUT_DETAILS"
        boot_umount
    fi
    return 1
}

audit_layout() {
    LAYOUT_DETAILS=$("$SUPPORT" layout-audit "$DEV/$disk" "$CLASS_SYS" "$@" 2>&1) || { layout_refuse; return 1; }
}
# Do this before interpreting p6 as userdata, p7 as EASYROMS or p13 as AUTOAPK.
# The helper reads GPT/types and kernel mappings; it never opens the disk rw.
audit_layout || exit 1

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
    audit_layout created || return 1
    [ -e "$SYS/$disk/${disk}p13" ] || return 0
    wait_node 13 || return 0
    mkdir -p "$APPS_MNT"
    mountpoint -q "$APPS_MNT" 2>/dev/null && return 0
    out=$(mount -t exfat -o ro,nosuid,nodev,noexec,uid=0,gid=0,fmask=0022,dmask=0022 \
        "$DEV/${disk}p13" "$APPS_MNT" 2>&1) && return 0
    fatout=$(mount -t vfat -o ro,nosuid,nodev,noexec,uid=0,gid=0,fmask=0022,dmask=0022,utf8 \
        "$DEV/${disk}p13" "$APPS_MNT" 2>&1) || log "AUTOAPK doesn't mount (exfat: $out; vfat: $fatout)"
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
    log "copying AUTOAPK (~2 GiB) to ${disk}p13 (can take a while)"
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
    log "fsck.f2fs fix pass on ${disk}p6"
    out=$("$FSCK_F2FS" -f "$DEV/${disk}p6" < /dev/null 2>&1)
    r=$?
    log "fsck.f2fs: exit $r, $(echo "$out" | grep -c 'Fail\|Error\|FIX') problem lines"
    [ $r = 0 ] || return 1
    log "fsck.f2fs verify pass on ${disk}p6"
    out=$("$FSCK_F2FS" -f --dry-run "$DEV/${disk}p6" < /dev/null 2>&1)
    r=$?
    if [ $r != 0 ] || echo "$out" | grep -q '\[Fail\]'; then
        log "fsck.f2fs still finds problems (exit $r): $(echo "$out" | grep '\[Fail\]' | head -3)"
        return 1
    fi
}

# A flasher-prepared card owns the space after system already. Its marker is
# an inventory, not permission to reformat. After reclaim it intentionally still
# describes the original p6+p13 pair; their union is the new userdata extent.
prepared_layout_valid() {
    [ -f "$BOOT/andr36oid-layout.txt" ] || return 1
    [ "$(head -n 1 "$BOOT/andr36oid-layout.txt")" = prepared-1 ] || return 1
    [ ! -e "$SYS/$disk/${disk}p12" ] && [ "$(part_last 2)" = "$SYSTEM_END" ] || return 1
    awk 'NR==1 {next} NF!=3 || $1!~/^(userdata|AUTOAPK|EASYROMS|DARKOS)$/ ||
      $2!~/^[0-9]+$/ || $3!~/^[0-9]+$/ || $2+0>$3+0 || seen[$1]++ {bad=1}
      END {exit bad || !seen["userdata"] || !seen["AUTOAPK"]}' "$BOOT/andr36oid-layout.txt" || return 1
    marker_span() { awk -v n="$1" '$1==n {print $2, $3}' "$BOOT/andr36oid-layout.txt"; }
    set -- $(marker_span userdata); [ "$#" = 2 ] || return 1
    [ "$1" = "$USERDATA_START" ] && [ "$1" = "$(part_first 6)" ] || return 1
    planned_data_end=$2
    set -- $(marker_span AUTOAPK); [ "$#" = 2 ] || return 1
    [ "$1" = $((planned_data_end+1)) ] && [ $(( $1 % 2048 )) = 0 ] || return 1
    if [ -e "$SYS/$disk/${disk}p13" ]; then
        [ "$(part_last 6)" = "$planned_data_end" ] &&
          [ "$(part_first 13)" = "$1" ] && [ "$(part_last 13)" = "$2" ] || return 1
    else
        [ "$(part_last 6)" = "$2" ] || return 1
    fi
    planned_apps_end=$2
    for item in '7:EASYROMS' '14:DARKOS'; do
        number=${item%%:*}; label=${item#*:}; span=$(marker_span "$label")
        if [ -e "$SYS/$disk/${disk}p$number" ]; then
            set -- $span; [ "$#" = 2 ] || return 1
            [ "$(part_first "$number")" = "$1" ] && [ "$(part_last "$number")" = "$2" ] || return 1
            [ "$1" -gt "$planned_apps_end" ] || return 1
        else [ -z "$span" ] || return 1; fi
    done
    expected_tail=$(( $(cat "$SYS/$disk/size") - 34 ))
    if [ -e "$SYS/$disk/${disk}p14" ]; then
        [ "$(part_last 14)" = "$expected_tail" ] && [ $(( $(part_first 14) % 2048 )) = 0 ] || return 1
        expected_tail=$(( $(part_first 14) - 1 ))
    fi
    if [ -e "$SYS/$disk/${disk}p7" ]; then
        [ "$(part_first 7)" = $((planned_apps_end+1)) ] &&
          [ $(( $(part_first 7) % 2048 )) = 0 ] && [ "$(part_last 7)" = "$expected_tail" ] || return 1
    else [ "$planned_apps_end" = "$expected_tail" ] || return 1; fi
}
reclaim_bounds_safe() {
    [ -e "$SYS/$disk/${disk}p13" ] || return 0
    # This upper bound can only reach p13, never EASYROMS or the guest root.
    for n in 7 14; do
        [ ! -e "$SYS/$disk/${disk}p$n" ] || [ "$(part_last 13)" -lt "$(part_first "$n")" ] || return 1
    done
    partname=$(sed -n 's/^PARTNAME=//p' "$SYS/$disk/${disk}p13/uevent" 2>/dev/null)
    [ -z "$partname" ] || [ "$partname" = AUTOAPK ]
}

# apps_reclaim ; userdata grows over AUTOAPK once the apps are installed. Only after
# fsck finds userdata fine, anything off and it backs out with nothing changed and
# tries again next start. Then the partition table and the kernel, then the file
# system (resize.f2fs). A start cut off in the middle (BOOT/reclaim-pending) does the
# file system part again, after another fsck.
apps_reclaim() {
    audit_layout || return 1
    reclaim_bounds_safe || { log "AUTOAPK overlaps protected storage or has the wrong name, leaving it alone"; return 1; }
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
    log "resize.f2fs growing userdata ${disk}p6 (can take a while)"
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
    if [ -e "$SYS/$disk/${disk}p14" ] || [ -e "$BOOT/andr36oid-layout.txt" ]; then
        if [ -e "$BOOT/resize-pending" ]; then
            log "prepared/DARKOS layout with legacy resize-pending: refusing destructive recovery"
            LAYOUT_DETAILS="Unexpected legacy resize-pending on a protected prepared/DARKOS layout"; layout_refuse; exit 1
        fi
        if ! prepared_layout_valid; then
            log "prepared/DARKOS layout inventory mismatch: leaving all partitions untouched"
            LAYOUT_DETAILS="Prepared layout inventory does not match the partition table"; layout_refuse; exit 1
        fi
    fi
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
    if [ -e "$SYS/$disk/${disk}p14" ]; then
        LAYOUT_DETAILS="DARKOS exists without userdata: refusing legacy resize"; layout_refuse; exit 1
    fi
    # Only on the layout the image makes: system ends right before userdata, and
    # nothing after it but the image's AUTOAPK right behind system
    p2_end=$(( $(cat "$SYS/$disk/${disk}p2/start") + $(cat "$SYS/$disk/${disk}p2/size") - 1 ))
    # Where userdata goes: where the image's AUTOAPK starts (the aligned start, or the
    # old 5701009 of earlier images); without it (a start cut off after AUTOAPK moved)
    # where the table already has userdata; else the aligned start.
    if [ -e "$SYS/$disk/${disk}p12" ]; then
        found=$(part_first 12)
    else
        found=$(sgdisk --print "$DEV/$disk" 2>/dev/null | awk '$1 == 6 { print $2 }')
    fi
    case "${found:-$USERDATA_START}" in
        "$USERDATA_START" | "$OLD_USERDATA_START") USERDATA_START=${found:-$USERDATA_START} ;;
        *) found=bad ;;
    esac
    if [ "$p2_end" != "$SYSTEM_END" ] || [ "$found" = bad ] || [ -e "$SYS/$disk/${disk}p7" ]; then
        log "$disk has no userdata and an unknown layout (system ends at ${p2_end}s), leaving it alone"
        exit 0
    fi
    boot_mount rw || { log "BOOT doesn't mount, not resizing"; exit 0; }
    if [ -e "$BOOT/andr36oid-layout.txt" ]; then
        LAYOUT_DETAILS="Prepared marker exists without userdata: refusing legacy resize"; layout_refuse; exit 1
    fi
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
    log "writing userdata/EASYROMS partition table"
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
# Bracket this with console (kmsg) markers. On a no-ADB boot (init blocked before /data,
# display loader too late) a UART trace is the only diagnosis, and an interrupted cold
# power-off here looks identical on the card to one during the EASYROMS mkfs below - these
# two markers tell them apart and pinpoint a stall to the exact step.
audit_layout created || { boot_umount; exit 1; }
log "blanking userdata ${disk}p6 (first MiB)"
dd if=/dev/zero of="$DEV/${disk}p6" bs=1048576 count=1 conv=fsync 2>/dev/null ||
    { log "could not blank userdata"; boot_umount; exit 1; }
log "userdata ${disk}p6 blanked"

if [ -e "$SYS/$disk/${disk}p7" ]; then
    i=0
    while [ ! -b "$DEV/${disk}p7" ] && [ $i -lt 50 ]; do
        sleep 0.1
        i=$((i + 1))
    done
    audit_layout created || { boot_umount; exit 1; }
    log "formatting EASYROMS ${disk}p7"
    out=$(mkfs.exfat -L EASYROMS "$DEV/${disk}p7" 2>&1) ||
        { log "mkfs.exfat failed: $out"; boot_umount; exit 1; }
    log "EASYROMS ${disk}p7 formatted"
fi

rm -f "$BOOT/resize-pending"
log "done, userdata is formatted by Android when it mounts it"
boot_umount
apps_mount
exit 0
