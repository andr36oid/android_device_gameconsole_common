#!/bin/sh
. "$(dirname "$0")/control-test-lib.inc"
dir="$ROOT/opt/dingux"; mkdir -p "$dir"
cat > "$dir/oshgamepad.cfg" <<'DATA'
input_player1_a_btn = "1
input_player1_b_btn = "0
input_player1_start_btn = "13"
input_player1_up_btn = "8"
input_select_btn = "12"
input_a_btn = "0
input_l3_btn = "14"
input_r3_btn = "15"
input_player1_l_btn = "4"
DATA
sed 's/input_player1_a_btn = .*/input_player1_a_btn = "0"/;s/input_player1_b_btn = .*/input_player1_b_btn = "1"/' "$dir/oshgamepad.cfg" > "$dir/oshgamepad.cfg.bak"
set_dingux_input_binds
for cfg in "$dir/oshgamepad.cfg" "$dir/oshgamepad.cfg.bak"; do
  for line in 'select_btn = "9"' 'start_btn = "8"' 'up_btn = "10"' 'down_btn = "13"' 'left_btn = "11"' 'right_btn = "12"'; do
    exact "$cfg" "input_player1_$line"
  done
  exact "$cfg" 'input_select_btn = "9"'
  exact "$cfg" 'input_a_btn = "0"'
  exact "$cfg" 'input_l3_btn = "15"'
  exact "$cfg" 'input_r3_btn = "14"'
  exact "$cfg" 'input_player1_l_btn = "4"'
done
exact "$dir/oshgamepad.cfg" 'input_player1_a_btn = "1"'
exact "$dir/oshgamepad.cfg" 'input_player1_b_btn = "0"'
exact "$dir/oshgamepad.cfg.bak" 'input_player1_a_btn = "0"'
exact "$dir/oshgamepad.cfg.bak" 'input_player1_b_btn = "1"'
repeat set_dingux_input_binds
echo 'F5 Dingux namespaces and quote repair passed'
