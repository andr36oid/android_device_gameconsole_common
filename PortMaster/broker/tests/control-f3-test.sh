#!/bin/sh
. "$(dirname "$0")/control-test-lib.inc"
cfg="$ROOT/home/ark/.config/mupen64plus/mupen64plus.cfg"
mkdir -p "$(dirname "$cfg")"
cat > "$cfg" <<'DATA'
[CoreEvents]
Joy Mapping Stop = "J0B12/B13"
Joy Mapping Stop = "J0B12/B13"
Joy Mapping Fullscreen = ""
Kbd Mapping Stop = 27
[Input-SDL-Control1]
name = "GO-Super Gamepad"
Start = ""button(13)""
[Input-SDL-Control2]
name = "External Pad"
Start = "button(13)"
DATA
set_mupen_input_binds
for line in 'Stop = "J0B9/B8"' 'Save State = "J0B9/B7"' 'Load State = "J0B9/B6"' 'Reset = "J0B9/B2"' 'Pause = "J0B9/B1"' 'Fullscreen = ""'; do
  exact "$cfg" "Joy Mapping $line"
done
[ "$(grep -c '^Joy Mapping Stop =' "$cfg")" = 1 ] || fail 'duplicate core stop'
exact "$cfg" 'Kbd Mapping Stop = 27'
exact "$cfg" 'Start = "button(13)"'
repeat set_mupen_input_binds
# The saved player-1 name gates global CoreEvents.
sed 's/name = "GO-Super Gamepad"/name = "External Pad"/;s/J0B9\/B8/J0B12\/B13/' "$cfg" > "$tmp/external"
cp "$tmp/external" "$cfg"
set_mupen_input_binds
cmp "$tmp/external" "$cfg" || fail 'non-GO saved core events changed'
echo 'F3 Mupen GO core events passed'
