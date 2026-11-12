#!/system/bin/sh
# Shared-namespace update lifecycle. Source after images/release/prepared/guest.
# Journals are root-owned data, never sourced/evaluated as shell commands.
LIFE_DIR="$CONTROL/release-lifecycle"
PART_UPDATE_DIR="$CONTROL/partition-update"
life_intent() { [ -f "$LIFE_DIR/intent" ]; }
life_write() {
  local tmp="$LIFE_DIR/intent.tmp"
  {
    printf 'version=1\noperation=%s\njob=%s\nboot=%s\npid=%s\nstart=%s\nphase=%s\n' "$LIFE_OPERATION" "$LIFE_JOB" "$(boot_id)" "$$" "$(process_start $$)" "$LIFE_PHASE"
    printf 'old_id=%s\nold_generation=%s\nold_source=%s\nold_uuid=%s\nold_meta_sha=%s\nwant_stage=%s\n' "$LIFE_OLD_ID" "$LIFE_OLD_GENERATION" "$LIFE_OLD_SOURCE" "$LIFE_OLD_UUID" "$LIFE_OLD_META_SHA" "$LIFE_WANT_STAGE"
    printf 'wake_pid=%s\nwake_start=%s\n' "${A36_RELEASE_WAKE_PID:-}" "${A36_RELEASE_WAKE_START:-}"
  } > "$tmp" && "$SUPPORT" fsync "$tmp" && mv -f "$tmp" "$LIFE_DIR/intent" && "$SUPPORT" fsync "$LIFE_DIR"
}
life_load() {
  local data
  data=$(cat "$LIFE_DIR/intent") || return 1
  [ "$(field "$data" version)" = 1 ] || return 1
  LIFE_OPERATION=$(field "$data" operation); LIFE_JOB=$(field "$data" job); valid_job "$LIFE_JOB" || return 1
  case "$LIFE_OPERATION" in install-release|apply-release-to-partition) ;; *) return 1;; esac
  LIFE_PHASE=$(field "$data" phase); LIFE_OLD_ID=$(field "$data" old_id)
  LIFE_OLD_GENERATION=$(field "$data" old_generation); LIFE_OLD_SOURCE=$(field "$data" old_source)
  LIFE_OLD_UUID=$(field "$data" old_uuid); LIFE_OLD_META_SHA=$(field "$data" old_meta_sha)
  LIFE_WANT_STAGE=$(field "$data" want_stage)
  case "$LIFE_WANT_STAGE" in 0|1) ;; *) return 1;; esac
  [ -z "$LIFE_OLD_ID" ] || valid_id "$LIFE_OLD_ID"
}
life_wake_start() {
  local job=$1
  valid_job "$job" || return 1
  printf 'a36-release-%s 60000000000\n' "$job" > "${A36_POWER_DIR:-/sys/power}/wake_lock" || return 1
  "$SUPPORT" wake-hold "$job" "${A36_POWER_DIR:-/sys/power}" "${A36_RELEASE_WAKE_MS:-15000}" 6<&- 7>&- 9>&- >/dev/null 2>&1 &
  A36_RELEASE_WAKE_PID=$!; export A36_RELEASE_WAKE_PID
  A36_RELEASE_WAKE_START=$(process_start "$A36_RELEASE_WAKE_PID"); export A36_RELEASE_WAKE_START
  [ -n "$A36_RELEASE_WAKE_START" ] && kill -0 "$A36_RELEASE_WAKE_PID" 2>/dev/null
}
life_wake_stop() {
  # Stop/wait BEFORE unlock so an old renewer can never reacquire afterwards.
  if [ -n "${A36_RELEASE_WAKE_PID:-}" ] && [ -n "${A36_RELEASE_WAKE_START:-}" ] && [ "$(process_start "$A36_RELEASE_WAKE_PID")" = "$A36_RELEASE_WAKE_START" ]; then
    kill -KILL "$A36_RELEASE_WAKE_PID" 2>/dev/null || :; wait "$A36_RELEASE_WAKE_PID" 2>/dev/null || :
  fi
  printf 'a36-release-%s\n' "$LIFE_JOB" > "${A36_POWER_DIR:-/sys/power}/wake_unlock" || :
  A36_RELEASE_WAKE_PID=
}
release_lifecycle_begin() {
  local operation=$1 job=$2 previous info
  valid_job "$job" || return 1
  # Never compete with Android OTA/shutdown admission. Our own stale intent was
  # reconciled under both flocks before reaching this point.
  prestage_blocked && return 1
  exec 6</proc/self/ns/mnt || return 1
  mkdir -p "$LIFE_DIR" || return 1; chmod 0700 "$LIFE_DIR"
  LIFE_OPERATION=$operation; LIFE_JOB=$job; LIFE_PHASE=admitted
  LIFE_OLD_ID=; LIFE_OLD_GENERATION=; LIFE_OLD_SOURCE=; LIFE_OLD_UUID=; LIFE_OLD_META_SHA=
  LIFE_WANT_STAGE=0; prestage_enabled && LIFE_WANT_STAGE=1
  life_wake_start "$job" || return 1
  if resolve_selected_image; then
    LIFE_OLD_ID=$SOURCE_ID; LIFE_OLD_GENERATION=$GENERATION; LIFE_OLD_SOURCE=$SOURCE
    if [ "${SOURCE_TRANSPORT:-file}" = file ]; then
      LIFE_OLD_UUID=$CARD_UUID
      if [ -f "$SOURCE.meta" ]; then LIFE_OLD_META_SHA=$(sha256sum "$SOURCE.meta" | cut -d' ' -f1); fi
    fi
  fi
  life_write || { life_wake_stop; return 1; }
}
life_forget() {
  rm -f "$LIFE_DIR/intent" && "$SUPPORT" fsync "$LIFE_DIR"
}
life_stage() {
  local selected=$1 key
  valid_id "$selected" || return 1
  setprop "$SELECT_PROP" "$selected" || return 1
  IMAGE=; STAGE_ONLY=1; SESSION_OWNED=1; DID_HANDOFF=0; HANDOFF=1
  STAGE_COMPLETE=0; PARKED_STAGE=0; FORCE_UNSTAGE=0
  stage_mounts || { prestage_unmount_locked || :; SESSION_OWNED=0; return 1; }
  key=$(ready_key)
  [ -s "$CONTROL/ready/$key" ] && [ "$(cat "$CONTROL/ready/$key")" = "$EFFECTIVE" ] || {
    prestage_unmount_locked || :; SESSION_OWNED=0; return 1;
  }
  # A setting changed to OFF during verification/setup is respected immediately.
  if prestage_enabled; then
    prestage_park || { prestage_unmount_locked || :; SESSION_OWNED=0; return 1; }
  else prestage_unmount_locked || { SESSION_OWNED=0; return 1; }; fi
  SESSION_OWNED=0
}
life_old_selection() {
  if [ -n "$LIFE_OLD_ID" ]; then setprop "$SELECT_PROP" "$LIFE_OLD_ID"; else setprop "$SELECT_PROP" ""; fi
}
life_restore_stage() {
  life_old_selection || return 1
  [ "$LIFE_WANT_STAGE" = 1 ] && prestage_enabled && [ -n "$LIFE_OLD_ID" ] || return 0
  life_stage "$LIFE_OLD_ID"
}
release_load_journal() {
  local data
  data=$(cat "$REL_DIR/journal") || return 1
  [ "$(field "$data" version)" = 1 ] || return 1
  REL_JOB=$(field "$data" job); valid_job "$REL_JOB" || return 1
  REL_CARD=$(field "$data" card); REL_UUID=$(field "$data" card_uuid); REL_NAME=$(field "$data" name)
  case "$REL_NAME" in ''|.*|*[!a-zA-Z0-9_.+-]*) return 1;; esac
  rel_card_ok || return 1
  REL_DEV=$(field "$data" device); REL_INO=$(field "$data" inode)
  REL_MDEV=$(field "$data" meta_device); REL_MINO=$(field "$data" meta_inode)
  REL_ODEV=$(field "$data" old_device); REL_OINO=$(field "$data" old_inode)
  REL_OMDEV=$(field "$data" old_meta_device); REL_OMINO=$(field "$data" old_meta_inode)
  REL_OLD_GENERATION=$(field "$data" old_generation); REL_OLD_META_SHA=$(field "$data" old_meta_sha)
  REL_LOOP=$(field "$data" loop); REL_ERROR=$(field "$data" error)
  REL_META_SHA=$(field "$data" meta_sha)
  REL_PHASE=$(field "$data" phase); REL_START=$(clock_seconds)
  REL_CHILD=; REL_CHILD_START=; REL_COMMITTED=0; REL_MOUNTED=0; REL_JOURNALED=1; REL_WAKE=1
  rel_paths
}
life_backup_info() {
  local image=$1 meta=$2
  [ -n "$REL_OLD_META_SHA" ] && [ -n "$REL_OLD_GENERATION" ] || return 1
  "$SUPPORT" release-backup-info "$image" "$meta" "$REL_UUID" "$REL_NAME" "$REL_OLD_GENERATION" "$REL_OLD_META_SHA"
}
life_restore_file_pair() {
  local image meta info generation newmeta
  # A differently named previous image was never replaced by this transaction.
  [ "$LIFE_OLD_SOURCE" = "$REL_FINAL" ] || return 0
  [ -e "$REL_OLD" ] || [ -e "$REL_OLDMETA" ] || return 0
  image=$REL_OLD; [ -f "$image" ] || image=$REL_FINAL
  meta=$REL_OLDMETA; [ -f "$meta" ] || meta=$REL_FINAL.meta
  info=$(life_backup_info "$image" "$meta") || return 1
  if [ "$image" != "$REL_FINAL" ] && [ -f "$REL_FINAL" ]; then
    # Check the new pair against the pinned request before discarding a failed
    # SETUP result; an unrelated destination is never overwritten.
    local expected record
    expected=$("$SUPPORT" release-check "$REL_REQUEST" "$REL_JOB") || return 1
    record=$(completion_record "$REL_FINAL") || return 1
    [ "$(field "$record" job)" = "$REL_JOB" ] && [ "$(field "$record" generation)" = "$(field "$expected" generation)" ] || return 1
    "$SUPPORT" release-manifest "$REL_FINAL" "$REL_UUID" "$(getprop ro.build.version.incremental)" "$LIFE_DIR/new-manifest.json" || return 1
    newmeta=$(sha256sum "$REL_FINAL.meta" | cut -d' ' -f1)
    "$SUPPORT" release-remove-image "$LIFE_DIR/new-manifest.json" "$REL_FINAL" "$REL_UUID" "$newmeta" || return 1
  fi
  # Metadata first, image last, with fresh validated identities (FAT inode
  # numbers may change after reboot). Each rename is independently durable.
  if [ "$meta" != "$REL_FINAL.meta" ]; then
    [ ! -e "$REL_FINAL.meta" ] || return 1
    "$SUPPORT" publish "$meta" "$REL_FINAL.meta" "$(field "$info" meta_device)" "$(field "$info" meta_inode)" || return 1
  fi
  if [ "$image" != "$REL_FINAL" ]; then
    "$SUPPORT" publish "$image" "$REL_FINAL" "$(field "$info" device)" "$(field "$info" inode)" || return 1
  fi
  # Never let a recycled/synthesized inode identify the restored old pair as
  # this transaction's new output during the following idempotent cleanup.
  REL_DEV=; REL_INO=; REL_MDEV=; REL_MINO=; REL_COMMITTED=0
  LIFE_PHASE=rolled-back; life_write && rel_journal
}
life_cleanup_backups() {
  local info identity actual
  if [ -f "$REL_OLD" ]; then
    info=$(life_backup_info "$REL_OLD" "$REL_OLDMETA") || return 1
    rel_remove "$REL_OLD" "$(field "$info" device)" "$(field "$info" inode)" || return 1
  fi
  if [ -f "$REL_OLDMETA" ]; then
    actual=$(sha256sum "$REL_OLDMETA" | cut -d' ' -f1)
    [ "$actual" = "$REL_OLD_META_SHA" ] || return 1
    identity=$(stat -c '%d %i' "$REL_OLDMETA") || return 1
    # Decimal identity only; never evaluate metadata as shell code.
    set -- $identity; [ "$#" = 2 ] || return 1
    rel_remove "$REL_OLDMETA" "$1" "$2" || return 1
  fi
  "$SUPPORT" fsync "${REL_FINAL%/*}"
}
release_private_finish() {
  local rc=$1
  trap - EXIT INT TERM HUP
  if [ -n "$REL_CHILD" ] && [ -n "$REL_CHILD_START" ] && [ "$(process_start "$REL_CHILD")" = "$REL_CHILD_START" ]; then
    kill -KILL "$REL_CHILD" 2>/dev/null || :; wait "$REL_CHILD" 2>/dev/null || :
  fi
  if [ "$REL_MOUNTED" = 1 ]; then umount "$REL_VERIFY" || rc=1; REL_MOUNTED=0; fi
  if [ "$REL_JOURNALED" = 1 ]; then
    rel_transaction_cleanup defer || rc=1
    rel_journal || rc=1
  fi
  # Preserve operation identities across the trusted return exec. Crash recovery
  # uses the durable journals instead of relying on the environment.
  export REL_JOB REL_CARD REL_UUID REL_NAME REL_DEV REL_INO REL_MDEV REL_MINO REL_ODEV REL_OINO REL_OMDEV REL_OMINO REL_LOOP REL_ERROR REL_PHASE REL_START REL_COMMITTED REL_JOURNALED REL_WAKE REL_META_SHA REL_OLD_GENERATION REL_OLD_META_SHA
  if [ "${A36_RELEASE_TEST_INLINE:-0}" = 1 ]; then release_shared_finish "$rc"; return $?; fi
  exec "$SUPPORT" namespace-return 6 /system/bin/sh "$0" release-finalize "$rc" 6<&6 7>&7 9>&9
}
release_shared_finish() {
  local rc=$1 pending=0 selected
  trap - EXIT INT TERM HUP
  flock -n 7 7>&7 && flock -n 9 9>&9 || return 1
  OPERATION_LOCKED=1
  life_load || return 1
  A36_RELEASE_FINALIZING=1
  REL_CHILD=; REL_CHILD_START=; REL_MOUNTED=0
  if [ "$REL_JOURNALED" = 1 ]; then
    rel_card_ok && rel_paths && rel_transaction_cleanup defer || pending=1
  fi
  if [ "$pending" != 0 ]; then rc=1; REL_ERROR=io_error; fi
  if [ "$REL_COMMITTED" = 1 ] && [ "$pending" = 0 ]; then
    rc=0; selected=$(getprop "$SELECT_PROP")
    if [ "$LIFE_WANT_STAGE" = 1 ] && prestage_enabled; then
      rel_state setup
      if ! life_stage "$selected"; then
        rc=1; REL_ERROR=setup_failed
        prestage_unmount_locked || pending=1
        if [ "$pending" = 0 ]; then life_restore_file_pair || pending=1; fi
        if [ "$pending" = 0 ]; then life_restore_stage || pending=1; fi
      fi
    fi
    if [ "$rc" = 0 ]; then
      LIFE_PHASE=ready
      if ! life_write; then pending=1; rc=1; REL_ERROR=io_error; fi
      # Cleanup only after SETUP/parking succeeded. Parts are safe to remove
      # because the committed image remains valid independently of readiness.
      if [ "$pending" = 0 ]; then
        rel_clean_parts || pending=1
        life_cleanup_backups || pending=1
      fi
    elif [ "$pending" = 0 ]; then
      # A separately named new image may remain valid after SETUP fails, while
      # the previous selected image is restored. Its replaced, inactive backup
      # is no longer the recovery source; never strand it without its journal.
      life_cleanup_backups || pending=1
    fi
  elif [ "$pending" = 0 ]; then
    life_restore_stage || pending=1
  fi
  if [ "$pending" = 0 ]; then
    [ "$REL_JOURNALED" != 1 ] || { rm -f "$REL_DIR/journal" && "$SUPPORT" fsync "$REL_DIR"; } || pending=1
    [ "$pending" != 0 ] || life_forget || pending=1
  fi
  write_catalog
  if resolve_selected_image; then
    publish_resolution 1; publish_setup_state; publish rel_image "$SOURCE_ID"; publish rel_transport "$SOURCE_TRANSPORT"
  else publish_resolution 0; publish rel_image ""; fi
  publish rel_job "$LIFE_JOB"; publish rel_cleanup_pending "$pending"
  publish rel_elapsed "$(( $(clock_seconds)-REL_START ))"
  if [ "$rc" = 0 ]; then publish rel_error ""; publish rel_state done
  else publish rel_error "${REL_ERROR:-io_error}"; publish rel_state failed; fi
  publish rel_done_job "$LIFE_JOB"
  life_wake_stop
  A36_RELEASE_FINALIZING=0
  operation_done "$([ "$rc" = 0 ] && printf installed || printf failed)"; release_operation
  return "$rc"
}
release_refresh_roles() {
  local expected generation image meta info
  # Retire only the exact recorded temporary verification loop. Its kernel
  # identity remains useful even when the card's synthesized inode changes.
  if [ -n "$REL_LOOP" ]; then
    "$SUPPORT" retire-owned "$REL_LOOP" "$REL_DEV" "$REL_INO" 0 || return 1
    REL_LOOP=
  fi
  # Never reuse a stale inode number to identify a file after a restart.
  REL_DEV=; REL_INO=; REL_MDEV=; REL_MINO=
  REL_ODEV=; REL_OINO=; REL_OMDEV=; REL_OMINO=
  expected=$("$SUPPORT" release-check "$REL_REQUEST" "$REL_JOB") || return 1
  generation=$(field "$expected" generation)
  if valid_id "$REL_META_SHA"; then
    for image in "$REL_FINAL" "$REL_PART"; do
      [ -f "$image" ] || continue
      for meta in "$REL_FINAL.meta" "$REL_META"; do
        [ -f "$meta" ] || continue
        if info=$("$SUPPORT" release-backup-info "$image" "$meta" "$REL_UUID" "$REL_NAME" "$generation" "$REL_META_SHA" 2>/dev/null); then
          REL_DEV=$(field "$info" device); REL_INO=$(field "$info" inode)
          REL_MDEV=$(field "$info" meta_device); REL_MINO=$(field "$info" meta_inode)
          break 2
        fi
      done
    done
  fi
  if [ -z "$REL_DEV" ] && [ -f "$REL_PART" ] && info=$("$SUPPORT" release-partial-info "$REL_REQUEST" "$REL_JOB" "$REL_PART" 2>/dev/null); then
    REL_DEV=$(field "$info" device); REL_INO=$(field "$info" inode)
  fi
  if valid_id "$REL_OLD_META_SHA" && valid_id "$REL_OLD_GENERATION"; then
    for image in "$REL_OLD" "$REL_FINAL"; do
      [ -f "$image" ] || continue
      for meta in "$REL_OLDMETA" "$REL_FINAL.meta"; do
        [ -f "$meta" ] || continue
        if info=$(life_backup_info "$image" "$meta" 2>/dev/null); then
          REL_ODEV=$(field "$info" device); REL_OINO=$(field "$info" inode)
          REL_OMDEV=$(field "$info" meta_device); REL_OMINO=$(field "$info" meta_inode)
          break 2
        fi
      done
    done
  fi
  rel_journal
}
release_archive_unidentified() {
  # Incomplete files on FAT can acquire a different inode after reboot. Retain
  # such unknown outputs and their evidence rather than deleting by filename.
  # They must not prevent the restored, verified old image from launching.
  local dir="$CONTROL/release-orphans/$REL_JOB"
  mkdir -p "$dir" || return 1; chmod 0700 "$CONTROL/release-orphans" "$dir"
  cp "$REL_REQUEST" "$dir/request.json" && "$SUPPORT" fsync "$dir/request.json" || return 1
  cp "$LIFE_DIR/intent" "$dir/lifecycle" && "$SUPPORT" fsync "$dir/lifecycle" || return 1
  cp "$REL_DIR/journal" "$dir/journal" && "$SUPPORT" fsync "$dir/journal" && "$SUPPORT" fsync "$dir" || return 1
  rm -f "$REL_DIR/journal" && "$SUPPORT" fsync "$REL_DIR"
}
release_lifecycle_recover() {
  life_intent || return 0
  local data pid start boot committed_ready=0 cleanup=0 failed=0
  data=$(cat "$LIFE_DIR/intent") || return 1
  pid=$(field "$data" pid); start=$(field "$data" start); boot=$(field "$data" boot)
  if [ "$boot" = "$(boot_id)" ] && [ -n "$start" ] && [ "$(process_start "$pid")" = "$start" ]; then return 1; fi
  life_load || return 1
  life_wake_start "$LIFE_JOB" || return 1
  A36_RELEASE_FINALIZING=1
  # Any retained shared mounts belong to the interrupted lifecycle. Never run
  # a second setup on top of them or restore files while their old loop is busy.
  if ! prestage_unmount_locked; then
    life_wake_stop; A36_RELEASE_FINALIZING=0; return 1
  fi
  if [ "$LIFE_OPERATION" = apply-release-to-partition ]; then
    if command -v partition_update_recover >/dev/null; then partition_update_recover
    else life_wake_stop; A36_RELEASE_FINALIZING=0; return 1; fi
    return $?
  fi
  [ "$LIFE_PHASE" != ready ] || committed_ready=1
  if [ -f "$REL_DIR/journal" ]; then
    if ! release_load_journal || ! release_refresh_roles; then
      life_wake_stop; A36_RELEASE_FINALIZING=0; return 1
    fi
    if ! rel_transaction_cleanup defer; then cleanup=1; fi
    if [ "$committed_ready" = 1 ] && [ "$REL_COMMITTED" = 1 ]; then
      if [ "$LIFE_WANT_STAGE" = 1 ] && prestage_enabled; then life_stage "$(getprop "$SELECT_PROP")" || failed=1; fi
      if [ "$failed" = 0 ]; then rel_clean_parts || cleanup=1; life_cleanup_backups || cleanup=1; fi
    else
      life_restore_file_pair || failed=1
      [ "$failed" != 0 ] || life_restore_stage || failed=1
      [ "$failed" != 0 ] || life_cleanup_backups || cleanup=1
    fi
    if [ "$failed" = 0 ]; then
      if [ "$cleanup" = 0 ]; then rm -f "$REL_DIR/journal" && "$SUPPORT" fsync "$REL_DIR" || failed=1
      else release_archive_unidentified || failed=1; fi
    fi
  else
    life_restore_stage || failed=1
  fi
  if [ "$failed" = 0 ]; then life_forget || failed=1; fi
  publish rel_job "$LIFE_JOB"; publish rel_cleanup_pending "$cleanup"
  if [ "$committed_ready" = 1 ] && [ "$failed" = 0 ]; then publish rel_state done; publish rel_error ""
  else publish rel_state failed; publish rel_error interrupted; fi
  publish rel_done_job "$LIFE_JOB"
  life_wake_stop; A36_RELEASE_FINALIZING=0
  [ "$failed" = 0 ]
}

# --- verified file -> DARKOS -------------------------------------------------
part_paths() { PART_MANIFEST="$PART_UPDATE_DIR/manifest.json"; PART_PROGRESS="$PART_UPDATE_DIR/progress"; }
part_state() {
  PART_PHASE=$1; publish rel_job "$PART_JOB"; publish rel_state "$1"
  publish rel_elapsed "$(( $(clock_seconds)-PART_START ))"
}
part_journal() {
  local tmp="$PART_UPDATE_DIR/journal.tmp"
  {
    printf 'version=1\njob=%s\nphase=%s\nboot=%s\npid=%s\nstart=%s\n' "$PART_JOB" "$PART_PHASE" "$(boot_id)" "$$" "$(process_start $$)"
    printf 'card=%s\ncard_uuid=%s\nfile=%s\nfile_id=%s\nmeta_sha=%s\ngeneration=%s\n' "$PART_CARD" "$PART_UUID" "$PART_FILE" "$PART_FILE_ID" "$PART_META_SHA" "$PART_GENERATION"
    printf 'partition_id=%s\ndisk_uuid=%s\npart_uuid=%s\nfirst=%s\ncapacity=%s\n' "$PART_ID" "$PART_DISK_UUID" "$PART_PART_UUID" "$PART_FIRST" "$PART_CAPACITY"
    printf 'touched=%s\ncommitted=%s\nfallback=%s\nerror=%s\nchild=%s\nchild_start=%s\n' "$PART_TOUCHED" "$PART_COMMITTED" "$PART_FALLBACK" "$PART_ERROR" "$PART_CHILD" "$PART_CHILD_START"
  } > "$tmp" && "$SUPPORT" fsync "$tmp" && mv -f "$tmp" "$PART_UPDATE_DIR/journal" && "$SUPPORT" fsync "$PART_UPDATE_DIR"
}
part_card_ok() {
  local dir uuid dev opts
  while IFS="$(printf '\t')" read -r dir uuid dev opts; do
    if [ "$dir" = "$PART_CARD" ] && [ "$(printf '%s' "$uuid" | tr '[:upper:]' '[:lower:]')" = "$PART_UUID" ]; then
      set_card "$dir" "$uuid" "$dev" "$opts"
      [ "$PART_FILE" = "$IMAGE_DIR/${PART_FILE##*/}" ] && [ "$CARD_RW" = 1 ]; return $?
    fi
  done <<EOF_PART_CARDS
$(image_cards)
EOF_PART_CARDS
  return 1
}
part_target_ok() {
  local info
  prepared_devices || return 1
  info=$("$SUPPORT" partition-target "$PREPARED_DISK" "$PREPARED_PART" "${A36_BLOCK_SYS:-/sys/class/block}") || return 1
  [ "$(field "$info" source_id)" = "$PART_ID" ] && [ "$(field "$info" disk_uuid)" = "$PART_DISK_UUID" ] &&
    [ "$(field "$info" part_uuid)" = "$PART_PART_UUID" ] && [ "$(field "$info" first)" = "$PART_FIRST" ] && [ "$(field "$info" capacity)" = "$PART_CAPACITY" ]
}
part_child() {
  local rc=0
  "$SUPPORT" child "$@" >> "$STATE/partition-update.log" 2>&1 & PART_CHILD=$!
  PART_CHILD_START=$(process_start "$PART_CHILD"); part_journal || return 1
  while kill -0 "$PART_CHILD" 2>/dev/null; do
    PART_ERROR=
    if ! part_card_ok; then PART_ERROR=card_unavailable
    elif ! kill -0 "${A36_RELEASE_WAKE_PID:-invalid}" 2>/dev/null; then PART_ERROR=power_hold; fi
    if [ -n "$PART_ERROR" ]; then
      kill -KILL "$PART_CHILD" 2>/dev/null || :; wait "$PART_CHILD" 2>/dev/null || :; PART_CHILD=; PART_CHILD_START=; return 1
    fi
    publish rel_bytes "$(cat "$PART_PROGRESS" 2>/dev/null)"
    publish rel_elapsed "$(( $(clock_seconds)-PART_START ))"
    sleep "${A36_RELEASE_POLL:-2}"
  done
  wait "$PART_CHILD" || rc=$?
  PART_CHILD=; PART_CHILD_START=; part_journal || return 1
  [ "$rc" = 0 ]
}
part_fail() { PART_ERROR=$1; exit 1; }
partition_publish_boot() (
  local lock=${A36_BOOT_LOCK:-/dev/andr36oid-boot.lock} where=${A36_PREPARED_BOOT:-/mnt/andr36oid-prepared-boot} n=0 owner m
  while ! mkdir "$lock" 2>/dev/null; do
    owner=$(cat "$lock/pid" 2>/dev/null)
    if [ -n "$owner" ] && ! kill -0 "$owner" 2>/dev/null; then rm -rf "$lock"; continue; fi
    n=$((n+1)); [ "$n" -le 150 ] || exit 1; sleep 0.2
  done
  printf '%s\n' "$$" > "$lock/pid"
  trap 'umount "$where" 2>/dev/null || :; prepared_boot_unlock' EXIT
  for m in $(awk '$2 ~ /^\/mnt\/andr36oid-[a-z]*-boot$/ {print $2}' /proc/mounts); do umount "$m" || exit 1; done
  mkdir -p "$where" || exit 1
  mount -t vfat -o rw,nosuid,nodev,noexec,noatime "$PREPARED_BOOTDEV" "$where" || exit 1
  "$SUPPORT" partition-publish "$PART_MANIFEST" "$PREPARED_DISK" "$PREPARED_PART" "${A36_BLOCK_SYS:-/sys/class/block}" "$PREPARED_DIR/cache" "$where"
)
partition_private_finish() {
  local rc=$1
  trap - EXIT INT TERM HUP
  if [ -n "$PART_CHILD" ] && [ -n "$PART_CHILD_START" ] && [ "$(process_start "$PART_CHILD")" = "$PART_CHILD_START" ]; then
    kill -KILL "$PART_CHILD" 2>/dev/null || :; wait "$PART_CHILD" 2>/dev/null || :
  fi
  [ "$PART_JOURNALED" != 1 ] || part_journal || rc=1
  export PART_JOB PART_PHASE PART_START PART_CARD PART_UUID PART_FILE PART_FILE_ID PART_META_SHA PART_GENERATION PART_ID PART_DISK_UUID PART_PART_UUID PART_FIRST PART_CAPACITY PART_TOUCHED PART_COMMITTED PART_FALLBACK PART_ERROR PART_JOURNALED
  if [ "${A36_RELEASE_TEST_INLINE:-0}" = 1 ]; then partition_shared_finish "$rc"; return $?; fi
  exec "$SUPPORT" namespace-return 6 /system/bin/sh "$0" partition-finalize "$rc" 6<&6 7>&7 9>&9
}
apply_release_partition_worker() {
  [ "${A36_OPT_PRIVATE:-0}" = 1 ] || exit 2
  PART_JOB=$1; local selected=$2 info pin_rc=0
  valid_job "$PART_JOB" && valid_id "$selected" || exit 2
  flock -n 7 7>&7 && flock -n 9 9>&9 || exit 2; OPERATION_LOCKED=1
  PART_START=$(clock_seconds); PART_PHASE=queued; PART_ERROR=; PART_CHILD=; PART_CHILD_START=
  PART_CARD=; PART_UUID=; PART_FILE=; PART_FILE_ID=; PART_META_SHA=; PART_GENERATION=
  PART_ID=; PART_DISK_UUID=; PART_PART_UUID=; PART_FIRST=; PART_CAPACITY=
  PART_TOUCHED=0; PART_COMMITTED=0; PART_JOURNALED=0; PART_FALLBACK=
  mkdir -p "$PART_UPDATE_DIR" "$PREPARED_DIR/cache" "$STATE"; chmod 0700 "$PART_UPDATE_DIR" "$PREPARED_DIR" "$PREPARED_DIR/cache"
  part_paths; : > "$PART_PROGRESS"; : > "$STATE/partition-update.log"
  trap 'partition_private_finish $?; exit $?' EXIT
  trap 'PART_ERROR=interrupted; exit 1' INT TERM HUP
  publish rel_done_job ""; publish rel_error ""; publish rel_cleanup_pending 0; publish rel_fallback ""; publish rel_transport file
  part_state verifying
  IMAGE=
  resolve_selected_image "$selected" || part_fail invalid_request
  [ "$SOURCE_TRANSPORT" = file ] && [ "$EFFECTIVE" = "$SOURCE" ] && [ "$(field "$RECORD" origin)" = release ] || part_fail invalid_request
  PART_CARD=$CARD; PART_UUID=$CARD_UUID; PART_FILE=$SOURCE; PART_FILE_ID=$SOURCE_ID
  PART_GENERATION=$GENERATION; PART_META_SHA=$(sha256sum "$SOURCE.meta" | cut -d' ' -f1)
  part_card_ok || part_fail card_unavailable
  setprop "$SELECT_PROP" "$PART_FILE_ID" || part_fail io_error
  publish rel_image "$PART_FILE_ID"
  prepared_devices || part_fail card_unavailable
  prepared_pin_manifest 2>> "$STATE/partition-update.log" || pin_rc=$?
  if [ "$pin_rc" = 3 ]; then PART_FALLBACK=no_partition; return 0; fi
  [ "$pin_rc" = 0 ] || part_fail partition_invalid
  info=$("$SUPPORT" partition-plan "$PART_FILE" "$PART_UUID" "$PREPARED_DISK" "$PREPARED_PART" "${A36_BLOCK_SYS:-/sys/class/block}" "$(getprop ro.build.version.incremental)" "$PART_MANIFEST" 2>> "$STATE/partition-update.log") || part_fail partition_invalid
  PART_ID=$(field "$info" source_id); PART_DISK_UUID=$(field "$info" disk_uuid); PART_PART_UUID=$(field "$info" part_uuid)
  PART_FIRST=$(field "$info" first); PART_CAPACITY=$(field "$info" capacity)
  publish rel_total "$(field "$info" size)"; publish rel_bytes 0
  if [ "$(field "$info" fits)" = 0 ]; then PART_FALLBACK=too_large; return 0; fi
  part_journal || part_fail io_error; PART_JOURNALED=1
  # Journal+selection survive a crash BEFORE any block byte changes. The guard
  # also protects a stale old cache/UUID from every native partition reader.
  PART_TOUCHED=1; part_journal || part_fail io_error
  "$SUPPORT" partition-hold "$PART_MANIFEST" "$PREPARED_DISK" "$PREPARED_PART" "${A36_BLOCK_SYS:-/sys/class/block}" "$PREPARED_DIR/cache" >> "$STATE/partition-update.log" 2>&1 || part_fail partition_invalid
  part_state copying; part_journal || part_fail io_error
  part_child "$SUPPORT" partition-copy "$PART_MANIFEST" "$PART_FILE" "$PART_UUID" "$PREPARED_DISK" "$PREPARED_PART" "${A36_BLOCK_SYS:-/sys/class/block}" "$PREPARED_DIR/cache" "$PART_PROGRESS" || part_fail "${PART_ERROR:-verify_failed}"
  part_state publishing; part_journal || part_fail io_error
  part_target_ok && partition_publish_boot || part_fail io_error
  setprop "$SELECT_PROP" "$PART_ID" || part_fail io_error
  "$SUPPORT" partition-activate "$PART_MANIFEST" "$PREPARED_DISK" "$PREPARED_PART" "${A36_BLOCK_SYS:-/sys/class/block}" "$PREPARED_DIR/cache" >> "$STATE/partition-update.log" 2>&1 || part_fail io_error
  PART_COMMITTED=1; part_state committed; part_journal || part_fail io_error
}
partition_shared_finish() {
  local rc=$1 pending=0 target
  trap - EXIT INT TERM HUP
  flock -n 7 7>&7 && flock -n 9 9>&9 || return 1; OPERATION_LOCKED=1
  life_load || return 1; part_paths
  PART_CHILD=; PART_CHILD_START=; A36_RELEASE_FINALIZING=1
  if [ "$PART_COMMITTED" = 1 ]; then
    target=$PART_ID; rc=0
  elif [ -n "$PART_FALLBACK" ] && valid_id "$PART_FILE_ID"; then
    target=$PART_FILE_ID; rc=0
  else
    target=$LIFE_OLD_ID
    # Once overwrite admission was durable the retained verified file is the
    # recovery image; the old partition may no longer exist.
    [ "$PART_TOUCHED" != 1 ] || target=$PART_FILE_ID
  fi
  if [ -n "$target" ]; then setprop "$SELECT_PROP" "$target" || pending=1; fi
  if [ "$LIFE_WANT_STAGE" = 1 ] && prestage_enabled && [ -n "$target" ] && [ "$pending" = 0 ]; then
    part_state setup
    if ! life_stage "$target"; then
      rc=1; PART_ERROR=setup_failed
      prestage_unmount_locked || pending=1
      if [ "$pending" = 0 ]; then
        if [ "$PART_TOUCHED" = 1 ]; then LIFE_OLD_ID=$PART_FILE_ID; fi
        life_restore_stage || pending=1
      fi
    fi
  fi
  if [ "$rc" = 0 ] && [ "$pending" = 0 ]; then
    LIFE_PHASE=ready
    if ! life_write; then pending=1; rc=1; PART_ERROR=io_error; fi
    if [ "$PART_COMMITTED" = 1 ] && [ "$pending" = 0 ]; then
      part_state cleaning
      part_card_ok && "$SUPPORT" release-remove-image "$PART_MANIFEST" "$PART_FILE" "$PART_UUID" "$PART_META_SHA" >> "$STATE/partition-update.log" 2>&1 || pending=1
    fi
  fi
  if [ "$pending" = 0 ]; then
    # Failed copies remain explicitly ineligible, but their journal does not
    # block the still-verified file from launching. A later retry replaces the
    # guard only under these same storage/operation locks.
    if [ "$rc" != 0 ] && [ "$PART_TOUCHED" = 1 ] && [ "$PART_COMMITTED" != 1 ]; then
      part_state abandoned; part_journal || pending=1
    elif [ "$PART_JOURNALED" = 1 ]; then
      rm -f "$PART_UPDATE_DIR/journal" && "$SUPPORT" fsync "$PART_UPDATE_DIR" || pending=1
    fi
    [ "$pending" != 0 ] || life_forget || pending=1
  fi
  write_catalog
  if resolve_selected_image; then publish_resolution 1; publish_setup_state; publish rel_image "$SOURCE_ID"; publish rel_transport "$SOURCE_TRANSPORT"; fi
  publish rel_job "$PART_JOB"; publish rel_cleanup_pending "$pending"; publish rel_fallback "$PART_FALLBACK"
  if [ "$rc" = 0 ]; then publish rel_state done; publish rel_error ""
  else publish rel_state failed; publish rel_error "${PART_ERROR:-io_error}"; fi
  publish rel_done_job "$PART_JOB"
  life_wake_stop; A36_RELEASE_FINALIZING=0
  operation_done "$([ "$rc" = 0 ] && printf installed || printf failed)"; release_operation
  return "$rc"
}
part_load() {
  local data description
  data=$(cat "$PART_UPDATE_DIR/journal") || return 1
  [ "$(field "$data" version)" = 1 ] || return 1
  PART_JOB=$(field "$data" job); [ "$PART_JOB" = "$LIFE_JOB" ] || return 1
  PART_PHASE=$(field "$data" phase); PART_START=$(clock_seconds)
  PART_CARD=$(field "$data" card); PART_UUID=$(field "$data" card_uuid)
  PART_FILE=$(field "$data" file); PART_FILE_ID=$(field "$data" file_id)
  PART_META_SHA=$(field "$data" meta_sha); PART_GENERATION=$(field "$data" generation)
  PART_ID=$(field "$data" partition_id); PART_DISK_UUID=$(field "$data" disk_uuid)
  PART_PART_UUID=$(field "$data" part_uuid); PART_FIRST=$(field "$data" first); PART_CAPACITY=$(field "$data" capacity)
  PART_TOUCHED=$(field "$data" touched); PART_COMMITTED=$(field "$data" committed)
  PART_FALLBACK=$(field "$data" fallback); PART_ERROR=$(field "$data" error)
  PART_CHILD=; PART_CHILD_START=; PART_JOURNALED=1; part_paths
  valid_id "$PART_FILE_ID" && valid_id "$PART_META_SHA" && valid_id "$PART_GENERATION" && valid_id "$PART_ID" || return 1
  case "$PART_TOUCHED:$PART_COMMITTED" in 0:0|1:0|1:1) ;; *) return 1;; esac
  case "${PART_FILE##*/}" in ''|.*|*[!a-zA-Z0-9_.+-]*) return 1;; esac
  description=$("$SUPPORT" partition-describe "$PART_MANIFEST") || return 1
  [ "$(field "$description" generation)" = "$PART_GENERATION" ] || return 1
  part_card_ok && part_target_ok
}
partition_update_recover() {
  local info rc=0
  part_paths
  if [ ! -f "$PART_UPDATE_DIR/journal" ]; then
    # Admission died before a target was pinned; no partition was writable yet.
    life_restore_stage && life_forget || rc=1
    publish rel_job "$LIFE_JOB"; publish rel_state failed; publish rel_error interrupted
    publish rel_done_job "$LIFE_JOB"; publish rel_cleanup_pending "$rc"
    life_wake_stop; A36_RELEASE_FINALIZING=0; return "$rc"
  fi
  if ! part_load; then
    publish rel_cleanup_pending 1; life_wake_stop; A36_RELEASE_FINALIZING=0; return 1
  fi
  if [ "$PART_COMMITTED" = 1 ]; then
    # The source may already be unlinked. Trust neither a stale UUID nor cache:
    # re-read the complete target before finishing source cleanup after a crash.
    part_state checking
    if ! part_child "$SUPPORT" partition-recheck "$PART_MANIFEST" "$PREPARED_DISK" "$PREPARED_PART" "${A36_BLOCK_SYS:-/sys/class/block}" "$PREPARED_DIR/cache" "$(getprop ro.build.version.incremental)" "$PART_PROGRESS"; then
      publish rel_cleanup_pending 1; life_wake_stop; A36_RELEASE_FINALIZING=0; return 1
    fi
    # Re-publish the pinned manifest under the BOOT lock. Re-establishing the
    # guard also covers a crash immediately after its prior activation.
    "$SUPPORT" partition-hold "$PART_MANIFEST" "$PREPARED_DISK" "$PREPARED_PART" "${A36_BLOCK_SYS:-/sys/class/block}" "$PREPARED_DIR/cache" &&
      partition_publish_boot && setprop "$SELECT_PROP" "$PART_ID" &&
      "$SUPPORT" partition-activate "$PART_MANIFEST" "$PREPARED_DISK" "$PREPARED_PART" "${A36_BLOCK_SYS:-/sys/class/block}" "$PREPARED_DIR/cache" || {
        publish rel_cleanup_pending 1; life_wake_stop; A36_RELEASE_FINALIZING=0; return 1;
      }
    partition_shared_finish 0
  else
    # An interrupted write never resumes on boot. Validate the retained file,
    # block the possibly half-written partition, and leave the file selectable.
    info=$("$SUPPORT" release-backup-info "$PART_FILE" "$PART_FILE.meta" "$PART_UUID" "${PART_FILE##*/}" "$PART_GENERATION" "$PART_META_SHA") || rc=1
    if [ "$rc" = 0 ] && [ "$PART_TOUCHED" = 1 ]; then
      "$SUPPORT" partition-hold "$PART_MANIFEST" "$PREPARED_DISK" "$PREPARED_PART" "${A36_BLOCK_SYS:-/sys/class/block}" "$PREPARED_DIR/cache" || rc=1
    fi
    if [ "$rc" != 0 ]; then publish rel_cleanup_pending 1; life_wake_stop; A36_RELEASE_FINALIZING=0; return 1; fi
    PART_ERROR=interrupted
    partition_shared_finish 1
    # A handled interruption is a failed update, not failed recovery. Allow the
    # next normal operation to launch the retained verified file.
    ! life_intent
  fi
}
