#!/system/bin/sh
# Foreground-only experiment. No clocks, voltage, thermal or online-core writes.
# The read-only fake root and command functions are injectable by host fixtures.
AF_ROOT=${GAME_AFFINITY_ROOT:-}
AF_STATE=${GAME_AFFINITY_STATE:-/data/misc/andr36oid-game-affinity}
AF_PROP=sys.andr36oid.gameaffinity.package
AF_BOOT=${GAME_AFFINITY_BOOT:-$(cat /proc/sys/kernel/random/boot_id)}
af_read() { cat "$AF_ROOT$1" 2>/dev/null; }
af_write() {
    case "$1" in /dev/cpuset/background/cpus|/dev/cpuset/system-background/cpus|/proc/irq/[0-9]*/smp_affinity) ;;
        *) return 1 ;; esac
    printf '%s\n' "$2" > "$AF_ROOT$1"
}
af_stamp() {
    local text
    text=$(af_read "/proc/$1/task/$2/stat") || return 1
    printf '%s\n' "${text##*) }" | awk '{print $20}'
}
af_mask() {
    local text
    text=$(taskset -p "$1" 2>/dev/null) || return 1
    text=${text##*:}
    printf '%s' "$text" | tr -d ' ,\n\t' | tr 'A-F' 'a-f' | sed 's/^0*//'
}
af_setmask() { taskset -p "$2" "$1" >/dev/null 2>&1; }
af_identity() {
    [ "$(af_stamp "$1" "$2")" = "$3" ]
}
af_thread() {
    local pid=$1 tid=$2 stamp old file="$AF_STATE/thread-$2"
    if [ -e "$file" ]; then
        # Do not fight an application/Android affinity change; retire the session.
        if [ "$(af_mask "$tid")" != c ] && [ -d "$AF_ROOT/proc/$pid/task/$tid" ]; then return 1; fi
        return 0
    fi
    stamp=$(af_stamp "$pid" "$tid") || return 0
    old=$(af_mask "$tid") || return 0
    case "$old" in ''|*[!0-9a-f]*) return 0 ;; esac
    # Threads created after admission inherit c; restore their pre-policy main
    # thread baseline on exit. Existing threads retain individual original masks.
    [ "${AF_INITIAL:-0}" = 1 ] || [ "$old" != c ] || old=$AF_MAIN_OLD
    printf '%s %s %s %s c\n' "$pid" "$tid" "$stamp" "$old" > "$file.tmp" && mv "$file.tmp" "$file" || return 1
    af_identity "$pid" "$tid" "$stamp" || return 0
    if ! af_setmask "$tid" c; then
        af_identity "$pid" "$tid" "$stamp" || return 0
        return 1
    fi
    [ "$(af_mask "$tid")" = c ] || { af_identity "$pid" "$tid" "$stamp" || return 0; return 1; }
    return 0
}
af_background() {
    local path old file inode tasks task game
    for path in /dev/cpuset/background/cpus /dev/cpuset/system-background/cpus; do
        file="$AF_STATE/cpuset-$(printf '%s' "$path" | cksum | awk '{print $1}')"
        tasks=$(af_read "${path%/cpus}/tasks") || continue
        game=0
        for task in $tasks; do
            [ ! -d "$AF_ROOT/proc/${AF_PID:-0}/task/$task" ] || game=1
        done
        # Never narrow a background cpuset containing an emulator thread.
        [ "$game" = 0 ] || continue
        [ ! -e "$file" ] || continue
        old=$(af_read "$path") || continue
        case "$old" in ''|*[!0-9,-]*) continue ;; esac
        inode=$(stat -c '%d:%i' "$AF_ROOT$path") || continue
        printf '%s %s %s 0-1\n' "$path" "$inode" "$old" > "$file.tmp" && mv "$file.tmp" "$file" || return 1
        af_write "$path" 0-1 2>/dev/null || :
    done
}
af_irqs() {
    # Device IRQs only. Never timer/IPI/GIC housekeeping or arbitrary IRQs.
    af_read /proc/interrupts | awk '
      /GIC/ && tolower($0) ~ /(mali|gpu|rga|vop|vpu|rkvdec|rkvenc|mpp|dwmmc|dw-mshc|mmc[01])/ {
        id=$1; sub(/:$/,"",id); if(id ~ /^[0-9]+$/) print id
      }'
}
af_irq_name() { af_read /proc/interrupts | awk -v id="$1:" '$1==id {print;exit}' | sed 's/^[^:]*:[[:space:]]*[0-9[:space:]]*//'; }
af_irq() {
    local irq=$1 old name file="$AF_STATE/irq-$1"
    [ ! -e "$file" ] || return 0
    old=$(af_read "/proc/irq/$irq/smp_affinity" | tr -d ', \n\t' | tr 'A-F' 'a-f') || return 0
    case "$old" in ''|*[!0-9a-f]*) return 0 ;; esac
    name=$(af_irq_name "$irq"); [ -n "$name" ] || return 0
    printf '%s\n%s\n' "$old" "$name" > "$file.tmp" && mv "$file.tmp" "$file" || return 1
    af_write "/proc/irq/$irq/smp_affinity" 3 2>/dev/null || :
}
af_restore() {
    local file pid tid stamp old target path inode current irq name failed=0
    [ -d "$AF_STATE" ] || return 0
    rm -f "$AF_STATE"/*.tmp # unpublished records precede all mutations
    if [ "$(cat "$AF_STATE/boot" 2>/dev/null)" != "$AF_BOOT" ]; then
        rm -f "$AF_STATE"/thread-* "$AF_STATE"/irq-* "$AF_STATE"/cpuset-* "$AF_STATE/boot"
        return 0
    fi
    for file in "$AF_STATE"/thread-*; do
        [ -f "$file" ] || continue
        read -r pid tid stamp old target < "$file" || { failed=1; continue; }
        if af_identity "$pid" "$tid" "$stamp"; then
            current=$(af_mask "$tid") || current=
            # A later explicit affinity change belongs to its writer.
            if [ "$current" = "$target" ] && [ "$old" != "$target" ]; then
                af_identity "$pid" "$tid" "$stamp" && af_setmask "$tid" "$old" || { failed=1; continue; }
            fi
        fi
        rm -f "$file"
    done
    for file in "$AF_STATE"/irq-*; do
        [ -f "$file" ] || continue
        irq=${file##*/irq-}
        { IFS= read -r old; IFS= read -r name; } < "$file" || { failed=1; continue; }
        if [ "$(af_irq_name "$irq")" = "$name" ]; then
            current=$(af_read "/proc/irq/$irq/smp_affinity" | tr -d ', \n\t' | sed 's/^0*//')
            if [ "$current" = 3 ]; then
                af_write "/proc/irq/$irq/smp_affinity" "$old" 2>/dev/null || { failed=1; continue; }
            fi
        fi
        rm -f "$file"
    done
    for file in "$AF_STATE"/cpuset-*; do
        [ -f "$file" ] || continue
        read -r path inode old target < "$file" || { failed=1; continue; }
        if [ "$(stat -c '%d:%i' "$AF_ROOT$path" 2>/dev/null)" = "$inode" ] && [ "$(af_read "$path")" = "$target" ]; then
            af_write "$path" "$old" 2>/dev/null || { failed=1; continue; }
        fi
        rm -f "$file"
    done
    [ "$failed" = 0 ] || return 1
    rm -f "$AF_STATE/boot"
}
af_foreground() {
    /system/bin/timeout 1 /system/bin/dumpsys activity activities 2>/dev/null |
        sed -n 's/.*mResumedActivity:.* u[0-9]* \([^ /}]*\)\/.*/\1/p' | head -1
}
af_live() {
    [ "$(getprop "$AF_PROP")" = "$1" ] && [ "$(af_foreground)" = "$1" ] &&
        [ -n "$(pidof org.andr36oid.gamecomfort)" ] &&
        [ "$(getprop sys.andr36oid.subsystem.session)" != running ]
}
af_pid() {
    local p line
    for p in $(pidof "$1"); do
        line=$(tr '\000' '\n' < "$AF_ROOT/proc/$p/cmdline" 2>/dev/null | head -1)
        [ "$line" = "$1" ] && { printf '%s\n' "$p"; return 0; }
    done
    return 1
}
af_finish() {
    trap - EXIT INT TERM HUP
    if af_restore; then setprop sys.andr36oid.gameaffinity.state idle
    else setprop sys.andr36oid.gameaffinity.state recovery_required; fi
    if [ "$(getprop "$AF_PROP")" = "${AF_SESSION_PACKAGE:-}" ]; then setprop "$AF_PROP" ""; fi
}
af_main() {
    umask 077
    mkdir -p "$AF_STATE" || return 1
    chmod 0700 "$AF_STATE"
    exec 6>"$AF_STATE/lock"
    flock -n 6 6>&6 || return 0
    af_restore || { setprop sys.andr36oid.gameaffinity.state recovery_required; setprop "$AF_PROP" ""; return 1; }
    trap af_finish EXIT
    trap 'exit 1' INT TERM HUP
    local pkg pid tid path irq stamp
    while :; do
        pkg=$(getprop "$AF_PROP"); AF_SESSION_PACKAGE=$pkg
        case "$pkg" in com.retroarch|org.ppsspp.ppsspp) ;; *) return 0 ;; esac
        [ "$(af_read /sys/devices/system/cpu/online)" = 0-3 ] && af_live "$pkg" || break
        pid=$(af_pid "$pkg") || break
        AF_PID=$pid
        stamp=$(af_stamp "$pid" "$pid") || break
        AF_MAIN_OLD=$(af_mask "$pid") || break
        printf '%s\n' "$AF_BOOT" > "$AF_STATE/boot" || return 1
        AF_INITIAL=1
        while af_live "$pkg" && af_identity "$pid" "$pid" "$stamp"; do
            [ "$(af_read /sys/devices/system/cpu/online)" = 0-3 ] || return 1
            for path in "$AF_ROOT/proc/$pid/task/"[0-9]*; do
                [ -d "$path" ] || continue
                tid=${path##*/}; af_thread "$pid" "$tid" || return 1
            done
            [ "$(af_mask "$pid")" = c ] || return 1
            AF_INITIAL=0
            af_background || return 1
            for irq in $(af_irqs); do af_irq "$irq" || return 1; done
            setprop sys.andr36oid.gameaffinity.state active
            sleep 1
        done
        af_restore || return 1
        if [ "$(getprop "$AF_PROP")" = "$pkg" ]; then
            af_live "$pkg" || break
            # A replaced emulator PID/TID is a new owner, even for the same
            # foreground package. Restore old shared resources, then admit it.
        fi
    done
    # Do not let init repeatedly restart a stale request after its owner exits.
    setprop "$AF_PROP" ""
}
