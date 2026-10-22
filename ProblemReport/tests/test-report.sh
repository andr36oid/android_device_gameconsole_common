#!/bin/bash
# Host test for helper/andr36oid-report.sh: runs the collector in a fake Android
# root (bwrap) with stub commands and fake /proc, /sys, /data, BOOT and EASYROMS,
# then checks the zip: where it went, what is in it, and that the personal things
# planted in the fake logs are gone.
#
#   tests/test-report.sh [shell]   shell: path to mksh (default: the one from
#                                  O2-56's tmp dir) or bash
# Needs bwrap, zip, unzip. Uses toybox 0.8.3 (Android 11's) for sed, tr, head,
# cut and friends if prebuilts/build-tools has it, like on the console.

# The stubs are shell code for the fake root, kept literal on purpose.
# shellcheck disable=SC2016
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
SCRIPT=$HERE/helper/andr36oid-report.sh
SH=${1:-/andr36oid/work/tmp/o2-shellcheck/mksh/mksh}
TOYBOX=${TOYBOX:-/andr36oid/src/prebuilts/build-tools/linux-x86/bin/toybox}
ROMLIST=$HERE/../emulators/seed/rom-folders.txt
T=$(mktemp -d "${TMPDIR:-/tmp}/test-report.XXXXXX")
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

# ---- the fake root ----
make_root() {
	R=$T/root
	rm -rf "$R"
	mkdir -p "$R"/system/bin "$R"/system/etc/gameconsole "$R"/toy "$R"/proc/device-tree \
		"$R"/proc/bus/input "$R"/sys/fs/pstore "$R"/sys/class/power_supply/battery \
		"$R"/sys/class/thermal/thermal_zone0 "$R"/sys/block/mmcblk0/device \
		"$R"/sys/block/mmcblk1/device "$R"/data/misc/andr36oid-report "$R"/data/media/0/Download \
		"$R"/data/tombstones "$R"/data/anr "$R"/mnt/media_rw/ABCD-1234 "$R"/boot "$R"/tmp \
		"$R"/sys/power

	mkdir -p "$R/data/misc/andr36oid-hotfix"
	printf 'test-fix\tran\tok\tHOTFIXSTATUSKEEP\tEASYROMS\n' >"$R/data/misc/andr36oid-hotfix/status"
	echo HOTFIXLOGKEEP >"$R/data/misc/andr36oid-hotfix/log"
	cp "$SCRIPT" "$R"/system/etc/andr36oid-report.sh
	cp "$ROMLIST" "$R"/system/etc/gameconsole/rom-folders.txt

	# toybox applets first in PATH, like /system/bin on the console
	if [ -x "$TOYBOX" ]; then
		mkdir -p "$R"/toy/bin "$R"/toy/lib64
		cp "$TOYBOX" "$R"/toy/bin/toybox
		cp "$(dirname "$TOYBOX")"/../lib64/libcrypto-host.so "$(dirname "$TOYBOX")"/../lib64/libz-host.so \
			"$(dirname "$TOYBOX")"/../lib64/libc++.so "$R"/toy/lib64/
		for a in sed tr head cut wc find sha1sum tail grep sort mkdir rm mv cat ls \
			rmdir date; do
			ln -s toybox "$R/toy/bin/$a"
		done
	fi

	# the calls log: every stub writes what it was asked
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
ro.build.fingerprint=andr36oid/r36s/r36s:11/RQ3A/1:userdebug/test-keys
ro.product.device=r36s
ro.boot.hardware=rk30board
ro.boot.revision=3
ro.serialno=SERIALSECRET1
ro.boot.serialno=SERIALSECRET1
net.hostname=kennys-secret-console
sys.andr36oid.ktd.serial_console=off
sys.boot_completed=1
EOF
	stub logcat 'cat <<"EOF"
09-29 10:00:00.000  1000  1000 I WifiService: connected to SSID: "HomeNetSecret", BSSID: 02:aa:bb:cc:dd:ee
09-29 10:00:00.001  1000  1000 I wpa_supplicant: wlan0: Trying to associate with SSID '"'"'OtherNetSecret'"'"'
09-29 10:00:00.002  1000  1000 I WifiConfigManager: config "CafeSecret"WPA_PSK added, psk=hunter2secret
09-29 10:00:00.003  1000  1000 I ConnectivityService: NetworkInfo [type: WIFI, state: CONNECTED, extra: "ExtraNetSecret", failover: false]
09-29 10:00:00.004  1000  1000 I RetroArch: Loading content file: /storage/ABCD-1234/snes/Super Secret Game (USA).sfc
09-29 10:00:00.005  1000  1000 I MediaProvider: open "/storage/emulated/0/Download/Private Photo.jpg" ok, next
09-29 10:00:00.006  1000  1000 I AccountManager: Account {name=alice@example.com, type=com.google}
09-29 10:00:00.007  1000  1000 I DropBox: added system_app_crash@1727000000000.txt
09-29 10:00:00.008  1000  1000 I DocumentsUI: uri content://com.android.externalstorage.documents/tree/primary%3ASecretFolder ok
09-29 10:00:00.009  1000  1000 I Mail: from bob.smith@mail.example.org
09-29 10:00:00.010  1000  1000 E InputReader: joypad keys dead KEEPME
EOF'
	stub dmesg 'printf "[    0.1] serial_debugger: disabled\n[    1.0] mmc0: new ultra high speed SDR104 SDXC card at address 59b4 KEEPKERNEL\n"'
	stub dumpsys 'case "$1" in
activity) echo "Intent { act=android.intent.action.VIEW dat=file:///storage/ABCD-1234/snes/Secret Game 2.sfc flg=0x1 }" ;;
audio) echo "Device: type:0x80 addr:AA:BB:CC:DD:EE:FF name:My Secret Headphones" ;;
input) echo "Input devices: odroidgo3-joypad KEEPINPUT" ;;
*) echo "dumpsys $* ok" ;;
esac'
	stub sm 'case "$*" in
"list-volumes public")
if [ -f /tmp/volumes ]; then cat /tmp/volumes
else [ -e /tmp/no-easyroms ] || echo "public:179,7 mounted ABCD-1234"; fi ;;
*) echo "emulated;0 mounted null" ;;
esac'
	stub settings 'case "$1" in
list) printf "device_name=Kenny Secret R36S\nwifi_on=1\nbluetooth_name=Kenny Secret BT\nadb_enabled=1\n" ;;
get) echo 1 ;;
esac'
	stub pm 'echo "package:/system/app/Settings/Settings.apk=com.android.settings versionCode:30"'
	stub am 'true'
	stub lsusb 'echo "Bus 001 Device 001: ID 1d6b:0002"'
	stub blkid 'case "$1" in
		*mmcblk2p2) echo "$1: LABEL=\"STOCKROOT\" UUID=\"EMMCUUIDSECRET\" TYPE=\"ext4\"" ;;
		esac'
	stub getevent 'echo "add device 1: /dev/input/event2"; echo "  name:     \"odroidgo3-joypad\""'
	stub ip 'echo "3: wlan0: <UP> link/ether 02:11:22:33:44:55 brd ff:ff:ff:ff:ff:ff"'
	stub lsmod 'echo "Module Size Used"'
	stub top 'echo "top ok"'
	stub ps 'echo "USER PID NAME"'
	stub uptime 'echo " 10:00:00 up 5 min"'
	stub sync 'true'
	stub chown 'true'
	# df: the fake BOOT's free space comes from /tmp/boot-free-kb
	stub df 'if [ -e /tmp/boot-free-kb ] && [ "$2" = /boot ]; then
echo "Filesystem 1K-blocks Used Available Use% Mounted"
echo "/dev/boot 262144 1000 $(cat /tmp/boot-free-kb) 1% /boot"
else /bin/df "$@"; fi'
	# mount/umount only record what they were asked, and whether the BOOT lock was held
	stub mount 'echo "mount $* lock=$([ -d /tmp/bootlock ] && echo held || echo free)" >>/tmp/mounts'
	stub umount 'echo "umount $* lock=$([ -d /tmp/bootlock ] && echo held || echo free)" >>/tmp/mounts'

	# /proc
	printf 'Powkiddy R36S\0' >"$R"/proc/device-tree/model
	printf 'rockchip,rk3326-odroid-go2\0rockchip,rk3326\0' >"$R"/proc/device-tree/compatible
	echo "console=ttyS2 androidboot.serialno=SERIALSECRET2 androidboot.revision=3" >"$R"/proc/cmdline
	printf 'processor\t: 0\nSerial\t\t: SERIALSECRET3\n' >"$R"/proc/cpuinfo
	echo "MemTotal: 1000 kB" >"$R"/proc/meminfo
	echo "/dev/block/mmcblk0p2 /system ext4 ro 0 0" >"$R"/proc/mounts
	for f in interrupts zoneinfo; do echo "$f" >"$R/proc/$f"; done
	printf 'I: Bus=0019\nN: Name="odroidgo3-joypad"\n' >"$R"/proc/bus/input/devices

	# /sys
	printf 'kernel log of the start before KEEPPSTORE, wifi ssid="PstoreNetSecret"\n' \
		>"$R"/sys/fs/pstore/console-ramoops-0
	echo 77 >"$R"/sys/class/power_supply/battery/capacity
	echo Discharging >"$R"/sys/class/power_supply/battery/status
	echo 3900000 >"$R"/sys/class/power_supply/battery/voltage_now
	printf 'POWER_SUPPLY_CAPACITY=77\n' >"$R"/sys/class/power_supply/battery/uevent
	echo cpu-thermal >"$R"/sys/class/thermal/thermal_zone0/type
	echo 45000 >"$R"/sys/class/thermal/thermal_zone0/temp
	echo mem >"$R"/sys/power/state
	# CID: MID 03, OID SD, PNM SC64G, PRV 80, PSN 12345678, reserved 0, MDT 16a (2022-10), CRC a7
	echo 03534453433634478012345678016aa7 >"$R"/sys/block/mmcblk0/device/cid
	echo 400e00325b590001d8bf7f800a4040df >"$R"/sys/block/mmcblk0/device/csd
	echo SC64G >"$R"/sys/block/mmcblk0/device/name
	echo 0x000003 >"$R"/sys/block/mmcblk0/device/manfid
	echo 124735488 >"$R"/sys/block/mmcblk0/size
	echo SECRETSERIALFILE >"$R"/sys/block/mmcblk0/device/serial
	echo short >"$R"/sys/block/mmcblk1/device/cid
	# the built-in eMMC (Kernel Tweaks switched it on): a disk under ff390000.dwmmc
	M=$R/sys/devices/platform/ff390000.dwmmc/mmc_host/mmc2/mmc2:0001/block/mmcblk2
	mkdir -p "$M/mmcblk2p1" "$M/mmcblk2p2" "$R"/sys/block/mmcblk2boot0
	echo 15269888 >"$M/size"
	echo 1 >"$M/ro"
	echo 16384 >"$M/mmcblk2p1/start"
	echo 8192 >"$M/mmcblk2p1/size"
	printf 'PARTNAME=uboot\n' >"$M/mmcblk2p1/uevent"
	echo 32768 >"$M/mmcblk2p2/start"
	echo 4194304 >"$M/mmcblk2p2/size"
	printf 'PARTNAME=rootfs\n' >"$M/mmcblk2p2/uevent"
	echo 8192 >"$R"/sys/block/mmcblk2boot0/size
	ln -s ../devices/platform/ff390000.dwmmc/mmc_host/mmc2/mmc2:0001/block/mmcblk2 \
		"$R"/sys/block/mmcblk2

	# /data
	mkdir -p "$R"/data/data/org.andr36oid.usbmode/files
	printf 'andr36oid USB check\n1-1  0bda:885a  NOT_SUPPORTED USBCHECKKEEP\n' \
		>"$R"/data/data/org.andr36oid.usbmode/files/usb-check.txt
	echo "pid: 1, name: /storage/emulated/0/Secret Tomb/file.bin" >"$R"/data/tombstones/tombstone_00
	echo "anr" >"$R"/data/anr/anr_1

	# EASYROMS: ROM folders with games, plus a private folder
	E=$R/mnt/media_rw/ABCD-1234
	mkdir -p "$E/snes" "$E/gba" "$E/bios" "$E/My Secret Folder"
	touch "$E/snes/Secret Game A.sfc" "$E/snes/Secret Game B.sfc" "$E/snes/Secret Game C.sfc" \
		"$E/gba/Secret Game D.gba" "$E/My Secret Folder/diary.txt" "$E/secret-top.txt"

	# BOOT
	B=$R/boot
	mkdir -p "$B/Panels/Panel 4" "$B/Panels/Panel 1"
	printf 'load mmc 1:1 ${kernel_addr_r} Image\r\nload mmc 1:1 ${fdt_addr_r} rk3326-r36s-android.dtb\r\n' >"$B/boot.ini"
	echo "panel 4 dtb" >"$B/rk3326-r36s-android.dtb"
	echo "panel 4 dtb" >"$B/Panels/Panel 4/rk3326-r36s-android.dtb"
	echo "panel 1 dtb" >"$B/Panels/Panel 1/rk3326-r36s-android.dtb"
	: >"$R"/tmp/calls
}

# run_report [boot] ; runs the collector in the fake root, as the console's init would
run_report() {
	local shell_cmd lib64=() boot_env=(--setenv REPORT_TEST_BOOT /boot)
	# REAL_MOUNT=1: the mount code runs (with the stubs), BOOT_MNT is the fake BOOT
	[ -n "${REAL_MOUNT:-}" ] && boot_env=(--setenv REPORT_BOOT_DEVS /tmp/bootdev --setenv REPORT_BOOT_MNT /boot)
	case $SH in
	bash) shell_cmd=/bin/bash ;;
	*)
		cp "$SH" "$R/system/bin/sh"
		shell_cmd=/system/bin/sh
		;;
	esac
	[ -e /lib64 ] && lib64=(--ro-bind /lib64 /lib64)
	bwrap --ro-bind /usr /usr --ro-bind /bin /bin --ro-bind /lib /lib \
		"${lib64[@]}" --ro-bind /etc /etc \
		--bind "$R/system" /system --bind "$R/proc" /proc --bind "$R/sys" /sys \
		--bind "$R/data" /data --bind "$R/mnt" /mnt --bind "$R/boot" /boot \
		--bind "$R/tmp" /tmp --ro-bind "$R/toy" /toy --dev /dev \
		--setenv PATH /toy/bin:/system/bin:/usr/bin:/bin --setenv LD_LIBRARY_PATH /toy/lib64 \
		"${boot_env[@]}" --setenv REPORT_BOOT_WAIT 10 --setenv REPORT_BOOT_LOCK /tmp/bootlock \
		--setenv REPORT_BOOT_LOCK_WAIT "${LOCK_WAIT:-30}" \
		--setenv REPORT_SETTLE 0 --setenv REPORT_LOCK /tmp/lock \
		"$shell_cmd" /system/etc/andr36oid-report.sh "$@" >"$T/out" 2>&1
	echo $? >"$T/rc"
}

# the saved zip, unpacked into $T/x
unpack() {
	rm -rf "$T/x"
	mkdir -p "$T/x"
	Z=$(find "$R/boot/andr36oid-reports" "$R/mnt/media_rw/ABCD-1234/andr36oid-reports" \
		"$R/data/media/0/Download/andr36oid-reports" -name 'report-*.zip' 2>/dev/null | head -n 1)
	[ -n "$Z" ] && unzip -q "$Z" -d "$T/x"
}

has() { grep -r -q -- "$1" "$T/x"; }
hasnt() { ! grep -r -q -- "$1" "$T/x"; }

# ---- 1. from the page, BOOT has room ----
make_root
run_report
check "app run exit 0 ($(cat "$T/out"))" [ "$(cat "$T/rc")" = 0 ]
check "status says BOOT" grep -q '^done BOOT/andr36oid-reports/report-.*\.zip$' "$R/data/misc/andr36oid-report/status"
check "zip on BOOT" unpack
check "zip has README.txt" [ -f "$(echo "$T"/x/report-*/README.txt)" ]
for f in summary system logcat kernel crashes power devices apps roms; do
	check "zip has $f.txt" [ -f "$(echo "$T"/x/report-*/$f.txt)" ]
done
check "pstore copy" [ -f "$(echo "$T"/x/report-*/previous-start-console-ramoops-0.txt)" ]
check "useful log line kept" has "joypad keys dead KEEPME"
check "hotfix status kept" has HOTFIXSTATUSKEEP
check "hotfix source kept" has 'HOTFIXSTATUSKEEP.*EASYROMS'
check "hotfix log kept" has HOTFIXLOGKEEP
check "kernel log kept" has KEEPKERNEL
check "USB check result kept" has "0bda:885a  NOT_SUPPORTED USBCHECKKEEP"
check "pstore kept" has KEEPPSTORE
check "input devices kept" has "odroidgo3-joypad"
check "DT model" has "Device tree model: Powkiddy R36S"
check "DT compatible" has "rockchip,rk3326-odroid-go2 rockchip,rk3326"
check "DTB from boot.ini" has "DTB loaded by boot.ini: rk3326-r36s-android.dtb"
check "DTB matches Panel 4" has "same as: Panels/Panel 4/rk3326-r36s-android.dtb"
check "DTB not Panel 1" sh -c "! grep -r 'same as:.*Panel 1' '$T/x'"
check "hwrev" has "Board revision (U-Boot hwrev): 3"
check "CID masked" has "035344534336344780xxxxxxxx016axx"
check "CID serial gone" hasnt "12345678"
check "short CID refused" has "(not a CID)"
check "eMMC listed" has "== built-in eMMC mmcblk2: 15269888 sectors, read-only 1"
check "eMMC partition name" has "mmcblk2p1 start 16384 size 8192 name uboot"
check "eMMC file system" has 'mmcblk2p2 start 32768 size 4194304 name rootfs LABEL="STOCKROOT" TYPE="ext4"'
check "eMMC boot area" has "mmcblk2boot0 size 8192"
check "eMMC UUID left out" hasnt EMMCUUIDSECRET
check "SD card not taken for the eMMC" hasnt "built-in eMMC mmcblk0"
check "ROM counts" has "^snes: 3$"
check "ROM counts gba" has "^gba: 1$"
check "other folders count" has "other folders: 1"
check "top files count" has "files at the top: 1"
check "EASYROMS in summary of ROMs" has "EASYROMS: "
# personal things
for s in HomeNetSecret OtherNetSecret CafeSecret ExtraNetSecret PstoreNetSecret hunter2secret \
	"Super Secret Game" "Private Photo" alice@example.com bob.smith "SecretFolder" \
	SERIALSECRET1 SERIALSECRET2 SERIALSECRET3 SECRETSERIALFILE kennys-secret-console \
	"Kenny Secret" "Secret Game" "Secret Tomb" "My Secret Folder" diary secret-top \
	"dd:ee" "44:55" "DD:EE:FF"; do
	check "no '$s' in the zip" hasnt "$s"
done
check "dropbox name kept (not an e-mail)" has "system_app_crash@1727000000000.txt"
check "MAC maker half kept" has "02:aa:bb:xx:xx:xx"
check "serial console tweak not hidden" has "serial_console\]: \[off\]"
check "serial debugger line not hidden" has "serial_debugger: disabled"
check "path cut, rest of quoted line kept" has '"/storage/emulated/0/<hidden>" ok, next'
check "content uri cut" has "content://com.android.externalstorage.documents/<hidden> ok"
check "BOOT result not written from the page" [ ! -e "$R/boot/make-report-result.txt" ]
check "no wifi dump" sh -c "! grep -q 'dumpsys wifi' '$R/tmp/calls'"
check "no bluetooth dump" sh -c "! grep -q 'dumpsys bluetooth' '$R/tmp/calls'"
check "no connectivity dump" sh -c "! grep -q 'dumpsys connectivity' '$R/tmp/calls'"
check "work folder removed" sh -c "! ls -d '$R'/data/misc/andr36oid-report/report-* 2>/dev/null"
check "lock released" [ ! -e "$R/tmp/lock" ]
SIZE=$(wc -c <"$Z")
echo "zip size in the fake root: $SIZE bytes"
cp "$Z" "$T/first.zip"

# ---- 2. BOOT keeps only the newest 3 ----
for i in 1 2 3 4; do
	touch -d "2020-01-0$i" "$R/boot/andr36oid-reports/report-2020010$i-000000.zip"
done
sleep 1
run_report
check "4 old + 1 new -> 3 kept" [ "$(find "$R/boot/andr36oid-reports" -name 'report-*.zip' | wc -l)" = 3 ]
check "the new one is kept" [ -n "$(find "$R/boot/andr36oid-reports" -name 'report-2026*.zip' -o -name "report-$(date +%Y)*.zip" | head -n 1)" ]

# ---- 2b. the clock is behind (empty battery): the new report is kept all the same,
# the others go by name, not by file time ----
make_root
mkdir -p "$R/boot/andr36oid-reports"
for i in 1 2 3; do
	touch "$R/boot/andr36oid-reports/report-2099010$i-000000.zip"
done
# file times the other way round than the names
touch -d 2020-01-01 "$R/boot/andr36oid-reports/report-20990103-000000.zip"
run_report
check "clock behind: the new one is kept" [ -n "$(find "$R/boot/andr36oid-reports" -name "report-$(date +%Y)*.zip" | head -n 1)" ]
check "clock behind: 3 kept" [ "$(find "$R/boot/andr36oid-reports" -name 'report-*.zip' | wc -l)" = 3 ]
check "clock behind: the oldest name went" [ ! -e "$R/boot/andr36oid-reports/report-20990101-000000.zip" ]
check "clock behind: newest name kept despite its old file time" [ -e "$R/boot/andr36oid-reports/report-20990103-000000.zip" ]

# ---- 2c. a .tmp copy left by a power cut is removed at the next save ----
make_root
mkdir -p "$R/boot/andr36oid-reports"
touch "$R/boot/andr36oid-reports/report-20200101-000000.zip.tmp" \
	"$R/boot/andr36oid-reports/start-log-20200101-000000.zip.tmp" "$R/boot/andr36oid-reports/notes.tmp"
run_report
check "stale tmp: report .tmp removed" [ ! -e "$R/boot/andr36oid-reports/report-20200101-000000.zip.tmp" ]
check "stale tmp: start log .tmp removed" [ ! -e "$R/boot/andr36oid-reports/start-log-20200101-000000.zip.tmp" ]
check "stale tmp: other files kept" [ -e "$R/boot/andr36oid-reports/notes.tmp" ]

# ---- 3. BOOT too full -> EASYROMS ----
make_root
echo 20000 >"$R/tmp/boot-free-kb"
run_report
check "full BOOT: status says EASYROMS" grep -q '^done EASYROMS/andr36oid-reports/report-.*\.zip$' "$R/data/misc/andr36oid-report/status"
check "full BOOT: nothing on BOOT" [ ! -d "$R/boot/andr36oid-reports" ]

# The selected data card has different numbers; another card has the legacy id
make_root
echo 20000 >"$R/tmp/boot-free-kb"
printf 'sys.andr36oid.easyroms.ready=1\nsys.andr36oid.easyroms.id=public:179,103\n' >>"$R/tmp/props"
printf 'public:179,7 mounted ABCD-1234\npublic:179,103 mounted DCBA-4321\n' >"$R/tmp/volumes"
mkdir -p "$R/mnt/media_rw/DCBA-4321"
run_report
check "selected EASYROMS: saved on the data card" [ "$(find "$R/mnt/media_rw/DCBA-4321" -name 'report-*.zip' | wc -l)" = 1 ]
check "selected EASYROMS: other card untouched" [ ! -d "$R/mnt/media_rw/ABCD-1234/andr36oid-reports" ]

make_root
echo 20000 >"$R/tmp/boot-free-kb"
echo sys.andr36oid.easyroms.ready=1 >>"$R/tmp/props"
run_report
check "no selected EASYROMS: Download despite the other card" grep -q '^done Download/' "$R/data/misc/andr36oid-report/status"
check "no selected EASYROMS: other card untouched" [ ! -d "$R/mnt/media_rw/ABCD-1234/andr36oid-reports" ]

make_root
echo 20000 >"$R/tmp/boot-free-kb"
printf 'sys.andr36oid.easyroms.ready=1\nsys.andr36oid.easyroms.id=public:179,103\n' >>"$R/tmp/props"
printf 'public:179,7 mounted ABCD-1234\npublic:179,103 checking DCBA-4321\n' >"$R/tmp/volumes"
run_report
check "selected EASYROMS not mounted: Download" grep -q '^done Download/' "$R/data/misc/andr36oid-report/status"
check "selected EASYROMS not mounted: other card untouched" [ ! -d "$R/mnt/media_rw/ABCD-1234/andr36oid-reports" ]

# ---- 4. BOOT read-only and no EASYROMS -> Download ----
make_root
touch "$R/boot/.readonly" "$R/tmp/no-easyroms"
run_report
check "read-only BOOT, no EASYROMS: Download" grep -q '^done Download/andr36oid-reports/report-.*\.zip$' "$R/data/misc/andr36oid-report/status"
unpack
check "Download: roms.txt says not mounted" has "EASYROMS is not mounted"

# ---- 5. start-up run without a make-report file: nothing ----
make_root
run_report boot
check "boot, no file: exit 0" [ "$(cat "$T/rc")" = 0 ]
check "boot, no file: no report" [ ! -d "$R/boot/andr36oid-reports" ]
check "boot, no file: no status" [ ! -e "$R/data/misc/andr36oid-report/status" ]
check "boot, no file: no commands run" [ ! -s "$R/tmp/calls" ]

# ---- 6. start-up run with make-report.txt (Windows name, CRLF note) ----
make_root
printf 'Buttons dead on my EE clone\r\n' >"$R/boot/Make-Report.txt.txt"
run_report boot
check "boot: exit 0 ($(cat "$T/out"))" [ "$(cat "$T/rc")" = 0 ]
check "boot: trigger deleted" [ ! -e "$R/boot/Make-Report.txt.txt" ]
check "boot: report on BOOT" unpack
check "boot: note kept" has "Buttons dead on my EE clone"
check "boot: no CR in note" sh -c "! grep -r -q \$'\r' '$T'/x/report-*/summary.txt"
check "boot: started by boot" has "Started by: boot"
check "boot: result file" grep -q '^Problem report saved: BOOT/andr36oid-reports/report-.*\.zip' "$R/boot/make-report-result.txt"
# run again: the file is gone, so nothing new
N1=$(find "$R/boot/andr36oid-reports" -name '*.zip' | wc -l)
run_report boot
N2=$(find "$R/boot/andr36oid-reports" -name '*.zip' | wc -l)
check "boot: only once" [ "$N1" = "$N2" ]

# ---- 7. start-up run, BOOT read-only: the file stays, no report ----
make_root
touch "$R/boot/make-report.txt" "$R/boot/.readonly"
run_report boot
check "boot ro: file kept" [ -e "$R/boot/make-report.txt" ]
check "boot ro: no report" [ ! -d "$R/boot/andr36oid-reports" ]
check "boot ro: said so" grep -q "read-only" "$R/tmp/log"

# ---- 8. start-up run, the start never finishes: waits, then saves anyway ----
make_root
sed -i 's/^sys.boot_completed=1/sys.boot_completed=0/' "$R/tmp/props"
touch "$R/boot/make_report"
START=$(date +%s)
run_report boot
END=$(date +%s)
check "no boot_completed: waited ~10 s" [ $((END - START)) -ge 9 ]
check "no boot_completed: saved" grep -q '^Problem report saved' "$R/boot/make-report-result.txt"

# ---- 9. a second report while one runs: refused from the page ----
make_root
mkdir "$R/tmp/lock"
run_report
check "locked: exit 1" [ "$(cat "$T/rc")" = 1 ]
check "locked: lock not taken away" [ -d "$R/tmp/lock" ]
check "locked: the page is told" grep -qx 'error busy' "$R/data/misc/andr36oid-report/status"

# ---- 10. the mounts: BOOT read-only while the report is made, writable only to
# save it, and never mounted without the BOOT lock ----
make_root
touch "$R/tmp/bootdev"
REAL_MOUNT=1 run_report
check "mounts: saved on BOOT ($(cat "$T/out"))" grep -q '^done BOOT/' "$R/data/misc/andr36oid-report/status"
check "mounts: BOOT mounted read-only" grep -q '^mount -t vfat -o ro,noatime /tmp/bootdev /boot lock=held$' "$R/tmp/mounts"
check "mounts: never mounted read-write" sh -c "! grep -q 'o rw' '$R/tmp/mounts'"
check "mounts: always with the lock" sh -c "! grep -q 'lock=free' '$R/tmp/mounts'"
check "mounts: ro, closed while collecting, then ro + rw to save" \
	[ "$(sed 's/ lock=.*//; s/ \/tmp\/bootdev//' "$R/tmp/mounts" | tr '\n' ,)" = \
	"mount -t vfat -o ro,noatime /boot,umount /boot,mount -t vfat -o ro,noatime /boot,mount -o remount,rw /boot,umount /boot," ]
check "mounts: lock released" [ ! -e "$R/tmp/bootlock" ]

# ---- 11. start-up run, real mount code: rw only to delete the trigger and write the result ----
make_root
touch "$R/tmp/bootdev" "$R/boot/make-report.txt"
REAL_MOUNT=1 run_report boot
check "boot mounts: saved ($(cat "$T/out"))" grep -q '^Problem report saved: BOOT/' "$R/boot/make-report-result.txt"
check "boot mounts: always with the lock" sh -c "! grep -q 'lock=free' '$R/tmp/mounts'"
check "boot mounts: every mount unmounted" [ "$(grep -c '^mount -t' "$R/tmp/mounts")" = "$(grep -c '^umount /boot' "$R/tmp/mounts")" ]

# ---- 12. another helper holds BOOT: the report goes to EASYROMS ----
make_root
sleep 60 &
HOLDER=$!
mkdir "$R/tmp/bootlock"
echo "$HOLDER" >"$R/tmp/bootlock/pid"
LOCK_WAIT=1 run_report
kill "$HOLDER" 2>/dev/null
wait "$HOLDER" 2>/dev/null
check "BOOT busy: saved on EASYROMS" grep -q '^done EASYROMS/' "$R/data/misc/andr36oid-report/status"
check "BOOT busy: said so" grep -q "BOOT is busy" "$R/tmp/log"
check "BOOT busy: the holder's lock kept" [ "$(cat "$R/tmp/bootlock/pid")" = "$HOLDER" ]

# ---- 13. a lock left by a helper that was killed, and its mount: taken over ----
make_root
touch "$R/tmp/bootdev"
sh -c 'exit 0' &
DEAD=$!
wait "$DEAD"
mkdir "$R/tmp/bootlock"
echo "$DEAD" >"$R/tmp/bootlock/pid"
echo "/dev/block/mmcblk0p1 /mnt/andr36oid-cardsetup-boot vfat rw,noatime 0 0" >>"$R/proc/mounts"
LOCK_WAIT=1 REAL_MOUNT=1 run_report
check "stale lock: saved on BOOT" grep -q '^done BOOT/' "$R/data/misc/andr36oid-report/status"
check "stale lock: the killed helper's mount taken away" grep -q '^umount /mnt/andr36oid-cardsetup-boot lock=held' "$R/tmp/mounts"
check "stale lock: released" [ ! -e "$R/tmp/bootlock" ]

echo "passed $PASS, failed $FAIL (shell: $SH)"
[ "$FAIL" = 0 ]
