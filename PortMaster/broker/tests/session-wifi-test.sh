#!/bin/sh
# Exercise the host-side wake policy without modifying host networking.
set -eu
. "$(dirname "$0")/test-lib.inc"
sed "s@/sys/class/net/wlan0/carrier@$tmp/carrier@g; s@/proc/uptime@$tmp/uptime@g" "$broker" > "$tmp/broker"
broker="$tmp/broker"
load_functions session_wifi_owns_link session_wifi_status session_wifi_addresses session_wifi_routes \
  session_wifi_recovery_event session_wifi_gateway_alive snapshot_session_wifi restore_session_wifi_ipv4 \
  session_wifi_recovered wait_session_wifi resume_session_wifi
DID_HANDOFF=1
GUEST=wpa_mock
bssid=aa:bb:cc:dd:ee:ff
address=192.168.69.10/24
connected='192.168.69.0/24 dev wlan0 table 1016 proto static scope link'
default='default via 192.168.69.1 dev wlan0 table 1016 proto static'
: > "$STATE/session.handoff"
getprop() {
  case "$1" in
    init.svc.zygote) cat "$tmp/zygote" ;;
    init.svc.wpa_supplicant) printf 'running\n' ;;
  esac
}
dmesg() { cat "$tmp/dmesg"; }
ping() { [ "$(cat "$tmp/gateway")" = alive ]; }
timeout() { shift 3; "$@"; }
sleep() {
  read -r time rest < "$tmp/uptime"
  printf '%s.0 0.0\n' "$((${time%%.*}+1))" > "$tmp/uptime"
}
wpa_mock() {
  [ "$1" = --session-wifi ] || fail 'wrong control helper mode'
  case "$2" in
    status)
      printf 'wpa_state=COMPLETED\nid=4\nbssid=%s\n' "$(cat "$tmp/bssid")" ;;
    reassociate)
      echo reassociate >> "$tmp/events"
      case "$(cat "$tmp/scenario")" in
        reassociate) echo 1 > "$tmp/carrier"; echo alive > "$tmp/gateway" ;;
        framework) echo running > "$tmp/zygote" ;;
      esac ;;
    *) fail 'unexpected supplicant command' ;;
  esac
}
ip() {
  case "$*" in
    '-o -4 addr show dev wlan0 scope global')
      [ ! -s "$tmp/address" ] || printf '5: wlan0 inet %s scope global wlan0\n' "$(cat "$tmp/address")" ;;
    '-o -4 route show table all dev wlan0') cat "$tmp/routes" ;;
    '-4 addr add '*)
      echo "$*" >> "$tmp/events"; echo "$4" > "$tmp/address" ;;
    '-4 route add '*)
      echo "$*" >> "$tmp/events"; shift 3; echo "$*" >> "$tmp/routes" ;;
    'link set wlan0 down')
      echo "$*" >> "$tmp/events"; echo 0 > "$tmp/carrier" ;;
    'link set wlan0 up')
      echo "$*" >> "$tmp/events"
      if [ "$(cat "$tmp/scenario")" != always-down ]; then
        echo 1 > "$tmp/carrier"; echo alive > "$tmp/gateway"
      fi ;;
    *) fail "unexpected ip command $*" ;;
  esac
}
snapshot() {
  wifi_id= wifi_bssid= wifi_seen_id= wifi_seen_bssid= wifi_addresses= wifi_routes=
  wifi_gateway= wifi_probe=0 wifi_recovery=
  echo "$bssid" > "$tmp/bssid"
  echo 1 > "$tmp/carrier"
  echo stopped > "$tmp/zygote"
  echo "$address" > "$tmp/address"
  printf '%s\n' "$default" "$connected" > "$tmp/routes"
  printf 'nameserver 192.168.69.1\n' > "$STATE/resolv.conf"
  echo alive > "$tmp/gateway"
  echo '100.0 0.0' > "$tmp/uptime"
  : > "$tmp/dmesg"; : > "$tmp/events"; : > "$tmp/scenario"; : > "$LOG"
  snapshot_session_wifi
  [ "$wifi_id" = 4 ] && [ "$wifi_probe" = 1 ] || fail 'connected Wi-Fi snapshot was missed'
}

snapshot
resume_session_wifi
[ ! -s "$tmp/events" ] || fail 'healthy link was disturbed'

snapshot
echo reassociate > "$tmp/scenario"
echo 0 > "$tmp/carrier"; echo dead > "$tmp/gateway"
: > "$tmp/address"; : > "$tmp/routes"
resume_session_wifi
cat > "$tmp/expected" <<EXPECTED
reassociate
-4 addr add $address dev wlan0
-4 route add $connected
-4 route add $default
EXPECTED
cmp "$tmp/expected" "$tmp/events" || fail 'same-link IPv4 replay did not preserve route tables and order'
[ "$(cat "$STATE/resolv.conf")" = 'nameserver 192.168.69.1' ] || fail 'guest DNS was replaced'
grep -q 'Wi-Fi reassociated' "$LOG" || fail 'reassociation result omitted'

snapshot
wifi_probe=0
echo '[ 42.2] RK915: ERROR: -------- fw error recovery end --------' > "$tmp/dmesg"
resume_session_wifi
[ "$(cat "$tmp/events")" = reassociate ] || fail 'firmware reload did not refresh apparently healthy link'

snapshot
echo 0 > "$tmp/carrier"; echo dead > "$tmp/gateway"
resume_session_wifi
cat > "$tmp/expected" <<'EXPECTED'
reassociate
link set wlan0 down
link set wlan0 up
reassociate
EXPECTED
cmp "$tmp/expected" "$tmp/events" || fail 'interface fallback failed or restarted another service'
grep -q 'Wi-Fi recovered after interface reset' "$LOG" || fail 'interface fallback result omitted'

snapshot
echo always-down > "$tmp/scenario"
echo 0 > "$tmp/carrier"; echo dead > "$tmp/gateway"
resume_session_wifi
cmp "$tmp/expected" "$tmp/events" || fail 'unreachable link retried more than one reset'
grep -q 'Wi-Fi recovery timed out' "$LOG" || fail 'unreachable link was reported as recovered'

snapshot
echo framework > "$tmp/scenario"
echo 0 > "$tmp/carrier"
resume_session_wifi
[ "$(cat "$tmp/events")" = reassociate ] || fail 'broker reset Wi-Fi after Android started'
snapshot
echo running > "$tmp/zygote"
resume_session_wifi
[ ! -s "$tmp/events" ] || fail 'broker touched Wi-Fi under running Android'

snapshot
echo 11:22:33:44:55:66 > "$tmp/bssid"
: > "$tmp/address"; : > "$tmp/routes"
resume_session_wifi
[ ! -s "$tmp/events" ] || fail 'roamed link received old IPv4 state'
grep -q 'different link' "$LOG" || fail 'roam decision omitted'

snapshot
echo 192.168.69.99/24 > "$tmp/address"
resume_session_wifi
[ ! -s "$tmp/events" ] || fail 'existing new address was overwritten'

snapshot
wifi_id=
resume_session_wifi
[ ! -s "$tmp/events" ] || fail 'an offline pre-sleep link was brought up'
echo 'PASS: healthy and offline link, firmware reload, same-link IPv4/DNS, fallback, roam, and framework ownership'
