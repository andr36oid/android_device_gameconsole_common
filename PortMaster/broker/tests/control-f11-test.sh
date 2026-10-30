#!/bin/sh
. "$(dirname "$0")/control-test-lib.inc"
for storage in roms roms2; do
  for path in tools/PortMaster/batocera/gamecontrollerdb.txt tools/PortMaster/knulli/gamecontrollerdb.txt tools/PortMaster/gamecontrollerdb.txt.bak other/deep/keep/gamecontrollerdb.txt; do
    cfg="$ROOT/$storage/$path"; mkdir -p "$(dirname "$cfg")"
    printf 'other,Other Pad,a:b1,\n190000004b4800000011000000010000,GO-Super Gamepad,start:b13,\n' > "$cfg"
  done
done
prime_storage_input
for storage in roms roms2; do
  for path in tools/PortMaster/batocera/gamecontrollerdb.txt tools/PortMaster/knulli/gamecontrollerdb.txt tools/PortMaster/gamecontrollerdb.txt.bak; do
    cfg="$ROOT/$storage/$path"
    for guid in $SDL_PAD_GUIDS; do exact "$cfg" "$guid,$SDL_PAD_BINDS"; done
    exact "$cfg" 'other,Other Pad,a:b1,'
  done
  exact "$ROOT/$storage/other/deep/keep/gamecontrollerdb.txt" '190000004b4800000011000000010000,GO-Super Gamepad,start:b13,'
done
repeat prime_storage_input
echo 'F11 explicit storage database paths passed'
