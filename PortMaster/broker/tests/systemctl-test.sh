#!/bin/sh
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions session_uses_init install_systemctl_shim
find_es() { ES=/usr/bin/emulationstation/emulationstation.sh; return 0; }
has_init() { INIT=/sbin/init; return 0; }
install_systemctl_shim
original="$ROOT/usr/local/bin/systemctl"
shim="$tmp/systemctl"
# Redirect only the generated shim's absolute runtime/binary paths into a sandbox.
# Process matching and signals are real; ogage's setsid launch is mocked.
mkdir -p "$tmp/bin" "$tmp/es" "$tmp/mocks"
ln -s /bin/sleep "$tmp/es/emulationstation"
ln -s /bin/sleep "$tmp/es/emulationstation.sh"
ln -s /bin/sleep "$tmp/bin/ogage"
cat > "$tmp/mocks/setsid" <<'MOCK'
#!/bin/sh
printf '%s\n' "$*" >> "$A36_TEST_CALLS"
printf '%s\n' "$PATH" > "$A36_TEST_CALLS.path"
MOCK
printf '#!/bin/sh\nexit 1\n' > "$tmp/mocks/pkill"
chmod +x "$tmp/mocks/setsid" "$tmp/mocks/pkill"
export A36_TEST_CALLS="$tmp/calls"
PATH="$tmp/mocks:$PATH"; export PATH
sed -e "s@/usr/bin/emulationstation/emulationstation@$tmp/es/emulationstation@g" \
    -e "s@/usr/local/bin/ogage@$tmp/bin/ogage@g" \
    -e "s@/usr/bin/systemctl@$tmp/real-systemctl@g" \
    -e "s@/bin/systemctl@$tmp/no-client@g" \
    -e "s@/proc/1/comm@$tmp/pid1comm@g" \
    -e "s@/run/@$ROOT/run/@g" -e "s@/tmp/es-restart@$ROOT/tmp/es-restart@g" \
    "$original" > "$shim"
chmod +x "$shim"
start_es() {
  "$tmp/es/emulationstation" 60 & es_pid=$!
  printf '%s\n' "$es_pid" >> "$tmp/pids"
}
expect_failure() { if "$@" > "$tmp/answer" 2>&1; then fail "unexpected success: $*"; fi; }

case ${1:-all} in all|matching)
  "$tmp/es/emulationstation.sh" 60 & wrapper_pid=$!
  printf '%s\n' "$wrapper_pid" >> "$tmp/pids"
  # The same binary path inside a shell's arguments must not match that shell.
  sh -c 'sleep 60 & echo $! >> "$1"; wait' broker-shell "$tmp/pids" "$tmp/es/emulationstation" & broker_pid=$!
  printf '%s\n' "$broker_pid" >> "$tmp/pids"
  for verb in stop restart poweroff reboot; do
    rm -f "$ROOT/run/.a36-session-exit" "$ROOT/run/.a36-session-reboot"
    start_es
    [ "$(cat "/proc/$es_pid/comm")" = emulationstatio ] || fail 'fixture did not exercise comm truncation'
    [ "$("$shim" is-active emulationstation.service)" = active ] || fail 'running ES reported inactive'
    case "$verb" in stop|restart) "$shim" "$verb" emulationstation.service ;; *) "$shim" "$verb" ;; esac
    wait_until not_alive "$es_pid"
    wait "$es_pid" 2>/dev/null || :
    alive "$wrapper_pid" || fail "$verb killed the .sh wrapper"
    alive "$broker_pid" || fail "$verb killed the broker shell"
    expect_failure "$shim" is-active emulationstation
  done
  echo 'systemctl matching passed (finding 2)'
;; esac
case ${1:-all} in all|policy)
  : > "$tmp/calls"
  for verb in start restart; do
    rm -f "$ROOT/run/.a36-handoff" "$ROOT/run/.a36-muted"
    "$shim" "$verb" ogage.service
    sleep 0.1
    [ ! -s "$tmp/calls" ] || fail 'ogage started without handoff'
    : > "$ROOT/run/.a36-handoff"; : > "$ROOT/run/.a36-muted"
    "$shim" "$verb" ogage
    sleep 0.1
    [ ! -s "$tmp/calls" ] || fail 'ogage started during night mute'
  done
  rm "$ROOT/run/.a36-muted"
  "$shim" restart ogage
  wait_until test -s "$tmp/calls.path"
  case "$(cat "$tmp/calls.path")" in
    "$ROOT/run/andr36oid-audio-bin":*) : ;;
    *) fail 'restarted ogage bypassed host route filter' ;;
  esac
  echo 'systemctl ogage policy passed (finding 3 and host route ownership)'
;; esac
case ${1:-all} in all|manager)
  # Test retained-shim delegation with exact argument boundaries.
  cat > "$tmp/real-systemctl" <<'REAL'
#!/bin/sh
printf '<%s>\n' "$@" > "$A36_TEST_CALLS"
exit 7
REAL
  chmod +x "$tmp/real-systemctl"
  echo systemd > "$ROOT/run/.a36-session-manager"
  rc=0; "$shim" --job-mode replace stop 'a unit.service' || rc=$?
  [ "$rc" = 7 ] || fail 'shim did not delegate to the real client'
  printf '<%s>\n' --job-mode replace stop 'a unit.service' > "$tmp/expected"
  cmp -s "$tmp/expected" "$tmp/calls" || fail 'delegation changed arguments'
  # Mode transitions must remove our stale overlay shim for all init selections.
  for selection in explicit headless init-only; do
    SESSION_MODE=wrapper; HANDOFF=1
    find_es() { ES=/usr/bin/emulationstation/emulationstation.sh; return 0; }
    install_systemctl_shim
    case "$selection" in
      explicit) SESSION_MODE=systemd ;;
      headless) HANDOFF=0 ;;
      init-only) find_es() { return 1; } ;;
    esac
    install_systemctl_shim
    [ ! -e "$original" ] || fail "$selection left the shim ahead of systemd"
  done
  echo 'systemctl manager selection passed (finding 4)'
;; esac
case ${1:-all} in all|parsing)
  rm -f "$ROOT/run/.a36-session-manager" "$ROOT/run/.a36-muted"
  : > "$ROOT/run/.a36-handoff"
  start_es
  expect_failure "$shim" stop emulationstation-backup.service
  alive "$es_pid" || fail 'substring unit stopped ES'
  expect_failure "$shim" stop 'emulationstation.service ogage.service'
  alive "$es_pid" || fail 'unit argument boundaries were flattened'
  "$shim" --job-mode replace --type service stop emulationstation.service
  wait_until not_alive "$es_pid"; wait "$es_pid" 2>/dev/null || :
  start_es
  : > "$tmp/calls"
  "$shim" --job-mode=replace restart emulationstation.service --type=service ogage.service
  wait_until not_alive "$es_pid"; wait "$es_pid" 2>/dev/null || :
  wait_until test -s "$tmp/calls"
  for syntax in --unknown --user --type --job-mode; do expect_failure "$shim" "$syntax"; done
  expect_failure "$shim" --type socket stop emulationstation
  expect_failure "$shim" reload emulationstation
  expect_failure "$shim" stop
  expect_failure "$shim" poweroff emulationstation
  echo 'systemctl parsing passed (finding 6)'
;; esac
