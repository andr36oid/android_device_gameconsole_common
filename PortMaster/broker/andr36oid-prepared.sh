#!/system/bin/sh
# Prepared-card DARKOS transport; source after the image/release libraries.
PREPARED_DIR="$CONTROL/prepared"
SOURCE_TRANSPORT=${SOURCE_TRANSPORT:-file}
prepared_boot_unlock() {
  [ "$(cat "${A36_BOOT_LOCK:-/dev/andr36oid-boot.lock}/pid" 2>/dev/null)" != "$$" ] || rm -rf "${A36_BOOT_LOCK:-/dev/andr36oid-boot.lock}"
}
prepared_pin_manifest() (
  local lock=${A36_BOOT_LOCK:-/dev/andr36oid-boot.lock} mountpoint=${A36_PREPARED_BOOT:-/mnt/andr36oid-prepared-boot} n=0 pid m
  while ! mkdir "$lock" 2>/dev/null; do
    pid=$(cat "$lock/pid" 2>/dev/null)
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then rm -rf "$lock"; continue; fi
    n=$((n+1)); [ "$n" -le 150 ] || exit 4; sleep 0.2
  done
  printf '%s\n' "$$" > "$lock/pid"
  trap 'umount "$mountpoint" 2>/dev/null || :; prepared_boot_unlock' EXIT
  # Same BOOT lock protocol as KT/DtbHint/card setup: recover mounts whose owner
  # died, never mount the card's FAT filesystem with conflicting ro/rw flags.
  for m in $(awk '$2 ~ /^\/mnt\/andr36oid-[a-z]*-boot$/ {print $2}' /proc/mounts); do umount "$m" || exit 1; done
  mkdir -p "$mountpoint" || exit 1
  mount -t vfat -o ro,nosuid,nodev,noexec,noatime "$PREPARED_BOOTDEV" "$mountpoint" || exit 2
  if [ ! -e "$mountpoint/andr36oid-darkosen.json" ]; then
    if awk '$1=="DARKOS" {found=1} END {exit !found}' "$mountpoint/andr36oid-layout.txt" 2>/dev/null; then exit 1; fi
    exit 3
  fi
  "$SUPPORT" partition-pin "$mountpoint/andr36oid-darkosen.json" "$PREPARED_DIR/manifest.json"
)
prepared_devices() {
  local sysdev bootdev name base=${A36_BYNAME:-} controller
  if [ -z "$base" ]; then
    controller=$(getprop ro.boot.boot_devices)
    case "$controller" in ff370000.dwmmc|ff390000.dwmmc) ;; *) return 1;; esac
    base="/dev/block/platform/$controller/by-name"
  fi
  sysdev=$(readlink -f "$base/system") || return 1
  bootdev=$(readlink -f "$base/BOOT") || return 1
  name=${sysdev##*/}
  case "$name" in mmcblk[0-9]*p2) ;; *) return 1;; esac
  PREPARED_DISK=${sysdev%p2}; PREPARED_PART="${PREPARED_DISK}p14"; PREPARED_BOOTDEV="${PREPARED_DISK}p1"
  [ "$sysdev" = "${PREPARED_DISK}p2" ] && [ "$bootdev" = "$PREPARED_BOOTDEV" ]
}
prepared_invalid() { publish prepared_error "$1"; publish prepared_state invalid; return 1; }
prepared_native_error() {
  if grep -q 'newer Android\|minimum build syntax' "$PREPARED_DIR/error.tmp"; then prepared_invalid incompatible_build
  elif grep -q 'partition image checksum' "$PREPARED_DIR/error.tmp"; then prepared_invalid checksum_failed
  elif grep -q 'No such file\|No such device' "$PREPARED_DIR/error.tmp"; then prepared_invalid card_unavailable
  elif grep -q 'Input/output error\|No space left\|cache\|fsync\|Permission denied' "$PREPARED_DIR/error.tmp"; then prepared_invalid io_error
  else prepared_invalid partition_invalid; fi
}
prepared_probe() {
  local mode=${1:-cached} info description child start rc=0 pin_rc=0
  PREPARED_INFO=
  if ! prepared_devices; then publish prepared 0; return 1; fi
  mkdir -p "$PREPARED_DIR/cache" "$STATE"; chmod 0700 "$PREPARED_DIR" "$PREPARED_DIR/cache"
  prepared_pin_manifest 2> "$PREPARED_DIR/error.tmp" || pin_rc=$?
  cat "$PREPARED_DIR/error.tmp" >> "$STATE/prepared.log"
  if [ "$pin_rc" = 3 ] && grep -q '^PARTNAME=DARKOS$' "${A36_BLOCK_SYS:-/sys/class/block}/${PREPARED_PART##*/}/uevent" 2>/dev/null; then pin_rc=1; fi
  if [ "$pin_rc" = 3 ]; then
    publish prepared 0; publish prepared_error ""; publish prepared_state ""; publish prepared_id ""; publish prepared_generation ""; return 1
  fi
  publish prepared 1
  if [ "$pin_rc" != 0 ]; then
    [ "$pin_rc" != 4 ] || { prepared_invalid busy; return 1; }
    [ "$pin_rc" != 2 ] || { prepared_invalid card_unavailable; return 1; }
    prepared_invalid manifest_invalid; return 1
  fi
  [ -b "$PREPARED_PART" ] || { prepared_invalid card_unavailable; return 1; }
  description=$("$SUPPORT" partition-describe "$PREPARED_DIR/manifest.json" 2> "$PREPARED_DIR/error.tmp") || { prepared_invalid manifest_invalid; return 1; }
  publish prepared_total "$(field "$description" size)"
  if info=$("$SUPPORT" partition-cached "$PREPARED_DIR/manifest.json" "$PREPARED_DISK" "$PREPARED_PART" \
      "${A36_BLOCK_SYS:-/sys/class/block}" "$PREPARED_DIR/cache" "$(getprop ro.build.version.incremental)" - 2> "$PREPARED_DIR/error.tmp"); then
    :
  elif [ "$mode" = verify ]; then
    : > "$PREPARED_DIR/progress"
    publish prepared_error ""; publish prepared_state verifying; publish prepared_bytes 0
    printf 'a36-prepared 60000000000\n' > "${A36_POWER_DIR:-/sys/power}/wake_lock" || { prepared_invalid power_hold; return 1; }
    nice -n 10 "$SUPPORT" child "$SUPPORT" partition-info "$PREPARED_DIR/manifest.json" "$PREPARED_DISK" "$PREPARED_PART" \
      "${A36_BLOCK_SYS:-/sys/class/block}" "$PREPARED_DIR/cache" "$(getprop ro.build.version.incremental)" "$PREPARED_DIR/progress" \
      > "$PREPARED_DIR/info.tmp" 2> "$PREPARED_DIR/error.tmp" & child=$!
    start=$(process_start "$child")
    while kill -0 "$child" 2>/dev/null; do
      if ! printf 'a36-prepared 60000000000\n' > "${A36_POWER_DIR:-/sys/power}/wake_lock"; then
        kill -KILL "$child" 2>/dev/null || :; rc=1; publish prepared_error power_hold; break
      fi
      publish prepared_bytes "$(cat "$PREPARED_DIR/progress")"; sleep 2
    done
    wait "$child" || rc=1
    printf 'a36-prepared\n' > "${A36_POWER_DIR:-/sys/power}/wake_unlock" || :
    cat "$PREPARED_DIR/error.tmp" >> "$STATE/prepared.log"
    if [ "$rc" != 0 ]; then
      if [ "$(prop prepared_error)" = power_hold ]; then prepared_invalid power_hold; else prepared_native_error; fi
      return 1
    fi
    info=$(cat "$PREPARED_DIR/info.tmp")
  else return 1
  fi
  PREPARED_INFO=$info
  publish prepared_error ""; publish prepared_state ready; publish prepared_bytes "$(field "$info" size)"
  publish prepared_id "$(field "$info" source_id)"; publish prepared_generation "$(field "$info" generation)"
  return 0
}
use_partition() {
  SOURCE_TRANSPORT=partition; SOURCE=$PREPARED_PART; EFFECTIVE=$SOURCE; EFFECTIVE_FORMAT=erofs
  SOURCE_ID=$(field "$PREPARED_INFO" source_id); ACTIVE_ID=$SOURCE_ID; FAMILY=$SOURCE_ID
  GENERATION=$(field "$PREPARED_INFO" generation); RECORD=$PREPARED_INFO; SOURCE_PROBE=$PREPARED_INFO
  RESOLVE_STATE=ready_erofs; DIAGNOSTIC_DIR=0
  RESOLVE_SELECTION=${1:-}; RESOLVE_PREFER=1
}
resolve_partition() {
  [ -z "$IMAGE" ] || return 1
  local selected=${1:-} mode=${2:-cached}
  # A first default resolution verifies once. An explicit file choice must not
  # wait for hashing an unused partition (including an outgrown old release).
  [ -n "$selected" ] || mode=verify
  prepared_probe "$mode" || return 1
  [ -z "$selected" ] || [ "$selected" = "$(field "$PREPARED_INFO" source_id)" ] || return 1
  use_partition "$selected"
}
attach_partition_lower() {
  local family=$FAMILY generation=$GENERATION
  prepared_probe cached || return 1
  [ "$(field "$PREPARED_INFO" source_id)" = "$family" ] && [ "$(field "$PREPARED_INFO" generation)" = "$generation" ] || return 1
  LOOPDEV=
  mount -t erofs -o ro "$PREPARED_PART" "$LOWER" || return 1
  track_mount "$LOWER" || return 1
  if ! "$SUPPORT" metadata "$LOWER" >/dev/null; then
    umount "$LOWER" || return 1; forget_mount "$LOWER"; return 1
  fi
}
partition_lower_valid() {
  prepared_probe cached || return 1
  [ "$(field "$PREPARED_INFO" source_id)" = "$FAMILY" ] && [ "$(field "$PREPARED_INFO" generation)" = "$GENERATION" ] || return 1
  awk -v p="$LOWER" -v d="$PREPARED_PART" '$1==d && $2==p && $3=="erofs" && $4 ~ /(^|,)ro(,|$)/ {ok=1} END {exit !ok}' /proc/mounts
}
