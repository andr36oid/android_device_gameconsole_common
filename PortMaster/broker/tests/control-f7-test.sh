#!/bin/sh
. "$(dirname "$0")/control-test-lib.inc"
cfg="$ROOT/home/ark/.mednafen/mednafen.cfg"; mkdir -p "$(dirname "$cfg")"
id=0x0019484b110001000004001100000000
cat > "$cfg" <<DATA
command.exit keyboard 0x0 1 || joystick $id button_12 && joystick $id button_13
command.exit joystick 0xother button_12 && joystick 0xother button_13 || joystick $id button_12 && joystick $id button_13
command.save_state joystick $id button_12 && joystick $id button_5
command.state_slot_dec joystick $id button_12 && joystick $id button_10
command.state_slot_inc joystick $id button_12 && joystick $id button_11
gb.input.builtin.gamepad.up keyboard 0x0 82 || joystick $id button_8 || joystick 0xother button_8
gb.input.builtin.gamepad.down joystick $id button_9
gb.input.builtin.gamepad.left joystick $id button_10
gb.input.builtin.gamepad.right joystick $id button_11
gb.input.builtin.gamepad.select joystick $id button_12
gb.input.builtin.gamepad.start joystick $id button_13
vb.input.builtin.gamepad.lt joystick $id button_14
vb.input.builtin.gamepad.rt joystick $id button_15
lynx.input.builtin.gamepad.pause joystick $id button_13
ngp.input.builtin.gamepad.option joystick $id button_13
pce.input.port1.gamepad.run joystick $id button_13
wswan.input.builtin.gamepad.up-y joystick $id button_8
gb.input.builtin.gamepad.a joystick $id button_1
gb.input.builtin.gamepad.up joystick $id button_120
gb.input.builtin.gamepad.axis joystick $id abs_0-
command.other joystick $id button_12
DATA
set_mednafen_input_binds
primed set_mednafen_input_binds
exact "$cfg" "command.exit keyboard 0x0 1 || joystick $id button_9 && joystick $id button_8"
exact "$cfg" "command.exit joystick 0xother button_12 && joystick 0xother button_13 || joystick $id button_9 && joystick $id button_8"
exact "$cfg" "command.save_state joystick $id button_9 && joystick $id button_5"
exact "$cfg" "command.state_slot_dec joystick $id button_9 && joystick $id button_11"
exact "$cfg" "command.state_slot_inc joystick $id button_9 && joystick $id button_12"
exact "$cfg" "gb.input.builtin.gamepad.up keyboard 0x0 82 || joystick $id button_10 || joystick 0xother button_8"
for pair in down:13 left:11 right:12 select:9 start:8 a:1; do
  exact "$cfg" "gb.input.builtin.gamepad.${pair%:*} joystick $id button_${pair#*:}"
done
exact "$cfg" "vb.input.builtin.gamepad.lt joystick $id button_15"
exact "$cfg" "vb.input.builtin.gamepad.rt joystick $id button_14"
exact "$cfg" "lynx.input.builtin.gamepad.pause joystick $id button_8"
exact "$cfg" "ngp.input.builtin.gamepad.option joystick $id button_8"
exact "$cfg" "pce.input.port1.gamepad.run joystick $id button_8"
exact "$cfg" "wswan.input.builtin.gamepad.up-y joystick $id button_10"
exact "$cfg" "gb.input.builtin.gamepad.up joystick $id button_120"
exact "$cfg" "gb.input.builtin.gamepad.axis joystick $id abs_0-"
exact "$cfg" "command.other joystick $id button_12"
repeat set_mednafen_input_binds
echo 'F7 Mednafen native identity and full-token semantic upserts passed'
