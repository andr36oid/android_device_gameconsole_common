#!/bin/bash
# Host test for the startlog mode of helper/andr36oid-report.sh (start log on BOOT
# when Android never finishes starting). Runs the real script in a fake Android
# root (bwrap) with stub commands and fake /proc, /sys, /data and BOOT.
#
#   tests/test-startlog.sh [shell]   shell: path to mksh (default: the one from
#                                    O2-56's tmp dir), bash, dash or busybox
# Needs bwrap, zip, unzip. Uses toybox 0.8.3 (Android 11's) for sed, tr, head and
# friends if prebuilts/build-tools has it, like on the console.

# The stubs are shell code for the fake root, kept literal on purpose.
# shellcheck disable=SC2016
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
SCRIPT=$HERE/helper/andr36oid-report.sh
SH=${1:-/andr36oid/work/tmp/o2-shellcheck/mksh/mksh}
TOYBOX=${TOYBOX:-/andr36oid/src/prebuilts/build-tools/linux-x86/bin/toybox}
T=$(mktemp -d /tmp/test-startlog.XXXXXX)
[ -n "$T" ] && [ -d "$T" ] || exit 1
[ -n "${KEEP:-}" ] || trap 'rm -rf "$T"' EXIT
PASS=0
FAIL=0

ok() { PASS=$((PASS + 1)); }
bad() {
	FAIL=$((FAIL + 1))
	echo "FAIL: $*"
}
check() { # check <description> <command...>
	local d=$1
	shift
	if "$@"; then ok; else bad "$d"; fi
}

make_root() {
	R=$T/root
	rm -rf "$R"
	mkdir -p "$R"/system/bin "$R"/system/etc "$R"/toy "$R"/proc/device-tree \
		"$R"/sys/fs/pstore "$R"/sys/class/power_supply/battery \
		"$R"/data/misc/andr36oid-report "$R"/data/system "$R"/data/tombstones "$R"/data/anr \
		"$R"/mnt "$R"/boot "$R"/tmp
	cp "$SCRIPT" "$R"/system/etc/andr36oid-report.sh
	if [ -x "$TOYBOX" ]; then
		mkdir -p "$R"/toy/bin "$R"/toy/lib64
		cp "$TOYBOX" "$R"/toy/bin/toybox
		cp "$(dirname "$TOYBOX")"/../lib64/libcrypto-host.so "$(dirname "$TOYBOX")"/../lib64/libz-host.so \
			"$(dirname "$TOYBOX")"/../lib64/libc++.so "$R"/toy/lib64/
		for a in sed tr head cut wc find sha1sum tail grep sort mkdir rm mv cat ls rmdir date; do
			ln -s toybox "$R/toy/bin/$a"
		done
	fi
	stub() {
		printf '#!/bin/bash\necho "$(basename "$0") $*" >>/tmp/calls\n%s\n' "$2" >"$R/system/bin/$1"
		chmod +x "$R/system/bin/$1"
	}
	stub log 'echo "log: $*" >>/tmp/log'
	stub getprop 'P=/tmp/props
if [ $# -eq 0 ]; then sed "s/^\([^=]*\)=\(.*\)/[\1]: [\2]/" $P; exit 0; fi
v=$(sed -n "s/^$1=//p" $P | head -n 1); echo "${v:-$2}"'
	cat >"$R"/tmp/props <<'EOF'
ro.andr36oid.version=test-1
ro.build.fingerprint=andr36oid/r36s:11/test/2:userdebug
ro.product.device=r36s
ro.boot.revision=3
ro.serialno=SERIALSECRET1
init.svc.bootanim=running
init.svc.zygote=restarting
sys.boot_completed=0
EOF
	stub logcat 'echo "logcat $*" >>/tmp/logcat-args
cat <<"EOF"
09-29 10:00:00.000  1000  1000 I WifiService: connected to SSID: "HomeNetSecret"
09-29 10:00:00.004  1000  1000 I RetroArch: Loading content file: /storage/ABCD-1234/snes/Super Secret Game (USA).sfc
09-29 10:00:00.010  1000  1000 E AndroidRuntime: *** FATAL EXCEPTION IN SYSTEM PROCESS KEEPME
EOF'
	stub dmesg 'echo "[    1.0] rockchip-drm display-subsystem: KEEPKERNEL"'
	for c in dumpsys sm pm settings am; do stub "$c" 'echo "must not be called"'; done
	stub ps 'echo "USER PID NAME"'
	stub uptime 'echo " 10:05:00 up 5 min"'
	stub sync 'true'
	stub chown 'true'
	stub df 'if [ -e /tmp/boot-free-kb ] && [ "$2" = /boot ]; then
echo "Filesystem 1K-blocks Used Available Use% Mounted"
echo "/dev/boot 262144 1000 $(cat /tmp/boot-free-kb) 1% /boot"
else /bin/df "$@"; fi'
	stub mount 'echo "mount $* lock=$([ -d /tmp/bootlock ] && echo held || echo free)" >>/tmp/mounts'
	stub umount 'echo "umount $* lock=$([ -d /tmp/bootlock ] && echo held || echo free)" >>/tmp/mounts'

	printf 'Powkiddy R36S\0' >"$R"/proc/device-tree/model
	printf 'rockchip,rk3326-odroid-go2\0' >"$R"/proc/device-tree/compatible
	echo "console=ttyS2 androidboot.serialno=SERIALSECRET2" >"$R"/proc/cmdline
	echo "MemTotal: 1000 kB" >"$R"/proc/meminfo
	echo "/dev/block/mmcblk0p2 /system ext4 ro 0 0" >"$R"/proc/mounts
	printf 'kernel log of the start before KEEPPSTORE\n' >"$R"/sys/fs/pstore/console-ramoops-0
	echo 77 >"$R"/sys/class/power_supply/battery/capacity
	echo "pid: 1, name: zygote KEEPTOMB /storage/emulated/0/Secret Tomb/file.bin" >"$R"/data/tombstones/tombstone_00
	# not the first start: PackageManager wrote this at an earlier start
	echo "<packages/>" >"$R"/data/system/packages.xml
	# and this version finished starting before
	echo "andr36oid/r36s:11/test/2:userdebug" >"$R"/data/misc/andr36oid-report/start-build

	B=$R/boot
	mkdir -p "$B/Panels/Panel 4"
	printf 'load mmc 1:1 ${fdt_addr_r} rk3326-r36s-android.dtb\r\n' >"$B/boot.ini"
	echo "panel 4 dtb" >"$B/rk3326-r36s-android.dtb"
	echo "panel 4 dtb" >"$B/Panels/Panel 4/rk3326-r36s-android.dtb"
	: >"$R"/tmp/calls
}

# run_script <mode> ; the script in the fake root, as the console's init would start it
run_script() {
	local shell_cmd lib64=() boot_env=(--setenv REPORT_TEST_BOOT /boot)
	[ -n "${REAL_MOUNT:-}" ] && boot_env=(--setenv REPORT_BOOT_DEVS /tmp/bootdev --setenv REPORT_BOOT_MNT /boot)
	case $SH in
	bash) shell_cmd=(/bin/bash /system/etc/andr36oid-report.sh) ;;
	dash) shell_cmd=(/bin/dash /system/etc/andr36oid-report.sh) ;;
	busybox)
		# busybox ash runs its own applets before PATH: sync, mount, umount, df, ps
		# and uptime would be the real ones. Functions come first, so the stubs are
		# functions here.
		cat >"$R/tmp/pre.sh" <<'EOF'
for c in sync mount umount df ps uptime; do eval "$c() { /system/bin/$c \"\$@\"; }"; done
EOF
		shell_cmd=(/bin/busybox ash -c '. /tmp/pre.sh; . /system/etc/andr36oid-report.sh' ash)
		;;
	*)
		cp "$SH" "$R/system/bin/sh"
		shell_cmd=(/system/bin/sh /system/etc/andr36oid-report.sh)
		;;
	esac
	[ -e /lib64 ] && lib64=(--ro-bind /lib64 /lib64)
	bwrap --ro-bind /usr /usr --ro-bind /bin /bin --ro-bind /lib /lib \
		"${lib64[@]}" --ro-bind /etc /etc \
		--bind "$R/system" /system --bind "$R/proc" /proc --bind "$R/sys" /sys \
		--bind "$R/data" /data --bind "$R/mnt" /mnt --bind "$R/boot" /boot \
		--bind "$R/tmp" /tmp --ro-bind "$R/toy" /toy --dev /dev \
		--setenv PATH /toy/bin:/system/bin:/usr/bin:/bin --setenv LD_LIBRARY_PATH /toy/lib64 \
		"${boot_env[@]}" --setenv REPORT_BOOT_LOCK /tmp/bootlock \
		--setenv REPORT_BOOT_LOCK_WAIT "${LOCK_WAIT:-30}" --setenv REPORT_LOCK /tmp/lock \
		--setenv REPORT_START_WAIT "${WAIT:-2}" --setenv REPORT_START_WAIT_FIRST "${WAIT_FIRST:-6}" \
		--setenv REPORT_START_STEP 1 --setenv REPORT_START_WORK /tmp/startwork \
		--setenv REPORT_START_PENDING /tmp/pending --setenv REPORT_BOOT_WAIT 2 --setenv REPORT_SETTLE 0 \
		"${shell_cmd[@]}" "$@" >"$T/out" 2>&1
	echo $? >"$T/rc"
}

logs_on_boot() { find "$R/boot/andr36oid-reports" -name 'start-log-*' 2>/dev/null | wc -l; }

unpack() { # unpack <zip>
	rm -rf "$T/x"
	mkdir -p "$T/x"
	[ -n "$1" ] && unzip -q "$1" -d "$T/x"
}
has() { grep -r -q -- "$1" "$T/x"; }
hasnt() { ! grep -r -q -- "$1" "$T/x"; }

# ---- 1. a normal start: ends at once, nothing mounted or written ----
make_root
sed -i 's/^sys.boot_completed=0/sys.boot_completed=1/' "$R/tmp/props"
touch "$R/tmp/bootdev"
S=$(date +%s)
REAL_MOUNT=1 run_script startlog
E=$(date +%s)
check "normal: exit 0 ($(cat "$T/out"))" [ "$(cat "$T/rc")" = 0 ]
check "normal: ends at once" [ $((E - S)) -le 1 ]
check "normal: only getprop" [ -z "$(grep -v '^getprop ' "$R/tmp/calls")" ]
check "normal: BOOT not mounted" [ ! -e "$R/tmp/mounts" ]
check "normal: no start log" [ "$(logs_on_boot)" = 0 ]
check "normal: no note" [ ! -e "$R/boot/andr36oid-start-log.txt" ]

# ---- 2. a slow start that finishes before the limit: nothing ----
make_root
(sleep 1 && sed -i 's/^sys.boot_completed=0/sys.boot_completed=1/' "$R/tmp/props") &
WAIT=4 run_script startlog
wait
check "slow start: no start log" [ "$(logs_on_boot)" = 0 ]
check "slow start: no logcat" [ ! -e "$R/tmp/logcat-args" ]

# ---- 3. the start never finishes: a start log on BOOT ----
make_root
S=$(date +%s)
run_script startlog
E=$(date +%s)
check "hang: exit 0 ($(cat "$T/out"))" [ "$(cat "$T/rc")" = 0 ]
check "hang: waited the limit" [ $((E - S)) -ge 2 ]
Z=$(find "$R/boot/andr36oid-reports" -name 'start-log-*.zip' | head -n 1)
check "hang: zip on BOOT" [ -n "$Z" ]
unpack "$Z"
for f in summary system logcat kernel crashes README previous-start-console-ramoops-0; do
	check "hang: zip has $f.txt" [ -f "$(echo "$T"/x/start-log-*/$f.txt)" ]
done
check "hang: summary says why" has "did not finish starting in 0 minutes (first start of this card: no)"
check "hang: zygote state" has "zygote: restarting"
check "hang: DTB matched" has "same as: Panels/Panel 4/rk3326-r36s-android.dtb"
check "hang: getprop" has "init.svc.zygote"
check "hang: logcat kept" has KEEPME
check "hang: dmesg kept" has KEEPKERNEL
check "hang: pstore kept" has KEEPPSTORE
check "hang: tombstone kept" has KEEPTOMB
check "hang: logcat main,system,crash" grep -q -- '-d -b main,system,crash' "$R/tmp/logcat-args"
for s in HomeNetSecret "Super Secret Game" "Secret Tomb" SERIALSECRET1 SERIALSECRET2; do
	check "hang: no '$s'" hasnt "$s"
done
check "hang: nothing asked system_server" [ -z "$(grep -E '^(dumpsys|sm|pm|settings|am) ' "$R/tmp/calls")" ]
N=$R/boot/andr36oid-start-log.txt
check "hang: note on BOOT" [ -f "$N" ]
check "hang: note names the zip" grep -q "Newest / Más nuevo / Mais novo: andr36oid-reports/${Z##*/}" "$N"
check "hang: note has the issue link" grep -q "github.com/andr36oid/releases/issues" "$N"
check "hang: note in Spanish" grep -q "no terminó de arrancar" "$N"
check "hang: note CRLF" grep -q $'\r$' "$N"
check "hang: note BOM" [ "$(head -c 3 "$N" | od -An -tx1 | tr -d ' ')" = efbbbf ]
check "hang: RAM work folder removed" [ ! -e "$R/tmp/startwork" ]
check "hang: lock released" [ ! -e "$R/tmp/lock" ]
check "hang: BOOT lock released" [ ! -e "$R/tmp/bootlock" ]
check "hang: nothing kept in /data" [ ! -e "$R/data/misc/andr36oid-report/start-logs" ]

# ---- 4. first start of the card: waits the longer limit ----
make_root
rm "$R/data/system/packages.xml"
S=$(date +%s)
run_script startlog
E=$(date +%s)
check "first start: waited the long limit" [ $((E - S)) -ge 6 ]
unpack "$(find "$R/boot/andr36oid-reports" -name 'start-log-*.zip' | head -n 1)"
check "first start: summary says so" has "(first start of this card: yes)"
# ... and a first start that finishes after the normal limit but before the long one
make_root
rm "$R/data/system/packages.xml"
(sleep 3 && sed -i 's/^sys.boot_completed=0/sys.boot_completed=1/' "$R/tmp/props") &
run_script startlog
wait
check "first start, done in time: no start log" [ "$(logs_on_boot)" = 0 ]

# ---- 4b. first start of a new version (the updater keeps /data): the long limit ----
make_root
echo "andr36oid/r36s:11/test/1:userdebug" >"$R"/data/misc/andr36oid-report/start-build
S=$(date +%s)
run_script startlog
E=$(date +%s)
check "new version: waited the long limit" [ $((E - S)) -ge 6 ]
unpack "$(find "$R/boot/andr36oid-reports" -name 'start-log-*.zip' | head -n 1)"
check "new version: summary says so" has "(first start of this card: no, first start of a new version)"
check "new version, never started: old version kept" \
	grep -qx "andr36oid/r36s:11/test/1:userdebug" "$R"/data/misc/andr36oid-report/start-build
# ... finishing after the normal limit but before the long one: no start log, and the
# new version is noted, so the next start has the normal limit again
make_root
rm "$R"/data/misc/andr36oid-report/start-build
(sleep 3 && sed -i 's/^sys.boot_completed=0/sys.boot_completed=1/' "$R/tmp/props") &
run_script startlog
wait
check "new version, done in time: no start log" [ "$(logs_on_boot)" = 0 ]
check "new version, done in time: version noted" \
	grep -qx "andr36oid/r36s:11/test/2:userdebug" "$R"/data/misc/andr36oid-report/start-build

# ---- 5. BOOT keeps the newest 3 start logs (by name) and never the reports' ----
make_root
mkdir -p "$R/boot/andr36oid-reports"
for d in 20990101 20990102 20990103 20200101; do
	touch "$R/boot/andr36oid-reports/start-log-$d-000000.zip"
done
touch "$R/boot/andr36oid-reports/report-20200101-000000.zip"
run_script startlog
check "keep 3: 3 left" [ "$(logs_on_boot)" = 3 ]
check "keep 3: the new one kept" [ -n "$(find "$R/boot/andr36oid-reports" -name "start-log-$(date +%Y)*.zip")" ]
check "keep 3: newest names kept" [ -e "$R/boot/andr36oid-reports/start-log-20990103-000000.zip" ] &&
	[ -e "$R/boot/andr36oid-reports/start-log-20990102-000000.zip" ]
check "keep 3: reports untouched" [ -e "$R/boot/andr36oid-reports/report-20200101-000000.zip" ]
check "keep 3: note lists 3" [ "$(grep -c '^  start-log-' "$R/boot/andr36oid-start-log.txt")" = 3 ]

# ---- 6. BOOT too full: kept in /data, and the next problem report takes it along ----
make_root
echo 40000 >"$R/tmp/boot-free-kb"
run_script startlog
check "full BOOT: nothing on BOOT" [ "$(logs_on_boot)" = 0 ] && [ ! -e "$R/boot/andr36oid-start-log.txt" ]
K=$(find "$R/data/misc/andr36oid-report/start-logs" -name 'start-log-*.zip' 2>/dev/null | head -n 1)
check "full BOOT: kept in /data" [ -n "$K" ]
check "full BOOT: said so" grep -q "only 40000 KB free" "$R/tmp/log"
# the page's report (BOOT still full, so it goes to Download)
sed -i 's/^sys.boot_completed=0/sys.boot_completed=1/' "$R/tmp/props"
mkdir -p "$R/data/media/0/Download"
for c in dumpsys sm pm settings am; do printf '#!/bin/bash\ntrue\n' >"$R/system/bin/$c"; done
run_script
RZ=$(find "$R/data/media/0/Download/andr36oid-reports" -name 'report-*.zip' 2>/dev/null | head -n 1)
unpack "$RZ"
check "report takes the start log along ($(tail -n 3 "$T/out"))" [ -f "$(echo "$T"/x/report-*/"${K##*/}")" ]

# ---- 7. BOOT read-only: kept in /data ----
make_root
touch "$R/boot/.readonly"
run_script startlog
check "ro BOOT: nothing on BOOT" [ "$(logs_on_boot)" = 0 ]
check "ro BOOT: kept in /data" [ -n "$(find "$R/data/misc/andr36oid-report/start-logs" -name 'start-log-*.zip' 2>/dev/null)" ]

# ---- 8. BOOT held by another helper the whole time: kept in /data ----
make_root
sleep 60 &
HOLDER=$!
mkdir "$R/tmp/bootlock"
echo "$HOLDER" >"$R/tmp/bootlock/pid"
LOCK_WAIT=1 run_script startlog
kill "$HOLDER" 2>/dev/null
wait "$HOLDER" 2>/dev/null
check "BOOT busy: kept in /data" [ -n "$(find "$R/data/misc/andr36oid-report/start-logs" -name 'start-log-*.zip' 2>/dev/null)" ]
check "BOOT busy: holder's lock kept" [ "$(cat "$R/tmp/bootlock/pid")" = "$HOLDER" ]
unpack "$(find "$R/data/misc/andr36oid-report/start-logs" -name 'start-log-*.zip' | head -n 1)"
check "BOOT busy: summary says BOOT not readable" has "BOOT: not readable"

# ---- 9. no BOOT device at all: kept in /data ----
make_root
REAL_MOUNT=1 run_script startlog
check "no BOOT: kept in /data" [ -n "$(find "$R/data/misc/andr36oid-report/start-logs" -name 'start-log-*.zip' 2>/dev/null)" ]
check "no BOOT: lock released" [ ! -e "$R/tmp/bootlock" ]

# ---- 10. /data kept logs: newest 3 ----
make_root
touch "$R/boot/.readonly"
mkdir -p "$R/data/misc/andr36oid-report/start-logs"
for d in 20990101 20990102 20990103; do
	touch "$R/data/misc/andr36oid-report/start-logs/start-log-$d-000000.zip"
done
run_script startlog
check "/data keep 3" [ "$(find "$R/data/misc/andr36oid-report/start-logs" -name 'start-log-*' | wc -l)" = 3 ]

# ---- 11. make-report.txt is there: the start-up report saves instead ----
make_root
touch "$R/tmp/pending"
run_script startlog
check "pending report: no start log" [ "$(logs_on_boot)" = 0 ]
check "pending report: nothing in /data" [ ! -e "$R/data/misc/andr36oid-report/start-logs" ]
# and the start-up report sets that marker
make_root
touch "$R/boot/make-report.txt"
sed -i 's/^sys.boot_completed=0/sys.boot_completed=1/' "$R/tmp/props"
for c in dumpsys sm pm settings am; do printf '#!/bin/bash\ntrue\n' >"$R/system/bin/$c"; done
run_script boot
check "make-report sets the marker" [ -e "$R/tmp/pending" ]

# ---- 12. the real mount code: BOOT read-only for the DTB, writable only for the copy ----
make_root
touch "$R/tmp/bootdev"
REAL_MOUNT=1 run_script startlog
check "mounts: saved on BOOT ($(cat "$T/out"))" [ "$(logs_on_boot)" = 1 ]
check "mounts: always with the lock" sh -c "! grep -q 'lock=free' '$R/tmp/mounts'"
check "mounts: ro for the DTB, closed while collecting, ro + rw to save" \
	[ "$(sed 's/ lock=.*//; s/ \/tmp\/bootdev//' "$R/tmp/mounts" | tr '\n' ,)" = \
	"mount -t vfat -o ro,noatime /boot,umount /boot,mount -t vfat -o ro,noatime /boot,mount -o remount,rw /boot,umount /boot," ]
check "mounts: rw only after logcat ran" [ "$(grep -n -m1 '^logcat ' "$R/tmp/calls" | cut -d: -f1)" -lt \
	"$(grep -n -m1 'remount,rw' "$R/tmp/calls" | cut -d: -f1)" ]

echo "passed $PASS, failed $FAIL (shell: $SH)"
[ "$FAIL" = 0 ]
