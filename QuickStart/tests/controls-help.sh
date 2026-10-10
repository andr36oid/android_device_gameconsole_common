#!/bin/sh
# Host test for Settings > Buttons and shortcuts: res/raw/controls.txt must match the build
# (check-controls.py), and the check must catch drift. Each case below breaks one thing in a
# copy of the files and expects the check to fail with a matching message.
#   FWB=<frameworks/base> R36S=<device/gameconsole/r36s> sh controls-help.sh
# Without a frameworks/base tree the FN cases are skipped (the check notes it).
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
APP=$(dirname "$HERE")
COMMON=$(dirname "$APP")
FWB=${FWB:-/andr36oid/src/frameworks/base}
CHECK="python3 $HERE/check-controls.py"
PWM=services/core/java/com/android/server/policy/PhoneWindowManager.java
# The frameworks/base files controls.txt names
FWB_FILES="$PWM core/res/res/values/config.xml
services/core/java/com/android/server/input/InputManagerService.java"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
pass=0
fail=0

ok() { pass=$((pass + 1)); echo "ok   $1"; }
bad() { fail=$((fail + 1)); echo "FAIL $1"; }

# The real tree
if $CHECK >"$T/out" 2>&1; then
	ok "controls.txt matches this tree ($(tail -n 1 "$T/out"))"
else
	cat "$T/out"
	bad "controls.txt matches this tree"
fi

# A copy to break: the app folder and the sources the check reads
fresh() {
	rm -rf "$T/c"
	mkdir -p "$T/c/emulators/seed/retroarch" "$T/c/joyMouse/src" "$T/c/QuickStart"
	cp "$COMMON"/Vendor_484b_Product_1100.kl "$COMMON"/Vendor_484b_Product_1100.kcm "$T/c/"
	cp "$COMMON/emulators/seed/retroarch/retroarch.cfg" "$T/c/emulators/seed/retroarch/"
	cp "$COMMON/joyMouse/src/Buttons.cpp" "$COMMON/joyMouse/src/Config.h" "$T/c/joyMouse/src/"
	cp "$COMMON/joyMouse/README.md" "$T/c/joyMouse/"
	cp -r "$APP/res" "$T/c/QuickStart/"
	rm -rf "$T/f"
	if [ -f "$FWB/$PWM" ]; then
		for f in $FWB_FILES; do
			mkdir -p "$T/f/$(dirname "$f")"
			cp "$FWB/$f" "$T/f/$f"
		done
	fi
	# Only the FN cases compare with frameworks/base
	fwb=$T/none
}

# expect <name> <message regex>: the check on the broken copy fails and says so
expect() {
	if $CHECK --app "$T/c/QuickStart" --common "$T/c" --fwb "$fwb" >"$T/out" 2>&1; then
		bad "$1: the check passed"
	elif grep -q -- "$2" "$T/out"; then
		ok "$1"
	else
		cat "$T/out"
		bad "$1: no message like '$2'"
	fi
}

# The untouched copy passes (so every failure below comes from the break)
fresh
[ -d "$T/f" ] && fwb=$T/f
if $CHECK --app "$T/c/QuickStart" --common "$T/c" --fwb "$fwb" >"$T/out" 2>&1; then
	ok "copy passes"
else
	cat "$T/out"
	bad "copy passes"
fi

cfg=$T/c/emulators/seed/retroarch/retroarch.cfg
data=$T/c/QuickStart/res/raw/controls.txt

fresh
sed -i 's/^input_save_state_btn = .*/input_save_state_btn = "105"/' "$cfg"
expect "RetroArch save state moved to R2" "input_save_state_btn is 105"

fresh
echo 'input_hold_fast_forward_btn = "105"' >>"$cfg"
expect "new RetroArch hotkey without a row" "input_hold_fast_forward_btn is seeded but not on the page"

fresh
sed -i 's/^input_enable_hotkey_btn = .*/input_enable_hotkey_btn = "108"/' "$cfg"
expect "RetroArch hotkey button is Start" "hotkey button is 108, not Select"

fresh
sed -i 's/^input_menu_toggle_gamepad_combo = .*/input_menu_toggle_gamepad_combo = "2"/' "$cfg"
expect "RetroArch menu combo changed" "menu combo 2"

fresh
echo 'quit_press_twice = "false"' >>"$cfg"
expect "RetroArch quits on one press" "retroarch.cfg has"

fresh
sed -i '/^row Select + Y = controls_screenshot/,+1d' "$data"
expect "RetroArch row removed" "input_screenshot_btn is seeded but not on the page"

fresh
sed -i 's/^key 172   HOME/key 172   BUTTON_MODE/' "$T/c/Vendor_484b_Product_1100.kl"
expect "FN not HOME in the key layout" "HOME is not on scan code 172"

fresh
sed -i 's/Button::R2)\] = Action::Precision/Button::R2)] = Action::Left/' \
	"$T/c/joyMouse/src/Buttons.cpp"
expect "mouse R2 changed" "gives R2 the action Left"

fresh
sed -i 's/Action::None;/Action::Middle;/' "$T/c/joyMouse/src/Buttons.cpp"
expect "mouse L3 got an action" "gives L3 the action Middle"

fresh
sed -i 's/^row Select + L1 = controls_load_state/row Select + L1 = controls_load_statx/' "$data"
expect "row names a missing string" "controls_load_statx is used"

fresh
sed -i 's/^row B = buttons_b/row B = buttons_b\nrow Z = buttons_b/' "$data"
expect "unknown button" "unknown button 'Z'"

fresh
mkdir -p "$T/c/QuickStart/res/values-xx"
grep -v 'controls_pause' "$T/c/QuickStart/res/values/controls_strings.xml" \
	>"$T/c/QuickStart/res/values-xx/controls_strings.xml"
expect "translation lacks a string" "values-xx/controls_strings.xml lacks controls_pause"

fresh
sed -i 's/Save state</Save'"'"'s state</' "$T/c/QuickStart/res/values/controls_strings.xml"
expect "unescaped apostrophe" "unescaped quote"

fresh
sed -i '/^heading =PPSSPP/,/^  if-file/{/^  if-file/d}' "$data"
expect "PPSSPP rows without if-file" "is missing, and the row has no if-file"

if [ -f "$FWB/$PWM" ]; then
	fresh
	fwb=$T/f
	sed -i 's/^\(            case KeyEvent.KEYCODE_BUTTON_L1:\)$/            case KeyEvent.KEYCODE_BUTTON_R2:\n                return FN_HOTKEY_SCREENSHOT;\n\1/' \
		"$fwb/$PWM"
	expect "new FN shortcut without a row" "FN + BUTTON_R2 is a shortcut"

	fresh
	fwb=$T/f
	sed -i '/case KeyEvent.KEYCODE_BUTTON_Y:/,+1d' "$fwb/$PWM"
	expect "FN shortcut removed, row still there" "FN + Y is not an FN shortcut"
else
	echo "skip FN cases: no frameworks/base at $FWB"
fi

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
