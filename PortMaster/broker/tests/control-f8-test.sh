#!/bin/sh
. "$(dirname "$0")/control-test-lib.inc"
for app in gzdoom lzdoom; do
  dir="$ROOT/home/ark/.config/$app"; mkdir -p "$dir/backup"
  cat > "$dir/$app.ini" <<'DATA'
[Doom.Bindings]
Joy1=+use
Joy11=weapprev
Joy12=weapnext
Joy14=menu_main
Space=+jump
Pad_Start=menu_main
[Doom.AutomapBindings]
Joy5=+am_zoomout
[Joy:JS:0]
Axis0deadzone=0.200001
DATA
  cp "$dir/$app.ini" "$dir/backup/$app.ini.351mp"
  cp "$dir/$app.ini" "$dir/$app.ini.bak"
done
cat >> "$ROOT/home/ark/.config/gzdoom/gzdoom.ini" <<'DATA'
[Doom.Bindings]
Joy13=togglemap
Joy16=centerview
DATA
cat >> "$ROOT/home/ark/.config/lzdoom/lzdoom.ini" <<'DATA'
[Doom.Bindings]
Joy10=centerview
[Hexen.Bindings]
Joy7=menu_main
Joy8=togglemap
Joy9=toggle cl_run
Joy10=centerview
Joy11=weapprev
Joy12=weapnext
Joy14=showpop 3
Joy15=showpop 2
Joy16=showpop 1
[Hexen.WOCKeysection.Bindings]
Joy16=viewStats
Joy2=abil1
Joy13=abil2
Joy14=abil3
Joy15=abil4
[Other.Section]
Joy14=menu_main
DATA
set_doom_input_binds
primed set_doom_input_binds
for app in gzdoom lzdoom; do
  cfg="$ROOT/home/ark/.config/$app/$app.ini"
  for line in 'Joy12=weapprev' 'Joy13=weapnext' 'Joy9=menu_main' 'Joy1=+use' 'Space=+jump' 'Pad_Start=menu_main' 'Joy5=+am_zoomout' 'Axis0deadzone=0.200001'; do exact "$cfg" "$line"; done
  cmp "$ROOT/home/ark/.config/$app/backup/$app.ini.351mp" "$ROOT/home/ark/.config/$app/$app.ini.bak" || fail 'restore seed changed'
  exact "$ROOT/home/ark/.config/$app/$app.ini.bak" 'Joy14=menu_main'
done
exact "$ROOT/home/ark/.config/gzdoom/gzdoom.ini" 'Joy10=togglemap'
exact "$ROOT/home/ark/.config/gzdoom/gzdoom.ini" 'Joy15=centerview'
for line in 'Joy14=centerview' 'Joy11=toggle cl_run' 'Joy9=showpop 3' 'Joy16=showpop 2' 'Joy15=showpop 1' 'Joy15=viewStats' 'Joy10=abil2' 'Joy9=abil3' 'Joy16=abil4' 'Joy7=menu_main' 'Joy8=togglemap' 'Joy14=menu_main'; do exact "$ROOT/home/ark/.config/lzdoom/lzdoom.ini" "$line"; done
repeat set_doom_input_binds
echo 'F8 Doom active-only absolute Joy action binds passed'
