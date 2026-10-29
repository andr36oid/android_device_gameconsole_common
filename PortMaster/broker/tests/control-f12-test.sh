#!/bin/sh
. "$(dirname "$0")/control-test-lib.inc"
mkdir -p "$ROOT/roms/tools/ThemeMaster" "$tmp/sys/event17/device" "$tmp/sys/event2/device"
cfg="$ROOT/roms/tools/ThemeMaster/paramcontrols.txt"
cat > "$cfg" <<'DATA'
back_key = 704
start_key = 705
a_key = 304
b_key = 305
x_key = 307
y_key = 308
up_key = 544
down_key = 545
left_key = 546
right_key = 547
l1_key = 310
l2_key = 311
r1_key = 312
r2_key = 313
inputstr = /dev/input/by-path/platform-odroidgo3-joypad-event-joystick
handler = generic
DATA
launcher="$ROOT/roms/tools/ThemeMaster.sh"
cat > "$launcher" <<'DATA'
#!/bin/bash
cd "$1/ThemeMaster" || exit 1
cat ./paramcontrols.txt
DATA
chmod 755 "$launcher"
prime_storage_input
for pair in back:314 start:315 up:103 down:108 left:105 right:106 l1:310 r1:311 l2:312 r2:313 a:304 b:305 x:307 y:308; do exact "$cfg" "${pair%:*}_key = ${pair#*:}"; done
exact "$cfg" 'handler = generic'
exact "$cfg" 'inputstr ='
repeat prime_storage_input
printf 'GO-Super Gamepad\n' > "$tmp/sys/event17/device/name"
printf 'GO-Super Gamepad Extra\n' > "$tmp/sys/event2/device/name"
sed "s@/sys/class/input/@$tmp/sys/@g" "$launcher" > "$tmp/launcher"
bash -n "$tmp/launcher"
bash "$tmp/launcher" "$ROOT/roms/tools" > "$tmp/paramcontrols"
exact "$tmp/paramcontrols" 'inputstr = /dev/input/event17'
exact "$cfg" 'inputstr = /dev/input/event17'
echo 'F12 ThemeMaster evdev values and runtime native device resolution passed'
