#!/usr/bin/env bash
# Shared by cores which have a portable libretro Makefile but no JNI recipe.
# Source this file, then call build_ndk_make <src> <out> <makefile> [make vars...].
# Do not replace CFLAGS/CXXFLAGS: the core adds its required defines/includes there.
build_ndk_make() {
	local src=$1 out=$2 makefile=$3
	shift 3
	: "${NDK:?NDK must point to Android NDK r27c}"
	local tc=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
	local flags='-march=armv8-a -mtune=cortex-a35 -mno-outline-atomics'
	[ -x "$tc/aarch64-linux-android21-clang" ] || return 1
	[ -f "$src/$makefile" ] || return 1
	# The Unix platform selects portable POSIX code. These are Android compilers,
	# not Linux/glibc compilers. Bionic provides pthread functions in libc.
	make --no-print-directory -j"${JOBS:-4}" -C "$src" -f "$makefile" \
		platform=unix ARCH=aarch64 arch=aarch64 EXT=so \
		"CC=$tc/aarch64-linux-android21-clang $flags" \
		"CXX=$tc/aarch64-linux-android21-clang++ $flags -static-libstdc++" \
		"LD=$tc/aarch64-linux-android21-clang++ $flags -static-libstdc++" \
		"AR=$tc/llvm-ar" "RANLIB=$tc/llvm-ranlib" \
		"TARGET=$out" "$@" "$out"
	[ -s "$out" ]
}
