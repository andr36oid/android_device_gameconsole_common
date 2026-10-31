#!/bin/sh
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions track_mount forget_mount root_path subsystem_muted wait_service_stopped stop_android_display handoff process_start_time \
  recorded_process_alive process_deadline_reached freeze_process kill_process_children kill_tracked_tree \
  stop_mute_guard restore_android recover_session_locked recover_session record_session_loop release_session_loop
# A property change during preparation cannot split watchdog/filter policy.
(
  SESSION_MUTED=1; getprop() { echo 0; }
  subsystem_muted || fail 'session mute changed after capture'
  SESSION_MUTED=0; getprop() { echo 1; }
  if subsystem_muted; then fail 'session admission policy changed mid-launch'; fi
)
# A terminal init state and a real DRM probe are both required for admission.
(
  HWC=composer; GUEST=guest_mock; count=0; mode=transition; stop_failed=0; drm_failed=0
  sleep() { count=$((count+1)); }
  getprop() {
    case "$1" in init.svc.*)
      if [ "$mode" = stuck ] || { [ "$mode" = transition ] && [ "$count" -lt 2 ]; }; then echo stopping; else echo stopped; fi;;
    esac
  }
  stop() { [ "$stop_failed" = 0 ]; }
  setprop() { :; }
  guest_mock() { [ "$1" = --check-drm ] && [ "$drm_failed" = 0 ]; }
  show_splash() { : > "$tmp/splash"; }
  handoff || fail 'delayed service stop did not admit released DRM'
  [ "$count" -ge 2 ] || fail 'stopping was accepted as stopped'
  for failure in stuck stop drm; do
    rm -f "$tmp/splash"
    mode=stopped; stop_failed=0; drm_failed=0; count=0
    case "$failure" in stuck) mode=stuck;; stop) stop_failed=1;; drm) drm_failed=1;; esac
    if handoff; then fail "$failure admitted the guest"; fi
    [ ! -e "$tmp/splash" ] || fail "$failure reached guest admission"
    [ "$count" -le 100 ] || fail 'handoff exceeded deadline'
  done
)
# No single freeze/reap can wait beyond the shared tree deadline. Model the
# non-stoppable /proc state of a D-state ioctl, rather than interruptible sleep.
(
  sleep 60 & target=$!
  printf '%s\n' "$target" >> "$tmp/pids"
  stamp=$(process_start_time "$target"); boot=$(cat /proc/sys/kernel/random/boot_id)
  printf '%s %s %s\n' "$target" "$stamp" "$boot" > "$MUTE_PIDFILE"
  cat() {
    if [ "$1" = "/proc/$target/stat" ]; then printf '%s (blocked) D 1\n' "$target"; else command cat "$@"; fi
  }
  process_start_time() { [ "$1" = "$target" ] && printf '%s\n' "$stamp"; }
  checks=0
  process_deadline_reached() { checks=$((checks+1)); [ "$checks" -ge 2 ]; }
  if stop_mute_guard; then fail 'non-stoppable worker reported reaped'; fi
  [ "$checks" -ge 2 ] || fail 'freeze did not check deadline'
  [ -s "$MUTE_PIDFILE" ] || fail 'timeout discarded blocked worker identity'
  kill -KILL "$target"; wait "$target" 2>/dev/null || :
)
# Cleanup progresses to guest termination and display recovery on a worker
# timeout, retaining codec snapshots and withholding conflicting audio restart.
(
  HWC=composer; DID_HANDOFF=1; LOOPDEV=; : > "$STATE/session.mixer"
  stop_mute_guard() { return 1; }
  kill_subsystem() { : > "$tmp/guest-terminated"; }
  prepare_retroarch_sync() { RA_SYNC_DIR=; }
  restore_guest_hardware() { fail 'codec restoration raced a live worker'; }
  release_session_loop() { :; }
  show_splash() { :; }; sleep() { :; }
  start() { fail 'framework/audio restarted with unresolved codec worker'; }
  restore_android
  [ "$CLEANUP_FAILED" = 1 ] || fail 'worker timeout was lost'
  [ -f "$tmp/guest-terminated" ] || fail 'worker timeout skipped guest termination'
  [ -f "$STATE/session.mixer" ] || fail 'worker timeout discarded codec snapshot'
  grep -Fq 'ctl.start surfaceflinger' "$tmp/properties" || fail 'worker timeout skipped display recovery'
)
rm -f "$MUTE_PIDFILE" "$STATE/session.mixer"
# Journal identity precedes attachment, survives release errors, and is cleared
# independently of hardware restoration. A legacy bare name is never detached.
(
  LOOPDEV=/dev/loop17; SUPPORT=support_mock; detach_failed=0
  support_mock() {
    case "$1" in fsync) return 0;; retire-owned)
      [ "$*" = 'retire-owned /dev/loop17 42 91 512' ] || fail 'lost loop ownership fields'
      [ "$detach_failed" = 0 ] || return 1
      printf '%s\n' "$*" >> "$tmp/detached";; *) return 1;; esac
  }
  record_session_loop 42 91 512
  detach_failed=1
  if release_session_loop; then fail 'release failure reported success'; fi
  [ -s "$STATE/session.loop" ] || fail 'release error discarded identity'
  detach_failed=0; release_session_loop
  [ ! -e "$STATE/session.loop" ] || fail 'released loop left a destructive journal'
  release_session_loop
  [ "$(wc -l < "$tmp/detached")" = 1 ] || fail 'repeat cleanup detached twice'
  echo /dev/loop17 > "$STATE/session.loop"
  if release_session_loop; then fail 'legacy loop without identity accepted'; fi
  [ "$(wc -l < "$tmp/detached")" = 1 ] || fail 'bare device name detached'
)
: > "$STATE/session.loop"
# Release journals are consumed per resource even while hardware recovery
# fails; the second pass must not touch the freed loop number again.
(
  HWC=composer; DID_HANDOFF=0; LOOPDEV=/dev/loop17; SUPPORT=support_mock
  support_mock() { case "$1" in fsync) :;; retire-owned) echo detached >> "$tmp/release-count";; *) return 1;; esac; }
  stop_mute_guard() { :; }; kill_subsystem() { :; }
  prepare_retroarch_sync() { RA_SYNC_DIR=; }; restore_guest_hardware() { return 1; }
  umount() { :; }
  track_mount "$ROOT/first"; track_mount "$ROOT/second"
  record_session_loop 42 91 512
  restore_android
  [ "$CLEANUP_FAILED" = 1 ] || fail 'hardware failure was lost'
  [ -z "$(cat "$STATE/session.mounts")" ] || fail 'released mounts retained in journal'
  [ ! -e "$STATE/session.loop" ] || fail 'released loop retained after hardware failure'
  restore_android
  [ "$(wc -l < "$tmp/release-count")" = 1 ] || fail 'hardware retry repeated loop release'
)
# Recovery publishes a failed completion state without claiming session ended.
(
  restore_android() { CLEANUP_FAILED=1; }
  : > "$tmp/properties"
  if recover_session_locked "$$"; then fail 'failed recovery reported success'; fi
  grep -Fqx 'sys.andr36oid.subsystem.session cleanup_failed' "$tmp/properties" || fail 'missing cleanup failure state'
  if grep -Fqx 'sys.andr36oid.subsystem.session ended' "$tmp/properties"; then fail 'failed recovery published ended'; fi
  [ -d "$STATE/session.lock" ] || fail 'failed recovery discarded its journal'
)
# Discarding stale-boot evidence must also report filesystem cleanup errors.
(
  STATE="$tmp/old-boot"; MUTE_PIDFILE="$STATE/mute"
  mkdir -p "$STATE/session.lock"
  echo previous-boot > "$STATE/session.lock/boot_id"
  echo "$$" > "$STATE/session.lock/pid"
  rmdir() { return 1; }
  : > "$tmp/properties"
  if recover_session_locked "$$"; then fail 'stale journal removal failure reported success'; fi
  grep -Fqx 'sys.andr36oid.subsystem.session cleanup_failed' "$tmp/properties" || fail 'stale journal failure was not published'
)
# Gamma recovery must stop active Android display owners before SETGAMMA.
(
  HWC=composer; GUEST=guest_mock; DID_HANDOFF=1; display=running; fail_display=0
  : > "$STATE/session.gamma"
  stop_mute_guard() { :; }; kill_subsystem() { :; }
  prepare_retroarch_sync() { RA_SYNC_DIR=; }; release_session_loop() { :; }
  stop() { [ "$fail_display" = 0 ] || return 1; display=stopped; }
  setprop() { case "$1" in ctl.stop) display=stopped;; esac; }
  getprop() { echo "$display"; }
  guest_mock() { [ "$1" = --check-drm ] && [ "$display" = stopped ]; }
  restore_guest_hardware() { [ "$display" = stopped ] || fail 'restored gamma under active Android'; rm -f "$STATE/session.gamma"; }
  show_splash() { :; }; sleep() { :; }; start() { display=running; }
  restore_android
  [ "$CLEANUP_FAILED" = 0 ] && [ ! -f "$STATE/session.gamma" ] || fail 'gamma retry was not consumed'
  : > "$STATE/session.gamma"; display=running; fail_display=1
  restore_android
  [ "$CLEANUP_FAILED" = 1 ] && [ -f "$STATE/session.gamma" ] || fail 'failed gamma handoff lost recovery state'
)
rm -f "$STATE/session.gamma"
# Execute actual dispatch branches so a returning failure cannot be masked.
awk '/^  stop-prop\)/ {p=1} p {print} /^  recover\)/ {exit}' "$broker" > "$tmp/dispatch"
for cmd in stop-prop stop recover; do
  if (
    stop_session() { return 1; }; recover_session() { return 1; }
    eval "case \"\$cmd\" in $(cat "$tmp/dispatch") esac"
  ); then fail "$cmd hid cleanup failure"; fi
done
# Install the native override twice; an alias targeting event17 must never
# send a button query to event2. The upstream fixture uses bash syntax.
A36_TEST_BROKER=${broker%/*}/andr36oid-guest.sh; broker=$A36_TEST_BROKER
load_functions repair_native_buttonmon
mkdir -p "$ROOT/usr/local/bin" "$tmp/input/by-path"
cp "$(dirname "$0")/fixtures/native-helpers/buttonmon.sh" "$ROOT/usr/local/bin/buttonmon.sh"
repair_native_buttonmon
cp "$ROOT/usr/local/bin/buttonmon.sh" "$tmp/once"
repair_native_buttonmon
cmp "$tmp/once" "$ROOT/usr/local/bin/buttonmon.sh" || fail 'helper repair was not idempotent'
: > "$tmp/input/event17"
ln -s ../event17 "$tmp/input/by-path/platform-odroidgo3-joypad-event-joystick"
sed "s@/dev/input@$tmp/input@g" "$ROOT/usr/local/bin/buttonmon.sh" > "$tmp/buttonmon"
A36_TEST_QUERY="$tmp/query"; export A36_TEST_QUERY
bash -c '. "$1"; evtest() { printf "%s\n" "$*" > "$A36_TEST_QUERY"; }; Test_Button_A' bash "$tmp/buttonmon"
grep -Fq -- "--query $tmp/input/event17 " "$tmp/query" || fail 'native alias did not follow event17'
# Both shell interpreters also exercise the actual checked scalar and kernel
# syscall policy code through the same compiler harness.
cc -Wall -Wextra -Werror -O2 "$(dirname "$0")/supervisor-policy-test.c" -o "$tmp/policy"
"$tmp/policy" > "$tmp/policy.log" 2>&1 || { cat "$tmp/policy.log"; fail 'supervisor policy regression'; }
cc -Wall -Wextra -Werror -O2 "$(dirname "$0")/loop-retire-test.c" -o "$tmp/loop-retire"
"$tmp/loop-retire" > "$tmp/loop-retire.log" 2>&1 || { cat "$tmp/loop-retire.log"; fail 'loop ownership regression'; }
echo 'PASS: review fixes: stopped+DRM admission, bounded blocked worker, loop identity journal, gamma recovery ordering, failed Stop status, native event17 alias, checked scalars and inherited mute filter'
