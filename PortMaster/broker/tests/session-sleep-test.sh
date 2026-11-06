#!/bin/sh
set -eu
. "$(dirname "$0")/test-lib.inc"
# Exercise the power-write boundary against fake sysfs, never the host's nodes.
sed "s@/sys/@$tmp/sys/@g" "$broker" > "$tmp/broker"
broker="$tmp/broker"
load_functions blank_session_backlight capture_session_wake_sources do_session_sleep
sleep() { :; }
node="$tmp/sys/devices/panel/brightness"
state="$tmp/sys/power/state"
wake="$tmp/sys/module/rockchip_pm_config/parameters/sleep_wakeup"
ac="$tmp/sys/class/power_supply/ac"; usb="$tmp/sys/class/power_supply/usb"
reason="$tmp/sys/kernel/wakeup_reasons/last_resume_reason"
mkdir -p "$(dirname "$node")" "$(dirname "$state")" "$(dirname "$wake")" "$ac" "$usb" "$(dirname "$reason")"
printf '%s 70\n' "$node" > "$STATE/session.brightness"
echo '48 rk817' > "$reason"
reset_fixture() {
  echo 70 > "$node"; : > "$state"; : > "$ROOT/run/.a36-session-sleep"
  echo 0 > "$ac/online"; echo 0 > "$usb/online"
  : > "$ac/uevent"; : > "$usb/uevent"; echo '0x83' > "$wake"
  rm -f "$STATE/sleep.interrupts.before" "$STATE/sleep.interrupts.after" "$LOG"
}

# 1) On battery: real suspend proceeds, panel blanks then restores, guardian
#    marker clears, sleep request is consumed and wake-sources are snapshotted.
reset_fixture
do_session_sleep
[ "$(cat "$state")" = mem ] || fail 'battery sleep did not request real mem suspend'
[ "$(cat "$node")" = 70 ] || fail 'battery sleep left panel blank'
[ ! -f "$ROOT/run/.a36-sleeping" ] || fail 'battery sleep left guardian disabled'
[ ! -f "$ROOT/run/.a36-session-sleep" ] || fail 'battery sleep request was not consumed'
[ -f "$STATE/sleep.interrupts.before" ] && [ -f "$STATE/sleep.interrupts.after" ] \
  || fail 'battery sleep did not snapshot wake sources'
[ "$(cat "$wake")" = 0x83 ] || fail 'battery sleep must not touch USB wake mask'

# 2) External power with USB wake bit 7 set: mask it for the suspend, restore
#    it on resume, and keep the power-key wake (do not disable all wake).
reset_fixture
echo 1 > "$ac/online"
do_session_sleep
[ "$(cat "$state")" = mem ] || fail 'external-power sleep did not suspend'
[ "$(cat "$wake")" = 0x83 ] || fail 'external-power sleep did not restore USB wake mask'
grep -q 'masked USB wake' "$LOG" || fail 'external-power USB wake masking not reported'
[ "$(cat "$node")" = 70 ] || fail 'external-power sleep left panel blank'

# 3) External power but USB wake policy unreadable: refuse suspend, recover panel.
reset_fixture
echo 1 > "$usb/online"; rm -f "$wake"
do_session_sleep || true
[ ! -s "$state" ] || fail 'suspend attempted with unreadable USB wake policy'
grep -q 'refusing suspend' "$LOG" || fail 'unreadable USB wake policy was not reported'
[ "$(cat "$node")" = 70 ] || fail 'refused suspend left panel blank'
[ ! -f "$ROOT/run/.a36-sleeping" ] || fail 'refused suspend left guardian disabled'

# 4) /sys/power/state write fails: error captured, panel/guardian recovered.
reset_fixture
rm -f "$state"; mkdir "$state"
do_session_sleep || true
[ -s "$STATE/sleep.error" ] || fail 'power-write error was swallowed'
grep -q 'sleep: mem failed rc=' "$LOG" || fail 'power-write failure was not reported'
[ "$(cat "$node")" = 70 ] || fail 'failed suspend left panel blank'
[ ! -f "$ROOT/run/.a36-sleeping" ] || fail 'failed suspend left guardian disabled'
echo 'PASS: battery/external-power suspend, USB-wake masking + restore, refusal on unreadable policy, and panel/guardian recovery on state-write failure'
