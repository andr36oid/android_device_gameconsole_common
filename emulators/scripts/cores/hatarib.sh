#!/usr/bin/env bash
set -euo pipefail
# This Makefile is a driver for zlib and Hatari's CMake build, not a normal
# TARGET-based libretro Makefile. Use its Android toolchain branch for both.
src=$(cd "$1" && pwd)
out=$(realpath -m "$2")
: "${NDK:?}" "${JOBS:=4}"
cd "$src"
make --no-print-directory -j"$JOBS" -f makefile.libretro \
	platform=android ANDROID_PLATFORM=android-arm64 NDK_ROOT="$NDK" \
	CFLAGS_EXTRA='-march=armv8-a -mtune=cortex-a35 -mno-outline-atomics' \
	MULTITHREAD="-j$JOBS" libretro
cp hatarib_libretro_android.so "$out"
