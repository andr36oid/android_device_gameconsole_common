#!/bin/bash
# Fake cards and /data, mksh, the real host unpacker and Toybox command syntax.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
MKSH=${1:-$(command -v mksh)}
TOYBOX=${TOYBOX:-/andr36oid/src/prebuilts/build-tools/linux-x86/bin/toybox}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
export PYTHONDONTWRITEBYTECODE=1
export TEST_ROOT=$T
export HOTFIX_STATE=$T/state HOTFIX_BOOT=$T/boot HOTFIX_ROOT=$T/root
export HOTFIX_UNPACK=$T/unpack HOTFIX_SH=$MKSH
export HOTFIX_LIB=$HERE/../helper/andr36oid-hotfix-lib.sh
export HOTFIX_BOOT_LOCK=$T/boot.lock HOTFIX_BOOT_MNT=$T/mnt
export HOTFIX_EASYROMS_MNT=$T/roms-mnt
(cd "$HERE/.." && go test ./... && go build -o "$T/unpack" ./cmd/andr36oid-hotfix-unpack)
mkdir -p "$T/bin" "$T/props" "$T/root/system/lib64" "$T/root/vendor/lib64" "$T/mnt"
printf original >"$T/root/system/lib64/test.so"
chmod 0644 "$T/root/system/lib64/test.so"
if [ "$(id -u)" = 0 ]; then /usr/bin/chown 1234:2345 "$T/root/system/lib64/test.so"; fi
expected_owner=$(stat -c %u:%g "$T/root/system/lib64/test.so")
cat >"$T/bin/getprop" <<'SH'
#!/bin/sh
[ "$#" = 1 ] || exit 1
if [ -f "$TEST_ROOT/props/$1" ]; then cat "$TEST_ROOT/props/$1"; else echo; fi
SH
cat >"$T/bin/setprop" <<'SH'
#!/bin/sh
[ "${#2}" -lt 92 ] || exit 1
[ "${FAIL_COUNTER:-0}" != 1 ] || [ "$1" != persist.andr36oid.hotfix.tries ] || exit 1
printf 'setprop %s %s\n' "$1" "$2" >>"$TEST_ROOT/trace"
printf '%s\n' "$2" >"$TEST_ROOT/props/$1"
SH
cat >"$T/bin/log" <<'SH'
#!/bin/sh
[ "$1" = -t ] && [ "$2" = andr36oid-hotfix ] || exit 1
SH
cat >"$T/bin/chown" <<'SH'
#!/bin/sh
# Names on Android; the host need not have a system group.
printf 'chown %s %s\n' "$1" "$2" >>"$TEST_ROOT/trace"
case $1 in root:system) exit 0 ;; esac
exec /usr/bin/chown "$@"
SH
cat >"$T/bin/chcon" <<'SH'
#!/bin/sh
[ "$#" = 2 ] && [ "$1" = u:object_r:system_file:s0 ] && [ -f "$2" ] || exit 1
printf 'chcon %s %s\n' "$1" "$2" >>"$TEST_ROOT/trace"
[ "${FAIL_CONTEXT:-0}" != 1 ]
SH
cat >"$T/bin/stat" <<'SH'
#!/bin/sh
if [ "$1" = -c ] && [ "$2" = %C ]; then
    [ -f "$3" ] || exit 1
    echo u:object_r:system_file:s0
else
    exec /usr/bin/stat "$@"
fi
SH
cat >"$T/bin/mount" <<'SH'
#!/bin/sh
case "$1:$2" in
-o:bind)
    [ "$#" = 4 ] && [ -f "$3" ] && [ -f "$4" ] || exit 1
    printf 'bind %s %s\n' "$3" "$4" >>"$TEST_ROOT/trace"
    [ "${FAIL_BIND:-0}" != 1 ] ;;
-t:vfat)
    [ "$#" = 6 ] && [ "$3" = -o ] && [ "$4" = ro,noatime ] && [ -e "$5" ] || exit 1
    printf 'boot %s\n' "$5" >>"$TEST_ROOT/trace"
    cp -R "$TEST_ROOT/boot/." "$6/" ;;
-t:exfat)
    [ "$#" = 6 ] && [ "$3" = -o ] || exit 1
    [ "$4" = ro,nodev,nosuid,noexec,noatime,uid=0,gid=1023,fmask=0007,dmask=0007 ] || exit 1
    [ "$5" = "$TEST_ROOT/root/dev/block/$(cat "$TEST_ROOT/props/sys.andr36oid.easyroms.dev")" ] || exit 1
    [ "$6" = "$HOTFIX_EASYROMS_MNT" ] || exit 1
    printf 'easyroms %s\n' "$5" >>"$TEST_ROOT/trace"
    [ "${SLOW_ROMS_MOUNT:-0}" != 1 ] || exec sleep 20
    [ "${FAIL_ROMS_MOUNT:-0}" != 1 ] || exit 1
    cp -R "$TEST_ROOT/easyroms/." "$6/"
    : >"$TEST_ROOT/roms-mounted" ;;
*) exit 1 ;;
esac
SH
cat >"$T/bin/umount" <<'SH'
#!/bin/sh
[ "$#" = 1 ] || { [ "$#" = 2 ] && [ "$1" = -l ]; } || exit 1
printf 'umount %s\n' "$1" >>"$TEST_ROOT/trace"
if [ "$1" = "$HOTFIX_EASYROMS_MNT" ] || [ "${2:-}" = "$HOTFIX_EASYROMS_MNT" ]; then
    [ "${FAIL_ROMS_UMOUNT:-0}" != 1 ] || [ "$1" = -l ] || exit 1
    rm -rf "$TEST_ROOT/roms-mounted" "$HOTFIX_EASYROMS_MNT"
fi
SH
chmod +x "$T/bin/"*
# Use the checked-in host Toybox for every ordinary helper command it supplies.
if [ -x "$TOYBOX" ]; then
    ln -s "$(dirname "$TOYBOX")/../lib64" "$T/lib64"
    for cmd in cat chmod cp grep head mkdir mktemp readlink rm sleep timeout tr; do
        ln -s "$TOYBOX" "$T/bin/$cmd"
    done
fi
export PATH="$T/bin:$PATH"
pass=0
fail=0
check() {
    if [ "$2" = "$3" ]; then pass=$((pass + 1)); else
        echo "FAIL $1: expected '$2', got '$3'"
        fail=$((fail + 1))
    fi
}
prop() { cat "$T/props/$1" 2>/dev/null || true; }
setp() { printf '%s\n' "$2" >"$T/props/$1"; }
setup() {
    rm -rf "${T:?}/boot" "$T/state" "$T/props" "${T:?}/mnt" "$T/easyroms" "$T/roms-mnt" "$T/roms-mounted"
    mkdir -p "$T/boot/hotfix" "$T/props" "$T/mnt" "$T/easyroms/hotfix"
    : >"$T/trace"
    setp ro.andr36oid.version v1
    setp ro.product.build.version.incremental 123
    setp sys.andr36oid.easyroms.ready 1
    unset FAIL_COUNTER FAIL_CONTEXT FAIL_BIND FAIL_ROMS_MOUNT SLOW_ROMS_MOUNT FAIL_ROMS_UMOUNT
}
pack() { # filename build script
    python3 - "$T/boot/hotfix/$1.fix.zip" "$2" "$3" <<'PY'
import sys,zipfile
with zipfile.ZipFile(sys.argv[1], 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('manifest.txt', 'name='+sys.argv[1].split('/')[-1][:-8]+'\ndescription=A test\nbuild='+sys.argv[2]+'\n')
    z.writestr('hotfix.sh', sys.argv[3])
    z.writestr('files/replacement', b'patched')
PY
}
run() { rm -f "$T/state/started"; "$MKSH" "$HERE/../helper/andr36oid-hotfix.sh"; }
result() { cut -f 2,3 "$T/state/status" | tr '\t' ':'; }

setup
pack a 'v1|123' 'hotfix_bind files/replacement /system/lib64/test.so'
run
check applied ran:ok "$(result)"
check counted 1 "$(prop persist.andr36oid.hotfix.tries)"
check 'status readable' 640 "$(stat -c %a "$T/state/status")"
check 'directory traversable' 750 "$(stat -c %a "$T/state")"
check 'stable bind source' 1 "$(find "$T/state/bind" -type f | wc -l | tr -d ' ')"
check 'bind mode copied' 644 "$(stat -c %a "$T/state/bind/1/"*)"
check 'bind owner copied' "$expected_owner" "$(stat -c %u:%g "$T/state/bind/1/"*)"
check 'label copied' 1 "$(grep -c '^chcon u:object_r:system_file:s0 ' "$T/trace")"
check 'original untouched' original "$(cat "$T/root/system/lib64/test.so")"
check 'applying cleared' 0 "$(prop sys.andr36oid.hotfix.applying)"
check 'source BOOT' BOOT "$(cut -f 5 "$T/state/status")"
"$MKSH" "$HERE/../helper/andr36oid-hotfix.sh"
check 'same start runs once' 1 "$(grep -c '^bind ' "$T/trace")"
run
check reapplied ran:ok "$(result)"
check 'second try' 2 "$(prop persist.andr36oid.hotfix.tries)"
run
check 'third start paused' skipped:paused "$(result)"
check 'safety property' crash "$(prop persist.andr36oid.hotfix.paused)"
setp persist.andr36oid.hotfix.tries 0
setp persist.andr36oid.hotfix.paused 0
run
check 'reenabled' ran:ok "$(result)"
# The real boot-completed init action resets this property.
setp persist.andr36oid.hotfix.tries 0
run
check 'successful start resets sequence' 1 "$(prop persist.andr36oid.hotfix.tries)"

setup
pack old 'v0|123' 'exit 99'
run
check 'build mismatch' skipped:build "$(result)"
check 'mismatch not counted' '' "$(prop persist.andr36oid.hotfix.tries)"
check 'mismatch retained' yes "$([ -f "$T/boot/hotfix/old.fix.zip" ] && echo yes)"
check 'mismatch has no animation note' '' "$(prop sys.andr36oid.hotfix.applying)"
setup
pack a 'v1|123' 'exit 0'
setp ro.product.build.version.incremental 124
run
check 'same version different incremental' skipped:build "$(result)"
setp ro.product.build.version.incremental ''
run
check 'missing identity' skipped:build "$(result)"
setp ro.product.build.version.incremental 123
setp persist.andr36oid.hotfix.paused 1
run
check disabled skipped:disabled "$(result)"
check 'disabled has no animation note' '' "$(prop sys.andr36oid.hotfix.applying)"
setp persist.andr36oid.hotfix.paused 0
setp ro.boot.andr36oid_safe 1
run
check 'safe start' skipped:safe "$(result)"
check 'safe not counted' '' "$(prop persist.andr36oid.hotfix.tries)"
setp persist.andr36oid.hotfix.tries 2
run
check 'safe start after failures' skipped:safe "$(result)"
check 'safe start keeps safety pause' crash "$(prop persist.andr36oid.hotfix.paused)"

setup
pack a 'v1|123' 'exit 42'
pack b 'v1|123' 'hotfix_log success'
run
check 'failure continues' $'failed:exit\nran:ok' "$(result)"
check 'failure clears animation note' 0 "$(prop sys.andr36oid.hotfix.applying)"
check 'multiple hotfixes counted once' 1 "$(prop persist.andr36oid.hotfix.tries)"
setup
printf invalid >"$T/boot/hotfix/a.fix.zip"
pack b 'v1|123' 'exit 0'
run
check 'bad zip continues' $'failed:invalid\nran:ok' "$(result)"
setup
pack a 'v1|123' 'exit 0'
export FAIL_COUNTER=1
run
check 'counter failure skips' skipped:counter "$(result)"
unset FAIL_COUNTER

setup
pack a 'v1|123' 'hotfix_bind files/replacement /system/lib64/missing.so'
run
check 'missing target' failed:exit "$(result)"
pack a 'v1|123' 'hotfix_bind /etc/passwd /system/lib64/test.so'
setp persist.andr36oid.hotfix.tries 0
run
check 'source outside archive' failed:exit "$(result)"
pack a 'v1|123' 'hotfix_bind files/replacement /data/test'
setp persist.andr36oid.hotfix.tries 0
run
check 'target outside system/vendor' failed:exit "$(result)"
pack a 'v1|123' 'hotfix_bind files/replacement /system/lib64/test.so'
setp persist.andr36oid.hotfix.tries 0
export FAIL_CONTEXT=1
run
check 'label failure' failed:exit "$(result)"
check 'label failure prevents bind' 0 "$(grep -c '^bind ' "$T/trace" || true)"

setup
pack a 'v1|123' 'exit 0'
setp ro.boot.boot_devices ff390000.dwmmc
emmc_boot=$T/root/dev/block/platform/ff390000.dwmmc/by-name/BOOT
mkdir -p "$(dirname "$emmc_boot")"
: >"$emmc_boot"
HOTFIX_BOOT='' run
check 'eMMC BOOT mount' "boot $emmc_boot" "$(grep '^boot ' "$T/trace")"
check 'eMMC applied' ran:ok "$(result)"
check 'eMMC unmounted' "umount $T/mnt" "$(grep '^umount ' "$T/trace")"
check 'BOOT lock released' yes "$([ ! -e "$T/boot.lock" ] && echo yes)"

setup
pack a 'v1|123' 'exit 0'
sd_boot=$T/root/dev/block/platform/ff370000.dwmmc/by-name/BOOT
mkdir -p "$(dirname "$sd_boot")"
: >"$sd_boot"
HOTFIX_BOOT='' run
check 'SD BOOT mount' "boot $sd_boot" "$(grep '^boot ' "$T/trace")"
setp ro.boot.boot_devices unknown
HOTFIX_BOOT='' run
check 'unknown disk skipped' skipped:unavailable "$(result)"

roms() {
    setp sys.andr36oid.easyroms.dev "${1:-mmcblk1p7}"
    mkdir -p "$T/root/dev/block"
    python3 - "$T/root/dev/block/${1:-mmcblk1p7}" "${2:-0}" <<'PY'
import sys
from pathlib import Path
b = bytearray(512)
b[3:11], b[106], b[510:512] = b'EXFAT   ', int(sys.argv[2]), b'\x55\xaa'
Path(sys.argv[1]).write_bytes(b)
PY
}

setup
run
check 'no hotfixes has no animation note' '' "$(prop sys.andr36oid.hotfix.applying)"
check 'no EASYROMS does not mount' 0 "$(grep -c '^easyroms ' "$T/trace" || true)"

setup
roms
pack a 'v1|123' '[ ! -e "$TEST_ROOT/roms-mounted" ] && [ "$(getprop sys.andr36oid.hotfix.applying)" = 1 ]'
mv "$T/boot/hotfix/a.fix.zip" "$T/easyroms/hotfix/"
mv "$T/easyroms/hotfix" "$T/easyroms/HoTfIx"
run
check 'mixed case EASYROMS folder' ran:ok "$(result)"
check 'source EASYROMS' EASYROMS "$(cut -f 5 "$T/state/status")"
check 'selected partition only' "easyroms $T/root/dev/block/mmcblk1p7" "$(grep '^easyroms ' "$T/trace")"
check 'unmounted before applying' yes "$([ ! -e "$T/roms-mounted" ] && echo yes)"
check 'EASYROMS archive retained' yes "$([ -f "$T/easyroms/HoTfIx/a.fix.zip" ] && echo yes)"
check 'EASYROMS note cleared' 0 "$(prop sys.andr36oid.hotfix.applying)"

setup
roms
pack a 'v1|123' 'exit 99'
mv "$T/boot/hotfix/a.fix.zip" "$T/easyroms/hotfix/"
pack a 'v1|123' 'exit 0'
run
check 'same filename BOOT wins' $'skipped:duplicate\nran:ok' "$(result)"
check 'duplicate source shown' $'EASYROMS\nBOOT' "$(cut -f 5 "$T/state/status")"
check 'duplicate logged' 1 "$(grep -c 'BOOT wins over EASYROMS' "$T/state/log")"

setup
roms
pack a 'v1|123' 'exit 99'
mv "$T/boot/hotfix/a.fix.zip" "$T/easyroms/hotfix/A.fix.zip"
pack a 'v0|123' 'exit 0'
run
check 'filename case and mismatched BOOT keep priority' $'skipped:duplicate\nskipped:build' "$(result)"
check 'duplicates alone have no animation note' '' "$(prop sys.andr36oid.hotfix.applying)"

setup
roms
pack a 'v1|123' 'exit 99'
mv "$T/boot/hotfix/a.fix.zip" "$T/easyroms/hotfix/alias.fix.zip"
pack a 'v1|123' 'exit 0'
run
check 'manifest name BOOT wins' $'ran:ok\nskipped:duplicate' "$(result)"

setup
roms
pack a 'v1|123' 'exit 0'
mv "$T/boot/hotfix/a.fix.zip" "$T/easyroms/hotfix/"
pack b 'v1|123' 'exit 0'
run
check 'both sources apply' $'ran:ok\nran:ok' "$(result)"
check 'both sources in status' $'BOOT\nEASYROMS' "$(cut -f 5 "$T/state/status")"
check 'both sources counted once' 1 "$(prop persist.andr36oid.hotfix.tries)"

setup
roms
pack a 'v1|123' 'exit 0'
mv "$T/boot/hotfix/a.fix.zip" "$T/easyroms/hotfix/"
setp ro.boot.boot_devices unknown
HOTFIX_BOOT='' run
check 'unavailable BOOT still loads EASYROMS' $'skipped:unavailable\nran:ok' "$(result)"

for failure in FAIL_ROMS_MOUNT SLOW_ROMS_MOUNT; do
    setup
    roms
    pack a 'v1|123' 'exit 0'
    export "$failure=1"
    run
    check "$failure keeps BOOT" ran:ok "$(result)"
    check "$failure logged" 1 "$(grep -c 'mount failed or timed out' "$T/state/log")"
done

setup
roms mmcblk1p7 2
pack a 'v1|123' 'exit 0'
run
check 'dirty EASYROMS keeps BOOT' ran:ok "$(result)"
check 'dirty EASYROMS never mounted' 0 "$(grep -c '^easyroms ' "$T/trace" || true)"
check 'dirty EASYROMS logged' 1 "$(grep -c 'dirty or unreadable' "$T/state/log")"
check 'dirty flag never written' 2 "$(od -An -tu1 -j106 -N1 "$T/root/dev/block/mmcblk1p7" | tr -d ' ')"

setup
roms
setp sys.andr36oid.easyroms.ready 0
run
check 'unpublished lookup never mounts a card' 0 "$(grep -c '^easyroms ' "$T/trace" || true)"
setup
roms
setp sys.andr36oid.easyroms.dev ''
run
check 'published absence never falls back' 0 "$(grep -c '^easyroms ' "$T/trace" || true)"

setup
roms
pack a 'v1|123' '[ ! -e "$TEST_ROOT/roms-mounted" ]'
export FAIL_ROMS_UMOUNT=1
run
check 'failed unmount detaches before scripts' ran:ok "$(result)"
check 'lazy unmount used' 1 "$(grep -c '^umount -l' "$T/trace")"

setup
roms
python3 - "$T/boot/hotfix/large.fix.zip" "$T/easyroms/hotfix/large-roms.fix.zip" <<'PY'
import sys, zipfile
for path in sys.argv[1:]:
    with open(path, 'wb') as f:
        f.seek(64 << 20)
        with zipfile.ZipFile(f, 'w') as z:
            z.writestr('manifest.txt', 'name=large\ndescription=A test\nbuild=v1|123\n')
            z.writestr('hotfix.sh', 'exit 0\n')
PY
run
check 'packed limits differ by source' $'failed:invalid\nran:ok' "$(result)"

setup
roms
python3 - "$T/easyroms/hotfix/too-large.fix.zip" <<'PY'
import sys
with open(sys.argv[1], 'wb') as f:
    f.truncate((128 << 20) + 1)
PY
run
check 'EASYROMS packed limit enforced' failed:invalid "$(result)"

setup
roms
python3 - "$T/boot/hotfix/boot.fix.zip" "$T/easyroms/hotfix/a.fix.zip" "$T/easyroms/hotfix/b.fix.zip" <<'PY'
import sys, zipfile
for i, path in enumerate(sys.argv[1:]):
    with open(path, 'wb') as f:
        f.seek((32 if i == 0 else 112) << 20)
        with zipfile.ZipFile(f, 'w') as z:
            z.writestr('manifest.txt', 'name=fix'+str(i)+'\ndescription=A test\nbuild=v1|123\n')
            z.writestr('hotfix.sh', 'exit 0\n')
PY
run
check 'combined staging budget enforced' $'skipped:limit\nran:ok\nran:ok' "$(result)"
check 'staging limit source shown' $'EASYROMS\nBOOT\nEASYROMS' "$(cut -f 5 "$T/state/status")"

setup
roms
for i in $(seq 1 17); do
    pack "fix$i" 'v1|123' 'exit 0'
done
mv "$T/boot/hotfix/fix17.fix.zip" "$T/easyroms/hotfix/"
run
check 'archive count shared across sources' 16 "$(grep -c 'ran' "$T/state/status")"
check 'archive count limit recorded' 1 "$(grep -c 'skipped.*limit.*EASYROMS' "$T/state/status")"

setup
pack a 'v1|123' 'sleep 60'
run
check 'script timeout' failed:timeout "$(result)"
check 'timeout clears animation note' 0 "$(prop sys.andr36oid.hotfix.applying)"

setup
pack a 'v1|123' 'kill -TERM "$HOTFIX_TEST_PID"'
# Give the script the runner's PID through its inherited environment.
rm -f "$T/state/started"
"$MKSH" -c 'export HOTFIX_TEST_PID=$$; . "$1"' hotfix "$HERE/../helper/andr36oid-hotfix.sh"
check 'signal clears animation note' 0 "$(prop sys.andr36oid.hotfix.applying)"

python3 "$HERE/test_generator.py"
python3 "$HERE/test_delta.py"
echo "$pass shell checks passed, $fail failed"
[ "$fail" = 0 ]
