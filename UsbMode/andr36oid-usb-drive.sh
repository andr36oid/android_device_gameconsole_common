#!/system/bin/sh
# shellcheck shell=ksh
# Own g1 only while storage is lent to the computer. The journal survives a
# helper crash; the app's process identity is a lease, never a saved preference.

SYS=${USB_DRIVE_SYS:-/sys/class/block}
DEV=${USB_DRIVE_DEV:-/dev/block}
G=${USB_DRIVE_GADGET:-/config/usb_gadget/g1}
STATE=${USB_DRIVE_STATE:-/data/misc/andr36oid-usb-drive}
MOUNTS=${USB_DRIVE_MOUNTS:-/proc/self/mountinfo}
FSTABS=${USB_DRIVE_FSTABS:-/vendor/etc}
OTG=${USB_DRIVE_OTG:-/sys/devices/platform/ff2c0000.syscon/ff2c0000.syscon:usb2-phy@100/otg_mode}
POWER=${USB_DRIVE_POWER:-/sys/class/power_supply}
DT=${USB_DRIVE_DT:-/proc/device-tree}
BOOT_ID=${USB_DRIVE_BOOT_ID:-/proc/sys/kernel/random/boot_id}
P=sys.andr36oid.usbdrive
F=$G/functions/mass_storage.andr36oid

put() { printf '%s\n' "$2" >"$1"; }
prop() { getprop "$P.$1"; }
status() { setprop "$P.state" "$1"; }
block_node() { [ -b "$DEV/$1" ]; }

nodes() {
	printf '%s\n' "$target"
	if [ "$target" = mmcblk2 ]; then
		printf '%s\n' mmcblk2boot0 mmcblk2boot1
	fi
	case $target in *p*) ;; *)
		for n in "$SYS/$target"p*; do
			[ -f "$n/partition" ] && printf '%s\n' "${n##*/}"
		done ;;
	esac
}

# Refuse unknown layouts. Protect every Android partition from both the real
# mount device numbers and the active fstab's resolved links, in either slot.
layout() {
	protected=' '
	system=0 data=0
	while read -r _ _ nums _ point rest; do
		case $point in /|/system|/vendor|/data|/metadata|/cache) ;; *) continue ;; esac
		for n in "$SYS"/mmcblk*p*; do
			[ "$(cat "$n/dev" 2>/dev/null)" = "$nums" ] || continue
			protect "${n##*/}" "$point"
		done
	done <"$MOUNTS"
	suffix=$(getprop ro.boot.fstab_suffix)
	case $suffix in ''|rk30board) suffix=rk30board ;; emmc|emmcsd) ;; *) return 1 ;; esac
	[ -r "$FSTABS/fstab.$suffix" ] || return 1
	while read -r source point rest; do
		case $source in "$DEV"/*) ;; *) continue ;; esac
		case $point in /|/system|/vendor|/data|/metadata|/cache) ;; *) continue ;; esac
		n=$(readlink -f "$source") || continue
		n=${n##*/}
		[ -f "$SYS/$n/partition" ] && protect "$n" "$point"
	done <"$FSTABS/fstab.$suffix"
	[ "$system$data" = 11 ]
}

protect() {
	case $1 in mmcblk*p[0-9]*) ;; *) return ;; esac
	protected="$protected${1%p*} "
	case $2 in /|/system) system=1 ;; /data) data=1 ;; esac
}

charging() {
	blocks=1
	for port in "$DT"/*/usb2-phy@100/otg-port; do
		[ -d "$port" ] || continue
		[ -e "$port/rockchip,host-while-charging" ] || blocks=0
		break
	done
	[ "$blocks" = 1 ] || return 1
	for supply in "$POWER"/*; do
		[ "$(cat "$supply/type" 2>/dev/null)" = Battery ] && continue
		[ "$(cat "$supply/online" 2>/dev/null)" = 1 ] && return 0
	done
	return 1
}

lease() {
	[ "$(prop request)" = start ] && [ "$(cat "$OTG")" = peripheral ] || return 1
	! charging || return 1
	[ "$(cat "$SYS/$disk/device/cid" 2>/dev/null)" = "$cid" ] || return 1
	[ "$(awk '{print $22}' "/proc/$owner/stat" 2>/dev/null)" = "$owner_start" ]
}

validate() {
	target=$(prop target) cid=$(prop cid) ro=$(prop ro) owner=$(prop owner)
	case $target in mmcblk[0-9]|mmcblk[0-9]p7) ;; *) return 1 ;; esac
	case $ro in 0|1) ;; *) return 1 ;; esac
	case $owner in ''|*[!0-9]*) return 1 ;; esac
	owner_start=$(awk '{print $22}' "/proc/$owner/stat") || return 1
	[ -n "$owner_start" ] || return 1
	disk=${target%p7}
	block_node "$target" || return 1
	[ "$(cat "$SYS/$disk/device/cid")" = "$cid" ] || return 1
	[ "${#cid}" = 32 ] || return 1
	case $cid in *[!0-9a-fA-F]*) return 1 ;; esac
	layout || return 1
	forced=0
	case $disk in
	mmcblk2)
		[ -e "$SYS/$disk/andr36oid_unlock" ] || return 1
		[ "$target" = "$disk" ] || return 1
		block_node "${disk}boot0" && block_node "${disk}boot1" || return 1
		case $protected in *" $disk "*) forced=1; ro=1 ;; esac
		;;
	*)
		path=$(readlink -f "$SYS/$disk") || return 1
		case $path in */ff370000.dwmmc/*|*/ff380000.dwmmc/*) ;; *) return 1 ;; esac
		case $protected in *" $disk "*)
			[ "$target" = "${disk}p7" ] || return 1
			[ "$(getprop sys.andr36oid.easyroms.ready)" = 1 ] || return 1
			[ "$(getprop sys.andr36oid.easyroms.dev)" = "$target" ] || return 1
			[ "$(cat "$SYS/$target/partition")" = 7 ] || return 1
			partname=$(sed -n 's/^PARTNAME=//p' "$SYS/$target/uevent")
			case $partname in ''|EASYROMS) ;; *) return 1 ;; esac
			;;
		*) [ "$target" = "$disk" ] || return 1 ;;
		esac
		;;
	esac
	# Private volumes are never lent, even if they are currently unmounted.
	sm list-volumes all >"$STATE/volumes" || return 1
	for n in $(nodes); do
		partname=$(sed -n 's/^PARTNAME=//p' "$SYS/$n/uevent" 2>/dev/null)
		if [ "$disk" != mmcblk2 ]; then
			case $partname in android_expand|android_meta) return 1 ;; esac
		fi
		if [ "$disk" != mmcblk2 ] && [ "$target" = "$disk" ]; then
			case $partname in system|vendor|userdata) return 1 ;; esac
		fi
		nums=$(cat "$SYS/$n/dev") || return 1
		id=private:$(printf '%s' "$nums" | tr : ,)
		awk -v id="$id" '$1==id { found=1 } END { exit !found }' "$STATE/volumes" && return 1
		for holder in "$SYS/$n/holders"/*; do
			[ ! -e "$holder" ] || [ "$forced" = 1 ] || return 1
		done
	done
	lease
}

mounted() {
	for n in $(nodes); do
		nums=$(cat "$SYS/$n/dev") || return 0
		awk -v dev="$nums" -v forced="$forced" '
		$3==dev {
			if (forced==1 && ($5=="/" || $5=="/system" || $5=="/vendor" ||
			    $5=="/data" || $5=="/metadata" || $5=="/cache")) next
			found=1
		} END { exit !found }' "$MOUNTS" && return 0
	done
	return 1
}

release_storage() {
	for n in $(nodes); do
		nums=$(cat "$SYS/$n/dev") || return 1
		id=public:$(printf '%s' "$nums" | tr : ,)
		if awk -v id="$id" '$1==id && ($2=="mounted" || $2=="mounted_ro" || $2=="checking") { found=1 }
		    END { exit !found }' "$STATE/volumes"; then
			# Journal first: an interrupted unmount must still be restored.
			printf '%s\n' "$id" >>"$STATE/restore"
			sm unmount "$id" || return 1
		fi
	done
	i=0
	while mounted; do
		lease || return 1
		i=$((i + 1)); [ "$i" -lt 50 ] || return 1
		sleep 0.2
	done
	lease
}

adb_on() {
	case ,$(getprop persist.sys.usb.config), in *,adb,*) return 0 ;; *) return 1 ;; esac
}

bind_drive() {
	want_adb=0
	if adb_on; then
		want_adb=1
		setprop ctl.start adbd
		[ "$(getprop sys.usb.ffs.ready)" = 1 ] || return 0
	fi
	udc=$(getprop sys.usb.controller)
	[ -n "$udc" ] || return 1
	if [ "$linked_adb" != "$want_adb" ]; then
		put "$G/UDC" none || return 1
		rm -f "$G/configs/b.1/f2"
		if [ "$want_adb" = 1 ]; then
			ln -s "$G/functions/ffs.adb" "$G/configs/b.1/f2" || return 1
		else
			setprop ctl.stop adbd
		fi
		linked_adb=$want_adb
	fi
	[ "$(cat "$G/UDC")" = "$udc" ] || put "$G/UDC" "$udc"
}

start_drive() {
	status preparing
	validate || return 1
	release_storage || return 1
	# 2 suspends both the configfs=1 and legacy configfs=0 init actions.
	# The wildcard action in our usb rc acknowledges UsbDeviceManager requests.
	cat "$G/idProduct" >"$STATE/product" || return 1
	cat "$G/os_desc/use" >"$STATE/os_desc" || return 1
	touch "$STATE/owned"
	setprop "$P.gated" 0
	setprop sys.usb.configfs 2 || return 1
	i=0
	while [ "$(prop gated)" != 1 ]; do
		lease || return 1
		i=$((i + 1)); [ "$i" -lt 50 ] || return 1
		sleep 0.1
	done
	put "$G/UDC" none || return 1
	rm -f "$G/configs/b.1/f1" "$G/configs/b.1/f2" "$G/configs/b.1/f3"
	put "$G/os_desc/use" 0 || return 1
	put "$G/bDeviceClass" 0 || return 1
	put "$G/bDeviceSubClass" 0 || return 1
	put "$G/bDeviceProtocol" 0 || return 1
	put "$G/idProduct" 0x4ee0 || return 1
	put "$G/configs/b.1/strings/0x409/configuration" mass_storage || return 1
	mkdir "$F" || return 1
	luns=$target
	if [ "$disk" = mmcblk2 ]; then
		luns="mmcblk2 mmcblk2boot0 mmcblk2boot1"
		if [ "$ro" = 0 ]; then
			put "$SYS/mmcblk2/andr36oid_unlock" "unlock $cid" || return 1
			[ "$(cat "$SYS/mmcblk2/andr36oid_unlock")" = unlocked ] || return 1
			put "$SYS/mmcblk2boot0/force_ro" 0 || return 1
			put "$SYS/mmcblk2boot1/force_ro" 0 || return 1
		fi
	fi
	i=0
	for n in $luns; do
		[ "$i" = 0 ] || mkdir "$F/lun.$i" || return 1
		put "$F/lun.$i/removable" 1 || return 1
		put "$F/lun.$i/ro" "$ro" || return 1
		put "$F/lun.$i/file" "$DEV/$n" || return 1
		i=$((i + 1))
	done
	lease && ! mounted || return 1
	case $target in *p*) ;; *)
		[ "$forced" = 1 ] || put "$STATE/rescan" "$target" || return 1 ;;
	esac
	ln -s "$F" "$G/configs/b.1/f1" || return 1
	linked_adb=-1
	bind_drive || return 1
	status sharing
}

close_drive() {
	# Do not hand anything back until the LUNs have closed their backing files.
	if [ -e "$STATE/owned" ] || [ -d "$F" ]; then
		put "$G/UDC" none || return 1
		rm -f "$G/configs/b.1/f1" "$G/configs/b.1/f2" "$G/configs/b.1/f3"
		for lun in "$F"/lun.*; do
			[ ! -d "$lun" ] || put "$lun/file" '' || return 1
		done
		for lun in "$F/lun.2" "$F/lun.1"; do
			[ ! -d "$lun" ] || rmdir "$lun" || return 1
		done
		[ ! -d "$F" ] || rmdir "$F" || return 1
	fi
	return 0
}

cleanup() {
	status stopping
	closed=1
	close_drive || closed=0
	# Flush writes accepted before detach while the eMMC is still unlocked.
	sync
	# Even partial setup or a helper restart must close the write window.
	if [ -e "$SYS/mmcblk2/andr36oid_unlock" ]; then
		put "$SYS/mmcblk2/andr36oid_unlock" lock || return 1
		for boot in 0 1; do
			[ ! -e "$SYS/mmcblk2boot$boot/force_ro" ] ||
				put "$SYS/mmcblk2boot$boot/force_ro" 1 || return 1
		done
	fi
	[ "$closed" = 1 ] || return 1
	if [ -e "$STATE/owned" ]; then
		put "$G/idProduct" "$(cat "$STATE/product")" || return 1
		put "$G/os_desc/use" "$(cat "$STATE/os_desc")" || return 1
		# Returning configfs to 1 retriggers Android's latest requested config.
		setprop sys.usb.configfs 1 || return 1
		rm -f "$STATE/owned"
	fi
	# Re-read a changed partition table before remounting any old volume.
	if [ -f "$STATE/rescan" ]; then
		n=$(cat "$STATE/rescan")
		blockdev --rereadpt "$DEV/$n" || true
		rm -f "$STATE/rescan"
	fi
	if [ -f "$STATE/restore" ]; then
		while read -r id; do
			# The old volume might no longer exist after repartitioning.
			sm mount "$id" || true
		done <"$STATE/restore"
		rm -f "$STATE/restore"
	fi
	return 0
}

prepare_state() {
	umask 022
	mkdir -p "$STATE" || return 1
	chmod 0755 "$STATE"
	boot_id=$(cat "$BOOT_ID") || return 1
	if [ "$(cat "$STATE/boot_id" 2>/dev/null)" != "$boot_id" ]; then
		# A full restart has fresh configfs and vold; never replay old disk IDs.
		rm -f "$STATE/owned" "$STATE/restore" "$STATE/rescan" "$STATE/product" "$STATE/os_desc"
	fi
	put "$STATE/boot_id" "$boot_id"
}

main() {
	prepare_state || exit 1
	setprop "$P.request" stop
	cleanup || exit 1
	status idle
	trap 'setprop "$P.request" stop; cleanup; exit' HUP INT TERM
	while :; do
		if [ "$(prop request)" = start ]; then
			if start_drive; then
				while lease && ! mounted && [ "$(getprop sys.usb.configfs)" = 2 ]; do
					bind_drive || break
					sleep 0.2
				done
				result=idle
			else
				result=error
			fi
			setprop "$P.request" stop
			cleanup || exit 1
			status "$result"
		fi
		sleep 0.2
	done
}

[ "${USB_DRIVE_TEST:-0}" = 1 ] || main
