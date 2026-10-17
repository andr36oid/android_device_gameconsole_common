#!/bin/sh
# Uses fake package/property commands and a temporary state directory only.
# shellcheck disable=SC2034 # State variables are read by the sourced functions.
set -eu
EMU=${1:?emulators directory}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
getprop() { :; }
GAMECONSOLE_EMU_TEST=1
. "$EMU/seed/gameconsole-emu.sh"
STATE=$T/state
mkdir "$STATE"
log() { :; }
setprop() { printf '%s\t%s\n' "$1" "$2" >>"$T/properties"; }
pm() {
    if [ "${PM_FAIL:-0}" = 1 ]; then
        echo 'Failure [INSTALL_FAILED_TEST]'
        return 1
    fi
    echo Success
}

setup_progress apps
setup_installing game.apk
[ ! -e "$T/properties" ]
SETUP_PROGRESS_ENABLED=1
setup_progress cores:2:3
install_one '/fake/My Game.apk' example.game 1
[ -e "$STATE/installed.example.game" ]
[ ! -e "$STATE/failed.example.game" ]
PM_FAIL=1
install_one /fake/Broken.apk example.broken 2
[ "$(cat "$STATE/failed.example.broken")" = 2 ]
cat >"$T/expected" <<'EOF'
sys.andr36oid.setup_stage	cores:2:3
sys.andr36oid.setup_app	My Game
sys.andr36oid.setup_app	
sys.andr36oid.setup_app	Broken
sys.andr36oid.setup_app	
EOF
cmp "$T/expected" "$T/properties"
SETUP_PROGRESS_ENABLED=0
setup_progress library
setup_installing Another.apk
cmp "$T/expected" "$T/properties"
echo 'Progress codes, app names, failure cleanup and disabled updates passed'
