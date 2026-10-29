#!/bin/sh
set -eu

broker=$(CDPATH='' cd -- "$(dirname "$0")/.." && pwd)/andr36oid-subsystem.sh
tmp=$(mktemp -d "${TMPDIR:-/tmp}/subsystem-input.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
ROOT="$tmp/root"
UPPER="$tmp/upper"
RAAUTOCFG_SRC="$tmp/ra-profile"
mkdir -p "$ROOT" "$tmp/helpers"
log() { printf '%s\n' "$*" >> "$tmp/log"; }
sed -n '/^root_path() {/,/^}/p' "$broker" > "$tmp/helpers/input.sh"
sed -n '/^SDL_PAD_BINDS=/,/^prime_input() {/p' "$broker" | sed '$d' >> "$tmp/helpers/input.sh"
# shellcheck disable=SC1091
. "$tmp/helpers/input.sh"
fail() { echo "FAIL: $*" >&2; exit 1; }
contains() { grep -F -q -- "$2" "$1" || fail "missing $2 in $1"; }

ra="$ROOT/home/ark/.config/retroarch"
ra32="$ROOT/home/ark/.config/retroarch32"
mkdir -p "$ra/autoconfig/sdl3" "$ra/autoconfig/udev" "$ra32/autoconfig/udev" "$ROOT/usr/local/bin" "$ROOT/opt/mupen64plus" "$ROOT/home/ark/.config/mupen64plus"
cat > "$ra/autoconfig/sdl3/gamecontrollerdb.cfg" <<'DATA'
other,Other Pad,a:b0,
190000004b4800000011000000010000,GO-Super Gamepad,a:b1,b:b0,x:b3,y:b2,back:b12,start:b13,dpup:b8,dpdown:b9,dpleft:b10,dpright:b11,leftstick:b14,rightstick:b15,leftx:a0,lefty:a1,
DATA
cp "$ra/autoconfig/sdl3/gamecontrollerdb.cfg" "$tmp/native-db"
# SDL discovery must cover shallow app directories (including spaces), while
# avoiding the games card, rootfs root, and deeper unrelated directories.
for dir in "$ROOT/opt/Some App" "$ROOT/home/ark/.config/Some App/nested" \
           "$ROOT" "$ROOT/roms/unrelated" "$ROOT/usr/share/unrelated" \
           "$ROOT/opt/deep/one/two" "$ROOT/home/ark/.config/deep/one/two"; do
  mkdir -p "$dir"
  cp "$tmp/native-db" "$dir/gamecontrollerdb.txt"
done
cat > "$ROOT/usr/local/bin/ecwolf.sh" <<'DATA'
#!/bin/sh
export SDL_GAMECONTROLLERCONFIG="190000004b4800000011000000010000,GO-Super Gamepad,a:b1,b:b0,x:b2,y:b3,back:b12,start:b13,dpup:b8,dpdown:b9,dpleft:b10,dpright:b11,leftstick:b14,rightstick:b15,leftx:a0,lefty:a1,"
DATA
chmod 755 "$ROOT/usr/local/bin/ecwolf.sh"
cat > "$ROOT/usr/local/bin/pico8.sh" <<'DATA'
echo '1900bb3e4b4800000011000000010000,GO-Super Gamepad,a:b0,b:b1,x:b3,y:b2,back:b12,start:b13,dpup:b8,dpdown:b9,dpleft:b10,dpright:b11,' > sdl_controllers.txt
DATA
cat > "$ra/retroarch.cfg" <<'DATA'
input_enable_hotkey_btn = "12"
input_exit_emulator_btn = "13"
input_menu_toggle_btn = "nul"
input_player1_up_btn = "8"
input_player1_down_btn = "9"
input_player1_left_btn = "10"
input_player1_right_btn = "11"
input_player1_select_btn = "12"
input_player1_start_btn = "13"
input_player1_l3_btn = "14"
input_player1_r3_btn = "15"
input_player1_a_btn = "1"
input_player1_l_x_plus_axis = "+0"
DATA
cp "$ra/retroarch.cfg" "$ra32/retroarch.cfg"
cat > "$ROOT/opt/mupen64plus/InputAutoCfg.ini" <<'DATA'
[Other Pad]
Start = button(13)
[GO-Super Gamepad]
Start = button(13)
DPad U = button(8)
DPad D = button(9)
DPad L = button(10)
DPad R = button(11)
A Button = button(1)
[Other Pad 2]
Start = button(13)
DATA
cat > "$ROOT/home/ark/.config/mupen64plus/mupen64plus.cfg" <<'DATA'
[Input-SDL-Control1]
Start = "button(13)"
DPad U = "button(8)"
DPad D = "button(9)"
DPad L = "button(10)"
DPad R = "button(11)"
[Input-SDL-Control2]
Start = "button(13)"
DATA
printf 'shipped profile\n' > "$RAAUTOCFG_SRC"
printf 'user profile\n' > "$ra/autoconfig/udev/go_super_gamepad.cfg"
printf 'native profile\n' > "$ra32/autoconfig/udev/GO-Super Gamepad.cfg"
printf 'native profile\n' > "$ra32/autoconfig/udev/go_super_gamepad.cfg"
mkdir -p "$UPPER/home/ark/.config/retroarch/autoconfig/udev"
printf 'user profile\n' > "$UPPER/home/ark/.config/retroarch/autoconfig/udev/go_super_gamepad.cfg"
install_sdl_mapping
install_launcher_sdl_mapping
set_ra_input_binds
set_mupen_input_binds
install_ra_profiles

db="$ra/autoconfig/sdl3/gamecontrollerdb.cfg"
launcher="$ROOT/usr/local/bin/ecwolf.sh"
contains "$db" 'a:b0,b:b1,x:b2,y:b3,back:b9,start:b8,guide:b16'
contains "$db" 'dpup:b10,dpdown:b13,dpleft:b11,dpright:b12'
contains "$db" 'leftstick:b15,rightstick:b14'
contains "$db" '1900bb3e4b4800000011000000010000,GO-Super Gamepad'
contains "$launcher" 'a:b0,b:b1,x:b2,y:b3,back:b9,start:b8,guide:b16'
contains "$launcher" '1900bb3e4b4800000011000000010000,GO-Super Gamepad'
contains "$ROOT/usr/local/bin/pico8.sh" 'a:b0,b:b1,x:b2,y:b3,back:b9,start:b8,guide:b16'
for cfg in "$ROOT/home/ark/.config/SDL-GameControllerDB/gamecontrollerdb.txt" \
           "$ROOT/opt/Some App/gamecontrollerdb.txt" \
           "$ROOT/home/ark/.config/Some App/nested/gamecontrollerdb.txt"; do
  contains "$cfg" 'back:b9,start:b8,guide:b16'
  contains "$cfg" '1900bb3e4b4800000011000000010000,GO-Super Gamepad'
done
contains "$ROOT/opt/Some App/gamecontrollerdb.txt" 'other,Other Pad,a:b0,'
for dir in "$ROOT" "$ROOT/roms/unrelated" "$ROOT/usr/share/unrelated" \
           "$ROOT/opt/deep/one/two" "$ROOT/home/ark/.config/deep/one/two"; do
  cmp -s "$tmp/native-db" "$dir/gamecontrollerdb.txt" || fail "SDL discovery reached $dir"
done
sh -n "$ROOT/usr/local/bin/pico8.sh" || fail 'PICO-8 generator is not valid shell'
[ -x "$launcher" ] || fail 'launcher lost executable permissions'
sh -n "$launcher" || fail 'launcher is not valid shell'
for cfg in "$ra/retroarch.cfg" "$ra32/retroarch.cfg"; do
  contains "$cfg" 'input_enable_hotkey_btn = "8"'
  contains "$cfg" 'input_exit_emulator_btn = "nul"'
  contains "$cfg" 'input_menu_toggle_btn = "nul"'
  contains "$cfg" 'input_player1_up_btn = "nul"'
  contains "$cfg" 'input_player1_down_btn = "nul"'
  contains "$cfg" 'input_player1_left_btn = "nul"'
  contains "$cfg" 'input_player1_right_btn = "nul"'
  contains "$cfg" 'input_player1_select_btn = "8"'
  contains "$cfg" 'input_player1_start_btn = "9"'
  contains "$cfg" 'input_player1_l3_btn = "nul"'
  contains "$cfg" 'input_player1_r3_btn = "nul"'
  contains "$cfg" 'input_player1_a_btn = "1"'
  contains "$cfg" 'input_player1_l_x_plus_axis = "+0"'
done
mupen="$ROOT/opt/mupen64plus/InputAutoCfg.ini"
contains "$mupen" 'DPad U = button(10)'
contains "$mupen" 'DPad D = button(13)'
contains "$mupen" 'DPad L = button(11)'
contains "$mupen" 'DPad R = button(12)'
contains "$mupen" 'A Button = button(1)'
[ "$(grep -c 'Start = button(13)' "$mupen")" = 2 ] || fail 'other Mupen profiles changed'
contains "$ROOT/home/ark/.config/mupen64plus/mupen64plus.cfg" 'DPad R = "button(12)"'
contains "$ra/autoconfig/udev/go_super_gamepad.cfg" 'user profile'
contains "$ra32/autoconfig/udev/GO-Super Gamepad.cfg" 'shipped profile'
contains "$ra32/autoconfig/udev/go_super_gamepad.cfg" 'shipped profile'
mkdir -p "$UPPER/home/ark/.config/retroarch32/autoconfig/udev"
cp "$ra32/autoconfig/udev/GO-Super Gamepad.cfg" "$UPPER/home/ark/.config/retroarch32/autoconfig/udev/GO-Super Gamepad.cfg"
cp "$ra32/autoconfig/udev/go_super_gamepad.cfg" "$UPPER/home/ark/.config/retroarch32/autoconfig/udev/go_super_gamepad.cfg"
cp -R "$ROOT" "$tmp/first"
cp "$tmp/log" "$tmp/first-log"
install_sdl_mapping
install_launcher_sdl_mapping
set_ra_input_binds
set_mupen_input_binds
install_ra_profiles
diff -r "$tmp/first" "$ROOT" >/dev/null || fail 'second run changed files'
cmp -s "$tmp/first-log" "$tmp/log" || fail 'second run logged a change'
echo 'subsystem input tests passed'
