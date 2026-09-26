#!/bin/bash -e
#
# Renders overlay.png, the text drawn at the bottom of the boot animation.
# Needs ImageMagick with FreeType (the prebuilt mogrify used at build time
# can't render fonts). The build runs it with the version from version.sh
# when ImageMagick is installed; the committed overlay.png is the fallback,
# renew it from the top of the source tree with:
#
#   device/gameconsole/common/bootanimation/make-overlay.sh "andr36oid - Android 11"
#
# An output path can be given as second argument, CONVERT sets the convert
# binary.
# The text is rendered for a 480px tall screen at 4x and scaled per device
# by gen-bootanimation.sh.

TEXT=${1:?usage: $0 "<text>" [output.png]}
DIR=$(dirname "$0")
OUT=${2:-$DIR/overlay.png}
CONVERT=${CONVERT:-convert}
FONT=${FONT:-external/roboto-fonts/Roboto-Regular.ttf}
COLOR=${COLOR:-#4a4a4a}

"$CONVERT" -background none -fill "$COLOR" -font "$FONT" -pointsize 44 \
    label:"$TEXT" -trim +repage -define png:exclude-chunks=date,time \
    "$OUT"
