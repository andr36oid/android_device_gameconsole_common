#!/bin/sh
# Exercise both exit entry points and the wake-lock writes against fake sysfs.
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions begin_android_power_return android_power_return_in_progress finish_android_power_return \
  restore_android finish_session recover_session_locked stop_session handoff
HWC=composer
wake="$A36_POWER_DIR/wake_lock"
unlock="$A36_POWER_DIR/wake_unlock"
deadline="$STATE/session.lock/power-return-deadline"
stop_mute_guard() { :; }
kill_subsystem() { hold_during_restore; }
prepare_retroarch_sync() { RA_SYNC_DIR=; }
release_session_loop() { hold_during_restore; }
show_splash() { hold_during_restore; }
stop_android_display() { [ "$(cat "$wake")" = andr36oid-subsystem ]; }
getprop() {
  case "$1" in
    init.svc.zygote) echo running ;;
    init.svc.surfaceflinger) echo stopped ;;
    sys.boot_completed) echo 1 ;;
  esac
}
stop() {
  [ "$(cat "$wake")" = andr36oid-subsystem ] || fail 'zygote stop raced wake-lock acquisition'
  echo "$1" >> "$tmp/stops"
}
wait_service_stopped() { :; }
session_uptime_seconds() { cat "$tmp/clock"; }
sleep() {
  if [ -z "${testing_stop:-}" ]; then hold_during_restore; fi
  echo "$(( $(session_uptime_seconds) + 1 ))" > "$tmp/clock"
}
hold_during_restore() {
  [ "$(cat "$wake")" = 'andr36oid-subsystem 90000000000' ] || fail 'kernel backstop was not armed'
  [ ! -e "$unlock" ] || fail 'wake lock released before Android was ready'
}
start() {
  hold_during_restore
  [ "$mode" != start-failure ]
}
timeout() {
  [ "$*" = '-k 1 2 dumpsys -t 1 power' ] || fail 'power readiness probe was unbounded'
  hold_during_restore
  case "$mode" in
    unready) return 1 ;;
  esac
  echo '  mSystemReady=true'
  if [ "$(session_uptime_seconds)" -ge 112 ]; then
    echo '  mBootCompleted=true'
  else
    echo '  mBootCompleted=false'
  fi
}
reset_return() {
  mkdir -p "$STATE/session.lock"
  printf '%s\n' "$$" > "$STATE/session.lock/pid"
  cat /proc/sys/kernel/random/boot_id > "$STATE/session.lock/boot_id"
  : > "$STATE/session.handoff"
  SESSION_OWNED=1; DID_HANDOFF=1; mode=ready
  echo 100 > "$tmp/clock"
  echo andr36oid-subsystem > "$wake"
  rm -f "$unlock" "$deadline" "$LOG" "$STATE/session.restore-complete"
}

reset_return
(finish_session 0) || fail 'normal exit failed'
[ "$(cat "$unlock")" = andr36oid-subsystem ] || fail 'normal exit leaked the wake lock'
[ "$(session_uptime_seconds)" = 112 ] || fail 'stale boot property released the lock early'
grep -q 'released session wake lock (Android power service ready)' "$LOG" || fail 'ready reason omitted'
[ ! -e "$deadline" ] || fail 'normal exit retained power deadline'

reset_return
DID_HANDOFF=0
recover_session_locked "$$" || fail 'same-boot crash recovery failed'
[ "$(cat "$unlock")" = andr36oid-subsystem ] || fail 'crash recovery leaked the wake lock'
[ "$(session_uptime_seconds)" = 112 ] || fail 'crash recovery released before fresh power readiness'
[ ! -e "$deadline" ] || fail 'crash recovery retained power deadline'

reset_return
mode=unready
restore_android
[ "$(session_uptime_seconds)" = 175 ] || fail 'power wait exceeded the handback deadline'
[ "$(cat "$unlock")" = andr36oid-subsystem ] || fail 'readiness timeout leaked the wake lock'
grep -q 'framework power readiness timed out' "$LOG" || fail 'readiness timeout was hidden'

reset_return
mode=start-failure
restore_android
[ "$CLEANUP_FAILED" = 1 ] || fail 'framework restart failure was hidden'
hold_during_restore
grep -q 'session wake lock will expire in the kernel' "$LOG" || fail 'failed restart lost the kernel expiry'

# A stop request must allow an in-progress handback to finish beyond the
# ten-second teardown limit and must not send TERM to the active restore.
(
  reset_return
  testing_stop=1
  echo 190 > "$deadline"
  session_active() { [ "$(session_uptime_seconds)" -lt 120 ]; }
  recover_session() { :; }
  kill() { fail 'stop signalled broker during a valid handback'; }
  stop_session
  [ "$(session_uptime_seconds)" = 120 ] || fail 'stop did not wait for handback'
)

# An unresponsive runner without a handback still gets the existing hard stop.
(
  reset_return
  testing_stop=1; : > "$tmp/signals"
  session_active() { :; }
  recover_session() { :; }
  kill() { echo "$1" >> "$tmp/signals"; }
  stop_session
  [ "$(session_uptime_seconds)" = 110 ] || fail 'unresponsive runner got an extended stop wait'
  [ "$(cat "$tmp/signals")" = "$(printf '%s\n' -TERM -KILL)" ] || fail 'unresponsive runner signals changed'
)

reset_return
DID_HANDOFF=0
show_splash() { :; }
handoff || fail 'lock acquisition did not admit handoff'
[ -s "$tmp/stops" ] || fail 'fixture did not stop framework'
[ "$DID_HANDOFF" = 1 ] || fail 'handoff ownership was not recorded'

DID_HANDOFF=0
: > "$tmp/stops"
rm -f "$wake"
mkdir "$wake"
if handoff; then fail 'handoff continued without a wake lock'; fi
[ ! -s "$tmp/stops" ] && [ ! -e "$STATE/session.handoff" ] || fail 'failed acquisition affected Android'

echo 'PASS: wake lock held across EXIT and crash recovery, current power service readiness, bounded failures, stop grace, and lock-before-stop entry'
