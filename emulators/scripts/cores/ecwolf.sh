#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/ndk-make.sh"
# Bionic has no separate libpthread. Keep the core's built-in libretro data and
# software renderer; do not build the standalone SDL frontend.
build_ndk_make "$1" "$2" Makefile LIBS=-ldl
