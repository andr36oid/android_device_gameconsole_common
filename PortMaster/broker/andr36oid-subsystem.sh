#!/system/bin/sh
# shellcheck shell=ksh
# shellcheck disable=SC2015 # Guard chains intentionally fail on any precondition.
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
#   andr36oid-subsystem.sh optimize        # optimize resolved EASYROMS original
# Root-only launch/scan diagnostics: A36_IMAGE=/data/.../darkosen.img, or
# A36_IMAGE=/extracted/root A36_ROOT=/extracted/root. Init ignores A36_IMAGE.

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
SESSION_MODE=${A36_SESSION_MODE:-systemd}
FB=${A36_FB:-/dev/graphics/fb0}               # fbdev node for the handoff splash
SPLASH=${A36_SPLASH:-/system/etc/andr36oid-splash.raw}
[ -f "$SPLASH" ] || SPLASH=/data/local/tmp/andr36oid-splash.raw   # test fallback

mkdir -p "$STATE"
LOG="$STATE/subsystem.log"
log() { printf '%s %s\n' "$(cut -d. -f1 /proc/uptime)s" "$*" >> "$LOG"; }
[ "$(id -u)" = 0 ] || { echo "subsystem: must run as root" >&2; exit 1; }

# --- shared image protocol ---------------------------------------------------
CONTROL=${A36_CONTROL:-/data/misc/andr36oid-subsystem}
SUPPORT=${A36_EROFS_SUPPORT:-/system/bin/andr36oid-erofs-support}
PROP=sys.andr36oid.subsystem
SELECT_PROP=persist.sys.andr36oid.subsystem.selected
PREFER_PROP=persist.sys.andr36oid.subsystem.prefer_erofs
export LC_ALL=C
mkdir -p "$CONTROL"; chmod 0700 "$CONTROL"
prop() { getprop "$PROP.$1" 2>/dev/null; }
publish() { setprop "$PROP.$1" "$2" 2>/dev/null; }
field() { printf '%s\n' "$1" | sed -n "s/^$2=//p"; }
valid_id() { [ "${#1}" = 64 ] && case "$1" in *[!0-9a-f]*) false;; *) true;; esac; }
valid_job() { [ "${#1}" = 16 ] && case "$1" in *[!0-9a-f]*) false;; *) true;; esac; }
# Shared encoding vectors: PortMaster/erofs-tools/selection-vectors.tsv.
selection_id() { printf '%s\000%s' "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" "$2" | sha256sum | cut -d' ' -f1; }
hex_name() { printf '%s' "$1" | od -An -tx1 | tr -d ' \n'; }
clock_seconds() { cut -d. -f1 /proc/uptime; }
boot_id() { cat /proc/sys/kernel/random/boot_id; }
process_start() { sed 's/^.*) //' "/proc/$1/stat" 2>/dev/null | awk '{print $20}'; }
acquire_operation() {
  exec 9>"$CONTROL/operation.lock"
  flock -n 9 || return 1
  publish operation "$1"
}
release_operation() { publish operation ""; exec 9>&-; }

# Exactly the SaveBackup/EASYROMS identity rule, accepting ro mounts for launch.
games_card() {
  CARD=; CARD_UUID=; CARD_RW=0; CARD_DEV=
  local id dev dir _fs opts _rest
  id=$(getprop sys.andr36oid.easyroms.id)
  if [ -z "$id" ] && [ "$(getprop sys.andr36oid.easyroms.ready)" != 1 ]; then id=public:179,7; fi
  [ -n "$id" ] || return 1
  while read -r dev dir _fs opts _rest; do
    [ "$dev" = "/dev/block/vold/$id" ] || continue
    case "$dir" in /mnt/media_rw/?*) ;; *) continue;; esac
    [ -d "$dir" ] && [ "$(readlink -f "$dir")" = "$dir" ] || continue
    CARD=$dir; CARD_DEV=$dev
    CARD_UUID=$(printf '%s' "${dir##*/}" | tr '[:upper:]' '[:lower:]')
    case ",$opts," in *,rw,*) CARD_RW=1;; esac
    return 0
  done < /proc/mounts
  return 1
}
# Only canonical immediate regular files with safe names. No /storage aliases.
candidate_probe() { "$SUPPORT" candidate "$IMAGE_DIR" "${1##*/}" 2>/dev/null; }
completion_record() { "$SUPPORT" record "$1" "$CARD_UUID" 2>/dev/null; }
use_original() {
  SOURCE=$1; SOURCE_ID=$2; SOURCE_PROBE=$3
  GENERATION=$(field "$SOURCE_PROBE" generation); FAMILY=$SOURCE_ID
  EFFECTIVE=$SOURCE; ACTIVE_ID=$SOURCE_ID; EFFECTIVE_FORMAT=btrfs
  RESOLVE_STATE=ready_plain
}
use_completed() {
  SOURCE=$1; SOURCE_ID=$2; RECORD=$3
  FAMILY=$(field "$RECORD" source_id); GENERATION=$(field "$RECORD" generation)
  EFFECTIVE=$SOURCE; ACTIVE_ID=$SOURCE_ID; EFFECTIVE_FORMAT=erofs
  RESOLVE_STATE=ready_erofs
}
# Optional argument pins optimize to the ID accepted by its request, even if the
# Settings selection changes while init is starting the worker.
resolve_selected_image() {
  SOURCE=; SOURCE_ID=; SOURCE_PROBE=; EFFECTIVE=; ACTIVE_ID=; FAMILY=; GENERATION=
  RECORD=; RESOLVE_STATE=no_image; EFFECTIVE_FORMAT=; DIAGNOSTIC_DIR=0
  local selected candidate name candidates id info format record first_original='' first_optimized='' first_info='' first_record=''
  selected=${1-$(getprop "$SELECT_PROP")}
  RESOLVE_SELECTION=$selected; RESOLVE_PREFER=$(getprop "$PREFER_PROP")
  [ "$RESOLVE_PREFER" = 0 ] || RESOLVE_PREFER=1
  if [ -n "$IMAGE" ]; then
    # A36_IMAGE is a root-only CLI diagnostic override, never used by init.
    if [ -d "$IMAGE/usr/bin/emulationstation" ]; then
      SOURCE=$IMAGE; EFFECTIVE=$IMAGE; DIAGNOSTIC_DIR=1; RESOLVE_STATE=ready_plain; return 0
    fi
    info=$("$SUPPORT" probe "$IMAGE" 2>/dev/null) || { RESOLVE_STATE=invalid_image; return 1; }
    SOURCE=$IMAGE; EFFECTIVE=$IMAGE; SOURCE_PROBE=$info
    SOURCE_ID=$(printf '%s' "$IMAGE" | sha256sum | cut -d' ' -f1); FAMILY=$SOURCE_ID
    GENERATION=$(field "$info" generation); EFFECTIVE_FORMAT=$(field "$info" format)
    [ -n "$GENERATION" ] || GENERATION=$(printf '%s' "$info" | sha256sum | cut -d' ' -f1)
    ACTIVE_ID=$SOURCE_ID; RESOLVE_STATE=ready_plain
    [ "$EFFECTIVE_FORMAT" = erofs ] && RESOLVE_STATE=ready_erofs
    return 0
  fi
  if [ -n "$selected" ] && ! valid_id "$selected"; then RESOLVE_STATE=selected_missing; return 1; fi
  if ! games_card; then
    RESOLVE_STATE=no_card; [ -n "$selected" ] && RESOLVE_STATE=selected_missing
    return 1
  fi
  IMAGE_DIR="$CARD/subsystem/darkosen"
  if [ ! -d "$IMAGE_DIR" ] || [ "$(readlink -f "$IMAGE_DIR")" != "$IMAGE_DIR" ]; then
    [ -n "$selected" ] && RESOLVE_STATE=selected_missing
    return 1
  fi
  # Helper includes hidden files and returns safe names in bytewise order.
  candidates=$("$SUPPORT" list "$IMAGE_DIR") || { RESOLVE_STATE=invalid_image; return 1; }
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    candidate="$IMAGE_DIR/$name"
    [ -f "$candidate" ] && [ ! -L "$candidate" ] || continue
    # Validate names BEFORE passing them through command substitution.
    case "${candidate##*/}" in *[[:cntrl:]]*) continue;; esac
    id=$(selection_id "$CARD_UUID" "${candidate##*/}")
    if [ -n "$selected" ] && [ "$selected" != "$id" ]; then continue; fi
    info=$(candidate_probe "$candidate") || {
      if [ "$selected" = "$id" ]; then SOURCE_ID=$id; RESOLVE_STATE=invalid_image; return 1; fi
      continue
    }
    format=$(field "$info" format)
    case "$candidate" in
      *.erofs.img)
        # Reserved generated names always need a matching committed record.
        if [ "$format" != erofs ]; then
          if [ "$selected" = "$id" ]; then SOURCE_ID=$id; RESOLVE_STATE=invalid_image; return 1; fi
          continue
        fi
        record=$(completion_record "$candidate") || {
          if [ "$selected" = "$id" ]; then SOURCE_ID=$id; RESOLVE_STATE=invalid_image; return 1; fi
          continue
        } ;;
      *)
        # Raw EROFS is usable only with a validated completion record too.
        record=
        if [ "$format" = erofs ]; then
          record=$(completion_record "$candidate") || {
            if [ "$selected" = "$id" ]; then SOURCE_ID=$id; RESOLVE_STATE=invalid_image; return 1; fi
            continue
          }
        fi ;;
    esac
    if [ -n "$selected" ]; then
      if [ "$format" = btrfs ]; then use_original "$candidate" "$id" "$info"; else use_completed "$candidate" "$id" "$record"; fi
      break
    fi
    if [ "$format" = btrfs ] && [ -z "$first_original" ]; then
      first_original=$candidate; first_info=$info
    elif [ "$format" = erofs ] && [ -z "$first_optimized" ]; then
      first_optimized=$candidate; first_record=$record
    fi
  done <<EOF_CANDIDATES
$candidates
EOF_CANDIDATES
  if [ -z "$selected" ]; then
    if [ -n "$first_original" ]; then
      use_original "$first_original" "$(selection_id "$CARD_UUID" "${first_original##*/}")" "$first_info"
    elif [ -n "$first_optimized" ]; then
      use_completed "$first_optimized" "$(selection_id "$CARD_UUID" "${first_optimized##*/}")" "$first_record"
    fi
  fi
  if [ -z "$SOURCE" ]; then [ -n "$selected" ] && RESOLVE_STATE=selected_missing; return 1; fi
  if [ "$EFFECTIVE_FORMAT" = btrfs ] && [ "$RESOLVE_PREFER" != 0 ]; then
    local companion="${SOURCE%.img}.erofs.img"
    if [ -e "$companion" ] || [ -L "$companion" ]; then
      record=$(completion_record "$companion") || { RESOLVE_STATE=fallback_plain; return 0; }
      if [ "$(field "$record" source_id)" = "$SOURCE_ID" ] && [ "$(field "$record" generation)" = "$GENERATION" ]; then
        EFFECTIVE=$companion; ACTIVE_ID=$(selection_id "$CARD_UUID" "${companion##*/}")
        EFFECTIVE_FORMAT=erofs; RESOLVE_STATE=ready_erofs; RECORD=$record
      else
        RESOLVE_STATE=fallback_plain
      fi
    fi
  fi
  return 0
}
publish_resolution() {
  local available=$1 seq
  # A busy scan and a session may publish concurrently. Serialize this small
  # boundary independently of the lifetime operation lock so sequences cannot
  # collide, and expose a guard against reading fields during an update.
  exec 8>"$CONTROL/resolve.lock"
  flock 8 || return 1
  seq=$(prop resolve_seq); case "$seq" in ''|*[!0-9]*) seq=0;; esac
  publish resolve_guard "$seq:writing"
  # Clear stale details too; availability and sequence form the result boundary.
  publish resolve_selection "${RESOLVE_SELECTION:-}"; publish resolve_prefer "${RESOLVE_PREFER:-}"
  publish resolved "$SOURCE_ID"; publish active "$ACTIVE_ID"
  publish resolve_state "$RESOLVE_STATE"
  publish image "$available"; publish resolve_seq "$((seq+1))"
  publish resolve_guard "$((seq+1))"
  exec 8>&-
}
scan_images() {
  if ! acquire_operation scan; then
    SOURCE_ID=; ACTIVE_ID=; RESOLVE_SELECTION=; RESOLVE_PREFER=; RESOLVE_STATE=busy; publish_resolution 0; return 1
  fi
  reconcile_optimize
  local available=0
  resolve_selected_image && available=1
  publish_resolution "$available"
  release_operation
}

# --- read-only lower and per-family writable state ----------------------------
LOWER="$STATE/lower"; UPPER=; WORK=; LOOPDEV=""; MOUNTED=0
mounted_at() { awk -v p="$1" '$2==p {found=1} END {exit !found}' /proc/mounts; }
capture_legacy_evidence() {
  [ -d "$STATE/upper" ] && [ ! -e "$CONTROL/legacy-source-evidence" ] || return 0
  # Only the old broker's successful mount message establishes this evidence.
  # Capture it before session log rotation, and never infer it from a failed try.
  awk '/^[^ ]* using image / {p=$0; sub(/^[^ ]* using image /,"",p)}
       /mounted dArkOS rootfs \(btrfs ro \+ overlay\)/ {good=p}
       END {if (good!="") print good}' "$LOG" > "$CONTROL/legacy-source-evidence.tmp"
  if [ -s "$CONTROL/legacy-source-evidence.tmp" ]; then
    mv "$CONTROL/legacy-source-evidence.tmp" "$CONTROL/legacy-source-evidence"
  else rm -f "$CONTROL/legacy-source-evidence.tmp"; fi
}
legacy_sole_original() {
  local names name info count=0
  names=$("$SUPPORT" list "$IMAGE_DIR") || return 1
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    case "$name" in *.erofs.img) continue;; esac
    info=$(candidate_probe "$IMAGE_DIR/$name") || continue
    if [ "$(field "$info" format)" = btrfs ]; then count=$((count+1)); fi
  done <<EOF_LEGACY
$names
EOF_LEGACY
  [ "$count" = 1 ]
}
prepare_writable_state() {
  local base="$STATE/images/$FAMILY/$GENERATION" association evidence
  UPPER="$base/upper"; WORK="$base/work"
  mkdir -p "$base" || return 1
  if [ -d "$STATE/upper" ]; then
    association=$(cat "$CONTROL/legacy-family" 2>/dev/null) || association=
    if [ -z "$association" ] && [ -z "$IMAGE" ] && [ "$(field "$SOURCE_PROBE" format)" = btrfs ] && [ ! -e "$UPPER" ]; then
      evidence=$(cat "$CONTROL/legacy-source-evidence" 2>/dev/null) || evidence=
      if [ "$evidence" = "$SOURCE" ] && legacy_sole_original && [ ! -d "$STATE/work/index" ] && "$SUPPORT" overlay-safe "$STATE/upper"; then
        # Keep the old upper at its original path. Only this proven family may
        # refer to it; new generations and all other originals get fresh state.
        printf '%s\n' "$FAMILY:$GENERATION" > "$CONTROL/legacy-family.tmp"
        "$SUPPORT" fsync "$CONTROL/legacy-family.tmp" || return 1
        mv "$CONTROL/legacy-family.tmp" "$CONTROL/legacy-family" || return 1
        "$SUPPORT" fsync "$CONTROL" || return 1
        association="$FAMILY:$GENERATION"
      fi
    fi
    if [ "$association" = "$FAMILY:$GENERATION" ] && [ ! -e "$UPPER" ]; then
      "$SUPPORT" overlay-safe "$STATE/upper" || return 1
      ln -s "$STATE/upper" "$UPPER" || return 1
      log "associated retained legacy upper with $FAMILY:$GENERATION"
    elif [ -z "$association" ]; then
      log "legacy upper retained at $STATE/upper; manual ownership/overlay-feature migration required"
    fi
  fi
  mkdir -p "$UPPER" "$WORK" "$LOWER" "$ROOT"
}

attach_lower() {
  local path=$1 format=$2 info record current offset=0
  info=$("$SUPPORT" probe "$path") || return 1
  [ "$(field "$info" format)" = "$format" ] || return 1
  if [ "$format" = btrfs ]; then
    [ "$(field "$info" generation)" = "$GENERATION" ] || return 1
    offset=$(field "$info" offset)
  else
    # Revalidate completion and the source family immediately before mounting.
    if [ -z "$IMAGE" ]; then
      record=$(completion_record "$path") || return 1
      [ "$(field "$record" source_id)" = "$FAMILY" ] && [ "$(field "$record" generation)" = "$GENERATION" ] || return 1
      if [ "$SOURCE" != "$path" ]; then
        current=$("$SUPPORT" probe "$SOURCE") || return 1
        [ "$(field "$current" generation)" = "$GENERATION" ] || return 1
      fi
    fi
  fi
  LOOPDEV=$(losetup -f) || return 1
  # Offset only: toybox -S truncates the 8.5 GB partition limit to 32 bits.
  losetup -r -o "$offset" "$LOOPDEV" "$path" || { LOOPDEV=; return 1; }
  if ! "$SUPPORT" loop-owned "$LOOPDEV" "$(field "$info" device)" "$(field "$info" inode)" "$offset"; then
    # Attachment succeeded but its backing file changed during resolution.
    losetup -d "$LOOPDEV"; LOOPDEV=; return 1
  fi
  local opts=ro
  [ "$format" = btrfs ] && opts=ro,nologreplay
  if ! mount -t "$format" -o "$opts" "$LOOPDEV" "$LOWER"; then
    losetup -d "$LOOPDEV"; LOOPDEV=; return 1
  fi
  if ! "$SUPPORT" metadata "$LOWER" >/dev/null; then
    umount "$LOWER"; losetup -d "$LOOPDEV"; LOOPDEV=; return 1
  fi
  return 0
}
mount_rootfs() {
  # Must be called under the operation lock, before any populated-ROOT shortcut.
  resolve_selected_image || { publish_resolution 0; log "image resolve: $RESOLVE_STATE"; return 1; }
  if [ "$DIAGNOSTIC_DIR" = 1 ]; then
    [ "$SOURCE" = "$ROOT" ] || { log "directory diagnostic requires A36_ROOT=A36_IMAGE"; return 1; }
    UPPER="$STATE/upper"; WORK="$STATE/work"; publish_resolution 1; return 0
  fi
  local identity="$FAMILY:$GENERATION:$ACTIVE_ID" old
  old=$(cat "$CONTROL/mounted-image" 2>/dev/null) || old=
  if mounted_at "$ROOT"; then
    [ "$old" = "$identity" ] || { log "refusing mounted ROOT from a different source/lower"; return 1; }
    # Reuse only a recorded overlay; do not mistake an extracted tree for it.
    prepare_writable_state || return 1
    LOOPDEV=$(awk -v p="$LOWER" '$2==p {print $1; exit}' /proc/mounts)
    [ -n "$LOOPDEV" ] || return 1
    local mounted_probe mounted_offset=0
    mounted_probe=$("$SUPPORT" probe "$EFFECTIVE") || return 1
    [ "$EFFECTIVE_FORMAT" != btrfs ] || mounted_offset=$(field "$mounted_probe" offset)
    "$SUPPORT" loop-owned "$LOOPDEV" "$(field "$mounted_probe" device)" "$(field "$mounted_probe" inode)" "$mounted_offset" || return 1
    MOUNTED=1; publish_resolution 1; return 0
  fi
  [ ! -d "$ROOT/usr/bin/emulationstation" ] || { log "unrecorded populated ROOT; use explicit A36_IMAGE directory diagnostic"; return 1; }
  mounted_at "$LOWER" && { log "unowned lower mount remains; explicit recovery required"; return 1; }
  prepare_writable_state || return 1
  if ! attach_lower "$EFFECTIVE" "$EFFECTIVE_FORMAT"; then
    if [ "$EFFECTIVE" != "$SOURCE" ] && [ "$EFFECTIVE_FORMAT" = erofs ]; then
      EFFECTIVE=$SOURCE; ACTIVE_ID=$SOURCE_ID; EFFECTIVE_FORMAT=btrfs; RESOLVE_STATE=fallback_plain
      attach_lower "$SOURCE" btrfs || { RESOLVE_STATE=invalid_image; publish_resolution 0; return 1; }
    else RESOLVE_STATE=invalid_image; publish_resolution 0; return 1; fi
  fi
  if ! mount -t overlay overlay -o "lowerdir=$LOWER,upperdir=$UPPER,workdir=$WORK,index=off,metacopy=off,nfs_export=off" "$ROOT"; then
    umount "$LOWER"; losetup -d "$LOOPDEV"; LOOPDEV=; return 1
  fi
  MOUNTED=1
  printf '%s\n' "$FAMILY:$GENERATION:$ACTIVE_ID" > "$CONTROL/mounted-image"
  log "using image $SOURCE; lower $EFFECTIVE; upper $UPPER"
  publish_resolution 1
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
      && { BOUND="$dst $BOUND"; log "storage: $src -> ${dst#"$ROOT"}"; n=$((n+1)); }
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
    mounted_at "$ROOT" || rm -f "$CONTROL/mounted-image"
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

# --- isolated optimization worker (never enters display/session paths) --------
OPT_DIR="$CONTROL/optimize"
MKFS=${A36_MKFS_EROFS:-/system/bin/mkfs.erofs}
FSCK=${A36_FSCK_EROFS:-/system/bin/fsck.erofs}
opt_journal() {
  local tmp="$OPT_DIR/journal.tmp.$$"
  {
    printf 'version=1\nboot=%s\npid=%s\nprocess_start=%s\njob=%s\nphase=%s\n' "$(boot_id)" "$$" "$(process_start $$)" "$OPT_JOB" "$OPT_PHASE"
    printf 'card_uuid=%s\nsource=%s\nsource_device=%s\nsource_inode=%s\noffset=%s\n' "$OPT_UUID" "$OPT_SOURCE" "$OPT_SOURCE_DEV" "$OPT_SOURCE_INO" "$OPT_OFFSET"
    printf 'partial=%s\npartial_device=%s\npartial_inode=%s\nmeta_partial=%s\nmeta_device=%s\nmeta_inode=%s\nfinal=%s\n' "$PARTIAL" "$PART_DEV" "$PART_INO" "$META_PARTIAL" "$META_DEV" "$META_INO" "$FINAL"
    printf 'source_loop=%s\nverify_loop=%s\nsource_mount=%s\nverify_mount=%s\nchild=%s\nchild_start=%s\nerror=%s\n' "$SRC_LOOP" "$VERIFY_LOOP" "$OPT_SRC" "$OPT_VERIFY" "$CHILD" "$CHILD_START" "$OPT_ERROR"
  } > "$tmp" || return 1
  "$SUPPORT" fsync "$tmp" && mv -f "$tmp" "$OPT_DIR/journal" && "$SUPPORT" fsync "$OPT_DIR"
}
opt_details() {
  local bytes=0
  [ -z "$PARTIAL" ] || bytes=$(stat -c %s "$PARTIAL" 2>/dev/null) || bytes=0
  [ "$OPT_PHASE" != "done" ] || bytes=$(stat -c %s "$FINAL" 2>/dev/null) || bytes=0
  publish opt_bytes "$bytes"; publish opt_elapsed "$(( $(clock_seconds)-OPT_START ))"
  publish opt_error "$OPT_ERROR"
}
publish_opt_state() {
  publish opt_state "$1"
  # Atomic job+phase boundary prevents a new job inheriting the old done state
  # while its detail properties and journal are still being initialized.
  publish opt_status "$2:$1"
}
opt_transition() {
  OPT_PHASE=$1
  opt_journal || { OPT_ERROR=io_error; return 1; }
  opt_details; publish_opt_state "$OPT_PHASE" "$OPT_JOB"
}
opt_fail() { OPT_ERROR=$1; log "optimize $OPT_JOB: $1"; exit 1; }
power_hold() { printf '%s 60000000000\n' "$WAKE" > /sys/power/wake_lock; }
opt_progress() {
  power_hold || { OPT_ERROR=power_hold; return 1; }
  games_card && [ "$CARD_UUID" = "$OPT_UUID" ] && [ "$CARD" = "$OPT_CARD" ] && [ "$CARD_RW" = 1 ] || { OPT_ERROR=no_card; return 1; }
  local info available
  info=$("$SUPPORT" space "$OPT_IMAGE_DIR") || { OPT_ERROR=io_error; return 1; }
  available=$(field "$info" available)
  publish opt_free "$available"
  [ "$available" -ge 553648128 ] || { OPT_ERROR=no_space; return 1; }
  if [ -n "$PARTIAL" ] && [ -f "$PARTIAL" ]; then
    [ "$(stat -c %s "$PARTIAL")" -le "$(field "$info" file_limit)" ] || { OPT_ERROR=no_space; return 1; }
  fi
  # Children append to this file. Quiet formatter/fsck output plus periodic
  # truncation bounds diagnostics, without a pipe obscuring their exit status.
  if [ "$(stat -c %s "$OPT_DIR/worker.log" 2>/dev/null || echo 0)" -gt 1048576 ]; then
    : > "$OPT_DIR/worker.log"
  fi
  opt_details
}
opt_child() {
  if [ -n "${OPT_CAPTURE:-}" ]; then
    nice -n 10 "$SUPPORT" child "$@" > "$OPT_CAPTURE" 2>> "$OPT_DIR/worker.log" & CHILD=$!
  else
    nice -n 10 "$SUPPORT" child "$@" >> "$OPT_DIR/worker.log" 2>&1 & CHILD=$!
  fi
  CHILD_START=$(process_start "$CHILD")
  command -v ionice >/dev/null 2>&1 && ionice -c 3 -p "$CHILD" 2>/dev/null
  opt_journal || { OPT_ERROR=io_error; return 1; }
  while [ -n "$CHILD_START" ] && [ "$CHILD_START" = "$(process_start "$CHILD")" ]; do
    opt_progress || return 1
    sleep 2
  done
  local rc=0
  wait "$CHILD" || rc=$?
  CHILD=; opt_details
  return "$rc"
}
stop_opt_child() {
  if [ -n "$CHILD" ]; then
    if [ -n "$CHILD_START" ] && [ "$CHILD_START" = "$(process_start "$CHILD")" ]; then
      kill "$CHILD" 2>/dev/null
      sleep 1
      [ "$CHILD_START" != "$(process_start "$CHILD")" ] || kill -9 "$CHILD" 2>/dev/null
    fi
    wait "$CHILD" 2>/dev/null
    CHILD=
  fi
}
opt_cleanup() {
  local rc=$1
  trap - EXIT INT TERM HUP
  [ "$OPT_LOCKED" = 1 ] || exit "$rc"
  if [ "$OPT_ACCEPTED" != 1 ]; then
    publish opt_job "$OPT_JOB"; publish opt_error "${OPT_ERROR:-interrupted}"
    publish_opt_state failed "$OPT_JOB"
    release_operation; exit 1
  fi
  stop_opt_child
  if [ -n "$VERIFY_LOOP" ]; then
    if [ "$VERIFY_MOUNTED" = 1 ]; then umount "$OPT_VERIFY" 2>/dev/null || OPT_ERROR=io_error; fi
    if "$SUPPORT" detach-owned "$VERIFY_LOOP" "$PART_DEV" "$PART_INO" 0 2>/dev/null; then VERIFY_LOOP=; else OPT_ERROR=${OPT_ERROR:-io_error}; fi
  fi
  if [ -n "$SRC_LOOP" ]; then
    if [ "$SRC_MOUNTED" = 1 ]; then umount "$OPT_SRC" 2>/dev/null || OPT_ERROR=io_error; fi
    if "$SUPPORT" detach-owned "$SRC_LOOP" "$OPT_SOURCE_DEV" "$OPT_SOURCE_INO" "$OPT_OFFSET" 2>/dev/null; then SRC_LOOP=; else OPT_ERROR=${OPT_ERROR:-io_error}; fi
  fi
  # Only remove our exact staging inode, never a completed final or record.
  if [ -n "$OPT_UUID" ] && games_card && [ "$CARD_UUID" = "$OPT_UUID" ] && [ "$CARD" = "$OPT_CARD" ]; then
    [ -z "$PART_INO" ] || [ ! -e "$PARTIAL" ] || "$SUPPORT" remove "$PARTIAL" "$PART_DEV" "$PART_INO" 2>/dev/null
    [ -z "$META_INO" ] || [ ! -e "$META_PARTIAL" ] || "$SUPPORT" remove "$META_PARTIAL" "$META_DEV" "$META_INO" 2>/dev/null
    local orphan
    if [ -n "$FINAL" ]; then
      orphan=$("$SUPPORT" orphan "$FINAL" "$OPT_UUID" "$OPT_JOB" 2>/dev/null) && {
        "$SUPPORT" remove "$FINAL.meta" "$(field "$orphan" meta_device)" "$(field "$orphan" meta_inode)" && "$SUPPORT" fsync "${FINAL%/*}"
      }
    fi
  fi
  [ -z "$WAKE" ] || printf '%s\n' "$WAKE" > /sys/power/wake_unlock
  if [ "$rc" = 0 ] && [ -z "$OPT_ERROR" ]; then
    OPT_PHASE="done"
  elif [ "$OPT_ERROR" = interrupted ]; then OPT_PHASE=interrupted
  else OPT_PHASE=failed; [ -n "$OPT_ERROR" ] || OPT_ERROR=io_error; fi
  opt_journal || { OPT_ERROR=io_error; OPT_PHASE=failed; }
  opt_details; publish_opt_state "$OPT_PHASE" "$OPT_JOB"
  rmdir "$OPT_SRC" "$OPT_VERIFY" 2>/dev/null
  release_operation
  # Completion is independently discoverable even if this request is lost.
  [ "$OPT_PHASE" != "done" ] || [ -n "$(prop request)" ] || publish request scan
  [ "$OPT_PHASE" = "done" ] && exit 0
  exit 1
}
# Called only while holding the operation lock. Journal paths are root-owned.
# Never remove a staging file merely because its name looks like a partial.
reconcile_optimize() {
  local saved
  for saved in "$OPT_DIR"/journals/*; do [ ! -f "$saved" ] || reconcile_journal "$saved" 0; done
  reconcile_journal "$OPT_DIR/journal" 1
}
reconcile_journal() {
  local journal=$1 current=$2 data phase job owner_boot owner_pid owner_start record final src partial meta dev ino loop offset
  local terminal=interrupted error=interrupted
  [ -f "$journal" ] || return 0
  data=$(cat "$journal"); phase=$(field "$data" phase); job=$(field "$data" job)
  valid_job "$job" || return 0
  owner_boot=$(field "$data" boot); owner_pid=$(field "$data" pid); owner_start=$(field "$data" process_start)
  case "$owner_pid" in ''|*[!0-9]*) return 0;; esac
  if [ "$owner_boot" = "$(boot_id)" ] && [ "$owner_start" = "$(process_start "$owner_pid")" ]; then return 0; fi
  case "$phase" in done) return 0;; failed) terminal=failed; error=$(field "$data" error);; esac
  # Publish a dead worker's terminal state even if its card is now absent or
  # another card is mounted. Leave the journal for identity-checked cleanup.
  if [ "$current" = 1 ]; then
    publish opt_job "$job"; publish opt_error "$error"; publish_opt_state "$terminal" "$job"
  fi
  # Card identity and canonical directory must agree before touching its files.
  if ! games_card; then
    if [ "$current" = 1 ] && [ "$terminal" != failed ]; then
      publish opt_job "$job"; publish opt_error interrupted; publish_opt_state interrupted "$job"
    fi
    return 0
  fi
  [ "$CARD_UUID" = "$(field "$data" card_uuid)" ] || return 0
  src=$(field "$data" source); final=$(field "$data" final)
  [ "${src%/*}" = "$CARD/subsystem/darkosen" ] || return 0
  [ "$final" = "${src%.img}.erofs.img" ] || return 0
  record=$(completion_record "$final") && [ "$(field "$record" job)" = "$job" ] && {
    if [ "$current" = 1 ]; then
      publish opt_job "$job"; publish opt_error ""; publish opt_bytes "$(field "$record" output_size)"; publish_opt_state "done" "$job"
    fi
    sed 's/^phase=.*/phase=done/' "$journal" > "$journal.reconcile"
    mv -f "$journal.reconcile" "$journal"
    "$SUPPORT" fsync "$journal"; "$SUPPORT" fsync "${journal%/*}"
    return 0
  }
  if [ "$owner_boot" = "$(boot_id)" ]; then
    loop=$(field "$data" verify_loop); dev=$(field "$data" partial_device); ino=$(field "$data" partial_inode)
    [ -z "$loop" ] || "$SUPPORT" detach-owned "$loop" "$dev" "$ino" 0 2>/dev/null
    loop=$(field "$data" source_loop); dev=$(field "$data" source_device); ino=$(field "$data" source_inode); offset=$(field "$data" offset)
    [ -z "$loop" ] || "$SUPPORT" detach-owned "$loop" "$dev" "$ino" "$offset" 2>/dev/null
  fi
  partial=$(field "$data" partial); meta=$(field "$data" meta_partial)
  if [ "$partial" = "$final.partial.$job" ] && [ -f "$partial" ]; then
    "$SUPPORT" remove "$partial" "$(field "$data" partial_device)" "$(field "$data" partial_inode)" 2>/dev/null
  fi
  if [ "$meta" = "$final.meta.partial.$job" ] && [ -f "$meta" ]; then
    "$SUPPORT" remove "$meta" "$(field "$data" meta_device)" "$(field "$data" meta_inode)" 2>/dev/null
  fi
  # Reclaim only a proven interrupted job's orphan record. A foreign or
  # malformed record remains reserved and is never replaced automatically.
  local orphan
  orphan=$("$SUPPORT" orphan "$final" "$CARD_UUID" "$job" 2>/dev/null) && {
    "$SUPPORT" remove "$final.meta" "$(field "$orphan" meta_device)" "$(field "$orphan" meta_inode)" && "$SUPPORT" fsync "${final%/*}"
  }
  sed "s/^phase=.*/phase=$terminal/" "$journal" > "$journal.reconcile"
  mv -f "$journal.reconcile" "$journal"
  "$SUPPORT" fsync "$journal"; "$SUPPORT" fsync "${journal%/*}"
  if [ "$current" = 1 ]; then
    publish opt_job "$job"; publish opt_error "$error"; publish_opt_state "$terminal" "$job"
  fi
}
recheck_opt_source() {
  local current
  games_card || return 1
  [ "$CARD_UUID" = "$OPT_UUID" ] && [ "$CARD" = "$OPT_CARD" ] && [ "$CARD_DEV" = "$OPT_CARD_DEV" ] && [ "$CARD_RW" = 1 ] || return 1
  current=$("$SUPPORT" probe "$OPT_SOURCE") || return 1
  [ "$(field "$current" generation)" = "$OPT_GENERATION" ] && [ "$(field "$current" device)" = "$OPT_SOURCE_DEV" ] && [ "$(field "$current" inode)" = "$OPT_SOURCE_INO" ]
}
optimize_worker() {
  [ "${A36_OPT_PRIVATE:-}" = 1 ] || { log "optimize-worker requires private namespace entry"; exit 2; }
  OPT_JOB=$1; local pinned=$2
  valid_job "$OPT_JOB" && valid_id "$pinned" || exit 2
  OPT_PHASE=preparing; OPT_ERROR=; OPT_START=$(clock_seconds)
  OPT_UUID=; OPT_CARD=; OPT_CARD_DEV=; OPT_SOURCE=; OPT_SOURCE_DEV=; OPT_SOURCE_INO=; OPT_OFFSET=0
  PARTIAL=; FINAL=; PART_DEV=; PART_INO=; META_PARTIAL=; META_DEV=; META_INO=
  SRC_LOOP=; VERIFY_LOOP=; SRC_MOUNTED=0; VERIFY_MOUNTED=0; CHILD=; CHILD_START=; WAKE=
  OPT_SRC="$OPT_DIR/source-$OPT_JOB"; OPT_VERIFY="$OPT_DIR/verify-$OPT_JOB"
  # These traps precede loops, mounts, staging files and the power hold.
  OPT_LOCKED=0; OPT_ACCEPTED=0
  trap 'opt_cleanup $?' EXIT
  trap 'OPT_ERROR=interrupted; exit 1' INT TERM HUP
  if ! acquire_operation optimize; then
    publish opt_reject_error busy; publish opt_reject_job "$OPT_JOB"; exit 1
  fi
  OPT_LOCKED=1
  reconcile_optimize
  mkdir -p "$OPT_DIR/journals"; chmod 0700 "$OPT_DIR"
  if [ -f "$OPT_DIR/journal" ]; then
    local previous previous_job
    previous=$(cat "$OPT_DIR/journal"); previous_job=$(field "$previous" job)
    if valid_job "$previous_job"; then
      cp "$OPT_DIR/journal" "$OPT_DIR/journals/$previous_job.tmp" &&
        "$SUPPORT" fsync "$OPT_DIR/journals/$previous_job.tmp" &&
        mv -f "$OPT_DIR/journals/$previous_job.tmp" "$OPT_DIR/journals/$previous_job" &&
        "$SUPPORT" fsync "$OPT_DIR/journals" || opt_fail io_error
    fi
  fi
  OPT_ACCEPTED=1
  publish opt_job "$OPT_JOB"; publish opt_bytes 0; publish opt_elapsed 0
  publish opt_error ""; publish opt_required ""; publish opt_available ""
  opt_transition preparing || opt_fail io_error
  [ -x "$SUPPORT" ] && [ -x "$MKFS" ] && [ -x "$FSCK" ] || opt_fail tool_missing
  WAKE="a36-erofs-$OPT_JOB"
  power_hold || opt_fail power_hold
  # The namespace was made private by the helper before entering this function.
  IMAGE=; resolve_selected_image "$pinned" || opt_fail "$RESOLVE_STATE"
  [ "$EFFECTIVE_FORMAT" != erofs ] || [ "$SOURCE" != "$EFFECTIVE" ] || opt_fail already_optimized
  [ -n "$SOURCE_PROBE" ] && [ "$(field "$SOURCE_PROBE" format)" = btrfs ] || opt_fail invalid_image
  [ "$CARD_RW" = 1 ] || opt_fail no_card
  OPT_UUID=$CARD_UUID; OPT_CARD=$CARD; OPT_CARD_DEV=$CARD_DEV; OPT_IMAGE_DIR=$IMAGE_DIR
  OPT_SOURCE=$SOURCE; OPT_GENERATION=$GENERATION; OPT_SOURCE_ID=$SOURCE_ID
  OPT_SOURCE_DEV=$(field "$SOURCE_PROBE" device); OPT_SOURCE_INO=$(field "$SOURCE_PROBE" inode)
  OPT_OFFSET=$(field "$SOURCE_PROBE" offset)
  FINAL="${SOURCE%.img}.erofs.img"; PARTIAL="$FINAL.partial.$OPT_JOB"; META_PARTIAL="$FINAL.meta.partial.$OPT_JOB"
  # A no-replace publish will check again. Existing outputs/records are reserved.
  [ ! -e "$FINAL" ] && [ ! -L "$FINAL" ] && [ ! -e "$FINAL.meta" ] && [ ! -L "$FINAL.meta" ] || opt_fail output_exists
  [ "${#FINAL}" -lt 4000 ] && [ "${#META_PARTIAL}" -lt 4000 ] && [ "$(printf '%s' "${META_PARTIAL##*/}" | wc -c)" -le 255 ] || opt_fail name_too_long
  local data_free
  data_free=$("$SUPPORT" space "$CONTROL") || opt_fail io_error
  [ "$(field "$data_free" available)" -ge 67108864 ] || opt_fail data_space
  mkdir -p "$OPT_SRC" "$OPT_VERIFY" || opt_fail io_error
  opt_journal || opt_fail io_error
  SRC_LOOP=$(losetup -f) || opt_fail io_error
  # Record ownership before attachment; detach-owned checks actual backing inode.
  opt_journal || opt_fail io_error
  losetup -r -o "$OPT_OFFSET" "$SRC_LOOP" "$SOURCE" || opt_fail io_error
  mount -t btrfs -o ro,nologreplay "$SRC_LOOP" "$OPT_SRC" || opt_fail invalid_image
  SRC_MOUNTED=1
  "$SUPPORT" metadata "$OPT_SRC" > "$OPT_DIR/source.metadata" || opt_fail invalid_image
  local estimate identity
  local estimate_rc=0
  : > "$OPT_DIR/worker.log"
  OPT_CAPTURE="$OPT_DIR/estimate"
  opt_child "$SUPPORT" estimate "$OPT_SRC" "$IMAGE_DIR" || estimate_rc=$?
  OPT_CAPTURE=
  estimate=$(cat "$OPT_DIR/estimate")
  publish opt_required "$(field "$estimate" required)"; publish opt_available "$(field "$estimate" available)"
  if [ "$estimate_rc" != 0 ]; then
    if [ -z "$OPT_ERROR" ] && [ -n "$(field "$estimate" output_bound)" ] && [ "$(field "$estimate" output_bound)" -gt "$(field "$estimate" file_limit)" ]; then
      opt_fail file_limit
    fi
    opt_fail "${OPT_ERROR:-no_space}"
  fi
  [ "$(field "$estimate" available)" -ge "$(field "$estimate" required)" ] || opt_fail no_space
  identity=$("$SUPPORT" reserve "$PARTIAL") || opt_fail output_exists
  PART_DEV=$(field "$identity" device); PART_INO=$(field "$identity" inode)
  : > "$OPT_DIR/worker.log"
  opt_transition building || opt_fail io_error
  # TODO: measure LZ4 vs LZ4HC wall time, size and RAM on the RK3326. Plain LZ4
  # is intentional. No big pclusters/fragments/dedupe/tailpacking/legacy profile.
  # The pinned prebuilt lacks pthread compression support and is single-worker;
  # --workers is not available in that build.
  opt_child "$MKFS" -b4096 -zlz4 -E^xattr-name-filter --preserve-mtime --quiet "$PARTIAL" "$OPT_SRC" || opt_fail "${OPT_ERROR:-mkfs_failed}"
  opt_transition verifying || opt_fail io_error
  local output
  output=$("$SUPPORT" probe "$PARTIAL") || opt_fail verify_failed
  [ "$(field "$output" format)" = erofs ] || opt_fail verify_failed
  opt_child "$FSCK" -d0 --extract "$PARTIAL" || opt_fail "${OPT_ERROR:-verify_failed}"
  VERIFY_LOOP=$(losetup -f) || opt_fail io_error
  opt_journal || opt_fail io_error
  losetup -r "$VERIFY_LOOP" "$PARTIAL" || opt_fail io_error
  mount -t erofs -o ro "$VERIFY_LOOP" "$OPT_VERIFY" || opt_fail verify_failed
  VERIFY_MOUNTED=1
  "$SUPPORT" metadata "$OPT_VERIFY" > "$OPT_DIR/output.metadata" || opt_fail verify_failed
  cmp -s "$OPT_DIR/source.metadata" "$OPT_DIR/output.metadata" || opt_fail verify_failed
  opt_progress || opt_fail "$OPT_ERROR"
  recheck_opt_source || opt_fail source_changed
  umount "$OPT_VERIFY" || opt_fail io_error
  VERIFY_MOUNTED=0
  "$SUPPORT" detach-owned "$VERIFY_LOOP" "$PART_DEV" "$PART_INO" 0 || opt_fail io_error
  VERIFY_LOOP=
  opt_transition publishing || opt_fail io_error
  identity=$("$SUPPORT" reserve "$META_PARTIAL") || opt_fail output_exists
  META_DEV=$(field "$identity" device); META_INO=$(field "$identity" inode)
  opt_journal || opt_fail io_error
  {
    printf 'version=1\njob=%s\nsource_name_hex=%s\nsource_id=%s\ngeneration=%s\n' "$OPT_JOB" "$(hex_name "${SOURCE##*/}")" "$OPT_SOURCE_ID" "$OPT_GENERATION"
    printf 'offset=%s\nsectors=%s\nsource_size=%s\ncard_uuid=%s\n' "$OPT_OFFSET" "$(field "$SOURCE_PROBE" sectors)" "$(field "$SOURCE_PROBE" size)" "$OPT_UUID"
    printf 'tool=erofs-utils-1.8.10\noptions=b4096,lz4,no-xattr-filter,preserve-mtime,workers1\noutput_size=%s\noutput_uuid=%s\n' "$(field "$output" size)" "$(field "$output" fs_uuid)"
  } > "$META_PARTIAL" || opt_fail io_error
  # Flush/publication can also take time on a slow card. Keep renewing the
  # timed wake lock and checking card/free-space state while those children run.
  opt_child "$SUPPORT" fsync "$PARTIAL" || opt_fail "${OPT_ERROR:-io_error}"
  # Durable record first; final image appearance is the commit point. Each
  # publication fsyncs its file AND directory and fails if NOREPLACE is absent.
  opt_child "$SUPPORT" publish "$META_PARTIAL" "$FINAL.meta" "$META_DEV" "$META_INO" || opt_fail "${OPT_ERROR:-io_error}"
  recheck_opt_source || opt_fail source_changed
  opt_child "$SUPPORT" publish "$PARTIAL" "$FINAL" "$PART_DEV" "$PART_INO" || opt_fail "${OPT_ERROR:-io_error}"
  completion_record "$FINAL" >/dev/null || opt_fail verify_failed
  # EXIT cleanup tears down resources before declaring done and requesting scan.
}

cmd=${1:-start}; arg=${2:-}

# Compatibility CLI dispatcher has a strict allowlist. Production init uses the
# fixed commands below after consuming and snapshotting its request itself.
if [ "$cmd" = launch-prop ]; then
  req=$(prop request)
  publish request ""
  case "$req" in
    scan) cmd=scan;;
    run) cmd=run-prop; publish run_target "$(prop port)";;
    *) log "rejected request $req"; exit 2;;
  esac
fi
case "$cmd" in
  scan) publish scan_pending 0; scan_images; exit $? ;;
  reconcile)
    acquire_operation reconcile || exit 1
    reconcile_optimize; release_operation; exit 0 ;;
  optimize-prop)
    IMAGE=
    job=$(prop opt_dispatch_job); pinned=$(prop opt_dispatch_source)
    valid_job "$job" && valid_id "$pinned" || exit 2
    if [ ! -x "$SUPPORT" ]; then
      publish opt_job "$job"; publish opt_error tool_missing; publish_opt_state failed "$job"; exit 1
    fi
    # Arguments are pinned before the namespace exec. Never reread request,
    # selection or port properties in the worker.
    exec "$SUPPORT" namespace /system/bin/sh "$0" optimize-worker "$job" "$pinned" ;;
  optimize)
    job=$(tr -d '-' < /proc/sys/kernel/random/uuid | cut -c1-16)
    resolve_selected_image || { echo "$RESOLVE_STATE" >&2; exit 1; }
    exec "$SUPPORT" namespace /system/bin/sh "$0" optimize-worker "$job" "$SOURCE_ID" ;;
  optimize-worker)
    optimize_worker "$arg" "${3:-}"
    exit 0 ;;
  run-prop)
    IMAGE=
    want=$(prop run_target)
    case "$want" in
      ES|"") cmd=start; arg= ;;
      PM) cmd=run; arg=/roms/tools/PortMaster/PortMaster.sh; IS_PM=1 ;;
      /*) cmd=run; arg=$want ;;
      *) cmd=run; arg="/roms/ports/$want" ;;
    esac ;;
  stop)
    # Stop is the explicit root recovery operation. It must not interfere with
    # an optimizer's mounts, loops or Android services.
    [ "$(prop operation)" != optimize ] || exit 1
    : > "$STATE/subsystem.clean"; restore_android; log "forced stop"; exit 0 ;;
  start|run) ;;
  *) echo "usage: $0 start | run <port.sh> | stop | scan | optimize" >&2; exit 2 ;;
esac
publish_run_result() {
  local run_seq
  run_seq=$(prop run_seq); case "$run_seq" in ''|*[!0-9]*) run_seq=0;; esac
  publish run_state "$1"; publish run_seq "$((run_seq+1))"
}
if ! acquire_operation run; then
  publish_run_result busy; exit 1
fi
# No display restoration should run for resolver/preflight errors.
trap 'release_operation' EXIT
capture_legacy_evidence
: > "$LOG"; log "subsystem $cmd arg=$arg"
if ! mount_rootfs; then publish_run_result failed; exit 1; fi
publish_run_result accepted
# After a PortMaster session, ask the existing ports scanner to refresh.
trap 'rc=$?; trap - EXIT INT TERM HUP; restore_android; [ "${IS_PM:-0}" = 1 ] && setprop sys.andr36oid.ports_changed 1 2>/dev/null; : > "$STATE/subsystem.clean"; [ -z "${GUARD:-}" ] || kill "$GUARD" 2>/dev/null; log "exit rc=$rc"; publish run_state idle; release_operation; exit $rc' EXIT
trap 'exit 1' INT TERM HUP
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
