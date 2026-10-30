#!/system/bin/sh
# SPDX-License-Identifier: MIT
# andr36oid Subsystem for Linux - handoff broker.
#
# Boots the user's dArkOS (dArkOSen) userspace - EmulationStation / PortMaster and
# its ports - on the console's real GPU, by handing the display off from Android
# for the duration and restoring Android cleanly on exit. Nothing of dArkOS is
# shipped with andr36oid: the rootfs comes from a dArkOSen image the user supplies.
#
# Proven mechanics (see work/reports/portmaster, gates G1-G3 + the ES bring-up):
#   * Android `stop` tears down zygote/system_server and SurfaceFlinger but leaves
#     the HWC composer; stopping the composer AFTER SF has exited frees /dev/dri/
#     card0 without the 4.19 drm_fb_release flush_work wedge. With no system_server
#     there is no DisplayManager re-asserting the mode and no PowerManager to
#     suspend-freeze a clone.
#   * The freed card0 + /dev/mali0 drive dArkOS's libmali (bifrost-g31 r13p0, the
#     exact pairing for our kbase UK 11.29) via SDL2 KMSDRM. Confirmed on hardware.
#   * Extraction/mounting must leave the ELF loader (ld-linux-aarch64.so.1)
#     executable or the kernel cannot exec any dynamic binary (EACCES on PT_INTERP).
#
# Layering of the "return" path:
#   a port exits  -> back to EmulationStation (its own quit / gptokeyb chord)
#   ES "Quit"     -> back to Android (ES is our main process; its exit restores)
#   force chord / guardian -> back to Android even if ES hangs.
#
# Usage (root):
#   andr36oid-subsystem.sh start           # mount (if needed), handoff, run ES
#   andr36oid-subsystem.sh run <port.sh>   # handoff, run a single port, restore
#   andr36oid-subsystem.sh stop            # force teardown + restore Android

set -u
umask 022

# --- configuration -----------------------------------------------------------
STATE=${A36_STATE:-/data/local/tmp/portmaster}
ROOT=${A36_ROOT:-/data/local/tmp/darkfull}   # dArkOS rootfs mountpoint (or dir)
IMAGE=${A36_IMAGE:-}                          # optional rootfs/disk image
HANDOFF=${A36_HANDOFF:-1}
KEYHELD=${A36_KEYHELD:-/system/bin/andr36oid-keyheld}
CHROOT=${A36_CHROOT:-/system/bin/chroot}
HWC=${A36_HWC:-vendor.hwcomposer-2-1}
GUARD_GRACE=${A36_GUARD_GRACE:-20}
# How a full "start" brings up the dArkOS frontend: systemd (B1, boot dArkOS's own
# init in a PID namespace - full session state), wrapper (run ES's wrapper directly,
# the proven 60fps path), or bare (the raw ES binary, legacy).
# Default to wrapper: the systemd path is not ready yet, and when it fails to boot it
# leaves DRM master/mode held by the killed /sbin/init, so the wrapper fallback (and
# even the Android restore afterwards) then floods "Could not queue pageflip: -22" and
# the display wedges. The wrapper path takes clean DRM and is the verified 60fps bring-up.
# Set A36_SESSION_MODE=systemd to work on the B1 container path.
SESSION_MODE=${A36_SESSION_MODE:-wrapper}
FB=${A36_FB:-/dev/graphics/fb0}               # fbdev node for the handoff splash
SPLASH=${A36_SPLASH:-/system/etc/andr36oid-splash.raw}
[ -f "$SPLASH" ] || SPLASH=/data/local/tmp/andr36oid-splash.raw   # test fallback
# where the user drops the image, searched in order (first existing wins)
IMAGE_SEARCH="/storage/*/subsystem/darkosen/*.img /mnt/media_rw/*/subsystem/darkosen/*.img /data/subsystem/darkosen/*.img"

mkdir -p "$STATE"
LOG="$STATE/subsystem.log"
log() {
  local line
  line="$(cut -d. -f1 /proc/uptime)s $*"
  printf '%s\n' "$line" >> "$LOG"
  [ "${LIVE_LOG:-0}" = 1 ] && printf '%s\n' "$line"
  return 0
}
[ "$(id -u)" = 0 ] || { echo "subsystem: must run as root" >&2; exit 1; }

# --- rootfs: supported filesystem image or MBR/GPT disk + writable overlay ----
# The lower stays read-only; each image has its own upper/work on /data. Never
# reuse one image's overlay for another. Keep the extracted-directory fallback
# for developer use, but an explicit IMAGE always takes precedence over it.
LOWER="$STATE/lower"; UPPER="$STATE/upper"; WORK="$STATE/work"; LOOPDEV=""
BOUND=""; DID_HANDOFF=0; SESSION_OWNED=0
track_mount() {
  BOUND="$1
$BOUND"
  printf '%s\n' "$BOUND" > "$STATE/session.mounts"
}
forget_mount() {
  BOUND=$(printf '%s\n' "$BOUND" | awk -v p="$1" '$0!=p')
  printf '%s\n' "$BOUND" > "$STATE/session.mounts"
}
bind_mount() { mount -o bind "$1" "$2" && track_mount "$2"; }
detect_image() {
  [ -n "$IMAGE" ] && return 0
  local g
  for g in $IMAGE_SEARCH; do [ -f "$g" ] && { IMAGE="$g"; return 0; }; done
  return 1
}
# Resolve symlinks as the chroot would, including absolute /sbin/init links and
# usr-merged directories. Host-side test -f alone follows absolute links outside
# the image and can incorrectly accept a dangling init or miss a valid target.
root_path() {
  local base="$1" todo="${2#/}" resolved="" component link hops=0
  while [ -n "$todo" ]; do
    component=${todo%%/*}
    if [ "$todo" = "$component" ]; then todo=""; else todo=${todo#*/}; fi
    case "$component" in ''|.) continue ;; ..) resolved=${resolved%/*}; continue ;; esac
    if [ -L "$base$resolved/$component" ]; then
      hops=$((hops+1)); [ "$hops" -le 40 ] || return 1
      link=$(readlink "$base$resolved/$component") || return 1
      case "$link" in /*) resolved="" ;; esac
      todo="${link#/}${todo:+/$todo}"
    else
      resolved="$resolved/$component"
    fi
  done
  printf '%s\n' "$base$resolved"
}
root_file() {
  local path
  path=$(root_path "$1" "$2") || return 1
  [ -f "$path" ]
}
has_init() {
  local p
  for p in /sbin/init /init /bin/init /usr/lib/systemd/systemd /lib/systemd/systemd; do
    if root_file "$1" "$p"; then INIT="$p"; return 0; fi
  done
  return 1
}
find_es() {
  local p
  for p in /usr/bin/emulationstation/emulationstation.sh /usr/bin/emulationstation/emulationstation /usr/bin/emulationstation /usr/bin/EmulationStation; do
    if root_file "$1" "$p"; then ES="$p"; return 0; fi
  done
  return 1
}
eligible_rootfs() {
  local p
  has_init "$1" && return 0
  find_es "$1" && return 0
  for p in /roms/tools/PortMaster.sh /roms/tools/PortMaster/PortMaster.sh /usr/bin/portmaster "$arg" "$arg.sh"; do
    [ -n "$p" ] && root_file "$1" "$p" && return 0
  done
  # A chosen port can live on the Android games card, bound later at /roms.
  if [ "$cmd" = run ] && [ -e "$1/bin/sh" ]; then
    for p in /mnt/media_rw/*; do
      case "$arg" in /roms/*)
        [ -f "$p/${arg#/roms/}" ] || [ -f "$p/${arg#/roms/}.sh" ] || continue
        return 0 ;;
      esac
    done
  fi
  return 1
}
mount_rootfs() {
  if [ -z "$IMAGE" ] && eligible_rootfs "$ROOT"; then
    setprop sys.andr36oid.subsystem.boot_pending "" 2>/dev/null
    log "rootfs present at $ROOT"; return 0
  fi
  # vold may expose EASYROMS after boot_completed. The key was sampled already;
  # wait here, without resampling it or changing the boot decision.
  local tries=0 offsets off fs identity
  while ! detect_image; do
    [ "$(getprop sys.andr36oid.subsystem.boot_pending)" = 1 ] && [ "$tries" -lt 60 ] || break
    sleep 1; tries=$((tries+1))
  done
  setprop sys.andr36oid.subsystem.boot_pending "" 2>/dev/null
  [ -f "$IMAGE" ] || { log "no rootfs image found (default search: $IMAGE_SEARCH)"; return 1; }
  log "using image $IMAGE"
  # Key on stable file identity (inode/size/mtime), NOT the mount's device number:
  # the games card and the overlay get a fresh st_dev on every boot/remount, so a %d
  # in the key changed every launch and re-ran the whole (~8 min) dArkOSen setup.
  identity=$(printf '%s\n%s\n' "$IMAGE" "$(stat -c '%i:%s:%Y' "$IMAGE")" | cksum | awk '{print $1}')
  IMAGE_ID="$identity"
  UPPER="$STATE/images/$identity/upper"; WORK="$STATE/images/$identity/work"
  mkdir -p "$LOWER" "$UPPER" "$WORK" "$ROOT"
  offsets="0 $(partition_offsets "$IMAGE")"
  for off in $offsets; do
    fs=$(image_fstype "$IMAGE" "$off")
    [ -n "$fs" ] || continue
    LOOPDEV=$(losetup -f) || return 1
    printf '%s\n' "$LOOPDEV" > "$STATE/session.loop"
    # No -S: this device's toybox truncates large sizelimits to 32 bits.
    if losetup -r -o "$off" "$LOOPDEV" "$IMAGE"; then
      if mount -t "$fs" -o ro "$LOOPDEV" "$LOWER"; then
        track_mount "$LOWER"
        if eligible_rootfs "$LOWER"; then
          mount -t overlay overlay -o "lowerdir=$LOWER,upperdir=$UPPER,workdir=$WORK" "$ROOT" || return 1
          track_mount "$ROOT"; MOUNTED=1
          log "mounted eligible $fs rootfs at byte $off (ro + overlay) at $ROOT"
          return 0
        fi
        umount "$LOWER" || return 1
        forget_mount "$LOWER"
      fi
    fi
    losetup -d "$LOOPDEV" 2>/dev/null; LOOPDEV=""
    rm -f "$STATE/session.loop"
  done
  log "image has no mountable btrfs/ext4/squashfs/erofs rootfs with init or a launch target"
  return 1
}
# Partition fields are little-endian (this console is aarch64 LE).
_le32() { dd if="$1" bs=1 skip="$2" count=4 2>/dev/null | od -An -tu4 | tr -d ' '; }
_type8() { dd if="$1" bs=1 skip="$2" count=1 2>/dev/null | od -An -tu1 | tr -d ' '; }

# Probe magic directly: Android's toybox blkid does not recognize erofs. Offsets
# are byte offsets; raw filesystems and all eligible MBR/GPT partitions are tried.
_hex() { dd if="$1" bs=1 skip="$2" count="$3" 2>/dev/null | od -An -tx1 | tr -d ' \n'; }
_le64() { dd if="$1" bs=1 skip="$2" count=8 2>/dev/null | od -An -tu8 | tr -d ' \n'; }
image_fstype() {
  local image="$1" off="$2"
  case "$(_hex "$image" "$off" 4)" in 68737173) echo squashfs; return ;; esac
  case "$(_hex "$image" $((off+1024)) 4)" in e2e1f5e0) echo erofs; return ;; esac
  case "$(_hex "$image" $((off+1080)) 2)" in 53ef) echo ext4; return ;; esac
  case "$(_hex "$image" $((off+65600)) 8)" in 5f42485266535f4d) echo btrfs; return ;; esac
}
partition_offsets() {
  [ "$(_hex "$1" 510 2)" = 55aa ] || return 0
  local i base t start table count entsize
  if [ "$(_hex "$1" 512 8)" = 4546492050415254 ]; then
    table=$(_le64 "$1" 584); count=$(_le32 "$1" 592); entsize=$(_le32 "$1" 596)
    # Bound all reads, including on corrupt/user-supplied partition tables.
    [ "${table:-0}" -gt 1 ] && [ "${count:-0}" -gt 0 ] && [ "$count" -le 4096 ] &&
      [ "${entsize:-0}" -ge 128 ] && [ "$entsize" -le 4096 ] || return 0
    i=0
    while [ "$i" -lt "$count" ]; do
      base=$((table*512+i*entsize)); start=$(_le64 "$1" $((base+32)))
      [ "${start:-0}" -gt 0 ] && echo $((start*512))
      i=$((i+1))
    done
  else
    for i in 0 1 2 3; do
      base=$((446+i*16)); t=$(_type8 "$1" $((base+4)))
      [ "$t" = 131 ] || continue
      start=$(_le32 "$1" $((base+8)))
      [ "${start:-0}" -gt 0 ] && echo $((start*512))
    done
  fi
}

# --- exec-bit repair (only for a legacy extracted dir) --------------------------
# A real btrfs mount already has correct exec bits; chmod-ing over the overlay
# would copy EVERY binary up into the upper (slow, fills /data). So skip entirely
# when we mounted an image; only repair when running against an extracted dir.
fix_exec_bits() {
  [ "${MOUNTED:-0}" = 1 ] && { log "mounted image: exec bits are native, skipping repair"; return 0; }
  chmod 0755 "$ROOT"/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 2>/dev/null
  [ -e "$ROOT/usr/lib/ld-linux-aarch64.so.1" ] || \
    ln -sf aarch64-linux-gnu/ld-linux-aarch64.so.1 "$ROOT/usr/lib/ld-linux-aarch64.so.1" 2>/dev/null
  for d in bin usr/bin sbin usr/sbin usr/local/bin opt; do
    [ -d "$ROOT/$d" ] && find "$ROOT/$d" -type f -exec chmod 0755 {} + 2>/dev/null
  done
}

# --- storage: expose EASYROMS / SD2 / USB into the chroot, exec + permissive -----
# exFAT cannot store per-file x bits, so we drop noexec and use mount-wide masks;
# this covers hotplugged volumes via the same path (see hotplug watcher, TODO).
mount_storage() {
  mkdir -p "$ROOT/roms"
  local n=0 src dst id ready easy
  ready=$(getprop sys.andr36oid.easyroms.ready)
  id=$(getprop sys.andr36oid.easyroms.id)
  if [ -z "$id" ] && [ "$ready" != 1 ] && [ -e /sys/block/mmcblk0/mmcblk0p7 ]; then
    id=public:179,7
  fi
  if [ -n "$id" ]; then
    easy=$(awk -v dev="/dev/block/vold/$id" '$1==dev && $2 ~ "^/mnt/media_rw/" {print $2; exit}' /proc/mounts)
    [ -n "$easy" ] && [ -d "$easy" ] || { log "EASYROMS exists but is not mounted; try again when it is ready"; return 1; }
    src="$easy"
  else
    src=/data/media/0
    [ -d "$src" ] || { log "internal games storage is unavailable"; return 1; }
  fi
  bind_mount "$src" "$ROOT/roms" || return 1
  mount -o remount,exec,bind "$ROOT/roms" 2>/dev/null
  log "storage: $src -> /roms"
  ROMS_SOURCE="$src"
  n=1
  for src in /mnt/media_rw/*; do
    [ -d "$src" ] || continue
    [ "$src" = "$ROMS_SOURCE" ] && continue
    dst="$ROOT/roms$((n+1))"
    mkdir -p "$dst"
    # bind the vold mount, then drop noexec for this view
    if bind_mount "$src" "$dst"; then
      mount -o remount,exec,bind "$dst" 2>/dev/null
      log "storage: $src -> ${dst#"$ROOT"}"; n=$((n+1))
    fi
  done
  return 0
}

# The stamp is on the games volume, keyed by the rootfs/overlay identity. An
# interrupted or failed merge leaves no stamp; the next launch retries safely.
# Stage on the games filesystem as media_rw. Only completed files are renamed
# into place; a retry discards an incomplete staging tree before starting again.
provision_darkos() {
  [ -f "$ROOT/roms.tar" ] || return 0
  local key stamp
  # Daijishou's PICO-8 platform scans pico-8 recursively. ES otherwise starts
  # at carts/, missing games placed directly in the shared platform folder.
  if [ -f "$ROOT/etc/emulationstation/es_systems.cfg" ] &&
      grep -q '<path>/roms/pico-8/carts/</path>' "$ROOT/etc/emulationstation/es_systems.cfg"; then
    cp -p "$ROOT/etc/emulationstation/es_systems.cfg" "$ROOT/etc/emulationstation/es_systems.cfg.a36" || return 1
    sed 's|<path>/roms/pico-8/carts/</path>|<path>/roms/pico-8/</path>|' \
      "$ROOT/etc/emulationstation/es_systems.cfg" > "$ROOT/etc/emulationstation/es_systems.cfg.a36" || return 1
    mv "$ROOT/etc/emulationstation/es_systems.cfg.a36" "$ROOT/etc/emulationstation/es_systems.cfg" || return 1
  fi
  # The "already merged" stamp must track the dArkOSen RELEASE, not the image file.
  # IMAGE_ID is cksum(stat of the .img), but the .img lives on a FAT card whose
  # synthesised inode changes on every remount -- so the key, and the whole ~8 min
  # merge, re-ran every boot. It is also wrong across an "optimise to EROFS" pass:
  # that rewrites the .img bytes while leaving roms.tar byte-identical, which must NOT
  # re-trigger the merge. roms.tar is the release payload -- a new dArkOSen release
  # always ships a new one -- so key on its size plus a hash of its head: stable across
  # reboots and an EROFS conversion, changes on a release upgrade, and cheap (reads
  # 64 KiB, not the whole ~300 MB tar).
  key=$( { stat -c %s "$ROOT/roms.tar"; dd if="$ROOT/roms.tar" bs=64k count=1 2>/dev/null; } | cksum | awk '{print $1}')
  stamp="$ROOT/roms/.andr36oid-darkosen-$key.done"
  [ -f "$stamp" ] && return 0
  # Check the actual bind, not just the existence or permissions of its source.
  # The write probe also catches a read-only underlying filesystem and vold races.
  if ! awk -v p="$ROOT/roms" '
    {gsub(/\\040/, " ", $2); gsub(/\\011/, "\t", $2)}
    $2==p {n=split($4, opts, ","); for (i=1; i<=n; i++) if (opts[i]=="rw") rw=1}
    END {exit !rw}' /proc/mounts ||
    ! "$CHROOT" "$ROOT" /usr/bin/setpriv --reuid=1023 --regid=1023 --clear-groups /bin/sh -c '
      PATH=/usr/bin:/bin; export PATH
      umask 002
      probe=/roms/.andr36oid-write-probe-$$
      mkdir "$probe" || exit 1
      trap '\''rm -f "$probe/file"; rmdir "$probe"'\'' EXIT HUP INT TERM
      printf test > "$probe/file" || exit 1
      rm "$probe/file" && rmdir "$probe" || exit 1
      trap - EXIT HUP INT TERM
    '; then
    log "games storage $ROMS_SOURCE is not mounted rw or writable by media_rw; try again when it is ready"
    return 1
  fi
  setprop sys.andr36oid.subsystem.provisioning 1 2>/dev/null
  # Show the setup progress even when the launch did not come from the subsystem
  # settings screen (a bare launch, or a boot straight into the subsystem): bring
  # the progress UI to the front so a new user is not left on a blank screen.
  # Best effort - provisioning still runs without it.
  /system/bin/am start -n org.andr36oid.subsystemsetup/.SubsystemSetupActivity \
    --activity-single-top >/dev/null 2>&1 || true
  log "Setting up dArkOSen... merging /roms.tar into $ROMS_SOURCE"
  "$CHROOT" "$ROOT" /usr/bin/setpriv --reuid=1023 --regid=1023 --clear-groups /bin/sh -c '
    PATH=/usr/bin:/bin; export PATH
    umask 002
    stage=/roms/.andr36oid-darkosen-stage-$1
    rm -rf -- "$stage" || exit 1
    mkdir -- "$stage" || exit 1
    /usr/bin/tar --no-same-owner --no-same-permissions --no-overwrite-dir \
      --warning=no-timestamp -xvf /roms.tar -C "$stage" || exit 1
    [ -d "$stage/roms" ] || exit 1
    if [ -d /tempthemes ]; then
      echo "Installing default themes..."
      rm -rf "$stage/roms/themes" || exit 1
      # The sdcardfs games volume rejects any chmod whose mode is not its synthetic
      # 0775 (so cp -R, and tar restoring a "." root to its stored 07xx mode, fail
      # with EPERM). Archive with a NAMED top dir (themes), never ".", so tar
      # creates it fresh (mkdir 0775, accepted) and chmods every dir to the umask
      # 0775 - a no-op match - exactly like the roms.tar entries that already work.
      /usr/bin/tar -C / --transform 's,^tempthemes,themes,' \
        -cf "$stage/.themes.tar" tempthemes || exit 1
      /usr/bin/tar --no-same-owner --no-same-permissions \
        --warning=no-timestamp -xf "$stage/.themes.tar" -C "$stage/roms" || exit 1
      rm -f "$stage/.themes.tar" || exit 1
    fi
    # Create directories first, then rename each completed file without replacing
    # an existing entry. --no-copy forbids a non-atomic cross-mount fallback.
    find "$stage/roms" -type d -exec /bin/sh -c '\''
      stage=$1; shift
      for src do
        [ "$src" = "$stage/roms" ] && continue
        mkdir -p -- "/roms/${src#"$stage/roms"/}" || exit 1
      done
    '\'' sh "$stage" {} + || exit 1
    find "$stage/roms" ! -type d ! -type f -print | grep . && exit 1
    find "$stage/roms" -type f -exec /bin/sh -c '\''
      stage=$1; shift
      for src do
        dst=/roms/${src#"$stage/roms"/}
        /usr/bin/mv -n -T --no-copy -- "$src" "$dst" || exit 1
      done
    '\'' sh "$stage" {} + || exit 1
    rm -rf -- "$stage" || exit 1
  ' sh "$key" || { log "dArkOSen setup failed; it will be retried on the next launch"; return 1; }
  sync || { log "could not sync dArkOSen setup"; return 1; }
  (umask 002; : > "$stamp") || { log "cannot record dArkOSen setup completion"; return 1; }
  sync || { rm -f "$stamp"; log "could not sync dArkOSen setup completion"; return 1; }
  log "dArkOSen setup complete"
  setprop sys.andr36oid.subsystem.provisioning "" 2>/dev/null
}

# Input mapping: andr36oid ships a known-good es_input.cfg for the console's gamepad
# (the fixed odroidgo3-joypad, deviceName "GO-Super Gamepad"), so ES must NEVER run
# its Configure-Input wizard. dArkOS ES reads /etc/emulationstation/es_input.cfg.
# Refresh the built-in pad in the overlay at each session start, retaining maps
# for external controllers.
ESINPUT_SRC=${A36_ESINPUT:-/system/etc/andr36oid-es_input.cfg}
[ -f "$ESINPUT_SRC" ] || ESINPUT_SRC=/data/local/tmp/es_input.cfg   # test fallback
# RetroArch is launched bare (no input append), so it needs a udev autoconfig
# matching the pad name "GO-Super Gamepad" or it falls back to retroarch.cfg's
# generic binds (wrong D-pad/Start/Select for our odroidgo3-joypad). The file is
# the es_input.cfg layout expressed as RetroArch udev binds.
RAAUTOCFG_SRC=${A36_RA_AUTOCFG:-/system/etc/andr36oid-retroarch-GO-Super-Gamepad.cfg}
[ -f "$RAAUTOCFG_SRC" ] || RAAUTOCFG_SRC=/data/local/tmp/GO-Super_Gamepad.cfg   # test fallback
# SDL GameController mapping for the console pad, used by gptokeyb in PortMaster
# ports for the Select+Start quit chord. The pad still plays because gptokeyb reads
# raw joystick buttons, but the quit chord uses SDL's semantic back/start. PortMaster
# already ships a "GO-Super Gamepad" entry, but its button numbers are the native
# ArkOS order (back:b12, dpup:b8, dpdown:b9, ...), which does NOT match how the
# andr36oid kernel enumerates the odroidgo3-joypad (es_input.cfg: start=b8, select=b9,
# up=b10, left=b11, right=b12, down=b13). So the stock entry sends the wrong buttons
# and the chord never fires. We override it with our own binds. PortMaster selects the
# controller by the GUID it computes for the pad; depending on the SDL build that GUID
# carries a name CRC (1900bb3e...) or not (190000004b48...), so we ship both. Binds
# below are es_input.cfg expressed as an SDL mapping.
SDL_PAD_BINDS="GO-Super Gamepad,a:b0,b:b1,x:b2,y:b3,back:b9,start:b8,guide:b16,dpup:b10,dpdown:b13,dpleft:b11,dpright:b12,leftshoulder:b4,rightshoulder:b5,lefttrigger:b6,righttrigger:b7,leftstick:b15,rightstick:b14,leftx:a0,lefty:a1,rightx:a2,righty:a3,platform:Linux,"
# no-CRC GUID (PortMaster's DEVICE for the odroidgo3-joypad) first, CRC GUID second.
SDL_PAD_GUIDS="190000004b4800000011000000010000 1900bb3e4b4800000011000000010000"
# env-var form: both lines, newline-separated, for SDL_GAMECONTROLLERCONFIG.
SDL_PAD_MAP=""
for _g in $SDL_PAD_GUIDS; do SDL_PAD_MAP="${SDL_PAD_MAP:+$SDL_PAD_MAP
}$_g,$SDL_PAD_BINDS"; done
# Write through the merged root only. Resolve absolute image symlinks in the
# chroot namespace, so a database linked to /roms never touches the host path.
finish_input_file() {
  local f="$1" t="$2"
  if cmp -s "$f" "$t"; then rm -f "$t"
  else mv "$t" "$f" && log "updated console input in ${f#$ROOT}"; fi
}
# PortMaster, standalone SDL applications and the two SDL3 autoconfig databases
# all receive the same physical mapping, including both SDL GUID variants.
install_sdl_mapping() {
  local path f udir
  udir=$(root_path "$ROOT" /home/ark/.config/SDL-GameControllerDB) || return 1
  mkdir -p "$udir" || return 1
  f=$(root_path "$ROOT" /home/ark/.config/SDL-GameControllerDB/gamecontrollerdb.txt) || return 1
  [ -f "$f" ] || : > "$f" || return 1
  {
    printf '%s\n' "$f"
    # Explicit paths also cover databases beneath image-absolute directory links.
    for path in /opt/ppsspp/assets/gamecontrollerdb.txt /opt/ppsspp-2021/assets/gamecontrollerdb.txt \
                /home/ark/.config/duckstation/database/gamecontrollerdb.txt /opt/duckstation/resources/gamecontrollerdb.txt \
                /opt/hypseus-singe/gamecontrollerdb.txt /opt/scummvm/extra/gamecontrollerdb.txt \
                /opt/mvem/controls/gamecontrollerdb.txt /opt/inttools/gamecontrollerdb.txt \
                /opt/linapple/gamecontrollerdb.txt /opt/openmsx/gamecontrollerdb.txt; do
      printf '%s\n' "$ROOT$path"
    done
    # Keep discovery shallow: launch-time searches must avoid the games volume.
    find "$ROOT/opt" -maxdepth 3 -name gamecontrollerdb.txt 2>/dev/null
    find "$ROOT/home/ark/.config" -maxdepth 3 -name gamecontrollerdb.txt 2>/dev/null
  } | sort -u | while IFS= read -r f; do
    f=$(root_path "$ROOT" "${f#"$ROOT"}") || continue
    [ -f "$f" ] || continue
    rewrite_sdl_pad "$f" db || return 1
  done || return 1
  install_sdl3_mapping
}
rewrite_sdl_pad() {
  local f="$1" mode="$2" t="$1.a36tmp"
  cp -p "$f" "$t" || return 1
  if ! awk -v mode="$mode" -v binds="$SDL_PAD_BINDS" -v guids="$SDL_PAD_GUIDS" '
    BEGIN { split(guids, g, " "); quote = sprintf("%c", 39) }
    mode == "db" {
      if (index($0, g[1] ",") != 1 && index($0, g[2] ",") != 1) print
      next
    }
    mode == "launcher" {
      line = $0
      # The Android input device has the same name, but need not have the
      # native Linux by-path symlink. Change only this pad selection test.
      sub(/-e ["\047]?\/dev\/input\/by-path\/platform-odroidgo3-joypad-event-joystick["\047]?/,
          "-n \"$(grep -l -x " quote "GO-Super Gamepad" quote " /sys/class/input/event*/device/name 2>/dev/null)\"", line)
      at = index(line, g[1] ",GO-Super Gamepad,")
      if (!at) at = index(line, g[2] ",GO-Super Gamepad,")
      if (!at) { print line; next }
      before = substr(line, 1, at - 1); q = substr(before, length(before), 1)
      if (q != "\"" && q != quote) { print line; next }
      rest = substr(line, at); end = index(rest, q)
      # Also repair pairs written by an earlier broker, even if stale.
      while (!end && (getline rest) > 0) end = index(rest, q)
      if (!end) exit 1
      print before g[1] "," binds
      print g[2] "," binds substr(rest, end)
    }
    END {
      if (mode == "db") { print g[1] "," binds; print g[2] "," binds }
    }
  ' "$f" > "$t"; then
    rm -f "$t"; return 1
  fi
  finish_input_file "$f" "$t"
}
install_sdl3_mapping() {
  local path f
  for path in /home/ark/.config/retroarch/autoconfig/sdl3/gamecontrollerdb.cfg \
              /home/ark/.config/retroarch32/autoconfig/sdl3/gamecontrollerdb.cfg; do
    f=$(root_path "$ROOT" "$path") || continue
    [ -f "$f" ] || continue
    rewrite_sdl_pad "$f" db || return 1
  done
}
install_launcher_sdl_mapping() {
  local f
  for f in "$ROOT/usr/local/bin/ecwolf.sh" "$ROOT/usr/local/bin/solarus.sh" \
           "$ROOT/usr/local/bin/piemu_run.sh" "$ROOT/usr/local/bin/dreamcast.sh" \
           "$ROOT/usr/local/bin/atomiswave.sh" "$ROOT/usr/local/bin/naomi.sh" \
           "$ROOT/usr/local/bin/pico8.sh"; do
    f=$(root_path "$ROOT" "${f#"$ROOT"}") || return 1
    [ -f "$f" ] || continue
    rewrite_sdl_pad "$f" launcher || return 1
    case "$f" in */pico8.sh) rewrite_pico_seed "$f" || return 1 ;; esac
  done
}
# PICO-8 creates its private DB on first launch from a multiline echo. Its bare
# database rows are not SDL_GAMECONTROLLERCONFIG assignments. Keep the shell
# quote/redirect and other pads; replace all GO rows in that seed with one pair.
rewrite_pico_seed() {
  local cfg="$1" t="$1.a36tmp"
  cp -p "$cfg" "$t" || return 1
  if ! awk -v binds="$SDL_PAD_BINDS" -v guids="$SDL_PAD_GUIDS" '
    BEGIN { split(guids, g, " ") }
    function flush(rewrite, i, line, emitted) {
      for (i=1; i<=count; i++) {
        line=lines[i]
        if (rewrite && (index(line, g[1] ",GO-Super Gamepad,") == 1 ||
                        index(line, g[2] ",GO-Super Gamepad,") == 1)) {
          if (!emitted) {
            print g[1] "," binds
            print g[2] "," binds
            emitted=1
          }
          # A final GO row may also carry the echo closing quote/redirect.
          if (match(line, /["\047][[:space:]]*>/)) print substr(line, RSTART)
        } else print line
        delete lines[i]
      }
      count=0
    }
    {
      # An echo starting with GO is already owned by rewrite_sdl_pad.
      if (!seed && /^[[:space:]]*echo[[:space:]]+["\047][0-9a-f]+,/ &&
          !index($0, ",GO-Super Gamepad,")) seed=1
      if (seed) {
        lines[++count]=$0
        # Buffer until the redirect proves this is the private DB seed.
        if (/["\047][[:space:]]*>/) {
          flush($0 ~ />[[:space:]]*.*sdl_controllers[.]txt/)
          seed=0
        }
        next
      }
      print
    }
    END { flush(0) }
  ' "$cfg" > "$t"; then rm -f "$t"; return 1; fi
  finish_input_file "$cfg" "$t"
}
# Refresh only the built-in pad, including stale upper-layer maps. ES and
# PortMaster mapper.py both use the shipped face-button convention. Keep every
# external controller and keyboard block, and handle old and name-CRC SDL GUIDs.
install_es_input() {
  local path f t
  [ -f "$ESINPUT_SRC" ] || { log "no shipped es_input.cfg at $ESINPUT_SRC"; return 1; }
  for path in /etc/emulationstation/es_input.cfg /home/ark/.config/emulationstation/es_input.cfg; do
    f=$(root_path "$ROOT" "$path") || return 1
    mkdir -p "$(dirname "$f")" || return 1
    [ -f "$f" ] || cp "$ESINPUT_SRC" "$f" || return 1
    t="$f.a36tmp"
    cp -p "$f" "$t" || return 1
    if ! awk -v guids="$SDL_PAD_GUIDS" '
      BEGIN { split(guids, g, " ") }
      FNR == NR {
        if ($0 ~ /<inputConfig[[:space:]]/ && /deviceName="GO-Super Gamepad"/) capture=1
        if (capture) {
          if (/<\/inputConfig>/) {
            if (block !~ /name="system_hk"/)
              block=block "\t\t<input name=\"system_hk\" type=\"button\" id=\"16\" value=\"1\" />\n"
            capture=0
          }
          block=block $0 "\n"
        }
        next
      }
      /<inputConfig[[:space:]]/ {
        skip=(/deviceName="GO-Super Gamepad"/ || index($0, g[1]) || index($0, g[2]))
      }
      /<\/inputList>/ {
        if (!block || capture) exit 1
        for (i=1; i<=2; i++) {
          pad=block
          sub(/deviceGUID="[^"]*"/, "deviceGUID=\"" g[i] "\"", pad)
          printf "%s", pad
        }
        inserted=1
      }
      { if (!skip) print }
      /<\/inputConfig>/ { skip=0 }
      END { if (!inserted) exit 1 }
    ' "$ESINPUT_SRC" "$f" > "$t"; then
      rm -f "$t"; return 1
    fi
    finish_input_file "$f" "$t" || return 1
  done
}
# Install the same udev profile under the other names shipped by dArkOSen.
# Keep a profile already changed in the overlay, as with the original install.
install_ra_profiles() {
  local path
  [ -f "$RAAUTOCFG_SRC" ] || return
  for path in home/ark/.config/retroarch/autoconfig/udev/go_super_gamepad.cfg \
              home/ark/.config/retroarch32/autoconfig/udev/GO-Super\ Gamepad.cfg \
              home/ark/.config/retroarch32/autoconfig/udev/go_super_gamepad.cfg; do
    [ -f "$ROOT/$path" ] || continue
    [ -f "$UPPER/$path" ] && continue
    cp "$RAAUTOCFG_SRC" "$ROOT/$path" && log "installed shipped retroarch autoconfig in /$path"
  done
}
# Only these are full configs. Rotated/spectator append files have independent
# axes/defaults and must never receive full-config upserts.
RA_FULL_CONFIGS="/home/ark/.config/retroarch/retroarch.cfg
/home/ark/.config/retroarch/retroarch.cfg.bak
/home/ark/.config/retroarch32/retroarch.cfg
/home/ark/.config/retroarch32/retroarch.cfg.bak"
# Fix native udev defaults by name; retain X/Y, shoulders, keys and axes.
set_ra_input_binds() {
  local cfg t
  for cfg in $RA_FULL_CONFIGS; do
    cfg=$(root_path "$ROOT" "$cfg") || return 1
    [ -f "$cfg" ] || continue
    t="$cfg.a36tmp"
    cp -p "$cfg" "$t" || return 1
    if ! awk '
      BEGIN {
        # RA udev joypad indices for the GO-Super pad: SELECT=8, START=9 (evdev-code
        # order), NOT SDL/ES order. The D-pad is keyboard keys the udev joypad driver
        # does not expose as buttons, and there is no L3/R3 -- leaving those bound to
        # non-existent indices made RetroArch mishandle input, so clear them. Quit stays
        # on the Start+Select combo (input_quit_gamepad_combo), not a lone exit button.
        set["input_player1_select_btn"]="8"; set["input_player1_start_btn"]="9"
        set["input_player1_a_btn"]="0";      set["input_player1_b_btn"]="1"
        set["input_enable_hotkey_btn"]="8";  set["input_exit_emulator_btn"]="nul"
        set["input_player1_up_btn"]="nul";   set["input_player1_down_btn"]="nul"
        set["input_player1_left_btn"]="nul"; set["input_player1_right_btn"]="nul"
        set["input_player1_l3_btn"]="nul";   set["input_player1_r3_btn"]="nul"
      }
      {
        key=$0; sub(/[[:space:]]*=.*$/, "", key); sub(/^[[:space:]]*/, "", key)
        if (key in set && index($0, "=")) {
          if (seen[key]++) next
          print key " = \"" set[key] "\""
        } else print
      }
      END { for (key in set) if (!seen[key]) print key " = \"" set[key] "\"" }
    ' "$cfg" > "$t"; then rm -f "$t"; return 1; fi
    finish_input_file "$cfg" "$t" || return 1
  done
}
# Mupen uses raw button(n) values, not SDL GameController button IDs. Preserve
# virtual N64 faces and stick/C-button axes, and repair doubled saved quotes.
rewrite_mupen_input() {
  local cfg="$1" section="$2" quoted="$3" t="$1.a36tmp"
  cp -p "$cfg" "$t" || return 1
  if ! awk -v section="$section" -v quoted="$quoted" '
    BEGIN {
      binds["Start"]=8; binds["DPad U"]=10; binds["DPad D"]=13
      binds["DPad L"]=11; binds["DPad R"]=12
    }
    function missing( key, value) {
      if (!active) return
      for (key in binds) if (!(key in seen)) {
        value="button(" binds[key] ")"
        if (quoted) value="\"" value "\""
        print key " = " value
      }
    }
    /^[[:space:]]*\[/ {
      missing()
      for (key in seen) delete seen[key]
      name=$0; sub(/^[[:space:]]*\[/, "", name); sub(/\].*$/, "", name)
      active=(name == section)
    }
    {
      if (active && index($0, "=")) {
        key=substr($0, 1, index($0, "=")-1)
        sub(/^[[:space:]]*/, "", key); sub(/[[:space:]]*$/, "", key)
        value=substr($0, index($0, "=")+1)
        sub(/^[[:space:]]*/, "", value); sub(/[[:space:]]*$/, "", value)
        if (key in binds) {
          if (key in seen) next
          seen[key]=1
          value="button(" binds[key] ")"
          if (quoted) value="\"" value "\""
          $0=key " = " value
        } else if (quoted && value ~ /^""[^"]+""$/) {
          sub(/^""/, "\"", value); sub(/""$/, "\"", value)
          $0=key " = " value
        }
      }
      print
    }
    END { missing() }
  ' "$cfg" > "$t"; then rm -f "$t"; return 1; fi
  finish_input_file "$cfg" "$t"
}
set_mupen_input_binds() {
  local cfg
  {
    printf '%s\n' "$ROOT/opt/mupen64plus/InputAutoCfg.ini" "$ROOT/opt/mupen64plus/Default-InputAutoCfg.ini"
    find "$ROOT/opt/mupen64plus" -name '*InputAutoCfg.ini' 2>/dev/null
  } | sort -u |
    while IFS= read -r cfg; do
      cfg=$(root_path "$ROOT" "${cfg#"$ROOT"}") || continue
      [ -f "$cfg" ] || continue
      rewrite_mupen_input "$cfg" 'GO-Super Gamepad' 0 || return 1
    done || return 1
  cfg=$(root_path "$ROOT" /home/ark/.config/mupen64plus/mupen64plus.cfg) || return 1
  [ -f "$cfg" ] || return 0
  rewrite_mupen_input "$cfg" 'Input-SDL-Control1' 1 || return 1
  # CoreEvents is global, so only migrate when the saved first player is GO.
  if awk '
    /^[[:space:]]*\[/ { active=($0 ~ /^[[:space:]]*\[Input-SDL-Control1\][[:space:]]*$/) }
    active && /^[[:space:]]*name[[:space:]]*=/ {
      value=$0; sub(/^[^=]*=[[:space:]]*/, "", value)
      gsub(/^"+|"+[[:space:]]*$/, "", value)
      go=(value == "GO-Super Gamepad")
    }
    END { exit !go }
  ' "$cfg"; then
    rewrite_mupen_core_events "$cfg" || return 1
  fi
  return 0
}
rewrite_mupen_core_events() {
  local cfg="$1" t="$1.a36tmp"
  cp -p "$cfg" "$t" || return 1
  if ! awk '
    BEGIN {
      binds["Joy Mapping Stop"]="J0B9/B8"
      binds["Joy Mapping Save State"]="J0B9/B7"
      binds["Joy Mapping Load State"]="J0B9/B6"
      binds["Joy Mapping Reset"]="J0B9/B2"
      binds["Joy Mapping Pause"]="J0B9/B1"
    }
    function missing( key) {
      if (active) for (key in binds) if (!seen[key]) print key " = \"" binds[key] "\""
    }
    /^[[:space:]]*\[/ {
      missing()
      active=($0 ~ /^[[:space:]]*\[CoreEvents\][[:space:]]*$/)
      if (active) found=1
    }
    {
      key=$0; sub(/[[:space:]]*=.*$/, "", key); sub(/^[[:space:]]*/, "", key)
      if (active && key in binds && index($0, "=")) {
        if (seen[key]++) next
        $0=key " = \"" binds[key] "\""
      }
      print
    }
    END { if (!found) { print "\n[CoreEvents]"; active=1 }; missing() }
  ' "$cfg" > "$t"; then rm -f "$t"; return 1; fi
  finish_input_file "$cfg" "$t"
}
# EASYROMS is bound and provisioned after prime_input. Refresh the existing card
# copies too: n64.sh can replace the auto profile from a card, and pico8.sh keeps
# sdl_controllers.txt once generated. Only known controller files are rewritten.
prime_storage_input() {
  local dir cfg
  for dir in "$ROOT/roms" "$ROOT"/roms[0-9]*; do
    [ -d "$dir" ] || continue
    # Known deeper platform DBs and the restore backup, without scanning games.
    for cfg in "$dir/tools/PortMaster/batocera/gamecontrollerdb.txt" \
               "$dir/tools/PortMaster/knulli/gamecontrollerdb.txt" \
               "$dir/tools/PortMaster/gamecontrollerdb.txt.bak"; do
      cfg=$(root_path "$ROOT" "${cfg#"$ROOT"}") || return 1
      [ ! -f "$cfg" ] || rewrite_sdl_pad "$cfg" db || return 1
    done
    set_thememaster_input_binds "$dir" || return 1
    # Keep this shallow: /roms is the games card (can be 170+ GB), and a full
    # recursive scan here stalls every launch by ~70s. The per-emulator config
    # overrides we care about (PortMaster, pico-8, mupen) live within three levels.
    find "$dir" -maxdepth 3 \( -name gamecontrollerdb.txt -o -name sdl_controllers.txt -o -name '*InputAutoCfg.ini' \) 2>/dev/null |
      while IFS= read -r cfg; do
        cfg=$(root_path "$ROOT" "${cfg#"$ROOT"}") || continue
        [ -f "$cfg" ] || continue
        case "$cfg" in
          */gamecontrollerdb.txt|*/pico-8/sdl_controllers.txt) rewrite_sdl_pad "$cfg" db || return 1 ;;
          *InputAutoCfg.ini) rewrite_mupen_input "$cfg" 'GO-Super Gamepad' 0 || return 1 ;;
        esac
      done || return 1
  done
  return 0
}
# ThemeMaster consumes evdev codes, not SDL buttons. Its seed cannot know the
# event number: leave inputstr empty until the launcher resolves the exact GO
# name at runtime. Provisioning happens after prime_input, hence the storage pass.
set_thememaster_input_binds() {
  local dir="$1" cfg t
  cfg=$(root_path "$ROOT" "${dir#"$ROOT"}/tools/ThemeMaster/paramcontrols.txt") || return 1
  if [ -f "$cfg" ]; then
    t="$cfg.a36tmp"
    cp -p "$cfg" "$t" || return 1
    if ! awk '
      BEGIN {
        binds["back_key"]=314; binds["start_key"]=315
        binds["up_key"]=103; binds["down_key"]=108; binds["left_key"]=105; binds["right_key"]=106
        binds["l1_key"]=310; binds["r1_key"]=311; binds["l2_key"]=312; binds["r2_key"]=313
        binds["inputstr"]=""
      }
      {
        key=$0; sub(/[[:space:]]*=.*$/, "", key); sub(/^[[:space:]]*/, "", key)
        if (key in binds && index($0, "=")) {
          if (seen[key]++) next
          $0=key " =" (binds[key] == "" ? "" : " " binds[key])
        }
        print
      }
      END {
        for (key in binds) if (!seen[key]) print key " =" (binds[key] == "" ? "" : " " binds[key])
      }
    ' "$cfg" > "$t"; then rm -f "$t"; return 1; fi
    finish_input_file "$cfg" "$t" || return 1
  fi
  cfg=$(root_path "$ROOT" "${dir#"$ROOT"}/tools/ThemeMaster.sh") || return 1
  [ ! -f "$cfg" ] || rewrite_thememaster_launcher "$cfg"
}
rewrite_thememaster_launcher() {
  local cfg="$1" t="$1.a36tmp" block="$1.a36block"
  cp -p "$cfg" "$t" || return 1
  {
    printf '%s\n' '# a36 ThemeMaster GO device begin' 'a36_go_event=$('
    native_go_event_lookup
    cat <<'THEME'
)
if [ -n "$a36_go_event" ] && [ -f ./paramcontrols.txt ]; then
  sed "s|^[[:space:]]*inputstr[[:space:]]*=.*$|inputstr = /dev/input/event$a36_go_event|" ./paramcontrols.txt > ./paramcontrols.txt.a36tmp &&
    mv ./paramcontrols.txt.a36tmp ./paramcontrols.txt
fi
# a36 ThemeMaster GO device end
THEME
  } > "$block" || { rm -f "$block" "$t"; return 1; }
  if ! awk '
    FILENAME == ARGV[1] { block=block $0 "\n"; next }
    $0 == "# a36 ThemeMaster GO device begin" { skip=1 }
    skip { if ($0 == "# a36 ThemeMaster GO device end") skip=0; next }
    { print }
    /^cd[[:space:]]/ && !inserted { printf "%s", block; inserted=1 }
  ' "$block" "$cfg" > "$t"; then rm -f "$block" "$t"; return 1; fi
  rm -f "$block"
  finish_input_file "$cfg" "$t"
}
# Flycast stores raw joystick IDs even when SDL supplies a default controller
# map (upstream core/sdl/sdl.cpp dispatches SDL_JOYBUTTON events). Preserve the
# Dreamcast face layout and analog fields; handle legacy and saved v3 formats.
set_flycast_input_binds() {
  local cfg t
  cfg=$(root_path "$ROOT" "/home/ark/.config/flycast/mappings/SDL_GO-Super Gamepad.cfg") || return 1
  [ -f "$cfg" ] || return 0
  t="$cfg.a36tmp"
  cp -p "$cfg" "$t" || return 1
  if ! awk '
    BEGIN {
      binds["btn_start"]=8; binds["btn_menu"]=9
      binds["btn_dpad1_up"]=10; binds["btn_dpad1_down"]=13
      binds["btn_dpad1_left"]=11; binds["btn_dpad1_right"]=12
    }
    /^[[:space:]]*\[/ {
      section=$0; sub(/^[[:space:]]*\[/, "", section); sub(/\].*$/, "", section)
    }
    {
      if (index($0, "=")) {
        key=substr($0, 1, index($0, "=")-1)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
        value=substr($0, index($0, "=")+1)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
        if ((section == "dreamcast" && key != "btn_menu" || section == "emulator" && key == "btn_menu") && key in binds)
          $0=key " = " binds[key]
        else if (section == "digital" && key ~ /^bind[0-9]+$/ && value ~ /^[0-9]+:/) {
          semantic=value; sub(/^[0-9]+:/, "", semantic)
          if (semantic in binds) $0=key " = " binds[semantic] ":" semantic
        }
      }
      print
    }
  ' "$cfg" > "$t"; then rm -f "$t"; return 1; fi
  finish_input_file "$cfg" "$t"
}
set_dingux_input_binds() {
  local cfg t
  for cfg in "$ROOT/opt/dingux/oshgamepad.cfg" "$ROOT/opt/dingux/oshgamepad.cfg.bak"; do
    cfg=$(root_path "$ROOT" "${cfg#"$ROOT"}") || return 1
    [ -f "$cfg" ] || continue
    t="$cfg.a36tmp"
    cp -p "$cfg" "$t" || return 1
    if ! awk '
      BEGIN {
        binds["input_select_btn"]=9; binds["input_start_btn"]=8
        binds["input_up_btn"]=10; binds["input_down_btn"]=13
        binds["input_left_btn"]=11; binds["input_right_btn"]=12
        binds["input_l3_btn"]=15; binds["input_r3_btn"]=14
        binds["input_player1_select_btn"]=9; binds["input_player1_start_btn"]=8
        binds["input_player1_up_btn"]=10; binds["input_player1_down_btn"]=13
        binds["input_player1_left_btn"]=11; binds["input_player1_right_btn"]=12
      }
      {
        key=$0; sub(/[[:space:]]*=.*$/, "", key); sub(/^[[:space:]]*/, "", key)
        if (key in binds && index($0, "=")) {
          if (seen[key]++) next
          $0=key " = \"" binds[key] "\""
        }
        # The shipped A/B values have unterminated quotes. Retain the chosen
        # confirm/cancel layout while emitting valid quoted numeric values.
        else if (key ~ /^input_(player1_)?[ab]_btn$/ && index($0, "=")) {
          value=substr($0, index($0, "=")+1); gsub(/["[:space:]]/, "", value)
          if (value ~ /^[0-9]+$/) $0=key " = \"" value "\""
        }
        print
      }
      END {
        for (key in binds) if (!seen[key] && key ~ /^input_player1_/)
          print key " = \"" binds[key] "\""
      }
    ' "$cfg" > "$t"; then rm -f "$t"; return 1; fi
    finish_input_file "$cfg" "$t" || return 1
  done
}
# DraStic TEXT profiles: values are 1024 + raw SDL button. Assign by semantic
# key and known GO profile, never permute the current numbers. Standard DraStic
# uses analog gameplay directions in bank A and digital/UI directions in B;
# advanced DraStic uses bank A for both. A non-button encoding stays intact.
# .cf2 is a DSC2 binary companion; its import/save precedence needs build-level
# validation. Foreign-device and .cfg.<device> files are restore-only seeds:
# normalize the resulting active GO text config after restoring, before launch.
set_drastic_input_binds() {
  local app cfg t
  for app in drastic advanceddrastic; do
    cfg=$(root_path "$ROOT" "/opt/$app/config/drastic.cfg") || return 1
    [ -f "$cfg" ] || continue
    # Refuse anything other than the text controls format, including DSC2.
    LC_ALL=C grep -q '^controls_[ab]\[CONTROL_INDEX_' "$cfg" || continue
    t="$cfg.a36tmp"
    cp -p "$cfg" "$t" || return 1
    if ! awk -v app="$app" '
      function bind(bank, action, value) { binds["controls_" bank "[CONTROL_INDEX_" action "]"]=value }
      BEGIN {
        bind("a", "START", 1032); bind("a", "SELECT", 1033)
        bind("a", "TOUCH_CURSOR_PRESS", 1038); bind("a", "MENU", 1039)
        bank=(app == "drastic" ? "b" : "a")
        bind(bank, "UP", 1034); bind(bank, "DOWN", 1037)
        bind(bank, "LEFT", 1035); bind(bank, "RIGHT", 1036)
        bind(bank, "UI_UP", 1034); bind(bank, "UI_DOWN", 1037)
        bind(bank, "UI_LEFT", 1035); bind(bank, "UI_RIGHT", 1036)
        if (app == "drastic") {
          bind("b", "TOUCH_CURSOR_PRESS", 1038); bind("b", "MENU", 1039)
          bind("b", "UI_SWITCH", 1039)
        } else {
          bind("a", "HOT", 1033); bind("a", "HOT_CHANGE_THEME", 1034)
        }
      }
      {
        key=$0; sub(/[[:space:]]*=.*$/, "", key); sub(/^[[:space:]]*/, "", key)
        if (key in binds && index($0, "=")) {
          if (seen[key]++) next
          value=$0; sub(/^[^=]*=[[:space:]]*/, "", value); sub(/[[:space:]]*$/, "", value)
          # Preserve analog, keyboard, disabled (65535), and button 16 (1040).
          if (value ~ /^103[2-9]$/) $0=key " = " binds[key]
        }
        print
      }
      END { for (key in binds) if (!seen[key]) print key " = " binds[key] }
    ' "$cfg" > "$t"; then rm -f "$t"; return 1; fi
    finish_input_file "$cfg" "$t" || return 1
  done
}
# Mednafen IDs are not SDL GUIDs. Only the stored native GO identity qualifies.
# Resolve each known action to an absolute button; preserve keyboard OR clauses,
# other identities, axes, and unknown actions. Full numeric tokens avoid matching
# button_1 inside button_12 (or a button_120 extension).
set_mednafen_input_binds() {
  local cfg t
  cfg=$(root_path "$ROOT" /home/ark/.mednafen/mednafen.cfg) || return 1
  [ -f "$cfg" ] || return 0
  t="$cfg.a36tmp"
  cp -p "$cfg" "$t" || return 1
  if ! awk '
    BEGIN {
      id="0x0019484b110001000004001100000000"
      buttons["up"]=10; buttons["down"]=13; buttons["left"]=11; buttons["right"]=12
      buttons["select"]=9; buttons["start"]=8; buttons["pause"]=8
      buttons["option"]=8; buttons["run"]=8
      buttons["lt"]=15; buttons["rt"]=14
      commands["exit"]=8; commands["fast_forward"]=7
      commands["load_state"]=4; commands["pause"]=1; commands["reset"]=0
      commands["save_state"]=5; commands["state_rewind"]=6
      commands["state_slot_dec"]=11; commands["state_slot_inc"]=12
      commands["toggle_fps_view"]=3; commands["toggle_state_rewind"]=2
    }
    {
      action=$1; sub(/^.*\./, "", action)
      sub(/-(l|y)$/, "", action)
      command=($1 ~ /^command\./ && action in commands)
      target=-1
      if ($1 ~ /[.]input[.]/ && action in buttons) target=buttons[action]
      if (!command && target < 0) { print; next }
      rest=$0; out=""
      # Include the entire joystick clause, but never consume the delimiter.
      while (match(rest, /joystick[[:space:]]+[^[:space:]]+[[:space:]]+button_[0-9]+([[:space:]]|$)/)) {
        start=RSTART; len=RLENGTH
        before=substr(rest, 1, start-1); token=substr(rest, start, len)
        split(token, parts, /[[:space:]]+/)
        number=parts[3]; sub(/^button_/, "", number)
        if (parts[2] == id && number+0 <= 16) {
          value=target
          if (command) value=(before ~ /&&[[:space:]]*$/ ? commands[action] : 9)
          sub(/button_[0-9]+/, "button_" value, token)
        }
        out=out before token
        rest=substr(rest, start+len)
      }
      print out rest
    }
  ' "$cfg" > "$t"; then rm -f "$t"; return 1; fi
  finish_input_file "$cfg" "$t"
}
# Raw SDL one-based JoyN bindings in the ACTIVE INIs. These native templates are
# keyed by section + action, so repeated sessions cannot rotate numbers again.
# .bak and backup/*.ini.<device> are restore-only seeds; normalize the resulting
# active GO config after restoring. Keep Pad_*, keyboard, axes and unknown actions.
set_doom_input_binds() {
  local app cfg t
  for app in gzdoom lzdoom; do
    cfg=$(root_path "$ROOT" "/home/ark/.config/$app/$app.ini") || return 1
    [ -f "$cfg" ] || continue
    t="$cfg.a36tmp"
    cp -p "$cfg" "$t" || return 1
    if ! awk -v app="$app" '
      function bind(section, action, button) { binds[section SUBSEP action]=button }
      BEGIN {
        bind("Doom.Bindings", "weapprev", 12); bind("Doom.Bindings", "weapnext", 13)
        bind("Doom.Bindings", "menu_main", 9)
        bind("Doom.Bindings", "centerview", (app == "gzdoom" ? 15 : 14))
        if (app == "gzdoom") bind("Doom.Bindings", "togglemap", 10)
        else {
          split("Strife Hexen Chex Heretic", games, " ")
          for (i in games) {
            section=games[i] ".Bindings"
            bind(section, "toggle cl_run", 11); bind(section, "centerview", 14)
            bind(section, "weapprev", 12); bind(section, "weapnext", 13)
            bind(section, "showpop 3", 9); bind(section, "showpop 2", 16)
            bind(section, "showpop 1", 15)
          }
          bind("Hexen.WOCKeysection.Bindings", "viewStats", 15)
          bind("Hexen.WOCKeysection.Bindings", "abil2", 10)
          bind("Hexen.WOCKeysection.Bindings", "abil3", 9)
          bind("Hexen.WOCKeysection.Bindings", "abil4", 16)
        }
      }
      /^[[:space:]]*\[/ {
        section=$0; sub(/^[[:space:]]*\[/, "", section); sub(/\].*$/, "", section)
      }
      /^[[:space:]]*Joy[0-9]+[[:space:]]*=/ {
        key=$0; sub(/^[[:space:]]*Joy/, "", key); sub(/[[:space:]]*=.*$/, "", key)
        rhs=substr($0, index($0, "=")+1)
        action=rhs; gsub(/^[[:space:]]+|[[:space:]]+$/, "", action)
        if (key+0 >= 9 && key+0 <= 16 && (section SUBSEP action) in binds)
          $0="Joy" binds[section SUBSEP action] "=" rhs
      }
      { print }
    ' "$cfg" > "$t"; then rm -f "$t"; return 1; fi
    finish_input_file "$cfg" "$t" || return 1
  done
}
# Legacy stored Solarus template; the current GameController consumer also uses
# the launcher DB. Retain the emulator-specific action/attack/item face choices.
set_solarus_input_binds() {
  local cfg t
  cfg=$(root_path "$ROOT" /opt/solarus/pads.ini) || return 1
  [ -f "$cfg" ] || return 0
  t="$cfg.a36tmp"
  cp -p "$cfg" "$t" || return 1
  if ! awk '
    BEGIN { binds["up"]=10; binds["down"]=13; binds["left"]=11; binds["right"]=12; binds["pause"]=8 }
    {
      key=$0; sub(/[[:space:]]*=.*$/, "", key); sub(/^[[:space:]]*/, "", key)
      if (key in binds && index($0, "=")) {
        if (seen[key]++) next
        $0=key "=button " binds[key]
      }
      print
    }
    END { for (key in binds) if (!seen[key]) print key "=button " binds[key] }
  ' "$cfg" > "$t"; then rm -f "$t"; return 1; fi
  finish_input_file "$cfg" "$t"
}
# Shell body shared by GO-only runtime event resolvers. Sysfs names are compared
# exactly; event numbering and Linux by-path symlinks are not stable on Android.
native_go_event_lookup() {
  cat <<'LOOKUP'
  for a36_name in /sys/class/input/event*/device/name; do
    [ -r "$a36_name" ] || continue
    grep -Fqx 'GO-Super Gamepad' "$a36_name" || continue
    a36_node=${a36_name%/device/name}
    printf '%s\n' "${a36_node##*/event}"
    break
  done
LOOKUP
}
install_native_evdev_helpers() {
  local name cfg t block
  for name in buttonmon.sh killer_daemon.py fn_menu_watcher.sh; do
    cfg=$(root_path "$ROOT" "/usr/local/bin/$name") || return 1
    [ -f "$cfg" ] || continue
    t="$cfg.a36tmp"
    cp -p "$cfg" "$t" || return 1
    # Supply generated shell via a file rather than awk -v (which interprets
    # backslash escapes in printf). Never execute a helper while patching it.
    block="$cfg.a36block"
    case "$name" in
      buttonmon.sh)
        {
          printf '%s\n' '# a36 native GO override begin' 'a36_go_event=$('
          native_go_event_lookup
          cat <<'GO'
)
if [ -n "$a36_go_event" ]; then
  event_num="$a36_go_event"
  event_type="EV_KEY"
  event_btn_a="BTN_SOUTH"
  event_btn_b="BTN_EAST"
  event_btn_x="BTN_NORTH"
  event_btn_y="BTN_WEST"
  event_btn_hk="KEY_HOMEPAGE"
fi
# a36 native GO override end
GO
        } > "$block" || { rm -f "$t" "$block"; return 1; }
        ;;
      fn_menu_watcher.sh) native_go_event_lookup > "$block" || { rm -f "$t" "$block"; return 1; } ;;
      killer_daemon.py) : > "$block" || { rm -f "$t"; return 1; } ;;
    esac
    if ! awk -v name="$name" '
      FILENAME == ARGV[1] { block=block $0 "\n"; next }
      name == "buttonmon.sh" {
        if ($0 == "# a36 native GO override begin") skip=1
        if (skip) { if ($0 == "# a36 native GO override end") skip=0; next }
        if ($0 ~ /^function Test_Button_A\(\)/) { printf "%s", block; inserted=1 }
        print; next
      }
      name == "killer_daemon.py" {
        if ($0 ~ /^[[:space:]]*"GO-Super Gamepad"[[:space:]]*:[[:space:]]*\{/) go=1
        if (go) {
          sub(/"f1":[[:space:]]*[0-9]+/, "\"f1\": 314")
          sub(/"f3":[[:space:]]*[0-9]+/, "\"f3\": 315")
          if (/}/) go=0
        }
        print; next
      }
      name == "fn_menu_watcher.sh" {
        gsub(/BTN_TRIGGER_HAPPY5/, "KEY_HOMEPAGE")
        if ($0 ~ /^find_device\(\)[[:space:]]*\{/) {
          print; printf "%s", block; skip=1; inserted=1; next
        }
        if (skip) { if ($0 == "}") { skip=0; print }; next }
        print
      }
      END {
        # An unfamiliar/truncated template must not be partially installed.
        if (skip || go || (name != "killer_daemon.py" && !inserted)) exit 1
      }
    ' "$block" "$cfg" > "$t"; then rm -f "$t" "$block"; return 1; fi
    rm -f "$block"
    finish_input_file "$cfg" "$t" || return 1
  done
}
# F10 known gap: OpenBOR master.cfg and per-game saves use an opaque binary
# serializer. Never invent offsets or rewrite them without a build-matched seed
# and serializer/version validation.
set_kodi_input_binds() {
  local dir="$ROOT/home/ark/.kodi/userdata/addon_data/peripheral.joystick/resources/buttonmaps/xml/linux" cfg t
  for cfg in "$dir/GO-Super_Gamepad_17b_4a.xml" "$dir/GO-Super_Gamepad_18b_4a.xml" \
             "$dir/GO-Super_Gamepad_17b_4a.xml.bak"; do
    cfg=$(root_path "$ROOT" "${cfg#"$ROOT"}") || return 1
    [ -f "$cfg" ] || continue
    t="$cfg.a36tmp"
    cp -p "$cfg" "$t" || return 1
    if ! awk '
      BEGIN {
        binds["back"]=9; binds["guide"]=16; binds["start"]=8
        binds["up"]=10; binds["down"]=13; binds["left"]=11; binds["right"]=12
        binds["leftthumb"]=15; binds["rightthumb"]=14
      }
      /<controller[[:space:]]/ { active=(/id="game.controller.default"/); back=0 }
      {
        if (active && /<feature[[:space:]]/ && match($0, /name="[^"]+"/)) {
          name=substr($0, RSTART+6, RLENGTH-7)
          if (name == "back") back=1
          if (name in binds) sub(/button="[0-9]+"/, "button=\"" binds[name] "\"")
        }
        if (active && /<\/controller>/ && !back)
          print "      <feature name=\"back\" button=\"9\" />"
        print
      }
      /<\/controller>/ { active=0 }
    ' "$cfg" > "$t"; then rm -f "$t"; return 1; fi
    finish_input_file "$cfg" "$t" || return 1
  done
}
# RetroArch's quit chord: dArkOS's retroarch.cfg hardwires input_exit_emulator_btn to
# the native pad's number (13 = our D-pad down), so Select+Start never quits a core on
# our pad. Set the semantic quit combo (value 4 = Select+Start); RetroArch resolves it
# through the start/select binds our autoconfig already fixes, so it is right regardless
# of button numbering. Applied to the 64- and 32-bit RetroArch configs in the overlay.
set_ra_quit_combo() {
  local cfg t
  for cfg in $RA_FULL_CONFIGS; do
    cfg=$(root_path "$ROOT" "$cfg") || return 1
    [ -f "$cfg" ] || continue
    t="$cfg.a36tmp"
    if grep -q 'input_quit_gamepad_combo[[:space:]]*=' "$cfg" 2>/dev/null; then
      sed 's/^.*input_quit_gamepad_combo[[:space:]]*=.*$/input_quit_gamepad_combo = "4"/' \
        "$cfg" > "$t" 2>/dev/null && mv "$t" "$cfg" &&
        log "set RetroArch quit combo (Select+Start) in ${cfg#$ROOT}"
    else
      printf 'input_quit_gamepad_combo = "4"\n' >> "$cfg" &&
        log "added RetroArch quit combo (Select+Start) to ${cfg#$ROOT}"
    fi
  done
}
session_uses_init() {
  [ "$cmd" = start ] || return 1
  [ "$SESSION_MODE" = systemd ] && return 0
  { [ "$HANDOFF" = 0 ] && has_init "$ROOT"; } && return 0
  ! find_es "$ROOT"
}

install_systemctl_shim() {
  local dst="$ROOT/usr/local/bin/systemctl"
  # An overlay can retain the shim from a previous wrapper session. Remove only
  # our generated client before booting init, so systemd gets its real client.
  if session_uses_init; then
    if [ -f "$dst" ] && grep -q '^# andr36oid subsystem systemctl shim' "$dst"; then
      rm -f "$dst"
    fi
    return 0
  fi
  mkdir -p "$ROOT/usr/local/bin" || return 1
  cat > "$dst" <<'SHIM' || return 1
#!/bin/sh
# andr36oid subsystem systemctl shim (written by the broker).
# Also delegate if a retained copy is reached after switching session managers.
if [ "$(cat /run/.a36-session-manager 2>/dev/null)" = systemd ] ||
   [ "$(cat /proc/1/comm 2>/dev/null)" = systemd ]; then
  for client in /usr/bin/systemctl /bin/systemctl; do
    [ -x "$client" ] && [ ! "$client" -ef "$0" ] && exec "$client" "$@"
  done
  echo 'systemctl: real systemd client unavailable' >&2
  exit 1
fi

# Rotate only the original arguments; retained positional arguments stay separate
# at the end. Options may precede or follow the verb and may have operands.
verb=; options=1; remaining=$#; quiet=0
while [ "$remaining" -gt 0 ]; do
  a=$1; shift; remaining=$((remaining-1))
  if [ "$options" = 1 ]; then
    case "$a" in
      --) options=0; continue ;;
      --job-mode|--type|-t)
        [ "$remaining" -gt 0 ] || { echo "systemctl: missing operand for $a" >&2; exit 2; }
        value=$1; shift; remaining=$((remaining-1))
        case "$a:$value" in --job-mode:replace|--type:service|-t:service) continue ;; esac
        echo "systemctl: unsupported $a $value" >&2; exit 2 ;;
      --job-mode=replace|--type=service|--no-block|--no-pager|--no-ask-password|--full|-l|--plain) continue ;;
      --quiet|-q) quiet=1; continue ;;
      -*) echo "systemctl: unsupported option $a" >&2; exit 2 ;;
    esac
  fi
  if [ -z "$verb" ]; then verb=$a; else set -- "$@" "$a"; fi
done

# Match argv[0], including its boundary: the 16-character comm name is truncated
# by Linux, and an unanchored -f would also match the wrapper or a broker shell.
# /proc may be shared with Android; filter every candidate by its actual root.
unit_pids() {
  case "$1" in
    emulationstation) pattern='^(/usr/bin/emulationstation/emulationstation|/usr/bin/emulationstation|/usr/bin/EmulationStation)([[:space:]]|$)' ;;
    ogage) pattern='^/usr/local/bin/ogage([[:space:]]|$)' ;;
  esac
  for pid in $(pgrep -f "$pattern" 2>/dev/null); do
    [ "/proc/$pid/root" -ef / ] && printf '%s\n' "$pid"
  done
}
stop_unit() {
  for pid in $(unit_pids "$1"); do kill "$pid" 2>/dev/null || :; done
}

case "$verb" in
  poweroff|halt|suspend|reboot)
    [ "$#" = 0 ] || { echo "systemctl: $verb takes no units" >&2; exit 2; }
    if [ "$verb" = reboot ]; then
      : > /run/.a36-session-reboot || exit 1
    else
      : > /run/.a36-session-exit || exit 1
    fi
    stop_unit emulationstation
    exit 0 ;;
  start|restart|stop|is-active) [ "$#" -gt 0 ] || { echo 'systemctl: missing unit' >&2; exit 2; } ;;
  *) echo "systemctl: unsupported verb $verb" >&2; exit 2 ;;
esac
rc=0
for unit do
  case "$unit" in
    emulationstation|emulationstation.service) unit=emulationstation ;;
    ogage|ogage.service) unit=ogage ;;
    *) echo "systemctl: unsupported unit $unit" >&2; rc=4; continue ;;
  esac
  case "$verb" in
    stop)
      # A deliberate stop can cover unbounded sync/maintenance. Keep the broker
      # from treating the ensuing exit as a crash until start/restart or poweroff.
      if [ "$unit" = emulationstation ]; then
        : > /run/.a36-es-stopped || exit 1
        rm -f /run/.a36-es-start
      fi
      stop_unit "$unit" ;;
    start|restart)
      if [ "$unit" = emulationstation ]; then
        if [ -f /run/.a36-es-stopped ]; then
          : > /run/.a36-es-start || exit 1
          rm -f /run/.a36-es-stopped
        else
          : > /run/.a36-es-restart || exit 1
        fi
        stop_unit "$unit"
      else
        stop_unit "$unit"
        # This policy is prepared by the broker, also for emulator return paths
        # that call systemctl directly instead of start_hotkeys.
        if [ -f /run/.a36-handoff ] && [ ! -f /run/.a36-muted ]; then
          setsid /usr/local/bin/ogage >/dev/null 2>&1 &
        fi
      fi ;;
    is-active)
      if [ -n "$(unit_pids "$unit")" ]; then
        [ "$quiet" = 1 ] || echo active
      else
        [ "$quiet" = 1 ] || echo inactive
        [ "$rc" != 0 ] || rc=3
      fi ;;
  esac
done
exit "$rc"
SHIM
  chmod 0755 "$dst" || return 1
  log "installed subsystem systemctl shim (lifecycle verbs -> real actions)"
}

prime_input() {
  install_es_input
  # RetroArch udev autoconfig for the console gamepad. The name carries a space,
  # which RetroArch matches against the evdev device name verbatim.
  local radst="$ROOT/home/ark/.config/retroarch/autoconfig/udev"
  if [ -f "$UPPER/home/ark/.config/retroarch/autoconfig/udev/GO-Super Gamepad.cfg" ]; then
    log "retroarch autoconfig already present in overlay (keeping user mapping)"
  elif [ -f "$RAAUTOCFG_SRC" ]; then
    mkdir -p "$radst"
    cp "$RAAUTOCFG_SRC" "$radst/GO-Super Gamepad.cfg" && log "installed shipped retroarch autoconfig (GO-Super Gamepad)"
  else
    log "no shipped retroarch autoconfig at $RAAUTOCFG_SRC; RetroArch will use fallback binds"
  fi
  install_sdl_mapping
  install_launcher_sdl_mapping
  install_ra_profiles
  set_ra_input_binds
  set_mupen_input_binds
  set_flycast_input_binds
  set_dingux_input_binds
  install_native_evdev_helpers
  set_drastic_input_binds
  set_mednafen_input_binds
  set_doom_input_binds
  set_solarus_input_binds
  set_kodi_input_binds
  prime_storage_input
  set_ra_quit_combo
  # Volume-key diagnostic: dArkOS's ogage daemon scans /dev/input/event0..event10
  # and drives "amixer sset Playback". If the volume buttons do nothing, this
  # records every input device's name, event handler and key bitmap so the report
  # shows which event node carries the volume keys (codes 114/115) and whether it
  # falls within ogage's scan range - traceable without a device in hand.
  if [ -r /proc/bus/input/devices ]; then
    grep -E '^N:|^H:|^B: KEY=' /proc/bus/input/devices 2>/dev/null |
      while IFS= read -r line; do log "input: $line"; done
  fi
}

prime_udev() {
  [ "$HANDOFF" = 1 ] || return 0
  # RetroArch's udev joypad driver only opens input devices udev has tagged
  # ID_INPUT_JOYSTICK, which it reads from /run/udev/data/c<major>:<minor>. Booting udevd
  # to produce those tags does NOT work in this container -- its uevent netlink is blocked
  # (hardware-confirmed: the daemon starts but /run/udev/data stays empty), so RA finds
  # zero joypads ("[udev] Couldn't open any joypads ... /run/udev/?") and every libretro
  # core runs with a dead gamepad. Seed the tag directly for each input event node the
  # kernel also exposes a joystick (jsN) for: that is exactly what RA's enumerate filter
  # matches on; RA then opens the node and reads the button/axis map itself. The file
  # format mirrors a real udev db entry (udev 255). ES is unaffected (SDL2 scans evdev).
  local sysdev cand js devnum name n=0
  mkdir -p "$ROOT/run/udev/data" 2>/dev/null || return 0
  for sysdev in /sys/class/input/event*; do
    [ -r "$sysdev/dev" ] || continue
    js=""
    for cand in "$sysdev"/device/js*; do [ -e "$cand" ] && { js=$cand; break; }; done
    [ -n "$js" ] || continue
    devnum=$(cat "$sysdev/dev" 2>/dev/null) || continue
    printf 'I:1\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\nV:1\n' \
      > "$ROOT/run/udev/data/c$devnum" 2>/dev/null || continue
    n=$((n+1))
    name=$(cat "$sysdev/device/name" 2>/dev/null)
    log "udev: tagged ${name:-input} (c$devnum) ID_INPUT_JOYSTICK for RetroArch"
  done
  log "udev: seeded joystick tags for $n input device(s)"
}

# Populate the container /dev. toybox mount cannot bind a non-directory source
# (it tries to loop-mount it and fails with "losetup ... Invalid argument"), so
# device nodes are recreated with mknod in the tmpfs /dev (which is not nodev);
# only directories are bind-mounted.
add_dev() {
  local src="/dev/$1" dst="$ROOT/dev/$1" maj min
  [ -e "$src" ] || return 0
  if [ -d "$src" ]; then
    mkdir -p "$dst"; bind_mount "$src" "$dst"; return
  fi
  maj=$(stat -c '%t' "$src"); min=$(stat -c '%T' "$src")
  case "$(stat -c '%F' "$src")" in
    "block special file") mknod -m 0660 "$dst" b "$((0x$maj))" "$((0x$min))" ;;
    *)                    mknod -m 0666 "$dst" c "$((0x$maj))" "$((0x$min))" ;;
  esac
}
bind_core() {
  local n
  mkdir -p "$ROOT/dev" "$ROOT/proc" "$ROOT/sys" "$ROOT/tmp" "$ROOT/run" || return 1
  # A separate /dev keeps shm and hidden display nodes local to this container.
  # Binding Android's whole /dev would create /dev/shm on the host as well.
  mount -t tmpfs -o mode=0755,nosuid tmpfs "$ROOT/dev" || return 1
  track_mount "$ROOT/dev"
  for n in null zero full random urandom tty ptmx pts input snd; do
    add_dev "$n" || return 1
  done
  if [ "$HANDOFF" = 1 ]; then
    # ion is the Mali GPU buffer allocator on this 4.19 kernel (no /dev/dma_heap):
    # without it EGL/GLES cannot allocate and EmulationStation spins without ever
    # rendering. rga (2D blit) and mpp_service (video codec) are what ports need.
    for n in dri mali0 mali graphics uinput tty0 tty1 ion rga mpp_service; do
      add_dev "$n" || return 1
    done
    [ -e "$ROOT/dev/graphics/fb0" ] && ln -s graphics/fb0 "$ROOT/dev/fb0"
  fi
  # /dev/console: init may reopen it after replacing stdout, so a /proc/self/fd/1
  # symlink would silently follow its new stdout. toybox mount cannot bind the
  # logging FIFO either (a non-directory source loop-mounts and fails), so make a
  # FIFO in the container /dev and forward it into the same log stream.
  if mkfifo -m 0666 "$ROOT/dev/console" 2>/dev/null; then
    ( while [ -p "$ROOT/dev/console" ]; do cat "$ROOT/dev/console" || break; done >> "$STATE/session.fifo" ) 2>/dev/null &
    CONSOLE_FWD=$!
  else
    return 1
  fi
  ln -s /proc/self/fd "$ROOT/dev/fd"
  ln -s /proc/self/fd/0 "$ROOT/dev/stdin"
  ln -s /proc/self/fd/1 "$ROOT/dev/stdout"
  ln -s /proc/self/fd/2 "$ROOT/dev/stderr"
  mkdir -p "$ROOT/dev/shm"
  mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "$ROOT/dev/shm" || return 1
  track_mount "$ROOT/dev/shm"
  bind_mount /proc "$ROOT/proc" || return 1
  if [ "$HANDOFF" = 0 ]; then
    # Android keeps the live display, so the rootfs's governor/gamma/backlight
    # scripts must not write sysfs. toybox cannot ro-remount a bind (it reports
    # the mount busy), so give the container a fresh, genuinely read-only sysfs.
    mount -t sysfs -o ro,nosuid,nodev,noexec sysfs "$ROOT/sys" || return 1
    track_mount "$ROOT/sys"
  else
    bind_mount /sys "$ROOT/sys" || return 1
  fi
  mount -t tmpfs -o mode=0755,nosuid,nodev tmpfs "$ROOT/run" || return 1
  track_mount "$ROOT/run"
  mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "$ROOT/tmp" || return 1
  track_mount "$ROOT/tmp"
}

# Kill everything running inside the subsystem (EmulationStation and whatever it
# launched - an emulator that hangs holds /dev/dri/card0's DRM master, so just
# restarting SurfaceFlinger would not get the display back). Identify them by
# their chroot root pointing into $ROOT; belt-and-braces fuser on card0.
kill_subsystem_payload() {
  local p pid r
  for p in /proc/[0-9]*; do
    r=$(readlink "$p/root" 2>/dev/null) || continue
    case "$r" in "$ROOT"|"$ROOT"/*) pid=${p#/proc/}; kill -9 "$pid" 2>/dev/null;; esac
  done
  return 0
}

kill_subsystem() {
  kill_subsystem_payload
  if [ "$DID_HANDOFF" = 1 ]; then
    command -v fuser >/dev/null 2>&1 && fuser -k /dev/dri/card0 2>/dev/null
  fi
  [ -n "${SESSION_PID:-}" ] && kill "$SESSION_PID" 2>/dev/null
  return 0
}

# --- teardown (idempotent) ----------------------------------------------------
enable_hardware_master() {
  # 235 maps to attenuation 20, the R36S headphone default. tinymix's
  # boolean controls take numeric values, and the broker runs on Android.
  if /system/bin/tinymix -D 0 "Master Playback Volume" 235 >/dev/null 2>&1; then
    # Record intent before enabling, so interrupted sessions are recoverable.
    if : > "$STATE/session.hw-master"; then
      if /system/bin/tinymix -D 0 "Playback Hardware Master Switch" 1 >/dev/null 2>&1; then
        log "hardware master enabled"
      else
        log "hardware master switch unavailable"
      fi
    fi
  else
    log "hardware master unavailable; continuing without it"
  fi
  return 0
}

# Keep the two shareable config files in the image's writable upper before the
# overlay goes away. The queued setup worker can then merge them after teardown.
prepare_retroarch_sync() {
  RA_SYNC_DIR=""
  rm -f "$STATE/retroarch-sync-dir"
  [ "$(getprop persist.andr36oid.subsystem_autosync)" != 0 ] || return 0
  [ "$(getprop persist.andr36oid.sync.retroarch)" = 1 ] || return 0
  local dir="$ROOT/home/ark/.config/retroarch" file tmp
  [ -d "$dir" ] && [ "$(readlink -f "$dir")" = "$dir" ] || return 0
  if [ "${MOUNTED:-0}" = 1 ]; then
    for file in retroarch.cfg retroarch-core-options.cfg; do
      [ -L "$dir/$file" ] && return 0
      [ -f "$dir/$file" ] || continue
      tmp=$(mktemp "$dir/.andr36oid-sync.XXXXXX") || return 0
      if ! cp -p "$dir/$file" "$tmp" || ! mv -f "$tmp" "$dir/$file"; then
        rm -f "$tmp"
        log "could not prepare RetroArch settings for sharing"
        return 0
      fi
    done
    RA_SYNC_DIR="$UPPER/home/ark/.config/retroarch"
  else
    RA_SYNC_DIR="$dir"
  fi
}

restore_android() {
  [ "$SESSION_OWNED" = 1 ] || return 0
  # Invalidate ownership and reap the watchdog before touching the shared codec
  # or restarting Android. This path is shared by EXIT and guardian recovery.
  stop_mute_guard
  kill_subsystem
  prepare_retroarch_sync
  if [ -f "$STATE/session.hw-master" ]; then
    /system/bin/tinymix -D 0 "Playback Hardware Master Switch" 0 >/dev/null 2>&1 ||
      log "hardware master switch could not be restored"
    rm -f "$STATE/session.hw-master"
  fi
  local b tries
  CLEANUP_FAILED=0
  # Newest first, including partially prepared sessions and paths with spaces.
  if [ -f "$STATE/session.mounts" ]; then
    while IFS= read -r b; do
      [ -n "$b" ] || continue
      tries=0
      while ! umount "$b" 2>/dev/null; do
        # Already-unmounted entries are harmless on repeated stop/recovery.
        awk -v p="$b" '{gsub(/\\040/," ",$2); gsub(/\\011/,"\t",$2)} $2==p {f=1} END {exit !f}' /proc/mounts || break
        if [ "$tries" -ge 3 ]; then CLEANUP_FAILED=1; log "mount still busy: $b"; break; fi
        sleep 1; tries=$((tries+1))
      done
    done < "$STATE/session.mounts"
  fi
  if [ -n "$LOOPDEV" ]; then
    if ! losetup -d "$LOOPDEV" 2>/dev/null && [ -r "/sys/class/block/${LOOPDEV##*/}/loop/backing_file" ]; then
      CLEANUP_FAILED=1; log "loop still busy: $LOOPDEV"
    fi
  fi
  if [ "$CLEANUP_FAILED" = 0 ] && [ -n "$RA_SYNC_DIR" ]; then
    printf '%s\n' "$RA_SYNC_DIR" > "$STATE/retroarch-sync-dir.tmp" &&
      mv -f "$STATE/retroarch-sync-dir.tmp" "$STATE/retroarch-sync-dir"
  fi
  if [ "$DID_HANDOFF" = 1 ]; then
    show_splash
    setprop ctl.start "$HWC" 2>/dev/null; sleep 1
    setprop ctl.start surfaceflinger 2>/dev/null; start 2>/dev/null
  fi
  return 0
}

session_active() {
  local pid
  [ "$(cat "$STATE/session.lock/boot_id" 2>/dev/null)" = "$(cat /proc/sys/kernel/random/boot_id)" ] || return 1
  pid=$(cat "$STATE/session.lock/pid" 2>/dev/null)
  case "$pid" in ''|*[!0-9]*) return 1 ;; esac
  kill -0 "$pid" 2>/dev/null
}

recover_session() {
  [ -d "$STATE/session.lock" ] || return 0
  # A guardian for an older session must never tear down its replacement.
  [ -z "${1:-}" ] || [ "$(cat "$STATE/session.lock/pid" 2>/dev/null)" = "$1" ] || return 0
  if [ "$(cat "$STATE/session.lock/boot_id" 2>/dev/null)" != "$(cat /proc/sys/kernel/random/boot_id)" ]; then
    # /data survives reboot, mounts and PIDs do not. Never detach a loop or kill
    # a recycled PID from a previous boot based on its old metadata.
    rm -f "$STATE/session.loop" "$STATE/session.handoff" "$STATE/session.mounts" "$STATE/session.fifo" "$STATE/session.hw-master" "$MUTE_PIDFILE" "$STATE/mute-guard.owner" "$STATE/session.lock/pid" "$STATE/session.lock/boot_id"
    rmdir "$STATE/session.lock" 2>/dev/null
    return 0
  fi
  SESSION_OWNED=1
  [ -f "$STATE/session.handoff" ] && DID_HANDOFF=1
  LOOPDEV=$(cat "$STATE/session.loop" 2>/dev/null)
  restore_android
  setprop sys.andr36oid.subsystem.session ended 2>/dev/null
  [ "$CLEANUP_FAILED" = 0 ] || return 1
  rm -f "$STATE/session.loop" "$STATE/session.handoff" "$STATE/session.mounts" "$STATE/session.fifo" "$STATE/session.lock/pid" "$STATE/session.lock/boot_id"
  rmdir "$STATE/session.lock" 2>/dev/null
  SESSION_OWNED=0; DID_HANDOFF=0; LOOPDEV=""; BOUND=""
}

stop_session() {
  local pid tries=0
  pid=$(cat "$STATE/session.lock/pid" 2>/dev/null)
  if session_active; then
    kill -TERM "$pid" 2>/dev/null
    while session_active && [ "$tries" -lt 10 ]; do sleep 1; tries=$((tries+1)); done
    session_active && kill -KILL "$pid" 2>/dev/null
  fi
  recover_session "$pid"
}

# FIFO + a separately waited tee preserves the payload exit code (a shell
# pipeline would report tee's status). Preparation, init, descendants and cleanup
# all use the same stream; the app may open/tail boot.log as soon as running=1.
start_live_log() {
  chmod 0755 "$STATE"
  : > "$STATE/boot.log"; chmod 0644 "$STATE/boot.log"
  : > "$SESSION_OUT"
  rm -f "$STATE/session.fifo"
  mkfifo -m 0600 "$STATE/session.fifo" || return 1
  tee -a "$STATE/boot.log" < "$STATE/session.fifo" >> "$SESSION_OUT" & TEE_PID=$!
  exec 3>&1 4>&2
  exec > "$STATE/session.fifo" 2>&1
  LIVE_LOG=1
}

finish_session() {
  local rc="$1"
  trap - EXIT INT TERM HUP
  setprop sys.andr36oid.subsystem.provisioning "" 2>/dev/null
  restore_android
  [ "${IS_PM:-0}" = 1 ] && setprop sys.andr36oid.ports_changed 1 2>/dev/null
  setprop sys.andr36oid.subsystem_changed 1 2>/dev/null
  : > "$STATE/subsystem.clean"
  [ -n "${GUARD:-}" ] && kill "$GUARD" 2>/dev/null
  [ -n "${CONSOLE_FWD:-}" ] && kill "$CONSOLE_FWD" 2>/dev/null
  log "session ended rc=$rc"
  if [ "${LIVE_LOG:-0}" = 1 ]; then
    LIVE_LOG=0
    exec 1>&3 2>&4 3>&- 4>&-
    wait "$TEE_PID"
  fi
  setprop sys.andr36oid.subsystem.session ended 2>/dev/null
  setprop sys.andr36oid.subsystem.boot_pending "" 2>/dev/null
  update_settings_entry org.andr36oid.syncsettings
  if [ "$CLEANUP_FAILED" = 0 ]; then
    rm -f "$STATE/session.loop" "$STATE/session.handoff" "$STATE/session.mounts" "$STATE/session.fifo" "$STATE/session.lock/pid" "$STATE/session.lock/boot_id"
    rmdir "$STATE/session.lock" 2>/dev/null
  fi
  exit "$rc"
}

# --- guardian: restore Android if we die without cleaning up ------------------
arm_guardian() {
  rm -f "$STATE/subsystem.clean"
  local me
  me=$$
  (
    while kill -0 "$me" 2>/dev/null; do sleep 1; done
    [ -f "$STATE/subsystem.clean" ] && exit 0
    sleep "$GUARD_GRACE"; [ -f "$STATE/subsystem.clean" ] && exit 0
    echo "GUARDIAN restore $(cut -d. -f1 /proc/uptime)s"
    A36_STATE="$STATE" A36_ROOT="$ROOT" /system/bin/sh "$0" recover "$me"
  ) </dev/null >> "$LOG" 2>&1 & GUARD=$!
}

# Fill the framebuffer with the andr36oid splash so the handoff gaps (composer
# stopped but the port not yet on card0, and the reverse on the way back) show the
# logo instead of a blank screen. fbdev may pan to any of the triple buffers, so
# the raw covers all three. Best-effort: silent if the node/file is absent, and a
# no-op while something holds DRM master on card0 (the port's own output wins).
show_splash() {
  [ -f "$SPLASH" ] && [ -c "$FB" ] || return 0
  # Only paint when the splash is exactly the framebuffer's byte size (stride x
  # virtual height). A panel of a different resolution would ship a differently
  # sized splash; a mismatch means "no correct splash for this panel", so skip
  # rather than paint garbage. stride x yres_virtual covers all pan buffers.
  local stride vh want have
  stride=$(cat /sys/class/graphics/fb0/stride 2>/dev/null)
  vh=$(cat /sys/class/graphics/fb0/virtual_size 2>/dev/null); vh=${vh#*,}
  case ${stride:-x}:${vh:-x} in *[!0-9:]*|*:x*|x*) return 0 ;; esac
  want=$((stride * vh)); have=$(stat -c %s "$SPLASH" 2>/dev/null)
  if [ "$want" = "$have" ]; then
    dd if="$SPLASH" of="$FB" bs="$stride" 2>/dev/null && log "splash painted ($want b)"
  else
    log "splash skip: fb=$want splash=${have:-none}"
  fi
  return 0
}

# --- the handoff --------------------------------------------------------------
handoff() {
  # Mark before the first stop, so even an interrupted handoff is restored.
  : > "$STATE/session.handoff"; DID_HANDOFF=1
  local service
  log "stop framework (keep netd and Android's connection)"
  # toolbox `stop` without arguments also stops netd. Keep it alive so its
  # interfaces, policy routing and DNS state remain available to the chroot.
  # joyMouse (class main) grabs the gamepad (EVIOCGRAB on event3) to drive Android's
  # pointer; left running through a handoff it starves ES/RetroArch of the pad, so the
  # session looks alive but takes no input. Stop it here; the bare `start` in
  # restore_android brings every class-main service, joyMouse included, back afterwards.
  for service in zygote zygote_secondary surfaceflinger audioserver iorapd joyMouse; do
    case "$(getprop "init.svc.$service")" in running|restarting) stop "$service" ;; esac
  done
  i=0; while [ "$(getprop init.svc.surfaceflinger)" = running ] && [ "$i" -lt 10 ]; do sleep 1; i=$((i+1)); done
  log "stop composer"; setprop ctl.stop "$HWC"
  i=0; while [ "$(getprop "init.svc.$HWC")" = running ] && [ "$i" -lt 10 ]; do sleep 1; i=$((i+1)); done
  show_splash   # logo over the blank gap until the port grabs card0
  # Keep adbd up for the whole subsystem session so the session stays remotely
  # reachable (watch logs, kill a stuck port, reboot) with nobody at the device.
  # adbd is its own init service and `stop` above does not kill it, but make it
  # explicit. DEBUG ONLY: forcing adbd up is a test convenience, never on a
  # release build - end users should not have adb forced on during a game. These
  # images are userdebug even for release, so gate on ro.andr36oid.channel (set
  # from the build kind): night/beta keep it, release drops it.
  case "$(getprop ro.andr36oid.channel)" in release|'') : ;; *) start adbd 2>/dev/null ;; esac
}

run_in_subsystem() {   # $* = command to exec inside the chroot
  # PATH is essential: EmulationStation launches ports via a NON-login shell, so
  # the port command (`sudo perfmax ...; nice -n -19 %ROM%`) inherits our PATH.
  # Without /usr/bin on it, `nice`/`sudo`/`perfmax` are "not found" and no port
  # ever execs. A sudo->exec shim (we are already root here) keeps perfmax/perfnorm
  # from failing on sudoers/PAM; it lives on the writable overlay, not the RO lower.
  if [ ! -x "$ROOT/usr/local/bin/sudo" ]; then
    mkdir -p "$ROOT/usr/local/bin"
    printf '#!/bin/sh\nexec "$@"\n' > "$ROOT/usr/local/bin/sudo" && chmod 0755 "$ROOT/usr/local/bin/sudo"
  fi
  local video=dummy
  [ "$HANDOFF" = 1 ] && video=kmsdrm
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  HOME=/home/ark TERM=linux \
  ALSA_TMPDIR=/run/alsa TMPDIR=/tmp \
  SDL_VIDEODRIVER="$video" SDL_VIDEO_EGL_DRIVER=libEGL.so SDL_KMSDRM_REQUIRE_DRM_MASTER="$HANDOFF" \
  SDL_GAMECONTROLLERCONFIG="$SDL_PAD_MAP" \
  SDL_GAMECONTROLLERCONFIG_FILE=/opt/inttools/gamecontrollerdb.txt \
    "$CHROOT" "$ROOT" "$@"
}

prepare_network() {
  # The chroot shares Android's network namespace, interfaces and policy routes:
  # never unshare -n, add a default route, or start a competing DHCP/WiFi manager.
  # glibc cannot use bionic's netd resolver, so snapshot Android's default-link
  # DNS into a real resolv.conf, replacing systemd-resolved's dangling stub link.
  # Overlay unit masks and temporary init-script binds keep the existing Android
  # connection in charge, including when booting a generic rootfs's own init.
  local dns="" p snapshot net d unit path
  snapshot=$(dumpsys connectivity 2>/dev/null)
  net=$(printf '%s\n' "$snapshot" | awk '/Active default network:/ {print $NF; exit}')
  if [ -n "$net" ]; then
    dns=$(printf '%s\n' "$snapshot" | awk -v n="$net" '
      index($0,"network{" n "}") && /DnsAddresses: *\[/ {
        sub(/.*DnsAddresses: *\[/,""); sub(/\].*/,""); gsub(/[,\/]/," "); print; exit
      }')
  fi
  if [ -z "$dns" ]; then
    for p in net.dns1 net.dns2 net.dns3 net.dns4; do dns="$dns $(getprop "$p")"; done
  fi
  : > "$STATE/resolv.conf"
  for d in $dns; do
    case "$d" in ''|127.*|::1|*[!0-9a-fA-F.:]*) continue ;; esac
    printf 'nameserver %s\n' "$d" >> "$STATE/resolv.conf"
  done
  if [ ! -s "$STATE/resolv.conf" ]; then
    for p in /etc/resolv.conf /system/etc/resolv.conf; do
      [ -f "$p" ] || continue
      awk '$1=="nameserver" && $2 !~ /^(127\.|::1$)/ {print "nameserver " $2}' "$p" > "$STATE/resolv.conf"
      [ -s "$STATE/resolv.conf" ] && break
    done
  fi
  if [ ! -s "$STATE/resolv.conf" ]; then
    log "Android has no published DNS; using fallback resolvers (requires an Android connection)"
    printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$STATE/resolv.conf"
  fi
  printf 'options timeout:2 attempts:2\n' >> "$STATE/resolv.conf"
  chmod 0644 "$STATE/resolv.conf"
  mkdir -p "$ROOT/etc"
  [ -L "$ROOT/etc/resolv.conf" ] && rm -f "$ROOT/etc/resolv.conf"
  : > "$ROOT/etc/resolv.conf"
  bind_mount "$STATE/resolv.conf" "$ROOT/etc/resolv.conf" || return 1
  [ -L "$ROOT/etc/hosts" ] && rm -f "$ROOT/etc/hosts"
  touch "$ROOT/etc/hosts"
  if ! awk '$1=="127.0.0.1" {f=1} END {exit !f}' "$ROOT/etc/hosts"; then
    printf '127.0.0.1 localhost\n' >> "$ROOT/etc/hosts"
  fi
  if ! awk '$1=="::1" {f=1} END {exit !f}' "$ROOT/etc/hosts"; then
    printf '::1 localhost ip6-localhost\n' >> "$ROOT/etc/hosts"
  fi
  chmod 0644 "$ROOT/etc/hosts"
  mkdir -p "$ROOT/run/systemd/system" "$ROOT/etc/systemd/system"
  for unit in NetworkManager NetworkManager-wait-online systemd-networkd systemd-networkd-wait-online systemd-resolved networking dhcpcd dhcpcd@ wpa_supplicant wpa_supplicant@ iwd connman; do
    ln -sf /dev/null "$ROOT/run/systemd/system/$unit.service"
    # /etc takes precedence over /run (including enabled custom unit files).
    ln -sf /dev/null "$ROOT/etc/systemd/system/$unit.service"
  done
  printf '#!/bin/sh\n# Android owns networking in this shared namespace.\nexit 0\n' > "$STATE/network-disabled"
  chmod 0755 "$STATE/network-disabled"
  for path in /etc/init.d/networking /etc/init.d/network /etc/init.d/NetworkManager /etc/init.d/dhcpcd /etc/init.d/wpa_supplicant /etc/init.d/connman; do
    [ -f "$ROOT$path" ] && bind_mount "$STATE/network-disabled" "$ROOT$path"
  done
  log "network: Android interfaces/routes retained; DNS $(cat "$STATE/resolv.conf")"
}

# dArkOSen's EmulationStation is started by systemd as the "ark" user with a prepared
# session (udev perms on the input/DRM nodes, an XDG runtime dir, gptokeyb for menu
# control). Running the bare ES binary as root skips all of that and renders black.
# prep_darkos_env makes the nodes dArkOS expects world-usable from inside the chroot;
# it is cheap and harmless to repeat, and both session modes below rely on it.
# The games card is bound into the container from Android's vold mount, which is
# mode 0770 owned root:media_rw (gid 1023); the input devices are 0660 root:input
# (gid 1004). ark (uid 1000) is in neither Android group, so ES would see an empty
# /roms and a port that reads /dev/input/event* directly (Kodi) would get no input.
# Those Android gids mean nothing by NAME inside dArkOS, but the kernel checks the
# NUMERIC gid, so adding ark to groups with those gids grants access. systemd reads
# User=ark's groups from /etc/group when it starts ES, so this must run before boot.
grant_storage_groups() {
  local grp="$ROOT/etc/group" gid
  [ -f "$grp" ] || return 0
  for gid in 1023 1015 1004 1005 3003 3004 9997; do   # storage, input, audio, inet, net_raw, everybody
    if awk -F: -v g="$gid" '$3==g{f=1} END{exit !f}' "$grp"; then
      # a group already owns this gid: add ark to it if absent
      awk -F: -v g="$gid" 'BEGIN{OFS=":"}
        $3==g && $4 !~ /(^|,)ark(,|$)/ { $4 = ($4=="" ? "ark" : $4",ark") }
        {print}' "$grp" > "$grp.a36" && cat "$grp.a36" > "$grp" && rm -f "$grp.a36"
    else
      echo "a36hw$gid:x:$gid:ark" >> "$grp"
    fi
  done
}

prep_darkos_env() {
  local n
  if [ "$HANDOFF" = 1 ]; then
    chmod 0666 /dev/uinput 2>/dev/null
    for n in /dev/dri/card0 /dev/dri/renderD128 /dev/mali0; do [ -e "$n" ] && chmod 0666 "$n" 2>/dev/null; done
  fi
  for n in /dev/snd/*; do [ -e "$n" ] && chmod 0666 "$n" 2>/dev/null; done
  # Raw input nodes for ports that read evdev directly (ES itself goes through ogage's
  # uinput device above, but Kodi and some ports open /dev/input/event* themselves).
  for n in /dev/input/event* /dev/input/js*; do [ -e "$n" ] && chmod 0666 "$n" 2>/dev/null; done
  grant_storage_groups
  mkdir -p "$ROOT/run/user/0" "$ROOT/run/user/1000" 2>/dev/null
  chmod 0700 "$ROOT/run/user/0" "$ROOT/run/user/1000" 2>/dev/null
  chown 1000:1000 "$ROOT/run/user/1000" 2>/dev/null   # ark
  # Standard dmix container prerequisites: private tmpfs /dev/shm (bind_core),
  # sticky /tmp and ALSA runtime/lock/state directories writable by ark and root.
  # SysV IPC, SELinux, kernel.sem and seccomp were already ruled out on hardware.
  # TODO(hardware): strace -f -e semget,semctl,semop,openat inside the container
  # to confirm the actual failing dmix semaphore call and its errno/ownership.
  # Use chroot for /var paths: many rootfs images use absolute /var/run symlinks.
  if root_file "$ROOT" /bin/sh; then
    "$CHROOT" "$ROOT" /bin/sh -c '
    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    mkdir -p /tmp/alsa /run/alsa /run/lock/alsa /var/lib/alsa /var/cache/alsa
    chmod 1777 /tmp /tmp/alsa /dev/shm /run/alsa /run/lock /run/lock/alsa /var/lib/alsa /var/cache/alsa
    for state in /var/lib/alsa/asound.state /var/lib/alsa/asound.state.lock; do
      [ ! -f "$state" ] || chmod 0666 "$state"
    done
    exit 0
    ' || return 1
  else
    # A minimal image may contain only a static init; do not require a shell
    # merely for preparing its ALSA directories.
    local dir
    for dir in /run/alsa /run/lock/alsa /var/lib/alsa /var/cache/alsa; do
      dir=$(root_path "$ROOT" "$dir") || return 1
      mkdir -p "$dir" && chmod 1777 "$dir" || return 1
    done
  fi
  # Mix playback streams in dmix and expose the codec's hardware controls.
  if [ -d "$ROOT/home/ark" ]; then
    mkdir -p "$ROOT/etc" || return 1
    cat > "$ROOT/etc/asound.conf" <<'EOF'
pcm.!default {
    type plug
    slave.pcm "dmixer"
}
pcm.dmixer {
    type dmix
    ipc_key 1024
    ipc_perm 0666
    slave { pcm "hw:0,0" period_time 0 period_size 1024 buffer_size 4096 rate 44100 }
    bindings { 0 0 1 1 }
}
ctl.!default { type hw card 0 }
EOF
    # ALSA loads ~/.asoundrc after /etc/asound.conf. Preserve the image's
    # original once and install the same configuration for its ark user.
    if [ -e "$ROOT/home/ark/.asoundrc" ] && [ ! -e "$ROOT/home/ark/.asoundrc.andr36oid-original" ]; then
      cp -p "$ROOT/home/ark/.asoundrc" "$ROOT/home/ark/.asoundrc.andr36oid-original" || return 1
    fi
    cp "$ROOT/etc/asound.conf" "$ROOT/home/ark/.asoundrc" || return 1
    chmod 0644 "$ROOT/etc/asound.conf" "$ROOT/home/ark/.asoundrc" || return 1
  fi
  if [ "$HANDOFF" = 1 ]; then
    [ -e "$ROOT/dev/tty1" ] || ln -sf tty0 "$ROOT/dev/tty1" 2>/dev/null
  fi
  return 0
}

# The dArkOS session/setup units we bring up under systemd. These configure the device
# the way dArkOS expects (CPU/GPU/RAM governors, screen gamma, audio routing, battery
# LED, the WiFi/BT status daemon the ES header reads, the Fn-button watcher), plus the
# controller daemon (ogage) and EmulationStation itself. Deliberately NOT included:
#   - firstboot.service: it only runs /boot/expandtoexfat.sh, dArkOS's SD-card grow /
#     exFAT-format step. There is no card to expand in our loop-mounted container, so it
#     is inapplicable (not session setup). ES's "After=firstboot" is likewise moot here.
#   - NetworkManager/wpa_supplicant/networking/wifi_monitor: Android owns the
#     shared network namespace; prepare_network supplies DNS and masks managers.
#     es-status-daemon stays to report the existing connection.
#   - console-setup: RequiresMountsFor=/usr + tty keymap, irrelevant to the GUI session.
SESSION_UNITS="351mp.service cpu-governor.service dmc-governor.service gpu-freq.service \
gamma-persist.service batt_led.service batteryplus.service fix-power-led.service \
rotate-boot-logo.service es-status-daemon.service fn-menu-watcher.service \
audiopath.service audiostate.service ogage.service emulationstation.service"

# Install a minimal session target + per-unit drop-ins into the (writable overlay) rootfs.
# dArkOS's units were written for a full systemd boot: the governors Require
# systemd-udev-settle, several order After local-fs.target / multi-user.target /
# network.target, console-setup RequiresMountsFor=/usr. Pulling any of that reconstructs
# dArkOS's whole boot inside the container, where sysinit.target hangs (ldconfig /
# local-fs / the /etc,/var overlay mounts) and the container udevd fights Android's
# ueventd over the shared uevent socket. So each unit gets EVERY dependency directive
# reset (DefaultDependencies=no + empty After/Requires/Wants/...): the ExecStart scripts
# themselves only write a sysfs value or start a daemon and need none of that ordering.
# We re-add just the one ordering that matters - ogage after ES - and boot this target
# directly, so the allowlisted units are the only thing that runs.
install_session_units() {
  local d u source
  mkdir -p "$ROOT/etc/systemd/system"
  { echo "[Unit]"; echo "Description=andr36oid subsystem session"
    echo "DefaultDependencies=no"; echo "Wants=$SESSION_UNITS"; } \
    > "$ROOT/etc/systemd/system/andr36oid-session.target"
  for u in $SESSION_UNITS; do
    d="$ROOT/etc/systemd/system/$u.d"; mkdir -p "$d"
    printf '[Unit]\nDefaultDependencies=no\nAfter=\nBefore=\nRequires=\nRequisite=\nWants=\nBindsTo=\nPartOf=\nConflicts=\nRequiresMountsFor=\n' > "$d/00-detach.conf"
  done
  printf '[Unit]\nAfter=emulationstation.service\n' \
    > "$ROOT/etc/systemd/system/ogage.service.d/10-order.conf"
  for u in audiopath.service audiostate.service ogage.service; do
    printf '[Unit]\nConditionPathExists=/run/.a36-handoff\nConditionPathExists=!/run/.a36-muted\n' \
      > "$ROOT/etc/systemd/system/$u.d/20-audio-policy.conf"
  done
  # 351mp also restores Playback Path, alongside unrelated LED/brightness work.
  # Replay its original commands, gating only fixvolume (including its args).
  mkdir -p "$ROOT/usr/local/libexec" || return 1
  cat > "$ROOT/usr/local/libexec/a36-session-fixvolume" <<'AUDIO' || return 1
#!/bin/sh
[ -f /run/.a36-handoff ] && [ ! -f /run/.a36-muted ] || exit 0
exec /usr/local/bin/fixvolume.sh "$@"
AUDIO
  chmod 0755 "$ROOT/usr/local/libexec/a36-session-fixvolume" || return 1
  for u in /etc/systemd/system/351mp.service /usr/lib/systemd/system/351mp.service /lib/systemd/system/351mp.service; do
    source=$(root_path "$ROOT" "$u") || return 1
    [ -f "$source" ] || continue
    { printf '[Service]\nExecStart=\n'
      awk '/^ExecStart=/ {
        sub(/^ExecStart=\/usr\/local\/bin\/fixvolume[.]sh([[:space:]]|$)/,
            "ExecStart=/usr/local/libexec/a36-session-fixvolume ")
        print
      }' "$source"
    } > "$ROOT/etc/systemd/system/351mp.service.d/20-audio-policy.conf" || return 1
    break
  done
}

# B1: boot dArkOSen's own init (systemd) as PID1 in a new PID namespace, so the session
# comes up the way dArkOS expects - ogage (controller), audio, governors, gamma, the
# EmulationStation service as the ark user - instead of us hand-starting pieces. Needs
# CONFIG_PID_NS in the kernel (added alongside CONFIG_BTRFS_FS). toybox unshare has no
# --mount-proc, so we mount a fresh /proc for the new pidns by hand before chroot. We
# boot systemd.unit=andr36oid-session.target (see install_session_units) rather than the
# default (multi-user) target, which would drag in the full, container-incompatible boot.
# The container stays up until the user picks "Shutdown" in dArkOS (systemd exits ->
# this call returns -> the EXIT trap restores Android); "Restart" re-execs and stays.
boot_subsystem_session() {
  has_init "$ROOT" || { log "rootfs has no init"; return 1; }
  set -- "$INIT"
  if root_file "$ROOT" /usr/lib/systemd/systemd || root_file "$ROOT" /lib/systemd/systemd; then
    if [ -d "$ROOT/usr/bin/emulationstation" ]; then
      install_session_units || return 1
      set -- "$@" systemd.unit=andr36oid-session.target
    fi
    set -- "$@" systemd.log_target=console systemd.log_level=info systemd.show_status=true
    mkdir -p "$ROOT/run/systemd/system.conf.d" "$ROOT/run/systemd/journald.conf.d"
    {
      printf '[Manager]\nDefaultEnvironment=ALSA_TMPDIR=/run/alsa TMPDIR=/tmp'
      [ "$HANDOFF" = 0 ] && printf ' SDL_VIDEODRIVER=dummy SDL_KMSDRM_REQUIRE_DRM_MASTER=0'
      printf '\nDefaultStandardOutput=journal+console\nDefaultStandardError=journal+console\n'
    } > "$ROOT/run/systemd/system.conf.d/andr36oid.conf"
    printf '[Journal]\nForwardToConsole=yes\nTTYPath=/dev/console\n' > "$ROOT/run/systemd/journald.conf.d/andr36oid.conf"
  fi
  # The rootfs runs in a container: it has no block devices to mount, so an empty
  # fstab keeps init's "mount -a" from erroring on a missing/stale fstab.
  : > "$ROOT/etc/fstab" 2>/dev/null || true
  log "boot rootfs init $INIT (PID namespace; console -> $STATE/boot.log)"
  # Never create a network namespace. Make mounts slave before replacing /proc
  # so the child's proc mount cannot propagate back into Android/the broker.
  # Leave /run intact: it contains network masks and ALSA/runtime preparation.
  container=andr36oid-subsystem HOME=/root TERM=linux ALSA_TMPDIR=/run/alsa TMPDIR=/tmp \
  unshare -f -p -m -u /system/bin/sh -c '
    root=$1; chroot_cmd=$2; shift 2
    mount -o rslave / || exit 1
    umount "$root/proc" || exit 1
    mount -t proc proc "$root/proc" || exit 1
    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    exec "$chroot_cmd" "$root" "$@"
  ' subsystem-init "$ROOT" "$CHROOT" "$@"
}

# Proven fallback (the 60fps ES bring-up): run dArkOSen's emulationstation.sh wrapper
# directly (it does the uinput chmod, starts gptokeyb, sets XDG_RUNTIME_DIR and loops
# ES) without systemd. Kept selectable so the console still has a working frontend if
# the systemd path needs more bring-up work on a given image.
# dArkOS's ogage daemon reads the gamepad and the volume keys and drives amixer
# for volume, plus the brightness, mute, speaker, Wi-Fi and performance button
# combos. The systemd session runs it as a unit; the wrapper and bare paths exec
# EmulationStation directly, so without this the volume buttons and every hotkey
# are dead. Start it in the background inside the subsystem, after a short settle
# like its own ExecStartPre. It needs no tracking: kill_subsystem reaps every
# process rooted in the chroot when the session ends, so it never outlives the
# session to drive Android's mixer.
# Night-mute: when persist.andr36oid.subsystem.mute=1 the subsystem must stay silent
# (the ES speaker is loud by default and someone is asleep nearby). We mute at the
# hardware codec, which is downstream of whatever ES/RetroArch/a port decides to play,
# so it holds no matter the source. We also do NOT start ogage in this mode, so nothing
# restores a saved volume and there is no start-up blip; a 1s watchdog re-asserts the
# mute in case an app pokes the mixer directly.
# Night-mute: hold the shared codec silent while a handoff session runs (the ES speaker is
# loud by default and someone may be asleep nearby). The subsystem's own amixer is
# unreliable here ("Mixer attach default error"), so we drive card 0 from the broker side
# with tinymix, which the shell domain can use. The control names are the RK817-style ones
# these clones carry. Playback Path is required; absent volume aliases are optional,
# but every advertised alias must accept zero and read back zero on every pass.
# An unsupported codec must fail closed rather than admit an audible payload.
MUTE_PIDFILE="$STATE/mute-guard.pid"
subsystem_muted() { [ "$(getprop persist.andr36oid.subsystem.mute 2>/dev/null)" = 1 ]; }
mute_all_once() {
  local control value failed=0
  for control in "Playback Path" "Master Playback Volume" "Playback Volume"; do
    if [ "$control" != "Playback Path" ]; then
      grep -Eq "^[0-9]+[[:space:]]+[A-Z]+[[:space:]]+[0-9]+[[:space:]]+${control}[[:space:]]" \
        "$STATE/mute-guard.controls" || continue
    fi
    if ! tinymix -D 0 "$control" 0 > /dev/null 2> "$STATE/mute-guard.error"; then
      log "NIGHT MUTE: write failed for $control: $(cat "$STATE/mute-guard.error")"
      failed=1
      continue
    fi
    if ! tinymix -D 0 -v "$control" > "$STATE/mute-guard.value" 2> "$STATE/mute-guard.error"; then
      log "NIGHT MUTE: readback failed for $control: $(cat "$STATE/mute-guard.error")"
      failed=1
      continue
    fi
    value=$(cat "$STATE/mute-guard.value")
    if [ "$control" = "Playback Path" ]; then
      [ "$value" = OFF ] && continue
    elif printf '%s\n' "$value" | grep -Eq '^[[:space:]]*0([[:space:]]+0)*[[:space:]]*$'; then
      continue
    fi
    log "NIGHT MUTE: unsafe readback for $control: $value"
    failed=1
  done
  return "$failed"
}
# Boot ID plus /proc start time protects recovery from stale/recycled PIDs.
process_start_time() {
  local stat
  stat=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
  printf '%s\n' "${stat##*) }" | awk '{print $20}'
}
recorded_process_alive() {
  [ "$3" = "$(cat /proc/sys/kernel/random/boot_id)" ] || return 1
  case "$1:$2" in *[!0-9:]*|:*|*:) return 1 ;; esac
  [ "$(process_start_time "$1")" = "$2" ] && kill -0 "$1" 2>/dev/null || return 1
  case "$(cat "/proc/$1/stat" 2>/dev/null)" in *') Z '*|*') X '*|'') return 1 ;; esac
}
mute_guard_owned() {
  [ "$(cat "$STATE/mute-guard.owner" 2>/dev/null)" = "$owner" ] &&
    [ "$(cat "$STATE/session.lock/pid" 2>/dev/null)" = "$owner_pid" ] &&
    recorded_process_alive "$owner_pid" "$owner_start" "$owner_boot" || return 1
  case "$(cat "/proc/$owner_pid/stat" 2>/dev/null)" in *') Z '*|*') X '*|'') return 1 ;; esac
}
# A mixer stalled during TERM must not complete a late mute after restoration.
# Freeze each parent before walking its children, so it cannot spawn another
# mixer/frontend between the sweep and its own termination.
freeze_process() {
  local pid="$1" stamp="$2"
  [ "$(process_start_time "$pid")" = "$stamp" ] || return 1
  kill -STOP "$pid" 2>/dev/null || return 1
  while [ "$(process_start_time "$pid")" = "$stamp" ]; do
    case "$(cat "/proc/$pid/stat" 2>/dev/null)" in
      *') T '*|*') t '*) return 0 ;;
      *') Z '*|*') X '*|'') return 1 ;;
    esac
    sleep 0.1
  done
  return 1
}
kill_process_children() {
  local parent="$1" p stat child stamp
  for p in /proc/[0-9]*; do
    stat=$(cat "$p/stat" 2>/dev/null) || continue
    # Kernel stat fields after comm contain no glob characters.
    # shellcheck disable=SC2086
    set -- ${stat##*) }
    [ "${2:-}" = "$parent" ] || continue
    child=${p#/proc/}
    stamp=${20}
    if freeze_process "$child" "$stamp"; then
      kill_process_children "$child"
      kill -9 "$child" 2>/dev/null || :
      while [ "$(process_start_time "$child")" = "$stamp" ]; do
        case "$(cat "/proc/$child/stat" 2>/dev/null)" in *') Z '*|*') X '*|'') break ;; esac
        sleep 0.1
      done
    fi
  done
}
kill_tracked_tree() {
  local pid="$1" stamp="$2" boot="$3"
  recorded_process_alive "$pid" "$stamp" "$boot" || return 0
  freeze_process "$pid" "$stamp" || return 0
  kill_process_children "$pid"
  recorded_process_alive "$pid" "$stamp" "$boot" && kill -KILL "$pid" 2>/dev/null || :
  # An uninterruptible ioctl must finish before the shared codec is restored.
  while recorded_process_alive "$pid" "$stamp" "$boot"; do sleep 0.1; done
}
stop_mute_guard() {
  local pid stamp boot extra
  rm -f "$STATE/mute-guard.owner"
  if [ -r "$MUTE_PIDFILE" ] && read -r pid stamp boot extra < "$MUTE_PIDFILE" 2>/dev/null &&
     [ -z "$extra" ] && recorded_process_alive "$pid" "$stamp" "$boot"; then
    kill_tracked_tree "$pid" "$stamp" "$boot"
    wait "$pid" 2>/dev/null || :
  fi
  rm -f "$MUTE_PIDFILE" "$MUTE_PIDFILE.tmp" "$STATE/mute-guard.ready" \
    "$STATE/mute-guard.failed" "$STATE/mute-guard.controls" "$STATE/mute-guard.value" "$STATE/mute-guard.error"
}
start_mute_guard() {
  { [ "$HANDOFF" = 1 ] && subsystem_muted; } || return 0
  command -v tinymix >/dev/null 2>&1 || { log "NIGHT MUTE requested but tinymix is missing; refusing session"; return 1; }
  local owner_pid owner_start owner_boot owner pid stamp identity tries=0
  owner_pid=$(cat "$STATE/session.lock/pid") || return 1
  owner_start=$(process_start_time "$owner_pid") || return 1
  owner_boot=$(cat /proc/sys/kernel/random/boot_id) || return 1
  owner="$owner_pid $owner_start $owner_boot"
  stop_mute_guard
  printf '%s\n' "$owner" > "$STATE/mute-guard.owner" || return 1
  (
    # Do not hold the session FIFO (or the saved console descriptors) open.
    exec </dev/null >/dev/null 2>&1 3>&- 4>&-
    trap 'exit 0' HUP INT TERM
    # $$ is the broker's PID in a subshell. Read the worker's actual PID using
    # the shell builtin, and wait for its durable identity before ANY mixer op.
    read -r pid identity < /proc/self/stat
    identity="$pid $(process_start_time "$pid") $owner_boot"
    while mute_guard_owned; do
      [ "$(cat "$MUTE_PIDFILE" 2>/dev/null)" != "$identity" ] || break
      tries=$((tries+1)); [ "$tries" -lt 100 ] || exit 1
      sleep 0.1
    done
    mute_guard_owned || exit 1
    if ! tinymix -D 0 > "$STATE/mute-guard.controls" 2> "$STATE/mute-guard.error"; then
      log "NIGHT MUTE: cannot enumerate mixer controls: $(cat "$STATE/mute-guard.error")"
      : > "$STATE/mute-guard.failed"; exit 1
    fi
    if ! mute_all_once; then : > "$STATE/mute-guard.failed"; exit 1; fi
    # Optional hardware-master setup also touches the shared codec. Keep it in
    # this recorded worker, with the route already OFF, then verify mute again
    # because the setup sets a default master volume.
    enable_hardware_master
    if ! mute_all_once; then : > "$STATE/mute-guard.failed"; exit 1; fi
    mute_guard_owned || exit 1
    printf '%s\n' "$identity" > "$STATE/mute-guard.ready" || exit 1
    tries=0
    while mute_guard_owned; do
      sleep 1
      mute_guard_owned || break
      if mute_all_once; then tries=0; else tries=$((tries+1)); fi
      if [ "$tries" -ge 3 ]; then
        log "NIGHT MUTE: enforcement failed three times; ending session"
        : > "$STATE/mute-guard.failed"
        : > "$ROOT/run/.a36-session-exit"
        kill_subsystem_payload
        exit 1
      fi
    done
  ) &
  pid=$!
  if ! stamp=$(process_start_time "$pid") ||
     ! printf '%s %s %s\n' "$pid" "$stamp" "$owner_boot" > "$MUTE_PIDFILE.tmp" ||
     ! mv -f "$MUTE_PIDFILE.tmp" "$MUTE_PIDFILE"; then
    kill "$pid" 2>/dev/null || :; wait "$pid" 2>/dev/null || :
    stop_mute_guard; return 1
  fi
  identity="$pid $stamp $owner_boot"
  while mute_guard_owned && recorded_process_alive "$pid" "$stamp" "$owner_boot"; do
    [ ! -f "$STATE/mute-guard.failed" ] || break
    if [ "$(cat "$STATE/mute-guard.ready" 2>/dev/null)" = "$identity" ]; then
      log "NIGHT MUTE on: verified codec silence; watchdog owns all mixer operations"
      return 0
    fi
    tries=$((tries+1)); [ "$tries" -lt 100 ] || break
    sleep 0.1
  done
  log "NIGHT MUTE: initial silence could not be verified; refusing session"
  stop_mute_guard
  return 1
}

# Prepare policy after /run is mounted, before any session daemon or frontend.
# The same boundary covers wrapper, bare, standalone ports and real init.
prepare_session_policy() {
  rm -f "$ROOT/run/.a36-handoff" "$ROOT/run/.a36-muted" \
    "$ROOT/run/.a36-session-exit" "$ROOT/run/.a36-session-reboot" \
    "$ROOT/run/.a36-es-stopped" "$ROOT/run/.a36-es-start" "$ROOT/run/.a36-es-restart"
  [ "$HANDOFF" != 1 ] || : > "$ROOT/run/.a36-handoff"
  if subsystem_muted; then : > "$ROOT/run/.a36-muted"; fi
  if session_uses_init; then
    echo systemd > "$ROOT/run/.a36-session-manager"
  else
    echo wrapper > "$ROOT/run/.a36-session-manager"
  fi
  install_systemctl_shim
}

# A marker must interrupt the foreground payload, including a standalone port or
# the wrapper's infinite RetroArch boot loop. Keep this watcher outside the chroot.
watch_session_markers() {
  local payload="$1" maintenance="${2:-0}" stamp boot
  exec </dev/null >/dev/null 2>&1 3>&- 4>&-
  stamp=$(process_start_time "$payload") || return 0
  boot=$(cat /proc/sys/kernel/random/boot_id)
  while :; do
    if [ -f "$ROOT/run/.a36-session-exit" ] || [ -f "$ROOT/run/.a36-session-reboot" ]; then
      kill -KILL "$payload" 2>/dev/null
      kill_subsystem_payload
      return 0
    fi
    if [ "$maintenance" = 1 ] &&
       { [ -f "$ROOT/run/.a36-es-stopped" ] || [ -f "$ROOT/run/.a36-es-start" ] ||
         [ -f "$ROOT/run/.a36-es-restart" ]; }; then
      # Stop the tracked wrapper and its actual frontend (including RA), while
      # leaving the session and unrelated maintenance/hotkey processes alive.
      kill_tracked_tree "$payload" "$stamp" "$boot"
      return 0
    fi
    recorded_process_alive "$payload" "$stamp" "$boot" || return 0
    sleep 0.1
  done
}
run_session_payload() {
  local payload watcher rc
  "$@" & payload=$!
  watch_session_markers "$payload" "${FRONTEND_MAINTENANCE:-0}" & watcher=$!
  wait "$payload"; rc=$?
  if [ ! -f "$ROOT/run/.a36-session-exit" ] && [ ! -f "$ROOT/run/.a36-session-reboot" ] &&
     { [ "${FRONTEND_MAINTENANCE:-0}" != 1 ] ||
       { [ ! -f "$ROOT/run/.a36-es-stopped" ] && [ ! -f "$ROOT/run/.a36-es-start" ] &&
         [ ! -f "$ROOT/run/.a36-es-restart" ]; }; }; then
    kill "$watcher" 2>/dev/null || :
  fi
  wait "$watcher" 2>/dev/null || :
  return "$rc"
}

start_hotkeys() {
  # Only a full handoff gives Linux the device and its audio. Headless (Android
  # still on screen) must not start ogage: it would drive card 0, Android's own
  # mixer, and fight the running Android session.
  [ "$HANDOFF" = 1 ] || return 0
  if subsystem_muted; then
    log "NIGHT MUTE: not starting ogage, so nothing un-mutes the codec"
    return 0
  fi
  if ! root_file "$ROOT" /usr/local/bin/ogage; then
    log "rootfs has no ogage: volume buttons and the hotkey combos will not work"
    return 0
  fi
  log "starting the dArkOS hotkey daemon (ogage) for volume and button combos"
  run_in_subsystem /bin/sh -lc 'sleep 3; exec /usr/local/bin/ogage' >>"$SESSION_OUT" 2>&1 &
}

run_es_wrapper() {
  find_es "$ROOT" || { log "rootfs has no EmulationStation target"; return 1; }
  start_hotkeys
  # dArkOS leans on systemd to keep ES alive across a crash or a settings-driven
  # restart; we have none, so re-launch ES ourselves when its wrapper exits. Only an
  # explicit power-off/suspend (-> /run/.a36-session-exit via the systemctl shim), a
  # clean quit, or a crash-loop ends the session; a reboot/restart re-launches. Bound
  # the rate so a pathological crash cannot trap the user inside the subsystem.
  local rc tries=0 window now
  window=$(date +%s 2>/dev/null) || window=0
  while :; do
    log "launch EmulationStation via $ES (log -> $SESSION_OUT)"
    if root_file "$ROOT" /bin/sh; then
      FRONTEND_MAINTENANCE=1 XDG_RUNTIME_DIR=/run/user/0 run_session_payload run_in_subsystem /bin/sh -lc 'exec "$1"' subsystem-es "$ES"
    else
      FRONTEND_MAINTENANCE=1 XDG_RUNTIME_DIR=/run/user/0 run_session_payload run_in_subsystem "$ES"
    fi
    rc=$?
    # A stop request precedes finish.sh's unbounded sync, or maintenance. Wait
    # for its explicit next action instead of guessing how long it might take.
    while [ -f "$ROOT/run/.a36-es-stopped" ]; do
      { [ -f "$ROOT/run/.a36-session-exit" ] || [ -f "$ROOT/run/.a36-session-reboot" ]; } && break
      session_active || break
      sleep 0.1
    done
    if [ -f "$ROOT/run/.a36-session-exit" ]; then
      rm -f "$ROOT/run/.a36-session-exit"; log "power off from subsystem; returning to Android"; break
    fi
    if [ -f "$ROOT/run/.a36-session-reboot" ]; then
      kill_subsystem_payload
      rm -f "$ROOT/run/.a36-session-reboot" "$ROOT/run/.a36-es-stopped" "$ROOT/run/.a36-es-start" "$ROOT/run/.a36-es-restart"
      log "restart from subsystem; relaunching EmulationStation"
      start_hotkeys
      continue
    fi
    session_active || break
    if [ -f "$ROOT/run/.a36-es-start" ] || [ -f "$ROOT/run/.a36-es-restart" ]; then
      rm -f "$ROOT/run/.a36-es-start" "$ROOT/run/.a36-es-restart"; continue
    fi
    [ "${rc:-1}" = 0 ] && { log "EmulationStation exited cleanly; returning to Android"; break; }
    now=$(date +%s 2>/dev/null) || now=0
    [ $((now - window)) -gt 60 ] && { tries=0; window=$now; }
    tries=$((tries+1))
    [ "$tries" -ge 3 ] && { log "EmulationStation exited repeatedly; returning to Android"; break; }
    log "EmulationStation exited unexpectedly (rc=$rc); relaunching (attempt $tries)"
  done
}

scan_images() {
  local selected
  selected=$(getprop sys.andr36oid.subsystem.rootfs)
  [ -n "$selected" ] && IMAGE="$selected"
  if detect_image && [ -f "$IMAGE" ]; then
    setprop sys.andr36oid.subsystem.image 1
  else
    setprop sys.andr36oid.subsystem.image 0
  fi
  update_settings_entry org.andr36oid.subsystemsetup
}

# Boot receivers also run after a scan or session, so Settings need not wait
# for another reboot. A stopped Android framework will refresh them at boot.
update_settings_entry() {
  /system/bin/am broadcast --user 0 -a "$1.UPDATE_SETTINGS" -n "$1/.BootReceiver" \
    >/dev/null 2>&1 || true
}

boot_decision() {
  # /dev is tmpfs: mkdir is an atomic once-per-boot guard, also across framework
  # restarts that set boot_completed again. Never switch an existing session.
  mkdir "${A36_BOOT_GUARD:-/dev/.andr36oid-subsystem-boot}" 2>/dev/null || return 0
  if session_active || [ "$(getprop sys.andr36oid.subsystem.session)" = running ] ||
    [ "$(getprop init.svc.andr36oid-subsystem)" = running ]; then return 0; fi
  local effective target held=0
  effective=$(getprop persist.andr36oid.subsystem.default)
  [ "$effective" = subsystem ] || effective=android
  target=$(getprop persist.andr36oid.subsystem.default_target)
  [ "$target" = PM ] || target=ES
  if "$KEYHELD"; then
    held=1
    if [ "$effective" = android ]; then effective=subsystem; else effective=android; fi
  fi
  log "boot selection: START=$held effective=$effective target=$target"
  scan_images
  if [ "$effective" = subsystem ]; then
    setprop sys.andr36oid.subsystem.boot_pending 1
    setprop sys.andr36oid.subsystem.rootfs ""
    setprop sys.andr36oid.subsystem.handoff 1
    setprop sys.andr36oid.subsystem.port "$target"
    setprop sys.andr36oid.subsystem.request run
  fi
}

resolve_run_target() {
  local c
  if [ "${IS_PM:-0}" = 1 ]; then
    for c in /roms/tools/PortMaster.sh /roms/tools/PortMaster/PortMaster.sh /usr/bin/portmaster; do
      root_file "$ROOT" "$c" && { arg="$c"; break; }
    done
  fi
  # Daijisho strips the .sh from names; resolve it after binding the games card.
  if ! root_file "$ROOT" "$arg" && root_file "$ROOT" "$arg.sh"; then arg="$arg.sh"; fi
  root_file "$ROOT" "$arg" || { log "launch target missing: $arg"; return 1; }
}

run_session() {
  case "$cmd" in
    start)
      if [ "$HANDOFF" = 0 ] && has_init "$ROOT"; then
        run_session_payload boot_subsystem_session
      elif ! find_es "$ROOT"; then
        run_session_payload boot_subsystem_session
      else
        case "$SESSION_MODE" in
          systemd) run_session_payload boot_subsystem_session ;;
          wrapper) run_es_wrapper ;;
          bare)
            root_file "$ROOT" /usr/bin/emulationstation/emulationstation && ES=/usr/bin/emulationstation/emulationstation
            start_hotkeys
            run_session_payload run_in_subsystem "$ES" ;;
          *) log "invalid session mode: $SESSION_MODE"; return 2 ;;
        esac
      fi ;;
    run)
      log "launch port $arg (log -> $SESSION_OUT)"
      # PortMaster and the ports are frontends too: start ogage so volume and the
      # hotkey combos work while they run (handoff only; see start_hotkeys).
      start_hotkeys
      # Positional arguments keep names containing quotes/spaces literal. Generic
      # rootfs images need not ship dArkOS's optional governor tools or sudo.
      run_session_payload run_in_subsystem /bin/sh -c '
        cd "$(dirname "$1")" || exit 1
        if [ "$2" = 1 ] && command -v perfmax >/dev/null 2>&1; then perfmax performance; fi
        nice -n -19 "$1"; rc=$?
        if [ "$2" = 1 ] && command -v perfnorm >/dev/null 2>&1; then perfnorm; fi
        exit "$rc"
      ' subsystem-port "$arg" "$HANDOFF" ;;
  esac
}

# The session is busy during preparation too. Record the first boot only after
# setup succeeds and the frontend binary is running inside this rootfs.
mark_frontend_booted() {
  [ "$(getprop persist.andr36oid.subsystem.booted)" = 1 ] && return 0
  [ "$cmd" = start ] || return 0
  local p root exe
  while kill -0 "$SESSION_PID" 2>/dev/null; do
    for p in /proc/[0-9]*; do
      root=$(readlink "$p/root" 2>/dev/null) || continue
      [ "$root" = "$ROOT" ] || continue
      exe=$(readlink "$p/exe" 2>/dev/null) || continue
      case "$exe" in
        */emulationstation|*/EmulationStation)
          setprop persist.andr36oid.subsystem.booted 1
          return 0 ;;
      esac
    done
    sleep 1
  done
}

cmd=${1:-start}; arg=${2:-}
case "$cmd" in
  boot-decision) boot_decision; exit 0 ;;
  scan-prop)
    [ "$(getprop sys.andr36oid.subsystem.request)" = scan ] && setprop sys.andr36oid.subsystem.request ""
    scan_images; exit 0 ;;
  scan) scan_images; exit 0 ;;
  stop-prop)
    [ "$(getprop sys.andr36oid.subsystem.request)" = stop ] && setprop sys.andr36oid.subsystem.request ""
    stop_session; exit 0 ;;
  stop) stop_session; exit 0 ;;
  recover) recover_session "$arg"; exit 0 ;;
  launch-prop)
    req=$(getprop sys.andr36oid.subsystem.request)
    [ "$req" = run ] || exit 0
    want=$(getprop sys.andr36oid.subsystem.port)
    IMAGE=$(getprop sys.andr36oid.subsystem.rootfs)
    HANDOFF=$(getprop sys.andr36oid.subsystem.handoff); HANDOFF=${HANDOFF:-1}
    setprop sys.andr36oid.subsystem.request ""
    case "$want" in
      ES|OS|"") cmd=start ;;
      INIT) cmd=start; SESSION_MODE=systemd ;;
      PM) cmd=run; arg=/roms/tools/PortMaster.sh; IS_PM=1 ;;
      /*) cmd=run; arg="$want" ;;
      *) cmd=run; arg="/roms/ports/$want" ;;
    esac ;;
  start|run) : ;;
  *) echo "usage: $0 start | run <port.sh> | stop | scan | boot-decision" >&2; exit 2 ;;
esac

if ! mkdir "$STATE/session.lock" 2>/dev/null; then
  if session_active || [ ! -f "$STATE/session.lock/pid" ]; then
    log "session already running/preparing; ignoring launch"; exit 1
  fi
  recover_session "$(cat "$STATE/session.lock/pid")"
  mkdir "$STATE/session.lock" 2>/dev/null || exit 1
fi
printf '%s\n' "$$" > "$STATE/session.lock/pid"
cat /proc/sys/kernel/random/boot_id > "$STATE/session.lock/boot_id"
SESSION_OWNED=1
: > "$STATE/session.mounts"
trap 'finish_session $?' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP
: > "$LOG"
SESSION_OUT="$STATE/session.out"
start_live_log || exit 1
setprop sys.andr36oid.subsystem.provisioning "" 2>/dev/null
setprop sys.andr36oid.subsystem.session running
log "subsystem $cmd arg=$arg handoff=$HANDOFF image=${IMAGE:-default}"
case "$HANDOFF" in 0|1) : ;; *) log "handoff must be 0 or 1"; exit 2 ;; esac
case "$IMAGE" in ''|/*) : ;; *) log "rootfs image path must be absolute"; exit 2 ;; esac
arm_guardian
mount_rootfs || { log "cannot prepare eligible rootfs"; exit 1; }
fix_exec_bits
find_es "$ROOT" && prime_input
bind_core || { log "core mounts failed"; exit 1; }
mount_storage || { log "games storage preparation failed"; exit 1; }
provision_darkos || exit 1
prime_storage_input
eligible_rootfs "$ROOT" || { log "rootfs has no init or launch target"; exit 1; }
[ "$cmd" = run ] && { resolve_run_target || exit 1; }
prepare_network || { log "network preparation failed"; exit 1; }
prep_darkos_env || { log "runtime/audio preparation failed"; exit 1; }
prime_udev
prepare_session_policy || exit 1
if [ "$HANDOFF" = 1 ]; then handoff; else log "no handoff: Android stays up; display devices hidden"; fi
# Muted handoffs perform this setup inside the recorded worker as well.
if ! { [ "$HANDOFF" = 1 ] && subsystem_muted; }; then enable_hardware_master; fi
start_mute_guard || exit 1
# Wait on a child so TERM interrupts wait immediately, including while init runs.
run_session & SESSION_PID=$!
mark_frontend_booted
wait "$SESSION_PID"; session_rc=$?
exit "$session_rc"
