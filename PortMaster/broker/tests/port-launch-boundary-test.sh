#!/bin/sh
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions root_path
broker=${broker%/*}/andr36oid-guest.sh
load_functions repair_port_launch_boundary
IMAGE=;GUEST="$tmp/guest"
printf fake-static-guest > "$GUEST"
mkdir -p "$ROOT/run" "$ROOT/etc/emulationstation"
cfg="$ROOT/etc/emulationstation/es_systems.cfg"
cat > "$cfg" <<'CFG'
<systemList>
<system>
<name>ports</name>
<command>sudo perfmax %GOVERNOR% %ROM%; nice -n -19 %ROM%; sudo perfnorm</command>
</system>
<system>
<name>other</name>
<command>sudo perfmax %GOVERNOR% %ROM%; nice -n -19 %ROM%; sudo perfnorm</command>
</system>
</systemList>
CFG
cp "$cfg" "$tmp/original"
mounted_at() { [ -f "$tmp/bound" ]; }
bind_mount() { printf '%s %s\n' "$1" "$2" > "$tmp/bound"; }
repair_port_launch_boundary
cmp "$cfg" "$tmp/original" || fail 'persistent user config changed'
[ "$(grep -c '/run/andr36oid-port-launch %ROM%' "$ROOT/run/andr36oid-es-systems.cfg")" = 1 ] || fail 'port routing missing or changed other systems'
[ "$(grep -c '<command>sudo perfmax' "$ROOT/run/andr36oid-es-systems.cfg")" = 1 ] || fail 'unrelated command modified'
cmp "$GUEST" "$ROOT/run/andr36oid-port-helper"
grep -Fq -- '--clean-port-mounts /' "$ROOT/run/andr36oid-port-launch"
grep -Fq 'sleep 0.2' "$ROOT/run/andr36oid-port-launch"
before=$(cat "$tmp/bound")
repair_port_launch_boundary
[ "$(cat "$tmp/bound")" = "$before" ] || fail 'warm launch stacked config bind'
cmp "$cfg" "$tmp/original"
echo 'PASS: session-local prelaunch route, bounded busy retry, other systems preserved, warm idempotence, original config untouched'
