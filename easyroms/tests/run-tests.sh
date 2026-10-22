#!/bin/bash
# Host test of init's EASYROMS selection. Both cards may have the same label.
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin"
TOYBOX=${TOYBOX:-/andr36oid/src/prebuilts/build-tools/linux-x86/bin/toybox}
if [ -x "$TOYBOX" ]; then
	ln -s "$(dirname "$TOYBOX")/../lib64" "$T/lib64"
	for cmd in cat readlink sed; do ln -s "$TOYBOX" "$T/bin/$cmd"; done
fi
cat >"$T/bin/getprop" <<'EOF'
#!/bin/sh
case $1 in
ro.boot.fstab_suffix) printf '%s\n' "$SUFFIX" ;;
ro.boot.boot_devices) printf '%s\n' "$BOOT_DEVICES" ;;
esac
exit 0
EOF
cat >"$T/bin/setprop" <<'EOF'
#!/bin/sh
printf '%s=%s\n' "$1" "$2" >>"$R/props"
EOF
chmod +x "$T/bin/getprop" "$T/bin/setprop"
export PATH="$T/bin:$PATH"
export R SUFFIX BOOT_DEVICES

check() {
	if [ "$2" != "$3" ]; then
		echo "FAIL [$sh] $1: got '$3', want '$2'"
		exit 1
	fi
}

card() { # <disk> <major:minor>
	mkdir -p "$R/sys/$1/device" "$R/sys/${1}p6" "$R/sys/${1}p7"
	echo 6 >"$R/sys/${1}p6/partition"
	echo 7 >"$R/sys/${1}p7/partition"
	echo "$2" >"$R/sys/${1}p7/dev"
	printf 'DEVNAME=%sp7\nPARTN=7\nPARTNAME=EASYROMS\n' "$1" >"$R/sys/${1}p7/uevent"
	: >"$R/dev/${1}p7"
	: >"$R/dev/${1}p6"
}

run() { # <expected disk or empty> <major,minor>
	: >"$R/props"
	EASYROMS_SYS="$R/sys" EASYROMS_DEV="$R/dev" $sh "$HERE/andr36oid-easyroms.sh"
	local id="" part="" dev=""
	if [ -n "$1" ]; then
		id=public:$2
		dev=${1}p7
		part=/sys/class/block/$dev
	fi
	check id "sys.andr36oid.easyroms.id=$id" "$(sed -n '1p' "$R/props")"
	check dev "sys.andr36oid.easyroms.dev=$dev" "$(sed -n '2p' "$R/props")"
	check part "sys.andr36oid.easyroms.part=$part" "$(sed -n '3p' "$R/props")"
	check ready-last sys.andr36oid.easyroms.ready=1 "$(sed -n '4p' "$R/props")"
}

for sh in dash bash mksh "busybox sh"; do
	command -v "${sh%% *}" >/dev/null || continue
	R=$T/${sh%% *}
	mkdir -p "$R/dev/platform/ff370000.dwmmc/by-name" "$R/dev/by-name"
	card mmcblk0 179:103
	card mmcblk1 179:7
	ln -s "$R/dev/mmcblk0p6" "$R/dev/platform/ff370000.dwmmc/by-name/userdata"
	SUFFIX=
	BOOT_DEVICES=ff370000.dwmmc
	run mmcblk0 179,103
	SUFFIX=rk30board
	run mmcblk0 179,103

	# Data card selected by GUID, with another andr36oid card in the other slot
	SUFFIX=emmcsd
	ln -s "$R/dev/mmcblk1p6" "$R/dev/by-name/a36data"
	run mmcblk1 179,7
	ln -sf "$R/dev/mmcblk0p6" "$R/dev/by-name/a36data"
	run mmcblk0 179,103
	rm "$R/dev/by-name/a36data"
	run "" ""

	# A pure eMMC install never treats either SD card as its EASYROMS
	SUFFIX=emmc
	run "" ""
	SUFFIX=unknown
	run "" ""
	SUFFIX=
	BOOT_DEVICES=ff390000.dwmmc
	run "" ""
	BOOT_DEVICES=
	run mmcblk0 179,103

	SUFFIX=
	# Missing p7 (.noroms), wrong partition or bad dev numbers
	rm "$R/sys/mmcblk0p7/partition"
	run "" ""
	echo 8 >"$R/sys/mmcblk0p7/partition"
	run "" ""
	echo 7 >"$R/sys/mmcblk0p7/partition"
	for bad in '' 179 179: 179:7:1 a:7 179:x; do
		echo "$bad" >"$R/sys/mmcblk0p7/dev"
		run "" ""
	done
	echo 179:103 >"$R/sys/mmcblk0p7/dev"
	# The GPT name confirms our partition; unnamed partitions are also accepted.
	for bad in other 'EASYROMS old'; do
		printf 'DEVNAME=mmcblk0p7\nPARTN=7\nPARTNAME=%s\n' "$bad" >"$R/sys/mmcblk0p7/uevent"
		run "" ""
	done
	printf 'DEVNAME=mmcblk0p7\nPARTN=7\n' >"$R/sys/mmcblk0p7/uevent"
	run mmcblk0 179,103
	printf 'DEVNAME=mmcblk0p7\nPARTN=7\nPARTNAME=EASYROMS\n' >"$R/sys/mmcblk0p7/uevent"
	run mmcblk0 179,103
	echo "ALL PASSED ($sh)"
done
