#!/bin/sh
# Execute actual restore functions against stateful Android service fixtures.
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions wait_service_stopped wait_service_running restore_android_network restore_android finish_session
HWC=composer
stop_mute_guard() { :; }
kill_subsystem() { echo guest-reaped >> "$tmp/events"; }
show_splash() { :; }
prepare_retroarch_sync() { RA_SYNC_DIR=; }
sleep() { ticks=$((ticks+1)); }
ticks=0; broken=; absent=; fail_start=0
for service in netd wificond vendor.wifi_hal_legacy wpa_supplicant surfaceflinger; do
  echo running > "$tmp/$service"
done
getprop() {
  case "$1" in init.svc.*)
    service=${1#init.svc.}
    [ "$service" != "$absent" ] || return 0
    [ ! -f "$tmp/$service" ] || cat "$tmp/$service" ;;
  esac
}
start() {
  [ "$fail_start" = 0 ] || return 1
  [ "$(cat "$tmp/netd")" = running ] || fail 'framework raced netd'
  [ "$(cat "$tmp/wificond")" = running ] || fail 'framework raced wificond'
  [ "$(cat "$tmp/wpa_supplicant")" = stopped ] || fail 'old supplicant survived'
  [ "$(cat "$tmp/netd-state")" = fresh ] || fail 'old netIds survived'
  echo framework-start >> "$tmp/events"
}
# Model the defect: init start is a no-op for already-running netd, retaining
# its networks. Only an actual stop/start clears the old ConnectivityService IDs.
netd_setprop() {
  if [ "$*" = 'ctl.start netd' ] && [ "$(cat "$tmp/netd")" = stopped ]; then
    echo fresh > "$tmp/netd-state"
  fi
}
property_impl() {
  echo "$*" >> "$tmp/events"
  case "$1" in
    ctl.stop) [ "$2" = "$broken" ] || echo stopped > "$tmp/$2" ;;
    ctl.start) [ "$2" = "$broken" ] || echo running > "$tmp/$2" ;;
  esac
}
setprop() { netd_setprop "$@"; property_impl "$@"; }
echo stale > "$tmp/netd-state"
DID_HANDOFF=1
restore_android
[ "$CLEANUP_FAILED" = 0 ] || fail 'normal network restore failed'
cat > "$tmp/expected" <<'DATA'
ctl.stop wpa_supplicant
ctl.stop vendor.wifi_hal_legacy
ctl.stop wificond
ctl.stop netd
ctl.start netd
ctl.start wificond
ctl.start vendor.wifi_hal_legacy
framework-start
DATA
sed -n '/ctl.stop wpa_supplicant/,$p' "$tmp/events" > "$tmp/network-events"
cmp "$tmp/expected" "$tmp/network-events" || fail 'network reset ordering changed'
# Recovery retries after framework start fails must preserve the completed reset.
: > "$tmp/events"
restore_android
if grep -q 'ctl.stop' "$tmp/events"; then fail 'repeated recovery disconnected restored Wi-Fi'; fi
# Missing optional HAL/supplicant is allowed, missing core service is an error.
for absent in wpa_supplicant vendor.wifi_hal_legacy netd wificond; do
  rm -f "$STATE/session.network-restored"
  case "$absent" in
    netd|wificond)
      if restore_android_network; then fail "missing $absent reported success"; fi ;;
    *) restore_android_network || fail "optional $absent blocked recovery" ;;
  esac
done
absent=
# A stuck stop is bounded and prevents starting the new framework. Retry succeeds.
rm -f "$STATE/session.network-restored"
echo running > "$tmp/netd"; broken=netd; ticks=0
: > "$tmp/events"
restore_android
[ "$CLEANUP_FAILED" = 1 ] || fail 'stuck netd stop hidden'
[ "$ticks" -le 102 ] || fail 'network wait exceeded bound'
[ ! -f "$STATE/session.network-restored" ] || fail 'failed reset marked complete'
if grep -q framework-start "$tmp/events"; then fail 'framework started with stale netd'; fi
broken=
restore_android
[ "$CLEANUP_FAILED" = 0 ] || fail 'network reset retry failed'
# A network start failure also retains the pending reset, with no framework.
rm -f "$STATE/session.network-restored"
broken=wificond; echo stopped > "$tmp/wificond"; ticks=0
: > "$tmp/events"
restore_android
[ "$CLEANUP_FAILED" = 1 ] || fail 'stuck wificond start hidden'
if grep -q framework-start "$tmp/events"; then fail 'framework raced failed wificond'; fi
broken=
restore_android
[ "$CLEANUP_FAILED" = 0 ] || fail 'wificond retry failed'
# Failed guest EXIT uses the same restoration and preserves the guest status.
: > "$tmp/events"
if (finish_session 42); then fail 'guest failure became success'; else rc=$?; fi
[ "$rc" = 42 ] || fail 'guest status lost'
# No-handoff work must leave live Android networking entirely alone.
DID_HANDOFF=0; : > "$tmp/events"
restore_android
if grep -q 'ctl\.' "$tmp/events"; then fail 'no-handoff work touched Android services'; fi
echo 'PASS: Wi-Fi/netd reset precedes framework, clears stale netIds, is bounded and retryable, preserves repeated recovery and failed EXIT'
