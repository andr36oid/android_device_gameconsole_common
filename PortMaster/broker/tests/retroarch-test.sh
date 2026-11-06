#!/bin/bash
# Host regression coverage for Daijishou's one-game Linux RetroArch session.
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions root_path root_file retroarch_error read_retroarch_request map_retroarch_rom resolve_retroarch_target resolve_run_target run_session
getprop() { [ "$1" != persist.andr36oid.subsystem.daijishou_cores ] || printf '%s\n' "${enabled:-1}"; }
A36_RA_REQUESTS="$tmp/requests"; mkdir -p "$A36_RA_REQUESTS"
id=0123456789abcdef0123456789abcdef
RA_REQUEST=$id
rom='/storage/AAAA-BBBB/nes/A very long directory name exceeding the Android property limit/Player'
rom="$rom's Game 日本語; \$(touch NEVER).nes"
printf 'nestopia\n%s\n' "$rom" > "$A36_RA_REQUESTS/ra-$id.request"
read_retroarch_request "$id" || fail 'valid long request rejected'
[ "$RA_CORE" = nestopia ] && [ "$RA_ROM" = "$rom" ] || fail 'request changed characters'
[ ! -e "$A36_RA_REQUESTS/ra-$id.request" ] || fail 'request not consumed'
if read_retroarch_request '../bad'; then fail 'invalid token accepted'; fi
printf 'nestopia\n%s\nextra\n' "$rom" > "$A36_RA_REQUESTS/ra-$id.request"
if read_retroarch_request "$id"; then fail 'extra request line accepted'; fi
printf 'nestopia\n%s\n' "$rom" > "$A36_RA_REQUESTS/ra-$id.request"
enabled=0
if read_retroarch_request "$id"; then fail 'default-off gate bypassed'; fi
enabled=1
printf 'nestopia\n%s\n' "$rom" > "$A36_RA_REQUESTS/ra-$id.request"
read_retroarch_request "$id"
rel=${rom#/storage/AAAA-BBBB/}
mkdir -p "$ROOT/roms/$(dirname "$rel")" "$ROOT/roms2/gb" "$ROOT/home/ark/.config/retroarch/cores" "$ROOT/opt/retroarch/bin"
: > "$ROOT/roms/$rel"; : > "$ROOT/roms2/gb/Other.gb"
: > "$ROOT/opt/retroarch/bin/retroarch"
printf '/mnt/media_rw/AAAA-BBBB\t/roms\n/mnt/media_rw/CCCC-DDDD\t/roms2\n' > "$STATE/roms-map"
arg=/opt/retroarch/bin/retroarch
if resolve_run_target; then fail 'missing Linux core accepted'; fi
grep -q 'core is missing' "$tmp/properties" || fail 'no visible missing-core diagnostic'
: > "$ROOT/home/ark/.config/retroarch/cores/nestopia_libretro.so"
# An Android .so is never a candidate.
RA_CORE=androidonly
: > "$ROOT/home/ark/.config/retroarch/cores/androidonly_libretro_android.so"
if resolve_run_target; then fail 'Android core used as Linux core'; fi
RA_CORE=nestopia
resolve_run_target || fail 'Linux core and shared ROM rejected'
[ "$RA_ROM" = "/roms/$rel" ] || fail 'primary card mapping'
[ "$RA_LIBRARY" = /home/ark/.config/retroarch/cores/nestopia_libretro.so ] || fail 'wrong core directory'
run_session_payload() { "$@"; }
run_in_subsystem() {
  [ "$#" = 4 ] && [ "$1" = "$arg" ] && [ "$2" = -L ] && [ "$3" = "$RA_LIBRARY" ] && [ "$4" = "$RA_ROM" ] || fail 'argv splitting/injection'
  return 17
}
SESSION_OUT="$tmp/session.out"; cmd=run
set +e
run_session; rc=$?
set -e
[ "$rc" = 17 ] || fail 'guest exit status lost'
for path in /storage/CCCC-DDDD/gb/Other.gb /mnt/media_rw/CCCC-DDDD/gb/Other.gb /roms2/gb/Other.gb; do
  RA_ROM="$path"; map_retroarch_rom || fail 'secondary mapping rejected'
  [ "$RA_ROM" = /roms2/gb/Other.gb ] || fail 'secondary mapping changed'
done
for path in /storage/emulated/0/gb/Other.gb /storage/AAAA-BBBB/../secret /storage/AAAA-BBBB/nes/Missing.nes; do
  RA_ROM="$path"
  if map_retroarch_rom; then fail "unshared/missing ROM accepted: $path"; fi
done
ln -s /opt/retroarch/bin/retroarch "$ROOT/roms/escape.nes"
RA_ROM=/storage/AAAA-BBBB/escape.nes
if map_retroarch_rom; then fail 'symlink escape accepted'; fi
printf '/data/media/0\t/roms\n' > "$STATE/roms-map"
RA_ROM="/storage/emulated/0/$rel"
map_retroarch_rom || fail 'internal storage fallback mapping rejected'
[ "$RA_ROM" = "/roms/$rel" ] || fail 'internal storage mapping changed'
# Alternate Linux core install location, including guest-absolute symlinks.
mkdir -p "$ROOT/opt/retroarch/cores"
mv "$ROOT/home/ark/.config/retroarch/cores/nestopia_libretro.so" "$ROOT/opt/retroarch/cores/nestopia_libretro.so"
RA_ROM="/storage/emulated/0/$rel"
resolve_run_target || fail 'opt Linux core fallback rejected'
[ "$RA_LIBRARY" = /opt/retroarch/cores/nestopia_libretro.so ] || fail 'opt core path changed'
echo 'RetroArch request, mapping, core preflight, argv and exit tests passed'
