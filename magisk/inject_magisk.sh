#!/bin/bash
#
# inject_magisk.sh - bake Magisk into a first-stage ramdisk at build time.
#
# Why this exists
# ---------------
# The GameConsole RK3326 devices boot very manually: U-Boot reads boot.ini and
# does `booti <Image> <ramdisk> <dtb>` off a FAT partition. There is no
# boot.img, so the normal Magisk flow (install app -> patch boot.img -> flash)
# has nothing to patch. Instead we inject magiskinit + the Magisk payload
# directly into the ramdisk we ship, using Magisk's OWN magiskboot so the
# result is identical to a normally-patched boot image. Root works from the
# first boot with no secondary install and no extra reboot.
#
# What it does (mirrors scripts/boot_patch.sh from the Magisk source):
#   * replaces /init in the ramdisk with magiskinit
#   * adds the compressed Magisk payload under overlay.d/sbin/
#   * writes the Magisk .backup/.magisk config (KEEPVERITY/KEEPFORCEENCRYPT
#     false, PREINITDEVICE=metadata so no post-boot reboot is needed)
#
# Usage:
#   inject_magisk.sh --ramdisk <in.img|in.cpio[.gz]> --apk <Magisk-vXX.apk> \
#                    --out <out.cpio.gz> [--preinit metadata] \
#                    [--keepverity false] [--keepforceencrypt false]
#
# magiskboot only runs on the build host. The APK carries it for x86_64, x86,
# arm64 and arm, so the host's own copy is used; other hosts fall back to the
# arm64 one through qemu-aarch64-static (qemu-user-static).
set -euo pipefail

RAMDISK_IN=""
APK=""
OUT=""
PREINITDEVICE="metadata"
KEEPVERITY="false"
KEEPFORCEENCRYPT="false"
RECOVERYMODE="false"

die() { echo "inject_magisk: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --ramdisk) RAMDISK_IN="$2"; shift 2;;
        --apk) APK="$2"; shift 2;;
        --out) OUT="$2"; shift 2;;
        --preinit) PREINITDEVICE="$2"; shift 2;;
        --keepverity) KEEPVERITY="$2"; shift 2;;
        --keepforceencrypt) KEEPFORCEENCRYPT="$2"; shift 2;;
        *) die "unknown arg: $1";;
    esac
done

[ -n "$RAMDISK_IN" ] && [ -f "$RAMDISK_IN" ] || die "missing --ramdisk (input ramdisk not found)"
[ -n "$APK" ] && [ -f "$APK" ] || die "missing --apk (Magisk apk not found)"
[ -n "$OUT" ] || die "missing --out"

# Make paths absolute now: the script cd's into its work dir later.
abspath() { case "$1" in /*) printf '%s\n' "$1";; *) printf '%s/%s\n' "$PWD" "$1";; esac; }
RAMDISK_IN="$(abspath "$RAMDISK_IN")"
APK="$(abspath "$APK")"
OUT="$(abspath "$OUT")"

WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

echo "inject_magisk: extracting Magisk payload from $(basename "$APK")"
unzip -qo "$APK" -d "$WORK/apk"

# The DEVICE payload (magiskinit, magisk applet, init-ld, stub app) is arm64:
# these are added into the ramdisk as files, they are not run on the host.
A64="$WORK/apk/lib/arm64-v8a"
A32="$WORK/apk/lib/armeabi-v7a"
[ -d "$A64" ] || die "APK has no arm64-v8a libs - is this a real Magisk apk?"

cd "$WORK"

# magiskinit (required)
[ -f "$A64/libmagiskinit.so" ] && cp "$A64/libmagiskinit.so" magiskinit || die "APK missing libmagiskinit.so"
# magisk applet: modern Magisk ships one libmagisk.so; older ships 64/32 split.
[ -f "$A64/libmagisk.so" ]   && cp "$A64/libmagisk.so"   magisk
[ -f "$A64/libmagisk64.so" ] && cp "$A64/libmagisk64.so" magisk64
[ -f "$A32/libmagisk32.so" ] && cp "$A32/libmagisk32.so" magisk32
# init-ld + stub app (optional but normally present)
[ -f "$A64/libinit-ld.so" ] && cp "$A64/libinit-ld.so" init-ld
if [ -f "$WORK/apk/assets/stub.apk" ]; then cp "$WORK/apk/assets/stub.apk" stub.apk;
elif [ -f "$A64/libstub.so" ]; then cp "$A64/libstub.so" stub.apk; fi

# magiskboot is just a build tool: run the HOST-arch copy natively so no qemu
# is needed. Fall back to the arm64 copy under qemu if there is no host build.
case "$(uname -m)" in
    x86_64)         HOSTLIB="$WORK/apk/lib/x86_64";      HOSTQEMU="";;
    i386|i686)      HOSTLIB="$WORK/apk/lib/x86";         HOSTQEMU="";;
    aarch64|arm64)  HOSTLIB="$A64";                      HOSTQEMU="";;
    armv7l|armv8l)  HOSTLIB="$A32";                      HOSTQEMU="";;
    *)              HOSTLIB="";                           HOSTQEMU="";;
esac
RUNNER=""
if [ -n "$HOSTLIB" ] && [ -f "$HOSTLIB/libmagiskboot.so" ]; then
    cp "$HOSTLIB/libmagiskboot.so" magiskboot
else
    # No matching host build: use arm64 magiskboot through qemu.
    cp "$A64/libmagiskboot.so" magiskboot
    for q in qemu-aarch64-static qemu-aarch64; do command -v "$q" >/dev/null 2>&1 && RUNNER="$q" && break; done
    [ -n "$RUNNER" ] || die "no host-arch magiskboot and no qemu-aarch64 to run the arm64 one (install qemu-user-static)."
fi
chmod +x magiskboot magiskinit 2>/dev/null || true
MB() { if [ -n "$RUNNER" ]; then "$RUNNER" ./magiskboot "$@"; else ./magiskboot "$@"; fi; }
# Sanity: magiskboot with no args prints usage and exits nonzero; an exit of
# 126/127 instead means it could not be executed at all. (|| so set -e is happy.)
ec=0; MB >/dev/null 2>&1 || ec=$?
{ [ "$ec" -ne 126 ] && [ "$ec" -ne 127 ]; } || die "cannot execute magiskboot on this host (exit $ec)."
echo "inject_magisk: magiskboot = $([ -n "$RUNNER" ] && echo "$RUNNER (qemu)" || echo native $(uname -m))"

# 1. Get an uncompressed cpio from whatever we were handed (gz/lz4/xz or raw).
if MB decompress "$RAMDISK_IN" ramdisk.cpio 2>/dev/null; then
    :
else
    # already uncompressed cpio
    cp "$RAMDISK_IN" ramdisk.cpio
fi

# 2. Magisk config written to .backup/.magisk (see boot_patch.sh).
{
    echo "KEEPVERITY=$KEEPVERITY"
    echo "KEEPFORCEENCRYPT=$KEEPFORCEENCRYPT"
    echo "RECOVERYMODE=$RECOVERYMODE"
    if [ -n "$PREINITDEVICE" ]; then echo "PREINITDEVICE=$PREINITDEVICE"; fi
} > config

# 3. Compress the payload the way this Magisk version expects. We derive the
#    exact overlay.d/sbin/*.xz set from the APK's own boot_patch.sh so we track
#    whichever layout that release uses (single magisk.xz vs magisk32/64.xz).
BP="$WORK/apk/assets/boot_patch.sh"
ADD_ARGS=()
add_xz() { # add_xz <target-in-ramdisk .xz name> <source file in $WORK>
    [ -f "$2" ] || return 0
    MB compress=xz "$2" "$1"
    ADD_ARGS+=("add 0644 overlay.d/sbin/$1 $1")
}
if [ ! -f "$BP" ] || grep -q 'overlay.d/sbin/magisk.xz' "$BP"; then
    # Unified single-binary layout (modern Magisk): one magisk.xz. Prefer the
    # already-extracted libmagisk.so; fall back to the 64/32 applet.
    if [ ! -f magisk ]; then
        if [ -f magisk64 ]; then cp magisk64 magisk; elif [ -f magisk32 ]; then cp magisk32 magisk; fi
    fi
    add_xz magisk.xz magisk
else
    # Split layout.
    add_xz magisk32.xz magisk32
    add_xz magisk64.xz magisk64
fi
add_xz stub.xz stub.apk
add_xz init-ld.xz init-ld

# 4. Patch the ramdisk cpio (identical command set to boot_patch.sh). The
#    pristine copy is what the "backup" command diffs against for restore.
cp -af ramdisk.cpio ramdisk.cpio.orig
MB cpio ramdisk.cpio \
    "add 0750 init magiskinit" \
    "mkdir 0750 overlay.d" \
    "mkdir 0750 overlay.d/sbin" \
    "${ADD_ARGS[@]}" \
    "patch" \
    "backup ramdisk.cpio.orig" \
    "mkdir 000 .backup" \
    "add 000 .backup/.magisk config" \
    || die "magiskboot cpio patch failed"

# 5. Emit a gzip-compressed ramdisk (kernel is built with CONFIG_RD_GZIP=y).
MB compress=gzip ramdisk.cpio ramdisk.magisk.cpio.gz
mkdir -p "$(dirname "$OUT")"
cp ramdisk.magisk.cpio.gz "$OUT"
echo "inject_magisk: wrote $OUT"
