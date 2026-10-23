#!/bin/sh
set -eu
DEST=${1:-/andr36oid/work/reports/flipper/fw}
SCRATCH=${FLIPPER_SCRATCH:?Set FLIPPER_SCRATCH to an authorized temporary directory}
mkdir -p "$DEST" "$SCRATCH"
TEMP=$(mktemp "$SCRATCH/flipper-firmware.XXXXXX")
trap 'rm -f "$TEMP"' EXIT HUP INT TERM
NAME=flipper-z-f7-full-1.5.1.bin
URL=https://update.flipperzero.one/builds/firmware/1.5.1/$NAME
SHA=5fcf348c1216d29c63a27f906996076e9f3a43c8cb61a5e21fe1ff929e50631f
curl -fL --connect-timeout 15 --max-time 120 "$URL" -o "$TEMP"
printf '%s  %s\n' "$SHA" "$TEMP" | sha256sum -c -
cp "$TEMP" "$DEST/$NAME"
printf 'Official f7 firmware 1.5.1 (GPL-3.0), SHA256 %s\n%s\n' "$SHA" "$DEST/$NAME"
