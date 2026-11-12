#!/system/bin/sh
# Ordinary Android boot first, then the proven framework-down handoff.
BOOT_GUARD=${A36_BOOT_GUARD:-/dev/.andr36oid-subsystem-boot}
BOOT_FAIL_PROP=persist.andr36oid.subsystem.autostart_failures
BOOT_ERROR_PROP=persist.andr36oid.subsystem.autostart_error
boot_phase_write() {
  local token=$1 phase=$2 stamp
  stamp=$(process_start $$) || return 1
  printf 'token=%s\nphase=%s\npid=%s\nstart=%s\n' "$token" "$phase" "$$" "$stamp" > "$BOOT_GUARD/phase.tmp" &&
    chmod 0600 "$BOOT_GUARD/phase.tmp" && mv -f "$BOOT_GUARD/phase.tmp" "$BOOT_GUARD/phase"
}
boot_scan_refresh() {
  local tries=0
  # One init service owns automatic boot/return scans. Explicit scan calls still
  # take the normal locks. A return trigger may precede the old broker's exit.
  while ! scan_images; do
    if session_active || [ "$(getprop init.svc.andr36oid-subsystem)" = running ]; then return 0; fi
    tries=$((tries+1)); [ "$tries" -lt 30 ] || return 1
    sleep 1
  done
}
boot_wait_storage() {
  local info selected tries=0 id
  selected=$(getprop "$SELECT_PROP")
  # DARKOS on the boot card is independent of vold mounting a games card.
  if command -v prepared_devices >/dev/null && prepared_devices &&
     info=$("$SUPPORT" partition-target "$PREPARED_DISK" "$PREPARED_PART" "${A36_BLOCK_SYS:-/sys/class/block}" 2>/dev/null) &&
     { [ -z "$selected" ] || [ "$selected" = "$(field "$info" source_id)" ]; }; then return 0; fi
  # ready=1 with no id is the resolver's explicit no-games-card result. Before
  # ready, an empty id is NOT proof that vold has no card to mount.
  while [ "$(getprop sys.andr36oid.easyroms.ready)" != 1 ] && [ "$tries" -lt 30 ]; do
    sleep 1; tries=$((tries+1))
  done
  id=$(getprop sys.andr36oid.easyroms.id)
  [ -z "$id" ] || wait_games_card_mount "$((30-tries))" || :
}
boot_decision() {
  if ! mkdir "$BOOT_GUARD" 2>/dev/null; then boot_scan_refresh; return 0; fi
  if session_active || [ "$(getprop init.svc.andr36oid-subsystem)" = running ]; then boot_scan_refresh; return 0; fi
  local effective target held=0 failures token
  effective=$(getprop persist.andr36oid.subsystem.default)
  [ "$effective" = subsystem ] || effective=android
  target=$(getprop persist.andr36oid.subsystem.default_target); [ "$target" = PM ] || target=ES
  # Sample ONCE at boot-service admission, before card waits or hashing.
  if "$KEYHELD"; then held=1; if [ "$effective" = android ]; then effective=subsystem; else effective=android; fi; fi
  log "boot selection: START=$held effective=$effective target=$target"
  publish boot_deciding 1
  if [ "$effective" = subsystem ]; then boot_wait_storage; fi
  if ! boot_scan_refresh; then publish boot_reason busy; publish boot_deciding 0; return 0; fi
  if [ "$effective" != subsystem ]; then publish boot_reason android; publish boot_deciding 0; return 0; fi
  if [ "$(prop image)" != 1 ]; then publish boot_reason no_image; publish boot_deciding 0; return 0; fi
  failures=$(getprop "$BOOT_FAIL_PROP")
  case "$failures" in ''|0) failures=0;; 1) failures=1;; *) failures=2;; esac
  if [ "$failures" = 2 ]; then publish boot_reason fail_safe; publish boot_deciding 0; return 0; fi
  token=$(boot_id)
  # Arm BEFORE launch. A reboot/SIGKILL before the watcher runs cannot lose a
  # failed boot; only a real live payload interval of 30s clears this counter.
  if ! setprop "$BOOT_FAIL_PROP" "$((failures+1))" || ! boot_phase_write "$token" waiting; then
    publish boot_reason state_failed; publish boot_deciding 0; return 0
  fi
  setprop "$BOOT_ERROR_PROP" launch_pending
  publish boot_token "$token"; publish boot_pending 1; publish boot_reason launching
  setprop sys.andr36oid.boottarget subsystem
  setprop ctl.start andr36oid-subsystem-boot-watch
  publish rootfs ""; publish handoff 1; publish port "$target"
  publish boot_deciding 0
  publish request run
}
boot_autostart_capture() {
  AUTO_BOOT_TOKEN=
  if [ "$(prop boot_pending)" = 1 ] && [ "$(prop boot_token)" = "$(boot_id)" ]; then
    AUTO_BOOT_TOKEN=$(prop boot_token)
    # Covers lock/admission exits before the ordinary session EXIT trap exists.
    trap 'boot_autostart_end' EXIT
  fi
}
boot_autostart_running() {
  [ -n "${AUTO_BOOT_TOKEN:-}" ] || return 0
  boot_phase_write "$AUTO_BOOT_TOKEN" running || return 1
  publish boot_pending 0; publish boot_reason running
}
boot_autostart_end() {
  [ -n "${AUTO_BOOT_TOKEN:-}" ] || return 0
  boot_phase_write "$AUTO_BOOT_TOKEN" ended || :
  publish boot_pending 0; publish boot_reason ended
  setprop sys.andr36oid.boottarget android
}
boot_watch() {
  local token result
  token=$(prop boot_token); [ -n "$token" ] && [ "$token" = "$(boot_id)" ] || return 0
  result=$("$SUPPORT" autostart-watch "$BOOT_GUARD/phase" "$token" "${A36_BOOT_LAUNCH_WAIT_MS:-180000}" "${A36_BOOT_SUCCESS_MS:-30000}") || result=failed_launch
  [ "$(prop boot_token)" = "$token" ] || return 0
  case "$result" in
    success) setprop "$BOOT_FAIL_PROP" 0; setprop "$BOOT_ERROR_PROP" ""; log 'autostart: live session 30s, failure counter reset';;
    failed_launch|ended_early)
      setprop "$BOOT_ERROR_PROP" "$result"; publish boot_pending 0
      log "autostart: $result (armed failure count retained)";;
    changed) :;;
  esac
}
