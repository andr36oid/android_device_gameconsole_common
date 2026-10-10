#!/bin/bash
# Host test for ../andr36oid-clock.sh: runs it with a fake clock, fake RTC and fake
# properties (stub commands first in PATH). Run: bash andr36oid-clock-test.sh
# (TEST_SHELL=dash or a mksh path runs the script under another shell)
set -u
# busybox's ash runs its own date and hwclock, not the stubs: it would set this
# machine's clock
case ${TEST_SHELL:-} in *busybox*) echo "not under busybox: its date/hwclock would set the host clock"; exit 1 ;; esac
HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT=$HERE/../andr36oid-clock.sh
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
BIN=$T/bin
mkdir -p "$BIN"
fails=0

# Stubs. $T/now is the system clock, $T/rtc the RTC, $T/props the properties.
cat > "$BIN/date" <<'S'
#!/bin/bash
now=$(cat "$FAKE/now")
case "$1" in
    +%s) echo "$now" ;;
    @*) [ -n "${FAKE_DATE_FAILS:-}" ] && exit 1; echo "${1#@}" > "$FAKE/now" ;;
    -u) if [ "${2:-}" = -d ]; then /bin/date -u -d "$3"; else /bin/date -u -d "@$now"; fi ;;
    *) exit 3 ;;
esac
S
cat > "$BIN/hwclock" <<'S'
#!/bin/bash
[ "$*" = "-u -w -f /dev/rtc0" ] || { echo "bad hwclock args: $*" >&2; exit 1; }
cp "$FAKE/now" "$FAKE/rtc"
S
cat > "$BIN/touch" <<'S'
#!/bin/bash
/usr/bin/touch -d "@$(cat "$FAKE/now")" "$@"
S
cat > "$BIN/getprop" <<'S'
#!/bin/bash
grep "^$1=" "$FAKE/props" | cut -d= -f2-
S
cat > "$BIN/setprop" <<'S'
#!/bin/bash
echo "$1=$2" >> "$FAKE/props"
S
cat > "$BIN/log" <<'S'
#!/bin/bash
echo "log: $*" >> "$FAKE/log"
S
chmod +x "$BIN"/*

BUILD=1788000000        # 2026-08-29
RESET=1356998400        # 2013-01-01, what a reset RTC might read

setup() { # now
    rm -rf "$T/data" "$T/usage" "$T/props" "$T/rtc" "$T/log"
    mkdir -p "$T/data"
    echo "$1" > "$T/now"
    echo "ro.build.date.utc=$BUILD" > "$T/props"
}
run() {
    FAKE=$T PATH="$BIN:$PATH" ANDR36OID_CLOCK_DIR=$T/data \
        ANDR36OID_CLOCK_USAGE=$T/usage ${TEST_SHELL:-bash} "$SCRIPT" "$@"
}
stamp_at() { /usr/bin/touch -d "@$1" "$T/data/last-good"; }
usage_at() { mkdir -p "$T/usage"; /usr/bin/touch -d "@$1" "$T/usage"; }
expect() { # name want got
    if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: want '$2' got '$3'"; fails=$((fails + 1)); fi
}
now() { cat "$T/now"; }
restored() { grep -c "^sys.andr36oid.clock_restored=1" "$T/props"; }
stamp() { stat -c %Y "$T/data/last-good" 2>/dev/null || echo none; }
rtc() { cat "$T/rtc" 2>/dev/null || echo untouched; }

# 1. Normal boot, clock fine: nothing set, stamp = now
setup 1790000000; stamp_at 1789990000
run boot
expect "normal boot keeps the clock" 1790000000 "$(now)"
expect "normal boot not flagged" 0 "$(restored)"
expect "normal boot leaves the RTC" untouched "$(rtc)"
expect "normal boot stamps" 1790000000 "$(stamp)"

# 2. Reset, last good from the shutdown stamp
setup $RESET; stamp_at 1790000000
run boot
expect "reset: clock to the last good time" 1790000000 "$(now)"
expect "reset: RTC written" 1790000000 "$(rtc)"
expect "reset: flagged" 1 "$(restored)"
expect "reset: stamp at the new time" 1790000000 "$(stamp)"

# 3. Reset, usage stats newer than the stamp (battery pulled while running)
setup $RESET; stamp_at 1790000000; usage_at 1790004000
run boot
expect "reset: newer usage stats win" 1790004000 "$(now)"

# 4. Reset, stamp older than the build date (just flashed): build date
setup $RESET; stamp_at 1700000000
run boot
expect "reset: never before the build date" $BUILD "$(now)"
expect "reset: flagged with an old stamp" 1 "$(restored)"

# 5. Reset, first boot ever (no stamp): build date, like Android would
setup $RESET
run boot
expect "reset without a stamp: build date" $BUILD "$(now)"
expect "first boot stamp made" $BUILD "$(stamp)"

# 6. Clock set back by the user but after the build date: left alone
setup 1789000000; stamp_at 1795000000
run boot
expect "valid clock behind the stamp is left alone" 1789000000 "$(now)"
expect "stamp follows the clock back" 1789000000 "$(stamp)"

# 7. No build date property: never touch the clock
setup $RESET; stamp_at 1790000000; echo "ro.build.date.utc=" > "$T/props"
run boot
expect "no build date: left alone" $RESET "$(now)"

# 8. Setting the clock fails: not flagged, RTC untouched
setup $RESET; stamp_at 1790000000
FAKE_DATE_FAILS=1 run boot
expect "date fails: not flagged" 0 "$(restored)"
expect "date fails: RTC untouched" untouched "$(rtc)"
expect "date fails: the good stamp is kept" 1790000000 "$(stamp)"

# 9. Shutdown: stamp and RTC
setup 1791000000; stamp_at 1790000000
run shutdown
expect "shutdown stamps" 1791000000 "$(stamp)"
expect "shutdown writes the RTC" 1791000000 "$(rtc)"

# 9b. Shutdown with a clock before the build date (set by hand): the good stamp stays
setup $RESET; stamp_at 1790000000
run shutdown
expect "shutdown with a wrong clock keeps the stamp" 1790000000 "$(stamp)"

# 10. Full cycle: shutdown, battery flat, next boot
setup 1791000000
run shutdown
echo $RESET > "$T/now"
run boot
expect "cycle: back at the shutdown time" 1791000000 "$(now)"
: > "$T/props"; echo "ro.build.date.utc=$BUILD" > "$T/props"
echo 1791000600 > "$T/now"     # the next boot reads the RTC we wrote, plus uptime
run boot
expect "cycle: the boot after is normal" 0 "$(restored)"

# 10b. A date far in the future set by hand, saved, then a flat battery: capped at
# the build date + 20 years
setup $RESET; stamp_at 4102444800     # 2100-01-01
run boot
expect "future stamp capped at build + 20 years" $((BUILD + 20 * 31557600)) "$(now)"

# 10c. After 2038 (past 2^31): still compared right. Build date 2040, reset RTC,
# stamp in 2041
BUILD=2210000000
setup $RESET; stamp_at 2240000000
run boot
expect "2040s: reset seen, clock to the stamp" 2240000000 "$(now)"
setup 2250000000; stamp_at 2240000000
run boot
expect "2040s: a good clock is left alone" 2250000000 "$(now)"
expect "2040s: not flagged" 0 "$(restored)"
BUILD=1788000000

# 11. Unknown argument
run bogus 2> /dev/null
expect "bad argument exits 2" 2 "$?"

if [ "$fails" -ne 0 ]; then echo "$fails failed"; exit 1; fi
echo "all passed"
