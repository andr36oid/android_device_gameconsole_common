#!/bin/sh
# Real helper policy, fake /proc /sys cpusets and taskset. NEVER host affinity/IRQs.
set -eu
repo=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
GAME_AFFINITY_ROOT=$tmp/root GAME_AFFINITY_STATE=$tmp/state GAME_AFFINITY_BOOT=fixture-boot
export GAME_AFFINITY_ROOT GAME_AFFINITY_STATE GAME_AFFINITY_BOOT
mkdir -p "$tmp/masks" "$GAME_AFFINITY_STATE" "$tmp/props" "$GAME_AFFINITY_ROOT/proc/irq/40" "$GAME_AFFINITY_ROOT/proc/irq/41" "$GAME_AFFINITY_ROOT/proc/irq/42" "$GAME_AFFINITY_ROOT/dev/cpuset/background" "$GAME_AFFINITY_ROOT/dev/cpuset/system-background" "$GAME_AFFINITY_ROOT/sys/devices/system/cpu"
. "$repo/GameComfort/helper/andr36oid-game-affinity.sh"
fail() { echo "FAIL: $*" >&2; exit 1; }
getprop() { cat "$tmp/props/$1" 2>/dev/null || :; }
setprop() { printf '%s' "$2" > "$tmp/props/$1"; }
pidof() { case "$1" in com.retroarch) echo 123;; org.ppsspp.ppsspp) echo 123;; org.andr36oid.gamecomfort) echo 555;; *) return 1;; esac; }
taskset() {
    [ "$1" = -p ] || fail 'bad taskset call'
    if [ "$#" = 2 ]; then
        printf "pid %s's current affinity mask: %s\n" "$2" "$(cat "$tmp/masks/$2")"
    else
        [ ! -f "$tmp/refuse-$3" ] || return 1
        printf '%s' "$2" > "$tmp/masks/$3"
        printf '%s %s\n' "$3" "$2" >> "$tmp/task-writes"
    fi
}
af_foreground() { cat "$tmp/top"; }
thread() {
    local tid=$1 start=$2 mask=$3
    mkdir -p "$AF_ROOT/proc/123/task/$tid"
    awk -v tid="$tid" -v start="$start" 'BEGIN {printf "%s (emulator worker) S",tid;for(i=0;i<18;i++)printf " 0";printf " %s\n",start}' > "$AF_ROOT/proc/123/task/$tid/stat"
    printf '%s' "$mask" > "$tmp/masks/$tid"
}
reset() {
    rm -f "$AF_STATE"/thread-* "$AF_STATE"/irq-* "$AF_STATE"/cpuset-* "$AF_STATE/boot" "$tmp"/refuse-* "$tmp/task-writes"
    rm -rf "$AF_ROOT/proc/123"
    thread 123 100 f;thread 124 101 b
    printf 'com.retroarch\000' > "$AF_ROOT/proc/123/cmdline"
    printf '0-3\n' > "$AF_ROOT/sys/devices/system/cpu/online"
    printf '0-3\n' > "$AF_ROOT/dev/cpuset/background/cpus"
    printf '0-2\n' > "$AF_ROOT/dev/cpuset/system-background/cpus"
    printf 'f\n' > "$AF_ROOT/proc/irq/40/smp_affinity"
    printf 'a\n' > "$AF_ROOT/proc/irq/41/smp_affinity"
    printf 'f\n' > "$AF_ROOT/proc/irq/42/smp_affinity"
    printf '40: 0 0 0 0 GICv3 40 Level mali-job\n41: 0 0 0 0 GICv3 41 Level mmc0\n42: 0 0 0 0 GICv3 42 Level arch_timer\nIPI0: 1 1 1 1 Rescheduling interrupts\n' > "$AF_ROOT/proc/interrupts"
    setprop "$AF_PROP" com.retroarch;printf com.retroarch > "$tmp/top"
    setprop sys.andr36oid.subsystem.session ended
    printf '%s\n' "$AF_BOOT" > "$AF_STATE/boot"
    : > "$AF_ROOT/dev/cpuset/background/tasks"
    : > "$AF_ROOT/dev/cpuset/system-background/tasks"
    AF_MAIN_OLD=f AF_INITIAL=1 AF_PID=123
}
reset
af_thread 123 123;af_thread 123 124
af_background
for irq in $(af_irqs); do af_irq "$irq"; done
[ "$(cat "$tmp/masks/123")" = c ] && [ "$(cat "$tmp/masks/124")" = c ] || fail thread-pin
[ "$(af_read /dev/cpuset/background/cpus)" = 0-1 ] || fail background-pin
[ "$(af_read /proc/irq/40/smp_affinity)" = 3 ] && [ "$(af_read /proc/irq/42/smp_affinity)" = f ] || fail irq-allowlist
n=$(wc -l < "$tmp/task-writes");af_thread 123 123;af_background;af_irq 40
[ "$(wc -l < "$tmp/task-writes")" = "$n" ] || fail duplicate-start
AF_INITIAL=0;thread 125 102 c;af_thread 123 125
af_restore
[ "$(cat "$tmp/masks/123")" = f ] && [ "$(cat "$tmp/masks/124")" = b ] && [ "$(cat "$tmp/masks/125")" = f ] || fail thread-restore
[ "$(af_read /dev/cpuset/background/cpus)" = 0-3 ] && [ "$(af_read /dev/cpuset/system-background/cpus)" = 0-2 ] || fail cpuset-restore
[ "$(af_read /proc/irq/40/smp_affinity)" = f ] && [ "$(af_read /proc/irq/41/smp_affinity)" = a ] || fail irq-restore
af_restore
echo 'PASS: thread/IRQ/background admission, duplicate requests, inherited new threads and exact restoration'
reset;af_thread 123 124;printf 3 > "$tmp/masks/124";af_restore
[ "$(cat "$tmp/masks/124")" = 3 ] || fail user-mask
reset;af_thread 123 124;thread 124 999 c;af_restore
[ "$(cat "$tmp/masks/124")" = c ] || fail recycled-tid
reset;af_irq 40;sed 's/mali-job/other-device/' "$AF_ROOT/proc/interrupts" > "$tmp/interrupts";mv "$tmp/interrupts" "$AF_ROOT/proc/interrupts";af_restore
[ "$(af_read /proc/irq/40/smp_affinity)" = 3 ] || fail recycled-irq
reset;af_thread 123 123;touch "$tmp/refuse-123"
if af_restore; then fail restore-failure-hidden;fi
[ -f "$AF_STATE/thread-123" ] || fail lost-journal
rm "$tmp/refuse-123";af_restore
[ "$(cat "$tmp/masks/123")" = f ] || fail recovery-retry
reset;af_thread 123 123;printf old-boot > "$AF_STATE/boot";af_restore
[ "$(cat "$tmp/masks/123")" = c ] && [ ! -f "$AF_STATE/thread-123" ] || fail stale-boot
reset;printf partial > "$AF_STATE/cpuset-incomplete.tmp";af_restore
[ ! -e "$AF_STATE/cpuset-incomplete.tmp" ] || fail unpublished-journal
echo 'PASS: later user masks, recycled TID/IRQ identities, failed restore journal/retry and stale boot'
reset
# Execute the full service loop; on its second tick the foreground request ends.
(
    ticks=0
    sleep() {
        ticks=$((ticks+1))
        if [ "$ticks" = 1 ];then thread 125 102 c
        else setprop "$AF_PROP" '';printf home > "$tmp/top";fi
    }
    af_main
)
[ "$(cat "$tmp/masks/123")" = f ] && [ "$(cat "$tmp/masks/125")" = f ] || fail service-exit
[ "$(getprop sys.andr36oid.gameaffinity.state)" = idle ] || fail service-status
[ "$(af_read /dev/cpuset/background/cpus)" = 0-3 ] || fail service-background-leak
echo 'PASS: full foreground service loop observes stop and restores all owned state; no host taskset/device writes'

reset
(
    ticks=0
    sleep() {
        ticks=$((ticks+1))
        if [ "$ticks" = 1 ]; then
            rm -rf "$AF_ROOT/proc/123"
            thread 123 200 f;thread 124 201 b
            printf 'com.retroarch\000' > "$AF_ROOT/proc/123/cmdline"
        else setprop "$AF_PROP" '';printf home > "$tmp/top";fi
    }
    af_main
)
[ "$(awk '$1==123 && $2=="c" {n++} END {print n+0}' "$tmp/task-writes")" = 2 ] || fail restarted-owner
[ "$(cat "$tmp/masks/123")" = f ] || fail restarted-owner-restore
echo 'PASS: same-package process restart re-admits new identities and restores their masks'
