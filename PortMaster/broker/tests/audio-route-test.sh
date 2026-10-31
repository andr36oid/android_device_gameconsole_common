#!/bin/sh
# Host-only codec/DT fixtures; no hardware, mounts or Android services.
set -eu
. "$(dirname "$0")/test-lib.inc"
# Keep the production setup code, substituting only Android's tinymix location.
sed 's@/system/bin/tinymix@tinymix@g' "$broker" > "$tmp/broker"
broker="$tmp/broker"
load_functions enable_hardware_master subsystem_muted audio_route_target audio_route_once audio_route_owned \
  process_start_time recorded_process_alive process_deadline_reached freeze_process kill_process_children kill_tracked_tree \
  stop_audio_route_guard start_audio_route_guard mute_guard_owned mute_all_once stop_mute_guard start_mute_guard \
  restore_android recover_session_locked recover_session prepare_session_policy session_uses_init install_systemctl_shim \
  root_path install_session_units
production_broker=$(CDPATH='' cd -- "$(dirname "$0")/.." && pwd)
broker="$production_broker/andr36oid-guest.sh"
load_functions capture_guest_mixer restore_guest_mixer restore_guest_hardware write_guest_session
trap 'stop_audio_route_guard || :; cleanup' EXIT
A36_DT_MODEL="$tmp/model"; A36_FORCE_SPK_HP="$tmp/force"
GUEST=fixture_guest; HWC=fixture-composer; SESSION_MUTED=0; DID_HANDOFF=0
fixture_guest() {
  case "$1" in
    --headphone-state)
      case "$(cat "$tmp/jack")" in fail) return 1 ;; fail-one) echo 1; return 1 ;; *) cat "$tmp/jack" ;; esac ;;
    --capture-mixer)
      [ "$(cat "$tmp/capture-fail")" = 0 ] || return 1
      for control in 'Playback Path' 'Master Playback Volume' 'Playback Volume' 'Playback Hardware Master Switch'; do
        value=$(tinymix -D 0 -v "$control") || return 1
        printf '%s\t%s\n' "$control" "$value"
      done ;;
    *) return 0 ;;
  esac
}
tinymix() {
  [ "$1" != -D ] || shift 2
  if [ "$#" = 0 ]; then
    printf '0\tENUM\t1\tPlayback Path                            OFF\n'
    printf '1\tINT\t2\tMaster Playback Volume                   192 191\n'
    printf '2\tINT\t2\tPlayback Volume                          220 219\n'
    return 0
  fi
  local reading=0 control key
  if [ "$1" = -v ]; then reading=1; shift; fi
  control=$1; shift
  case "$control" in
    'Playback Path') key=path ;;
    'Master Playback Volume') key=master ;;
    'Playback Volume') key=playback ;;
    'Playback Hardware Master Switch') key=switch ;;
    *) return 1 ;;
  esac
  if [ "$reading" = 1 ]; then
    cat "$tmp/codec-$key"
    [ "$(cat "$tmp/write-mode")" != read-error ] || return 1
    return 0
  fi
  # Any codec mutation before a complete snapshot violates admission ordering.
  [ -s "$STATE/session.mixer" ] || fail 'write before Android mixer capture'
  printf '%s %s\n' "$control" "$*" >> "$tmp/writes"
  case "$(cat "$tmp/write-mode")" in
    write-error) return 1 ;;
    ignored) return 0 ;;
    blocked) if [ "$key" = path ]; then "$tmp/blocked-route"; return; fi ;;
  esac
  if [ "$key" = path ] && [ "$1" = 0 ]; then echo OFF > "$tmp/codec-$key"
  elif [ "$key" = master ] || [ "$key" = playback ]; then
    if [ "$#" = 1 ]; then printf '%s %s\n' "$1" "$1" > "$tmp/codec-$key"
    else printf '%s\n' "$*" > "$tmp/codec-$key"; fi
  else printf '%s\n' "$*" > "$tmp/codec-$key"; fi
}
find_es() { return 0; }
has_init() { return 1; }
prime_udev() { :; }
prepare_guest_hardware() { capture_guest_mixer; }
handoff() { DID_HANDOFF=1; }
install_systemctl_shim() { :; }
prepare_retroarch_sync() { RA_SYNC_DIR=; }
kill_subsystem() {
  [ ! -f "$STATE/audio-route.pid" ] || fail 'payload teardown before route worker stopped'
}
kill_subsystem_payload() { : > "$tmp/payload-killed"; }
start() { :; }
show_splash() { :; }
stop_android_display() { :; }
# Only restore_android's display restart delay is irrelevant to the fixture.
sleep() { case "$*" in 1) command sleep 0.1 ;; *) command sleep "$@" ;; esac; }
sed -n '/^prime_udev$/,/^# Wait/p' "$production_broker/andr36oid-subsystem.sh" > "$tmp/boundary"
reset_codec() {
  stop_audio_route_guard
  echo "$1" > "$tmp/codec-path"
  echo '192 191' > "$tmp/codec-master"
  echo '220 219' > "$tmp/codec-playback"
  echo 0 > "$tmp/codec-switch"
  echo good > "$tmp/write-mode"; echo 0 > "$tmp/capture-fail"
  rm -f "$tmp/writes" "$tmp/payload-killed" "$ROOT/run/.a36-session-exit" "$STATE/session.mixer"
  DID_HANDOFF=0; SESSION_OWNED=1; SESSION_MUTED=0
}
select_board() {
  echo N > "$A36_FORCE_SPK_HP"
  case "$1" in
    detected) printf 'Game Console R35S/R36S\000' > "$A36_DT_MODEL" ;;
    combined) echo unknown-clone > "$A36_DT_MODEL"; echo Y > "$A36_FORCE_SPK_HP" ;;
    mechanical) echo 'R36S clone Soy Sauce V04, Panel 2 (experimental)' > "$A36_DT_MODEL" ;;
    unknown) echo unvalidated-clone > "$A36_DT_MODEL" ;;
  esac
}
route_is() { [ "$(cat "$tmp/codec-path")" = "$1" ]; }
restored() {
  [ "$CLEANUP_FAILED" = 0 ] || fail 'cleanup failed'
  route_is "$1" || fail 'lost Android route'
  [ "$(cat "$tmp/codec-master")" = '192 191' ] || fail 'lost master stereo volume'
  [ "$(cat "$tmp/codec-playback")" = '220 219' ] || fail 'lost playback stereo volume'
  [ "$(cat "$tmp/codec-switch")" = 0 ] || fail 'lost hardware switch'
  [ ! -f "$STATE/session.mixer" ] || fail 'restore left a snapshot'
  [ ! -f "$STATE/audio-route.pid" ] || fail 'restore left a route worker'
}
# Actual common admission: OFF must become an explicit route for every class.
for board in detected combined mechanical unknown; do
  select_board "$board"
  for inserted in 0 1; do
    reset_codec OFF; echo "$inserted" > "$tmp/jack"
    . "$tmp/boundary"
    expected=SPK
    case "$board:$inserted" in detected:1) expected=HP ;; combined:*|mechanical:*) expected=SPK_HP ;; esac
    route_is "$expected" || fail "$board/$inserted inherited OFF"
    cp "$STATE/session.mixer" "$tmp/snapshot"
    # Exercise both normal cleanup and guardian recovery against the same policy.
    if [ "$inserted" = 0 ]; then restore_android; else recover_session "$$"; fi
    restored OFF
    mkdir -p "$STATE/session.lock"
    printf '%s\n' "$$" > "$STATE/session.lock/pid"
    cat /proc/sys/kernel/random/boot_id > "$STATE/session.lock/boot_id"
    : > "$STATE/session.loop"
    # Quiescence: no late route write after Android regained its captured state.
    count=$(wc -l < "$tmp/writes"); command sleep 0.2
    [ "$(wc -l < "$tmp/writes")" = "$count" ] || fail 'late route write after restore'
  done
  # Startup failure AFTER volume setup must still restore a non-OFF entry route.
  for mode in ignored write-error read-error; do
    reset_codec RING_HP; echo 0 > "$tmp/jack"
    capture_guest_mixer; DID_HANDOFF=1; echo "$mode" > "$tmp/write-mode"
    if start_audio_route_guard; then fail "$mode admitted payload"; fi
    echo good > "$tmp/write-mode"
    restore_android; restored RING_HP
  done
  # Muted admission must never start the route worker or reopen the route.
  reset_codec OFF; SESSION_MUTED=1; echo 1 > "$tmp/jack"
  . "$tmp/boundary"
  route_is OFF || fail "$board muted route is open"
  for key in master playback; do
    [ "$(cat "$tmp/codec-$key")" = '0 0' ] || fail "$board muted volume is nonzero"
  done
  [ ! -f "$STATE/audio-route.pid" ] || fail 'mute spawned an audible route owner'
  ! grep -Eq '^Playback Path (SPK|HP)' "$tmp/writes" || fail 'mute reopened route transiently'
  command sleep 0.2
  route_is OFF || fail 'mute changed after admission'
  restore_android; restored OFF
  SESSION_MUTED=0
done
# Follow jack changes during playback, including normalized active-low/flip
# states supplied by the kernel; volume keys must not be reset by route polling.
select_board detected; reset_codec OFF; echo 0 > "$tmp/jack"
. "$tmp/boundary"
echo '180 179' > "$tmp/codec-playback"
echo 1 > "$tmp/jack"; wait_until route_is HP
echo 0 > "$tmp/jack"; wait_until route_is SPK
[ "$(cat "$tmp/codec-playback")" = '180 179' ] || fail 'jack change reset playback volume'
# Absent, invalid, or failed detection explicitly selects speaker; detection
# recovery resumes tracking without inheriting a stale HP or OFF state.
for state in fail invalid fail-one; do
  echo 1 > "$tmp/jack"; wait_until route_is HP
  echo "$state" > "$tmp/jack"; wait_until route_is SPK
done
# A runtime forced-combined override takes precedence over detection.
echo Y > "$A36_FORCE_SPK_HP"; wait_until route_is SPK_HP
restore_android; restored OFF
# A present board with failed enforcement requests termination, then restores.
select_board detected; reset_codec OFF; echo 0 > "$tmp/jack"
. "$tmp/boundary"
echo write-error > "$tmp/write-mode"; echo 1 > "$tmp/jack"
wait_until test -f "$tmp/payload-killed"
[ -f "$ROOT/run/.a36-session-exit" ] || fail 'route failure did not request teardown'
echo good > "$tmp/write-mode"
restore_android; restored OFF
# Missing DT/module files also have a defined default.
rm -f "$A36_DT_MODEL" "$A36_FORCE_SPK_HP"
[ "$(audio_route_target)" = SPK ] || fail 'missing board metadata has no speaker default'
# Capture failure must prevent any route/volume mutation.
reset_codec OFF; echo 1 > "$tmp/capture-fail"
if (. "$tmp/boundary"); then fail 'capture failure admitted session'; fi
[ ! -f "$tmp/writes" ] || fail 'failed capture mutated codec'
# Failure to publish the worker identity must precede all codec mutations.
reset_codec OFF; capture_guest_mixer; DID_HANDOFF=1
if (mv() { return 1; }; start_audio_route_guard); then fail 'unrecorded route owner admitted'; fi
[ ! -f "$tmp/writes" ] || fail 'unrecorded route owner touched codec'
# Kill a broker during its first route write. Recovery must kill the registered
# worker AND its blocked mixer child before restoring the Android snapshot.
cat > "$tmp/blocked-route" <<'BLOCKED'
#!/bin/sh
trap '' TERM
printf '%s\n' "$$" > "$A36_TEST_DIR/blocked-pid"
printf '%s\n' "$$" >> "$A36_TEST_DIR/pids"
: > "$A36_TEST_DIR/route-write-entered"
while [ ! -f "$A36_TEST_DIR/release-route" ]; do sleep 0.05; done
: > "$A36_TEST_DIR/late-route"
BLOCKED
chmod +x "$tmp/blocked-route"
export A36_TEST_DIR="$tmp"
select_board detected; reset_codec OFF; echo 0 > "$tmp/jack"
capture_guest_mixer; DID_HANDOFF=1; echo blocked > "$tmp/write-mode"
(
  read -r me rest < /proc/self/stat
  printf '%s\n' "$me" > "$STATE/session.lock/pid"
  start_audio_route_guard
) > "$tmp/startup.log" 2>&1 & starter=$!
printf '%s\n' "$starter" >> "$tmp/pids"
wait_until test -f "$tmp/route-write-entered"
[ -s "$STATE/audio-route.pid" ] || fail 'route write before worker publication'
blocked_pid=$(cat "$tmp/blocked-pid")
kill -KILL "$starter"; wait "$starter" 2>/dev/null || :
echo good > "$tmp/write-mode"
recover_session "$starter"; restored OFF
not_alive "$blocked_pid" || fail 'restoration raced an in-flight route write'
: > "$tmp/release-route"; command sleep 0.2
[ ! -f "$tmp/late-route" ] || fail 'blocked mixer wrote after Android restoration'
mkdir -p "$STATE/session.lock"
printf '%s\n' "$$" > "$STATE/session.lock/pid"
cat /proc/sys/kernel/random/boot_id > "$STATE/session.lock/boot_id"
: > "$STATE/session.loop"
# Headless admission must leave Android's mixer alone, with no snapshot needed.
reset_codec OFF; HANDOFF=0
. "$tmp/boundary"
[ ! -f "$tmp/writes" ] || fail 'headless route policy changed Android audio'
HANDOFF=1
# Verify the deployed guest ownership policy, while keeping volume operations.
select_board detected; SESSION_MUTED=0
prepare_session_policy
[ -f "$ROOT/run/.a36-host-audio" ] || fail 'missing host route ownership marker'
write_guest_session
grep -Fq 'else PATH=/run/andr36oid-audio-bin:$PATH /usr/local/bin/ogage &' "$ROOT/run/andr36oid-session.sh" || fail 'wrapper ogage bypasses route filter'
sed -e "s@/run/@$ROOT/run/@g" -e 's@exec /usr/bin/amixer@printf "%s\\n"@' "$ROOT/run/andr36oid-audio-bin/amixer" > "$tmp/amixer-filter"
for command in sset cset; do
  [ -z "$(sh "$tmp/amixer-filter" -q "$command" "'Playback Path'" HP)" ] || fail 'ogage route not filtered'
done
[ "$(sh "$tmp/amixer-filter" -q sset Playback 1%+)" = "$(printf '%s\n' -q sset Playback 1%+)" ] || fail 'ogage volume command was lost'
sed -n '/^SESSION_UNITS=/,/^$/p' "$production_broker/andr36oid-subsystem.sh" > "$tmp/units"
. "$tmp/units"
install_session_units
for unit in audiopath audiostate; do
  grep -Fqx 'ConditionPathExists=!/run/.a36-host-audio' "$ROOT/etc/systemd/system/$unit.service.d/20-audio-policy.conf" || fail "$unit competes with host routes"
done
grep -Fq 'Environment=PATH=/run/andr36oid-audio-bin:' "$ROOT/etc/systemd/system/ogage.service.d/30-host-route.conf" || fail 'systemd ogage bypasses filter'
sed "s@/run/@$ROOT/run/@g" "$ROOT/usr/local/libexec/a36-session-fixvolume" > "$tmp/fixvolume"
sh "$tmp/fixvolume" || fail 'host route policy allowed fixvolume execution'
cc -Wall -Wextra -Werror -O2 "$(dirname "$0")/headphone-state-test.c" -o "$tmp/headphone-test"
"$tmp/headphone-test"
echo 'PASS: audible route admission/tracking, mute isolation, per-board restoration and guest ownership'
