#!/bin/sh
set -eu
A36_TEST_BROKER=$(CDPATH='' cd -- "$(dirname "$0")/.." && pwd)/andr36oid-guest.sh
. "$(dirname "$0")/test-lib.inc"
load_functions capture_guest_mixer restore_guest_mixer
mock_tinymix() {
  shift 2
  if [ "$1" = -v ]; then
    case "$2" in
      'Playback Path') echo SPK_HP;;
      'Master Playback Volume') echo '192 191';;
      'Playback Volume') echo '220 219';;
      'Playback Hardware Master Switch') echo On;;
      *) return 1;;
    esac
  else printf '%s\n' "$*" >> "$tmp/restored"; fi
}
capture_guest_mixer
restore_guest_mixer
for expected in 'Playback Path SPK_HP' 'Master Playback Volume 192 191' 'Playback Volume 220 219' 'Playback Hardware Master Switch 1'; do
  grep -Fqx "$expected" "$tmp/restored" || fail "lost mixer state: $expected"
done
[ ! -f "$STATE/session.mixer" ] || fail 'snapshot not consumed'
echo 'PASS: mixer restore retains Android route, distinct stereo volumes and hardware-master state'
