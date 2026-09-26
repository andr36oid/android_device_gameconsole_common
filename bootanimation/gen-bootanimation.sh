#!/bin/bash -e
#
# Copyright (C) 2016 The CyanogenMod Project
#               2017-2024 The LineageOS Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Based on vendor/lineage/bootanimation/gen-bootanimation.sh (lineage-23.2).
# The logo is scaled the same way, but every frame is padded to a full-screen
# canvas and overlay.png is composited at the bottom center. bootanimation
# clears everything outside the frame area, so the overlay has to be part of
# each frame to stay visible.

PARAM_OUT=$1
PARAM_GENDIR=$2
PARAM_BOOTANIMATION_TAR=$3
PARAM_DESC_TXT=$4
PARAM_OVERLAY_PNG=$5
PARAM_MOGRIFY=$6
PARAM_SOONG_ZIP=$7
PARAM_TARGET_SCREEN_HEIGHT=$8
PARAM_TARGET_SCREEN_WIDTH=$9

INTERMEDIATES=$PARAM_GENDIR/intermediates

rm -rf $INTERMEDIATES
mkdir -p $INTERMEDIATES

tar xfp $PARAM_BOOTANIMATION_TAR -C $INTERMEDIATES

# All supported consoles run in landscape (panels mounted in portrait are
# rotated by SurfaceFlinger), so the canvas is always long side x short side.
if [ $PARAM_TARGET_SCREEN_HEIGHT -lt $PARAM_TARGET_SCREEN_WIDTH ]; then
    SHORTSIDE=$PARAM_TARGET_SCREEN_HEIGHT
    LONGSIDE=$PARAM_TARGET_SCREEN_WIDTH
else
    SHORTSIDE=$PARAM_TARGET_SCREEN_WIDTH
    LONGSIDE=$PARAM_TARGET_SCREEN_HEIGHT
fi

IMAGEWIDTH=$SHORTSIDE
IMAGEHEIGHT=$(expr $IMAGEWIDTH / 3)

# overlay.png is rendered for a 480px tall screen at 4x
OVERLAY=$PARAM_GENDIR/overlay.png
cp $PARAM_OVERLAY_PNG $OVERLAY
$PARAM_MOGRIFY -resize $(expr $SHORTSIDE \* 100 / 1920)% $OVERLAY
MARGIN=$(expr $SHORTSIDE / 80)

$PARAM_MOGRIFY -resize "$IMAGEWIDTH"x"$IMAGEHEIGHT" \
    -background black -gravity center -extent "$LONGSIDE"x"$SHORTSIDE" \
    -gravity south -draw "image over 0,$MARGIN 0,0 '$OVERLAY'" \
    -colors 256 $INTERMEDIATES/*/*.png

echo "$LONGSIDE $SHORTSIDE 60" > $INTERMEDIATES/desc.txt
cat $PARAM_DESC_TXT >> $INTERMEDIATES/desc.txt

rm -f $PARAM_OUT
$PARAM_SOONG_ZIP -L 0 -o $PARAM_OUT -C $INTERMEDIATES -D $INTERMEDIATES
