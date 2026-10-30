#!/system/bin/sh
# Guest integration overrides. The original compatibility rewrites are retained
# only for audit history; the native input ABI uses a fresh, separate overlay.
GUEST=${A36_GUEST:-/system/bin/andr36oid-guest}
mount_rootfs() {
  if [ -n "$IMAGE" ]; then
    mount_generic_rootfs || return 1
    FAMILY=${IMAGE_ID:-generic}; GENERATION=generic
    return 0
  fi
  mount_managed_rootfs
}
scan_images() {
  scan_managed_images
  update_settings_entry org.andr36oid.subsystemsetup
}
publish_setup_complete() {
  [ -n "$IMAGE" ] && return 0
  mkdir -p "$CONTROL/ready" "$CONTROL/versions"
  printf '%s\n' "$EFFECTIVE" > "$CONTROL/ready/$(ready_key).tmp" || return 1
  "$SUPPORT" fsync "$CONTROL/ready/$(ready_key).tmp" || return 1
  mv "$CONTROL/ready/$(ready_key).tmp" "$CONTROL/ready/$(ready_key)" || return 1
  "$SUPPORT" fsync "$CONTROL/ready" || return 1
  local version
  version=$(cat "$ROOT/home/ark/.config/.VERSION" "$ROOT/etc/os-release" 2>/dev/null | head -12 | tr -d '\r')
  printf '%s\n' "$version" > "$CONTROL/versions/$(ready_key)"
  "$SUPPORT" fsync "$CONTROL/versions/$(ready_key)" || return 1
  cp "$CONTROL/versions/$(ready_key)" "$STATE/version.txt"; chmod 0644 "$STATE/version.txt"
  publish ready 1
  setprop persist.andr36oid.subsystem.booted 1
  update_settings_entry org.andr36oid.subsystemsetup
}
select_image() {
  local selected oldfamily="" newfamily keep
  selected=$(prop select_id); keep=$(prop select_keep)
  valid_id "$selected" || return 2
  acquire_operation select || return 1
  trap 'release_operation' EXIT
  if resolve_selected_image; then oldfamily=$FAMILY; fi
  resolve_selected_image "$selected" || return 1
  newfamily=$FAMILY
  # Default is a clean system overlay. Games and saves on /roms are independent.
  if [ "$keep" != 1 ]; then
    for oldfamily in "$oldfamily" "$newfamily"; do
      valid_id "$oldfamily" || continue
      [ ! -L "$STATE/images/$oldfamily" ] || return 1
      rm -rf -- "$STATE/images/$oldfamily" || return 1
      rm -f -- "$CONTROL/ready/$oldfamily-"*
    done
  fi
  setprop "$SELECT_PROP" "$selected" || return 1
  publish select_error ""
  operation_done "done"
  release_operation; trap - EXIT
  scan_images
}
prime_native_input() {
  # Upstream contains two competing GO profiles, one with invalid axes/button A.
  # Correct that default defect AFTER supplying its native kernel ABI. No emulator-
  # wide numeric rewrites, launch-script patches or SDL override environment.
  [ -z "$IMAGE" ] || return 0
  local dir cfg
  for dir in "$ROOT/home/ark/.config/retroarch/autoconfig/udev" "$ROOT/home/ark/.config/retroarch32/autoconfig/udev"; do
    dir=$(root_path "$ROOT" "${dir#"$ROOT"}") || return 1
    [ -d "$dir" ] || continue
    [ ! -f "$dir/.andr36oid-native1" ] || continue
    for cfg in "$dir/GO-Super Gamepad.cfg" "$dir/go_super_gamepad.cfg"; do
      cfg=$(root_path "$ROOT" "${cfg#"$ROOT"}") || return 1
      cat > "$cfg" <<'EOF'
input_driver = "udev"
input_device = "GO-Super Gamepad"
input_vendor_id = "18507"
input_product_id = "4352"
input_b_btn = "0"
input_a_btn = "1"
input_x_btn = "2"
input_y_btn = "3"
input_l_btn = "4"
input_r_btn = "5"
input_l2_btn = "6"
input_r2_btn = "7"
input_up_btn = "8"
input_down_btn = "9"
input_left_btn = "10"
input_right_btn = "11"
input_select_btn = "12"
input_start_btn = "13"
input_l3_btn = "14"
input_r3_btn = "15"
input_enable_hotkey_btn = "12"
input_menu_toggle_btn = "16"
input_l_x_plus_axis = "+0"
input_l_x_minus_axis = "-0"
input_l_y_plus_axis = "+1"
input_l_y_minus_axis = "-1"
input_r_x_plus_axis = "+2"
input_r_x_minus_axis = "-2"
input_r_y_plus_axis = "+3"
input_r_y_minus_axis = "-3"
EOF
    done
    : > "$dir/.andr36oid-native1"
  done
  # Kodi's Linux peripheral map is identified by exact button/axis counts.
  dir="$ROOT/home/ark/.kodi/userdata/addon_data/peripheral.joystick/resources/buttonmaps/xml/linux"
  dir=$(root_path "$ROOT" "${dir#"$ROOT"}") || return 1
  mkdir -p "$dir"
  if [ ! -e "$dir/GO-Super_Gamepad_17b_4a.xml" ]; then
    cat > "$dir/GO-Super_Gamepad_17b_4a.xml" <<'EOF'
<buttonmap><device name="GO-Super Gamepad" provider="linux" buttoncount="17" axiscount="4">
<controller id="game.controller.default">
<feature name="a" button="0"/><feature name="b" button="1"/>
<feature name="x" button="2"/><feature name="y" button="3"/>
<feature name="leftbumper" button="4"/><feature name="rightbumper" button="5"/>
<feature name="lefttrigger" button="6"/><feature name="righttrigger" button="7"/>
<feature name="up" button="8"/><feature name="down" button="9"/>
<feature name="left" button="10"/><feature name="right" button="11"/>
<feature name="back" button="12"/><feature name="start" button="13"/>
<feature name="leftthumb" button="14"/><feature name="rightthumb" button="15"/>
<feature name="guide" button="16"/>
<feature name="leftstick"><up axis="-1"/><down axis="+1"/><left axis="-0"/><right axis="+0"/></feature>
<feature name="rightstick"><up axis="-3"/><down axis="+3"/><left axis="-2"/><right axis="+2"/></feature>
</controller></device></buttonmap>
EOF
  fi
}
# Hotkey daemons start inside the same PID namespace as the frontend.
start_hotkeys() { :; }
write_guest_session() {
  if [ -z "$IMAGE" ]; then
    local sudo_path
    sudo_path=$(root_path "$ROOT" /usr/local/bin/sudo) || return 1
    mkdir -p "${sudo_path%/*}"
    printf '#!/bin/sh\nexec "$@"\n' > "$sudo_path" || return 1
    chmod 0755 "$sudo_path"
  fi
  # Native status daemon requires NetworkManager; derive ES indicators from the
  # shared kernel network instead, without starting another network manager.
  cat > "$ROOT/run/andr36oid-status.sh" <<'EOF'
#!/bin/sh
while :; do
  wifi=0
  if ip -o link show 2>/dev/null | grep -q wlan; then
    wifi=1
    ip -o addr show 2>/dev/null | grep -Eq 'wlan[^ ]* +inet6? ' && wifi=2
  fi
  printf '%s\n' "$wifi" > /tmp/es-wifi-state.a36
  mv /tmp/es-wifi-state.a36 /tmp/es-wifi-state
  sleep 5
done
EOF
  cat > "$ROOT/run/andr36oid-session.sh" <<'EOF'
#!/bin/sh
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/home/ark XDG_RUNTIME_DIR=/run/user/0
handoff=$1; mute=$2; shift 2
if [ "$handoff" = 1 ]; then
  if [ -x /usr/local/bin/ogage ]; then
    if [ "$mute" = 1 ]; then
      mkdir -p /run/andr36oid-muted-bin
      printf '#!/bin/sh\nexit 0\n' > /run/andr36oid-muted-bin/amixer
      chmod 0755 /run/andr36oid-muted-bin/amixer
      PATH=/run/andr36oid-muted-bin:$PATH /usr/local/bin/ogage &
    else /usr/local/bin/ogage & fi
  fi
  /bin/sh /run/andr36oid-status.sh &
  if [ -x /usr/local/bin/fn_menu_watcher.sh ]; then /usr/local/bin/fn_menu_watcher.sh & fi
  if command -v dbus-daemon >/dev/null; then dbus-daemon --system --fork 2>/dev/null || true; fi
fi
cd /home/ark 2>/dev/null || cd /
exec "$@"
EOF
}
run_in_subsystem() {
  local video=dummy mode=generic keys=quiet mute=0
  [ "$HANDOFF" = 0 ] || { video=kmsdrm; keys=hotkeys; }
  [ -n "$IMAGE" ] || mode=native
  [ "$(getprop persist.andr36oid.subsystem.hotkeys)" != 0 ] || keys=quiet
  subsystem_muted && mute=1
  if [ "$SESSION_MODE" = systemd ]; then
    mode=init; [ -n "$IMAGE" ] || mode=native-init
  else
    write_guest_session || return 1
    set -- /bin/sh /run/andr36oid-session.sh "$HANDOFF" "$mute" "$@"
  fi
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    HOME=/home/ark TERM=linux XDG_RUNTIME_DIR=/run/user/0 \
    ALSA_TMPDIR=/run/alsa TMPDIR=/tmp SDL_VIDEODRIVER="$video" \
    SDL_VIDEO_EGL_DRIVER=libEGL.so SDL_KMSDRM_REQUIRE_DRM_MASTER="$HANDOFF" \
    "$GUEST" "$ROOT" "$mode" "$keys" -- "$@"
}

# Preserve the Android mixer controls touched by the guest and handoff broker.
capture_guest_mixer() {
  local control value
  : > "$STATE/session.mixer.tmp"
  for control in "Playback Path" "Master Playback Volume" "Playback Volume" "Playback Hardware Master Switch"; do
    value=$(tinymix -D 0 -v "$control" 2>/dev/null) || continue
    printf '%s\t%s\n' "$control" "$value" >> "$STATE/session.mixer.tmp"
  done
  mv "$STATE/session.mixer.tmp" "$STATE/session.mixer"
}
restore_guest_mixer() {
  local control value
  [ -f "$STATE/session.mixer" ] || return 0
  while IFS="$(printf '\t')" read -r control value; do
    case "$control" in
      "Playback Path") tinymix -D 0 "$control" "$value" >/dev/null 2>&1 || log "could not restore $control" ;;
      "Playback Hardware Master Switch")
        case "$value" in On) value=1;; Off) value=0;; 0|1) ;; *) continue;; esac
        tinymix -D 0 "$control" "$value" >/dev/null 2>&1 || log "could not restore $control" ;;
      "Master Playback Volume"|"Playback Volume")
        case "$value" in ''|*[!0-9' ']*) continue;; esac
        # Only validated numeric mixer channels are split into separate arguments.
        tinymix -D 0 "$control" $value >/dev/null 2>&1 || log "could not restore $control" ;;
    esac
  done < "$STATE/session.mixer"
  rm -f "$STATE/session.mixer"
}

# Only brightness is delegated writable sysfs; governors, regulators and block
# devices remain read-only. Snapshot before exposing it and restore on every exit.
prepare_guest_hardware() {
  [ "$HANDOFF" = 1 ] || return 0
  local node real
  capture_guest_mixer || return 1
  "$GUEST" --save-gamma "$STATE/session.gamma" || return 1
  : > "$STATE/session.brightness"
  for node in /sys/class/backlight/*/brightness; do
    [ -f "$node" ] || continue
    real=$(readlink -f "$node") || return 1
    printf '%s %s\n' "$real" "$(cat "$node")" >> "$STATE/session.brightness"
    "$GUEST" --bind "$real" "$ROOT$real" || return 1
    track_mount "$ROOT$real"
  done
}
restore_guest_hardware() {
  local node value
  restore_guest_mixer
  if [ -f "$STATE/session.gamma" ]; then
    if [ "$DID_HANDOFF" = 1 ]; then
      "$GUEST" --restore-gamma "$STATE/session.gamma" || log "could not restore display gamma"
    fi
    rm -f "$STATE/session.gamma"
  fi
  [ -f "$STATE/session.brightness" ] || return 0
  while read -r node value; do
    case "$node" in /sys/devices/*/brightness) ;; *) continue;; esac
    case "$value" in ''|*[!0-9]*) continue;; esac
    printf '%s\n' "$value" > "$node" || log "could not restore brightness"
  done < "$STATE/session.brightness"
  rm -f "$STATE/session.brightness"
}
