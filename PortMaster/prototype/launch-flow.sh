#!/system/bin/sh
# SPDX-License-Identifier: MIT
# Local diagnostic draft, not an installed Android service.
# Default is a read-only outline. See report runbook before any mutating mode.
# ShellCheck does not follow EXIT trap callbacks in this script.
# shellcheck disable=SC2317
set -u
umask 077

log() { printf '%s\n' "$*"; }
die() { log "FAIL $*" >&2; exit 1; }
state() { getprop "init.svc.$1"; }
wait_state() {
    svc=$1 want=$2 count=0
    while [ "$count" -lt 10 ]; do
        [ "$(state "$svc")" = "$want" ] && return 0
        sleep 1
        count=$((count + 1))
    done
    log "FAIL service=$svc expected=$want actual=$(state "$svc")" >&2
    return 1
}

mode=${1:---dry-run}
case "$mode" in
    --dry-run)
        log 'Preflight: root, recovery guard, lock, service inventory, probe, storage.'
        log 'Snapshot running services before any stop or audio cascade.'
        log 'Chroot only: validate hooks, rootfs, input, audio, power and storage.'
        log 'Stop SurfaceFlinger explicitly, then the actual composer services.'
        log 'Acquire/drop DRM master. Fail closed if it is still held.'
        log 'Run timed probe, or isolated guest namespace and fixed chroot entrypoint.'
        log 'Terminate child group, unmount guest, restore composer then SF and helpers.'
        log 'Check visible Android, controls, sound, logs and unchanged kernel boot ID.'
        exit 0 ;;
    --guest)
        # Internal child. All filesystem paths are prepared by a reviewed hook.
        [ "$(id -u)" = 0 ] || die 'guest helper needs root'
        [ "${PM_CHROOT_REVIEWED:-}" = YES ] || die 'chroot hardware review missing'
        [ "$#" -eq 3 ] || die 'internal guest arguments'
        guest_root=$2 guest_hooks=$3
        guest_cleanup() {
            guest_result=$?
            trap - 0 INT TERM HUP
            "$guest_hooks" unmount "$guest_root" || guest_result=1
            exit "$guest_result"
        }
        trap guest_cleanup 0
        trap 'exit 130' INT
        trap 'exit 143' TERM
        trap 'exit 129' HUP
        # This runs only after unshare -m. Refuse shared propagation.
        mount --make-rprivate / || die 'private mount propagation'
        "$guest_hooks" mount "$guest_root" || die 'guest mounts'
        # TODO: hook must set up filtered /dev, no guest procfs, read-only facts,
        # minimal read-only sysfs,
        # content, resolver, bounded tmp and exact tested SDL/GL libs.
        # Do not start systemd/udev, expose binder, or bind all Android /dev.
        # The required trusted entry helper must apply the tested uid/domain
        # transition. A mount hook subprocess cannot change its parent's uid.
        chroot "$guest_root" /usr/local/sbin/portmaster-enter /usr/bin/env -i HOME=/home/portmaster \
            PATH=/usr/local/bin:/usr/bin:/bin \
            SDL_VIDEODRIVER=kmsdrm SDL_KMSDRM_REQUIRE_DRM_MASTER=1 \
            /usr/local/bin/portmaster-session
        exit "$?" ;;
    --probe|--chroot) ;;
    *) die 'usage: launch-flow.sh [--dry-run|--probe|--chroot]' ;;
esac

[ "$#" -eq 1 ] || die 'use documented PM_* variables for this diagnostic draft'
[ "$(id -u)" = 0 ] || die 'needs an Android root shell'
[ "${PM_RECOVERY_ARMED:-}" = YES ] || die 'arm independent recovery per runbook first'
for utility in getprop setprop timeout setsid pidof; do
    command -v "$utility" >/dev/null 2>&1 || die "missing $utility"
done

base=${PM_STATE_BASE:-/data/local/tmp/portmaster}
probe=${PM_PROBE:-/data/local/tmp/portmaster/kms-probe}
display=${PM_DISPLAY:-/data/local/tmp/portmaster/portmaster-display}
duration=${PM_SECONDS:-10}
case "$duration" in ''|*[!0-9]*) die 'PM_SECONDS must be 1..600' ;; esac
if [ "$duration" -lt 1 ] || [ "$duration" -gt 600 ]; then die 'PM_SECONDS must be 1..600'; fi
if [ ! -d "$base" ] || [ ! -x "$probe" ]; then die 'state directory or probe missing'; fi
# The display owner must be blanked cleanly before it is stopped, or its still
# active scanout framebuffer wedges the next card0 open in drm_fb_release. The
# blank goes through SurfaceFlinger (display power off, SoC stays awake), never
# system sleep, so it does not trip the clone deep-sleep freeze. See report
# G1-ROOTCAUSE.md.
[ -x "$display" ] || die 'display blank helper (portmaster-display) missing'
[ "$(state surfaceflinger)" = running ] || die 'SurfaceFlinger must start in running state'

composers=''
for service in vendor.hwcomposer-2-1 hwcomposer-2-1; do
    case "$(state "$service")" in
        running) composers="$composers $service" ;;
        stopped|'') ;;
        *) die "unstable composer $service" ;;
    esac
done
[ -n "$composers" ] || die 'no known running composer; inventory actual init services first'

rootfs=${PM_ROOTFS:-/data/portmaster/roots/UNVERIFIED}
hooks=${PM_CHROOT_HOOKS:-/data/local/tmp/portmaster/chroot-hooks.sh}
helpers=''
audio=''
if [ "$mode" = --chroot ]; then
    [ "${PM_CHROOT_REVIEWED:-}" = YES ] || die 'chroot is gated on G1/G2 hardware results'
    [ -x "$hooks" ] || die 'reviewed chroot hooks missing'
    if [ ! -x "$rootfs/usr/bin/env" ] || [ ! -x "$rootfs/usr/local/bin/portmaster-session" ]; then
        die 'fixed guest entrypoint missing'
    fi
    [ -x "$rootfs/usr/local/sbin/portmaster-enter" ] || die 'trusted guest uid/domain entry helper missing'
    command -v unshare >/dev/null 2>&1 || die 'unshare unavailable'
    command -v chroot >/dev/null 2>&1 || die 'chroot unavailable'
    "$hooks" preflight "$rootfs" || die 'hardware and rootfs preflight'
    helpers='joyMouse andr36oid-controller'
    # Native TODO: audioserver stopped/running triggers both start HALs,
    # including originally stopped ones. This list alone cannot restore them.
    audio='audioserver vendor.audio-hal vendor.audio-hal-2-0 audio-hal-2-0 vendor.audio-hal-4-0-msd'
fi

lock=$base/session.lock
mkdir "$lock" 2>/dev/null || die "session locked at $lock; inspect stale state before recovery"
restore_failed=0
child=''
hook_quiesced=0
journal_ready=0
cleanup() {
    result=$?
    trap - 0 INT TERM HUP
    if [ -n "$child" ]; then
        # Child starts via setsid. A daemon that escapes its group is a TODO.
        kill -s TERM -- "-$child" 2>/dev/null || :
        sleep 1
        kill -s KILL -- "-$child" 2>/dev/null || :
        wait "$child" 2>/dev/null || :
    fi
    display_safe=1
    if [ "$mode" = --probe ] && pidof kms-probe >/dev/null 2>&1; then
        log 'FAIL probe still exists; display restore withheld; inspect /proc' >&2
        display_safe=0
        restore_failed=1
    fi
    if [ "$hook_quiesced" = 1 ]; then
        "$hooks" restore "$rootfs" || restore_failed=1
    fi
    if [ "$journal_ready" = 1 ] && [ "$display_safe" = 1 ]; then
        # File order is deliberate, not the inverse of an audio cascade.
        while IFS= read -r service; do
            [ -n "$service" ] || continue
            if [ "$(state "$service")" != running ]; then
                log "RESTORE $service"
                setprop ctl.start "$service" || restore_failed=1
                wait_state "$service" running || restore_failed=1
            fi
        done < "$lock/original-running"
    fi
    # Preserve evidence, even on success; failed cleanup leaves the lock in place.
    cp "$lock/original-running" "$base/last-original-running" 2>/dev/null || :
    if [ "$restore_failed" = 0 ]; then
        rm -f "$lock/original-running" "$lock/manager" "$lock/child"
        rmdir "$lock" || restore_failed=1
    fi
    [ "$restore_failed" = 0 ] || result=1
    log "RETURN exit=$result restore_failed=$restore_failed; verify visible HOME manually"
    exit "$result"
}
trap cleanup 0
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

# Snapshot everything before mutations, including audio services stopped by triggers.
# Restore display before audio and input. Initially stopped services are not started.
: > "$lock/original-running" || die 'cannot create journal'
for service in $composers surfaceflinger $audio $helpers; do
    case "$(state "$service")" in
        running) printf '%s\n' "$service" >> "$lock/original-running" || die 'journal write' ;;
        stopped|'') ;;
        *) die "unstable service $service" ;;
    esac
done
journal_ready=1
printf '%s\n' "$$" > "$lock/manager" || die 'manager journal write'
cat "$lock/original-running"

# Clean-quiesce: blank the panel while the composer is still master and alive so
# it disables its own scanout planes through a normal presented frame. This must
# run BEFORE any ctl.stop below; stopping a composer that is still scanning out
# is exactly what wedged card0 on the first G1 run.
log 'BLANK display (power off, SoC stays awake)'
timeout -s TERM -k 2 10 "$display" off || die 'display blank request'
# Give the composer a moment to drain the in-flight flip and settle the plane
# disable before its fd is torn down.
sleep 1

for service in $helpers $audio surfaceflinger $composers; do
    # Input helpers stop before mute/quiesce, then audio and display owners.
    if [ "$mode" = --chroot ] && [ "$service" = audioserver ]; then
        hook_quiesced=1 # Mark before attempting a partially failing hook.
        "$hooks" quiesce "$rootfs" || die 'mute, power and input handoff'
    fi
    if [ "$(state "$service")" = running ]; then
        log "STOP $service"
        setprop ctl.stop "$service" || die "stop request $service"
        wait_state "$service" stopped || die "stop completion $service"
    fi
done
if [ "$mode" = --probe ]; then
    : > "$base/fds.stopped"
    for display_fd in /proc/[0-9]*/fd/*; do
        display_target=$(readlink "$display_fd" 2>/dev/null) || continue
        case "$display_target" in
            /dev/dri/*) printf '%s -> %s\n' "$display_fd" "$display_target" >> "$base/fds.stopped" ;;
        esac
    done
    if [ -r /sys/kernel/debug/dri/0/clients ]; then
        cat /sys/kernel/debug/dri/0/clients > "$base/clients.stopped"
    fi
fi
timeout -s TERM -k 2 5 "$probe" --check-master || die 'DRM master not available'
while IFS= read -r service; do
    [ "$(state "$service")" = stopped ] || die "service respawned before draw: $service"
done < "$lock/original-running"

if [ "$mode" = --probe ]; then
    # timeout bounds the process; the separate human guard bounds UI downtime.
    setsid timeout -s TERM -k 5 "$((duration + 5))" "$probe" --seconds "$duration" &
else
    # First guest experiments are also bounded. No downloads or updater here.
    # TODO: production broker must supervise descendants and an exit chord,
    # survive SIGKILL/framework death and persist VT/mixer restoration.
    setsid timeout -s TERM -k 5 "$duration" unshare -m \
        /system/bin/sh "$0" --guest "$rootfs" "$hooks" &
fi
child=$!
printf '%s\n' "$child" > "$lock/child" || die 'child journal write'
log "CHILD session/process=$child"
if [ "$mode" = --probe ]; then
    while kill -0 "$child" 2>/dev/null; do
        {
            date -u
            pidof surfaceflinger system_server zygote zygote64 || :
            for service in surfaceflinger $composers; do
                printf '%s=%s\n' "$service" "$(state "$service")"
            done
        } >> "$base/pids.during"
        for service in surfaceflinger $composers; do
            [ "$(state "$service")" = stopped ] || die "service respawned during probe: $service"
        done
        sleep 1
    done
fi
wait "$child"
result=$?
if [ "$mode" = --probe ]; then
    for service in surfaceflinger $composers; do
        if [ "$(state "$service")" != stopped ]; then
            log "FAIL service respawned before restore: $service"
            result=1
        fi
    done
fi
# Always run group cleanup before restoring, even after its leader exits.
exit "$result"
