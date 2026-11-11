#!/bin/sh
set -eu
. "$(dirname "$0")/test-lib.inc"
# Adapt only the Android command path; execute the production timeout wrapper.
sed "s@/system/bin/cmd@$tmp/cmd@g" "$broker" > "$tmp/broker"
broker="$tmp/broker"
load_functions update_settings_entry
cat > "$tmp/cmd" <<'AM'
#!/bin/sh
printf '%s\n' "$*" >> "$REFRESH_CALLS"
exec sleep 45
AM
chmod 0755 "$tmp/cmd"
REFRESH_CALLS="$tmp/calls";export REFRESH_CALLS
start=$(date +%s)
update_settings_entry org.andr36oid.syncsettings
elapsed=$(($(date +%s)-start))
[ "$elapsed" -ge 1 ] && [ "$elapsed" -le 4 ] || fail "stalled Settings refresh took ${elapsed}s"
[ "$(cat "$REFRESH_CALLS")" = 'activity broadcast --user 0 -a org.andr36oid.syncsettings.UPDATE_SETTINGS -n org.andr36oid.syncsettings/.BootReceiver' ] || fail 'refresh target changed'
echo 'PASS: stalled ordered Settings receiver cannot retain completed session; correct receiver still requested'
