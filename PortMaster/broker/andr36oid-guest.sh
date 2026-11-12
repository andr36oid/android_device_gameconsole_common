#!/system/bin/sh
# Guest integration overrides. The original compatibility rewrites are retained
# only for audit history; the native input ABI uses a fresh, separate overlay.
GUEST=${A36_GUEST:-/system/bin/andr36oid-guest}
FN_WATCH=${A36_FN_WATCH:-/system/bin/andr36oid-fn-watch}
mount_rootfs() {
  if [ -n "$IMAGE" ]; then
    mount_generic_rootfs || return 1
    FAMILY=${IMAGE_ID:-generic}; GENERATION=generic
    return 0
  fi
  mount_managed_rootfs
}
scan_images() {
  scan_managed_images || return 1
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
  # Join the native setup worker, never wait on its queue while holding the
  # broker operation lock. The firstboot gate revalidates durable readiness.
  if command -v gameconsole-setup >/dev/null; then gameconsole-setup firstboot >/dev/null 2>&1 7>&- 9>&- & fi
}
select_image() {
  local selected oldfamily="" newfamily family keep
  selected=$(prop select_id); keep=$(prop select_keep)
  valid_id "$selected" || return 2
  acquire_operation select || return 1
  trap 'release_operation' EXIT
  if resolve_selected_image; then oldfamily=$FAMILY; fi
  resolve_selected_image "$selected" || return 1
  newfamily=$FAMILY
  for family in "$oldfamily" "$newfamily"; do
    valid_id "$family" || continue
    [ ! -L "$STATE/images/$family" ] || return 1
  done
  # Do not discard state unless the requested selection can be persisted.
  setprop "$SELECT_PROP" "$selected" || return 1
  # Default is a clean system overlay. Games and saves on /roms are independent.
  # Re-selecting the current family (including its EROFS copy) is a no-op.
  if [ "$keep" != 1 ] && [ "$oldfamily" != "$newfamily" ]; then
    for oldfamily in "$oldfamily" "$newfamily"; do
      valid_id "$oldfamily" || continue
      rm -f -- "$CONTROL/ready/$oldfamily-"* || return 1
      rm -rf -- "$STATE/images/$oldfamily" || return 1
    done
  fi
  publish select_error ""
  operation_done "done"
  release_operation; trap - EXIT
  scan_images
}
# Native buttonmon historically uses alias existence to select event2. Append
# an override after its function definitions: queries expand event_num at call
# time. Resolve the actual alias, including multi-digit event numbers.
repair_native_buttonmon() {
  local helper staging codes
  helper=$(root_path "$ROOT" /usr/local/bin/buttonmon.sh) || return 1
  [ -f "$helper" ] || return 0
  codes=$(root_path "$ROOT" /usr/local/libexec/andr36oid-pad-codes) || return 1
  mkdir -p "${codes%/*}" || return 1
  if ! cmp -s /system/bin/andr36oid-pad-codes "$codes"; then
    cp /system/bin/andr36oid-pad-codes "$codes.tmp" && chmod 0755 "$codes.tmp" &&
      mv -f "$codes.tmp" "$codes" || { rm -f "$codes.tmp"; return 1; }
  fi
  grep -q '^# andr36oid native pad alias v2$' "$helper" && return 0
  staging=$(mktemp "${helper%/*}/.a36-buttonmon.XXXXXX") || return 1
  cp -p "$helper" "$staging" || { rm -f "$staging"; return 1; }
  cat >> "$staging" <<'A36_BUTTONMON'

# andr36oid native pad alias v2
a36_pad_alias=/dev/input/by-path/platform-odroidgo3-joypad-event-joystick
if [ -e "$a36_pad_alias" ]; then
  a36_pad_node=$(readlink -f "$a36_pad_alias") || return 1
  case "$a36_pad_node" in /dev/input/event*) ;; *) return 1 ;; esac
  a36_pad_number=${a36_pad_node#/dev/input/event}
  case "$a36_pad_number" in ''|*[!0-9]*) return 1 ;; esac
  event_num=$a36_pad_number
  a36_pad_codes=$(/usr/local/libexec/andr36oid-pad-codes "$a36_pad_node") || return 1
  read -r event_btn_a event_btn_b event_btn_x event_btn_y event_btn_r1 event_btn_r2 \
    event_btn_l1 event_btn_l2 event_btn_hk <<< "$a36_pad_codes"
fi
unset a36_pad_alias a36_pad_node a36_pad_number a36_pad_codes
A36_BUTTONMON
  mv -f "$staging" "$helper" || { rm -f "$staging"; return 1; }
}
repair_native_fn_watcher() {
  local helper staging
  helper=$(root_path "$ROOT" /usr/local/bin/fn_menu_watcher.sh) || return 1
  [ -f "$helper" ] || return 0
  # Replace the image helper at its original path so both the wrapper and
  # fn-menu-watcher.service use the same state machine, with just one sender.
  [ -x "$FN_WATCH" ] || return 1
  cmp -s "$FN_WATCH" "$helper" && return 0
  staging=$(mktemp "${helper%/*}/.a36-fn-watch.XXXXXX") || return 1
  cp "$FN_WATCH" "$staging" && chmod 0755 "$staging" && mv -f "$staging" "$helper" ||
    { rm -f "$staging"; return 1; }
}
# The image ships unterminated A/B quotes in both the live dingux profile and
# its restore copy. Repair the text syntax without changing the chosen layout.
repair_native_dingux_quotes() {
  local cfg staging
  for cfg in /opt/dingux/oshgamepad.cfg /opt/dingux/oshgamepad.cfg.bak; do
    cfg=$(root_path "$ROOT" "$cfg") || return 1
    [ -f "$cfg" ] || continue
    staging=$(mktemp "${cfg%/*}/.a36-dingux.XXXXXX") || return 1
    cp -p "$cfg" "$staging" || { rm -f "$staging"; return 1; }
    if ! awk '
      {
        key=$0; sub(/[[:space:]]*=.*$/, "", key); sub(/^[[:space:]]*/, "", key)
        if (key ~ /^input_(player1_)?[ab]_btn$/ && index($0, "=")) {
          value=substr($0, index($0, "=")+1)
          if (value ~ /^[[:space:]]*"?[0-9]+"?[[:space:]]*$/) {
            gsub(/["[:space:]]/, "", value)
            $0=key " = \"" value "\""
          }
        }
        print
      }
    ' "$cfg" > "$staging"; then rm -f "$staging"; return 1; fi
    if cmp -s "$cfg" "$staging"; then rm -f "$staging"
    else mv -f "$staging" "$cfg" || { rm -f "$staging"; return 1; }; fi
  done
}
prime_native_input() {
  # Upstream contains two competing GO profiles, one with invalid axes/button A.
  # Correct that default defect AFTER supplying its native kernel ABI. No emulator-
  # wide numeric rewrites, launch-script patches or SDL override environment.
  [ -z "$IMAGE" ] || return 0
  repair_native_buttonmon || return 1
  repair_native_fn_watcher || return 1
  # dingux is one launcher among many; a quote-repair I/O error must not block the
  # whole handoff (same rule as the gamma snapshot).
  repair_native_dingux_quotes || log "dingux gamepad quote repair skipped; continuing"
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
# A session-local ES command wrapper gives immediate relaunch a synchronous
# stale-mount check, even when the previous launcher was SIGKILLed or replaced
# its EXIT trap. Keep the user's original config in the rootfs unchanged.
repair_port_launch_boundary() {
  [ -z "$IMAGE" ] || return 0
  local cfg original generated command
  cfg=$(root_path "$ROOT" /etc/emulationstation/es_systems.cfg) || return 1
  [ -f "$cfg" ] || return 0
  original="$ROOT/run/andr36oid-es-systems.original"
  generated="$ROOT/run/andr36oid-es-systems.cfg"
  command='sudo perfmax %GOVERNOR% %ROM%; nice -n -19 %ROM%; sudo perfnorm'
  if [ ! -f "$original" ]; then
    grep -Fq "<command>$command</command>" "$cfg" || return 0
    cp "$cfg" "$original" || return 1
  fi
  cp "$GUEST" "$ROOT/run/andr36oid-port-helper" && chmod 0755 "$ROOT/run/andr36oid-port-helper" || return 1
  cat > "$ROOT/run/andr36oid-port-launch" <<'PORT'
#!/bin/sh
attempt=0
until /run/andr36oid-port-helper --clean-port-mounts /; do
  attempt=$((attempt+1))
  if [ "$attempt" -ge 25 ]; then
    echo "Port runtime cleanup is still busy; refusing an overlapping launch"
    exit 125
  fi
  sleep 0.2
done
sudo perfmax "${2:-performance}" "$1"
nice -n -19 "$1"
status=$?
sudo perfnorm
exit "$status"
PORT
  chmod 0755 "$ROOT/run/andr36oid-port-launch" || return 1
  awk -v old="$command" '
    /<system>/ {ports=0}
    /<name>ports<\/name>/ {ports=1}
    ports && index($0,"<command>" old "</command>") {
      sub(/<command>.*<\/command>/,"<command>/run/andr36oid-port-launch %ROM% %GOVERNOR%</command>")
    }
    {print}
  ' "$original" > "$generated" || return 1
  if ! mounted_at "$cfg"; then
    local base_alias
    if ! base_alias=$(root_path "$ROOT" /etc/emulationstation/.andr36oid-es-base); then
      disable_storage_tools 'ES base alias unavailable'
    elif [ ! "$cfg" -ef "$base_alias" ]; then
      if ! { rm -f "$base_alias" && ln "$cfg" "$base_alias"; } 2>/dev/null; then
        disable_storage_tools 'ES base alias unavailable'
      fi
    fi
    bind_mount "$generated" "$cfg" || return 1
  fi
  log "ports: session-local prelaunch cleanup enabled"
}
# Keep native menu scripts and their user-visible behavior. Replace only the
# physical-storage transport and the ES sed on our session-local config bind.
# This is optional: native script changes or a read-only overlay must never
# prevent a session. Disable the whole adaptation on failure, so no adapted
# script is left depending on a partially installed service.
disable_storage_tools() {
  local path target original temporary
  STORAGE_TOOLS_READY=0
  log "storage tools disabled: $1; continuing with native tools"
  rm -f "$ROOT/run/andr36oid-storage-helper" 2>/dev/null || :
  for path in '/opt/system/System/USB Drive Mount.sh' '/opt/system/System/USB Drive Unmount.sh' \
      '/usr/local/bin/Switch to SD2 for Roms.sh' '/usr/local/bin/Switch to Main SD for Roms.sh'; do
    target=$(root_path "$ROOT" "$path") || continue
    original="$target.andr36oid-native"; temporary="$target.a36-storage.tmp"
    # A new native script may have replaced our overlay. Never replace it with
    # an older saved version. Only roll back files carrying our own marker.
    if [ -f "$original" ] && grep -q '^# andr36oid-storage-tools-v1$' "$target" 2>/dev/null; then
      { cp -p "$original" "$temporary" && mv -f "$temporary" "$target"; } 2>/dev/null || :
    fi
    rm -f "$temporary" "$original.tmp" 2>/dev/null || :
  done
  return 0
}
prepare_storage_tools() {
  STORAGE_TOOLS_READY=0
  STORAGE_TOOLS_ERROR='preparation failed'
  if prepare_storage_tools_apply 2>/dev/null; then
    return 0
  fi
  disable_storage_tools "$STORAGE_TOOLS_ERROR"
  return 0
}
prepare_storage_tools_apply() {
  local mode path target output original temporary found=0
  STORAGE_TOOLS_ERROR='config recovery failed'
  "$GUEST" --storage-recover "$ROOT" || return 1
  for mode in usb-mount usb-unmount sd2 main; do
    case "$mode" in
      usb-mount) path='/opt/system/System/USB Drive Mount.sh' ;;
      usb-unmount) path='/opt/system/System/USB Drive Unmount.sh' ;;
      sd2) path='/usr/local/bin/Switch to SD2 for Roms.sh' ;;
      main) path='/usr/local/bin/Switch to Main SD for Roms.sh' ;;
    esac
    STORAGE_TOOLS_ERROR="$path: overlay preparation failed"
    target=$(root_path "$ROOT" "$path") || return 1
    [ -f "$target" ] || continue
    found=1
    original="$target.andr36oid-native"; temporary="$target.a36-storage.tmp"
    if ! grep -q '^# andr36oid-storage-tools-v1$' "$target" || [ ! -f "$original" ]; then
      cp -p "$target" "$original.tmp" && mv -f "$original.tmp" "$original" || return 1
    fi
    mkdir -p "$ROOT/run/andr36oid-storage-tools" || return 1
    output="$ROOT/run/andr36oid-storage-tools/$mode"
    awk -v mode="$mode" '
      NR==1 {print; print "# andr36oid-storage-tools-v1"; next}
      /filesystem=.*lsblk -no FSTYPE \/dev\/(sda1|mmcblk1p1|mmcblk0p3)/ {
        if (mode=="usb-mount" || mode=="sd2" || mode=="main") {
          verb=(mode=="sd2"?"mount-sd2":mode=="main"?"mount-main":"mount-usb")
          print "filesystem=$(/run/andr36oid-storage-helper --storage " verb ")"
          print "status=$?"; transport++; next
        }
      }
      mode=="usb-mount" && /^sudo mount -t .*\/dev\/sda1 / { skipstatus=1; next }
      mode=="usb-mount" && skipstatus && /^status=\$\?/ {next}
      mode=="usb-unmount" && /^sudo umount \/mnt\/usbdrive/ {
        print "/run/andr36oid-storage-helper --storage unmount-usb"; transport++; next
      }
      (mode=="sd2" || mode=="main") && /^sudo umount -l \/opt\/system\/Tools/ {next}
      mode=="main" && /^sudo umount \/roms / {next}
      mode=="main" && /^sudo mount -t .*\/dev\/mmcblk0p3 / {next}
      mode=="main" && /^status=\$\?/ {next}
      mode=="sd2" && !skipped && /^if \[ "\$filesystem" = "ext4" \]; then/ {skipmount=1; skipped=1}
      mode=="sd2" && skipmount {if ($0 ~ /^status=\$\?/) skipmount=0; next}
      (mode=="sd2" && /^if test \$status -eq 0/) || (mode=="main" && /^if \[ \$status -eq 0 \]/) { success=1 }
      mode=="sd2" && success && /^then$/ {print; print "  sudo umount -l /opt/system/Tools";success=0;next}
      mode=="main" && success {print; print "  sudo umount -l /opt/system/Tools";success=0;next}
      (mode=="sd2" || mode=="main") && /sed -i .*<path>.*\/etc\/emulationstation\/es_systems.cfg/ {
        print "  /run/andr36oid-storage-helper --storage config-" (mode=="sd2"?"sd2":"main")
        config++; next
      }
      {print}
      END {
        if (mode=="main") {if(config!=1 || transport!=1)exit 1}
        else if(transport!=1 || (mode=="sd2" && (!skipped || config!=1)))exit 1
      }
    ' "$original" > "$output" || { STORAGE_TOOLS_ERROR="$path: unknown native script format"; return 1; }
    chmod 0755 "$output" || return 1
    if ! cmp -s "$output" "$target"; then
      cp -p "$target" "$temporary" && cat "$output" > "$temporary" &&
        mv -f "$temporary" "$target" || return 1
    fi
  done
  [ "$found" = 1 ] || return 0
  STORAGE_TOOLS_ERROR='helper installation failed'
  cp "$GUEST" "$ROOT/run/andr36oid-storage-helper" && chmod 0755 "$ROOT/run/andr36oid-storage-helper" || return 1
  STORAGE_TOOLS_READY=1
  return 0
}
write_guest_session() {
  repair_port_launch_boundary || return 1
  if [ -z "$IMAGE" ]; then
    local sudo_path
    sudo_path=$(root_path "$ROOT" /usr/local/bin/sudo) || return 1
    mkdir -p "${sudo_path%/*}"
    # The guest already runs as root in its namespace, so sudo is a stand-in that
    # just runs the command. It MUST drop sudo's own options first: PortMaster
    # launches ports as `sudo --preserve-env=VAR,... cmd args`, and a bare
    # `exec "$@"` would try to exec the --preserve-env flag as the command, so
    # every port failed to launch. Skip leading options (consuming a value for the
    # few that take a separate one) and exec the real command.
    cat > "$sudo_path" <<'SUDO' || return 1
#!/bin/sh
while [ $# -gt 0 ]; do
  case "$1" in
    --) shift; break ;;
    -u|--user|-g|--group|-C|--close-from|-p|--prompt|-r|--role|-t|--type|-T|--command-timeout|-R|--chroot|-D|--chdir|-h|--host) shift 2; continue ;;
    -*) shift; continue ;;
    *) break ;;
  esac
done
exec "$@"
SUDO
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
  # dArkOSen's ogage opens a fixed /dev/input/event0-10 list and aborts (a Rust
  # unwrap) on a node that exists but cannot be opened (ENXIO) - e.g. a stale
  # virtual uinput pad left in /dev/input. That kills the volume daemon, so the
  # volume keys do nothing in-session. Drop unopenable nodes first; ogage then
  # treats them as absent and survives. Read-only open test, no side effects.
  for _n in /dev/input/event*; do [ -e "$_n" ] || continue; ( exec 3<"$_n" ) 2>/dev/null || rm -f "$_n"; done
  if [ -x /usr/local/bin/ogage ]; then
    if [ "$mute" = 1 ]; then
      mkdir -p /run/andr36oid-muted-bin
      printf '#!/bin/sh\nexit 0\n' > /run/andr36oid-muted-bin/amixer
      chmod 0755 /run/andr36oid-muted-bin/amixer
      PATH=/run/andr36oid-muted-bin:$PATH /usr/local/bin/ogage &
    else PATH=/run/andr36oid-audio-bin:$PATH /usr/local/bin/ogage & fi
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
    A36_STORAGE_READY="${STORAGE_TOOLS_READY:-0}" \
    ALSA_TMPDIR=/run/alsa TMPDIR=/tmp SDL_VIDEODRIVER="$video" \
    SDL_VIDEO_EGL_DRIVER=libEGL.so SDL_KMSDRM_REQUIRE_DRM_MASTER="$HANDOFF" \
    "$GUEST" "$ROOT" "$mode" "$keys" -- "$@"
}

# Preserve the Android mixer controls touched by the guest and handoff broker.
capture_guest_mixer() {
  if ! "$GUEST" --capture-mixer > "$STATE/session.mixer.tmp"; then
    log "could not capture validated mixer scalars; refusing hardware handoff"
    rm -f "$STATE/session.mixer.tmp"
    return 1
  fi
  mv "$STATE/session.mixer.tmp" "$STATE/session.mixer"
}
restore_guest_mixer() {
  local control value failed=0
  [ -f "$STATE/session.mixer" ] || return 0
  while IFS="$(printf '\t')" read -r control value; do
    case "$control" in
      "Playback Path") tinymix -D 0 "$control" "$value" >/dev/null 2>&1 || { log "could not restore $control"; failed=1; } ;;
      "Playback Hardware Master Switch")
        case "$value" in On) value=1;; Off) value=0;; 0|1) ;; *) failed=1; continue;; esac
        tinymix -D 0 "$control" "$value" >/dev/null 2>&1 || { log "could not restore $control"; failed=1; } ;;
      "Master Playback Volume"|"Playback Volume")
        case "$value" in ''|*[!0-9' ']*) failed=1; continue;; esac
        # Only validated numeric mixer channels are split into separate arguments.
        tinymix -D 0 "$control" $value >/dev/null 2>&1 || { log "could not restore $control"; failed=1; } ;;
      *) failed=1 ;;
    esac
  done < "$STATE/session.mixer"
  [ "$failed" = 0 ] || return 1
  rm -f "$STATE/session.mixer" "$STATE/session.hw-master"
}

# Only brightness is delegated writable sysfs; governors, regulators and block
# devices remain read-only. Snapshot before exposing it and restore on every exit.
prepare_guest_hardware() {
  [ "$HANDOFF" = 1 ] || return 0
  local node real
  capture_guest_mixer || return 1
  # Gamma snapshot is cosmetic (restores dArkOSen's LUT tweaks on return). Some
  # panels' DRM drivers reject the legacy GETGAMMA ioctl (EFAULT); that must not
  # block the handoff. Best-effort: on failure skip it and leave no stale snapshot.
  "$GUEST" --save-gamma "$STATE/session.gamma" ||
    { log "could not snapshot display gamma; continuing without it"; rm -f "$STATE/session.gamma"; }
  : > "$STATE/session.brightness"
  for node in /sys/class/backlight/*/brightness; do
    [ -f "$node" ] || continue
    real=$(readlink -f "$node") || return 1
    printf '%s %s\n' "$real" "$(cat "$node")" >> "$STATE/session.brightness"
    if ! mounted_at "$ROOT$real" || ! grep -Fxq "$ROOT$real" "$STATE/session.mounts"; then
      "$GUEST" --bind "$real" "$ROOT$real" || return 1
      track_mount "$ROOT$real" || return 1
    fi
  done
}
restore_guest_hardware() {
  local node value failed=0 brightness_failed=0
  restore_guest_mixer || failed=1
  if [ -f "$STATE/session.gamma" ]; then
    if [ "$DID_HANDOFF" = 1 ]; then
      if "$GUEST" --restore-gamma "$STATE/session.gamma"; then
        rm -f "$STATE/session.gamma"
      else
        log "could not restore display gamma"; failed=1
      fi
    else
      rm -f "$STATE/session.gamma"
    fi
  fi
  if [ -f "$STATE/session.brightness" ]; then
    while read -r node value; do
      case "$node" in /sys/devices/*/brightness) ;; *) brightness_failed=1; continue;; esac
      case "$value" in ''|*[!0-9]*) brightness_failed=1; continue;; esac
      printf '%s\n' "$value" > "$node" || { log "could not restore brightness"; brightness_failed=1; }
    done < "$STATE/session.brightness"
    if [ "$brightness_failed" = 0 ]; then rm -f "$STATE/session.brightness"; else failed=1; fi
  fi
  return "$failed"
}
