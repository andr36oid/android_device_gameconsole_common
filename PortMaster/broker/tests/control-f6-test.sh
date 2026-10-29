#!/bin/sh
. "$(dirname "$0")/control-test-lib.inc"
for app in drastic advanceddrastic; do
  dir="$ROOT/opt/$app/config"; mkdir -p "$dir/backup/configs"
  cat > "$dir/drastic.cfg" <<'DATA'
frameskip_type = 2
controls_a[CONTROL_INDEX_START] = 1037
controls_a[CONTROL_INDEX_SELECT] = 1036
controls_a[CONTROL_INDEX_TOUCH_CURSOR_PRESS] = 1039
controls_a[CONTROL_INDEX_MENU] = 1038
controls_a[CONTROL_INDEX_A] = 1025
controls_a[CONTROL_INDEX_B] = 1024
controls_a[CONTROL_INDEX_TOUCH_CURSOR_UP] = 1219
controls_a[CONTROL_INDEX_UI_EXIT] = 1040
controls_b[CONTROL_INDEX_QUIT] = 65535
DATA
  cp "$dir/drastic.cfg" "$dir/backup/configs/drastic.cfg.other"
  printf 'DSC2\000\377binary' > "$dir/drastic.cf2"
done
cat >> "$ROOT/opt/drastic/config/drastic.cfg" <<'DATA'
controls_a[CONTROL_INDEX_UP] = 1217
controls_b[CONTROL_INDEX_UP] = 1032
controls_b[CONTROL_INDEX_DOWN] = 1033
controls_b[CONTROL_INDEX_LEFT] = 1034
controls_b[CONTROL_INDEX_RIGHT] = 1035
controls_b[CONTROL_INDEX_START] = 13
controls_b[CONTROL_INDEX_SELECT] = 538
controls_b[CONTROL_INDEX_TOUCH_CURSOR_PRESS] = 1039
controls_b[CONTROL_INDEX_MENU] = 1038
controls_b[CONTROL_INDEX_UI_SWITCH] = 1038
DATA
cat >> "$ROOT/opt/advanceddrastic/config/drastic.cfg" <<'DATA'
controls_a[CONTROL_INDEX_UP] = 1032
controls_a[CONTROL_INDEX_DOWN] = 1033
controls_a[CONTROL_INDEX_LEFT] = 1034
controls_a[CONTROL_INDEX_RIGHT] = 1035
controls_a[CONTROL_INDEX_HOT] = 1036
controls_a[CONTROL_INDEX_HOT_CHANGE_THEME] = 1032
controls_b[CONTROL_INDEX_UP] = 65535
DATA
set_drastic_input_binds
primed set_drastic_input_binds
for app in drastic advanceddrastic; do
  cfg="$ROOT/opt/$app/config/drastic.cfg"
  for line in 'START] = 1032' 'SELECT] = 1033' 'TOUCH_CURSOR_PRESS] = 1038' 'MENU] = 1039' 'A] = 1025' 'B] = 1024' 'TOUCH_CURSOR_UP] = 1219' 'UI_EXIT] = 1040'; do
    exact "$cfg" "controls_a[CONTROL_INDEX_$line"
  done
  [ "$app" != drastic ] || bank=b
  [ "$app" != advanceddrastic ] || bank=a
  for line in 'UP] = 1034' 'DOWN] = 1037' 'LEFT] = 1035' 'RIGHT] = 1036'; do
    exact "$cfg" "controls_$bank[CONTROL_INDEX_$line"
    exact "$cfg" "controls_$bank[CONTROL_INDEX_UI_$line"
  done
done
exact "$ROOT/opt/drastic/config/drastic.cfg" 'controls_a[CONTROL_INDEX_UP] = 1217'
exact "$ROOT/opt/drastic/config/drastic.cfg" 'controls_b[CONTROL_INDEX_START] = 13'
exact "$ROOT/opt/drastic/config/drastic.cfg" 'controls_b[CONTROL_INDEX_UI_SWITCH] = 1039'
exact "$ROOT/opt/advanceddrastic/config/drastic.cfg" 'controls_a[CONTROL_INDEX_HOT] = 1033'
exact "$ROOT/opt/advanceddrastic/config/drastic.cfg" 'controls_a[CONTROL_INDEX_HOT_CHANGE_THEME] = 1034'
exact "$ROOT/opt/advanceddrastic/config/drastic.cfg" 'controls_b[CONTROL_INDEX_UP] = 65535'
repeat set_drastic_input_binds
for app in drastic advanceddrastic; do
  dir="$ROOT/opt/$app/config"
  exact "$dir/backup/configs/drastic.cfg.other" 'controls_a[CONTROL_INDEX_START] = 1037'
  printf 'DSC2\000\377binary' > "$tmp/binary"
  cmp "$tmp/binary" "$dir/drastic.cf2" || fail 'binary companion changed'
done
echo 'F6 DraStic absolute encoded semantic upserts passed'
