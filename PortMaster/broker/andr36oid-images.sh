#!/system/bin/sh
# Image catalog and EROFS worker; sourced by the subsystem broker.
# --- shared image protocol ---------------------------------------------------
CONTROL=${A36_CONTROL:-/data/misc/andr36oid-subsystem}
SUPPORT=${A36_EROFS_SUPPORT:-/system/bin/andr36oid-erofs-support}
PROP=sys.andr36oid.subsystem
SELECT_PROP=persist.sys.andr36oid.subsystem.selected
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
# Android mksh arithmetic and test -ge wrap at 32 bits, even on ARM64.
# awk doubles represent all byte counts on supported console media exactly.
bytes_ge() { awk -v a="$1" -v b="$2" 'BEGIN {exit !(a+0 >= b+0)}'; }
clock_seconds() { cut -d. -f1 /proc/uptime; }
boot_id() { cat /proc/sys/kernel/random/boot_id; }
process_start() { sed 's/^.*) //' "/proc/$1/stat" 2>/dev/null | awk '{print $20}'; }
OPERATION_LOCKED=0
acquire_operation() {
  exec 7>"${A36_STORAGE_LOCK:-/data/misc/andr36oid-storage.lock}"
  flock -n 7 7>&7 || { exec 7>&-; return 1; }
  exec 9>"$CONTROL/operation.lock"
  flock -n 9 9>&9 || { exec 7>&- 9>&-; return 1; }
  # A crashed USB exporter can retain open backing files after its lock dies.
  # Require its recovery path to clear ownership before using the same storage.
  local usb=${A36_USB_STATE:-/data/misc/andr36oid-usb-drive} lun
  if [ ! -f "$usb/boot_id" ] || [ "$(cat "$usb/boot_id")" = "$(boot_id)" ]; then
    for lun in "$usb/owned" "$usb/storage" "$usb/virtual"; do
      [ ! -e "$lun" ] || { exec 7>&- 9>&-; return 1; }
    done
  fi
  for lun in /config/usb_gadget/*/functions/mass_storage.*/lun.*/file; do
    [ ! -s "$lun" ] || [ -z "$(cat "$lun")" ] || { exec 7>&- 9>&-; return 1; }
  done
  case "$1" in run|recover) ;; *)
    [ ! -d "$STATE/session.lock" ] || { exec 7>&- 9>&-; return 1; };;
  esac
  OPERATION_LOCKED=1
  if command -v release_lifecycle_recover >/dev/null && ! release_lifecycle_recover; then release_operation; return 1; fi
  case "$1" in install-release|apply-release-to-partition)
    if command -v release_lifecycle_begin >/dev/null && ! release_lifecycle_begin "$1" "${2:-$(prop rel_dispatch_job)}"; then release_operation; return 1; fi ;;
  esac
  case "$1" in run)
    if command -v prestage_blocked >/dev/null && prestage_blocked; then exec 7>&- 9>&-; OPERATION_LOCKED=0; return 1; fi ;;
    scan|recover|prestage) : ;;
    *)
      if [ -f "$STATE/prestage.ready" ] || [ -s "$STATE/session.mounts" ]; then
        prestage_unmount_locked || { exec 7>&- 9>&-; OPERATION_LOCKED=0; return 1; }
      fi ;;
  esac
  publish operation "$1"
  if [ "$1" != install-release ] && command -v reconcile_release >/dev/null && ! reconcile_release; then
    release_operation; return 1
  fi
}
release_operation() { publish operation ""; exec 9>&- 7>&-; OPERATION_LOCKED=0; }
operation_done() {
  local seq
  seq=$(prop done_seq)
  case "$seq" in ""|*[!0-9]*) seq=0;; esac
  publish result "$1"; publish done_seq "$((seq+1))"
}

# Exactly the SaveBackup/EASYROMS identity rule, accepting ro mounts for launch.
# Canonical host paths only: no sdcardfs aliases and no duplicate volume entries.
image_cards() {
  local dev dir fs opts rest
  while read -r dev dir fs opts rest; do
    case "$dir" in /mnt/media_rw/*) ;; *) continue;; esac
    [ -d "$dir/subsystem/darkosen" ] || continue
    printf '%s\t%s\t%s\t%s\n' "$dir" "${dir##*/}" "$dev" "$opts"
  done < /proc/mounts
  [ ! -d /data/media/0/subsystem/darkosen ] || printf '/data/media/0\tinternal\t/data\trw\n'
  [ ! -d /data/subsystem/darkosen ] || printf '/data\tinternal-legacy\t/data\trw\n'
}
set_card() {
  CARD=$1; CARD_UUID=$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]'); CARD_DEV=$3
  CARD_RW=0; case ",$4," in *,rw,*) CARD_RW=1;; esac
  IMAGE_DIR="$CARD/subsystem/darkosen"
}
games_card() {
  local dir uuid dev opts found=1
  while IFS="$(printf '\t')" read -r dir uuid dev opts; do
    [ -z "${OPT_CARD:-}" ] || [ "$dir" = "$OPT_CARD" ] || continue
    set_card "$dir" "$uuid" "$dev" "$opts"; found=0; break
  done <<EOF_CARDS
$(image_cards)
EOF_CARDS
  return "$found"
}

# Only canonical immediate regular files with safe names. No /storage aliases.
candidate_probe() { "$SUPPORT" candidate "$IMAGE_DIR" "${1##*/}" 2>/dev/null; }
completion_record() { "$SUPPORT" record "$1" "$CARD_UUID" 2>/dev/null; }
release_version_rank() {
  printf '%s %s\n' "$1" "$2" | awk '{printf "%s%s%s%010.0f",substr($1,5,4),substr($1,3,2),substr($1,1,2),$2}'
}
release_rank_newer() { awk -v a="$1" -v b="$2" 'BEGIN {exit !("x" a > "x" b)}'; }
use_original() {
  SOURCE_TRANSPORT=file
  SOURCE=$1; SOURCE_ID=$2; SOURCE_PROBE=$3
  GENERATION=$(field "$SOURCE_PROBE" generation); FAMILY=$SOURCE_ID
  EFFECTIVE=$SOURCE; ACTIVE_ID=$SOURCE_ID; EFFECTIVE_FORMAT=btrfs
  RESOLVE_STATE=ready_plain
}
use_completed() {
  SOURCE_TRANSPORT=file
  SOURCE=$1; SOURCE_ID=$2; RECORD=$3
  FAMILY=$(field "$RECORD" source_id); GENERATION=$(field "$RECORD" generation)
  EFFECTIVE=$SOURCE; ACTIVE_ID=$SOURCE_ID; SOURCE_ID=$FAMILY; EFFECTIVE_FORMAT=erofs
  RESOLVE_STATE=ready_erofs
}
# Optional argument pins optimize to the ID accepted by its request, even if the
# Settings selection changes while init is starting the worker.
resolve_on_card() {
  SOURCE_TRANSPORT=file
  SOURCE=; SOURCE_ID=; SOURCE_PROBE=; EFFECTIVE=; ACTIVE_ID=; FAMILY=; GENERATION=
  RECORD=; RESOLVE_STATE=no_image; EFFECTIVE_FORMAT=; DIAGNOSTIC_DIR=0
  local selected candidate name candidates id info format record first_original='' first_optimized='' first_info='' first_record='' first_release='' release_record='' release_rank='' rank=''
  selected=${1-$(getprop "$SELECT_PROP")}
  RESOLVE_SELECTION=$selected; RESOLVE_PREFER=1
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
    if [ -n "$selected" ] && [ "$selected" != "$id" ]; then
      record=$(completion_record "$candidate") || continue
      [ "$(field "$record" source_id)" = "$selected" ] || continue
      [ ! -f "$IMAGE_DIR/$(field "$record" source_name)" ] || continue
    fi
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
    if [ "$format" = erofs ] && [ "$(field "$record" origin)" = release ]; then
      rank=$(release_version_rank "$(field "$record" darkosen)" "$(field "$record" revision)")
      if [ -z "$first_release" ] || release_rank_newer "$rank" "$release_rank"; then
        first_release=$candidate; release_record=$record; release_rank=$rank
      fi
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
    if [ -n "$first_release" ]; then
      use_completed "$first_release" "$(selection_id "$CARD_UUID" "${first_release##*/}")" "$release_record"
    elif [ -n "$first_original" ]; then
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
resolve_selected_image() {
  local selected=${1-$(getprop "$SELECT_PROP")} dir uuid dev opts rank
  local part_info= part_rank= fallback_dir= fallback_uuid= fallback_dev= fallback_opts= fallback_id=
  local best_dir= best_uuid= best_dev= best_opts= best_id= best_rank=
  if [ -n "$IMAGE" ]; then resolve_on_card "$selected"; return $?; fi
  if [ -n "$selected" ] && ! valid_id "$selected"; then RESOLVE_STATE=selected_missing; return 1; fi
  if command -v resolve_partition >/dev/null && resolve_partition "$selected"; then
    [ -z "$selected" ] || return 0
    part_info=$PREPARED_INFO
    part_rank=$(release_version_rank "$(field "$part_info" darkosen)" "$(field "$part_info" revision)")
  fi
  while IFS="$(printf '\t')" read -r dir uuid dev opts; do
    [ -n "$dir" ] || continue
    set_card "$dir" "$uuid" "$dev" "$opts"
    if resolve_on_card "$selected"; then
      [ -z "$selected" ] || return 0
      if [ "$EFFECTIVE_FORMAT" = erofs ] && [ "$(field "$RECORD" origin)" = release ]; then
        rank=$(release_version_rank "$(field "$RECORD" darkosen)" "$(field "$RECORD" revision)")
        if [ -z "$best_id" ] || release_rank_newer "$rank" "$best_rank"; then
          best_dir=$dir; best_uuid=$uuid; best_dev=$dev; best_opts=$opts; best_id=$SOURCE_ID; best_rank=$rank
        fi
      elif [ -z "$fallback_id" ]; then
        fallback_dir=$dir; fallback_uuid=$uuid; fallback_dev=$dev; fallback_opts=$opts; fallback_id=$SOURCE_ID
      fi
    fi
  done <<EOF_CARDS
$(image_cards)
EOF_CARDS
  if [ -z "$selected" ]; then
    if [ -n "$best_id" ] && { [ -z "$part_info" ] || release_rank_newer "$best_rank" "$part_rank"; }; then
      set_card "$best_dir" "$best_uuid" "$best_dev" "$best_opts"
      resolve_on_card "$best_id" || return 1
      RESOLVE_SELECTION=; return 0
    elif [ -n "$part_info" ]; then
      PREPARED_INFO=$part_info; use_partition ""; return 0
    elif [ -n "$fallback_id" ]; then
      set_card "$fallback_dir" "$fallback_uuid" "$fallback_dev" "$fallback_opts"
      resolve_on_card "$fallback_id" || return 1
      RESOLVE_SELECTION=; return 0
    fi
  fi
  if [ -n "$selected" ] && command -v resolve_partition >/dev/null && resolve_partition "$selected" verify; then return 0; fi
  SOURCE_TRANSPORT=file
  SOURCE=;SOURCE_ID=;ACTIVE_ID=;FAMILY=;GENERATION=;EFFECTIVE=;EFFECTIVE_FORMAT=
  RESOLVE_STATE=no_image; [ -z "$selected" ] || RESOLVE_STATE=selected_missing
  return 1
}

ready_key() { printf '%s-%s-native1' "$FAMILY" "$GENERATION"; }
publish_setup_state() {
  local ready=0
  [ -n "$FAMILY" ] && [ -n "$GENERATION" ] && [ -f "$CONTROL/ready/$(ready_key)" ] && ready=1
  cat "$CONTROL/versions/$(ready_key)" 2>/dev/null > "$STATE/version.txt.tmp" || :
  chmod 0644 "$STATE/version.txt.tmp"; mv "$STATE/version.txt.tmp" "$STATE/version.txt"
  publish ready "$ready"
  publish format "${EFFECTIVE_FORMAT:-}"
}
write_catalog() {
  local dir uuid dev opts names name info id record family gen original optimized ready
  mkdir -p "$STATE"; chmod 0755 "$STATE"
  : > "$STATE/images.tsv.tmp"
  if command -v prepared_probe >/dev/null && prepared_probe cached; then
    local partition_family partition_gen partition_ready=0
    partition_family=$(field "$PREPARED_INFO" source_id); partition_gen=$(field "$PREPARED_INFO" generation)
    [ ! -f "$CONTROL/ready/$partition_family-$partition_gen-native1" ] || partition_ready=1
    printf '%s\tdArkOSen %s (DARKOS)\tDARKOS\t0\t%s\t%s\n' "$partition_family" "$(field "$PREPARED_INFO" darkosen)" "$(field "$PREPARED_INFO" size)" "$partition_ready" >> "$STATE/images.tsv.tmp"
  fi
  while IFS="$(printf '\t')" read -r dir uuid dev opts; do
    [ -n "$dir" ] || continue
    set_card "$dir" "$uuid" "$dev" "$opts"
    names=$("$SUPPORT" list "$IMAGE_DIR") || continue
    while IFS= read -r name; do
      [ -n "$name" ] || continue
      info=$(candidate_probe "$IMAGE_DIR/$name") || continue
      id=$(selection_id "$CARD_UUID" "$name"); family=$id; gen=$(field "$info" generation)
      original=$(field "$info" size); optimized=0
      if [ "$(field "$info" format)" = erofs ]; then
        record=$(completion_record "$IMAGE_DIR/$name") || continue
        family=$(field "$record" source_id); gen=$(field "$record" generation)
        # A legacy conversion shares its original's row. A standalone release
        # names itself and must remain visible without a btrfs source.
        if [ "$(field "$record" origin)" != release ]; then
          [ ! -f "$IMAGE_DIR/$(field "$record" source_name)" ] || continue
        fi
        optimized=$original;original=0;id=$family
      else
        record=$(completion_record "$IMAGE_DIR/${name%.img}.erofs.img") || record=
        if [ "$(field "$record" generation)" = "$gen" ]; then optimized=$(field "$record" output_size); fi
      fi
      ready=0; [ ! -f "$CONTROL/ready/$family-$gen-native1" ] || ready=1
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$id" "$name" "$dir" "$original" "$optimized" "$ready" >> "$STATE/images.tsv.tmp"
    done <<EOF_NAMES
$names
EOF_NAMES
  done <<EOF_CARDS
$(image_cards)
EOF_CARDS
  du -sk "$STATE/images" "$STATE/upper" 2>/dev/null | awk '{sum+=$1} END {print sum*1024}' > "$STATE/overlay-bytes"; chmod 0644 "$STATE/overlay-bytes"
  chmod 0644 "$STATE/images.tsv.tmp"; mv "$STATE/images.tsv.tmp" "$STATE/images.tsv"
}
publish_resolution() {
  local available=$1 seq
  # A busy scan and a session may publish concurrently. Serialize this small
  # boundary independently of the lifetime operation lock so sequences cannot
  # collide, and expose a guard against reading fields during an update.
  exec 8>"$CONTROL/resolve.lock"
  flock 8 8>&8 || return 1
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
  # /data survives power loss. Recover its old session journal before allowing
  # the first catalog scan; otherwise a reboot could hide every installed image.
  if [ -d "$STATE/session.lock" ] && ! session_active; then
    recover_session || return 1
  fi
  if ! acquire_operation scan; then
    return 1
  fi
  reconcile_optimize
  if command -v prepared_probe >/dev/null; then prepared_probe verify || :; fi
  write_catalog
  local available=0
  resolve_selected_image && available=1
  publish_resolution "$available"
  publish_setup_state
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
  local base="$STATE/images/$FAMILY/$GENERATION/native1" association evidence
  UPPER="$base/upper"; WORK="$base/work"
  mkdir -p "$base" || return 1
  if [ -d "$STATE/upper" ] && [ "${A36_MIGRATE_LEGACY:-0}" = 1 ]; then
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

# Android toybox stores its argv backing name in a64-byte loop label. Open the
# full name in the shell, then let losetup open the inherited read-only FD via a
# short proc path. The subshell scopes fd6; inode/offset ownership checks remain.
loop_attach_ro() (
  exec 6<"$2" || exit 1
  losetup -r -o "$3" "$1" /proc/self/fd/6 6<&6
)

attach_lower() {
  if [ "${SOURCE_TRANSPORT:-file}" = partition ]; then attach_partition_lower; return $?; fi
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
  record_session_loop "$(field "$info" device)" "$(field "$info" inode)" "$offset" || return 1
  # Offset only: toybox -S truncates the 8.5 GB partition limit to 32 bits.
  loop_attach_ro "$LOOPDEV" "$path" "$offset" || { LOOPDEV=; return 1; }
  if ! "$SUPPORT" loop-owned "$LOOPDEV" "$(field "$info" device)" "$(field "$info" inode)" "$offset"; then
    # Attachment succeeded but its backing file changed during resolution.
    release_session_loop || return 1; return 1
  fi
  local opts=ro
  [ "$format" = btrfs ] && opts=ro,nologreplay
  if ! mount -t "$format" -o "$opts" "$LOOPDEV" "$LOWER"; then
    release_session_loop || return 1; return 1
  fi
  track_mount "$LOWER" || return 1
  if ! "$SUPPORT" metadata "$LOWER" >/dev/null; then
    umount "$LOWER" || return 1
    forget_mount "$LOWER"
    release_session_loop || return 1
    return 1
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
    if [ "${SOURCE_TRANSPORT:-file}" = partition ]; then
      partition_lower_valid || return 1
      LOOPDEV=; MOUNTED=1; publish_resolution 1; return 0
    fi
    LOOPDEV=$(awk -v p="$LOWER" '$2==p {print $1; exit}' /proc/mounts)
    [ -n "$LOOPDEV" ] || return 1
    local mounted_probe mounted_offset=0
    mounted_probe=$("$SUPPORT" probe "$EFFECTIVE") || return 1
    [ "$EFFECTIVE_FORMAT" != btrfs ] || mounted_offset=$(field "$mounted_probe" offset)
    "$SUPPORT" loop-owned "$LOOPDEV" "$(field "$mounted_probe" device)" "$(field "$mounted_probe" inode)" "$mounted_offset" || return 1
    record_session_loop "$(field "$mounted_probe" device)" "$(field "$mounted_probe" inode)" "$mounted_offset" || return 1
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
    umount "$LOWER" || return 1
    forget_mount "$LOWER"
    release_session_loop || return 1
    return 1
  fi
  track_mount "$ROOT" || return 1
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
# Keep command diagnostics and complete child output outside OPT_DIR so cleanup
# cannot erase the evidence. Reset only when starting the next optimize attempt.
opt_refresh_log() {
  # Keep command/exit summaries at the end for the UI's bounded tail reader.
  { cat "$STATE/optimize-child.log" 2>/dev/null
    cat "$STATE/optimize-diagnostics.log" 2>/dev/null
  } > "$STATE/optimize.log.tmp" || :
  chmod 0644 "$STATE/optimize.log.tmp"; mv "$STATE/optimize.log.tmp" "$STATE/optimize.log"
}
opt_note() {
  printf '%s\n' "$*" >> "$STATE/optimize-diagnostics.log"
  opt_refresh_log
}
opt_run() {
  local reason=$1 rc arg
  shift
  "$@" 2>> "$STATE/optimize-diagnostics.log" && return 0
  rc=$?
  { printf 'failed: reason=%s exit=%s command:' "$reason" "$rc"
    for arg do printf ' [%s]' "$arg"; done
    printf ' (stderr above)\n'
  } >> "$STATE/optimize-diagnostics.log"
  opt_refresh_log
  return "$rc"
}
opt_write_journal() {
  {
    printf 'version=1\nboot=%s\npid=%s\nprocess_start=%s\njob=%s\nphase=%s\n' "$(boot_id)" "$$" "$(process_start $$)" "$OPT_JOB" "$OPT_PHASE"
    printf 'card_uuid=%s\nsource=%s\nsource_device=%s\nsource_inode=%s\noffset=%s\n' "$OPT_UUID" "$OPT_SOURCE" "$OPT_SOURCE_DEV" "$OPT_SOURCE_INO" "$OPT_OFFSET"
    printf 'partial=%s\npartial_device=%s\npartial_inode=%s\nmeta_partial=%s\nmeta_device=%s\nmeta_inode=%s\nfinal=%s\n' "$PARTIAL" "$PART_DEV" "$PART_INO" "$META_PARTIAL" "$META_DEV" "$META_INO" "$FINAL"
    printf 'source_loop=%s\nverify_loop=%s\nsource_mount=%s\nverify_mount=%s\nchild=%s\nchild_start=%s\nerror=%s\n' "$SRC_LOOP" "$VERIFY_LOOP" "$OPT_SRC" "$OPT_VERIFY" "$CHILD" "$CHILD_START" "$OPT_ERROR"
    printf 'preserve_partial=%s\n' "${OPT_PRESERVE_PARTIAL:-0}"
  } > "$1"
}
opt_journal() {
  local tmp="$OPT_DIR/journal.tmp.$$"
  if opt_run journal_write opt_write_journal "$tmp" &&
     opt_run journal_write "$SUPPORT" fsync "$tmp" &&
     opt_run journal_write mv -f "$tmp" "$OPT_DIR/journal" &&
     opt_run journal_write "$SUPPORT" fsync "$OPT_DIR"; then return 0; fi
  OPT_ERROR=${OPT_ERROR:-journal_write}
  return 1
}
# This works before mounting the btrfs source. Its format does not describe the
# destination card filesystem. Preserve the actual helper stderr on probe errors.
opt_space() {
  OPT_SPACE=$(opt_run space_probe "$SUPPORT" space "$1") || { OPT_ERROR=space_probe; return 1; }
  publish opt_free "$(field "$OPT_SPACE" available)"
  publish opt_available "$(field "$OPT_SPACE" available)"
}
opt_details() {
  local bytes=0
  [ -z "$PARTIAL" ] || bytes=$(stat -c %s "$PARTIAL" 2>/dev/null) || bytes=0
  [ "$OPT_PHASE" != "done" ] || bytes=$(stat -c %s "$FINAL" 2>/dev/null) || bytes=0
  publish opt_bytes "$bytes"; publish opt_elapsed "$(( $(clock_seconds)-OPT_START ))"
  publish opt_error "$OPT_ERROR"
  opt_refresh_log
}
publish_opt_state() {
  publish opt_state "$1"
  # Atomic job+phase boundary prevents a new job inheriting the old done state
  # while its detail properties and journal are still being initialized.
  publish opt_status "$2:$1"
}
opt_transition() {
  OPT_PHASE=$1
  opt_journal || return 1
  [ -z "$OPT_ERROR" ] || OPT_PHASE=failed
  opt_details; publish_opt_state "$OPT_PHASE" "$OPT_JOB"
}
opt_fail() {
  opt_note "optimize $OPT_JOB: reason=$1 exit=1 phase=$OPT_PHASE"
  OPT_ERROR=$1; log "optimize $OPT_JOB: $1"; exit 1
}
power_hold() { printf '%s 60000000000\n' "$WAKE" > "${A36_POWER_DIR:-/sys/power}/wake_lock"; }
opt_progress() {
  opt_run power_hold power_hold || { OPT_ERROR=power_hold; return 1; }
  games_card && [ "$CARD_UUID" = "$OPT_UUID" ] && [ "$CARD" = "$OPT_CARD" ] && [ "$CARD_RW" = 1 ] || { OPT_ERROR=no_card; return 1; }
  local info available
  opt_space "$OPT_IMAGE_DIR" || return 1
  info=$OPT_SPACE; available=$(field "$info" available)
  bytes_ge "$available" 553648128 || { OPT_ERROR=no_space; return 1; }
  if [ -n "$PARTIAL" ] && [ -f "$PARTIAL" ]; then
    bytes_ge "$(field "$info" file_limit)" "$(stat -c %s "$PARTIAL")" || { OPT_ERROR=no_space; return 1; }
  fi
  # Preserve the complete child output, including errors before the final 8 KiB.
  # Quiet tools and resetting once per attempt avoid accumulating old job logs.
  opt_details
}
opt_child() {
  CHILD_COMMAND=$*
  if [ -n "${OPT_CAPTURE:-}" ]; then
    nice -n 10 "$SUPPORT" child "$@" > "$OPT_CAPTURE" 2>> "$STATE/optimize-child.log" & CHILD=$!
  else
    nice -n 10 "$SUPPORT" child "$@" >> "$STATE/optimize-child.log" 2>&1 & CHILD=$!
  fi
  opt_note "child: pid=$CHILD command=$*"
  CHILD_START=$(process_start "$CHILD")
  command -v ionice >/dev/null 2>&1 && ionice -c 3 -p "$CHILD" 2>/dev/null
  opt_journal || return 1
  while [ -n "$CHILD_START" ] && [ "$CHILD_START" = "$(process_start "$CHILD")" ]; do
    opt_progress || return 1
    sleep 2
  done
  local rc=0
  wait "$CHILD" || rc=$?
  [ "$rc" = 0 ] || opt_note "child failed: exit=$rc command=$* (persistent child output above)"
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
    local rc=0
    wait "$CHILD" 2>/dev/null || rc=$?
    [ "$rc" = 0 ] || opt_note "child stopped: exit=$rc command=${CHILD_COMMAND:-unknown} (persistent child output above)"
    CHILD=
  fi
}
opt_cleanup() {
  local rc=$1
  trap - EXIT INT TERM HUP
  [ "$OPT_LOCKED" = 1 ] || exit "$rc"
  if [ "$OPT_ACCEPTED" != 1 ]; then
    opt_refresh_log
    publish opt_job "$OPT_JOB"; publish opt_error "${OPT_ERROR:-interrupted}"
    publish_opt_state failed "$OPT_JOB"
    operation_done failed; release_operation; exit 1
  fi
  if [ "$OPT_PHASE" = verifying ] && [ "$OPT_ERROR" != interrupted ] &&
     { [ "$rc" != 0 ] || [ -n "$OPT_ERROR" ]; }; then
    OPT_PRESERVE_PARTIAL=1
    opt_note "preserving failed verification image: $PARTIAL device=$PART_DEV inode=$PART_INO (reclaimed by next optimize)"
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
    [ "${OPT_PRESERVE_PARTIAL:-0}" = 1 ] || [ -z "$PART_INO" ] || [ ! -e "$PARTIAL" ] || "$SUPPORT" remove "$PARTIAL" "$PART_DEV" "$PART_INO" 2>/dev/null
    [ -z "$META_INO" ] || [ ! -e "$META_PARTIAL" ] || "$SUPPORT" remove "$META_PARTIAL" "$META_DEV" "$META_INO" 2>/dev/null
    local orphan
    if [ -n "$FINAL" ]; then
      orphan=$("$SUPPORT" orphan "$FINAL" "$OPT_UUID" "$OPT_JOB" 2>/dev/null) && {
        "$SUPPORT" remove "$FINAL.meta" "$(field "$orphan" meta_device)" "$(field "$orphan" meta_inode)" && "$SUPPORT" fsync "${FINAL%/*}"
      }
    fi
  fi
  [ -z "$WAKE" ] || printf '%s\n' "$WAKE" > "${A36_POWER_DIR:-/sys/power}/wake_unlock"
  if [ "$rc" = 0 ] && [ -z "$OPT_ERROR" ]; then
    if [ "${OPT_KEEP:-1}" = 0 ] && [ -z "$SRC_LOOP" ] && [ -z "$VERIFY_LOOP" ]; then
      completion_record "$FINAL" >/dev/null &&
        "$SUPPORT" remove "$OPT_SOURCE" "$OPT_SOURCE_DEV" "$OPT_SOURCE_INO" &&
        "$SUPPORT" fsync "$OPT_IMAGE_DIR" || OPT_ERROR=source_remove_failed
    fi
    OPT_PHASE="done"; [ -z "$OPT_ERROR" ] || OPT_PHASE=failed
  elif [ "$OPT_ERROR" = interrupted ]; then OPT_PHASE=interrupted
  else OPT_PHASE=failed; [ -n "$OPT_ERROR" ] || OPT_ERROR=io_error; fi
  opt_journal || { OPT_ERROR=${OPT_ERROR:-journal_write}; OPT_PHASE=failed; }
  opt_details; publish_opt_state "$OPT_PHASE" "$OPT_JOB"
  rmdir "$OPT_SRC" "$OPT_VERIFY" 2>/dev/null
  operation_done "$OPT_PHASE"
  release_operation
  # Completion is independently discoverable even if this request is lost.
  [ "$OPT_PHASE" != "done" ] || [ -n "$(prop request)" ] || publish request scan
  [ "$OPT_PHASE" = "done" ] && exit 0
  exit 1
}
# Called only while holding the operation lock. Journal paths are root-owned.
# Never remove a staging file merely because its name looks like a partial.
reconcile_optimize() {
  local saved reclaim=${1:-0} rc=0
  for saved in "$OPT_DIR"/journals/*; do
    [ ! -f "$saved" ] || reconcile_journal "$saved" 0 "$reclaim" || rc=1
  done
  reconcile_journal "$OPT_DIR/journal" 1 "$reclaim" || rc=1
  return "$rc"
}
reconcile_loop() {
  [ -n "$1" ] || return 0
  # A reused or already-detached loop is not ours. Recheck identity during detach.
  if "$SUPPORT" loop-owned "$@" 2>/dev/null; then
    "$SUPPORT" detach-owned "$@" 2>/dev/null || return 1
  fi
  return 0
}
reconcile_journal() {
  local journal=$1 current=$2 reclaim=${3:-0} data phase job owner_boot owner_pid owner_start record final src partial meta dev ino loop offset
  local terminal=interrupted error=interrupted dir uuid opts found=0 cleanup_failed=0
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
  src=$(field "$data" source); final=$(field "$data" final)
  while IFS="$(printf '\t')" read -r dir uuid dev opts; do
    [ "$uuid" = "$(field "$data" card_uuid)" ] || continue
    [ "${src%/*}" = "$dir/subsystem/darkosen" ] || continue
    set_card "$dir" "$uuid" "$dev" "$opts"; found=1; break
  done <<EOF_CARDS
$(image_cards)
EOF_CARDS
  if [ "$found" != 1 ]; then
    if [ "$current" = 1 ] && [ "$terminal" != failed ]; then
      publish opt_job "$job"; publish opt_error interrupted; publish_opt_state interrupted "$job"
    fi
    return 0
  fi
  [ "$CARD_UUID" = "$(field "$data" card_uuid)" ] || return 0
  [ "${src%/*}" = "$CARD/subsystem/darkosen" ] || return 0
  [ "$final" = "${src%.img}.erofs.img" ] || return 0
  # The final image may already be committed while source-loop cleanup is still
  # pending. Recover resources before marking such a journal permanently done.
  if [ "$owner_boot" = "$(boot_id)" ]; then
    loop=$(field "$data" verify_loop); dev=$(field "$data" partial_device); ino=$(field "$data" partial_inode)
    reconcile_loop "$loop" "$dev" "$ino" 0 || cleanup_failed=1
    loop=$(field "$data" source_loop); dev=$(field "$data" source_device); ino=$(field "$data" source_inode); offset=$(field "$data" offset)
    reconcile_loop "$loop" "$dev" "$ino" "$offset" || cleanup_failed=1
  fi
  if [ "$cleanup_failed" = 1 ]; then
    if [ "$current" = 1 ]; then
      publish opt_error io_error; publish_opt_state failed "$job"
    fi
    return 1
  fi
  record=$(completion_record "$final") && [ "$(field "$record" job)" = "$job" ] && {
    if [ "$current" = 1 ]; then
      publish opt_job "$job"; publish opt_error ""; publish opt_bytes "$(field "$record" output_size)"; publish_opt_state "done" "$job"
    fi
    sed 's/^phase=.*/phase=done/' "$journal" > "$journal.reconcile"
    mv -f "$journal.reconcile" "$journal"
    "$SUPPORT" fsync "$journal"; "$SUPPORT" fsync "${journal%/*}"
    return 0
  }
  partial=$(field "$data" partial); meta=$(field "$data" meta_partial)
  if [ "$partial" = "$final.partial.$job" ] && [ -f "$partial" ]; then
    # Routine scans keep a verify failure available for inspection. Only the
    # next optimize attempt expires it, with the same card/path/inode guards.
    if [ "$phase" != failed ] || [ "$(field "$data" preserve_partial)" != 1 ] || [ "$reclaim" = 1 ]; then
      if ! "$SUPPORT" remove "$partial" "$(field "$data" partial_device)" "$(field "$data" partial_inode)" 2>/dev/null; then
        [ "$reclaim" != 1 ] || return 1
      fi
    fi
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
  OPT_PHASE=preparing; OPT_ERROR=; OPT_START=$(clock_seconds); OPT_PRESERVE_PARTIAL=0
  OPT_UUID=; OPT_CARD=; OPT_CARD_DEV=; OPT_SOURCE=; OPT_SOURCE_DEV=; OPT_SOURCE_INO=; OPT_OFFSET=0
  PARTIAL=; FINAL=; PART_DEV=; PART_INO=; META_PARTIAL=; META_DEV=; META_INO=
  SRC_LOOP=; VERIFY_LOOP=; SRC_MOUNTED=0; VERIFY_MOUNTED=0; CHILD=; CHILD_START=; WAKE=
  OPT_SRC="$OPT_DIR/source-$OPT_JOB"; OPT_VERIFY="$OPT_DIR/verify-$OPT_JOB"
  # These traps precede loops, mounts, staging files and the power hold.
  OPT_LOCKED=0; OPT_ACCEPTED=0
  OPT_KEEP=$(prop opt_dispatch_keep); [ "$OPT_KEEP" = 1 ] || OPT_KEEP=0
  trap 'opt_cleanup $?' EXIT
  trap 'OPT_ERROR=interrupted; exit 1' INT TERM HUP
  if [ "${A36_OPT_LOCKED:-0}" = 1 ]; then
    # Dispatcher evicted the shared mounts before namespace isolation. These
    # inherited open file descriptions retain both locks until opt_cleanup.
    flock -n 7 7>&7 && flock -n 9 9>&9 || exit 2
    OPERATION_LOCKED=1
  elif [ -f "$STATE/prestage.ready" ] || [ -s "$STATE/session.mounts" ] || ! acquire_operation optimize; then
    # A direct diagnostic private worker must not forget journals for mounts
    # which still exist in Android's namespace. Use optimize-prop to evict.
    publish opt_reject_error busy; publish opt_reject_job "$OPT_JOB"; operation_done busy; exit 1
  fi
  OPT_LOCKED=1
  # Reclaim earlier failed verification images before another can be created.
  # Refuse to proceed if an owned stale image cannot be safely removed.
  reconcile_optimize 1 || opt_fail io_error
  : > "$STATE/optimize-diagnostics.log"
  : > "$STATE/optimize.log"
  opt_run journal_write cp /dev/null "$STATE/optimize-child.log" || opt_fail journal_write
  publish opt_error ""; publish opt_required ""; publish opt_available ""; publish opt_free ""; publish opt_total ""
  opt_run journal_write mkdir -p "$OPT_DIR/journals" || opt_fail journal_write
  opt_run journal_write chmod 0700 "$OPT_DIR" || opt_fail journal_write
  opt_run support_missing test -x "$SUPPORT" || { opt_note "support unavailable: $SUPPORT"; opt_fail support_missing; }
  if [ -f "$OPT_DIR/journal" ]; then
    local previous previous_job
    previous=$(cat "$OPT_DIR/journal"); previous_job=$(field "$previous" job)
    if valid_job "$previous_job"; then
      opt_run journal_write cp "$OPT_DIR/journal" "$OPT_DIR/journals/$previous_job.tmp" &&
        opt_run journal_write "$SUPPORT" fsync "$OPT_DIR/journals/$previous_job.tmp" &&
        opt_run journal_write mv -f "$OPT_DIR/journals/$previous_job.tmp" "$OPT_DIR/journals/$previous_job" &&
        opt_run journal_write "$SUPPORT" fsync "$OPT_DIR/journals" || opt_fail journal_write
    fi
  fi
  OPT_ACCEPTED=1
  publish opt_job "$OPT_JOB"; publish opt_bytes 0; publish opt_elapsed 0
  # No loops/mounts/staging yet: publish preparing, then gather card space before
  # the first durable journal so even a journal failure has useful free bytes.
  publish_opt_state preparing "$OPT_JOB"
  opt_run tool_missing test -x "$MKFS" && opt_run tool_missing test -x "$FSCK" || opt_fail tool_missing
  WAKE="a36-erofs-$OPT_JOB"
  opt_run power_hold power_hold || opt_fail power_hold
  # The namespace was made private by the helper before entering this function.
  IMAGE=; opt_run invalid_image resolve_selected_image "$pinned" || opt_fail "$RESOLVE_STATE"
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
  opt_space "$OPT_IMAGE_DIR" || opt_fail "$OPT_ERROR"
  opt_note "destination=$OPT_IMAGE_DIR available=$(field "$OPT_SPACE" available) cluster=$(field "$OPT_SPACE" cluster) file_limit=$(field "$OPT_SPACE" file_limit)"
  bytes_ge "$(field "$OPT_SPACE" available)" 553648128 || opt_fail no_space
  data_free=$(opt_run space_probe "$SUPPORT" space "$CONTROL") || opt_fail space_probe
  opt_note "journal destination=$CONTROL available=$(field "$data_free" available)"
  bytes_ge "$(field "$data_free" available)" 67108864 || opt_fail data_space
  opt_run journal_write mkdir -p "$OPT_SRC" "$OPT_VERIFY" || opt_fail journal_write
  opt_transition preparing || opt_fail "$OPT_ERROR"
  SRC_LOOP=$(opt_run source_busy losetup -f) || opt_fail source_busy
  # Record ownership before attachment; detach-owned checks actual backing inode.
  opt_journal || opt_fail "$OPT_ERROR"
  opt_run source_busy loop_attach_ro "$SRC_LOOP" "$SOURCE" "$OPT_OFFSET" || opt_fail source_busy
  opt_run invalid_image mount -t btrfs -o ro,nologreplay "$SRC_LOOP" "$OPT_SRC" || opt_fail invalid_image
  SRC_MOUNTED=1
  opt_run invalid_image "$SUPPORT" metadata "$OPT_SRC" > "$OPT_DIR/source.metadata" || opt_fail invalid_image
  local estimate identity
  local estimate_rc=0
  OPT_CAPTURE="$OPT_DIR/estimate"
  opt_child "$SUPPORT" estimate "$OPT_SRC" "$IMAGE_DIR" || estimate_rc=$?
  OPT_CAPTURE=
  estimate=$(cat "$OPT_DIR/estimate")
  opt_note "estimate: apparent_bytes=$(field "$estimate" apparent_bytes) allocated_bytes=$(field "$estimate" allocated_bytes) output_bound=$(field "$estimate" output_bound) required=$(field "$estimate" required)"
  publish opt_required "$(field "$estimate" required)"; publish opt_available "$(field "$estimate" available)"
  # Estimated EROFS output size: denominator for the conversion progress bar.
  publish opt_total "$(field "$estimate" output_bound)"
  if [ "$estimate_rc" != 0 ]; then
    if [ -z "$OPT_ERROR" ] && [ -n "$(field "$estimate" output_bound)" ] && ! bytes_ge "$(field "$estimate" file_limit)" "$(field "$estimate" output_bound)"; then
      opt_fail file_limit
    fi
    opt_fail "${OPT_ERROR:-no_space}"
  fi
  bytes_ge "$(field "$estimate" available)" "$(field "$estimate" required)" || opt_fail no_space
  identity=$("$SUPPORT" reserve "$PARTIAL") || opt_fail output_exists
  PART_DEV=$(field "$identity" device); PART_INO=$(field "$identity" inode)
  opt_transition building || opt_fail "${OPT_ERROR:-io_error}"
  # TODO: measure LZ4 vs LZ4HC wall time, size and RAM on the RK3326. Plain LZ4
  # is intentional. No big pclusters/fragments/dedupe/tailpacking/legacy profile.
  # The pinned prebuilt lacks pthread compression support and is single-worker;
  # --workers is not available in that build.
  opt_child "$MKFS" -b4096 -zlz4 -E^xattr-name-filter --preserve-mtime --quiet "$PARTIAL" "$OPT_SRC" || opt_fail "${OPT_ERROR:-mkfs_failed}"
  opt_transition verifying || opt_fail "${OPT_ERROR:-io_error}"
  local output
  output=$("$SUPPORT" probe "$PARTIAL") || opt_fail verify_failed
  [ "$(field "$output" format)" = erofs ] || opt_fail verify_failed
  # Bare --extract decode-verifies every payload in memory (output fd -1).
  # Never pass --extract=DIR: no decompressed tree should be written to disk.
  opt_child "$FSCK" -d0 --extract "$PARTIAL" || opt_fail "${OPT_ERROR:-verify_failed}"
  VERIFY_LOOP=$(losetup -f) || opt_fail io_error
  opt_journal || opt_fail "$OPT_ERROR"
  loop_attach_ro "$VERIFY_LOOP" "$PARTIAL" 0 || opt_fail io_error
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
  opt_transition publishing || opt_fail "${OPT_ERROR:-io_error}"
  identity=$("$SUPPORT" reserve "$META_PARTIAL") || opt_fail output_exists
  META_DEV=$(field "$identity" device); META_INO=$(field "$identity" inode)
  opt_journal || opt_fail "$OPT_ERROR"
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

# --- archive unpack: a copied dArkOSen release -> darkosen.img ---------------
# dArkOSen is distributed as a split 7z (name.7z.001/.002...), sometimes a lone
# .7z or a .zip. People copy the archive onto the games card without extracting
# it first, so the subsystem finds no usable image. This unpacks it in place to
# the short canonical darkosen.img - which also keeps the later loop-mount path
# under the kernel's 64-byte backing-name limit. It reuses the optimize progress
# channel (opt_* properties) so the app shows the same progress screen.
#
# The device's /system/xbin/7z ships without its codec library; p7zip derives its
# home from argv[0], so a private home that links the on-device lib7z.so in as
# 7z.so lets the stock binary extract with no reflash.
UNPACK_HOME="$CONTROL/p7zip"
ensure_7z() {
  mkdir -p "$UNPACK_HOME" || return 1
  [ -e /system/xbin/7z ] && [ -e /system/lib64/lib7z.so ] || return 1
  ln -sfn /system/xbin/7z "$UNPACK_HOME/7z" || return 1
  ln -sfn /system/lib64/lib7z.so "$UNPACK_HOME/7z.so" || return 1
  SEVENZ="$UNPACK_HOME/7z"
  "$SEVENZ" i >/dev/null 2>&1
}
# Full split-volume set oldest-first, or the single file. Fails when a split set
# stops before a volume that 7z will then report as missing.
unp_volumes() {
  case "$1" in
    *.7z.[0-9][0-9][0-9])
      local base=${1%.[0-9][0-9][0-9]} n=1 vol
      while vol=$(printf '%s.%03d' "$base" "$n"); [ -f "$vol" ]; do
        printf '%s\n' "$vol"; n=$((n+1))
      done
      [ "$n" -gt 1 ] || return 1 ;;
    *) printf '%s\n' "$1" ;;
  esac
}
# Exactly one *.img member -> "<size>\t<name>", else non-zero.
unp_inner_img() {
  "$SEVENZ" l -slt "$1" 2>/dev/null | awk '
    /^Path = /{p=substr($0,8)} /^Size = /{s=substr($0,8)}
    /^$/{ if(p!=""){ if(tolower(p)~/\.img$/){c++;isz=s;inm=p}; p="";s="" } }
    END{ if(p!=""&&tolower(p)~/\.img$/){c++;isz=s;inm=p}
         if(c==1) print isz"\t"inm; else exit 1 }'
}
# A cheap integrity gate: the extracted image must carry a known rootfs
# filesystem at byte 0 or a partition start. Full eligibility is checked at
# launch; partition_offsets/image_fstype come from the broker (sourced first).
unp_verify_rootfs() {
  local off fs
  for off in 0 $(partition_offsets "$1"); do
    fs=$(image_fstype "$1" "$off"); [ -n "$fs" ] && return 0
  done
  return 1
}
unp_state() {
  UNP_PHASE=$1; [ -z "$UNP_ERROR" ] || UNP_PHASE=failed
  local bytes=0
  if [ "$UNP_PHASE" = done ]; then bytes=${UNP_TOTAL:-0}
  else [ -z "$UNP_DOWNLOAD" ] || [ ! -f "$UNP_DOWNLOAD" ] || bytes=$(stat -c %s "$UNP_DOWNLOAD" 2>/dev/null) || bytes=0; fi
  publish opt_bytes "$bytes"
  publish opt_elapsed "$(( $(clock_seconds)-UNP_START ))"
  publish opt_error "$UNP_ERROR"
  publish opt_state "$UNP_PHASE"
  publish opt_status "$UNP_JOB:$UNP_PHASE"
}
unp_fail() { UNP_ERROR=$1; log "unpack $UNP_JOB: $1"; exit 1; }
unp_cleanup() {
  local rc=$1
  trap - EXIT INT TERM HUP
  [ -z "${UNP_PID:-}" ] || kill "$UNP_PID" 2>/dev/null
  [ -z "${WAKE:-}" ] || printf '%s\n' "$WAKE" > "${A36_POWER_DIR:-/sys/power}/wake_unlock" 2>/dev/null
  [ -z "${UNP_EXDIR:-}" ] || rm -rf "$UNP_EXDIR" 2>/dev/null
  if [ "${UNP_LOCKED:-0}" = 1 ]; then
    if [ "$rc" = 0 ] && [ -z "$UNP_ERROR" ]; then UNP_PHASE=done
    elif [ "$UNP_ERROR" = interrupted ]; then UNP_PHASE=interrupted
    else UNP_PHASE=failed; [ -n "$UNP_ERROR" ] || UNP_ERROR=io_error; fi
    unp_state "$UNP_PHASE"
    operation_done "$UNP_PHASE"
    release_operation
    [ "$UNP_PHASE" != done ] || [ -n "$(prop request)" ] || publish request scan
  fi
  [ "$UNP_PHASE" = done ] && exit 0
  exit 1
}
# unpack_worker <job> <primary-archive-path under a games card's subsystem/darkosen>
unpack_worker() {
  UNP_JOB=$1; local primary=$2
  valid_job "$UNP_JOB" || exit 2
  UNP_PHASE=preparing; UNP_ERROR=; UNP_START=$(clock_seconds); UNP_LOCKED=0
  UNP_EXDIR=; UNP_DOWNLOAD=; UNP_PID=; WAKE=; UNP_TOTAL=
  trap 'unp_cleanup $?' EXIT
  trap 'UNP_ERROR=interrupted; exit 1' INT TERM HUP
  if ! acquire_operation unpack; then
    publish opt_reject_error busy; publish opt_reject_job "$UNP_JOB"; operation_done busy; exit 1
  fi
  UNP_LOCKED=1
  WAKE="a36-unpack-$UNP_JOB"
  power_hold || unp_fail power_hold
  publish opt_error ""; publish opt_total ""; publish opt_required ""; publish opt_available ""; publish opt_free ""
  publish opt_job "$UNP_JOB"; publish opt_bytes 0; publish opt_elapsed 0
  unp_state preparing

  case "$primary" in */subsystem/darkosen/*) ;; *) unp_fail invalid_archive ;; esac
  case "${primary##*/}" in
    *.7z.[0-9][0-9][0-9]|*.7z|*.7Z|*.zip|*.ZIP) ;;
    *) unp_fail invalid_archive ;;
  esac
  [ -f "$primary" ] || unp_fail selected_missing
  local dir=${primary%/*}
  [ -d "$dir" ] || unp_fail selected_missing
  [ ! -e "$dir/darkosen.img" ] && [ ! -L "$dir/darkosen.img" ] || unp_fail output_exists

  ensure_7z || unp_fail tool_missing
  local vols; vols=$(unp_volumes "$primary") || unp_fail incomplete_archive
  local info; info=$(unp_inner_img "$primary") || unp_fail no_image_inside
  local size=${info%%	*} iname=${info#*	}
  case "$size" in ''|*[!0-9]*) unp_fail invalid_archive ;; esac
  case "$iname" in */*|'') unp_fail invalid_archive ;; esac
  UNP_TOTAL=$size; publish opt_total "$size"

  # Reject an absurd/overflowing declared size before any shell arithmetic:
  # size comes from the archive header, and 64 TiB is never a real rootfs, so
  # this keeps "size + margin" well clear of signed-64-bit wraparound (a wrapped
  # negative value would otherwise make the free-space guard pass).
  bytes_ge 70368744177664 "$size" || unp_fail invalid_archive
  # Free space on the card: the uncompressed image plus a 128 MiB margin.
  local spaceinfo free flimit
  spaceinfo=$("$SUPPORT" space "$dir" 2>/dev/null)
  free=$(field "$spaceinfo" available)
  flimit=$(field "$spaceinfo" file_limit)
  [ -n "$free" ] || free=$(df "$dir" 2>/dev/null | awk 'NR==2{print $4*1024}')
  [ -n "$free" ] || free=0
  publish opt_available "$free"; publish opt_free "$free"
  bytes_ge "$free" "$(( size + 134217728 ))" || unp_fail no_space
  # FAT/exFAT cap a single file to file_limit (0 = no limit, e.g. ext4/f2fs):
  # fail up front like optimize does, instead of after a full extraction pass.
  if [ -n "$flimit" ] && [ "$flimit" != 0 ]; then
    bytes_ge "$flimit" "$size" || unp_fail file_limit
  fi

  power_hold || unp_fail power_hold
  UNP_EXDIR="$dir/.unpack.$UNP_JOB"
  rm -rf "$UNP_EXDIR"; mkdir -p "$UNP_EXDIR" || unp_fail io_error
  UNP_DOWNLOAD="$UNP_EXDIR/$iname"
  unp_state extracting
  # Extract ONLY the inner image, flattened ('e', not 'x'): a crafted archive
  # then cannot fill the card with extra members (the space budget covers just
  # this one image) nor write outside UNP_EXDIR via "../" or absolute members.
  "$SEVENZ" e -y -bso0 -bsp0 -o"$UNP_EXDIR" "$primary" "$iname" >/dev/null 2>&1 &
  UNP_PID=$!
  while kill -0 "$UNP_PID" 2>/dev/null; do
    power_hold || unp_fail power_hold
    unp_state extracting
    sleep 2
  done
  wait "$UNP_PID"; local rc=$?
  UNP_PID=
  { [ "$rc" = 0 ] && [ -f "$UNP_DOWNLOAD" ]; } || unp_fail extract_failed
  # A member stored as a symlink would make darkosen.img point at an
  # attacker-chosen backing file (it is later loop-mounted); refuse it.
  if [ -L "$UNP_DOWNLOAD" ]; then unp_fail invalid_image; fi

  unp_state verifying
  unp_verify_rootfs "$UNP_DOWNLOAD" || unp_fail invalid_image

  unp_state publishing
  mv -f "$UNP_DOWNLOAD" "$dir/darkosen.img" || unp_fail io_error
  UNP_DOWNLOAD=
  "$SUPPORT" fsync "$dir" 2>/dev/null
  rm -rf "$UNP_EXDIR"; UNP_EXDIR=
  # darkosen.img is in place; drop the now-redundant archive (user chose this).
  for v in $vols; do rm -f "$v"; done
  "$SUPPORT" fsync "$dir" 2>/dev/null
  # Success: the EXIT trap publishes done and requests a scan.
}


# --- opt-in resident mount stage ---------------------------------------------
prestage_enabled() { [ "$(getprop persist.andr36oid.subsystem.prestage)" = 1 ]; }
prestage_blocked() {
  if [ "${A36_RELEASE_FINALIZING:-0}" != 1 ] && [ -f "$CONTROL/release-lifecycle/intent" ]; then return 0; fi
  case "$(getprop sys.andr36oid.subsystem.prestage_block)" in ""|0) ;; *) return 0;; esac
  [ -n "$(getprop sys.powerctl)" ]
}
phase_clock() {
  local now idle seconds fraction
  IFS=' ' read -r now idle < /proc/uptime || return 1
  seconds=${now%.*}; fraction=${now#*.}
  # proc uptime has centiseconds. Avoid an awk process at every phase edge.
  if [ "$seconds" = 0 ]; then fraction=${fraction#0}; printf '%s\n' "$((fraction*10))"
  else printf '%s%s0\n' "$seconds" "$fraction"; fi
}
phase() {
  local name=$1 before after rc; shift
  before=$(phase_clock); "$@"; rc=$?; after=$(phase_clock)
  log "phase: $name  $((after-before))ms rc=$rc"
  return "$rc"
}
prestage_storage_token() {
  # Include mount device/path, so a missing/replaced/remounted card invalidates it.
  awk '$5 ~ /^\/mnt\/media_rw\// {print $1,$3,$4,$5}' /proc/self/mountinfo
}
prestage_signature() {
  local selected source effective source_stat effective_stat
  selected=$(getprop "$SELECT_PROP")
  if [ -n "$IMAGE" ]; then
    source=$IMAGE; effective=$IMAGE
  else
    [ "${1:-}" = resolved ] || resolve_selected_image || return 1
    [ "${DIAGNOSTIC_DIR:-0}" != 1 ] || return 1
    source=$SOURCE; effective=$EFFECTIVE
  fi
  if [ "${SOURCE_TRANSPORT:-file}" = partition ]; then
    [ -b "$source" ] && [ "$source" = "$effective" ] || return 1
    source_stat=$(printf '%s\n' "$PREPARED_INFO" | sed '/^cache_hit=/d' | sha256sum | cut -d' ' -f1)
    effective_stat=$source_stat
  else
  [ -f "$source" ] && [ -f "$effective" ] || return 1
  source_stat=$(stat -c '%d:%i:%s:%Y' "$source") || return 1
  if [ "$source" = "$effective" ]; then effective_stat=$source_stat
  else effective_stat=$(stat -c '%d:%i:%s:%Y' "$effective") || return 1; fi
  fi
  printf '%s\n' "$(boot_id)" "$selected" "$IMAGE" "$source" "$effective" \
    "$source_stat" "$effective_stat" \
    "$HANDOFF" "$(prestage_storage_token)" | sha256sum | cut -d' ' -f1
}
prestage_valid() {
  local key old b loop dev ino offset extra
  [ -f "$STATE/prestage.ready" ] && [ -s "$STATE/session.mounts" ] || return 1
  [ "$(cat "$STATE/prestage.boot" 2>/dev/null)" = "$(boot_id)" ] || return 1
  prestage_blocked && return 1
  if [ -z "$IMAGE" ]; then
    resolve_selected_image || return 1
    prepare_writable_state || return 1
    IMAGE_ID="$FAMILY:$GENERATION"
  fi
  ROMS_SOURCE=$(awk 'NR==1 {print $1}' "$STATE/roms-map")
  old=$(cat "$STATE/prestage.ready")
  key=$(prestage_signature resolved) || return 1
  [ "$old" = "$key" ] || return 1
  # One proc read and one awk, rather than a new process per retained mount.
  awk -v root="$ROOT" '
    NR==FNR {if(length($0)) expected[$0]=1; next}
    {gsub(/\\040/," ",$2); gsub(/\\011/,"\t",$2); delete expected[$2]; if($2==root) mounted=1}
    END {if(!mounted) exit 1; for(path in expected) exit 1}
  ' "$STATE/session.mounts" /proc/mounts || return 1
  if [ "${SOURCE_TRANSPORT:-file}" = partition ]; then
    partition_lower_valid || return 1
    LOOPDEV=
  else
    read -r loop dev ino offset extra < "$STATE/session.loop" || return 1
    [ -z "$extra" ] && "$SUPPORT" loop-owned "$loop" "$dev" "$ino" "$offset" || return 1
    LOOPDEV=$loop
  fi
  BOUND=$(cat "$STATE/session.mounts"); MOUNTED=1
  return 0
}
prestage_forget() {
  rm -f "$STATE/prestage.ready"
  setprop sys.andr36oid.subsystem.prestaged 0
}
prestage_unmount_locked() {
  # Caller owns the storage/operation locks; no framework, codec or power calls.
  local b rc=0
  rm -f "$STATE/prestage.ready"
  # Keep the occupied flag until every mount and loop is actually released.
  if [ ! -s "$STATE/prestage.boot" ]; then
    if mounted_at "$ROOT" || mounted_at "$LOWER"; then
      log "prestage: mount has no boot identity; refusing unowned eviction"
      return 1
    fi
    # With no live mounts, unknown old metadata cannot justify detaching a
    # possibly recycled loop. A new stage always records boot identity first.
    rm -f "$STATE/session.loop" "$STATE/session.mounts" "$CONTROL/mounted-image"
    prestage_forget
    return 0
  fi
  if [ -f "$STATE/prestage.boot" ] && [ "$(cat "$STATE/prestage.boot")" != "$(boot_id)" ]; then
    rm -f "$STATE/session.loop" "$STATE/session.mounts" "$STATE/prestage.boot" "$CONTROL/mounted-image"
    prestage_forget
    return 0
  fi
  if [ -d "$ROOT/proc" ] && mounted_at "$ROOT"; then
    "$GUEST" --stop-guest-root "$ROOT" || return 1
    if [ -f "$STATE/session.guest-loops" ]; then
      "$GUEST" --cleanup-guest-loops "$ROOT" "$STATE/session.guest-loops" || return 1
    fi
  fi
  if [ -f "$STATE/session.mounts" ]; then
    BOUND=$(cat "$STATE/session.mounts")
    while IFS= read -r b; do
      [ -n "$b" ] || continue
      if mounted_at "$b"; then umount "$b" 2>/dev/null || { log "prestage: busy mount $b; eviction refused"; rc=1; break; }; fi
      forget_mount "$b" || { rc=1; break; }
    done < "$STATE/session.mounts"
  fi
  [ "$rc" = 0 ] || return 1
  release_session_loop || return 1
  rm -f "$STATE/session.mounts" "$STATE/session.restore-complete" "$CONTROL/mounted-image" "$STATE/prestage.boot"
  LOOPDEV=; BOUND=; STAGE_COMPLETE=0
  prestage_forget
  log "prestage: resident mounts released"
}
prestage_park() {
  [ "$HANDOFF" = 1 ] && [ "${STAGE_COMPLETE:-0}" = 1 ] && [ "${FORCE_UNSTAGE:-0}" != 1 ] && prestage_enabled || return 1
  prestage_blocked && return 1
  local key
  # The operation lock pins selection/conversion for the session. Re-stat the
  # resolved paths; next launch still performs a fresh managed resolution.
  # Avoid probing the whole catalog while Android is starting in parallel.
  key=$(prestage_signature resolved) || return 1
  printf '%s\n' "$(boot_id)" > "$STATE/prestage.boot" || return 1
  printf '%s\n' "$key" > "$STATE/prestage.ready.tmp" && mv -f "$STATE/prestage.ready.tmp" "$STATE/prestage.ready" || return 1
  setprop sys.andr36oid.subsystem.prestaged 1 || { rm -f "$STATE/prestage.ready"; return 1; }
  PARKED_STAGE=1
  log "prestage: mounts retained; Android owns display/audio/input"
}
stage_mounts() {
  if prestage_valid; then
    STAGE_COMPLETE=1; WARM_STAGE=1
    prestage_forget
    log "prestage: warm mount cache reused"
    return 0
  fi
  if [ -e "$STATE/prestage.ready" ] || [ -s "$STATE/session.mounts" ]; then
    prestage_unmount_locked || return 1
  fi
  printf '%s\n' "$(boot_id)" > "$STATE/prestage.boot" || return 1
  : > "$STATE/session.mounts" || return 1
  rm -f "$STATE/session.restore-complete"
  [ "${STAGE_ONLY:-0}" != 1 ] || setprop sys.andr36oid.subsystem.prestaged 1 || return 1
  WARM_STAGE=0
  phase rootfs mount_rootfs || return 1
  phase executable-repair fix_exec_bits
  if find_es "$ROOT"; then phase native-input prime_native_input || return 1; fi
  phase core-mounts bind_core || return 1
  phase games-mounts mount_storage || return 1
  phase roms-provision provision_darkos || return 1
  phase tools-mount mount_tools_menu || return 1
  publish_setup_complete || return 1
  phase network prepare_network || return 1
  phase chroot-prep prep_darkos_env || return 1
  phase input-tags prime_udev
  STAGE_COMPLETE=1; WARM_STAGE=0
}
prestage_command() {
  [ "$1" != prestage ] || { prestage_enabled && ! prestage_blocked; } || return 0
  acquire_operation prestage || return 1
  if [ -d "$STATE/session.lock" ]; then release_operation; return 1; fi
  SESSION_OWNED=1; DID_HANDOFF=0; STAGE_COMPLETE=0; PARKED_STAGE=0; STAGE_ONLY=1
  if [ "$1" = unstage ]; then
    prestage_unmount_locked; local rc=$?
    SESSION_OWNED=0; operation_done "$rc"; release_operation; return "$rc"
  fi
  stage_mounts; local rc=$?
  if [ "$rc" = 0 ]; then prestage_park || rc=1; fi
  if [ "$rc" != 0 ]; then prestage_unmount_locked || :; fi
  SESSION_OWNED=0; operation_done "$rc"; release_operation
  return "$rc"
}
