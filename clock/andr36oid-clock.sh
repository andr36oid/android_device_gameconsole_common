#!/system/bin/sh
# shellcheck shell=ksh
#
# andr36oid-clock: keeps the date sane after the battery ran completely empty.
#
# The console's clock is the RTC in the RK817 PMIC (/dev/rtc0). It has no backup cell:
# it runs from the main battery, also while the console is off. When the battery is
# drained until its protection cuts it off, or unplugged, the RTC starts over at its
# reset date, years in the past. Android 11 then moves the clock up to the build date
# (AlarmManagerService), so after a reset the console would show the day the system
# was built, weeks or months ago, and time would go backwards for play time limits,
# save file dates and so on.
#
#   boot      (post-fs-data, before zygote) if the clock is before the build date, it
#             was reset: set it to the last time it was known to be right (never less
#             than the build date), write that to the RTC, set
#             sys.andr36oid.clock_restored=1 for this boot. Then stamp the time
#             (never while the clock is still before the build date).
#   shutdown  stamp the time (same rule) and write the system time to the RTC.
#
# "The last time known to be right" is the newest of:
#   - the mtime of $STAMP (touched at every boot and clean shutdown, and a flat battery
#     at 0 % is a clean shutdown)
#   - the mtime of Android's daily usage stats folder, which gets a new file (a rename)
#     at every usage stats flush, at most 20 minutes apart while the console is in use.
#     That covers a battery pulled while running.
# Both follow the clock when someone sets it back, at their next write.
# A last good time more than 20 years after the build date (a date set wrong by
# hand, then a flat battery) is capped there.
#
# mksh arithmetic is 32-bit, and Unix times pass 2^31 in 2038: times are
# compared with awk (gt), never with [ -lt ] or $(( )).

# The host tests point these elsewhere
DIR=${ANDR36OID_CLOCK_DIR:-/data/misc/andr36oid-clock}
USAGE=${ANDR36OID_CLOCK_USAGE:-/data/system/usagestats/0/daily}
STAMP=$DIR/last-good
RTC=/dev/rtc0
TAG=andr36oid-clock

say() {
    log -p i -t "$TAG" "$1"
    echo "$TAG: $1" 2> /dev/null > /dev/kmsg
}

# gt <a> <b> ; true if a > b (whole seconds; awk, see above)
gt() {
    awk -v a="$1" -v b="$2" 'BEGIN { exit !(a + 0 > b + 0) }'
}

# The latest time the clock is set back to: the build date plus 20 years
cap() {
    awk -v b="$1" 'BEGIN { printf "%.0f\n", b + 20 * 31557600 }'
}

mtime() {
    [ -e "$1" ] || { echo 0; return; }
    t=$(stat -c %Y "$1" 2>/dev/null)
    case "$t" in
        ''|*[!0-9]*) echo 0 ;;
        *) echo "$t" ;;
    esac
}

last_good() {
    a=$(mtime "$STAMP")
    b=$(mtime "$USAGE")
    if gt "$b" "$a"; then echo "$b"; else echo "$a"; fi
}

stamp() {
    mkdir -p "$DIR"
    touch "$STAMP"
}

to_rtc() {
    hwclock -u -w -f "$RTC" || say "could not write the RTC"
}

# The time to set the clock to, or nothing if it looks fine.
# $1 now, $2 build date, $3 last good time (0 = unknown)
restore_target() {
    case "$2" in
        ''|*[!0-9]*|0) return ;;
    esac
    gt "$2" "$1" || return
    if gt "$3" "$(cap "$2")"; then
        cap "$2"
    elif gt "$3" "$2"; then
        echo "$3"
    else
        echo "$2"
    fi
}

case "$1" in
    boot)
        now=$(date +%s)
        build=$(getprop ro.build.date.utc)
        last=$(last_good)
        target=$(restore_target "$now" "$build" "$last")
        if [ -n "$target" ]; then
            if date "@$target" > /dev/null; then
                to_rtc
                setprop sys.andr36oid.clock_restored 1
                say "clock was reset ($(date -u -d "@$now" 2>/dev/null)), set to $(date -u)"
            else
                say "clock was reset but could not be set"
            fi
        fi
        # Never stamp a clock that is still wrong: that would lose the last good time
        [ -n "$(restore_target "$(date +%s)" "$build" 0)" ] || stamp
        ;;
    shutdown)
        [ -n "$(restore_target "$(date +%s)" "$(getprop ro.build.date.utc)" 0)" ] || stamp
        to_rtc
        ;;
    *)
        echo "usage: $0 boot|shutdown" >&2
        exit 2
        ;;
esac
exit 0
