#!/bin/bash
# Regenerate the subsystem handoff splash from the andr36oid logo. The broker
# writes this raw to /dev/graphics/fb0 during the Android<->subsystem handoff so
# the screen shows the logo instead of going blank. The raw is a plain 32bpp
# BGRA framebuffer dump, sized stride x yres_virtual (triple-buffered), so it is
# per panel resolution. Run this when the logo or a shipped panel size changes.
# Needs ImageMagick. The broker only paints a raw whose byte size matches the
# panel's framebuffer, so shipping only the sizes you have is safe.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
logo=${LOGO:-$here/../../../Credits/res/drawable-nodpi/logo_andr36oid.png}
caption=${CAPTION:-andr36oid Subsystem}

# width height buffers  (fb is width x (height*buffers), stride = width*4)
for spec in "640 480 3"; do
  set -- $spec; w=$1; h=$2; bufs=$3
  out="$here/andr36oid-splash-${w}x${h}.raw"
  frame="$here/.frame.bgra"
  convert -size "${w}x${h}" xc:'#0b0b0f' \
    \( "$logo" -resize "$((w*56/100))x$((h*50/100))" \) -gravity center -geometry +0-20 -composite \
    -gravity center -fill '#9aa0b5' -pointsize "$((h/24))" -annotate "+0+$((h*31/100))" "$caption" \
    -depth 8 "bgra:$frame"
  : > "$out"
  i=0; while [ "$i" -lt "$bufs" ]; do cat "$frame" >> "$out"; i=$((i+1)); done
  rm -f "$frame"
  echo "wrote $out ($(stat -c %s "$out") bytes, ${w}x${h} x${bufs})"
done
