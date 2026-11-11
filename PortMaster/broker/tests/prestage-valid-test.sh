#!/bin/sh
set -eu
. "$(dirname "$0")/test-lib.inc"
CONTROL="$tmp/control";mkdir -p "$CONTROL"
SELECT_PROP=persist.sys.andr36oid.subsystem.selected
IMAGE=;SOURCE="$tmp/image";EFFECTIVE=$SOURCE;DIAGNOSTIC_DIR=0
printf image > "$SOURCE"
LIB=${broker%/*}/andr36oid-images.sh
# Only the proc input is adapted; the actual batch/key logic is executed.
sed "s@/proc/mounts@$tmp/mounts@g" "$LIB" > "$tmp/images"
broker="$tmp/images"
load_functions boot_id phase_clock prestage_blocked prestage_storage_token prestage_signature prestage_valid
getprop() { [ "$1" != "$SELECT_PROP" ] || printf '%s' "${mock_selected:-}"; }
resolve_selected_image() { echo resolve >> "$tmp/resolves";FAMILY=fixture;GENERATION=one; }
prepare_writable_state() { :; }
SUPPORT="$tmp/support"
cat > "$SUPPORT" <<'SUPPORT'
#!/bin/sh
[ "$1" = loop-owned ] && [ ! -e "$FAIL_LOOP" ]
SUPPORT
chmod 0755 "$SUPPORT";FAIL_LOOP="$tmp/fail-loop";export FAIL_LOOP
printf '%s\n' "$ROOT/dev" "$ROOT" > "$STATE/session.mounts"
printf '%s\n' "root $ROOT overlay rw 0 0" "tmpfs $ROOT/dev tmpfs rw 0 0" > "$tmp/mounts"
printf '/dev/loop17 1 2 0\n' > "$STATE/session.loop"
printf '/fixture\t/roms\n' > "$STATE/roms-map"
boot_id > "$STATE/prestage.boot"
prestage_signature resolved > "$STATE/prestage.ready"
prestage_valid || fail 'valid stage rejected'
[ "$(wc -l < "$tmp/resolves")" = 1 ] || fail 'warm key resolved the image twice'
cp "$tmp/mounts" "$tmp/mounts.save"
head -n 1 "$tmp/mounts.save" > "$tmp/mounts"
if prestage_valid; then fail 'missing mount accepted'; fi
cp "$tmp/mounts.save" "$tmp/mounts"
touch "$FAIL_LOOP"
if prestage_valid; then fail 'loop ownership failure accepted'; fi
rm "$FAIL_LOOP"
mock_selected=new-selection
if prestage_valid; then fail 'changed selection accepted'; fi
mock_selected=
printf changed-image >> "$SOURCE"
if prestage_valid; then fail 'changed source stat accepted'; fi
clock=$(phase_clock);case "$clock" in ''|*[!0-9]*) fail 'invalid clock';;esac
echo 'PASS: one native resolve, batch mount validation, missing mount/loop/selection/source invalidation, builtin clock'
