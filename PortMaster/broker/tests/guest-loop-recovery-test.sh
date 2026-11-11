#!/bin/sh
# Recover native ownership after the guest dies; retain failures for retry.
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions restore_android recover_session_locked
stop_mute_guard() { :; }
kill_subsystem() { echo killed >> "$tmp/events"; }
show_splash() { :; }
prepare_retroarch_sync() { RA_SYNC_DIR=; }
GUEST="$tmp/guest"
export EVENTS="$tmp/events" FAIL_CLEANUP=0
cat > "$GUEST" <<'HELPER'
#!/bin/sh
[ "$1" = --cleanup-guest-loops ] && [ "$2" = "$A36_TEST_ROOT" ] || exit 2
echo native-loop-cleanup >> "$EVENTS"
[ "$FAIL_CLEANUP" = 0 ] || exit 1
rm -f "$3"
HELPER
chmod 0755 "$GUEST"
export A36_TEST_ROOT="$ROOT"
: > "$STATE/session.guest-loops"
restore_android
[ "$CLEANUP_FAILED" = 0 ] && [ ! -e "$STATE/session.guest-loops" ] || fail 'successful native cleanup was not consumed'
[ "$(head -n 1 "$tmp/events")" = killed ] || fail 'loop recovery preceded payload teardown'
[ "$(sed -n '2p' "$tmp/events")" = native-loop-cleanup ] || fail 'loop recovery missing'
: > "$STATE/session.guest-loops";FAIL_CLEANUP=1
restore_android
[ "$CLEANUP_FAILED" = 1 ] && [ -e "$STATE/session.guest-loops" ] || fail 'failed cleanup discarded journal'
FAIL_CLEANUP=0
restore_android
[ "$CLEANUP_FAILED" = 0 ] && [ ! -e "$STATE/session.guest-loops" ] || fail 'retry did not recover'
# Reboot invalidates global loop identities: stale metadata is removed without
# asking the native helper to detach recycled devices.
echo old-boot > "$STATE/session.lock/boot_id"
: > "$STATE/session.guest-loops";: > "$STATE/session.guest-loops.tmp";: > "$tmp/events"
recover_session_locked
[ ! -e "$STATE/session.guest-loops" ] && [ ! -e "$STATE/session.guest-loops.tmp" ] || fail 'old-boot journal retained'
[ ! -s "$tmp/events" ] || fail 'old-boot recovery touched current devices'
echo 'PASS: guest-loop cleanup follows process teardown, retries failures, and drops old-boot metadata without detaching'
