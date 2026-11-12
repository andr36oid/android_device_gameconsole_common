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
IFS=' ' read -r BROKER_ENTER_UPTIME BROKER_IDLE_SECONDS < /proc/uptime
umask 022

# --- configuration -----------------------------------------------------------
STATE=${A36_STATE:-/data/local/tmp/portmaster}
export A36_LOOP_JOURNAL="$STATE/session.guest-loops"
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
  local line seconds idle
  IFS=' ' read -r seconds idle < /proc/uptime
  line="${seconds%.*}s $*"
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
  printf '%s\n' "$BOUND" > "$STATE/session.mounts.tmp" &&
    mv -f "$STATE/session.mounts.tmp" "$STATE/session.mounts"
}
forget_mount() {
  BOUND=$(printf '%s\n' "$BOUND" | awk -v p="$1" '$0!=p')
  printf '%s\n' "$BOUND" > "$STATE/session.mounts.tmp" &&
    mv -f "$STATE/session.mounts.tmp" "$STATE/session.mounts"
}
bind_mount() { mount -o bind "$1" "$2" && track_mount "$2"; }

# True if the top mount at a path carries noexec in /proc/mounts. Mount points
# here are space-free under $ROOT, so a plain field match is safe; the last
# matching line wins (the topmost mount after a remount,exec,bind).
mount_is_noexec() {
  awk -v mp="$1" '$2==mp{o=$4} END{exit (o ~ /(^|,)noexec(,|$)/)?0:1}' /proc/mounts
}
# Wait (at most $1 seconds) until Android has mounted the games card that
# andr36oid-easyroms resolved. Returns at once when there is no such card.
wait_games_card_mount() {
  local id n=0 dev dir rest
  id=$(getprop sys.andr36oid.easyroms.id)
  [ -n "$id" ] || return 0
  while [ "$n" -lt "$1" ]; do
    while read -r dev dir rest; do
      [ "$dev" = "/dev/block/vold/$id" ] || continue
      case "$dir" in /mnt/media_rw/*) return 0;; esac
    done < /proc/mounts
    sleep 1; n=$((n+1))
  done
  return 1
}
# Drop noexec from one bind mount. toybox 0.8.3 fails this bind remount with
# EINVAL on the 5.10 kernel (it worked on 4.19), so fall back to the guest
# helper, which issues the plain MS_BIND|MS_REMOUNT syscall.
remount_exec() {
  local err
  err=$(mount -o remount,exec,bind "$1" 2>&1)
  mount_is_noexec "$1" || return 0
  "${GUEST:-/system/bin/andr36oid-guest}" --remount-exec "$1" 2>&1 && ! mount_is_noexec "$1" && return 0
  [ -z "$err" ] || printf '%s\n' "$err"
  return 1
}
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
# Record before attachment; no recovery path may detach a bare device name.
record_session_loop() {
  case "$1:$2:$3" in *[!0-9:]*|:*|*::*|*:) return 1 ;; esac
  printf '%s %s %s %s\n' "$LOOPDEV" "$1" "$2" "$3" > "$STATE/session.loop.tmp" &&
    mv -f "$STATE/session.loop.tmp" "$STATE/session.loop"
}
release_session_loop() {
  local loop dev ino offset extra
  [ -s "$STATE/session.loop" ] || { LOOPDEV=; return 0; }
  read -r loop dev ino offset extra < "$STATE/session.loop" || return 1
  case "$loop" in /dev/loop[0-9]*|/dev/block/loop[0-9]*) ;; *) return 1 ;; esac
  case "$dev:$ino:$offset" in *[!0-9:]*|:*|*::*|*:) log "legacy/invalid loop identity; refusing detach"; return 1 ;; esac
  [ -z "$extra" ] || return 1
  # The helper checks identity and detaches through the same open loop FD.
  # Already-detached and recycled associations are safe to forget; I/O errors
  # retain the journal. Never fall back to losetup -d on a bare name.
  "$SUPPORT" retire-owned "$loop" "$dev" "$ino" "$offset" || return 1
  rm -f "$STATE/session.loop" || return 1
  LOOPDEV=
}
mount_generic_rootfs() {
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
    record_session_loop "$(stat -c %d "$IMAGE")" "$(stat -c %i "$IMAGE")" "$off" || return 1
    # No -S: this device's toybox truncates large sizelimits to 32 bits.
    if loop_attach_ro "$LOOPDEV" "$IMAGE" "$off"; then
      local loop_check dev_check ino_check off_check
      read -r loop_check dev_check ino_check off_check < "$STATE/session.loop" || return 1
      "$SUPPORT" loop-owned "$LOOPDEV" "$dev_check" "$ino_check" "$off_check" || return 1
      if mount -t "$fs" -o ro "$LOOPDEV" "$LOWER"; then
        track_mount "$LOWER" || return 1
        if eligible_rootfs "$LOWER"; then
          mount -t overlay overlay -o "lowerdir=$LOWER,upperdir=$UPPER,workdir=$WORK" "$ROOT" || return 1
          track_mount "$ROOT" || return 1; MOUNTED=1
          log "mounted eligible $fs rootfs at byte $off (ro + overlay) at $ROOT"
          return 0
        fi
        umount "$LOWER" || return 1
        forget_mount "$LOWER"
      fi
    fi
    release_session_loop || return 1
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
  : > "$STATE/roms-map" || return 1
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
  _rerr=$(remount_exec "$ROOT/roms")
  if mount_is_noexec "$ROOT/roms"; then
    log "warning: /roms is noexec (exec remount failed: ${_rerr:-flag stuck, no error}); ports and Tools there may not launch, games still work"
  fi
  log "storage: $src -> /roms"
  ROMS_SOURCE="$src"
  printf '%s\t%s\n' "$src" /roms >> "$STATE/roms-map" || return 1
  n=1
  for src in /mnt/media_rw/*; do
    [ -d "$src" ] || continue
    [ "$src" = "$ROMS_SOURCE" ] && continue
    dst="$ROOT/roms$((n+1))"
    mkdir -p "$dst"
    # bind the vold mount, then drop noexec for this view
    if bind_mount "$src" "$dst"; then
      remount_exec "$dst" >/dev/null
      mount_is_noexec "$dst" && log "warning: ${dst#"$ROOT"} is noexec; ports there may not launch"
      printf '%s\t%s\n' "$src" "${dst#"$ROOT"}" >> "$STATE/roms-map" || return 1
      log "storage: $src -> ${dst#"$ROOT"}"; n=$((n+1))
    fi
  done
  return 0
}

# The stamp is on the games volume, keyed by the rootfs/overlay identity. An
# interrupted or failed merge leaves no stamp; the next launch retries safely.
# Stage on the games filesystem as media_rw. Only completed files are renamed
# into place; a retry discards an incomplete staging tree before starting again.
# dArkOS/ArkOS surfaces the user tools (PortMaster, shader packs, theme tools) in
# EmulationStation's Tools menu by reading /opt/system/Tools, which upstream fills
# with a `/roms/tools -> /opt/system/Tools` bind from its live fstab. We launch ES
# without systemd and blank the guest fstab, so that bind never happens and the
# Tools menu - PortMaster included - comes up empty. Re-create it here, after the
# games card (and its merged tools/) is bound at /roms. Best effort: a tools menu
# that cannot be surfaced must never block the session.
mount_tools_menu() {
  [ -d "$ROOT/roms/tools" ] || return 0
  mkdir -p "$ROOT/opt/system/Tools" 2>/dev/null || return 0
  if bind_mount "$ROOT/roms/tools" "$ROOT/opt/system/Tools"; then
    remount_exec "$ROOT/opt/system/Tools" >/dev/null
    mount_is_noexec "$ROOT/opt/system/Tools" && log "warning: Tools menu is noexec; tools may not launch from ES"
    log "tools: /roms/tools -> /opt/system/Tools (PortMaster + tools in ES menu)"
  else
    log "could not surface /roms/tools in the Tools menu"
  fi
  return 0
}

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
    ! "$GUEST" "$ROOT" generic quiet -- /usr/bin/setpriv --reuid=1023 --regid=1023 --clear-groups /bin/sh -c '
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
  if [ "${SOURCE_TRANSPORT:-file}" != partition ] || [ "${cmd:-}" != setup ]; then
    /system/bin/am start -n org.andr36oid.subsystemsetup/.SubsystemSetupActivity \
      --activity-single-top >/dev/null 2>&1 || true
  fi
  log "Setting up dArkOSen... merging /roms.tar into $ROMS_SOURCE"
  "$GUEST" "$ROOT" generic quiet -- /usr/bin/setpriv --reuid=1023 --regid=1023 --clear-groups /bin/sh -c '
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
    case "$verb" in
      suspend)
        # Short power press = sleep, not exit. Hold the session and leave the
        # frontend running; the broker performs a real SoC suspend-to-RAM and
        # wakes on the power key. dArkOSen's long press sends poweroff instead.
        : > /run/.a36-session-sleep || exit 1
        exit 0 ;;
      reboot)
        : > /run/.a36-session-reboot || exit 1 ;;
      *)
        : > /run/.a36-session-exit || exit 1 ;;
    esac
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
          # Drop unopenable (ENXIO) input nodes so ogage does not unwrap-crash on
          # relaunch; see andr36oid-guest.sh for the full rationale.
          for _n in /dev/input/event*; do [ -e "$_n" ] || continue; ( exec 3<"$_n" ) 2>/dev/null || rm -f "$_n"; done
          PATH=/run/andr36oid-audio-bin:$PATH setsid /usr/local/bin/ogage >/dev/null 2>&1 &
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
    mkdir -p "$dst" || return 1
    local child
    for child in "$src"/*; do
      [ -c "$child" ] || continue
      add_dev "${child#/dev/}" || return 1
    done
    return 0
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
  track_mount "$ROOT/dev" || return 1
  for n in null zero full random urandom tty ptmx; do
    add_dev "$n" || return 1
  done
  if [ "$HANDOFF" = 1 ]; then
    # ion is the Mali GPU buffer allocator on this 4.19 kernel (no /dev/dma_heap):
    # without it EGL/GLES cannot allocate and EmulationStation spins without ever
    # rendering. rga (2D blit) and mpp_service (video codec) are what ports need.
    for n in input snd dri mali0 mali graphics uinput tty0 tty1 ion rga mpp_service; do
      add_dev "$n" || return 1
    done
    [ -e "$ROOT/dev/graphics/fb0" ] && ln -s graphics/fb0 "$ROOT/dev/fb0"
  fi
  # /dev/console: init may reopen it after replacing stdout, so a /proc/self/fd/1
  # symlink would silently follow its new stdout. toybox mount cannot bind the
  # logging FIFO either (a non-directory source loop-mounts and fails), so make a
  # FIFO in the container /dev and forward it into the same log stream.
  if [ "${STAGE_ONLY:-0}" = 1 ]; then
    mkfifo -m 0666 "$ROOT/dev/console" || return 1
  else
    start_session_console || return 1
  fi
  ln -s /proc/self/fd "$ROOT/dev/fd"
  ln -s /proc/self/fd/0 "$ROOT/dev/stdin"
  ln -s /proc/self/fd/1 "$ROOT/dev/stdout"
  ln -s /proc/self/fd/2 "$ROOT/dev/stderr"
  mkdir -p "$ROOT/dev/shm"
  mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "$ROOT/dev/shm" || return 1
  track_mount "$ROOT/dev/shm" || return 1
  bind_mount /proc "$ROOT/proc" || return 1
  mount -t sysfs -o ro,nosuid,nodev,noexec sysfs "$ROOT/sys" || return 1
  track_mount "$ROOT/sys" || return 1
  mkdir -p "$ROOT/dev/pts"
  mount -t devpts -o newinstance,ptmxmode=0666,mode=0620 devpts "$ROOT/dev/pts" || return 1
  track_mount "$ROOT/dev/pts" || return 1
  rm -f "$ROOT/dev/ptmx"; ln -s pts/ptmx "$ROOT/dev/ptmx"
  mount -t tmpfs -o mode=0755,nosuid,nodev tmpfs "$ROOT/run" || return 1
  track_mount "$ROOT/run" || return 1
  mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "$ROOT/tmp" || return 1
  track_mount "$ROOT/tmp" || return 1
}

# Kill everything running inside the subsystem (EmulationStation and whatever it
# launched - an emulator that hangs holds /dev/dri/card0's DRM master, so just
# restarting SurfaceFlinger would not get the display back). Identify them by
# their chroot root pointing into $ROOT. Never kill unrelated DRM clients.
kill_subsystem_payload() {
  "${GUEST:-/system/bin/andr36oid-guest}" --kill-guest-root "$ROOT"
}

kill_subsystem() {
  local survivors
  # SIGKILL is asynchronous. Wait for guest FDs to close, including orphaned
  # descendants discovered by their private root.
  if ! survivors=$("${GUEST:-/system/bin/andr36oid-guest}" --stop-guest-root "$ROOT" 2>&1); then
    log "guest processes did not exit: ${survivors:-no process details}"
    return 1
  fi
}

# --- teardown (idempotent) ----------------------------------------------------
# Host shells keep Android's root, so kill_subsystem's chroot sweep cannot see
# the session runner, its marker watcher, or the console FIFO forwarder. Publish
# their PID/start-time/boot identity before admitting work, also for guardian
# recovery after the original broker has died.
record_session_worker() {
  local role pid stamp boot
  role=$1; pid=$2
  stamp=$(process_start_time "$pid") || return 1
  boot=$(cat /proc/sys/kernel/random/boot_id) || return 1
  printf '%s %s %s\n' "$pid" "$stamp" "$boot" > "$STATE/session.$role.tmp" &&
    mv -f "$STATE/session.$role.tmp" "$STATE/session.$role"
}
await_session_worker() {
  local role pid rest identity tries=0
  role=$1
  read -r pid rest < /proc/self/stat
  identity="$pid $(process_start_time "$pid") $(cat /proc/sys/kernel/random/boot_id)"
  while session_active; do
    [ "$(cat "$STATE/session.$role" 2>/dev/null)" != "$identity" ] || return 0
    tries=$((tries+1)); [ "$tries" -lt 100 ] || return 1
    sleep 0.1
  done
  return 1
}
start_session_console() {
  [ -p "$ROOT/dev/console" ] || mkfifo -m 0666 "$ROOT/dev/console" || return 1
  forward_session_console 2>/dev/null & CONSOLE_FWD=$!
  record_session_worker console "$CONSOLE_FWD"
}
forward_session_console() {
  trap - EXIT HUP INT TERM
  await_session_worker console || return 1
  while [ -p "$ROOT/dev/console" ]; do
    cat "$ROOT/dev/console" || break
  done >> "$STATE/session.fifo"
}
run_session_worker() {
  trap - EXIT HUP INT TERM
  await_session_worker runner || return 1
  run_session
}
stop_session_workers() {
  local role pid stamp boot extra survivors failed=0
  for role in runner console; do
    [ -f "$STATE/session.$role" ] || continue
    if ! read -r pid stamp boot extra < "$STATE/session.$role" || [ -n "$extra" ]; then
      log "invalid $role worker identity; retaining recovery journal"
      failed=1; continue
    fi
    if ! survivors=$("${GUEST:-/system/bin/andr36oid-guest}" --stop-worker-tree "$pid" "$stamp" "$boot" 2>&1); then
      log "$role worker tree did not stop: ${survivors:-no process details}"
      failed=1; continue
    fi
    # The identity check is in the helper. A stale journal must not wait on
    # an unrelated child that happens to reuse the recorded PID.
    log "teardown: $role worker checked"
    rm -f "$STATE/session.$role" "$STATE/session.$role.tmp" || failed=1
  done
  return "$failed"
}

# Explicit ctl.stop zygote skips init's onrestart netd/wificond commands. Bare
# `start` does nothing to an already-running daemon: netd would retain the old
# system_server's netIds/routes while the new ConnectivityService reuses them.
# Reset all Android Wi-Fi owners before starting the new framework, including
# supplicant/HAL interface state. DHCP/IpClient is recreated by system_server;
# supplicant is disabled/oneshot and must be started on demand by that framework.
# A successful reset is journaled so repeated recovery cannot drop live Wi-Fi.
restore_android_network() {
  [ ! -f "$STATE/session.network-restored" ] || return 0
  local service state
  for service in wpa_supplicant vendor.wifi_hal_legacy wificond netd; do
    state=$(getprop "init.svc.$service")
    if [ -z "$state" ]; then
      case "$service" in
        netd|wificond) log "missing required network service: $service"; return 1 ;;
        *) continue ;;
      esac
    fi
    setprop ctl.stop "$service" 2>/dev/null || return 1
    wait_service_stopped "$service" || return 1
  done
  for service in netd wificond vendor.wifi_hal_legacy; do
    state=$(getprop "init.svc.$service")
    [ -n "$state" ] || continue
    setprop ctl.start "$service" 2>/dev/null || return 1
    wait_service_running "$service" || return 1
  done
  : > "$STATE/session.network-restored" || return 1
  log "network: reset Android netd/Wi-Fi owners; framework will restore saved connection and DHCP"
}

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

# Audible sessions have exactly one host route owner. Never enter this worker
# for night mute: its existing OFF/zero watchdog and admission stay independent.
audio_route_target() {
  local model force state
  model=$(tr -d '\000' 2>/dev/null < "${A36_DT_MODEL:-/proc/device-tree/model}") || model=
  force=$(cat "${A36_FORCE_SPK_HP:-/sys/module/snd_soc_rk817/parameters/force_spk_hp}" 2>/dev/null) || force=
  case "$force:$model" in
    Y:*|1:*|*:"R36S clone Soy Sauce V04, Panel 2 (experimental)") echo SPK_HP ;;
    *)
      # Every board with a working jack, clones included: follow the same ALSA
      # jack boolean Android's audio HAL follows. It already includes DT
      # active-low XOR hp_det_flip, so do not invert it again (ogage's historical
      # EV_SW mapping is unsuitable). No readable jack means speaker.
      state=$("$GUEST" --headphone-state 2>/dev/null) || state=
      case "$state" in
        1) echo HP ;;
        *) echo SPK ;;
      esac ;;
  esac
}
audio_route_once() {
  local target current
  target=$(audio_route_target) || return 1
  current=$(tinymix -D 0 -v "Playback Path" 2>/dev/null) || return 1
  if [ "$current" != "$target" ]; then
    tinymix -D 0 "Playback Path" "$target" >/dev/null 2>&1 || return 1
  fi
  current=$(tinymix -D 0 -v "Playback Path" 2>/dev/null) || return 1
  [ "$current" = "$target" ]
}
audio_route_owned() {
  [ "$(cat "$STATE/audio-route.owner" 2>/dev/null)" = "$owner" ] &&
    [ "$(cat "$STATE/session.lock/pid" 2>/dev/null)" = "$owner_pid" ] &&
    recorded_process_alive "$owner_pid" "$owner_start" "$owner_boot"
}
stop_audio_route_guard() {
  local pid stamp boot extra
  rm -f "$STATE/audio-route.owner"
  if [ -r "$STATE/audio-route.pid" ] && read -r pid stamp boot extra < "$STATE/audio-route.pid" &&
     [ -z "$extra" ] && recorded_process_alive "$pid" "$stamp" "$boot"; then
    kill_tracked_tree "$pid" "$stamp" "$boot" || {
      log "audio route worker did not stop; retaining codec snapshots"; return 1;
    }
    wait "$pid" 2>/dev/null || :
  fi
  rm -f "$STATE/audio-route.pid" "$STATE/audio-route.pid.tmp" \
    "$STATE/audio-route.ready" "$STATE/audio-route.failed"
}
start_audio_route_guard() {
  { [ "$HANDOFF" = 1 ] && ! subsystem_muted; } || return 0
  # Snapshot publication and confirmed audio shutdown must precede ALL writes.
  [ -s "$STATE/session.mixer" ] && [ "$DID_HANDOFF" = 1 ] || return 1
  local owner_pid owner_start owner_boot owner pid stamp identity tries=0
  owner_pid=$(cat "$STATE/session.lock/pid") || return 1
  owner_start=$(process_start_time "$owner_pid") || return 1
  owner_boot=$(cat /proc/sys/kernel/random/boot_id) || return 1
  owner="$owner_pid $owner_start $owner_boot"
  stop_audio_route_guard || return 1
  printf '%s\n' "$owner" > "$STATE/audio-route.owner" || return 1
  (
    exec </dev/null >/dev/null 2>&1 3>&- 4>&-
    trap 'exit 0' HUP INT TERM
    read -r pid identity < /proc/self/stat
    identity="$pid $(process_start_time "$pid") $owner_boot"
    while audio_route_owned; do
      [ "$(cat "$STATE/audio-route.pid" 2>/dev/null)" != "$identity" ] || break
      tries=$((tries+1)); [ "$tries" -lt 100 ] || exit 1
      sleep 0.1
    done
    audio_route_owned || exit 1
    enable_hardware_master
    if ! audio_route_once; then : > "$STATE/audio-route.failed"; exit 1; fi
    audio_route_owned || exit 1
    printf '%s\n' "$identity" > "$STATE/audio-route.ready" || exit 1
    tries=0
    while audio_route_owned; do
      sleep 1
      audio_route_owned || break
      if audio_route_once; then tries=0; else tries=$((tries+1)); fi
      if [ "$tries" -ge 3 ]; then
        log "audio route enforcement failed; ending session"
        : > "$STATE/audio-route.failed"
        : > "$ROOT/run/.a36-session-exit"
        kill_subsystem_payload
        exit 1
      fi
    done
  ) &
  pid=$!
  if ! stamp=$(process_start_time "$pid") ||
     ! printf '%s %s %s\n' "$pid" "$stamp" "$owner_boot" > "$STATE/audio-route.pid.tmp" ||
     ! mv -f "$STATE/audio-route.pid.tmp" "$STATE/audio-route.pid"; then
    [ -z "${stamp:-}" ] || kill_tracked_tree "$pid" "$stamp" "$owner_boot" || :
    stop_audio_route_guard; return 1
  fi
  identity="$pid $stamp $owner_boot"
  while audio_route_owned && recorded_process_alive "$pid" "$stamp" "$owner_boot"; do
    [ ! -f "$STATE/audio-route.failed" ] || break
    if [ "$(cat "$STATE/audio-route.ready" 2>/dev/null)" = "$identity" ]; then
      log "host owns audible session routes (jack polling every second)"
      return 0
    fi
    tries=$((tries+1)); [ "$tries" -lt 100 ] || break
    sleep 0.1
  done
  log "could not establish audible session route; refusing session"
  stop_audio_route_guard
  return 1
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

session_uptime_seconds() {
  local uptime rest
  read -r uptime rest < /proc/uptime
  printf '%s\n' "${uptime%%.*}"
}

begin_android_power_return() {
  local now
  now=$(session_uptime_seconds)
  power_deadline=$((now+75))
  # Convert the existing lock in place. The kernel timer also protects an
  # interrupted restore and expires independently if recovery itself dies.
  if { printf 'andr36oid-subsystem 90000000000\n' > "${A36_POWER_DIR:-/sys/power}/wake_lock"; } 2>/dev/null; then
    power_timed=1
  else
    log "teardown: could not arm the session wake lock timeout"
  fi
  printf '%s\n' "$((now+90))" > "$STATE/session.lock/power-return-deadline" ||
    log "teardown: could not journal the Android power return deadline"
}

android_power_return_in_progress() {
  local deadline now
  { read -r deadline < "$STATE/session.lock/power-return-deadline"; } 2>/dev/null || return 1
  case "$deadline" in ''|*[!0-9]*) return 1 ;; esac
  [ "${#deadline}" -le 12 ] || return 1
  now=$(session_uptime_seconds)
  [ "$deadline" -gt "$now" ] && [ "$deadline" -le "$((now+90))" ]
}

finish_android_power_return() {
  local reason="framework restart unavailable" dump
  if [ "$framework_started" = 1 ]; then
    reason="framework power readiness timed out"
    # The boot property can already be 1 from before handoff, and the power
    # Binder is published before systemReady(). Ask the live service for its
    # own boot phase, which also updates power policy and user activity.
    while [ "$(session_uptime_seconds)" -lt "$power_deadline" ]; do
      if [ "$(getprop init.svc.zygote)" = running ] &&
         dump=$(timeout -k 1 2 dumpsys -t 1 power 2>/dev/null) &&
         printf '%s\n' "$dump" | awk '
           /^ *mSystemReady=true$/ {ready=1}
           /^ *mBootCompleted=true$/ {booted=1}
           END {exit !(ready && booted)}'; then
        reason="Android power service ready"
        break
      fi
      sleep 1
    done
  elif [ "$power_timed" = 1 ]; then
    log "teardown: $reason; session wake lock will expire in the kernel"
    return 0
  fi
  if { printf 'andr36oid-subsystem\n' > "${A36_POWER_DIR:-/sys/power}/wake_unlock"; } 2>/dev/null; then
    log "teardown: released session wake lock ($reason)"
  else
    log "teardown: could not release session wake lock ($reason)"
  fi
}

restore_android() {
  [ "$SESSION_OWNED" = 1 ] || return 0
  CLEANUP_FAILED=0
  if [ -f "$STATE/session.restore-complete" ]; then
    PARKED_STAGE=0; [ ! -f "$STATE/prestage.ready" ] || PARKED_STAGE=1
    log "teardown: hardware restore already complete; finishing journal only"
    return 0
  fi
  local codec_unsafe=0 power_deadline=0 power_timed=0 framework_started=0 network_ready=0 network_failed=0
  PARKED_STAGE=0
  case "$(getprop sys.powerctl)" in reboot|reboot,*|shutdown|shutdown,*) FORCE_SHUTDOWN=1;; esac
  if [ "${FORCE_SHUTDOWN:-0}" = 1 ]; then DID_HANDOFF=0; FORCE_UNSTAGE=1; fi
  [ "$DID_HANDOFF" != 1 ] || begin_android_power_return
  [ "$DID_HANDOFF" != 1 ] || start_return_splash
  # Stop live backlight repair before teardown starts restoring Android's state.
  rm -f "$ROOT/run/.a36-handoff"
  # Invalidate ownership and reap the watchdog before touching the shared codec
  # or restarting Android. This path is shared by EXIT and guardian recovery.
  CLEANUP_FAILED=0
  log "teardown: starting Android restore"
  stop_mute_guard || { CLEANUP_FAILED=1; codec_unsafe=1; }
  stop_audio_route_guard || { CLEANUP_FAILED=1; codec_unsafe=1; }
  log "teardown: audio guards checked"
  stop_session_workers || { CLEANUP_FAILED=1; codec_unsafe=1; }
  phase return-guest kill_subsystem || { CLEANUP_FAILED=1; codec_unsafe=1; }
  log "teardown: guest root checked"
  # The native parent normally reclaims these. Its durable ownership journal
  # also covers SIGKILL/guardian recovery; never detach by loop number alone.
  if [ -f "$STATE/session.guest-loops" ]; then
    "${GUEST:-/system/bin/andr36oid-guest}" --cleanup-guest-loops "$ROOT" "$STATE/session.guest-loops" || {
      CLEANUP_FAILED=1; log "guest loop cleanup incomplete; retaining ownership journal"
    }
    log "teardown: guest loop cleanup checked"
  fi
  if [ "$DID_HANDOFF" = 1 ] && [ "$codec_unsafe" = 0 ] && [ "$CLEANUP_FAILED" = 0 ]; then
    if phase return-network restore_android_network; then network_ready=1; else network_failed=1; fi
  fi
  if [ "$CLEANUP_FAILED" = 0 ] && [ "$DID_HANDOFF" = 1 ] && [ -f "$STATE/session.gamma" ]; then
    # Recovery can run after Android has reacquired DRM. Re-establish exclusive
    # display ownership before consuming a retained gamma snapshot.
    stop_android_display || { CLEANUP_FAILED=1; log "pending gamma: display handoff failed"; }
  fi
  if [ "$CLEANUP_FAILED" = 0 ]; then
    phase return-hardware restore_guest_hardware || CLEANUP_FAILED=1
  fi
  if [ "$DID_HANDOFF" = 1 ]; then
    finish_return_splash
    show_splash return || :
  fi
  [ "$network_failed" = 0 ] || CLEANUP_FAILED=1
  if [ "$DID_HANDOFF" = 1 ]; then
    # Bring the display back before potentially slow mount/loop cleanup and
    # framework startup. bootanimation waits for SF's Binder service itself.
    # SF also starts bootanim, but only after HWC/RenderEngine initialization;
    # queue it now so the logo can appear as soon as SF accepts surfaces.
    local sf_state
    sf_state=$(getprop init.svc.surfaceflinger)
    # Android's start message says "Returning to andr36oid" for a start shortly after
    # this stamp (kernel uptime in seconds, suspend included like elapsedRealtime).
    local up
    up=$(cut -d. -f1 /proc/uptime 2>/dev/null)
    case "$up" in ''|*[!0-9]*) ;; *) setprop sys.andr36oid.subsystem.returning "$up" 2>/dev/null ;; esac
    # Optional attended SPEED2 experiment. Only this return animation can skip
    # mandatory outro frames after WMS requests exit; normal boot stays intact.
    if [ "$(getprop persist.andr36oid.subsystem.fast_return)" = 1 ]; then
      setprop sys.andr36oid.subsystem.return_animation 1 2>/dev/null
    else setprop sys.andr36oid.subsystem.return_animation 0 2>/dev/null; fi
    setprop ctl.start "$HWC" 2>/dev/null || CLEANUP_FAILED=1
    sleep 1
    if [ "$sf_state" != running ]; then
      setprop service.bootanim.exit 0 2>/dev/null || CLEANUP_FAILED=1
    fi
    setprop ctl.start surfaceflinger 2>/dev/null || CLEANUP_FAILED=1
    if [ "$sf_state" != running ]; then
      setprop ctl.start bootanim 2>/dev/null || CLEANUP_FAILED=1
      log "return boot animation requested"
    fi
    # Do not reset sys.boot_completed: this is a runtime restart. WMS requests
    # service.bootanim.exit=1 when its windows are drawn, and SF does so at
    # bootFinished too. A repeated recovery with live SF must not clear that
    # exit request and cover an already restored Android with a new animation.
  fi
  if [ "$DID_HANDOFF" = 1 ]; then
    # handoff() stopped joyMouse alongside the framework, but the bare `start`
    # below only revives netd/surfaceflinger/zygote/zygote_secondary. joyMouse is
    # class main with no onrestart hook, so without this it stays dead after a
    # session: FN+X still toggles sys.joymouse.active (PhoneWindowManager rides
    # the restarted zygote) but no daemon drives the cursor. Input is independent
    # of the codec, so restart it even on the audio-withheld recovery path.
    setprop ctl.start joyMouse 2>/dev/null || CLEANUP_FAILED=1
    if [ "$codec_unsafe" = 0 ] && [ "$CLEANUP_FAILED" = 0 ] && [ ! -f "$STATE/session.mixer" ]; then
      if [ "$network_ready" = 1 ]; then
        if phase return-framework-start start 2>/dev/null; then framework_started=1; else CLEANUP_FAILED=1; fi
      else
        CLEANUP_FAILED=1
        log "network reset failed; retaining journal and withholding framework restart until recovery"
      fi
    else
      # A stuck codec worker or guest may still have an in-flight audio ioctl.
      # Recover the display, but leave Android audio stopped until recovery.
      log "display recovery requested; audio/framework restart withheld until cleanup succeeds"
    fi
  fi
  prepare_retroarch_sync
  if [ "$CLEANUP_FAILED" = 0 ] && command -v prestage_park >/dev/null && prestage_park; then
    :
  else
  local b tries
  # Newest first. Atomic journal replacement preserves this reader's inode.
  if [ -f "$STATE/session.mounts" ]; then
    BOUND=$(cat "$STATE/session.mounts")
    while IFS= read -r b; do
      [ -n "$b" ] || continue
      if ! umount "$b" 2>/dev/null; then
        # Still listed? A frozen or exiting payload (e.g. ogage holding an open
        # fd under darkfull/dev) can pin it. Detach lazily so the return to
        # Android is immediate; the kernel frees it once the last fd closes.
        # This replaces a 3x1s retry that added seconds of black screen per mount.
        if awk -v p="$b" '{gsub(/\\040/," ",$2); gsub(/\\011/,"\t",$2)} $2==p {f=1} END {exit !f}' /proc/mounts; then
          umount -l "$b" 2>/dev/null || { CLEANUP_FAILED=1; log "mount still busy: $b"; }
        fi
      fi
      if ! awk -v p="$b" '{gsub(/\\040/," ",$2); gsub(/\\011/,"\t",$2)} $2==p {f=1} END {exit !f}' /proc/mounts; then
        forget_mount "$b" || CLEANUP_FAILED=1
      fi
    done < "$STATE/session.mounts"
  fi
  release_session_loop || { CLEANUP_FAILED=1; log "could not release owned session loop"; }

  fi

  if [ "$CLEANUP_FAILED" = 0 ] && [ -n "$RA_SYNC_DIR" ]; then
    printf '%s\n' "$RA_SYNC_DIR" > "$STATE/retroarch-sync-dir.tmp" &&
      mv -f "$STATE/retroarch-sync-dir.tmp" "$STATE/retroarch-sync-dir"
  fi
  if [ "$DID_HANDOFF" = 1 ]; then
    finish_android_power_return
    setprop sys.andr36oid.subsystem.return_animation 0 2>/dev/null
  fi
  return 0
}

session_active() {
  local pid stamp
  [ "$(cat "$STATE/session.lock/boot_id" 2>/dev/null)" = "$(cat /proc/sys/kernel/random/boot_id)" ] || return 1
  pid=$(cat "$STATE/session.lock/pid" 2>/dev/null)
  case "$pid" in ''|*[!0-9]*) return 1 ;; esac
  stamp=$(cat "$STATE/session.lock/start_time" 2>/dev/null)
  [ -z "$stamp" ] || [ "$stamp" = "$(process_start_time "$pid")" ] || return 1
  kill -0 "$pid" 2>/dev/null
}

recover_session() {
  [ -d "$STATE/session.lock" ] || return 0
  local held=${OPERATION_LOCKED:-0} rc=0
  [ "$held" = 1 ] || acquire_operation recover || return 1
  recover_session_locked "$@" || rc=$?
  if [ "$held" != 1 ]; then operation_done "$rc"; release_operation; fi
  return "$rc"
}
recover_session_locked() {
  [ -d "$STATE/session.lock" ] || return 0
  # A guardian for an older session must never tear down its replacement.
  [ -z "${1:-}" ] || [ "$(cat "$STATE/session.lock/pid" 2>/dev/null)" = "$1" ] || return 0
  if [ "$(cat "$STATE/session.lock/boot_id" 2>/dev/null)" != "$(cat /proc/sys/kernel/random/boot_id)" ]; then
    # /data survives reboot, mounts and PIDs do not. Never detach a loop or kill
    # a recycled PID from a previous boot based on its old metadata.
    if ! rm -f "$STATE/session.guest-loops" "$STATE/session.guest-loops.tmp" "$STATE/session.mixer" "$STATE/session.gamma" "$STATE/session.brightness" "$STATE/session.network-restored" "$STATE/session.runner" "$STATE/session.runner.tmp" "$STATE/session.console" "$STATE/session.console.tmp" "$STATE/session.handoff" "$STATE/session.fifo" "$STATE/session.hw-master" "$MUTE_PIDFILE" "$STATE/mute-guard.owner" "$STATE/audio-route.owner" "$STATE/audio-route.pid" "$STATE/audio-route.pid.tmp" "$STATE/audio-route.ready" "$STATE/audio-route.failed" "$STATE/session.lock/pid" "$STATE/session.lock/boot_id" "$STATE/session.lock/start_time" "$STATE/session.lock/power-return-deadline" ||
       ! rmdir "$STATE/session.lock" 2>/dev/null; then
      setprop sys.andr36oid.subsystem.session cleanup_failed 2>/dev/null
      return 1
    fi
    rm -f "$STATE/session.loop" "$STATE/session.mounts" "$STATE/prestage.ready" "$STATE/prestage.boot" "$STATE/session.restore-complete"
    setprop sys.andr36oid.subsystem.session ended 2>/dev/null
    return 0
  fi
  log "teardown: recovering journal for broker $(cat "$STATE/session.lock/pid" 2>/dev/null)"
  SESSION_OWNED=1
  [ -f "$STATE/session.handoff" ] && DID_HANDOFF=1
  LOOPDEV=$(awk '{print $1; exit}' "$STATE/session.loop" 2>/dev/null)
  STAGE_COMPLETE=0
  restore_android
  if [ "$CLEANUP_FAILED" = 0 ]; then : > "$STATE/session.restore-complete"; fi
  if [ "$CLEANUP_FAILED" = 0 ] && [ "${PARKED_STAGE:-0}" != 1 ]; then
    rm -f "$STATE/session.loop" "$STATE/session.mounts"
  fi
  if [ "$CLEANUP_FAILED" = 0 ]; then
    if ! rm -f "$STATE/session.network-restored" "$STATE/session.runner" "$STATE/session.runner.tmp" "$STATE/session.console" "$STATE/session.console.tmp" "$STATE/session.handoff" "$STATE/session.fifo" "$STATE/session.lock/pid" "$STATE/session.lock/boot_id" "$STATE/session.lock/start_time" "$STATE/session.lock/power-return-deadline" ||
       ! rmdir "$STATE/session.lock" 2>/dev/null; then CLEANUP_FAILED=1; fi
  fi
  if [ "$CLEANUP_FAILED" = 0 ]; then
    setprop sys.andr36oid.subsystem.session ended 2>/dev/null
    # A recovered (crashed/killed) session may have installed or removed ports
    # before dying; we cannot know, so refresh Daijishou's library (idempotent).
    setprop sys.andr36oid.ports_changed 1 2>/dev/null
    setprop sys.andr36oid.subsystem_changed 1 2>/dev/null
  else
    setprop sys.andr36oid.subsystem.session cleanup_failed 2>/dev/null
    return 1
  fi
  SESSION_OWNED=0; DID_HANDOFF=0; LOOPDEV=""; BOUND=""
}

stop_session() {
  local pid tries=0
  pid=$(cat "$STATE/session.lock/pid" 2>/dev/null)
  if session_active; then
    if ! android_power_return_in_progress; then
      log "teardown: stop requested for broker $pid"
      kill -TERM "$pid" 2>/dev/null
    fi
    # A normal broker in its bounded power return can take much longer than
    # the ten seconds allowed for an unresponsive session to begin teardown.
    while session_active && { [ "$tries" -lt 10 ] || android_power_return_in_progress; }; do
      sleep 1
      tries=$((tries+1))
    done
    if session_active; then
      log "teardown: broker $pid still active after stop wait; forcing recovery"
      kill -KILL "$pid" 2>/dev/null
    fi
  fi
  recover_session "$pid" || return 1
  if [ "${FORCE_UNSTAGE:-0}" = 1 ]; then prestage_command unstage; fi
}

# History is best effort and never owns session lifecycle. Three sessions of
# three 64 KiB tails plus one staging copy stay below 1 MiB, including metadata.
# A failed snapshot leaves active logs in append mode rather than erasing them.
history_index() {
  local h="$STATE/log-history" slot
  (
    printf 'version=1\ncurrent session.history subsystem.log boot.log session.out\n'
    for slot in 1 2 3; do
      [ -d "$h/$slot" ] || continue
      printf 'previous %s %s/session.info %s/subsystem.log %s/boot.log %s/session.out\n' \
        "$slot" "$slot" "$slot" "$slot" "$slot"
      [ ! -f "$h/$slot/session.info" ] || tail -c 512 "$h/$slot/session.info"
      printf '\n'
    done
  ) > "$h/index.tmp" && chmod 0644 "$h/index.tmp" && mv -f "$h/index.tmp" "$h/index.txt"
}

history_archive() {
  (
    local h="$STATE/log-history" file prior=0
    [ ! -L "$h" ] || exit 1
    mkdir -p "$h" && chmod 0755 "$STATE" "$h" || exit 1
    # An interrupted unpublished snapshot is disposable; published slots survive.
    rm -rf "$h/.pending" || exit 1
    for file in subsystem.log boot.log session.out session.history; do
      [ ! -f "$STATE/$file" ] || prior=1
    done
    if [ "$prior" = 0 ]; then history_index; exit $?; fi
    mkdir "$h/.pending" || exit 1
    trap 'rm -rf "$h/.pending"' EXIT
    for file in subsystem.log boot.log session.out; do
      if [ -f "$STATE/$file" ] && [ ! -L "$STATE/$file" ]; then
        tail -c 65536 "$STATE/$file" > "$h/.pending/$file" || exit 1
      fi
    done
    if [ -f "$STATE/session.history" ] && [ ! -L "$STATE/session.history" ]; then
      tail -c 512 "$STATE/session.history" > "$h/.pending/session.info" || exit 1
    else
      printf 'start=unknown\nend=unknown\nresult=interrupted-or-legacy\n' > "$h/.pending/session.info" || exit 1
    fi
    chmod 0755 "$h/.pending" && chmod 0644 "$h/.pending/"* || exit 1
    # Fixed slot names bound both completed and interrupted rotations. No copy
    # grows with runtime; never read a FIFO, device, directory or symlink as a log.
    rm -rf "$h/3" || exit 1
    [ ! -d "$h/2" ] || mv "$h/2" "$h/3" || exit 1
    [ ! -d "$h/1" ] || mv "$h/1" "$h/2" || exit 1
    mv "$h/.pending" "$h/1" || exit 1
    history_index || exit 1
  )
}

history_begin() {
  ROTATE_LOGS=0; HISTORY_RECORD=0
  if history_archive 2>/dev/null; then
    ROTATE_LOGS=1
    HISTORY_START=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
    if printf 'start=%s\nend=unknown\nresult=interrupted\n' "$HISTORY_START" > "$STATE/session.history.tmp" &&
       chmod 0644 "$STATE/session.history.tmp" && mv -f "$STATE/session.history.tmp" "$STATE/session.history"; then
      HISTORY_RECORD=1
    fi
  else
    log 'history snapshot unavailable; preserving active logs in append mode'
  fi
  return 0
}

history_finish() {
  [ "${HISTORY_RECORD:-0}" = 1 ] || return 0
  printf 'start=%s\nend=%s\nresult=rc:%s\n' "$HISTORY_START" \
    "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$1" > "$STATE/session.history.tmp" &&
    chmod 0644 "$STATE/session.history.tmp" && mv -f "$STATE/session.history.tmp" "$STATE/session.history"
  return 0
}

# FIFO + a separately waited tee preserves the payload exit code (a shell
# pipeline would report tee's status). Preparation, init, descendants and cleanup
# all use the same stream; the app may open/tail boot.log as soon as running=1.
start_live_log() {
  chmod 0755 "$STATE"
  if [ "${ROTATE_LOGS:-1}" = 1 ]; then
    : > "$STATE/boot.log"
    : > "$SESSION_OUT"
  fi
  touch "$STATE/boot.log" "$SESSION_OUT"
  chmod 0644 "$STATE/boot.log" "$SESSION_OUT"
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
  if [ "$CLEANUP_FAILED" = 0 ]; then : > "$STATE/session.restore-complete"; fi
  [ "$CLEANUP_FAILED" = 0 ] || rc=1
  # Any session that reached a running frontend (ES, a port, PortMaster or a tool)
  # may have installed or removed ports via PortMaster - now reachable from the ES
  # Tools menu too - so refresh Daijishou's Ports library; the reconcile is a no-op
  # when unchanged. Scoped to PORTS_MAYBE_CHANGED so an early mount failure, a
  # RetroArch-core launch or a setup-only run does not trigger spurious churn.
  if [ "${PORTS_MAYBE_CHANGED:-0}" = 1 ]; then
    setprop sys.andr36oid.ports_changed 1 2>/dev/null
    setprop sys.andr36oid.subsystem_changed 1 2>/dev/null
  fi
  : > "$STATE/subsystem.clean"
  [ -n "${GUARD:-}" ] && kill "$GUARD" 2>/dev/null
  log "session ended rc=$rc"
  log "teardown: final bookkeeping begins"
  if [ "${LIVE_LOG:-0}" = 1 ]; then
    LIVE_LOG=0
    if [ -e /proc/self/fd/3 ] && [ -e /proc/self/fd/4 ]; then
      exec 1>&3 2>&4 3>&- 4>&-
    else
      log "teardown: saved log descriptors missing; finishing with file log only"
      exec >/dev/null 2>&1 3>&- 4>&-
    fi
    # Bound the log drain. A guest stuck in D-state can keep session.fifo open
    # past kill_subsystem; an unbounded wait here would hang finish_session
    # forever, leaving session.lock and the operation flock held and refusing
    # ALL future subsystem launches until reboot. Android is already restored
    # by this point and the log is best-effort, so cap the wait.
    ( sleep 5; kill "$TEE_PID" 2>/dev/null ) &
    _tee_wd=$!
    wait "$TEE_PID" 2>/dev/null
    kill "$_tee_wd" 2>/dev/null
    wait "$_tee_wd" 2>/dev/null
  fi
  log "teardown: logger drained"
  setprop sys.andr36oid.subsystem.boot_pending "" 2>/dev/null
  # am waits for an ordered receiver. Bound this cosmetic Settings refresh so
  # it cannot retain a completed session while Android is busy drawing home.
  phase return-settings update_settings_entry org.andr36oid.syncsettings
  log "teardown: settings refresh submitted"
  if [ "$CLEANUP_FAILED" = 0 ] && [ "${PARKED_STAGE:-0}" != 1 ]; then
    rm -f "$STATE/session.loop" "$STATE/session.mounts"
  fi
  if [ "$CLEANUP_FAILED" = 0 ]; then
    if ! rm -f "$STATE/session.network-restored" "$STATE/session.runner" "$STATE/session.runner.tmp" "$STATE/session.console" "$STATE/session.console.tmp" "$STATE/session.handoff" "$STATE/session.fifo" "$STATE/session.lock/pid" "$STATE/session.lock/boot_id" "$STATE/session.lock/start_time" "$STATE/session.lock/power-return-deadline" ||
       ! rmdir "$STATE/session.lock" 2>/dev/null; then log "teardown: retained lock entries: $(ls -A "$STATE/session.lock" 2>/dev/null)"; CLEANUP_FAILED=1; rc=1; fi
  fi
  if [ "$CLEANUP_FAILED" = 0 ]; then
    setprop sys.andr36oid.subsystem.session ended 2>/dev/null
  else
    setprop sys.andr36oid.subsystem.session cleanup_failed 2>/dev/null
  fi
  # Restoration and operation status must succeed even if history storage fails.
  log "teardown: session status published; history finishing"
  history_finish "$rc" 2>/dev/null || :
  operation_done "$rc"
  release_operation
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

# The raw fbdev allocation is not automatically bound to a scanned-out plane.
# The accepted default enables ordinary kernel fbdev KMS restore/unblank, only after
# the old DRM master has released. No KD_TEXT force, retained userspace master,
# new FB allocation, or stale CRTC restore is used.
scanout_splash_enabled() {
  local enabled
  enabled=$(getprop persist.andr36oid.subsystem.kms_splash)
  [ "${enabled:-1}" = 1 ]
}
show_splash() {
  [ "${1:-}" != return ] || [ "${RETURN_SPLASH_OK:-0}" != 1 ] || return 0
  [ -f "$SPLASH" ] && [ -c "$FB" ] || return 0
  local rotation rc
  rotation=$(getprop ro.surface_flinger.primary_display_orientation)
  case "$rotation" in
    ORIENTATION_90) rotation=90;; ORIENTATION_180) rotation=180;; ORIENTATION_270) rotation=270;;
    ORIENTATION_0) rotation=0;; *) rotation=$(getprop ro.sf.hwrotation);;
  esac
  if scanout_splash_enabled; then
    if [ "${1:-}" = wait ]; then
      exec "$GUEST" --splash-scanout "$SPLASH" "$FB" "$rotation" /dev/dri/card0 "${2:-0}" "$STATE/session.brightness"
    fi
    "$GUEST" --splash-scanout "$SPLASH" "$FB" "$rotation" /dev/dri/card0 "${2:-0}" "$STATE/session.brightness" >> "$LOG" 2>&1
    rc=$?
    [ "$rc" = 0 ] || log "splash: KMS scanout not verified (busy owner or unavailable fbdev restore)"
    return "$rc"
  fi
  "$GUEST" --splash "$SPLASH" "$FB" "$rotation" >> "$LOG" 2>&1 || log "splash: framebuffer memory rendering unavailable"
  return 0
}
start_return_splash() {
  RETURN_SPLASH_PID=; RETURN_SPLASH_OK=0
  scanout_splash_enabled || return 0
  # Arm before audio/worker/loop/network cleanup. Activate as soon as ES drops
  # master, rather than leaving an empty CRTC until the later bootanim frame.
  ( trap - EXIT HUP INT TERM
    exec </dev/null >> "$LOG" 2>&1 3>&- 4>&- 7>&- 9>&-
    show_splash wait 10000
  ) &
  RETURN_SPLASH_PID=$!
  log "splash: return gap armed pid=$RETURN_SPLASH_PID"
}
finish_return_splash() {
  [ -n "${RETURN_SPLASH_PID:-}" ] || return 0
  # Reap our own bounded helper before composer starts. It normally completes
  # while earlier cleanup runs; never retain a master/worker into Android.
  if kill -0 "$RETURN_SPLASH_PID" 2>/dev/null; then
    kill -TERM "$RETURN_SPLASH_PID" 2>/dev/null || :
  fi
  if wait "$RETURN_SPLASH_PID" 2>/dev/null; then RETURN_SPLASH_OK=1; fi
  RETURN_SPLASH_PID=
  return 0
}

# Require init's terminal state; "stopping" precedes process exit.
wait_service_stopped() {
  local service="$1" state tries=0
  while :; do
    state=$(getprop "init.svc.$service")
    case "$state" in
      stopped) return 0 ;;
      '') [ "${2:-required}" = optional ] && return 0 ;;
    esac
    [ "$tries" -lt 100 ] || { log "service did not stop: $service ($state)"; return 1; }
    sleep 0.1; tries=$((tries+1))
  done
}
wait_service_running() {
  local service state tries=0
  service=$1
  while :; do
    state=$(getprop "init.svc.$service")
    [ "$state" != running ] || return 0
    [ "$tries" -lt 100 ] || { log "service did not start: $service ($state)"; return 1; }
    sleep 0.1; tries=$((tries+1))
  done
}
stop_android_display() {
  stop surfaceflinger || return 1
  wait_service_stopped surfaceflinger || return 1
  setprop ctl.stop "$HWC" || return 1
  wait_service_stopped "$HWC" || return 1
  # SET_MASTER succeeds only when the old DRM owner has released card0. The
  # probe closes its descriptor before the guest or gamma restore opens card0.
  # SurfaceFlinger/hwcomposer can report "stopped" to init a moment before the
  # kernel actually drops their card0 master handle, especially on a second
  # handoff after a prior session. A single immediate check then races and the
  # handoff is wrongly refused (back to Android at once). Poll briefly instead.
  local tries=0
  until "$GUEST" --check-drm; do
    tries=$((tries+1))
    [ "$tries" -lt 30 ] || { log "DRM master still held after 3s; refusing display admission"; return 1; }
    sleep 0.1
  done
}

# --- the handoff --------------------------------------------------------------
handoff() {
  : > "$STATE/session.handoff" || return 1
  local service state
  # Wi-Fi/adbd must remain reachable throughout the guest session. The guest
  # shares Android's namespace and needs no exclusive network ownership: keep
  # netd, wificond, supplicant, the Wi-Fi HAL and adbd alive. The netd reset on
  # EXIT is for the replacement ConnectivityService, not guest admission.
  # With system_server gone nothing holds a wake lock, so Android's suspend
  # service (autosuspend, enabled since the first screen-off of this boot)
  # would put the SoC back to sleep right after every wake. Hold a kernel wake
  # lock for the session; the broker's own sleep writes /sys/power/state
  # directly and is not blocked by it.
  if ! { printf 'andr36oid-subsystem\n' > "${A36_POWER_DIR:-/sys/power}/wake_lock"; } 2>/dev/null; then
    log "could not take session wake lock; refusing framework handoff"
    rm -f "$STATE/session.handoff"
    return 1
  fi
  DID_HANDOFF=1
  log "stop framework (keep Android Wi-Fi, netd/wificond and adbd)"
  for service in zygote zygote_secondary audioserver iorapd joyMouse; do
    state=$(getprop "init.svc.$service")
    case "$state" in ''|stopped) ;; *) stop "$service" || return 1 ;; esac
  done
  # Optional services may not be defined on every product. If present, wait
  # through stopping/restarting as well as running, especially for input/audio.
  for service in zygote zygote_secondary audioserver iorapd joyMouse; do
    wait_service_stopped "$service" optional || return 1
  done
  stop_android_display || return 1
  setprop service.bootanim.exit 1 || return 1
  setprop ctl.stop bootanim || return 1
  wait_service_stopped bootanim optional || return 1
  show_splash
  case "$(getprop ro.andr36oid.channel)" in release|'') : ;; *) start adbd 2>/dev/null ;; esac
  return 0
}


prepare_network() {
  # The chroot shares Android's network namespace, interfaces and policy routes.
  # Keep Android's existing routes at entry and the guest network managers off.
  # glibc cannot use bionic's netd resolver, so snapshot Android's default-link
  # DNS into a real resolv.conf, replacing systemd-resolved's dangling stub link.
  # Overlay unit masks and temporary init-script binds keep the existing Android
  # connection in charge, including when booting a generic rootfs's own init.
  local dns="" p snapshot net d unit path
  snapshot=$(timeout -k 1 1 dumpsys -t 1 connectivity --short 2>/dev/null) || snapshot=
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
  [ "${WARM_STAGE:-0}" != 1 ] || { log "network: warm DNS refreshed"; return 0; }
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
  # The native supervisor snapshots existing Android/APEX/rootfs loops and
  # creates ONLY free loop nodes in this session's private /dev before exec.
  if [ "$HANDOFF" = 1 ]; then
    chmod 0666 "$ROOT/dev/uinput" 2>/dev/null
    for n in "$ROOT"/dev/dri/card0 "$ROOT"/dev/dri/renderD128 "$ROOT"/dev/mali0; do [ -e "$n" ] && chmod 0666 "$n" 2>/dev/null; done
  fi
  for n in "$ROOT"/dev/snd/*; do [ -e "$n" ] && chmod 0666 "$n" 2>/dev/null; done
  # Raw input nodes for ports that read evdev directly (ES itself goes through ogage's
  # uinput device above, but Kodi and some ports open /dev/input/event* themselves).
  for n in "$ROOT"/dev/input/event* "$ROOT"/dev/input/js*; do [ -e "$n" ] && chmod 0666 "$n" 2>/dev/null; done
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
    "$GUEST" "$ROOT" generic quiet -- /bin/sh -c '
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
    local audio_rate audio_buffer
    audio_rate=$(getprop persist.andr36oid.subsystem.rate)
    audio_buffer=$(getprop persist.andr36oid.subsystem.buffer)
    case "$audio_rate" in 44100|48000) ;; *) audio_rate=44100;; esac
    case "$audio_buffer" in 2048|4096|8192) ;; *) audio_buffer=4096;; esac
    cat > "$ROOT/etc/asound.conf" <<EOF
pcm.!default {
    type plug
    slave.pcm "dmixer"
}
pcm.dmixer {
    type dmix
    ipc_key 1024
    ipc_perm 0666
    slave { pcm "hw:0,0" period_time 0 period_size 1024 buffer_size $audio_buffer rate $audio_rate }
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
  for u in audiopath.service audiostate.service; do
    printf 'ConditionPathExists=!/run/.a36-host-audio\n' >> "$ROOT/etc/systemd/system/$u.d/20-audio-policy.conf"
  done
  printf '[Service]\nEnvironment=PATH=/run/andr36oid-audio-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n' \
    > "$ROOT/etc/systemd/system/ogage.service.d/30-host-route.conf"
  # 351mp also restores Playback Path, alongside unrelated LED/brightness work.
  # Replay its original commands, gating only fixvolume (including its args).
  mkdir -p "$ROOT/usr/local/libexec" || return 1
  cat > "$ROOT/usr/local/libexec/a36-session-fixvolume" <<'AUDIO' || return 1
#!/bin/sh
[ -f /run/.a36-handoff ] && [ ! -f /run/.a36-muted ] || exit 0
[ ! -f /run/.a36-host-audio ] || exit 0
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
      if [ "$HANDOFF" = 1 ] && ! subsystem_muted; then
        printf ' PATH=/run/andr36oid-audio-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
      fi
      [ "$HANDOFF" = 0 ] && printf ' SDL_VIDEODRIVER=dummy SDL_KMSDRM_REQUIRE_DRM_MASTER=0'
      printf '\nDefaultStandardOutput=journal+console\nDefaultStandardError=journal+console\n'
    } > "$ROOT/run/systemd/system.conf.d/andr36oid.conf"
    printf '[Journal]\nForwardToConsole=yes\nTTYPath=/dev/console\n' > "$ROOT/run/systemd/journald.conf.d/andr36oid.conf"
  fi
  # The rootfs runs in a container: it has no block devices to mount, so an empty
  # fstab keeps init's "mount -a" from erroring on a missing/stale fstab.
  : > "$ROOT/etc/fstab" 2>/dev/null || true
  log "boot rootfs init $INIT (PID namespace; console -> $STATE/boot.log)"
  SESSION_MODE=systemd
  run_in_subsystem "$@"
}

# Keep the codec silent for muted handoff sessions. The guest hotkey daemon
# retains brightness/gamma functions but receives a no-op amixer in its PATH.
# Verify the required route and every present volume alias on admission and
# throughout the session; a failing codec must not admit an audible payload.
MUTE_PIDFILE="$STATE/mute-guard.pid"
subsystem_muted() {
  # One immutable admission decision drives the marker, filter and watchdog.
  [ "${SESSION_MUTED:-$(getprop persist.andr36oid.subsystem.mute 2>/dev/null)}" = 1 ]
}
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
# Shell builtins only: the session watchers call these ten times a second, and
# every cat/awk they used to spawn cost a fork+exec on a memory-starved console.
process_start_time() {
  local stat
  { IFS= read -r stat < "/proc/$1/stat"; } 2>/dev/null || return 1
  # Fields after "(comm) " start at field 3, so starttime (22) is the 20th.
  set -- ${stat##*) }
  [ -n "${20:-}" ] || return 1
  echo "${20}"
}
recorded_process_alive() {
  local boot stat
  { IFS= read -r boot < /proc/sys/kernel/random/boot_id; } 2>/dev/null || return 1
  [ "$3" = "$boot" ] || return 1
  case "$1:$2" in *[!0-9:]*|:*|*:) return 1 ;; esac
  [ "$(process_start_time "$1")" = "$2" ] && kill -0 "$1" 2>/dev/null || return 1
  { IFS= read -r stat < "/proc/$1/stat"; } 2>/dev/null || return 1
  case "$stat" in *') Z '*|*') X '*|'') return 1 ;; esac
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
# One shared deadline covers the entire tree, including blocked descendants.
process_deadline_reached() {
  local uptime rest
  read -r uptime rest < /proc/uptime
  [ "${uptime%%.*}" -ge "$MUTE_KILL_DEADLINE" ]
}
freeze_process() {
  local pid="$1" stamp="$2"
  [ "$(process_start_time "$pid")" = "$stamp" ] || return 1
  kill -STOP "$pid" 2>/dev/null || return 1
  while [ "$(process_start_time "$pid")" = "$stamp" ]; do
    case "$(cat "/proc/$pid/stat" 2>/dev/null)" in
      *') T '*|*') t '*) return 0 ;;
      *') Z '*|*') X '*|'') return 1 ;;
    esac
    process_deadline_reached && return 1
    sleep 0.1
  done
  return 1
}
kill_process_children() {
  local parent="$1" p stat child stamp failed=0 children="" task kids have_children=0
  # The runner also owns threaded processes. Linux lists a fork under the
  # calling TID, not necessarily under the group leader. Walk every thread in
  # the stopped process before killing it, or those children can be orphaned.
  for task in /proc/"$parent"/task/[0-9]*/children; do
    [ -r "$task" ] || continue
    have_children=1
    kids=
    { IFS= read -r kids < "$task"; } 2>/dev/null || :
    children="$children $kids"
  done
  if [ "$have_children" = 0 ]; then
    for p in /proc/[0-9]*; do
      process_deadline_reached && return 1
      { IFS= read -r stat < "$p/stat"; } 2>/dev/null || continue
      set -- ${stat##*) }
      [ "${2:-}" != "$parent" ] || children="$children ${p#/proc/}"
    done
  fi
  for child in $children; do
    stamp=$(process_start_time "$child") || continue
    if ! freeze_process "$child" "$stamp"; then
      case "$(cat "/proc/$child/stat" 2>/dev/null)" in *') Z '*|*') X '*|'') continue ;; esac
      [ "$(process_start_time "$child")" != "$stamp" ] || failed=1
      continue
    fi
    if ! kill_process_children "$child"; then failed=1; continue; fi
    kill -9 "$child" 2>/dev/null || :
    while [ "$(process_start_time "$child")" = "$stamp" ]; do
      case "$(cat "/proc/$child/stat" 2>/dev/null)" in *') Z '*|*') X '*|'') break ;; esac
      if process_deadline_reached; then failed=1; break; fi
      sleep 0.1
    done
  done
  return "$failed"
}
kill_tracked_tree() {
  local pid="$1" stamp="$2" boot="$3" uptime rest
  read -r uptime rest < /proc/uptime
  MUTE_KILL_DEADLINE=$(( ${uptime%%.*} + 4 ))
  recorded_process_alive "$pid" "$stamp" "$boot" || return 0
  if ! freeze_process "$pid" "$stamp"; then
    recorded_process_alive "$pid" "$stamp" "$boot" && return 1
    return 0
  fi
  # Keep a frozen parent alive if a child cannot be reaped. Its descendants
  # stay discoverable on retry instead of becoming unjournaled PID-1 orphans.
  kill_process_children "$pid" || return 1
  recorded_process_alive "$pid" "$stamp" "$boot" && kill -KILL "$pid" 2>/dev/null || :
  while recorded_process_alive "$pid" "$stamp" "$boot"; do
    process_deadline_reached && return 1
    sleep 0.1
  done
}
stop_mute_guard() {
  local pid stamp boot extra
  rm -f "$STATE/mute-guard.owner"
  if [ -r "$MUTE_PIDFILE" ] && read -r pid stamp boot extra < "$MUTE_PIDFILE" 2>/dev/null &&
     [ -z "$extra" ] && recorded_process_alive "$pid" "$stamp" "$boot"; then
    if ! kill_tracked_tree "$pid" "$stamp" "$boot"; then
      log "mute worker did not stop; retaining identity and codec snapshots; reboot may be required"
      return 1
    fi
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
  stop_mute_guard || return 1
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
    # No mixer operation can start before publication. Bound this failure too.
    [ -z "${stamp:-}" ] || kill_tracked_tree "$pid" "$stamp" "$owner_boot" || :
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
    "$ROOT/run/.a36-session-exit" "$ROOT/run/.a36-session-reboot" "$ROOT/run/.a36-session-sleep" "$ROOT/run/.a36-sleeping" \
    "$ROOT/run/.a36-es-stopped" "$ROOT/run/.a36-es-start" "$ROOT/run/.a36-es-restart"
  rm -f "$ROOT/run/.a36-host-audio"
  if [ "$HANDOFF" = 1 ] && ! subsystem_muted; then
    : > "$ROOT/run/.a36-host-audio" || return 1
    mkdir -p "$ROOT/run/andr36oid-audio-bin" || return 1
    # Ogage still owns volume/brightness. Suppress only its route commands,
    # including its quoted sset spelling; forward volume operations unchanged.
    cat > "$ROOT/run/andr36oid-audio-bin/amixer" <<'AUDIO_ROUTE' || return 1
#!/bin/sh
if [ -f /run/.a36-host-audio ]; then
  for arg do
    case "$arg" in *'Playback Path'*) exit 0 ;; esac
  done
fi
exec /usr/bin/amixer "$@"
AUDIO_ROUTE
    chmod 0755 "$ROOT/run/andr36oid-audio-bin/amixer" || return 1
  fi
  [ "$HANDOFF" != 1 ] || : > "$ROOT/run/.a36-handoff"
  if subsystem_muted; then : > "$ROOT/run/.a36-muted"; fi
  if session_uses_init; then
    echo systemd > "$ROOT/run/.a36-session-manager"
  else
    echo wrapper > "$ROOT/run/.a36-session-manager"
  fi
  install_systemctl_shim
}

# Repair native blanking without enabling host suspend or touching DRM/DPMS.
live_backlight_allowed() {
  [ "${A36_BACKLIGHT_GUARD:-1}" = 1 ] &&
    [ "$HANDOFF" = 1 ] && [ "$DID_HANDOFF" = 1 ] &&
    [ -f "$ROOT/run/.a36-handoff" ] &&
    [ ! -f "$ROOT/run/.a36-session-exit" ] && [ ! -f "$ROOT/run/.a36-session-reboot" ]
}
restore_live_backlight() {
  live_backlight_allowed || return 0
  # While the sleep handler holds the panel dark for a real suspend, leave it be.
  [ ! -f "$ROOT/run/.a36-sleeping" ] || return 0
  [ -r "$STATE/session.brightness" ] || return 0
  local node value current
  while read -r node value; do
    case "$node" in /sys/devices/*/brightness) ;; *) continue ;; esac
    case "$value" in ''|*[!0-9]*) continue ;; esac
    [ "$value" -gt 0 ] || continue
    current=$(cat "$node" 2>/dev/null) || continue
    [ "$current" = 0 ] || continue
    live_backlight_allowed || return 0
    # Native sleep can blank this writable bind while /sys/power/state stays
    # read-only. Repair only zero, using the already captured Android value;
    # leave nonzero brightness changes and the teardown snapshot untouched.
    if printf '%s\n' "$value" > "$node"; then
      log "backlight guardian: restored $node from 0 to $value"
    else
      log "backlight guardian: could not restore $node"
    fi
  done < "$STATE/session.brightness"
}
# level 0 = blank every captured backlight node; level 1 = restore each node to
# the Android value snapshotted at handoff.
blank_session_backlight() {
  local want=$1 node value
  [ -r "$STATE/session.brightness" ] || return 0
  while read -r node value; do
    case "$node" in /sys/devices/*/brightness) ;; *) continue ;; esac
    if [ "$want" = 0 ]; then
      printf '0\n' > "$node" 2>/dev/null || :
    else
      case "$value" in ''|*[!0-9]*) continue ;; esac
      printf '%s\n' "$value" > "$node" 2>/dev/null || :
    fi
  done < "$STATE/session.brightness"
}
capture_session_wake_sources() {
  local phase=$1
  # Read-only snapshots for IRQ deltas and active sources; never read the
  # potentially blocking /sys/power/wakeup_count here.
  cat /proc/interrupts 2>/dev/null > "$STATE/sleep.interrupts.$phase" || :
  if ! cat /sys/kernel/debug/wakeup_sources 2>/dev/null > "$STATE/sleep.wakeup_sources.$phase"; then
    cat /d/wakeup_sources 2>/dev/null > "$STATE/sleep.wakeup_sources.$phase" || :
  fi
}

session_wifi_owns_link() {
  [ "$DID_HANDOFF" = 1 ] && [ -f "$STATE/session.handoff" ] &&
    [ ! -e "$ROOT/run/.a36-session-exit" ] && [ ! -e "$ROOT/run/.a36-session-reboot" ] &&
    [ "$(getprop init.svc.zygote)" = stopped ] &&
    [ "$(getprop init.svc.wpa_supplicant)" = running ] || return 1
  case "$(getprop init.svc.zygote_secondary)" in ''|stopped) return 0 ;; esac
  return 1
}

session_wifi_status() {
  local status key value wifi_state=
  [ "$(cat /sys/class/net/wlan0/carrier 2>/dev/null)" = 1 ] || return 1
  status=$("${GUEST:-/system/bin/andr36oid-guest}" --session-wifi status 2>/dev/null) || return 1
  wifi_seen_id=; wifi_seen_bssid=
  while IFS='=' read -r key value; do
    case "$key" in
      wpa_state) wifi_state=$value ;;
      id) wifi_seen_id=$value ;;
      bssid) wifi_seen_bssid=$value ;;
    esac
  done <<STATUS
$status
STATUS
  [ "$wifi_state" = COMPLETED ] || return 1
  case "$wifi_seen_id" in ''|*[!0-9]*) return 1 ;; esac
  case "$wifi_seen_bssid" in
    [0-9a-fA-F][0-9a-fA-F]:[0-9a-fA-F][0-9a-fA-F]:[0-9a-fA-F][0-9a-fA-F]:[0-9a-fA-F][0-9a-fA-F]:[0-9a-fA-F][0-9a-fA-F]:[0-9a-fA-F][0-9a-fA-F]) return 0 ;;
  esac
  return 1
}

session_wifi_addresses() {
  ip -o -4 addr show dev wlan0 scope global 2>/dev/null |
    awk '$3 == "inet" {print $4}'
}

session_wifi_routes() {
  ip -o -4 route show table all dev wlan0 2>/dev/null |
    awk '$1 == "default" || $1 ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+(\/[0-9]+)?$/ {
      gsub(/ +linkdown( |$)/, " "); sub(/ +$/, ""); print
    }'
}

session_wifi_recovery_event() {
  dmesg 2>/dev/null | awk '/RK915: .*fw error recovery end/ {last=$0} END {if (last) print last}'
}

session_wifi_gateway_alive() {
  ping -I wlan0 -c 1 -W 1 "$wifi_gateway" >/dev/null 2>&1
}

snapshot_session_wifi() {
  session_wifi_owns_link || return 0
  if ! session_wifi_status; then
    [ "$(cat /sys/class/net/wlan0/carrier 2>/dev/null)" != 1 ] ||
      log "sleep: Wi-Fi control unavailable before suspend"
    return 0
  fi
  wifi_id=$wifi_seen_id
  wifi_bssid=$wifi_seen_bssid
  wifi_addresses=$(session_wifi_addresses)
  wifi_routes=$(session_wifi_routes)
  wifi_gateway=$(printf '%s\n' "$wifi_routes" | awk '$1=="default" && $2=="via" {print $3; exit}')
  if [ -n "$wifi_gateway" ] && session_wifi_gateway_alive; then wifi_probe=1; fi
  wifi_recovery=$(session_wifi_recovery_event)
}

restore_session_wifi_ipv4() {
  local address current routes route
  current=$(session_wifi_addresses)
  for address in $current; do
    # A newly configured address wins over our saved lease.
    if ! printf '%s\n' "$wifi_addresses" | grep -Fqx "$address"; then
      log "sleep: Wi-Fi has a new IPv4 address; skipped saved state"
      return 0
    fi
  done
  for address in $wifi_addresses; do
    session_wifi_owns_link || return 1
    printf '%s\n' "$current" | grep -Fqx "$address" ||
      ip -4 addr add "$address" dev wlan0 2>/dev/null || return 1
  done
  routes=$(session_wifi_routes)
  # Connected routes must precede a default via their gateway. Keep each
  # original table and metric. `add` cannot replace an existing route.
  while IFS= read -r route; do
    [ -n "$route" ] || continue
    printf '%s\n' "$routes" | grep -Fqx "$route" && continue
    session_wifi_owns_link || return 1
    # Tokens are from iproute2 for the fixed wlan0 device.
    if ! ip -4 route add $route 2>/dev/null &&
       ! session_wifi_routes | grep -Fqx "$route"; then
      return 1
    fi
  done <<ROUTES
$(printf '%s\n' "$wifi_routes" | awk '$1=="default" {defaults=defaults $0 "\n"; next} {print} END {printf "%s", defaults}')
ROUTES
}

session_wifi_recovered() {
  session_wifi_status || return 1
  if [ "$wifi_seen_id" != "$wifi_id" ] || [ "$wifi_seen_bssid" != "$wifi_bssid" ]; then
    log "sleep: Wi-Fi associated to a different link; skipped saved IPv4 state"
    return 0
  fi
  restore_session_wifi_ipv4 || return 1
  [ "$wifi_probe" = 0 ] || session_wifi_gateway_alive
}

wait_session_wifi() {
  local uptime rest deadline
  read -r uptime rest < /proc/uptime
  deadline=$(( ${uptime%%.*} + $1 ))
  while :; do
    session_wifi_owns_link || return 1
    session_wifi_recovered && return 0
    read -r uptime rest < /proc/uptime
    [ "${uptime%%.*}" -lt "$deadline" ] || return 1
    sleep 0.5
  done
}

resume_session_wifi() {
  local recovery error
  [ -n "$wifi_id" ] && session_wifi_owns_link || return 0
  recovery=$(session_wifi_recovery_event)
  if { [ -z "$recovery" ] || [ "$recovery" = "$wifi_recovery" ] || [ "$wifi_probe" = 1 ]; } &&
     session_wifi_recovered; then
    log "sleep: Wi-Fi link ready after resume"
    return 0
  fi
  log "sleep: Wi-Fi link lost or firmware reloaded; requesting reassociation"
  session_wifi_owns_link || return 0
  if error=$("${GUEST:-/system/bin/andr36oid-guest}" --session-wifi reassociate 2>&1); then
    # Let the supplicant begin the scan before considering a reported link.
    sleep 1
    if wait_session_wifi 4; then
      log "sleep: Wi-Fi reassociated; kept session DNS"
      return 0
    fi
  else
    log "sleep: Wi-Fi reassociation request failed: ${error:-no control response}"
  fi
  session_wifi_owns_link || return 0
  log "sleep: Wi-Fi still unavailable; resetting wlan0 with supplicant kept"
  if timeout -k 1 2 ip link set wlan0 down; then sleep 0.2; fi
  # Pair the down request with up even if the driver timed out returning it.
  if ! timeout -k 1 2 ip link set wlan0 up; then
    log "sleep: Wi-Fi could not bring wlan0 up; Android will recover on exit"
    return 0
  fi
  session_wifi_owns_link || return 0
  "${GUEST:-/system/bin/andr36oid-guest}" --session-wifi reassociate >/dev/null 2>&1 || :
  if wait_session_wifi 6; then
    log "sleep: Wi-Fi recovered after interface reset; kept session DNS"
  else
    log "sleep: Wi-Fi recovery timed out; Android will recover on exit"
  fi
}

session_wake_reason() {
  local wake irq action
  wake=$(cat /sys/kernel/wakeup_reasons/last_resume_reason 2>/dev/null) || wake=
  case "$wake" in ''|unknown|Unknown) ;; *) printf '%s\n' "$wake"; return 0 ;; esac
  irq=$(cat /sys/power/pm_wakeup_irq 2>/dev/null) || irq=
  case "$irq" in ''|0|*[!0-9]*) printf 'unknown\n'; return 0 ;; esac
  action=$(cat "/sys/kernel/irq/$irq/actions" 2>/dev/null) || action=
  printf 'irq %s%s\n' "$irq" "${action:+ $action}"
}

log_session_wakeup_candidates() {
  local sources
  sources=$(awk '
    FNR == 1 {next}
    NR == FNR {before[$1]=$4; next}
    $4 ~ /^[0-9]+$/ && $4 > before[$1] {printf "%s%s", sep, $1; sep=", "}
  ' "$STATE/sleep.wakeup_sources.before" "$STATE/sleep.wakeup_sources.after")
  [ -z "$sources" ] || log "sleep: wakeup counter increased for $sources"
}

# A short power press (-> /run/.a36-session-sleep via the shim) is a real sleep,
# not an exit. The guest cannot reach /sys/power/state (read-only bind); the
# broker runs on Android as root and drives the same suspend-to-RAM the device
# already wakes from on its power key. Blank the panel first so there is no
# bright flash across the transition; the kernel freezer pauses the guest for us.
do_session_sleep() {
  local sleep_worker saved_traps sleep_rc
  saved_traps=$(trap)
  # Isolate cleanup traps in a worker, with the watcher forwarding termination
  # and waiting for restoration before it exits.
  (
    rm -f "$ROOT/run/.a36-session-sleep"
    local error="$STATE/sleep.error" t0 t1 elapsed rc wake mem_ok=0
    local wifi_id= wifi_bssid= wifi_seen_id= wifi_seen_bssid= wifi_addresses= wifi_routes=
    local wifi_gateway= wifi_probe=0 wifi_recovery=
    local wake_mask_node=/sys/module/rockchip_pm_config/parameters/sleep_wakeup
    local wake_mask_saved= current digits masked actual rest supply powered=0
    : > "$ROOT/run/.a36-sleeping" || return 1
    trap '
      rc=$?
      if [ -n "$wake_mask_saved" ]; then
        if printf "%s\n" "$wake_mask_saved" > "$wake_mask_node" &&
           read -r actual rest < "$wake_mask_node" && [ "$actual" = "$wake_mask_saved" ]; then
          log "sleep: restored sleep_wakeup=$wake_mask_saved"
        else
          rc=1
          log "sleep: ERROR restoring sleep_wakeup=$wake_mask_saved"
        fi
      fi
      rm -f "$ROOT/run/.a36-sleeping"
      blank_session_backlight 1
      if [ "$rc" = 0 ] && [ "$mem_ok" = 1 ]; then resume_session_wifi; fi
      exit "$rc"
    ' EXIT
    trap 'exit 1' HUP INT TERM
    blank_session_backlight 0
    for supply in /sys/class/power_supply/ac /sys/class/power_supply/usb; do
      if [ "$(cat "$supply/online" 2>/dev/null)" = 1 ] ||
         grep -q '^POWER_SUPPLY_ONLINE=1$' "$supply/uevent" 2>/dev/null; then
        powered=1
        break
      fi
    done
    if [ "$powered" = 1 ]; then
      # PX30 USB_WKUP_EN is bit 7, also exposed by DeepSleepFragment. Do not
      # disable GPIO/PMIC wake: the power key shares the PMIC's parent IRQ.
      # The getter includes "default ..."; only its first token is writable.
      if ! read -r current rest 2>/dev/null < "$wake_mask_node"; then
        log "sleep: cannot read USB wake policy while externally powered; refusing suspend"
        exit 1
      fi
      case "$current" in 0x*) digits=${current#0x} ;; *) digits= ;; esac
      case "$digits" in ''|*[!0-9a-fA-F]*)
        log "sleep: invalid sleep_wakeup=$current; refusing suspend"
        exit 1 ;;
      esac
      [ "${#digits}" -le 8 ] || { log "sleep: oversized sleep_wakeup; refusing suspend"; exit 1; }
      if [ "$((current & 128))" != 0 ]; then
        masked=$(printf '0x%x' "$((current & ~128))")
        # Save before attempting the write so a failed/partial change is undone.
        wake_mask_saved=$current
        if ! printf '%s\n' "$masked" 2>"$error" > "$wake_mask_node" ||
           ! read -r actual rest 2>>"$error" < "$wake_mask_node" ||
           [ "$actual" != "$masked" ]; then
          log "sleep: could not mask USB wake; refusing suspend: $(cat "$error" 2>/dev/null)"
          exit 1
        fi
        log "sleep: external power; masked USB wake sleep_wakeup=$current->$masked (power-key wake retained)"
      else
        log "sleep: external power; USB wake already disabled (sleep_wakeup=$current)"
      fi
    fi
    # A future RTC alarm wakes healthy suspend and cannot rescue the clone's
    # deep-sleep wake hang (which needs reset/power-off). Leave alarms untouched;
    # detect an immediate return without scheduling a wake or retrying suspend.
    # Let the requesting press/release settle. Do not read wakeup_count here:
    # its blocking read can wait indefinitely while a wake source stays active.
    snapshot_session_wifi
    sleep 1
    log "sleep: suspending SoC (no RTC safety alarm)"
    capture_session_wake_sources before
    # Wall time advances during suspend, unlike CLOCK_MONOTONIC on Linux.
    t0=$(date +%s)
    if printf 'mem\n' 2>"$error" > /sys/power/state; then
      t1=$(date +%s)
      elapsed=$((t1-t0))
      mem_ok=1
      wake=$(session_wake_reason)
      if [ "$elapsed" -lt 3 ]; then
        # A quick intentional wake is indistinguishable from an abort here.
        # Both recover the panel and guardian below; never auto-retry.
        log "sleep: mem returned after ${elapsed}s (instant abort or early wake); wake=$wake"
      else
        log "sleep: mem returned successfully after ${elapsed}s; wake=$wake"
      fi
    else
      rc=$?
      log "sleep: mem failed rc=$rc: $(cat "$error" 2>/dev/null)"
    fi
    capture_session_wake_sources after
    if [ "$mem_ok" = 1 ] && [ "$wake" = unknown ]; then log_session_wakeup_candidates; fi
  ) &
  sleep_worker=$!
  trap 'kill -TERM "$sleep_worker" 2>/dev/null || :; wait "$sleep_worker" 2>/dev/null || :; exit 1' HUP INT TERM
  if wait "$sleep_worker"; then sleep_rc=0; else sleep_rc=$?; fi
  trap - HUP INT TERM
  eval "$saved_traps"
  return "$sleep_rc"
}

# A marker must interrupt the foreground payload, including a standalone port or
# the wrapper's infinite RetroArch boot loop. Keep this watcher outside the chroot.
watch_session_markers() {
  local payload="$1" maintenance="${2:-0}" stamp boot backlight_ticks=0 sleep_cooldown=0
  exec </dev/null >/dev/null 2>&1 3>&- 4>&-
  stamp=$(process_start_time "$payload") || return 0
  boot=$(cat /proc/sys/kernel/random/boot_id)
  while :; do
    if [ -f "$ROOT/run/.a36-session-exit" ] || [ -f "$ROOT/run/.a36-session-reboot" ]; then
      kill -KILL "$payload" 2>/dev/null
      kill_subsystem_payload
      return 0
    fi
    # Real sleep: suspend the SoC, hold the session. The debounce swallows the
    # wake press so the guest's power handler cannot immediately re-sleep.
    # The 30 ticks below are about 3 seconds at this loop's 0.1s interval.
    if [ "$sleep_cooldown" -gt 0 ]; then
      sleep_cooldown=$((sleep_cooldown-1))
      [ ! -f "$ROOT/run/.a36-session-sleep" ] || rm -f "$ROOT/run/.a36-session-sleep"
    elif [ -f "$ROOT/run/.a36-session-sleep" ]; then
      do_session_sleep
      sleep_cooldown=30
      continue
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
    # Reuse this payload-bound host watcher; no extra daemon to outlive cleanup.
    if [ "$backlight_ticks" = 0 ]; then
      restore_live_backlight
      backlight_ticks=10
    fi
    backlight_ticks=$((backlight_ticks-1))
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


run_es_wrapper() {
  find_es "$ROOT" || { log "rootfs has no EmulationStation target"; return 1; }
  start_hotkeys
  # dArkOS leans on systemd to keep ES alive across a crash or a settings-driven
  # restart; we have none, so re-launch ES ourselves when its wrapper exits. Only an
  # explicit power-off/suspend (-> /run/.a36-session-exit via the systemctl shim), a
  # clean quit, or a crash-loop ends the session; a reboot/restart re-launches. Bound
  # the rate so a pathological crash cannot trap the user inside the subsystem.
  local rc tries=0 window now retry_limit
  retry_limit=$(getprop persist.andr36oid.subsystem.retries)
  case "$retry_limit" in 1|2|3|4|5) : ;; *) retry_limit=3;; esac
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
    [ "$tries" -ge "$retry_limit" ] && { log "EmulationStation exited repeatedly; returning to Android"; break; }
    log "EmulationStation exited unexpectedly (rc=$rc); relaunching (attempt $tries)"
  done
}


# Boot receivers also run after a scan or session, so Settings need not wait
# for another reboot. A stopped Android framework will refresh them at boot.
update_settings_entry() {
  timeout -k 1 2 /system/bin/cmd activity broadcast --user 0 -a "$1.UPDATE_SETTINGS" -n "$1/.BootReceiver" \
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

# RACORE:<32 hex id> names a private, atomically published two-line request.
# Keep paths out of Android properties (91-byte value limit) and shell programs.
retroarch_error() {
  log "RetroArch (dArkOSen): $*"
  setprop sys.andr36oid.subsystem.launch_error "$1" 2>/dev/null
  return 1
}
read_retroarch_request() {
  local token="$1" file extra
  case "$token" in ''|*[!0-9a-f]*) retroarch_error "Invalid RetroArch request."; return 1 ;; esac
  [ "${#token}" = 32 ] || { retroarch_error "Invalid RetroArch request."; return 1; }
  file="${A36_RA_REQUESTS:-/data/user/0/org.andr36oid.subsystemsetup/files}/ra-$token.request"
  RA_CORE=; RA_ROM=; extra=
  if ! {
    IFS= read -r RA_CORE && IFS= read -r RA_ROM && ! IFS= read -r extra
  } < "$file"; then
    rm -f "$file"
    retroarch_error "Invalid RetroArch request."; return 1
  fi
  rm -f "$file"
  case "$RA_CORE" in ''|*[!a-zA-Z0-9_]*) retroarch_error "Invalid RetroArch core."; return 1 ;; esac
  [ "${#RA_CORE}" -le 64 ] && [ -n "$RA_ROM" ] && [ -z "$extra" ] || {
    retroarch_error "Invalid RetroArch request."; return 1;
  }
  [ "$(getprop persist.andr36oid.subsystem.daijishou_cores)" = 1 ] || {
    retroarch_error "Enable Daijishou dArkOSen cores in subsystem settings first."; return 1;
  }
}
map_retroarch_rom() {
  local source guest alias rel resolved path="$RA_ROM"
  # Never let a relative component escape the shared games volume.
  case "$path/" in *'/../'*|*'/./'*|*'//'*) return 1 ;; esac
  while IFS="$(printf '\t')" read -r source guest; do
    case "$source" in
      /data/media/0) alias=/storage/emulated/0 ;;
      /mnt/media_rw/*) alias="/storage/${source##*/}" ;;
      *) continue ;;
    esac
    case "$path" in
      "$source"/*) rel=${path#"$source"/} ;;
      "$alias"/*) rel=${path#"$alias"/} ;;
      "$guest"/*) rel=${path#"$guest"/} ;;
      *) continue ;;
    esac
    resolved=$(root_path "$ROOT" "$guest/$rel") || return 1
    case "$resolved" in "$ROOT$guest"/*) ;; *) return 1 ;; esac
    [ -f "$resolved" ] || return 1
    RA_ROM="$guest/$rel"
    return 0
  done < "$STATE/roms-map"
  return 1
}
resolve_retroarch_target() {
  local dir resolved
  [ "$(getprop persist.andr36oid.subsystem.daijishou_cores)" = 1 ] || {
    retroarch_error "Enable Daijishou dArkOSen cores in subsystem settings first."; return 1;
  }
  root_file "$ROOT" "$arg" || {
    retroarch_error "dArkOSen RetroArch is missing. Choose the Android RetroArch player."; return 1;
  }
  RA_LIBRARY=
  # Verified reference location first; also support images installing under /opt.
  for dir in /home/ark/.config/retroarch/cores /opt/retroarch/cores; do
    resolved=$(root_path "$ROOT" "$dir/${RA_CORE}_libretro.so") || continue
    [ -f "$resolved" ] && [ -r "$resolved" ] || continue
    RA_LIBRARY="$dir/${RA_CORE}_libretro.so"; break
  done
  [ -n "$RA_LIBRARY" ] || {
    log "requested Linux core not found: $RA_CORE"
    retroarch_error "dArkOSen core is missing. Choose the Android RetroArch player."; return 1;
  }
  map_retroarch_rom || {
    log "ROM is not on a shared games volume or is missing: $RA_ROM"
    retroarch_error "ROM is missing or not shared with dArkOSen. Choose the Android player."; return 1;
  }
}

resolve_run_target() {
  local c resolved
  if [ -n "${RA_REQUEST:-}" ]; then resolve_retroarch_target; return $?; fi
  if [ "${IS_PM:-0}" = 1 ]; then
    for c in /roms/tools/PortMaster.sh /roms/tools/PortMaster/PortMaster.sh /usr/bin/portmaster; do
      root_file "$ROOT" "$c" && { arg="$c"; break; }
    done
  elif [ "${IS_TOOL:-0}" != 1 ]; then
    # Daijishou strips the .sh from port names; resolve it after binding the card.
    # Tools (TOOL: namespace) and PortMaster carry their exact target already and
    # are resolved only where they live - never falling back to /roms/ports.
    if ! root_file "$ROOT" "$arg" && root_file "$ROOT" "$arg.sh"; then arg="$arg.sh"; fi
  fi
  root_file "$ROOT" "$arg" || { log "launch target missing: $arg"; return 1; }
  # Confine card-sourced targets to their directory even after symlink/.. resolution,
  # mirroring the RetroArch ROM/core guard: a value from a property plus the
  # user-writable games card must not resolve out of /roms/tools or /roms/ports.
  if [ -n "${CONFINE:-}" ]; then
    resolved=$(root_path "$ROOT" "$arg") || { log "cannot resolve launch target: $arg"; return 1; }
    case "$resolved" in "$ROOT$CONFINE"/*) ;; *) log "launch target escapes $CONFINE: $arg"; return 1 ;; esac
  fi
}

# Freeze the validated game-exit policy for the next guest invocation. Values
# stay below the independent four-second return and never affect Fn emergency.
prepare_exit_hold_policy() {
  A36_EXIT_HOLD_MS=$(getprop persist.andr36oid.subsystem.exit_hold_ms)
  case "$A36_EXIT_HOLD_MS" in 1200|2000|3000|4000|5000|6000) ;; *) A36_EXIT_HOLD_MS=1200 ;; esac
  export A36_EXIT_HOLD_MS
}

run_session() {
  case "$cmd" in
    start)
      PORTS_MAYBE_CHANGED=1
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
      if [ -n "${RA_REQUEST:-}" ]; then
        log "launch RetroArch core=$RA_LIBRARY ROM=$RA_ROM (log -> $SESSION_OUT)"
        run_session_payload run_in_subsystem "$arg" -L "$RA_LIBRARY" "$RA_ROM"
        return $?
      fi
      PORTS_MAYBE_CHANGED=1
      log "launch port $arg (log -> $SESSION_OUT)"
      run_session_payload run_in_subsystem /bin/sh -c '
        cd "$(dirname "$1")" || exit 1
        exec "$1"
      ' subsystem-port "$arg" ;;
  esac
}

# The session is busy during preparation too. Record the first boot only after
# setup succeeds and the frontend binary is running inside this rootfs.
mark_frontend_booted() {
  [ "$(getprop persist.andr36oid.subsystem.booted)" = 1 ] && return 0
  [ "$cmd" = start ] || return 0
  local p root exe
  local stamp boot
  stamp=$(process_start_time "$SESSION_PID") || return 0
  boot=$(cat /proc/sys/kernel/random/boot_id)
  while recorded_process_alive "$SESSION_PID" "$stamp" "$boot"; do
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

# shellcheck source=andr36oid-images.sh
. "${A36_IMAGES_LIB:-/system/etc/andr36oid-images.sh}"
# shellcheck source=andr36oid-release.sh
. "${A36_RELEASE_LIB:-/system/etc/andr36oid-release.sh}"
# shellcheck source=andr36oid-prepared.sh
. "${A36_PREPARED_LIB:-/system/etc/andr36oid-prepared.sh}"
# shellcheck source=andr36oid-guest.sh
. "${A36_GUEST_LIB:-/system/etc/andr36oid-guest.sh}"
# shellcheck source=andr36oid-update.sh
. "${A36_UPDATE_LIB:-/system/etc/andr36oid-update.sh}"

cmd=${1:-start}; arg=${2:-}
case "$cmd" in
  select-prop)
    publish request ""
    select_image || { publish select_error failed; operation_done failed; exit 1; }; exit 0 ;;
  install-release-prop)
    job=$(prop rel_dispatch_job); request=$(prop rel_dispatch_request)
    valid_job "$job" || exit 2
    case "$request" in ''|.*|*[!a-zA-Z0-9_.+-]*) exit 2;; esac
    acquire_operation install-release "$job" || {
      publish rel_job "$job"; publish rel_error busy; publish rel_state failed
      publish rel_done_job "$job"; operation_done busy; exit 1
    }
    export A36_REL_LOCKED=1
    trap 'release_operation' EXIT
    exec "$SUPPORT" namespace /system/bin/sh "$0" install-release-worker "$job" "$request" 6<&6 7>&7 9>&9 ;;
  install-release-worker) install_release_worker "$arg" "${3:-}"; exit 0 ;;
  apply-release-partition-prop)
    job=$(prop rel_dispatch_job); pinned=$(prop rel_dispatch_source)
    valid_job "$job" && valid_id "$pinned" || exit 2
    acquire_operation apply-release-to-partition "$job" || {
      publish rel_job "$job"; publish rel_error busy; publish rel_state failed
      publish rel_done_job "$job"; operation_done busy; exit 1
    }
    trap 'release_operation' EXIT
    exec "$SUPPORT" namespace /system/bin/sh "$0" apply-release-partition-worker "$job" "$pinned" 6<&6 7>&7 9>&9 ;;
  apply-release-partition-worker) apply_release_partition_worker "$arg" "${3:-}"; exit 0 ;;
  partition-finalize)
    [ "${A36_RELEASE_RETURNED:-0}" = 1 ] || exit 2
    case "$arg" in ''|*[!0-9]*) exit 2;; esac
    [ "${#arg}" -le 3 ] && [ "$arg" -le 255 ] || exit 2
    partition_shared_finish "$arg"; exit $? ;;
  release-finalize)
    [ "${A36_RELEASE_RETURNED:-0}" = 1 ] || exit 2
    case "$arg" in ''|*[!0-9]*) exit 2;; esac
    [ "${#arg}" -le 3 ] && [ "$arg" -le 255 ] || exit 2
    release_shared_finish "$arg"; exit $? ;;
  optimize-prop)
    job=$(prop opt_dispatch_job); pinned=$(prop opt_dispatch_source)
    valid_job "$job" && valid_id "$pinned" || exit 2
    # Evict in Android's shared namespace BEFORE making the worker private.
    # Keep both flocks across exec, so an idle stage cannot race this boundary.
    acquire_operation optimize || { publish opt_reject_error busy; publish opt_reject_job "$job"; operation_done busy; exit 1; }
    export A36_OPT_LOCKED=1
    trap 'release_operation' EXIT
    exec "$SUPPORT" namespace /system/bin/sh "$0" optimize-worker "$job" "$pinned" 7>&7 9>&9 ;;
  optimize-worker) optimize_worker "$arg" "${3:-}"; exit 0 ;;
  unpack-prop)
    job=$(prop unp_dispatch_job); src=$(prop unp_dispatch_source)
    valid_job "$job" || exit 2
    case "$src" in ''|*/*|.*) exit 2 ;; esac
    primary=
    for d in /mnt/media_rw/*/subsystem/darkosen; do
      [ -f "$d/$src" ] && { primary="$d/$src"; break; }
    done
    [ -n "$primary" ] || { publish opt_error selected_missing; publish opt_state failed; operation_done failed; exit 1; }
    unpack_worker "$job" "$primary"; exit 0 ;;
  unpack-worker) unpack_worker "$arg" "${3:-}"; exit 0 ;;
  boot-decision) boot_decision; exit 0 ;;
  scan-prop)
    if [ "$(getprop sys.andr36oid.subsystem.request)" = scan ]; then
      setprop sys.andr36oid.subsystem.request ""
    else
      # Boot scan: easyroms.ready only says the partition is known. Android mounts
      # it around boot_completed, so a scan started by that trigger can run before
      # the card is there and report no image. Give the mount a little time.
      wait_games_card_mount 30
    fi
    scan_images; exit 0 ;;
  scan) scan_images; exit 0 ;;
  stop-prop)
    [ "$(getprop sys.andr36oid.subsystem.request)" = stop ] && setprop sys.andr36oid.subsystem.request ""
    rc=0; stop_session || rc=$?; operation_done "$rc"; exit "$rc" ;;
  stop) stop_session; exit $? ;;
  recover) recover_session "$arg"; exit $? ;;
  launch-prop)
    req=$(getprop sys.andr36oid.subsystem.request)
    [ "$req" = run ] || exit 0
    want=$(getprop sys.andr36oid.subsystem.port)
    IMAGE=$(getprop sys.andr36oid.subsystem.rootfs)
    HANDOFF=$(getprop sys.andr36oid.subsystem.handoff); HANDOFF=${HANDOFF:-1}
    setprop sys.andr36oid.subsystem.request ""
    case "$want" in
      PRESTAGE) setprop sys.andr36oid.subsystem.request ""; prestage_command prestage; exit $? ;;
      UNSTAGE) setprop sys.andr36oid.subsystem.request ""; prestage_command unstage; exit $? ;;
      ES|OS|"") cmd=start ;;
      SETUP) cmd=setup; HANDOFF=0 ;;
      RA) cmd=run; arg=/opt/retroarch/bin/retroarch ;;
      RACORE:*) cmd=run; arg=/opt/retroarch/bin/retroarch; RA_REQUEST=${want#RACORE:}; RA_REQUEST=${RA_REQUEST:-invalid} ;;
      INIT) cmd=start; SESSION_MODE=systemd ;;
      PM) cmd=run; arg=/roms/tools/PortMaster.sh; IS_PM=1 ;;
      TOOL:*)
        cmd=run; rel=${want#TOOL:}
        case "/$rel/" in *'/../'*|*'/./'*|*'//'*) log "rejected unsafe tool name: $rel"; exit 2 ;; esac
        case "$rel" in
          PortMaster|PortMaster.sh) arg=/roms/tools/PortMaster.sh; IS_PM=1 ;;
          *) arg="/roms/tools/$rel"; IS_TOOL=1; CONFINE=/roms/tools ;;
        esac ;;
      /*) cmd=run; arg="$want" ;;
      *)
        case "/$want/" in *'/../'*|*'/./'*|*'//'*) log "rejected unsafe port name: $want"; exit 2 ;; esac
        cmd=run; arg="/roms/ports/$want"; CONFINE=/roms/ports ;;
    esac ;;
  prestage|unstage) prestage_command "$cmd"; exit $? ;;
  shutdown) FORCE_SHUTDOWN=1; FORCE_UNSTAGE=1; stop_session; exit $? ;;
  start|run|setup) : ;;
  *) echo "usage: $0 start | run <port.sh> | stop | scan | boot-decision" >&2; exit 2 ;;
esac

acquire_operation run || { log "another storage operation is running"; exit 1; }
if ! mkdir "$STATE/session.lock" 2>/dev/null; then
  if session_active || [ ! -f "$STATE/session.lock/pid" ]; then
    # These exits are before the EXIT trap is installed, so clear the operation
    # prop we just published or a stale "storage busy" sticks until the next op.
    log "session already running/preparing; ignoring launch"; release_operation; exit 1
  fi
  recover_session "$(cat "$STATE/session.lock/pid")"
  mkdir "$STATE/session.lock" 2>/dev/null || { release_operation; exit 1; }
fi
printf '%s\n' "$$" > "$STATE/session.lock/pid"
cat /proc/sys/kernel/random/boot_id > "$STATE/session.lock/boot_id"
process_start_time "$$" > "$STATE/session.lock/start_time"
SESSION_OWNED=1
rm -f "$STATE/session.restore-complete"
trap 'finish_session $?' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP
ENTRY_HISTORY_BEFORE=$(phase_clock)
history_begin
ENTRY_HISTORY_AFTER=$(phase_clock)
if [ "$ROTATE_LOGS" = 1 ]; then : > "$LOG"; fi
chmod 0644 "$LOG" 2>/dev/null
SESSION_OUT="$STATE/session.out"
start_live_log || exit 1
setprop sys.andr36oid.subsystem.provisioning "" 2>/dev/null
setprop sys.andr36oid.subsystem.session running
setprop sys.andr36oid.subsystem.launch_error ""
[ -z "${RA_REQUEST:-}" ] || { read_retroarch_request "$RA_REQUEST" || exit 1; }
log "subsystem $cmd arg=$arg handoff=$HANDOFF image=${IMAGE:-default}"
log "timing: broker entered boot_s=$BROKER_ENTER_UPTIME history_ms=$((ENTRY_HISTORY_AFTER-ENTRY_HISTORY_BEFORE)) launch_log_ms=$(phase_clock)"
case "$HANDOFF" in 0|1) : ;; *) log "handoff must be 0 or 1"; exit 2 ;; esac
case "$IMAGE" in ''|/*) : ;; *) log "rootfs image path must be absolute"; exit 2 ;; esac
SESSION_MUTED=$(getprop persist.andr36oid.subsystem.mute 2>/dev/null)
[ "$SESSION_MUTED" = 1 ] || SESSION_MUTED=0
arm_guardian
phase stage stage_mounts || { log "cannot prepare eligible rootfs"; exit 1; }
[ "$cmd" != setup ] || exit 0
eligible_rootfs "$ROOT" || { log "rootfs has no init or launch target"; exit 1; }
[ "$cmd" != run ] || resolve_run_target || exit 1
if [ "${WARM_STAGE:-0}" = 1 ]; then
  start_session_console || exit 1
  phase network-refresh prepare_network || exit 1
fi
# BEGIN session admission
phase policy prepare_session_policy || exit 1
if [ -z "$IMAGE" ] && ! phase native-probe "$GUEST" --probe; then
  log "native input ABI unavailable: install the matching andr36oid kernel"; exit 1
fi
phase hardware-snapshot prepare_guest_hardware || exit 1
if [ "$HANDOFF" = 1 ]; then phase handoff handoff || exit 1; else log "no handoff: Android stays up; display devices hidden"; fi
# Each policy owns its codec writes in a recoverable worker.
phase audio-admission start_audio_route_guard || exit 1
phase mute-admission start_mute_guard || exit 1
# Wait on a child so TERM interrupts wait immediately, including while init runs.
prepare_exit_hold_policy
run_session_worker & SESSION_PID=$!
record_session_worker runner "$SESSION_PID" || exit 1
mark_frontend_booted
wait "$SESSION_PID"; session_rc=$?
exit "$session_rc"
