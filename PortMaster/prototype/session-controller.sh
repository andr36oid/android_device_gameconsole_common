#!/system/bin/sh
# SPDX-License-Identifier: MIT
# PROTOTYPE, UNWIRED. Unproven until the G1 hardware experiment passes.
# Fixed shell entry, not a deployable root broker. A native
# /system/bin/portmaster-session must replace it before integration.
# --enter deliberately refuses before teardown. No environment switch bypasses
# the missing native supervisor/privilege boundary or hardware preflight.
set -u
umask 077

log() { printf '%s\n' "$*"; }
fail() { log "REFUSED: $*" >&2; exit 1; }
publish() { setprop sys.portmaster.status "$1"; }

# Fixed paths, never supplied by the tile, a property, or writable game content.
flow=/system/etc/portmaster/launch-flow.sh
hooks=/system/etc/portmaster/chroot-hooks.sh
probe=/system/etc/portmaster/kms-probe
journal=/data/portmaster/journal

reviewed_preflight() {
    # TODO G1: attach observed master/pattern/recovery evidence from runbook.md,
    # including actual service names, panel/VT behavior and framework survival.
    # TODO G2/G3: verified immutable rootfs, Mali/GBM/SDL and guest uid transition.
    # TODO native: root-owned paths/manifest, lock shared with storage helpers,
    # journal stale-session recovery, authenticated independent supervisor ready,
    # descendants/fd release and bounded cleanup before Android may restart.
    # TODO device hooks: unlocked/provisioned user, parental policy, battery,
    # free disk/RAM, idle USB/backup/update, input chord, power and mixer rollback.
    # Existing chroot-hooks.example.sh also refuses. The old shell --guest path
    # is not the read-only-image/native-entry implementation in portmaster.rules.
    log 'REFUSED: native recovery/guest boundary and reviewed hardware hooks are absent' >&2
    return 1
}

case "${1:---dry-run}" in
    --dry-run)
        [ "$#" -le 1 ] || fail 'unexpected arguments'
        log 'PROTOTYPE: sys.portmaster.request=enter -> init -> portmaster-session --enter'
        log 'Preflight refuses until the native boundary, recovery and hardware hooks exist.'
        log 'Then delegate teardown, bounded guest entry and restore to launch-flow.sh --chroot.'
        log 'Status: preflight -> unavailable; future flow: handoff -> returned-unverified or recovery-required.'
        exit 0 ;;
    --enter) [ "$#" -eq 1 ] || fail 'unexpected arguments' ;;
    *) fail 'usage: session-controller.sh [--dry-run|--enter]' ;;
esac

# init only starts this fixed operation for the enter value and resets the
# request to idle before exec. Do not accept commands, paths or service names.
[ "$(id -u)" = 0 ] || fail 'controller requires uid 0'
for utility in getprop setprop; do
    command -v "$utility" >/dev/null 2>&1 || fail "Android $utility unavailable"
done
publish preflight || fail 'cannot publish preflight status'
if ! reviewed_preflight; then
    publish unavailable || fail 'cannot publish refusal status'
    exit 1
fi

# Deliberately unreachable until reviewed_preflight is implemented/reviewed.
# Do not convert it to an environment flag. Native code must authenticate the
# external guard and establish these legacy flow assertions only after checks.
if [ "${PM_RECOVERY_ARMED:-}" != YES ] || [ "${PM_CHROOT_REVIEWED:-}" != YES ]; then
    publish unavailable || fail 'cannot publish missing-review status'
    fail 'reviewed independent recovery and chroot entry required'
fi
if [ ! -x /system/bin/sh ] || [ ! -r "$flow" ] || [ ! -x "$hooks" ] || [ ! -x "$probe" ]; then
    publish unavailable || fail 'cannot publish missing-entry status'
    fail 'fixed trusted entries missing'
fi
if [ ! -d "$journal" ] || [ -e "$journal/session.lock" ]; then
    publish recovery-required || fail 'cannot publish journal status'
    fail 'missing journal or unresolved previous session; preserve evidence'
fi

# All snapshots, stops, waits, master checks, guest cleanup and restore live in
# launch-flow.sh, not here. In --chroot it snapshots audio HALs before audioserver
# cascades, quiesces input/audio, stops surfaceflinger then the actual composer,
# and closes the guest before restoring composer -> SF -> original audio/input.
# It supports vendor.hwcomposer-2-1 and the legacy hwcomposer-2-1 candidate;
# TODO constrain that candidate list to the imported, observed device services.
# Keep adbd/vold/framework running; do not silently add a zygote stop fallback.
# Allocator stays alone unless a separately reviewed owner inventory requires it.
publish handoff || fail 'cannot publish handoff status'
PM_STATE_BASE=$journal PM_PROBE=$probe PM_CHROOT_HOOKS=$hooks \
    PM_ROOTFS=/mnt/portmaster PM_SECONDS=10 \
    /system/bin/sh "$flow" --chroot
flow_result=$?

# handoff covers entering, guest execution AND restoring inside the synchronous
# flow. Do not claim running/ready or emit restoring after cleanup already ran.
# A native supervisor must handle controller death, escaped descendants, mount
# failure, stuck display holders and recovery even if this process never returns.
if [ "$flow_result" -ne 0 ] || [ -e "$journal/session.lock" ]; then
    publish recovery-required || fail 'cannot publish recovery failure'
    fail "launch/restore incomplete (exit=$flow_result); keep journal and logs"
fi
publish returned-unverified || fail 'cannot publish return status'
log 'Flow returned; visible HOME, controls/audio, stable owners and boot ID still need verification.'
# TODO native: verify those checks before publishing ready for another request.
