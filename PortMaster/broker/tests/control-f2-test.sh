#!/bin/sh
. "$(dirname "$0")/control-test-lib.inc"
for app in retroarch retroarch32; do
  dir="$ROOT/home/ark/.config/$app"; mkdir -p "$dir"
  printf 'input_player1_start_btn = "13"\ninput_player1_a_btn = "1"\n' > "$dir/retroarch.cfg.bak"
  printf 'input_player1_a_btn = "0"\ninput_player1_l_x_plus_axis = "+1"\n' > "$dir/retroarch.cfg.vert"
  printf 'video_fullscreen = "true"\n' > "$dir/retroarch.cfg.spectate"
  cp "$dir/retroarch.cfg.vert" "$tmp/$app.vert"
  cp "$dir/retroarch.cfg.spectate" "$tmp/$app.spectate"
done
normalize() { set_ra_input_binds; set_ra_quit_combo; }
normalize
for app in retroarch retroarch32; do
  dir="$ROOT/home/ark/.config/$app"
  exact "$dir/retroarch.cfg.bak" 'input_player1_a_btn = "0"'
  exact "$dir/retroarch.cfg.bak" 'input_player1_start_btn = "9"'
  exact "$dir/retroarch.cfg.bak" 'input_exit_emulator_btn = "nul"'
  exact "$dir/retroarch.cfg.bak" 'input_quit_gamepad_combo = "4"'
  cmp "$tmp/$app.vert" "$dir/retroarch.cfg.vert" || fail 'rotated append file changed'
  cmp "$tmp/$app.spectate" "$dir/retroarch.cfg.spectate" || fail 'spectate append file changed'
done
repeat normalize
echo 'F2 RetroArch restore normalization passed'
