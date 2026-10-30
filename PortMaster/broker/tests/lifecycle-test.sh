#!/bin/sh
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions process_start_time recorded_process_alive freeze_process kill_process_children kill_tracked_tree \
  watch_session_markers run_session_payload run_es_wrapper run_session \
  session_uses_init install_systemctl_shim
SESSION_OUT="$tmp/session.out"
find_es() { ES=/usr/bin/emulationstation/emulationstation.sh; return 0; }
root_file() { return 0; }
session_active() { return 0; }
has_init() { return 1; }
start_hotkeys() { :; }
start_mute_guard() { :; }
kill_subsystem_payload() {
  # Stand in for the rootfs-scoped /proc sweep, using only our fixture children.
  [ ! -f "$tmp/fixture-pids" ] || while read -r pid; do kill -KILL "$pid" 2>/dev/null || :; done < "$tmp/fixture-pids"
}
install_systemctl_shim
mkdir "$tmp/mocks"
printf '#!/bin/sh\nexit 1\n' > "$tmp/mocks/pgrep"
printf '#!/bin/sh\nexit 1\n' > "$tmp/mocks/pkill"
chmod +x "$tmp/mocks/pgrep" "$tmp/mocks/pkill"
PATH="$tmp/mocks:$PATH"; export PATH
sed -e "s@/run/@$ROOT/run/@g" -e "s@/tmp/es-restart@$ROOT/tmp/es-restart@g" \
    -e "s@/proc/1/comm@$tmp/pid1comm@g" "$ROOT/usr/local/bin/systemctl" > "$tmp/systemctl"
shim="$tmp/systemctl"; chmod +x "$shim"
run_in_subsystem() {
  read -r fixture_pid rest < /proc/self/stat
  printf '%s\n' "$fixture_pid" >> "$tmp/pids"
  printf '%s\n' "$fixture_pid" >> "$tmp/fixture-pids"
  : > "$tmp/running"
  if [ "$behavior" = retroarch ]; then
    # Simulate .BOOT_TO_RETROARCH: a wrapper repeatedly spawning RA, without ES.
    exec sh -c 'while :; do sleep 60 & p=$!; echo "$p" >> "$1"; echo "$p" >> "$2"; wait "$p"; done' \
      wrapper "$tmp/fixture-pids" "$tmp/pids"
  fi
  exec sleep 60
}

case ${1:-all} in all|markers)
  for behavior in port retroarch bare; do
    rm -f "$ROOT/run/.a36-session-exit" "$ROOT/run/.a36-session-reboot" "$tmp/running"
    cmd=start; SESSION_MODE=wrapper
    case "$behavior" in port) cmd=run ;; bare) SESSION_MODE=bare ;; esac
    (set +e; run_session) > "$tmp/payload.log" 2>&1 & actor=$!
    printf '%s\n' "$actor" >> "$tmp/pids"
    wait_until test -f "$tmp/running"
    "$shim" poweroff
    wait_until not_alive "$actor"
    wait "$actor" 2>/dev/null || :
    while read -r pid; do wait_until not_alive "$pid"; done < "$tmp/fixture-pids"
  done
  echo 'payload marker watcher passed (finding 5: port, RetroArch loop, bare ES)'
;; esac

case ${1:-all} in all|frontend)
  # The image's RetroArch branch never launches an ES binary and loops forever.
  # Service maintenance must act on that actual payload without ending Android's
  # handoff session or killing a separate maintenance/hotkey process.
  rm -f "$ROOT/run/.a36-session-exit" "$ROOT/run/.a36-session-reboot" \
    "$ROOT/run/.a36-es-stopped" "$ROOT/run/.a36-es-start" "$ROOT/run/.a36-es-restart" "$tmp/launches"
  mkdir -p "$ROOT/home/ark/.config"
  boot_ra="$ROOT/home/ark/.config/.BOOT_TO_RETROARCH"
  : > "$boot_ra"
  run_in_subsystem() {
    read -r fixture_pid rest < /proc/self/stat
    printf '%s\n' "$fixture_pid" >> "$tmp/pids"
    printf '%s\n' "$fixture_pid" >> "$tmp/frontend-pids"
    if [ -f "$boot_ra" ]; then
      echo retroarch >> "$tmp/launches"
      exec sh -c 'while :; do sleep 60 & p=$!; echo "$p" >> "$1"; echo "$p" >> "$2"; wait "$p"; done' \
        wrapper "$tmp/frontend-pids" "$tmp/pids"
    fi
    echo es >> "$tmp/launches"
    exec sleep 60
  }
  launch_count() { [ "$(wc -l < "$tmp/launches")" = "$1" ]; }
  markers_consumed() { [ ! -f "$ROOT/run/.a36-es-start" ] && [ ! -f "$ROOT/run/.a36-es-restart" ]; }
  cmd=start; SESSION_MODE=wrapper
  sleep 60 & maintenance_pid=$!
  printf '%s\n' "$maintenance_pid" >> "$tmp/pids"
  (set +e; run_session) > "$tmp/frontend.log" 2>&1 & actor=$!
  printf '%s\n' "$actor" >> "$tmp/pids"
  wait_until test -s "$tmp/launches"
  wait_until test "$(tail -n 1 "$tmp/launches")" = retroarch
  "$shim" stop emulationstation
  wait_until markers_consumed
  # The PID file may grow until the loop has been frozen, so keep checking it.
  frontend_stopped() {
    while read -r pid; do not_alive "$pid" || return 1; done < "$tmp/frontend-pids"
  }
  wait_until frontend_stopped
  sleep 0.3
  launch_count 1 || fail 'maintenance stop relaunched the RetroArch loop'
  alive "$actor" || fail 'maintenance stop ended the session'
  alive "$maintenance_pid" || fail 'maintenance stop killed an unrelated process'
  rm "$boot_ra"
  "$shim" start emulationstation.service
  wait_until launch_count 2
  wait_until markers_consumed
  [ "$(tail -n 1 "$tmp/launches")" = es ] || fail 'start did not re-evaluate the wrapper boot mode'
  # Restart an active frontend into RA, then restart its endless loop back to ES.
  : > "$boot_ra"
  "$shim" restart emulationstation
  wait_until launch_count 3
  wait_until markers_consumed
  [ "$(tail -n 1 "$tmp/launches")" = retroarch ] || fail 'restart did not enter RetroArch mode'
  cp "$tmp/frontend-pids" "$tmp/previous-frontends"
  rm "$boot_ra"
  "$shim" restart emulationstation.service
  wait_until launch_count 4
  wait_until markers_consumed
  [ "$(tail -n 1 "$tmp/launches")" = es ] || fail 'restart left the RetroArch loop running'
  while read -r pid; do wait_until not_alive "$pid"; done < "$tmp/previous-frontends"
  alive "$actor" || fail 'frontend maintenance exhausted the crash bound'
  alive "$maintenance_pid" || fail 'restart killed an unrelated process'
  "$shim" poweroff
  wait_until not_alive "$actor"; wait "$actor" 2>/dev/null || :
  while read -r pid; do wait_until not_alive "$pid"; done < "$tmp/frontend-pids"
  kill "$maintenance_pid"; wait "$maintenance_pid" 2>/dev/null || :
  echo 'frontend service maintenance passed (stop/start/restart of RetroArch-only wrapper)'
;; esac

case ${1:-all} in all|sync)
  rm -f "$ROOT/run/.a36-session-exit" "$ROOT/run/.a36-session-reboot" "$tmp/launches"
  cmd=start
  run_in_subsystem() {
    echo launch >> "$tmp/launches"
    "$shim" stop emulationstation
    return 1
  }
  (set +e; run_es_wrapper) > "$tmp/payload.log" 2>&1 & actor=$!
  printf '%s\n' "$actor" >> "$tmp/pids"
  wait_until test -f "$tmp/launches"
  # Longer than the old four-second grace; represents finish.sh blocked in sync.
  sleep 5
  [ "$(wc -l < "$tmp/launches")" = 1 ] || fail 'ES relaunched while sync/shutdown was still in progress'
  alive "$actor" || fail 'deliberate ES stop ended the session before the power action'
  "$shim" poweroff
  wait_until not_alive "$actor"; wait "$actor" 2>/dev/null || :

  # A maintenance stop is resumed by an explicit start, even after a clean exit.
  rm -f "$ROOT/run/.a36-session-exit" "$ROOT/run/.a36-es-stopped" "$tmp/launches"
  run_in_subsystem() {
    echo launch >> "$tmp/launches"
    if [ "$(wc -l < "$tmp/launches")" = 1 ]; then "$shim" stop emulationstation; fi
    return 0
  }
  (set +e; run_es_wrapper) > "$tmp/payload.log" 2>&1 & actor=$!
  printf '%s\n' "$actor" >> "$tmp/pids"
  wait_until test -f "$ROOT/run/.a36-es-stopped"
  "$shim" start emulationstation.service
  wait_until not_alive "$actor"; wait "$actor" 2>/dev/null || :
  [ "$(wc -l < "$tmp/launches")" = 2 ] || fail 'explicit start did not resume stopped ES'
  echo 'explicit stop synchronization passed (finding 5: slow sync and maintenance resume)'
;; esac

case ${1:-all} in all|crashes)
  rm -f "$ROOT/run/.a36-session-exit" "$ROOT/run/.a36-session-reboot" \
    "$ROOT/run/.a36-es-stopped" "$ROOT/run/.a36-es-start" "$tmp/launches"
  run_in_subsystem() { echo launch >> "$tmp/launches"; return 1; }
  (set +e
    # Skip only the obsolete marker grace when testing the pre-fix crash count.
    sleep() { [ "$1" = 0.5 ] || command sleep "$@"; }
    run_es_wrapper
  ) > "$tmp/payload.log" 2>&1 & actor=$!
  printf '%s\n' "$actor" >> "$tmp/pids"
  wait_until not_alive "$actor"; wait "$actor" 2>/dev/null || :
  [ "$(wc -l < "$tmp/launches")" = 3 ] || fail 'crash bound was not exactly three launches'
  echo 'three-crash bound passed (finding 8)'
;; esac

case ${1:-all} in all|reboot)
  rm -f "$ROOT/run/.a36-session-exit" "$ROOT/run/.a36-session-reboot" \
    "$ROOT/run/.a36-es-stopped" "$ROOT/run/.a36-es-start" "$tmp/launches" "$tmp/running"
  start_hotkeys() { echo start >> "$tmp/hotkeys"; }
  run_in_subsystem() {
    echo launch >> "$tmp/launches"
    if [ "$(wc -l < "$tmp/launches")" = 1 ]; then
      read -r fixture_pid rest < /proc/self/stat
      printf '%s\n' "$fixture_pid" >> "$tmp/pids"
      printf '%s\n' "$fixture_pid" >> "$tmp/fixture-pids"
      : > "$tmp/running"
      exec sleep 60
    fi
    return 0
  }
  (set +e; run_es_wrapper) > "$tmp/payload.log" 2>&1 & actor=$!
  printf '%s\n' "$actor" >> "$tmp/pids"
  wait_until test -f "$tmp/running"
  "$shim" stop emulationstation
  "$shim" reboot
  wait_until not_alive "$actor"; wait "$actor" 2>/dev/null || :
  [ "$(wc -l < "$tmp/launches")" = 2 ] || fail 'reboot failed to relaunch ES once'
  [ "$(wc -l < "$tmp/hotkeys")" = 2 ] || fail 'reboot did not restart the stopped hotkey daemon'
  [ ! -f "$ROOT/run/.a36-es-stopped" ] || fail 'reboot retained the old stop state'
  start_hotkeys() { :; }
  echo 'wrapper reboot passed (marker interruption, hotkeys, stop state reset)'
;; esac

case ${1:-all} in all|happy)
  # Preserve the hardware-proven wrapper+handoff contract with Android/mixer
  # calls mocked: initial codec mute, ES-driven poweroff, then Android restoration.
  load_functions process_start_time recorded_process_alive mute_guard_owned freeze_process kill_process_children kill_tracked_tree \
    stop_mute_guard subsystem_muted mute_all_once start_mute_guard prepare_session_policy \
    start_live_log finish_session restore_android
  cmd=start; HANDOFF=1; SESSION_MODE=wrapper; DID_HANDOFF=1; HWC=test-composer
  prepare_retroarch_sync() { RA_SYNC_DIR=; }
  show_splash() { :; }
  start() { : > "$tmp/android-started"; }
  kill_subsystem() {
    not_alive "$(cat "$tmp/guard")" || fail 'Android restoration began with a live mute watchdog'
    kill_subsystem_payload
  }
  run_in_subsystem() {
    [ -s "$tmp/mixer" ] || fail 'wrapper started before the codec was muted'
    "$shim" poweroff
  }
  (
    set +e
    start_live_log
    prepare_session_policy
    start_mute_guard
    cut -d' ' -f1 "$MUTE_PIDFILE" > "$tmp/guard"
    run_session & SESSION_PID=$!
    wait "$SESSION_PID"; rc=$?
    finish_session "$rc"
  ) > "$tmp/happy.log" 2>&1 & actor=$!
  printf '%s\n' "$actor" >> "$tmp/pids"
  wait_until not_alive "$actor"; wait "$actor" || fail 'ES poweroff did not finish cleanly'
  [ -f "$tmp/android-started" ] || fail 'ES poweroff did not restart Android'
  grep -Fqx 'ctl.start surfaceflinger' "$tmp/properties" || fail 'SurfaceFlinger was not restored'
  grep -Fqx 'sys.andr36oid.subsystem.session ended' "$tmp/properties" || fail 'session did not finish publishing its state'
  echo 'wrapper handoff happy path passed (initial mute and ES poweroff -> Android)'
;; esac
