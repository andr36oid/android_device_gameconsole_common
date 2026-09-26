#!/usr/bin/env bash
#
# Builds the libretro cores listed in emulators/cores.lock from their pinned
# commits and packs them into emulators/libretro/cores-<tier>.tar.gz together
# with a NOTICE file per tier.
#
# Building from pinned sources (instead of grabbing buildbot nightlies) is what
# lets us point at the exact corresponding source for every binary we ship.
#
# usage: build-cores.sh [--tier foss|noncommercial|all] [--ndk DIR] [--work DIR] [--jobs N] [--sources DIR]
#
#   --ndk      NDK to use. Defaults to $ANDROID_NDK_HOME, otherwise the pinned
#              r27c gets downloaded into the work dir.
#   --sources  also drop a source tarball per core into DIR (optional, the NOTICE points at the sources)

set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
LOCK=$HERE/cores.lock
OUT=$HERE/libretro

NDK_URL=https://dl.google.com/android/repository/android-ndk-r27c-linux.zip
NDK_SHA256=59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc
API_LEVEL=21
ABI=arm64-v8a

TIER=all
NDK=${ANDROID_NDK_HOME:-}
WORK=${TMPDIR:-/tmp}/gameconsole-cores
JOBS=$(nproc)
SOURCES=

while [ $# -gt 0 ]; do
	case $1 in
		--tier) TIER=$2; shift 2 ;;
		--ndk) NDK=$2; shift 2 ;;
		--work) WORK=$2; shift 2 ;;
		--jobs) JOBS=$2; shift 2 ;;
		--sources) SOURCES=$2; shift 2 ;;
		-h|--help) sed -n '2,17p' "$0"; exit 0 ;;
		*) echo "unknown option: $1" >&2; exit 1 ;;
	esac
done

case $TIER in foss|noncommercial|all) ;; *) echo "bad --tier: $TIER" >&2; exit 1 ;; esac

die() { echo "error: $*" >&2; exit 1; }
for t in git make cmake unzip tar gzip sha256sum; do
	command -v $t >/dev/null || die "$t not found"
done

mkdir -p "$WORK" "$OUT"
[ -n "$SOURCES" ] && mkdir -p "$SOURCES" && SOURCES=$(cd "$SOURCES" && pwd)

if [ -z "$NDK" ]; then
	NDK=$WORK/android-ndk-r27c
	if [ ! -x "$NDK/ndk-build" ]; then
		echo ">> fetching NDK r27c"
		curl -fL -o "$WORK/ndk.zip" "$NDK_URL"
		echo "$NDK_SHA256  $WORK/ndk.zip" | sha256sum -c - >/dev/null || die "NDK checksum mismatch"
		unzip -q -o "$WORK/ndk.zip" -d "$WORK"
		rm -f "$WORK/ndk.zip"
	fi
fi
[ -x "$NDK/ndk-build" ] || die "no usable NDK at $NDK"
STRIP=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip

# checkout <dir> <repo> <commit>
checkout() {
	local dir=$1 repo=$2 commit=$3
	if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null)" != "$commit" ]; then
		rm -rf "$dir"
		git init -q "$dir"
		git -C "$dir" remote add origin "$repo"
		git -C "$dir" fetch -q --depth 1 origin "$commit"
		git -C "$dir" checkout -q FETCH_HEAD
	fi
	git -C "$dir" submodule update -q --init --recursive --depth 1
}

# build <name> <build> <path> <args> ; leaves $WORK/out/<name>_libretro_android.so
build() {
	local name=$1 kind=$2 path=$3 args=$4 src=$WORK/src/$1
	local lib=$WORK/out/${name}_libretro_android.so
	case $kind in
	jni)
		"$NDK/ndk-build" --no-print-directory -j"$JOBS" -C "$src/$path/jni" \
			APP_ABI=$ABI APP_PLATFORM=android-$API_LEVEL >"$WORK/log/$name.log" 2>&1
		cp "$src/$path/libs/$ABI/libretro.so" "$lib"
		;;
	cmake)
		local b=$WORK/build/$name extra=()
		[ "$args" != "-" ] && IFS=, read -r -a extra <<<"$args"
		cmake -DCMAKE_BUILD_TYPE=Release "${extra[@]}" \
			-DANDROID_PLATFORM=android-$API_LEVEL -DANDROID_STL=c++_static -DANDROID_ABI=$ABI \
			-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
			-S "$src/$path" -B "$b" >"$WORK/log/$name.log" 2>&1
		cmake --build "$b" --target "${name}_libretro" -j "$JOBS" >>"$WORK/log/$name.log" 2>&1
		local so
		so=$(find "$b" -name "${name}_libretro_android.so" -o -name "${name}_libretro.so" | head -n1)
		[ -n "$so" ] || die "$name: cmake produced no library"
		cp "$so" "$lib"
		;;
	*) die "$name: unknown build type $kind" ;;
	esac
	"$STRIP" "$lib"
}

mkdir -p "$WORK/src" "$WORK/out" "$WORK/log" "$WORK/build"

declare -A TIER_CORES
while read -r name tier spdx kind path licfile args repo commit; do
	case $name in ''|\#*) continue ;; esac
	[ "$TIER" = all ] || [ "$TIER" = "$tier" ] || continue

	echo ">> $name ($tier, ${commit:0:7})"
	checkout "$WORK/src/$name" "$repo" "$commit"
	build "$name" "$kind" "$path" "$args" || die "$name: build failed, see $WORK/log/$name.log"
	TIER_CORES[$tier]+="$name "

	if [ -n "$SOURCES" ]; then
		# tracked files only (incl. submodules), no build leftovers
		git -C "$WORK/src/$name" ls-files --recurse-submodules | sed "s|^|$name/|" |
			tar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@0 --no-recursion \
				-C "$WORK/src" -cf - -T - | gzip -9n >"$SOURCES/${name}-${commit}.tar.gz"
	fi
done <"$LOCK"

# notice <tier> <cores...>
notice() {
	local tier=$1; shift
	echo "libretro cores shipped in /system/etc/gameconsole/libretro/cores-$tier.tar.gz"
	echo "Built for $ABI (API $API_LEVEL) with Android NDK r27c from the exact commits below."
	echo "Corresponding source: the listed repository at the listed commit (including"
	echo "git submodules)."
	[ "$tier" = noncommercial ] && {
		echo
		echo "NOTE: the cores in this archive are licensed for NON-COMMERCIAL use only."
		echo "Do not sell devices or images that contain them."
	}
	local c name t spdx kind path licfile args repo commit
	for c in "$@"; do
		read -r name t spdx kind path licfile args repo commit < <(awk -v n="$c" '$1==n' "$LOCK")
		echo
		echo "================================================================================"
		echo "$name  ($spdx)"
		echo "source: $repo"
		echo "commit: $commit"
		echo "sha256: $(sha256sum "$WORK/out/${name}_libretro_android.so" | cut -d' ' -f1)"
		echo "================================================================================"
		cat "$WORK/src/$name/$licfile"
	done
}

for tier in "${!TIER_CORES[@]}"; do
	read -r -a cores <<<"${TIER_CORES[$tier]}"
	stage=$WORK/stage-$tier
	rm -rf "$stage"; mkdir -p "$stage/cores"
	for c in "${cores[@]}"; do cp "$WORK/out/${c}_libretro_android.so" "$stage/cores/"; done
	notice "$tier" "${cores[@]}" >"$OUT/NOTICE-$tier.txt"
	cp "$OUT/NOTICE-$tier.txt" "$stage/NOTICE.txt"
	# plain ustar, so toybox tar on the device has nothing to trip over
	tar --format=ustar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@0 -C "$stage" -cf - . \
		| gzip -9n >"$OUT/cores-$tier.tar.gz"
	echo ">> $OUT/cores-$tier.tar.gz ($(du -h "$OUT/cores-$tier.tar.gz" | cut -f1), ${#cores[@]} cores)"
done
