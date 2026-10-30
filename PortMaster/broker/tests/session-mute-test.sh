#!/bin/sh
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions root_path process_start_time recorded_process_alive mute_guard_owned freeze_process kill_process_children kill_tracked_tree stop_mute_guard \
  subsystem_muted mute_all_once start_mute_guard prepare_session_policy session_uses_init \
  install_systemctl_shim install_session_units boot_subsystem_session
CHROOT=/bin/false
# Use the production allowlist, including 351mp's mixed audio/display work.
sed -n '/^SESSION_UNITS=/,/^$/p' "$broker" > "$tmp/units"
. "$tmp/units"
mkdir -p "$ROOT/etc/systemd/system" "$ROOT/usr/local/bin"
cat > "$ROOT/etc/systemd/system/351mp.service" <<'UNIT'
[Unit]
Description=351MP Fixes
[Service]
Type=oneshot
User=root
ExecStart=/usr/local/bin/fix_power_led
ExecStart=/usr/local/bin/checkbrightonboot
ExecStart=/usr/local/bin/fixvolume.sh
Restart=no
UNIT
cat > "$ROOT/usr/local/bin/fixvolume.sh" <<'AUDIO'
#!/bin/sh
amixer -c 0 cset iface=MIXER,name='Playback Path' SPK_HP
exit 0
AUDIO
chmod +x "$ROOT/usr/local/bin/fixvolume.sh"
mkdir -p "$ROOT/usr/bin/emulationstation" "$ROOT/etc"
find_es() { ES=/usr/bin/emulationstation/emulationstation.sh; return 0; }
has_init() { INIT=/sbin/init; return 0; }
root_file() { return 0; }
prime_udev() { :; }
handoff() { :; }
enable_hardware_master() { :; }
# Exercise the actual common startup boundary rather than duplicating its calls.
sed -n '/^prime_udev$/,/^# Wait/p' "$broker" > "$tmp/boundary"
run_in_subsystem() {
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
policy="$ROOT/etc/systemd/system/351mp.service.d/20-audio-policy.conf"
grep -Fqx 'ExecStart=' "$policy" || fail '351mp did not reset its original commands'
for helper in fix_power_led checkbrightonboot; do
  grep -Fqx "ExecStart=/usr/local/bin/$helper" "$policy" || fail "351mp lost $helper"
done
! grep -Fqx 'ExecStart=/usr/local/bin/fixvolume.sh' "$policy" || fail '351mp still restores the route directly'
grep -Fq 'ExecStart=/usr/local/libexec/a36-session-fixvolume' "$policy" || fail '351mp did not gate fixvolume'
# Run the generated gate and the real image's fixvolume action, with amixer mocked.
mkdir -p "$tmp/bin"
cat > "$tmp/bin/amixer" <<'AMIXER'
#!/bin/sh
printf '%s\n' "$*" >> "$A36_TEST_MIXER"
AMIXER
chmod +x "$tmp/bin/amixer"
export A36_TEST_MIXER="$tmp/amixer"
PATH="$tmp/bin:$PATH"; export PATH
sed -e "s@/run/@$ROOT/run/@g" -e "s@/usr/local/bin/fixvolume.sh@$ROOT/usr/local/bin/fixvolume.sh@g" \
  "$ROOT/usr/local/libexec/a36-session-fixvolume" > "$tmp/fixvolume"
for policy_mode in muted headless audible; do
  rm -f "$ROOT/run/.a36-handoff" "$ROOT/run/.a36-muted" "$tmp/amixer"
  case "$policy_mode" in
    muted) : > "$ROOT/run/.a36-handoff"; : > "$ROOT/run/.a36-muted" ;;
    headless) : ;;
    audible) : > "$ROOT/run/.a36-handoff" ;;
  esac
  sh "$tmp/fixvolume"
  if [ "$policy_mode" = audible ]; then
    grep -Fqx -- '-c 0 cset iface=MIXER,name=Playback Path SPK_HP' "$tmp/amixer" || fail 'allowed audio restore was lost'
  else
    [ ! -e "$tmp/amixer" ] || fail "351mp reopened the speaker route during $policy_mode"
  fi
done
# Headless sessions never touch Android audio, even with mute requested.
HANDOFF=0
rm -f "$tmp/mixer"
. "$tmp/boundary"
[ ! -e "$tmp/mixer" ] || fail 'headless session touched the Android codec'
[ ! -e "$ROOT/run/.a36-handoff" ] || fail 'headless session enabled audio daemons'
echo 'session mute tests passed (finding 7: common boundary and systemd daemon policy)'
