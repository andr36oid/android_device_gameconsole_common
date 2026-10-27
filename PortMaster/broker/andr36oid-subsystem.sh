#!/system/bin/sh
# SPDX-License-Identifier: MIT
# andr36oid Subsystem for dArkOSen - handoff broker.
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
IMAGE=${A36_IMAGE:-}                          # optional dArkOSen .img to loop-mount
HWC=${A36_HWC:-vendor.hwcomposer-2-1}
GUARD_GRACE=${A36_GUARD_GRACE:-20}
FB=${A36_FB:-/dev/graphics/fb0}               # fbdev node for the handoff splash
SPLASH=${A36_SPLASH:-/system/etc/andr36oid-splash.raw}
[ -f "$SPLASH" ] || SPLASH=/data/local/tmp/andr36oid-splash.raw   # test fallback
# where the user drops the image, searched in order (first existing wins)
IMAGE_SEARCH="/storage/*/subsystem/darkosen/*.img /mnt/media_rw/*/subsystem/darkosen/*.img /data/subsystem/darkosen/*.img"

mkdir -p "$STATE"
LOG="$STATE/subsystem.log"
log() { printf '%s %s\n' "$(cut -d. -f1 /proc/uptime)s" "$*" >> "$LOG"; }
[ "$(id -u)" = 0 ] || { echo "subsystem: must run as root" >&2; exit 1; }

# --- rootfs: loop-mount the dArkOSen btrfs image read-only + writable overlay --
# (needs CONFIG_BTRFS_FS in the kernel; overlay upper MUST be on ext4/f2fs /data,
#  never exFAT. When $ROOT is already a populated dir we skip mounting.)
LOWER="$STATE/lower"; UPPER="$STATE/upper"; WORK="$STATE/work"; LOOPDEV=""
mount_rootfs() {
  if [ -d "$ROOT/usr/bin/emulationstation" ]; then log "rootfs present at $ROOT"; return 0; fi
  [ -z "$IMAGE" ] && for g in $IMAGE_SEARCH; do [ -f "$g" ] && { IMAGE="$g"; break; }; done
  [ -f "$IMAGE" ] || { log "no dArkOSen image found (searched $IMAGE_SEARCH)"; return 1; }
  log "using image $IMAGE"
  # find the ROOTFS (btrfs) partition offset in the image's MBR/GPT
  # part 2 on dArkOSen MBR = Linux ROOTFS; resolve its byte offset via sgdisk/fdisk
  local start size secsz=512
  start=$(unpack_rootfs_start "$IMAGE"); size=$(unpack_rootfs_size "$IMAGE")
  [ -n "$start" ] || { log "cannot locate ROOTFS partition in $IMAGE"; return 1; }
  mkdir -p "$LOWER" "$UPPER" "$WORK" "$ROOT"
  LOOPDEV=$(losetup -f) || { log "losetup -f failed (SELinux? run permissive)"; return 1; }
  # Offset-only loop: do NOT pass a sizelimit. toybox losetup -S truncates the
  # 8.5G value to 32 bits (saw 589299712), so the loop only exposed 589M and btrfs
  # could not reach its chunk root. btrfs reads its own size from the superblock,
  # so a loop spanning offset..EOF (rootfs + the trailing data partition) is fine.
  losetup -o $((start*secsz)) "$LOOPDEV" "$IMAGE" || { log "losetup failed"; return 1; }
  mount -t btrfs -o ro "$LOOPDEV" "$LOWER" || { log "btrfs mount failed (kernel lacks BTRFS_FS?)"; return 1; }
  mount -t overlay overlay -o "lowerdir=$LOWER,upperdir=$UPPER,workdir=$WORK" "$ROOT" || { log "overlay mount failed"; return 1; }
  MOUNTED=1
  log "mounted dArkOS rootfs (btrfs ro + overlay) at $ROOT"
}
# Partition geometry: dArkOSen ships a classic MBR (DOS) table; the rootfs is the
# first 0x83 (Linux) partition and holds btrfs. Parse the MBR in pure shell so the
# broker works on any user-supplied image without sgdisk/fdisk. od + dd are in
# Android toybox. le32/type8 read little-endian fields (device is aarch64 LE).
_le32() { dd if="$1" bs=1 skip="$2" count=4 2>/dev/null | od -An -tu4 | tr -d ' '; }
_type8() { dd if="$1" bs=1 skip="$2" count=1 2>/dev/null | od -An -tu1 | tr -d ' '; }
_find_rootfs() {   # echoes "start_lba sectors" for the first 0x83 partition
  local i base t
  for i in 0 1 2 3; do
    base=$((446 + i*16)); t=$(_type8 "$1" $((base+4)))
    [ "$t" = 131 ] && { echo "$(_le32 "$1" $((base+8))) $(_le32 "$1" $((base+12)))"; return 0; }
  done
  return 1
}
# Prefer real util-linux (sfdisk) when it is shipped (GNU userland, see
# [[ship-gnu-coreutils-utillinux]]); fall back to the pure-shell parser so the
# broker still works on a toybox-only system.
_rootfs_geom() {
  if command -v sfdisk >/dev/null 2>&1; then
    sfdisk -d "$1" 2>/dev/null | awk -F'[=,]' '/type=? *83/ {gsub(/ /,"",$2); gsub(/ /,"",$4); print $2, $4; exit}'
  else
    _find_rootfs "$1"
  fi
}
unpack_rootfs_start() { _rootfs_geom "$1" | awk '{print $1}'; }
unpack_rootfs_size()  { _rootfs_geom "$1" | awk '{print $2}'; }

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
BOUND=""
mount_storage() {
  mkdir -p "$ROOT/roms"
  local n=0 src dst
  for src in /mnt/media_rw/*; do
    [ -d "$src" ] || continue
    if [ "$n" = 0 ]; then dst="$ROOT/roms"; else dst="$ROOT/roms$((n+1))"; fi
    mkdir -p "$dst"
    # bind the vold mount, then drop noexec for this view
    mount -o bind "$src" "$dst" 2>/dev/null && mount -o remount,exec,bind "$dst" 2>/dev/null \
      && { BOUND="$dst $BOUND"; log "storage: $src -> ${dst#$ROOT}"; n=$((n+1)); }
  done
}

# NOTE: we deliberately do NOT unpack dArkOSen's /roms.tar. /roms is a bind mount
# of Android's EASYROMS (and SD2/USB) so the subsystem and Android share one games
# store - see mount_storage(). Ports the user installs on EASYROMS are then visible
# to both. (PortMaster's runtime likewise lives on EASYROMS, installed once there,
# not shipped inside the image.)

# Input mapping: andr36oid ships a known-good es_input.cfg for the console's gamepad
# (the fixed odroidgo3-joypad, deviceName "GO-Super Gamepad"), so ES must NEVER run
# its Configure-Input wizard. dArkOS ES reads /etc/emulationstation/es_input.cfg.
# Install it into the overlay on first run only; a later user remap (ES rewrites the
# same path, landing in the upper) is then kept.
ESINPUT_SRC=${A36_ESINPUT:-/system/etc/andr36oid-es_input.cfg}
[ -f "$ESINPUT_SRC" ] || ESINPUT_SRC=/data/local/tmp/es_input.cfg   # test fallback
prime_input() {
  if [ -f "$UPPER/etc/emulationstation/es_input.cfg" ]; then
    log "es_input.cfg already present in overlay (keeping user mapping)"; return 0
  fi
  if [ -f "$ESINPUT_SRC" ]; then
    mkdir -p "$ROOT/etc/emulationstation"
    cp "$ESINPUT_SRC" "$ROOT/etc/emulationstation/es_input.cfg" && log "installed shipped es_input.cfg (no wizard)"
  else
    log "no shipped es_input.cfg at $ESINPUT_SRC; ES wizard will run"
  fi
}

bind_core() {
  mkdir -p "$ROOT/dev" "$ROOT/proc" "$ROOT/sys" "$ROOT/tmp" "$ROOT/run"; chmod 1777 "$ROOT/tmp" 2>/dev/null
  mount -o bind /dev "$ROOT/dev"; mount -o bind /proc "$ROOT/proc"; mount -o bind /sys "$ROOT/sys"
}

# Kill everything running inside the subsystem (EmulationStation and whatever it
# launched - an emulator that hangs holds /dev/dri/card0's DRM master, so just
# restarting SurfaceFlinger would not get the display back). Identify them by
# their chroot root pointing into $ROOT; belt-and-braces fuser on card0.
kill_subsystem() {
  local p pid r
  for p in /proc/[0-9]*; do
    r=$(readlink "$p/root" 2>/dev/null) || continue
    case "$r" in "$ROOT"|"$ROOT"/*) pid=${p#/proc/}; kill -9 "$pid" 2>/dev/null;; esac
  done
  command -v fuser >/dev/null 2>&1 && fuser -k /dev/dri/card0 2>/dev/null
  sleep 1
}

# --- teardown (idempotent) ----------------------------------------------------
restore_android() {
  kill_subsystem
  for b in $BOUND; do umount "$b" 2>/dev/null; done
  umount "$ROOT/dev" 2>/dev/null; umount "$ROOT/proc" 2>/dev/null; umount "$ROOT/sys" 2>/dev/null
  if [ -n "$LOOPDEV" ]; then
    umount "$ROOT" 2>/dev/null; umount "$LOWER" 2>/dev/null; losetup -d "$LOOPDEV" 2>/dev/null
  fi
  show_splash   # logo over the blank gap until SurfaceFlinger repaints Android
  setprop ctl.start "$HWC" 2>/dev/null; sleep 1
  setprop ctl.start surfaceflinger 2>/dev/null; start 2>/dev/null
}

# --- guardian: restore Android if we die without cleaning up ------------------
arm_guardian() {
  rm -f "$STATE/subsystem.clean"
  local me=$$
  (
    while kill -0 "$me" 2>/dev/null; do sleep 1; done
    [ -f "$STATE/subsystem.clean" ] && exit 0
    sleep "$GUARD_GRACE"; [ -f "$STATE/subsystem.clean" ] && exit 0
    echo "GUARDIAN restore $(cut -d. -f1 /proc/uptime)s" >> "$LOG"
    setprop ctl.start "$HWC"; sleep 1; setprop ctl.start surfaceflinger; start
  ) & GUARD=$!
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
  log "stop framework"; stop
  i=0; while [ "$(getprop init.svc.surfaceflinger)" = running ] && [ "$i" -lt 10 ]; do sleep 1; i=$((i+1)); done
  log "stop composer"; setprop ctl.stop "$HWC"
  i=0; while [ "$(getprop init.svc.$HWC)" = running ] && [ "$i" -lt 10 ]; do sleep 1; i=$((i+1)); done
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
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  HOME=/home/ark TERM=linux \
  SDL_VIDEODRIVER=kmsdrm SDL_VIDEO_EGL_DRIVER=libEGL.so SDL_KMSDRM_REQUIRE_DRM_MASTER=1 \
  SDL_GAMECONTROLLERCONFIG_FILE=/opt/inttools/gamecontrollerdb.txt \
    chroot "$ROOT" "$@"
}

cmd=${1:-start}; arg=${2:-}

# launch-prop: invoked by the init service on a property trigger (the Daijisho
# bridge app sets these, see [[daijishou-shim]]). sys.andr36oid.subsystem.port
# holds the request: "ES" -> full frontend, "PM" -> PortMaster GUI, or a port
# path (absolute, or relative to /roms/ports) -> run that single port.
if [ "$cmd" = launch-prop ]; then
  want=$(getprop sys.andr36oid.subsystem.port 2>/dev/null)
  setprop sys.andr36oid.subsystem.request "" 2>/dev/null
  case "$want" in
    ES|"") set -- start ;;
    PM)    set -- run "/roms/tools/PortMaster/PortMaster.sh" ;;
    /*)    set -- run "$want" ;;            # absolute path inside the subsystem
    *)     set -- run "/roms/ports/$want" ;;
  esac
  cmd=$1; arg=${2:-}
fi

case "$cmd" in
  stop) : > "$STATE/subsystem.clean"; restore_android; log "forced stop"; exit 0 ;;
esac

: > "$LOG"; log "subsystem $cmd arg=$arg"
trap 'rc=$?; restore_android; : > "$STATE/subsystem.clean"; kill "${GUARD:-0}" 2>/dev/null; log "exit rc=$rc"; exit $rc' EXIT INT TERM HUP
mount_rootfs || { echo "subsystem: no rootfs" >&2; exit 1; }
fix_exec_bits
prime_input
arm_guardian
bind_core
mount_storage
handoff
# Session output (ES + whatever it launches) goes here so a stuck emulator/port
# leaves a trace - the handoff blips adb, so we cannot watch it live.
SESSION_OUT="$STATE/session.out"
case "$cmd" in
  start) log "launch EmulationStation (log -> $SESSION_OUT)"
         run_in_subsystem /usr/bin/emulationstation/emulationstation > "$SESSION_OUT" 2>&1 ;;
  run)   # The Daijisho bridge strips a port's .sh to name it, so "/roms/ports/<name>"
         # may not exist while "<name>.sh" does. /roms is bound under $ROOT now, so
         # add .sh back when the bare path is missing (handles both bare names and
         # full *.sh paths).
         [ ! -e "$ROOT$arg" ] && [ -e "$ROOT$arg.sh" ] && arg="$arg.sh"
         # Run a single port the way EmulationStation's "ports" system does:
         # perfmax (governors) -> nice the port -> perfnorm. cwd = the port's dir.
         pdir=$(dirname "$arg"); log "launch port $arg (cwd $pdir, log -> $SESSION_OUT)"
         run_in_subsystem /bin/sh -lc "cd '$pdir' 2>/dev/null; sudo perfmax performance '$arg' 2>/dev/null; nice -n -19 '$arg'; sudo perfnorm 2>/dev/null" > "$SESSION_OUT" 2>&1 ;;
  *) echo "usage: $0 start | run <port.sh> | stop" >&2; exit 2 ;;
esac
log "subsystem session ended"
# EXIT trap restores Android.
