#!/bin/sh
. "$(dirname "$0")/control-test-lib.inc"
for app in retroarch retroarch32; do
  cfg="$ROOT/home/ark/.config/$app/retroarch.cfg"
  mkdir -p "$(dirname "$cfg")"
  cat > "$cfg" <<'DATA'
input_player1_a_btn = "1"
input_player1_a_btn = "1"
input_player1_x_btn = "2"
input_player1_y_btn = "3"
input_player1_l_btn = "4"
input_player1_r_btn = "5"
input_player1_l2_btn = "6"
input_player1_r2_btn = "7"
input_player1_up = "up"
input_player1_l_x_plus_axis = "+0"
DATA
done
set_ra_input_binds
for app in retroarch retroarch32; do
  cfg="$ROOT/home/ark/.config/$app/retroarch.cfg"
  exact "$cfg" 'input_player1_a_btn = "0"'
  exact "$cfg" 'input_player1_b_btn = "1"'
  [ "$(grep -c '^input_player1_a_btn =' "$cfg")" = 1 ] || fail 'duplicate A bind'
  for line in 'x_btn = "2"' 'y_btn = "3"' 'l_btn = "4"' 'r_btn = "5"' 'l2_btn = "6"' 'r2_btn = "7"' 'up = "up"' 'l_x_plus_axis = "+0"' 'select_btn = "8"' 'start_btn = "9"'; do
    exact "$cfg" "input_player1_$line"
  done
done
repeat set_ra_input_binds
echo 'F1 RetroArch absolute A/B upsert passed'
