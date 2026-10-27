#!/system/bin/sh
# Image catalog and EROFS worker; sourced by the subsystem broker.
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
scan_managed_images() {
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
mount_managed_rootfs() {
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
  track_mount "$LOWER"; track_mount "$ROOT"
  printf '%s\n' "$LOOPDEV" > "$STATE/session.loop"
  IMAGE_ID="$FAMILY:$GENERATION"
  MOUNTED=1
  printf '%s\n' "$FAMILY:$GENERATION:$ACTIVE_ID" > "$CONTROL/mounted-image"
  log "using image $SOURCE; lower $EFFECTIVE; upper $UPPER"
  publish_resolution 1
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

