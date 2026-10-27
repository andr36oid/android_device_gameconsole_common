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
# How a full "start" brings up the dArkOS frontend: systemd (B1, boot dArkOS's own
# init in a PID namespace - full session state), wrapper (run ES's wrapper directly,
# the proven 60fps fallback), or bare (the raw ES binary, legacy). systemd falls back
# to wrapper automatically if it cannot boot.
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
  for gid in 1023 1015 1004 9997; do   # media_rw, sdcard_rw, input, everybody
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
  chmod 0666 /dev/uinput 2>/dev/null
  for n in /dev/dri/card0 /dev/dri/renderD128 /dev/mali0; do [ -e "$n" ] && chmod 0666 "$n" 2>/dev/null; done
  # Raw input nodes for ports that read evdev directly (ES itself goes through ogage's
  # uinput device above, but Kodi and some ports open /dev/input/event* themselves).
  for n in /dev/input/event* /dev/input/js*; do [ -e "$n" ] && chmod 0666 "$n" 2>/dev/null; done
  grant_storage_groups
  mkdir -p "$ROOT/run/user/0" "$ROOT/run/user/1000" 2>/dev/null
  chmod 0700 "$ROOT/run/user/0" "$ROOT/run/user/1000" 2>/dev/null
  chown 1000:1000 "$ROOT/run/user/1000" 2>/dev/null   # ark
  [ -e "$ROOT/dev/tty1" ] || ln -sf tty0 "$ROOT/dev/tty1" 2>/dev/null
}

# The dArkOS session/setup units we bring up under systemd. These configure the device
# the way dArkOS expects (CPU/GPU/RAM governors, screen gamma, audio routing, battery
# LED, the WiFi/BT status daemon the ES header reads, the Fn-button watcher), plus the
# controller daemon (ogage) and EmulationStation itself. Deliberately NOT included:
#   - firstboot.service: it only runs /boot/expandtoexfat.sh, dArkOS's SD-card grow /
#     exFAT-format step. There is no card to expand in our loop-mounted container, so it
#     is inapplicable (not session setup). ES's "After=firstboot" is likewise moot here.
#   - NetworkManager/wpa_supplicant/networking/wifi_monitor: network daemons, not setup;
#     the subsystem is offline-first and they need dbus + the wifi stack. es-status-daemon
#     stays (it just reports status; shows "disconnected" offline, which is correct).
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
  local d u
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
  prep_darkos_env
  [ -x "$ROOT/sbin/init" ] || [ -L "$ROOT/sbin/init" ] || { log "no /sbin/init in rootfs; cannot boot systemd"; return 1; }
  install_session_units
  log "boot dArkOSen systemd -> andr36oid-session.target (PID-ns container; log -> $SESSION_OUT)"
  # bind_core already bound the host /proc at $ROOT/proc; inside the new pidns it must
  # be that namespace's proc instead. We run the ns-internal setup in a sh wrapper and
  # exec systemd as the final (PID1) image. $container makes systemd take its container
  # path (skip fsck/hwclock/module loads/host-only units); api fs are pre-placed.
  # Only PID+mount+UTS namespaces: the kernel has CONFIG_PID_NS/UTS_NS but NOT IPC_NS
  # (that needs CONFIG_SYSVIPC, which Android disables), so -i would make unshare fail.
  unshare -f -p -m -u /system/bin/sh -c "
    umount '$ROOT/proc' 2>/dev/null
    mount -t proc proc '$ROOT/proc' 2>/dev/null
    mount -t tmpfs tmpfs '$ROOT/run' 2>/dev/null
    mount -t tmpfs -o mode=1777 tmpfs '$ROOT/tmp' 2>/dev/null
    mkdir -p '$ROOT/run/user/0' '$ROOT/run/user/1000' 2>/dev/null
    export container=andr36oid-subsystem HOME=/root TERM=linux \
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    exec chroot '$ROOT' /sbin/init systemd.unit=andr36oid-session.target systemd.log_target=kmsg systemd.log_level=info systemd.show_status=true
  "
}

# Proven fallback (the 60fps ES bring-up): run dArkOSen's emulationstation.sh wrapper
# directly (it does the uinput chmod, starts gptokeyb, sets XDG_RUNTIME_DIR and loops
# ES) without systemd. Kept selectable so the console still has a working frontend if
# the systemd path needs more bring-up work on a given image.
run_es_wrapper() {
  prep_darkos_env
  local w=/usr/bin/emulationstation/emulationstation.sh
  [ -x "$ROOT$w" ] || w=/usr/bin/emulationstation/emulationstation
  log "launch EmulationStation via wrapper $w (log -> $SESSION_OUT)"
  XDG_RUNTIME_DIR=/run/user/0 run_in_subsystem /bin/sh -lc "exec $w"
}

cmd=${1:-start}; arg=${2:-}

# launch-prop: invoked by the init service on a property trigger (the Daijisho
# bridge app sets these, see [[daijishou-shim]]). sys.andr36oid.subsystem.port
# holds the request: "ES" -> full frontend, "PM" -> PortMaster GUI, or a port
# path (absolute, or relative to /roms/ports) -> run that single port.
if [ "$cmd" = launch-prop ]; then
  req=$(getprop sys.andr36oid.subsystem.request 2>/dev/null)
  want=$(getprop sys.andr36oid.subsystem.port 2>/dev/null)
  setprop sys.andr36oid.subsystem.request "" 2>/dev/null
  if [ "$req" = scan ]; then
    set -- scan
  else
    case "$want" in
      ES|"") set -- start ;;
      PM)    set -- run "/roms/tools/PortMaster/PortMaster.sh"; IS_PM=1 ;;
      /*)    set -- run "$want" ;;            # absolute path inside the subsystem
      *)     set -- run "/roms/ports/$want" ;;
    esac
  fi
  cmd=$1; arg=${2:-}
fi

# scan: no handoff. Just report whether a dArkOSen image is present so the
# Settings page can show status and enable its launch buttons. Sets the
# read-only-ish status prop sys.andr36oid.subsystem.image to 1 or 0.
if [ "$cmd" = scan ]; then
  found=0
  for g in $IMAGE_SEARCH; do [ -f "$g" ] && { found=1; break; }; done
  setprop sys.andr36oid.subsystem.image "$found" 2>/dev/null
  exit 0
fi

case "$cmd" in
  stop) : > "$STATE/subsystem.clean"; restore_android; log "forced stop"; exit 0 ;;
esac

: > "$LOG"; log "subsystem $cmd arg=$arg"
# After a PortMaster session, ports may have been installed; ask the setup worker
# to refresh Daijishou's Ports library so it matches EmulationStation without a reboot.
trap 'rc=$?; restore_android; [ "${IS_PM:-0}" = 1 ] && setprop sys.andr36oid.ports_changed 1 2>/dev/null; : > "$STATE/subsystem.clean"; kill "${GUARD:-0}" 2>/dev/null; log "exit rc=$rc"; exit $rc' EXIT INT TERM HUP
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
  start) case "$SESSION_MODE" in
           systemd) boot_subsystem_session > "$STATE/systemd.out" 2>&1 || { log "systemd boot failed (see systemd.out), falling back to ES wrapper"; run_es_wrapper > "$SESSION_OUT" 2>&1; } ;;
           wrapper) run_es_wrapper > "$SESSION_OUT" 2>&1 ;;
           bare)    log "launch bare EmulationStation (log -> $SESSION_OUT)"
                    run_in_subsystem /usr/bin/emulationstation/emulationstation > "$SESSION_OUT" 2>&1 ;;
         esac ;;
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
