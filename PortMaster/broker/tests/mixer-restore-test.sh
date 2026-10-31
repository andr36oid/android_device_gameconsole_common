#!/bin/sh
set -eu
A36_TEST_BROKER=$(CDPATH='' cd -- "$(dirname "$0")/.." && pwd)/andr36oid-guest.sh
. "$(dirname "$0")/test-lib.inc"
load_functions capture_guest_mixer restore_guest_mixer restore_guest_hardware
restore_fail=0;capture_fail=0
mock_tinymix() {
  shift 2
  if [ "$1" = -v ]; then
    [ "$capture_fail" = 0 ] || [ "$2" != 'Playback Volume' ] || return 1
    case "$2" in
      'Playback Path') echo SPK_HP;;
      'Master Playback Volume') echo '192 191';;
      'Playback Volume') echo '220 219';;
      'Playback Hardware Master Switch') echo On;;
      *) return 1;;
    esac
  else
    [ "$restore_fail" = 0 ] || return 1
    printf '%s\n' "$*" >> "$tmp/restored"
  fi
}
GUEST=capture_helper
capture_helper() {
  [ "$1" = --capture-mixer ] || return 1
  for control in 'Playback Path' 'Master Playback Volume' 'Playback Volume' 'Playback Hardware Master Switch'; do
    value=$(mock_tinymix -D 0 -v "$control") || return 1
    printf '%s\t%s\n' "$control" "$value"
  done
}
capture_guest_mixer
cp "$STATE/session.mixer" "$tmp/original-mixer"
capture_fail=1
! capture_guest_mixer || fail 'partial capture reported success'
cmp "$tmp/original-mixer" "$STATE/session.mixer" || fail 'partial capture replaced snapshot'
[ ! -f "$STATE/session.mixer.tmp" ] || fail 'partial capture left staging file'
capture_fail=0
restore_guest_mixer
for expected in 'Playback Path SPK_HP' 'Master Playback Volume 192 191' 'Playback Volume 220 219' 'Playback Hardware Master Switch 1'; do
  grep -Fqx "$expected" "$tmp/restored" || fail "lost mixer state: $expected"
done
[ ! -f "$STATE/session.mixer" ] || fail 'snapshot not consumed'
capture_guest_mixer
restore_fail=1
! restore_guest_mixer || fail 'mixer write failure reported success'
[ -s "$STATE/session.mixer" ] || fail 'failed mixer restore discarded snapshot'
GUEST=mock_guest;DID_HANDOFF=1
mock_guest() { [ "$restore_fail" = 0 ]; }
touch "$STATE/session.gamma"
! restore_guest_hardware || fail 'hardware write failure reported success'
[ -f "$STATE/session.gamma" ] || fail 'failed gamma restore discarded snapshot'
[ -s "$STATE/session.mixer" ] || fail 'failed hardware restore discarded mixer snapshot'
restore_fail=0
restore_guest_hardware
[ ! -f "$STATE/session.gamma" ] || fail 'gamma retry did not consume snapshot'
[ ! -f "$STATE/session.mixer" ] || fail 'mixer retry did not consume snapshot'
echo 'PASS: mixer restore retains Android route, distinct stereo volumes and hardware-master state'
