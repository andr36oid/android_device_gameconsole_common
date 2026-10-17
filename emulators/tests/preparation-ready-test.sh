#!/bin/sh
# Exercise the real completion functions in both possible job orders.
# shellcheck disable=SC2034 # Variables are read by the sourced script.
set -eu
EMU=${1:?emulators directory}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
getprop() { :; }
GAMECONSOLE_EMU_TEST=1
. "$EMU/seed/gameconsole-emu.sh"
log() { :; }
settings() {
    [ "$*" = 'put global andr36oid_daijishou_ready 1' ] || return 99
    touch "$T/ready"
}
apps_wait() { [ "$WAIT" = 1 ]; }
reset_case() {
    rm -rf "$T/state" "$T/apps" "$T/metadata" "$T/ready"
    STATE=$T/state
    APPS=$T/apps
    APPS_DONE=$T/metadata/apps-done
    mkdir "$STATE" "$APPS"
    touch "$APPS/Example.apk"
    WAIT=0 APPS_LEFT='' FIRSTRUN_LEFT=''
}

# Library finishes while the wizard's app choice and install are still pending.
reset_case
WAIT=1
daijishou_ready
apps_done
[ ! -e "$T/ready" ]
WAIT=0 APPS_LEFT=1
apps_done
[ ! -e "$T/ready" ]
APPS_LEFT='' FIRSTRUN_LEFT=1
apps_done
[ ! -e "$T/ready" ]
FIRSTRUN_LEFT=''
apps_done
[ -e "$T/ready" ]

# Apps finish before core/library provisioning.
reset_case
apps_done
[ -e "$APPS_DONE" ]
[ ! -e "$T/ready" ]
daijishou_ready
[ -e "$T/ready" ]

# Retrying with the existing apps-done marker still announces completion.
rm "$T/ready"
apps_done
[ -e "$T/ready" ]

# Empty or already reclaimed AUTOAPK does not leave the guide waiting.
reset_case
rm "$APPS/Example.apk"
daijishou_ready
[ -e "$T/ready" ]
rm -r "$APPS" "$T/ready"
daijishou_ready
[ -e "$T/ready" ]
echo 'Preparation waits for library and app settings in either completion order'
