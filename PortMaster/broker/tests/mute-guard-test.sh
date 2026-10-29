#!/bin/sh
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions process_start_time recorded_process_alive mute_guard_owned freeze_process kill_process_children kill_tracked_tree stop_mute_guard \
  subsystem_muted mute_all_once start_mute_guard start_live_log finish_session \
  restore_android recover_session
kill_subsystem() {
  if [ -s "$tmp/guard" ]; then
    not_alive "$(cat "$tmp/guard")" || fail 'restoration started before the watchdog stopped'
  fi
}
prepare_retroarch_sync() { RA_SYNC_DIR=; }
# Keep the actual restoration/finish/recovery ordering and logger; mock hardware.
for outcome in clean crash poweroff recovery; do
  mkdir -p "$STATE/session.lock"
  printf '%s\n' "$$" > "$STATE/session.lock/pid"
  cat /proc/sys/kernel/random/boot_id > "$STATE/session.lock/boot_id"
  : > "$STATE/session.loop"
  SESSION_OUT="$STATE/session.out"
  (
    start_live_log
    # Reproduce the old ownership bug: start in a child, clean up in its parent.
    (start_mute_guard)
    cut -d' ' -f1 "$MUTE_PIDFILE" > "$tmp/guard"
    if [ "$outcome" = recovery ]; then
      recover_session "$$"
      exec 1>&3 2>&4 3>&- 4>&-
      wait "$TEE_PID"
    else
      case "$outcome" in clean) rc=0 ;; crash) rc=1 ;; poweroff) : > "$ROOT/run/.a36-session-exit"; rc=0 ;; esac
      finish_session "$rc"
    fi
  ) & finisher=$!
  printf '%s\n' "$finisher" > "$tmp/pids"
  wait_until test -s "$tmp/guard"
  guard=$(cat "$tmp/guard")
  wait_until not_alive "$finisher"
  wait "$finisher" 2>/dev/null || :
  not_alive "$guard" || fail "$outcome left the watchdog running"
  before=$(wc -l < "$tmp/mixer")
  sleep 1.1
  [ "$(wc -l < "$tmp/mixer")" = "$before" ] || fail "$outcome kept muting after restoration"
  [ ! -e "$MUTE_PIDFILE" ] || fail "$outcome retained the watchdog pidfile"
  rm -f "$tmp/guard"
done

# A still-running guard must not retain the FIFO, even before cleanup kills it.
mkdir -p "$STATE/session.lock"
printf '%s\n' "$$" > "$STATE/session.lock/pid"
(
  start_live_log
  start_mute_guard
  cut -d' ' -f1 "$MUTE_PIDFILE" > "$tmp/guard"
  exec 1>&3 2>&4 3>&- 4>&-
  wait "$TEE_PID"
  : > "$tmp/logger-eof"
) & logger_owner=$!
printf '%s\n' "$logger_owner" > "$tmp/pids"
wait_until test -f "$tmp/logger-eof"
guard=$(cat "$tmp/guard")
alive "$guard" || fail 'logger EOF test did not keep the watchdog alive'
wait "$logger_owner"
stop_mute_guard
rm -f "$tmp/guard"

# Losing the session marker or the owning process must stop the guard itself.
for loss in marker owner; do
  mkdir -p "$STATE/session.lock"
  if [ "$loss" = owner ]; then
    sleep 60 & owner_pid=$!
    printf '%s\n' "$owner_pid" > "$tmp/pids"
  else owner_pid=$$; fi
  printf '%s\n' "$owner_pid" > "$STATE/session.lock/pid"
  start_mute_guard
  guard=$(cut -d' ' -f1 "$MUTE_PIDFILE")
  if [ "$loss" = owner ]; then kill "$owner_pid"; wait "$owner_pid" 2>/dev/null || :
  else rm "$STATE/session.lock/pid"; fi
  wait_until not_alive "$guard"
  wait "$guard" 2>/dev/null || :
  stop_mute_guard
done

# A stale same-boot pidfile must not kill an unrelated, reused PID.
sleep 60 & innocent=$!
printf '%s\n' "$innocent" > "$tmp/pids"
printf '%s 1 %s\n' "$innocent" "$(cat /proc/sys/kernel/random/boot_id)" > "$MUTE_PIDFILE"
stop_mute_guard
alive "$innocent" || fail 'stale watchdog identity killed another process'
kill "$innocent"; wait "$innocent" 2>/dev/null || :
# Force cleanup of a guard blocked in a mixer child. Its child must die too.
(
  trap '' TERM
  sleep 60 & blocked_mixer=$!
  printf '%s\n' "$blocked_mixer" > "$tmp/blocked-mixer"
  printf '%s\n' "$blocked_mixer" >> "$tmp/pids"
  wait "$blocked_mixer"
) 2> "$tmp/blocked.log" & guard=$!
printf '%s\n' "$guard" >> "$tmp/pids"
wait_until test -s "$tmp/blocked-mixer"
printf '%s %s %s\n' "$guard" "$(process_start_time "$guard")" \
  "$(cat /proc/sys/kernel/random/boot_id)" > "$MUTE_PIDFILE"
stop_mute_guard
not_alive "$guard" || fail 'blocked watchdog survived forced cleanup'
not_alive "$(cat "$tmp/blocked-mixer")" || fail 'mixer child outlived forced cleanup'
echo 'mute guard tests passed (finding 1: cleanup, logger EOF, recovery, owner loss, PID reuse)'
