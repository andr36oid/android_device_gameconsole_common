#!/usr/bin/env bash
set -euo pipefail
src=$(cd "$1" && pwd)
out=$(realpath -m "$2")
: "${NDK:?}" "${JOBS:=4}"
command -v ruby >/dev/null
command -v rake >/dev/null
# Gravity uses bzero without its declaration. Bionic implements it in strings.h.
python3 - "$src/vendor/gravity/src/compiler/gravity_lexer.c" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text()
if '#include <strings.h>' not in s:
    anchor = '#include "gravity_lexer.h"'
    assert s.count(anchor) == 1
    p.write_text(s.replace(anchor, '#include <strings.h>\n' + anchor))
PY
# mruby's Android toolchain replaces its compiler flags; retain the A35 flags
# supplied by CMake through TARGET_CFLAGS as well.
python3 - "$src/build/mruby/tic_android.rb" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text()
line = "    cc.flags.concat ENV.fetch('TARGET_CFLAGS', '').split"
if line not in s:
    anchor = "    cc.flags << '-fPIC'"
    assert s.count(anchor) == 1
    p.write_text(s.replace(anchor, anchor + '\n' + line))
    stamps = p.parents[2] / '.build-android/mruby_vendor-prefix/src/mruby_vendor-stamp'
    for suffix in ('build', 'done'):
        (stamps / ('mruby_vendor-' + suffix)).unlink(missing_ok=True)
PY
# Gravity's upstream patch driver requires generated sources inside the git root.
b=$src/.build-android
flags='-march=armv8-a -mtune=cortex-a35 -mno-outline-atomics'
cmake -S "$src" -B "$b" -DCMAKE_BUILD_TYPE=Release \
	-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
	-DANDROID_PLATFORM=android-21 -DANDROID_ABI=arm64-v8a -DANDROID_STL=c++_static \
	-DCMAKE_C_FLAGS="$flags" -DCMAKE_CXX_FLAGS="$flags" \
	-DCMAKE_C_FLAGS_RELEASE='-O3 -DNDEBUG -flto=thin' \
	-DCMAKE_CXX_FLAGS_RELEASE='-O3 -DNDEBUG -flto=thin' \
	-DCMAKE_SHARED_LINKER_FLAGS='-Wl,--thinlto-jobs=8' \
	-DBUILD_LIBRETRO=ON -DBUILD_PLAYER=OFF -DBUILD_SDL=OFF -DBUILD_SOKOL=OFF \
	-DBUILD_EDITORS=OFF -DBUILD_SURF=OFF -DBUILD_PRO=OFF -DBUILD_WITH_ALL=ON
cmake --build "$b" --target tic80_libretro -j "$JOBS"
cp "$b/bin/tic80_libretro_android.so" "$out"
