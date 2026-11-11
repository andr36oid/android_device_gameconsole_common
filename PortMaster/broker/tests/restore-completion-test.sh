#!/bin/sh
# A guardian finishing an interrupted finalizer must not reboot Android again.
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions restore_android recover_session_locked
HWC=composer
kill_subsystem() { echo reap >> "$tmp/events"; }
stop_mute_guard() { echo mute-stop >> "$tmp/events"; }
restore_android_network() { echo network >> "$tmp/events"; }
restore_guest_hardware() { echo hardware >> "$tmp/events"; }
show_splash() { echo splash >> "$tmp/events"; }
prepare_retroarch_sync() { RA_SYNC_DIR=; }
start() { echo framework >> "$tmp/events"; }
getprop() { [ "$1" != init.svc.surfaceflinger ] || echo stopped; }
DID_HANDOFF=1
restore_android
[ "$CLEANUP_FAILED" = 0 ] || fail first-restore
cp "$tmp/events" "$tmp/before"
# Process dies after successful hardware restore but before deleting the lock.
: > "$STATE/session.restore-complete"; : > "$STATE/session.handoff"
recover_session_locked "$$" || fail finalizer-recovery
cmp "$tmp/before" "$tmp/events" || fail 'duplicate finalizer repeated hardware/network/framework work'
[ ! -d "$STATE/session.lock" ] || fail 'finalizer retained session lock'
grep -q 'hardware restore already complete' "$LOG" || fail 'idempotent recovery was not logged'
echo 'PASS: interrupted finalizer clears its journal without another hardware/network/framework restore'
