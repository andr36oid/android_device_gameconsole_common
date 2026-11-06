#!/bin/sh
set -eu
. "$(dirname "$0")/test-lib.inc"
# Rewrite only the sysfs prefix in the fixture copy. Never write real hardware.
sed "s@/sys/devices/@$tmp/sys/devices/@g" "$broker" > "$tmp/broker"
broker="$tmp/broker"
load_functions live_backlight_allowed restore_live_backlight process_start_time recorded_process_alive \
  watch_session_markers run_session_payload
node="$tmp/sys/devices/panel/backlight/brightness"
other="$tmp/sys/devices/other/backlight/brightness"
mkdir -p "$(dirname "$node")" "$(dirname "$other")"
printf '%s %s\n' "$node" 123 "$other" 64 > "$STATE/session.brightness"
cp "$STATE/session.brightness" "$tmp/snapshot"
HANDOFF=1; DID_HANDOFF=1
: > "$ROOT/run/.a36-handoff"
echo 0 > "$node"; echo 22 > "$other"
restore_live_backlight
[ "$(cat "$node")" = 123 ] || fail 'zero brightness was not restored'
[ "$(cat "$other")" = 22 ] || fail 'nonzero guest brightness changed'
cmp "$tmp/snapshot" "$STATE/session.brightness" || fail 'guardian consumed or changed the teardown snapshot'
restore_live_backlight
[ "$(wc -l < "$LOG")" = 1 ] || fail 'repeated polling rewrote a lit backlight'
echo 0 > "$other"
restore_live_backlight
[ "$(cat "$other")" = 64 ] || fail 'second backlight did not use its own snapshot'

for gate in headless before-handoff teardown exit reboot sleeping disabled; do
  (
    case "$gate" in
      headless) HANDOFF=0 ;;
      before-handoff) DID_HANDOFF=0 ;;
      teardown) rm "$ROOT/run/.a36-handoff" ;;
      exit) : > "$ROOT/run/.a36-session-exit" ;;
      reboot) : > "$ROOT/run/.a36-session-reboot" ;;
      sleeping) : > "$ROOT/run/.a36-sleeping" ;;
      disabled) A36_BACKLIGHT_GUARD=0 ;;
    esac
    echo 0 > "$node"
    restore_live_backlight
    [ "$(cat "$node")" = 0 ] || fail "$gate allowed a brightness write"
  )
  rm -f "$ROOT/run/.a36-session-exit" "$ROOT/run/.a36-session-reboot" "$ROOT/run/.a36-sleeping"
  : > "$ROOT/run/.a36-handoff"
done

# Invalid paths/values and a captured zero cannot supply a safe restore value.
for value in 0 invalid; do
  printf '%s %s\n' "$node" "$value" > "$STATE/session.brightness"
  restore_live_backlight
  [ "$(cat "$node")" = 0 ] || fail 'unsafe snapshot was used'
done
printf '%s 123\n' "$tmp/unrelated" > "$STATE/session.brightness"
echo 0 > "$tmp/unrelated"
restore_live_backlight
[ "$(cat "$tmp/unrelated")" = 0 ] || fail 'guardian wrote an unrelated path'
rm "$STATE/session.brightness"
restore_live_backlight
cp "$tmp/snapshot" "$STATE/session.brightness"

# Exercise the actual payload-bound watcher, including marker exit and reaping.
kill_subsystem_payload() { :; }
sleep 60 & payload=$!
printf '%s\n' "$payload" >> "$tmp/pids"
watch_session_markers "$payload" & watcher=$!
printf '%s\n' "$watcher" >> "$tmp/pids"
is_restored() { [ "$(cat "$node")" = 123 ]; }
wait_until is_restored
: > "$ROOT/run/.a36-session-exit"
wait_until not_alive "$payload"
wait "$payload" 2>/dev/null || :
wait "$watcher"
echo 0 > "$node"
restore_live_backlight
[ "$(cat "$node")" = 0 ] || fail 'exit marker did not suppress repair'
echo 'PASS: live zero-only backlight repair, lifecycle gates, preserved snapshot and watcher exit'
