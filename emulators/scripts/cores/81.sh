#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/ndk-make.sh"
# EightyOne's legacy C++ uses register, removed in the NDK's default C++17.
build_ndk_make "$1" "$2" Makefile.libretro \
	"CXX=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang++ -march=armv8-a -mtune=cortex-a35 -mno-outline-atomics -static-libstdc++ -std=gnu++14"
