#!/bin/sh
# SPDX-License-Identifier: MIT
set -eu
HERE=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
SRC_ROOT=${SRC_ROOT:-/andr36oid/src}
TOOLCHAIN=${TOOLCHAIN:-/andr36oid/buildenv/rootfs/opt/toolchains/gcc-linaro-6.3.1-2017.05-x86_64_aarch64-linux-gnu}
PROBE_CC=${PROBE_CC:-$TOOLCHAIN/bin/aarch64-linux-gnu-gcc}
OUT=${OUT:-/andr36oid/work/reports/portmaster/prototype/artifacts/kms-probe-aarch64}
DRM_HEADERS=$SRC_ROOT/kernel/gameconsole/r36s/include/uapi/drm
test -r "$DRM_HEADERS/drm.h"
test -r "$DRM_HEADERS/drm_mode.h"
mkdir -p -- "$(dirname -- "$OUT")"
"$PROBE_CC" -std=c99 -Os -static -s -Wall -Wextra -Werror -Wformat=2 \
    -D_FILE_OFFSET_BITS=64 -I"$DRM_HEADERS" "$HERE/kms-probe.c" -o "$OUT"
printf 'Built %s\n' "$OUT"
