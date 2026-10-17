#!/bin/sh
# Exercises real first-run parsing with temporary app data and a fake notification service.
# shellcheck disable=SC2034
set -eu
EMU=${1:?emulators directory}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
getprop() { :; }
GAMECONSOLE_EMU_TEST=1
# shellcheck source=/dev/null
. "$EMU/seed/gameconsole-emu.sh"
STATE=$T/state
APP_DATA=$T/data
MEDIA=$T/media
mkdir -p "$STATE" "$APP_DATA/com.looker.droidify"
log() { :; }
appops() { printf '%s\n' "$*" >>"$T/appops"; }
CMD_FAIL=0
cmd() {
    [ "$*" = 'notification set_notifications_enabled com.looker.droidify false 0' ] || return 99
    printf '%s\n' "$*" >>"$T/calls"
    [ "$CMD_FAIL" = 0 ] || return 1
    echo false >"$T/enabled"
}
list=$EMU/../foss-apps/Droidify/firstrun/droidify.list
stamp=$STATE/notifications.com.looker.droidify
firstrun_list "$list"
[ -f "$stamp" ]
[ "$(cat "$T/enabled")" = false ]
[ "$(wc -l <"$T/calls")" -eq 1 ]

# A later change in Settings survives every subsequent seed pass.
echo true >"$T/enabled"
firstrun_list "$list"
[ "$(cat "$T/enabled")" = true ]
[ "$(wc -l <"$T/calls")" -eq 1 ]
[ "$(wc -l <"$T/appops")" -eq 1 ]

# A failed service call must not mark the default applied; the next pass retries.
rm "$stamp"
CMD_FAIL=1
firstrun_list "$list"
[ ! -f "$stamp" ]
[ "$FIRSTRUN_LEFT" = 1 ]
[ "$(cat "$T/enabled")" = true ]
CMD_FAIL=0
FIRSTRUN_LEFT=
firstrun_list "$list"
[ -f "$stamp" ]
[ "$(cat "$T/enabled")" = false ]
[ "$(wc -l <"$T/calls")" -eq 3 ]

# An invalid default never calls the service or records success.
rm "$stamp"
printf '%s\n' 'package com.looker.droidify' 'notifications maybe' >"$T/invalid.list"
firstrun_list "$T/invalid.list"
[ ! -f "$stamp" ]
[ "$FIRSTRUN_LEFT" = 1 ]
[ "$(wc -l <"$T/calls")" -eq 3 ]
echo 'Notification default, later user choice and failure retry passed'
