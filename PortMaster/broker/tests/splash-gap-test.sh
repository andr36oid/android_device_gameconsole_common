#!/bin/sh
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions scanout_splash_enabled show_splash start_return_splash finish_return_splash
SPLASH="$tmp/splash";: > "$SPLASH";FB=/dev/null;GUEST="$tmp/guest";mode=0
getprop() { case "$1" in persist.andr36oid.subsystem.kms_splash) printf '%s' "$mode";; ro.surface_flinger.primary_display_orientation) echo ORIENTATION_90;; esac; }
MOCK_STATE="$tmp";export MOCK_STATE
cat > "$GUEST" <<'GUEST'
#!/bin/sh
printf '%s\n' "$$" > "$MOCK_STATE/helper.pid"
printf '%s\n' "$*" >> "$MOCK_STATE/calls"
[ ! -e /proc/self/fd/7 ] && [ ! -e /proc/self/fd/9 ] || exit 70
while [ ! -f "$MOCK_STATE/release" ]; do sleep 0.02; done
GUEST
chmod 0755 "$GUEST"
start_return_splash
[ -z "$RETURN_SPLASH_PID" ] && [ ! -e "$tmp/calls" ] || fail 'explicit opt-out spawned display worker'
# An unset property is the shipping ON default.
mode=
exec 7>"$tmp/storage.lock" 9>"$tmp/operation.lock"
start_return_splash
wait_until test -s "$tmp/helper.pid"
[ "$(cat "$tmp/helper.pid")" = "$RETURN_SPLASH_PID" ] || fail 'display helper is hidden behind an unreaped shell'
touch "$tmp/release"
wait_until not_alive "$RETURN_SPLASH_PID"
finish_return_splash
[ "$RETURN_SPLASH_OK" = 1 ] || fail 'successful scanout not collected'
show_splash return
[ "$(wc -l < "$tmp/calls")" = 1 ] || fail 'return painted again after successful gap worker'
grep -q -- "--splash-scanout $SPLASH $FB 90 /dev/dri/card0 10000 $STATE/session.brightness" "$tmp/calls" || fail 'rotation/wait/snapshot arguments lost'
rm "$tmp/helper.pid" "$tmp/release"
# Explicit ON takes the same path; cancellation still reaps its own helper.
mode=1
start_return_splash
wait_until test -s "$tmp/helper.pid"
pid=$RETURN_SPLASH_PID
finish_return_splash
[ "$RETURN_SPLASH_OK" = 0 ] && not_alive "$pid" || fail 'cancelled gap helper survived into composer startup'
echo 'PASS: default-on and explicit-off gap gate, actual helper PID, operation descriptors closed, rotation preserved, no duplicate paint and cancellation before composer'
