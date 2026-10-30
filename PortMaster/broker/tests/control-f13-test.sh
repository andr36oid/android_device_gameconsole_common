#!/bin/sh
. "$(dirname "$0")/control-test-lib.inc"
fixtures="$(dirname "$0")/fixtures/native-helpers"
mkdir -p "$ROOT/usr/local/bin" "$tmp/sys/event17/device" "$tmp/sys/event3/device" "$tmp/dev"
for name in buttonmon.sh killer_daemon.py fn_menu_watcher.sh; do cp "$fixtures/$name" "$ROOT/usr/local/bin/$name"; done
chmod 755 "$ROOT/usr/local/bin/buttonmon.sh"
printf 'GO-Super Gamepad\n' > "$tmp/sys/event17/device/name"
printf 'GO-Super Gamepad Extra\n' > "$tmp/sys/event3/device/name"
install_native_evdev_helpers
primed install_native_evdev_helpers
buttonmon="$ROOT/usr/local/bin/buttonmon.sh"
watcher="$ROOT/usr/local/bin/fn_menu_watcher.sh"
bash -n "$buttonmon"; bash -n "$watcher"
[ -x "$buttonmon" ] || fail 'helper lost executable mode'
# Run only buttonmon variable/device setup with fake sysfs and by-path trees.
sed -e "s@/sys/class/input/@$tmp/sys/@g" -e "s@/dev/input/by-path/@$tmp/dev/@g" "$buttonmon" > "$tmp/buttonmon"
bash -c '. "$1"; [ "$event_num:$event_type:$event_btn_a:$event_btn_b:$event_btn_x:$event_btn_y:$event_btn_hk:$event_btn_l1:$event_btn_r1:$event_btn_l2:$event_btn_r2" = "17:EV_KEY:BTN_SOUTH:BTN_EAST:BTN_NORTH:BTN_WEST:KEY_HOMEPAGE:BTN_TL:BTN_TR:BTN_TL2:BTN_TR2" ]' test "$tmp/buttonmon" || fail 'native GO event or button codes wrong'
rm "$tmp/sys/event17/device/name"
# Similar names must not qualify. External USB branch must keep its behavior.
touch "$tmp/dev/platform-ff300000.usb-usb-0:1.2:1.0-event-joystick"
bash -c '. "$1"; [ "$event_num:$event_btn_a:$event_btn_b:$event_btn_hk" = "3:BTN_SOUTH:BTN_EAST:BTN_MODE" ]' test "$tmp/buttonmon" || fail 'external USB branch changed'
rm "$tmp/dev/platform-ff300000.usb-usb-0:1.2:1.0-event-joystick"
touch "$tmp/dev/platform-fe5b0000.i2c-event"
bash -c '. "$1"; [ "$event_num:$event_btn_a:$event_btn_b:$event_btn_hk" = "4:BTN_EAST:BTN_SOUTH:BTN_MODE" ]' test "$tmp/buttonmon" || fail 'external I2C branch changed'
contains "$watcher" 'KEY_HOMEPAGE'
! grep -q 'BTN_TRIGGER_HAPPY5' "$watcher" || fail 'stale Fn code'
sed -n '/^find_device() {/,/^}/p' "$watcher" | sed "s@/sys/class/input/@$tmp/sys/@g" > "$tmp/find-device"
printf 'GO-Super Gamepad\n' > "$tmp/sys/event17/device/name"
[ "$(bash -c '. "$1"; find_device' test "$tmp/find-device")" = 17 ] || fail 'Fn watcher device resolver wrong'
# Parse only the dictionary; no evdev import or daemon process is run.
python3 - "$fixtures/killer_daemon.py" "$ROOT/usr/local/bin/killer_daemon.py" <<'PY'
import ast, sys
def mapping(path):
    tree = ast.parse(open(path).read())
    return next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'JOY_MAPPINGS' for t in n.targets))
before, after = map(mapping, sys.argv[1:])
assert after.pop('GO-Super Gamepad') == {'f1': 314, 'f3': 315}
before.pop('GO-Super Gamepad')
assert before == after, 'external-device dictionary changed'
PY
repeat install_native_evdev_helpers
echo 'F13 native evdev helper codes, exact device discovery and external branches passed'
