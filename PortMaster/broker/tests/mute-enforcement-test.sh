#!/bin/sh
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions process_start_time recorded_process_alive mute_guard_owned process_deadline_reached freeze_process kill_process_children kill_tracked_tree \
  stop_mute_guard subsystem_muted mute_all_once start_mute_guard restore_android recover_session_locked recover_session
prime_udev() { :; }
prepare_session_policy() { :; }
handoff() { :; }
enable_hardware_master() {
  [ "$(cat "$tmp/mixer-path")" = OFF ] || fail 'hardware-master setup ran before the initial mute'
  # The real setup sets a nonzero default; admission must verify mute afterwards.
  echo '235 235' > "$tmp/mixer-master"
  echo '235 235' > "$tmp/mixer-playback"
}
sed -n '/^prime_udev$/,/^# Wait/p' "$broker" > "$tmp/boundary"

tinymix() {
  case "$(cat "$tmp/mode"):$*" in
    card-error:*) echo 'Failed to open mixer' >&2; return 1 ;;
    write-error:'-D 0 Playback Path 0'|missing-route:'-D 0 Playback Path 0'|alias-error:'-D 0 Master Playback Volume 0')
      echo 'control write failed' >&2; return 1 ;;
    read-error:'-D 0 -v Playback Path') echo 'control read failed' >&2; return 1 ;;
    route-ignored:'-D 0 -v Playback Path') echo SPK_HP ;;
    volume-ignored:'-D 0 -v Master Playback Volume') echo '0 235' ;;
    empty-read:'-D 0 -v Playback Path') : ;;
    optional-alias:'-D 0') mock_tinymix "$@" | sed '/Master Playback Volume/d' ;;
    optional-alias:*'Master Playback Volume'*)
      : > "$tmp/touched-absent-alias"; return 2 ;;
    *) mock_tinymix "$@" ;;
  esac
}

# Exercise the actual fail-closed admission boundary. A successful write status
# with an unchanged route or one nonzero stereo channel must also be rejected.
for mode in missing card-error missing-route write-error read-error route-ignored volume-ignored empty-read alias-error; do
  echo "$mode" > "$tmp/mode"
  rm -f "$tmp/admitted"
  if (
    if [ "$mode" = missing ]; then
      unset -f tinymix
      PATH="$tmp/no-binaries"
    fi
    . "$tmp/boundary"
    : > "$tmp/admitted"
  ) > "$tmp/admission.log" 2>&1; then
    fail "$mode admitted an unverified muted payload"
  fi
  [ ! -e "$tmp/admitted" ] || fail "$mode ran the payload"
  [ ! -e "$MUTE_PIDFILE" ] || fail "$mode retained a mute worker"
done
grep -Fq 'unsafe readback' "$LOG" || fail 'readback failures were not logged'
grep -Fq 'control write failed' "$LOG" || fail 'mixer errors were not retained'

# A worker whose identity cannot be published must perform no mixer operations.
echo good > "$tmp/mode"
rm -f "$tmp/mixer"
if (
  mv() { return 1; }
  . "$tmp/boundary"
  : > "$tmp/admitted"
) > "$tmp/registration.log" 2>&1; then
  fail 'unrecorded mute worker admitted the payload'
fi
[ ! -e "$tmp/mixer" ] || fail 'worker touched the mixer before identity publication'
[ ! -e "$MUTE_PIDFILE" ] || fail 'registration failure retained a worker record'

# An absent legacy alias is optional; a present alias that fails above is not.
echo optional-alias > "$tmp/mode"
start_mute_guard || fail 'missing optional volume alias rejected a verified mute'
[ ! -e "$tmp/touched-absent-alias" ] || fail 'absent alias was treated as a required control'
stop_mute_guard

# Fail enforcement after admission. Test both explicit ioctl failure and writes
# that report success while leaving the route open. Use a separate session owner
# to exercise payload exit and the owner's normal cleanup path.
kill_subsystem_payload() {
  [ ! -s "$tmp/frontend" ] || kill -KILL "$(cat "$tmp/frontend")" 2>/dev/null || :
}
for mode in write-error route-ignored; do
  echo good > "$tmp/mode"
  rm -f "$tmp/admitted" "$tmp/ended" "$tmp/frontend" "$ROOT/run/.a36-session-exit"
  (
    set +e
    read -r me rest < /proc/self/stat
    printf '%s\n' "$me" > "$STATE/session.lock/pid"
    trap 'stop_mute_guard; : > "$tmp/ended"' EXIT
    trap 'exit 0' TERM
    start_mute_guard || exit 1
    sleep 60 & frontend=$!
    printf '%s\n' "$frontend" > "$tmp/frontend"
    printf '%s\n' "$frontend" >> "$tmp/pids"
    : > "$tmp/admitted"
    wait "$frontend"
  ) > "$tmp/session.log" 2>&1 & actor=$!
  printf '%s\n' "$actor" >> "$tmp/pids"
  wait_until test -f "$tmp/admitted"
  guard=$(cut -d' ' -f1 "$MUTE_PIDFILE")
  echo "$mode" > "$tmp/mode"
  wait_until test -f "$tmp/ended"
  wait_until not_alive "$actor"; wait "$actor" 2>/dev/null || :
  not_alive "$(cat "$tmp/frontend")" || fail "$mode left an audible payload running"
  not_alive "$guard" || fail "$mode retained the failed watchdog"
  grep -Fq 'enforcement failed three times; ending session' "$LOG" || fail "$mode did not report persistent failure"
  [ -f "$ROOT/run/.a36-session-exit" ] || fail "$mode did not request session termination"
  printf '%s\n' "$$" > "$STATE/session.lock/pid"
done

# Block the very first mute write, kill its startup broker, and recover using
# the actual restoration path. The write cannot complete after Android returns.
cat > "$tmp/blocked-tinymix" <<'MIXER'
#!/bin/sh
trap '' TERM
printf '%s\n' "$$" > "$A36_TEST_DIR/blocked-mixer"
printf '%s\n' "$$" >> "$A36_TEST_DIR/pids"
: > "$A36_TEST_DIR/initial-call"
while [ ! -f "$A36_TEST_DIR/release-mixer" ]; do sleep 0.05; done
: > "$A36_TEST_DIR/late-mute"
MIXER
chmod +x "$tmp/blocked-tinymix"
export A36_TEST_DIR="$tmp"
tinymix() {
  case "$*" in
    '-D 0 Playback Path 0') "$tmp/blocked-tinymix" ;;
    *) mock_tinymix "$@" ;;
  esac
}
kill_subsystem() {
  not_alive "$(cat "$tmp/blocked-mixer")" || fail 'Android restored with the initial mixer still alive'
  : > "$tmp/android-restored"
}
prepare_retroarch_sync() { RA_SYNC_DIR=; }
(
  read -r me rest < /proc/self/stat
  printf '%s\n' "$me" > "$STATE/session.lock/pid"
  start_mute_guard
) > "$tmp/startup.log" 2>&1 & starter=$!
printf '%s\n' "$starter" >> "$tmp/pids"
wait_until test -f "$tmp/initial-call"
[ -s "$MUTE_PIDFILE" ] || fail 'initial mute ran before recoverable ownership was recorded'
guard=$(cut -d' ' -f1 "$MUTE_PIDFILE")
kill -KILL "$starter"; wait "$starter" 2>/dev/null || :
recover_session "$starter"
[ -f "$tmp/android-restored" ] || fail 'startup recovery did not restore Android'
not_alive "$guard" || fail 'startup recovery retained the mixer worker'
: > "$tmp/release-mixer"
sleep 0.3
[ ! -f "$tmp/late-mute" ] || fail 'initial mixer muted Android after recovery'
[ ! -e "$MUTE_PIDFILE" ] || fail 'startup recovery retained the worker record'
echo 'mute enforcement passed (verified admission, persistent failure, interrupted initial write)'
