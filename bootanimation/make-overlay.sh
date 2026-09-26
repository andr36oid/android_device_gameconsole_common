#!/bin/bash -e
#
# Renders overlay.png, the text drawn at the bottom of the boot animation.
# Needs ImageMagick with FreeType (the prebuilt mogrify used at build time
# can't render fonts). Run from the top of the source tree, then commit the
# result:
#
#   device/gameconsole/common/bootanimation/make-overlay.sh "andr36oid - Android 11 - v2026-09-06"
#
# The text is rendered for a 480px tall screen at 4x and scaled per device
# by gen-bootanimation.sh.

TEXT=${1:?usage: $0 "<text>"}
DIR=$(dirname "$0")
FONT=${FONT:-external/roboto-fonts/Roboto-Regular.ttf}
COLOR=${COLOR:-#4a4a4a}

convert -background none -fill "$COLOR" -font "$FONT" -pointsize 44 \
    label:"$TEXT" -trim +repage -define png:exclude-chunks=date,time \
    "$DIR/overlay.png"
