#!/bin/sh
. "$(dirname "$0")/control-test-lib.inc"
cfg="$ROOT/usr/local/bin/pico8.sh"; mkdir -p "$(dirname "$cfg")"
cat > "$cfg" <<'DATA'
#!/bin/sh
echo "03000000091200000031000011010000,Other Pad,a:b1,
190000004b4800000011000000010000,GO-Super Gamepad,keep:outside,
last,Last Pad,a:b2," > "$1/unrelated.txt"
echo "03000000091200000031000011010000,Other Pad,a:b1,
190000004b4800000011000000010000,GO-Super Gamepad,start:b13,back:b12,
last,Last Pad,a:b2," > "$1/sdl_controllers.txt"
DATA
chmod 755 "$cfg"
install_launcher_sdl_mapping
sh -n "$cfg"
sh "$cfg" "$tmp"
exact "$tmp/unrelated.txt" '190000004b4800000011000000010000,GO-Super Gamepad,keep:outside,'
for guid in $SDL_PAD_GUIDS; do
  exact "$tmp/sdl_controllers.txt" "$guid,$SDL_PAD_BINDS"
  [ "$(grep -c "^$guid," "$tmp/sdl_controllers.txt")" = 1 ] || fail 'duplicate seed GUID'
done
exact "$tmp/sdl_controllers.txt" '03000000091200000031000011010000,Other Pad,a:b1,'
exact "$tmp/sdl_controllers.txt" 'last,Last Pad,a:b2,'
[ -x "$cfg" ] || fail 'lost executable mode'
repeat install_launcher_sdl_mapping
sh "$cfg" "$tmp"
[ "$(wc -l < "$tmp/sdl_controllers.txt")" = 4 ] || fail 'seed not deduplicated'
echo 'F4 PICO-8 executed multiline first-launch seed passed'
