#!/system/bin/sh
# SPDX-License-Identifier: MIT
# Human-run G1 driver. Never installed by Android build or init files.
# shellcheck disable=SC2317
set -u
umask 077

case "${1:---dry-run}" in
    --dry-run)
        echo 'G1: snapshot, live master refusal, detached guard, stop SF/composer,'
        echo 'ten-second CPU pattern, release DRM, restore original services, logs.'
        echo 'Run on the unit from a root console: sh hardware-test.sh --run'
        exit 0 ;;
    --run) [ "$#" -eq 1 ] || exit 2 ;;
    *) echo 'Usage: hardware-test.sh [--dry-run|--run]' >&2; exit 2 ;;
esac

base=/data/local/tmp/portmaster
shell=/system/bin/sh
probe=$base/kms-probe
say() { printf '%s\n' "$*"; }
fail() { say "FAIL DRIVER $*" >&2; exit 1; }
state() { getprop "init.svc.$1"; }
[ "$(id -u)" = 0 ] || fail 'requires Android root console'
for utility in getprop setprop timeout setsid nohup pidof readlink date grep; do
    command -v "$utility" >/dev/null 2>&1 || fail "missing $utility"
done
for source_file in launch-flow.sh handoff-watchdog.sh; do
    [ -r "$base/$source_file" ] || fail "missing $source_file"
done
[ -x "$probe" ] || fail 'missing executable kms-probe'
[ -x "$base/portmaster-display" ] || fail 'missing executable portmaster-display'
[ -c /dev/dri/card0 ] || fail '/dev/dri/card0 is not a character device'
[ ! -e "$base/session.lock" ] || fail 'old session.lock; inspect it first'
[ ! -e "$base/guard.ready" ] || fail 'old guard.ready; inspect it first'
pidof kms-probe >/dev/null 2>&1 && fail 'a kms-probe already exists'
[ "$(state surfaceflinger)" = running ] || fail 'SurfaceFlinger is not running'
composers=0
for service in vendor.hwcomposer-2-1 hwcomposer-2-1; do
    case "$(state "$service")" in
        running) composers=$((composers + 1)) ;;
        stopped|'') ;;
        *) fail "unstable composer $service" ;;
    esac
done
[ "$composers" = 1 ] || fail 'expected one known composer; inventory init services first'
mkdir "$base/hardware.lock" 2>/dev/null || fail 'hardware.lock exists; inspect previous run'
run=$base/runs/$(date -u +%Y%m%dT%H%M%SZ)-$$
mkdir -p "$run" || { rmdir "$base/hardware.lock"; fail 'cannot create run directory'; }
printf '%s\n' "$run" > "$base/hardware.lock/run"
printf '%s\n' "$$" > "$base/hardware.lock/pid"
manager=''
guard=''
finished=0

fds() {
    for fd in /proc/[0-9]*/fd/*; do
        target=$(readlink "$fd" 2>/dev/null) || continue
        case "$target" in /dev/dri/*) printf '%s -> %s\n' "$fd" "$target" ;; esac
    done
}
snapshot() {
    phase=$1
    getprop > "$run/$phase.props"
    { date -u; pidof surfaceflinger system_server zygote zygote64 || :; } > "$run/pids.$phase"
    cat /proc/sys/kernel/random/boot_id > "$run/boot-id.$phase"
    cat /proc/meminfo > "$run/meminfo.$phase"
    cat /proc/mounts > "$run/mounts.$phase"
    fds > "$run/fds.$phase"
    if [ -r /sys/kernel/debug/dri/0/clients ]; then
        cat /sys/kernel/debug/dri/0/clients > "$run/clients.$phase"
    fi
    dmesg > "$run/dmesg.$phase" 2>&1 || :
    timeout -s TERM -k 1 5 logcat -d -b all > "$run/logcat.$phase" 2>&1 || :
}
finish() {
    result=$?
    trap - 0 INT TERM HUP
    if [ -n "$manager" ]; then
        kill -s TERM "$manager" 2>/dev/null || :
        wait "$manager" 2>/dev/null || :
    fi
    machine_return=YES
    if [ -r "$run/guard.original-running" ]; then
        while IFS= read -r service; do
            [ "$(state "$service")" = running ] || machine_return=NO
        done < "$run/guard.original-running"
    fi
    pidof kms-probe >/dev/null 2>&1 && machine_return=NO
    # Compare read values: /proc reports a zero stat size, so cmp -s can
    # reject matching contents when compared with a regular snapshot file.
    before_boot=$(cat "$run/boot-id.before" 2>/dev/null)
    after_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
    if [ -z "$before_boot" ] || [ "$before_boot" != "$after_boot" ]; then
        machine_return=NO
    fi
    if [ -n "$guard" ] && [ "$machine_return" = YES ]; then
        # Cancellation asserts only machine checks, never visible recovery.
        : > "$run/guard.cancel"
        tick=0
        while [ -e "$run/guard.ready" ] && [ "$tick" -lt 3 ]; do
            sleep 1
            tick=$((tick + 1))
        done
    fi
    snapshot after
    if [ -e "$run/guard.expired" ]; then
        say 'FAIL DRIVER guard expired; retain the recovery result for review'
        result=1
    fi
    if [ "$machine_return" = YES ] && [ ! -e "$run/guard.ready" ]; then
        rm -f "$base/hardware.lock/run" "$base/hardware.lock/pid"
        rmdir "$base/hardware.lock" || result=1
    else
        say "FAIL DRIVER recovery pending; preserve $base/hardware.lock and inspect guard.log"
        result=1
    fi
    {
        say '# Hardware result, complete the observation fields'
        say "- Logs: $run"
        say "- Driver exit: $result"
        say "- Sequence completed: $finished"
        say "- Services running, probe gone, same boot: $machine_return"
        say '- Identity, uid/domain, labels and hashes: identity.txt'
        say '- Before/after PIDs, boot ID, DRM owners: pids.*, boot-id.*, fds.*, clients.*'
        say '- Live baseline: baseline.log and baseline.exit'
        say '- Stop, pattern, restore and probe exit: handoff.log and handoff.exit'
        say '- Guard: guard.log and guard.* markers'
        say '- Actual visible bars, border/checkerboard, duration and rotation: TODO'
        say '- Visible Android HOME and physical controls/audio after return: TODO'
        say '- Any guard expiry, extra framework restart or reboot: TODO'
        say '- G1: UNASSESSED until operator completes result-template.md'
    } > "$run/result-template.md"
    say "RESULT driver_exit=$result services_and_boot=$machine_return visible=UNASSESSED"
    say "RESULT logs=$run"
    say 'OBSERVE: verify visible Android HOME, physical controls and sound; fill result-template.md.'
    exit "$result"
}
trap finish 0
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

{
    id; id -Z; getenforce; uname -a
    getprop ro.build.fingerprint
    sha256sum "$probe" "$base/portmaster-display" "$base/launch-flow.sh" "$base/handoff-watchdog.sh" "$base/hardware-test.sh"
    ls -lZ /dev/dri /dev/dri/card0 /dev/mali0 /dev/input /dev/input/* /dev/uinput /dev/tty /dev/tty0 /dev/tty1
    cat /proc/bus/input/devices
    cat /sys/class/drm/card0-*/status /sys/class/drm/card0-*/modes
} > "$run/identity.txt" 2>&1
snapshot before
timeout -s TERM -k 2 5 "$probe" --check-master > "$run/baseline.log" 2>&1
baseline=$?
printf '%s\n' "$baseline" > "$run/baseline.exit"
cat "$run/baseline.log"
if [ "$baseline" != 3 ] || ! grep -q '^FAIL SET_MASTER ' "$run/baseline.log"; then
    fail 'live baseline must refuse SET_MASTER with exit 3; no service was stopped'
fi
say 'BASELINE expected-refusal exit=3'

nohup setsid "$shell" "$base/handoff-watchdog.sh" "$run" > "$run/guard.log" 2>&1 < /dev/null &
guard=$!
tick=0
while [ ! -s "$run/guard.ready" ] && [ "$tick" -lt 3 ]; do
    sleep 1
    tick=$((tick + 1))
done
[ -s "$run/guard.ready" ] || fail 'guard did not become ready; no service was stopped'
read -r guard_pid < "$run/guard.ready"
case "$guard_pid" in ''|*[!0-9]*) fail 'invalid guard PID' ;; esac
kill -0 "$guard_pid" 2>/dev/null || fail 'guard is not alive'
case "$(tr '\000' ' ' < "/proc/$guard_pid/cmdline")" in
    *handoff-watchdog.sh*) ;;
    *) fail 'guard identity mismatch' ;;
esac
grep -qx surfaceflinger "$run/guard.original-running" || fail 'guard did not journal SF'
say "GUARD ready pid=$guard_pid seconds=45"

PM_RECOVERY_ARMED=YES PM_STATE_BASE="$run" PM_PROBE="$probe" PM_DISPLAY="$base/portmaster-display" PM_SECONDS=10 \
    "$shell" "$base/launch-flow.sh" --probe > "$run/handoff.log" 2>&1 &
manager=$!
wait "$manager"
handoff=$?
manager=''
printf '%s\n' "$handoff" > "$run/handoff.exit"
cat "$run/handoff.log"
sleep 2
finished=1
exit "$handoff"
