#!/system/bin/sh
#
# Seed a visible default for the R36 Ultra stick-ring "keros" LED, but only on a
# fresh unit that has never chosen one. Runs once at boot, after persisted props
# have loaded.
#
# Rules (must hold across reboots):
#   - Only acts when the keros LED actually exists (Ultra-class hardware); on any
#     other console it exits without touching the shared stickled property, so
#     the gpio joypad LED default elsewhere is left alone.
#   - Only seeds when persist.andr36oid.stickled.preset is UNSET. If the user has
#     chosen anything - including preset 0 ("off") - the saved value is kept and
#     never overridden here, so a user who disabled the lights stays dark every
#     boot.

KEROS=/sys/class/leds/keros::ambient/brightness

# No keros hardware: nothing to do.
[ -e "$KEROS" ] || exit 0

# Already chosen (by the user or a previous boot's seed): never override. Note an
# explicit "0" (off) is a real choice and is non-empty, so it is kept.
cur=$(getprop persist.andr36oid.stickled.preset)
[ -z "$cur" ] || exit 0

# Fresh unit: show a nice default. The property trigger in init.rk30board.rc
# writes this value straight to the keros brightness node; 9 is the rainbow preset.
setprop persist.andr36oid.stickled.preset 9

# Sleep behaviour default: "deepsleep" (rings stay on with the screen off, off when
# the SoC suspends). Only seeded when unset, so a user's choice is never overridden.
sleep=$(getprop persist.andr36oid.stickled.sleep)
[ -z "$sleep" ] && setprop persist.andr36oid.stickled.sleep deepsleep
