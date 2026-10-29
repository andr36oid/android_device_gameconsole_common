#!/bin/sh
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions process_start_time recorded_process_alive mute_guard_owned kill_mute_guard_children stop_mute_guard \
  subsystem_muted mute_all_once start_mute_guard prepare_session_policy session_uses_init \
  install_systemctl_shim install_session_units boot_subsystem_session
CHROOT=/bin/false
SESSION_UNITS='audiopath.service audiostate.service ogage.service emulationstation.service'
mkdir -p "$ROOT/usr/bin/emulationstation" "$ROOT/etc"
find_es() { ES=/usr/bin/emulationstation/emulationstation.sh; return 0; }
has_init() { INIT=/sbin/init; return 0; }
root_file() { return 0; }
prime_udev() { :; }
handoff() { :; }
enable_hardware_master() { :; }
# Exercise the actual common startup boundary rather than duplicating its calls.
sed -n '/^prime_udev$/,/^# Wait/p' "$broker" > "$tmp/boundary"
unshare() {
  [ -s "$tmp/mixer" ] || fail 'init was booted before tinymix applied night mute'
  printf '%s\n' "$*" > "$tmp/init"
}
for SESSION_MODE in systemd wrapper bare; do
  rm -f "$tmp/mixer"
  . "$tmp/boundary"
  [ -s "$tmp/mixer" ] || fail "$SESSION_MODE session did not apply the initial tinymix mute"
  grep -Fq 'Playback Path 0' "$tmp/mixer" || fail 'mute did not cut the playback route'
  [ -s "$MUTE_PIDFILE" ] || fail "$SESSION_MODE session did not start a watchdog"
  stop_mute_guard
done
# Boot the actual systemd preparation with namespace/exec operations mocked.
boot_subsystem_session
for unit in audiopath audiostate ogage; do
  policy="$ROOT/etc/systemd/system/$unit.service.d/20-audio-policy.conf"
  [ -f "$policy" ] || fail "$unit unit has no mute policy"
  grep -Fqx 'ConditionPathExists=/run/.a36-handoff' "$policy" || fail "$unit ignores handoff"
  grep -Fqx 'ConditionPathExists=!/run/.a36-muted' "$policy" || fail "$unit ignores night mute"
done
[ -f "$ROOT/run/.a36-muted" ] || fail 'mute policy was not exposed inside the subsystem'
# Headless sessions never touch Android audio, even with mute requested.
HANDOFF=0
rm -f "$tmp/mixer"
. "$tmp/boundary"
[ ! -e "$tmp/mixer" ] || fail 'headless session touched the Android codec'
[ ! -e "$ROOT/run/.a36-handoff" ] || fail 'headless session enabled audio daemons'
echo 'session mute tests passed (finding 7: common boundary and systemd daemon policy)'
