#!/system/bin/sh
# SPDX-License-Identifier: MIT
# Independent guard for the display-only experiment, detached by hardware-test.sh.
# Not a production supervisor and not sufficient for a chroot session.
set -eu
umask 077
base=${1:-/data/local/tmp/portmaster}
[ "$(id -u)" = 0 ] || exit 1
[ -d "$base" ] || exit 1
[ ! -e "$base/guard.ready" ] || { echo 'Existing guard state; inspect it first' >&2; exit 1; }
: > "$base/guard.original-running"
for svc in vendor.hwcomposer-2-1 hwcomposer-2-1 surfaceflinger; do
    case "$(getprop "init.svc.$svc")" in
        running) printf '%s\n' "$svc" >> "$base/guard.original-running" ;;
        stopped|'') ;;
        *) echo "Unstable $svc; refuse guard" >&2; exit 1 ;;
    esac
done
grep -qx surfaceflinger "$base/guard.original-running" || exit 1
rm -f "$base/guard.cancel" "$base/guard.expired"
printf '%s\n' "$$" > "$base/guard.ready"
echo 'Guard armed for 45 seconds; perform one 10-second probe now.'
tick=0
while [ "$tick" -lt 45 ]; do
    if [ -f "$base/guard.cancel" ]; then
        echo 'Guard cancelled; verify visible Android manually.'
        rm -f "$base/guard.ready"
        exit 0
    fi
    sleep 1
    tick=$((tick + 1))
done
: > "$base/guard.expired"
echo 'Guard expired. Stop the recorded probe session before starting display owners.'
# Only act on current command lines matching this diagnostic draft.
if [ -r "$base/session.lock/manager" ]; then
    read -r manager < "$base/session.lock/manager"
    case "$manager" in
        ''|*[!0-9]*) exit 1 ;;
    esac
    if [ -r "/proc/$manager/cmdline" ]; then
        case "$(tr '\000' ' ' < "/proc/$manager/cmdline")" in
            *launch-flow.sh*--probe*) kill -s TERM "$manager" 2>/dev/null || : ;;
            *) echo 'Manager identity mismatch; human recovery required' >&2; exit 1 ;;
        esac
    fi
fi
sleep 3
if [ -r "$base/session.lock/child" ]; then
    read -r child < "$base/session.lock/child"
    case "$child" in
        ''|*[!0-9]*) exit 1 ;;
    esac
    if [ -r "/proc/$child/cmdline" ]; then
        case "$(tr '\000' ' ' < "/proc/$child/cmdline")" in
            *timeout*kms-probe*) kill -s KILL -- "-$child" 2>/dev/null || : ;;
            *) echo 'Child identity mismatch; human recovery required' >&2; exit 1 ;;
        esac
    fi
fi
# Refuse concurrent scanout even if the manager died before writing its child PID.
# The executable is pushed as kms-probe by the runbook.
if pidof kms-probe >/dev/null 2>&1; then
    echo 'Probe still exists; do not restart competing display owners. Inspect /proc.' >&2
    exit 1
fi
while IFS= read -r svc; do
    echo "Guard restore $svc"
    setprop ctl.start "$svc"
done < "$base/guard.original-running"
echo 'Recovery requests sent. Check service states and visible Android manually.'
rm -f "$base/guard.ready"
