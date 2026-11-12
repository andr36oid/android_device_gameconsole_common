#!/system/bin/sh
# Release-origin EROFS installation. Sourced after andr36oid-images.sh.
# The coordinator's app owns download/UI; this worker never runs release code.
REL_DIR="$CONTROL/release"
rel_owned() {
  [ -n "$2" ] && [ -n "$3" ] && [ -f "$1" ] && [ ! -L "$1" ] &&
    [ "$(stat -c '%d:%i' "$1" 2>/dev/null)" = "$2:$3" ]
}
rel_remove() {
  [ -e "$1" ] || [ -L "$1" ] || return 0
  "$SUPPORT" remove "$1" "$2" "$3"
}
rel_wake() { printf '%s 60000000000\n' "a36-release-$REL_JOB" > "${A36_POWER_DIR:-/sys/power}/wake_lock"; }
rel_journal() {
  local tmp="$REL_DIR/journal.tmp"
  {
    printf 'version=1\nboot=%s\npid=%s\nstart=%s\njob=%s\nphase=%s\n' "$(boot_id)" "$$" "$(process_start $$)" "$REL_JOB" "$REL_PHASE"
    printf 'card=%s\ncard_uuid=%s\nname=%s\n' "$REL_CARD" "$REL_UUID" "$REL_NAME"
    printf 'device=%s\ninode=%s\nmeta_device=%s\nmeta_inode=%s\n' "$REL_DEV" "$REL_INO" "$REL_MDEV" "$REL_MINO"
    printf 'old_device=%s\nold_inode=%s\nold_meta_device=%s\nold_meta_inode=%s\n' "$REL_ODEV" "$REL_OINO" "$REL_OMDEV" "$REL_OMINO"
    printf 'old_generation=%s\nold_meta_sha=%s\n' "${REL_OLD_GENERATION:-}" "${REL_OLD_META_SHA:-}"
    printf 'loop=%s\nerror=%s\nchild=%s\nchild_start=%s\nmeta_sha=%s\n' "$REL_LOOP" "$REL_ERROR" "$REL_CHILD" "$REL_CHILD_START" "${REL_META_SHA:-}"
  } > "$tmp" && "$SUPPORT" fsync "$tmp" && mv -f "$tmp" "$REL_DIR/journal" && "$SUPPORT" fsync "$REL_DIR"
}
rel_paths() {
  REL_FINAL="$IMAGE_DIR/$REL_NAME"
  REL_PART="$IMAGE_DIR/.a36-release-$REL_JOB.partial"
  REL_META="$IMAGE_DIR/.a36-release-$REL_JOB.meta"
  REL_OLD="$IMAGE_DIR/.a36-release-$REL_JOB.old"
  REL_OLDMETA="$IMAGE_DIR/.a36-release-$REL_JOB.old.meta"
  REL_VERIFY="$REL_DIR/verify-$REL_JOB"
  REL_REQUEST="$REL_DIR/request.json"
}
rel_state() {
  REL_PHASE=$1; publish rel_state "$1"
  publish rel_elapsed "$(( $(clock_seconds)-REL_START ))"
}
rel_fail() { REL_ERROR=$1; printf '%s: %s\n' "$REL_PHASE" "$1" >> "$STATE/release-install.log"; exit 1; }
rel_card_ok() {
  local dir uuid dev opts
  while IFS="$(printf '\t')" read -r dir uuid dev opts; do
    if [ "$dir" = "$REL_CARD" ] && [ "$(printf '%s' "$uuid" | tr '[:upper:]' '[:lower:]')" = "$REL_UUID" ]; then
      set_card "$dir" "$uuid" "$dev" "$opts"
      [ "$CARD_RW" = 1 ] && [ "$(readlink -f "$IMAGE_DIR")" = "$IMAGE_DIR" ]; return $?
    fi
  done <<EOF_CARDS
$(image_cards)
EOF_CARDS
  return 1
}
rel_details() {
  local verified=0 written=0 info
  [ ! -f "$REL_DIR/verified" ] || verified=$(cat "$REL_DIR/verified")
  [ ! -f "$REL_DIR/written" ] || written=$(cat "$REL_DIR/written")
  case "$REL_PHASE" in
    verifying|unpacking)
      if bytes_ge "$verified" "$REL_COMPRESSED"; then
        rel_state unpacking; publish rel_bytes "${written:-0}"; publish rel_total "$REL_SIZE"
      else publish rel_bytes "${verified:-0}"; publish rel_total "$REL_COMPRESSED"; fi ;;
  esac
  publish rel_elapsed "$(( $(clock_seconds)-REL_START ))"
  rel_wake || { REL_ERROR=power_hold; return 1; }
  rel_card_ok || { REL_ERROR=card_unavailable; return 1; }
  info=$("$SUPPORT" space "$IMAGE_DIR") || { REL_ERROR=io_error; return 1; }
  publish rel_free "$(field "$info" available)"
  bytes_ge "$(field "$info" available)" "$REL_HEADROOM" || { REL_ERROR=no_space; return 1; }
}
rel_child() {
  nice -n 10 "$SUPPORT" child "$@" >> "$STATE/release-install.log" 2>&1 & REL_CHILD=$!
  REL_CHILD_START=$(process_start "$REL_CHILD")
  rel_journal || return 1
  local rc=0
  while kill -0 "$REL_CHILD" 2>/dev/null; do
    if ! rel_details; then
      kill -KILL "$REL_CHILD" 2>/dev/null || :; wait "$REL_CHILD" 2>/dev/null || :; REL_CHILD=; REL_CHILD_START=; return 1
    fi
    sleep "${A36_RELEASE_POLL:-2}"
  done
  wait "$REL_CHILD" || rc=$?
  REL_CHILD=; REL_CHILD_START=
  rel_journal || return 1
  [ "$rc" = 0 ] || { printf 'child failed: exit=%s command=%s\n' "$rc" "$*" >> "$STATE/release-install.log"; return 1; }
}
rel_clean_parts() {
  "$SUPPORT" child "$SUPPORT" release-clean "$REL_REQUEST" "$REL_JOB" "$IMAGE_DIR" >> "$STATE/release-install.log" 2>&1 &
  local cleaner=$! rc=0
  while kill -0 "$cleaner" 2>/dev/null; do
    if ! rel_wake || ! rel_card_ok; then kill -KILL "$cleaner" 2>/dev/null || :; wait "$cleaner" 2>/dev/null || :; return 1; fi
    sleep "${A36_RELEASE_POLL:-2}"
  done
  wait "$cleaner" || rc=$?
  [ "$rc" = 0 ]
}
# Idempotent transaction recovery under both storage flocks. Publication is
# metadata first, image last. Old image+record survive until the new pair commits.
rel_transaction_cleanup() {
  local bad=0 committed=0 rec mode=${1:-complete}
  rel_card_ok || return 1
  if [ -n "$REL_LOOP" ]; then
    "$SUPPORT" retire-owned "$REL_LOOP" "$REL_DEV" "$REL_INO" 0 || return 1
    REL_LOOP=
  fi
  if rel_owned "$REL_FINAL" "$REL_DEV" "$REL_INO" && rel_owned "$REL_FINAL.meta" "$REL_MDEV" "$REL_MINO"; then
    rec=$(completion_record "$REL_FINAL") || return 1
    [ "$(field "$rec" job)" = "$REL_JOB" ] || return 1
    committed=1
  fi
  if [ "$committed" = 1 ]; then
    # A property failure never invalidates the committed image. Retain the
    # journal so the next scan can retry selection/cleanup.
    setprop "$SELECT_PROP" "$(field "$rec" source_id)" || bad=1
    publish rel_image "$(field "$rec" source_id)"
    if [ "$mode" != defer ]; then
      rel_clean_parts || bad=1
      rel_remove "$REL_OLD" "$REL_ODEV" "$REL_OINO" || bad=1
      rel_remove "$REL_OLDMETA" "$REL_OMDEV" "$REL_OMINO" || bad=1
    fi
    REL_COMMITTED=1
  else
    # Remove only this transaction's outputs. A foreign destination blocks
    # rollback rather than authorizing an overwrite or deletion.
    if rel_owned "$REL_FINAL" "$REL_DEV" "$REL_INO"; then rel_remove "$REL_FINAL" "$REL_DEV" "$REL_INO" || bad=1; fi
    if rel_owned "$REL_FINAL.meta" "$REL_MDEV" "$REL_MINO"; then rel_remove "$REL_FINAL.meta" "$REL_MDEV" "$REL_MINO" || bad=1; fi
    if [ -e "$REL_OLD" ] || [ -L "$REL_OLD" ]; then
      "$SUPPORT" publish "$REL_OLD" "$REL_FINAL" "$REL_ODEV" "$REL_OINO" || bad=1
    fi
    if [ -e "$REL_OLDMETA" ] || [ -L "$REL_OLDMETA" ]; then
      "$SUPPORT" publish "$REL_OLDMETA" "$REL_FINAL.meta" "$REL_OMDEV" "$REL_OMINO" || bad=1
    fi
  fi
  rel_remove "$REL_PART" "$REL_DEV" "$REL_INO" || bad=1
  rel_remove "$REL_META" "$REL_MDEV" "$REL_MINO" || bad=1
  "$SUPPORT" fsync "$IMAGE_DIR" || bad=1
  [ "$bad" = 0 ]
}
reconcile_release() (
  [ -f "$REL_DIR/journal" ] || exit 0
  local data dir uuid dev opts found=0
  data=$(cat "$REL_DIR/journal")
  [ "$(field "$data" version)" = 1 ] || exit 1
  REL_JOB=$(field "$data" job); valid_job "$REL_JOB" || exit 1
  trap 'printf "%s\n" "a36-release-$REL_JOB" > "${A36_POWER_DIR:-/sys/power}/wake_unlock" 2>/dev/null || :' EXIT
  # Only stale workers may be recovered; pid reuse is checked with boot+start.
  if [ "$(field "$data" boot)" = "$(boot_id)" ] &&
     [ -n "$(field "$data" start)" ] &&
     [ "$(process_start "$(field "$data" pid)")" = "$(field "$data" start)" ]; then exit 1; fi
  REL_CARD=$(field "$data" card); REL_UUID=$(field "$data" card_uuid); REL_NAME=$(field "$data" name)
  case "$REL_NAME" in ''|.*|*[!a-zA-Z0-9_.+-]*) exit 1;; esac
  rel_card_ok || exit 1
  REL_DEV=$(field "$data" device); REL_INO=$(field "$data" inode)
  REL_MDEV=$(field "$data" meta_device); REL_MINO=$(field "$data" meta_inode)
  REL_ODEV=$(field "$data" old_device); REL_OINO=$(field "$data" old_inode)
  REL_OMDEV=$(field "$data" old_meta_device); REL_OMINO=$(field "$data" old_meta_inode)
  REL_LOOP=$(field "$data" loop); REL_COMMITTED=0
  rel_paths
  rel_transaction_cleanup || { publish rel_cleanup_pending 1; exit 1; }
  if [ "$REL_COMMITTED" = 1 ]; then
    publish rel_state done; publish rel_error ""
  else publish rel_state failed; publish rel_error interrupted; fi
  publish rel_job "$REL_JOB"; publish rel_cleanup_pending 0; publish rel_done_job "$REL_JOB"
  rm -f "$REL_DIR/journal" && "$SUPPORT" fsync "$REL_DIR"
)
rel_finish() {
  local rc=$1 pending=0
  if command -v life_intent >/dev/null && life_intent; then release_private_finish "$rc"; exit $?; fi
  trap - EXIT INT TERM HUP
  if [ -n "$REL_CHILD" ] && [ -n "$REL_CHILD_START" ] && [ "$(process_start "$REL_CHILD")" = "$REL_CHILD_START" ]; then
    kill -KILL "$REL_CHILD" 2>/dev/null || :; wait "$REL_CHILD" 2>/dev/null || :
  fi
  if [ "$REL_MOUNTED" = 1 ]; then umount "$REL_VERIFY" || pending=1; fi
  if [ "$REL_JOURNALED" = 1 ]; then
    rel_transaction_cleanup || pending=1
    if [ "$pending" = 0 ]; then rm -f "$REL_DIR/journal"; "$SUPPORT" fsync "$REL_DIR" || pending=1; fi
  fi
  if [ "$REL_COMMITTED" = 1 ]; then
    rc=0
    # Drop stale readiness from the previously selected generation before the
    # terminal token lets the app decide whether SETUP is still required.
    write_catalog
    if resolve_selected_image; then publish_resolution 1; publish_setup_state; fi
  fi
  if [ -n "$REL_WAKE" ]; then printf '%s\n' "a36-release-$REL_JOB" > "${A36_POWER_DIR:-/sys/power}/wake_unlock" || :; fi
  publish rel_cleanup_pending "$pending"
  publish rel_elapsed "$(( $(clock_seconds)-REL_START ))"
  if [ "$rc" = 0 ]; then publish rel_error ""; publish rel_state done
  else publish rel_error "${REL_ERROR:-io_error}"; publish rel_state failed; fi
  # Publish token last, after all terminal fields. The app may now delete its
  # request; recovery uses the private pinned copy and never needs that file.
  publish rel_done_job "$REL_JOB"
  operation_done "$([ "$rc" = 0 ] && printf installed || printf failed)"
  release_operation
  exit "$rc"
}
install_release_worker() {
  [ "${A36_OPT_PRIVATE:-}" = 1 ] || exit 2
  REL_JOB=$1; local request=$2
  valid_job "$REL_JOB" || exit 2
  case "$request" in ''|.*|*[!a-zA-Z0-9_.+-]*) exit 2;; esac
  REL_START=$(clock_seconds); REL_PHASE=queued; REL_ERROR=; REL_CHILD=; REL_CHILD_START=
  REL_CARD=; REL_UUID=; REL_NAME=; REL_DEV=; REL_INO=; REL_MDEV=; REL_MINO=
  REL_ODEV=; REL_OINO=; REL_OMDEV=; REL_OMINO=; REL_LOOP=; REL_MOUNTED=0
  REL_COMMITTED=0; REL_JOURNALED=0; REL_WAKE=; REL_HEADROOM=67108864; REL_META_SHA=; REL_OLD_GENERATION=; REL_OLD_META_SHA=
  if [ "${A36_REL_LOCKED:-0}" = 1 ]; then
    flock -n 7 7>&7 && flock -n 9 9>&9 || exit 2; OPERATION_LOCKED=1
  elif ! acquire_operation install-release "$REL_JOB"; then
    publish rel_job "$REL_JOB"; publish rel_error busy; publish rel_state failed; publish rel_done_job "$REL_JOB"; operation_done busy; exit 1
  fi
  # Resolve an earlier interruption before replacing its pinned request/journal.
  if ! reconcile_release; then
    publish rel_job "$REL_JOB"; publish rel_error recovery_pending; publish rel_state failed; publish rel_done_job "$REL_JOB"; operation_done failed; release_operation; exit 1
  fi
  trap 'rel_finish $?' EXIT
  trap 'REL_ERROR=interrupted; exit 1' INT TERM HUP
  publish rel_done_job ""; publish rel_job "$REL_JOB"; publish rel_error ""; publish rel_image ""; publish rel_cleanup_pending 0
  publish rel_bytes 0; publish rel_total 0; publish rel_free ""; publish rel_required ""; rel_state queued
  mkdir -p "$REL_DIR" "$STATE"; chmod 0700 "$REL_DIR"
  : > "$STATE/release-install.log"
  [ -x "$SUPPORT" ] && [ -x "$FSCK" ] && [ -x "${A36_UNXZ:-/system/bin/unxz}" ] || rel_fail tool_missing
  local dir uuid dev opts candidate= found=0 info rc=0
  while IFS="$(printf '\t')" read -r dir uuid dev opts; do
    [ -n "$dir" ] || continue
    set_card "$dir" "$uuid" "$dev" "$opts"
    candidate="$IMAGE_DIR/$request"
    [ "$CARD_RW" = 1 ] && [ "$(readlink -f "$IMAGE_DIR")" = "$IMAGE_DIR" ] && [ -f "$candidate" ] && [ ! -L "$candidate" ] || continue
    "$SUPPORT" release-check "$candidate" "$REL_JOB" >> "$STATE/release-install.log" 2>&1 || continue
    [ "$found" = 0 ] || rel_fail invalid_request
    REL_CARD=$dir; REL_UUID=$CARD_UUID; found=1
  done <<EOF_CARDS
$(image_cards)
EOF_CARDS
  [ "$found" = 1 ] || rel_fail invalid_request
  rel_card_ok || rel_fail card_unavailable
  # No journal refers to request.json now. Replace only a private root-owned
  # snapshot, fsync before its first use, and revalidate the snapshot token.
  "$SUPPORT" release-pin "$IMAGE_DIR/$request" "$REL_JOB" "$REL_DIR/request.tmp" && mv -f "$REL_DIR/request.tmp" "$REL_DIR/request.json" && "$SUPPORT" fsync "$REL_DIR" || rel_fail io_error
  REL_REQUEST="$REL_DIR/request.json"
  info=$("$SUPPORT" release-info "$REL_REQUEST" "$REL_JOB" "$IMAGE_DIR" "$REL_UUID" "$(getprop ro.build.version.incremental)" 2>> "$STATE/release-install.log") || rc=$?
  publish rel_free "$(field "$info" available)"; publish rel_required "$(field "$info" required)"
  if [ "$rc" != 0 ]; then
    if grep -q 'newer Android\|minimum build syntax' "$STATE/release-install.log"; then rel_fail incompatible_build
    elif grep -q 'file-size limit' "$STATE/release-install.log"; then rel_fail file_limit
    elif grep -q 'release free space' "$STATE/release-install.log"; then rel_fail no_space
    else rel_fail invalid_request; fi
  fi
  REL_NAME=$(field "$info" name); REL_SIZE=$(field "$info" size); REL_COMPRESSED=$(field "$info" compressed); REL_HEADROOM=$(field "$info" headroom)
  rel_paths
  rel_wake || rel_fail power_hold; REL_WAKE=1
  # Existing user files or legacy conversions are never replaced. An owned
  # release pair can be upgraded with a rollback copy on the same filesystem.
  if [ -e "$REL_FINAL" ] || [ -L "$REL_FINAL" ] || [ -e "$REL_FINAL.meta" ] || [ -L "$REL_FINAL.meta" ]; then
    local old probe
    old=$(completion_record "$REL_FINAL") || rel_fail output_exists
    [ "$(field "$old" origin)" = release ] || rel_fail output_exists
    probe=$(candidate_probe "$REL_FINAL") || rel_fail output_exists
    REL_ODEV=$(field "$probe" device); REL_OINO=$(field "$probe" inode)
    REL_OMDEV=$(stat -c %d "$REL_FINAL.meta"); REL_OMINO=$(stat -c %i "$REL_FINAL.meta")
    REL_OLD_GENERATION=$(field "$old" generation); REL_OLD_META_SHA=$(sha256sum "$REL_FINAL.meta" | cut -d' ' -f1)
  fi
  # Persist names before creating staging resources; identities follow each
  # successful reserve. Unknown/replaced files are retained, never inferred.
  rel_journal || rel_fail io_error; REL_JOURNALED=1
  local identity
  identity=$("$SUPPORT" reserve "$REL_PART") || rel_fail output_exists
  REL_DEV=$(field "$identity" device); REL_INO=$(field "$identity" inode); rel_journal || rel_fail io_error
  : > "$REL_DIR/verified"; : > "$REL_DIR/written"
  rel_state verifying
  if ! rel_child "$SUPPORT" release-unpack "$REL_REQUEST" "$REL_JOB" "$IMAGE_DIR" "$REL_PART" "$REL_DEV" "$REL_INO" "$REL_DIR/verified" "$REL_DIR/written" "${A36_UNXZ:-/system/bin/unxz}"; then
    [ -z "$REL_ERROR" ] || rel_fail "$REL_ERROR"
    if grep -q 'checksum\|short part\|part size\|part grew\|regular file\|No such file\|levels of symbolic links\|Permission denied' "$STATE/release-install.log"; then rel_fail download_invalid; else rel_fail unpack_failed; fi
  fi
  rel_state checking; publish rel_bytes "$REL_SIZE"; publish rel_total "$REL_SIZE"
  rel_child "$FSCK" -d0 --extract "$REL_PART" || rel_fail "${REL_ERROR:-verify_failed}"
  mkdir -p "$REL_VERIFY" || rel_fail io_error
  REL_LOOP=$(losetup -f) || rel_fail io_error; rel_journal || rel_fail io_error
  loop_attach_ro "$REL_LOOP" "$REL_PART" 0 || rel_fail io_error
  mount -t erofs -o ro "$REL_LOOP" "$REL_VERIFY" || rel_fail verify_failed
  REL_MOUNTED=1
  "$SUPPORT" metadata "$REL_VERIFY" >> "$STATE/release-install.log" 2>&1 || rel_fail verify_failed
  umount "$REL_VERIFY" || rel_fail io_error; REL_MOUNTED=0
  "$SUPPORT" detach-owned "$REL_LOOP" "$REL_DEV" "$REL_INO" 0 || rel_fail io_error
  REL_LOOP=; rel_journal || rel_fail io_error
  identity=$("$SUPPORT" reserve "$REL_META") || rel_fail output_exists
  REL_MDEV=$(field "$identity" device); REL_MINO=$(field "$identity" inode); rel_journal || rel_fail io_error
  "$SUPPORT" release-record "$REL_REQUEST" "$REL_JOB" "$REL_UUID" "$REL_PART" > "$REL_META" || rel_fail verify_failed
  "$SUPPORT" fsync "$REL_META" || rel_fail io_error
  REL_META_SHA=$(sha256sum "$REL_META" | cut -d' ' -f1) || rel_fail io_error
  rel_state publishing; rel_journal || rel_fail io_error
  if [ -n "$REL_ODEV" ]; then
    "$SUPPORT" publish "$REL_FINAL.meta" "$REL_OLDMETA" "$REL_OMDEV" "$REL_OMINO" &&
      "$SUPPORT" publish "$REL_FINAL" "$REL_OLD" "$REL_ODEV" "$REL_OINO" || rel_fail io_error
  fi
  "$SUPPORT" publish "$REL_META" "$REL_FINAL.meta" "$REL_MDEV" "$REL_MINO" &&
    "$SUPPORT" publish "$REL_PART" "$REL_FINAL" "$REL_DEV" "$REL_INO" || rel_fail io_error
  completion_record "$REL_FINAL" >/dev/null || rel_fail verify_failed
  REL_COMMITTED=1; rel_state cleaning
  # EXIT uses the same cleanup routine as boot/scan recovery. Parts are removed
  # there only after the complete, durable new image+record is recognized.
  return 0
}
