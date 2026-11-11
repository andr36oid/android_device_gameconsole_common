#!/bin/sh
set -eu
. "$(dirname "$0")/test-lib.inc"
broker=${broker%/*}/andr36oid-guest.sh
mkdir -p "$tmp/backlight/panel";echo 77 > "$tmp/backlight/panel/brightness"
sed "s@/sys/class/backlight/\*/brightness@$tmp/backlight/*/brightness@g" "$broker" > "$tmp/guest-source";broker="$tmp/guest-source"
load_functions prepare_guest_hardware
capture_guest_mixer() { :; }
GUEST=fixture_guest
fixture_guest() { case "$1" in --save-gamma) return 1;;--bind) echo "$2 $3" >> "$tmp/binds";;*) fail 'unexpected helper';;esac; }
mounted_at() { [ -f "$tmp/binds" ]; }
track_mount() { echo "$1" >> "$STATE/session.mounts"; }
prepare_guest_hardware
[ "$(wc -l < "$tmp/binds")" = 1 ] || fail 'first bind missing'
echo 92 > "$tmp/backlight/panel/brightness"
prepare_guest_hardware
[ "$(wc -l < "$tmp/binds")" = 1 ] || fail 'warm launch stacked a brightness bind'
grep -q ' 92$' "$STATE/session.brightness" || fail 'warm brightness snapshot reused old value'
echo 'PASS: warm brightness binding is idempotent; every launch still captures current Android brightness'
