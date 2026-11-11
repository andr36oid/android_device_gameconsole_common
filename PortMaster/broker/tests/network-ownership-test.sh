#!/bin/sh
# Handoff must preserve an existing Wi-Fi/TCP-adb session throughout guest use.
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions wait_service_stopped handoff prepare_network
HWC=composer
sleep() { :; }
show_splash() { :; }
stop_android_display() { echo display-stop >> "$tmp/events"; }
getprop() {
  case "$1" in
    ro.andr36oid.channel) echo "$channel" ;;
    init.svc.*)
      service=${1#init.svc.}
      if [ -f "$tmp/$service" ]; then cat "$tmp/$service"; else echo running; fi ;;
  esac
}
stop() { echo "stop $*" >> "$tmp/events"; echo stopped > "$tmp/$1"; }
start() { echo "start $*" >> "$tmp/events"; }
setprop() { echo "setprop $*" >> "$tmp/events"; [ "$1" != ctl.stop ] || echo stopped > "$tmp/$2"; }
for channel in release debug; do
  rm -f "$tmp/zygote" "$tmp/zygote_secondary" "$tmp/audioserver" "$tmp/iorapd" "$tmp/joyMouse"
  : > "$tmp/events"
  handoff
  if grep -Eq 'stop (adbd|netd|wificond|wpa_supplicant|vendor.wifi_hal_legacy)' "$tmp/events"; then
    fail 'handoff disconnected active Android Wi-Fi/TCP-adb'
  fi
  for service in zygote zygote_secondary audioserver iorapd joyMouse; do
    grep -qx "stop $service" "$tmp/events" || fail "handoff lost $service shutdown"
  done
done
# Network preparation supplies DNS/masks in the guest; it must not take an
# interface down, move namespaces, reset Android services or start a competitor.
ip() { fail 'guest preparation changed host interfaces/routes'; }
ifconfig() { fail 'guest preparation changed host interfaces'; }
iptables() { fail 'guest preparation changed host firewall'; }
timeout() { shift 3; dumpsys "$@"; }
dumpsys() { printf 'Active default network: 100\nnetwork{100} DnsAddresses: [ /192.0.2.53 ]\n'; }
bind_mount() { echo "bind $*" >> "$tmp/binds"; }
mkdir -p "$ROOT/etc/init.d"
: > "$ROOT/etc/init.d/networking"
: > "$tmp/events"
prepare_network
[ ! -s "$tmp/events" ] || fail 'guest preparation controlled Android services'
grep -qx 'nameserver 192.0.2.53' "$STATE/resolv.conf" || fail 'guest lost Android DNS'
for unit in NetworkManager systemd-networkd wpa_supplicant dhcpcd connman; do
  [ "$(readlink "$ROOT/etc/systemd/system/$unit.service")" = /dev/null ] || fail "guest $unit not masked"
done
grep -q 'Android owns networking' "$STATE/network-disabled" || fail 'guest init manager not disabled'
echo 'PASS: handoff preserves Android Wi-Fi/netd/wificond/adbd; guest shares the existing connection and DNS with competing managers masked'
