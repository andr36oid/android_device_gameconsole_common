#!/usr/bin/env bash
#
# Builds the EasyRPG libretro core (RPG Maker 2000/2003) for arm64-v8a the way
# libretro's own CI does it (.gitlab-ci.yml in the source: deps:android, then
# the android-arm64-v8a-libretro-release CMake preset), but for arm64-v8a only
# and with our NDK. The libraries it links in (zlib, libpng, freetype,
# harfbuzz, pixman, expat, ogg/vorbis, mpg123, libsndfile, libxmp-lite,
# speexdsp, wildmidi, opus, fmt, ICU, ...) are the versions pinned in the
# builds/libretro/buildscripts submodule (shared/packages.ini).
#
# Called by build-cores.sh:  easyrpg.sh <source dir> <output .so>
# Needs from the environment: NDK, JOBS; host tools: cmake, ninja, meson,
# autotools, perl, python3, curl.

set -euo pipefail

src=$(cd "$1" && pwd) out=$(realpath -m "$2")
BS=$src/builds/libretro/buildscripts
: "${NDK:?}" "${JOBS:=4}"
export NDK_ROOT=$NDK ANDROID_NDK=$NDK MAKEFLAGS=-j$JOBS
# tarballs from other machines: do not try to keep their owners (fails in containers)
export TAR_OPTIONS=--no-same-owner

cd "$src"
if [ ! -f "$BS/android/.deps-built" ]; then
	# start from the pinned scripts, then apply libretro's CI patches
	git -C "$BS" checkout -q -- .
	builds/libretro/patches.sh
	# arm64-v8a only
	perl -ni -e 'print unless /^build "(ARMeabi-v7a|x86|x86_64)"/' "$BS/android/2_build_toolchain.sh"
	(
		cd "$BS/android"
		rm -rf include lib lib64 ./*-toolchain
		./1_download_library.sh
		./2_build_toolchain.sh
		./3_cleanup.sh
	)
	touch "$BS/android/.deps-built"
fi

export EASYRPG_BUILDSCRIPTS=$BS
preset=android-arm64-v8a-libretro-release
cmake --preset $preset -DPLAYER_BUILD_LIBLCF=ON -DCMAKE_INSTALL_PREFIX=. \
	-DCMAKE_IGNORE_PATH=/usr/include -DPLAYER_VERSION_APPEND=libretro \
	-DCMAKE_ANDROID_NDK="$NDK"
cmake --build --preset $preset -j "$JOBS"
cp "build/$preset/easyrpg_libretro.so" "$out"
