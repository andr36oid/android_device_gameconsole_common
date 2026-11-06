#!/usr/bin/env bash
set -euo pipefail
src=$(cd "$1" && pwd)
out=$(realpath -m "$2")
: "${NDK:?}" "${JOBS:=4}"
# The pinned JNI recipe omits '$' from this make-variable expansion, passing
# literal parentheses to /bin/sh instead of NP21's required include paths.
python3 - "$src/jni/Android.mk" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
old, new = 'INCFLAGS += (NP21_INCFLAGS)', 'INCFLAGS += $(NP21_INCFLAGS)'
s = p.read_text()
if old in s:
    assert s.count(old) == 1
    p.write_text(s.replace(old, new))
else:
    assert new in s, 'unexpected NP2kai JNI recipe'
PY
"$NDK/ndk-build" --no-print-directory -j"$JOBS" -C "$src/jni" \
	APP_ABI=arm64-v8a APP_PLATFORM=android-21 APP_STL=c++_static \
	APP_CFLAGS='-march=armv8-a -mtune=cortex-a35 -mno-outline-atomics' \
	APP_CPPFLAGS='-march=armv8-a -mtune=cortex-a35 -mno-outline-atomics' \
	NP2KAI_VERSION=0.86 NP2KAI_HASH=253ef65 SUPPORT_NET=0
cp "$src/libs/arm64-v8a/libretro.so" "$out"
