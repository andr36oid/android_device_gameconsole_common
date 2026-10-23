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
MEDIA=${USB_DRIVE_MEDIA:-/mnt/media_rw}
OTG=${USB_DRIVE_OTG:-/sys/devices/platform/ff2c0000.syscon/ff2c0000.syscon:usb2-phy@100/otg_mode}
POWER=${USB_DRIVE_POWER:-/sys/class/power_supply}
DT=${USB_DRIVE_DT:-/proc/device-tree}
BOOT_ID=${USB_DRIVE_BOOT_ID:-/proc/sys/kernel/random/boot_id}
P=sys.andr36oid.usbdrive
F=$G/functions/mass_storage.andr36oid

error=
log_step() { log -t andr36oid-usb-drive "$*" || true; }
put() { printf '%s\n' "$2" >"$1" || { log_step "write failed: $1"; return 1; }; }
fail() {
	log_step "failed: $1"
	[ -n "$error" ] || error=$1
	setprop "$P.error" "$error"
	return 1
}
step() {
	log_step "$1"
	"$@" || { fail "$1"; return 1; }
}
prop() { getprop "$P.$1"; }
status() { log_step "state: $1"; setprop "$P.state" "$1"; }
block_node() { [ -b "$DEV/$1" ]; }

# Use vold's selected public volume and its actual mount, never a label or an
# app-supplied path. Keep this separate from the block export's unmount scope.
image_root() {
	local node numbers record volume_state uuid point partname
	[ "$(getprop sys.andr36oid.easyroms.ready)" = 1 ] || return 1
	node=$(getprop sys.andr36oid.easyroms.dev)
	case $node in mmcblk[0-9]p7) ;; *) return 1 ;; esac
	[ "$(cat "$SYS/$node/partition")" = 7 ] || return 1
	partname=$(sed -n 's/^PARTNAME=//p' "$SYS/$node/uevent")
	case $partname in ''|EASYROMS) ;; *) return 1 ;; esac
	# Confirm this is the active Android/data card, as for partition sharing.
	(layout && case $protected in *" ${node%p7} "*) ;; *) exit 1 ;; esac) || return 1
	numbers=$(cat "$SYS/$node/dev") || return 1
	case $numbers in ''|*[!0-9:]*) return 1 ;; esac
	sm list-volumes all >"$STATE/image-volumes" || return 1
	record=$(awk -v id="public:$(printf '%s' "$numbers" | tr : ,)" '
	    $1==id && ($2=="mounted" || $2=="mounted_ro") {print $2 " " $3}' "$STATE/image-volumes")
	volume_state=${record%% *} uuid=${record#* }
	case $uuid in ''|*[!a-zA-Z0-9_-]*) return 1 ;; esac
	point=$MEDIA/$uuid
	[ -d "$point" ] && [ "$(readlink -f "$point")" = "$point" ] || return 1
	# Escaped mount paths, bind roots and duplicate mounts are refused.
	easy_mount=$(awk -v dev="$numbers" -v point="$point" '
	    $3==dev && $4=="/" && $5==point {print $1}' "$MOUNTS")
	case $easy_mount in ''|*[!0-9]*) return 1 ;; esac
	easy_root=$point easy_disk=${node%p7}
	easy_cid=$(cat "$SYS/$easy_disk/device/cid") || return 1
	[ "${#easy_cid}" = 32 ] || return 1
	case $easy_cid in *[!0-9a-fA-F]*) return 1 ;; esac
	easy_ro=1
	if [ "$volume_state" = mounted ] && awk -v id="$easy_mount" '
	    $1==id && ("," $6 ",") ~ /,rw,/ {found=1} END {exit !found}' "$MOUNTS"; then
		easy_ro=0
	fi
}

image_name() {
	# One property holds the basename (Android's limit is 91 bytes).
	[ "${#1}" -le 91 ] || return 1
	case $1 in ''|*[!a-zA-Z0-9._\ -]*|[!a-zA-Z0-9]*|*..*) return 1 ;; esac
	case $1 in *.[iI][sS][oO]|*.[iI][mM][gG]) ;; *) return 1 ;; esac
}

image_info() {
	local path info size
	image_name "$1" || return 1
	path=$easy_root/isos/$1
	[ -d "$easy_root/isos" ] && [ ! -L "$easy_root/isos" ] || return 1
	[ -f "$path" ] && [ ! -L "$path" ] || return 1
	[ "$(readlink -f "$path")" = "$path" ] || return 1
	# A mount below EASYROMS must not redirect the directory or this file.
	awk -v dir="$easy_root/isos" '$5==dir || index($5,dir "/")==1 {found=1}
	    END {exit found}' "$MOUNTS" || return 1
	[ "$(stat -c %d "$path")" = "$(stat -c %d "$easy_root")" ] || return 1
	info=$(stat -c '%d:%i:%s' "$path") || return 1
	case $info in ''|*[!0-9:]*) return 1 ;; esac
	size=${info##*:}
	# mksh's test/arithmetic can wrap at 32 bits; image sizes need wider math.
	awk -v bytes="$size" 'BEGIN {exit !(bytes>0)}' || return 1
	printf '%s\n' "$info"
}

scan_images() {
	local path info
	if image_root; then
		printf 'ready\t%s\t%s\n' "$easy_cid" "$easy_ro" >"$STATE/images.tmp" || return 1
		for path in "$easy_root/isos/"*; do
			info=$(image_info "${path##*/}") || continue
			printf 'file\t%s\t%s\n' "$info" "${path##*/}" >>"$STATE/images.tmp" || return 1
		done
	else
		printf 'unavailable\n' >"$STATE/images.tmp" || return 1
	fi
	chmod 0644 "$STATE/images.tmp" && mv -f "$STATE/images.tmp" "$STATE/images"
}

check_image() {
	local info
	image_root || return 1
	[ "$easy_disk:$easy_cid:$easy_mount:$easy_root" = "$image_source" ] || return 1
	[ "$ro" = 1 ] || [ "$easy_ro" = 0 ] || return 1
	info=$(image_info "$image") || return 1
	[ "$info" = "$stamp" ]
}

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
	[ "$(prop request)" = start ] && [ "$(cat "$OTG")" = "$expected_role" ] || return 1
	! charging || return 1
	[ "$(cat "$SYS/$disk/device/cid" 2>/dev/null)" = "$cid" ] || return 1
	[ "$target" != file ] || check_image || return 1
	[ "$(awk '{print $22}' "/proc/$owner/stat" 2>/dev/null)" = "$owner_start" ]
}

validate() {
	expected_role=$(cat "$OTG") || return 1
	case $expected_role in host|peripheral|otg) ;; *) return 1 ;; esac
	target=$(prop target) cid=$(prop cid) ro=$(prop ro) owner=$(prop owner)
	case $target in file|mmcblk[0-9]|mmcblk[0-9]p7) ;; *) return 1 ;; esac
	case $ro in 0|1) ;; *) return 1 ;; esac
	case $owner in ''|*[!0-9]*) return 1 ;; esac
	owner_start=$(awk '{print $22}' "/proc/$owner/stat") || return 1
	[ -n "$owner_start" ] || return 1
	if [ "$target" = file ]; then
		image=$(prop image) stamp=$(prop stamp) cdrom=$(prop cdrom)
		case $cdrom in 0) ;; 1) ro=1 ;; *) return 1 ;; esac
		image_root || return 1
		disk=$easy_disk forced=0
		[ "$cid" = "$easy_cid" ] || return 1
		image_source=$easy_disk:$easy_cid:$easy_mount:$easy_root
		if [ "$cdrom" = 1 ]; then
			# This kernel caps CD media at 256*60*75-1 2048-byte blocks.
			# Refuse truncation; larger images can use the normal disk LUN.
			bytes=${stamp##*:}
			case $bytes in ''|*[!0-9]*) return 1 ;; esac
			awk -v bytes="$bytes" 'BEGIN {
			    exit !(bytes>=614400 && bytes<=2359293952 && bytes%2048==0)}' || return 1
		fi
		lease
		return $?
	fi
	cdrom=0
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
	[ "$target" != file ] || return 1
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
	[ "$target" != file ] || { check_image && lease; return $?; }
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

# configfs returns ENODEV for "none" when no driver is bound already.
unbind_udc() {
	bound=$(cat "$G/UDC") || return 1
	case $bound in ''|none) [ "${1:-}" = retry ] || return 0 ;; esac
	put "$G/UDC" none && return 0
	bound=$(cat "$G/UDC") || return 1
	case $bound in ''|none) return 0 ;; *) return 1 ;; esac
}

bind_udc() {
	# Recheck the mount and file identity immediately before every bind.
	[ "$target" != file ] || check_image || return 1
	udc=$(getprop sys.usb.controller)
	[ -n "$udc" ] || return 1
	[ "$(cat "$G/UDC")" != "$udc" ] || return 0
	attempt=0
	while [ "$attempt" -lt 20 ]; do
		lease || return 1
		attempt=$((attempt + 1))
		log_step "bind_udc: attempt $attempt"
		if put "$G/UDC" "$udc" && [ "$(cat "$G/UDC")" = "$udc" ]; then
			return 0
		fi
		log_step "bind_udc: attempt $attempt failed"
		if [ "$attempt" = 10 ]; then
			# Mirror Android's none -> functions transition, with time to settle.
			step unbind_udc retry || return 1
		fi
		sleep 0.2
	done
	return 1
}

bind_drive() {
	want_adb=0
	if adb_on; then
		want_adb=1
		setprop ctl.start adbd
		# Waiting for functionfs must not be reported as a successful share.
		ready=0
		while [ "$(getprop sys.usb.ffs.ready)" != 1 ]; do
			lease || return 1
			ready=$((ready + 1)); [ "$ready" -lt 50 ] || { fail adb_ready; return 1; }
			sleep 0.1
		done
	fi
	if [ "$linked_adb" != "$want_adb" ]; then
		step unbind_udc || return 1
		rm -f "$G/configs/b.1/f2" || return 1
		if [ "$want_adb" = 1 ]; then
			ln -s "$G/functions/ffs.adb" "$G/configs/b.1/f2" || return 1
		else
			setprop ctl.stop adbd
		fi
		linked_adb=$want_adb
	fi
	bind_udc || { fail bind_udc; return 1; }
}

gate_android() {
	# 2 suspends both the configfs=1 and legacy configfs=0 init actions.
	# The acknowledgement drains queued Android actions before we touch g1.
	cat "$G/idProduct" >"$STATE/product" || return 1
	cat "$G/os_desc/use" >"$STATE/os_desc" || return 1
	put "$STATE/role" "$expected_role" || return 1
	touch "$STATE/owned" || return 1
	setprop "$P.gated" 0 || return 1
	setprop sys.usb.configfs 2 || return 1
	i=0
	while [ "$(prop gated)" != 1 ]; do
		lease || return 1
		i=$((i + 1)); [ "$i" -lt 50 ] || return 1
		sleep 0.1
	done
}

device_role() {
	# Let the previous gadget finish disconnecting before the phy changes roles.
	sleep 0.2
	lease || return 1
	put "$OTG" peripheral || return 1
	expected_role=peripheral
	# The phy readback changes before dwc2's role work has finished.
	log_step "device_role: settling"
	sleep 0.5
	lease
}

start_drive() {
	status preparing
	step validate || return 1
	step gate_android || return 1
	step release_storage || return 1
	step unbind_udc || return 1
	step configure_drive || return 1
	step device_role || return 1
	linked_adb=-1
	step bind_drive || return 1
	status sharing
}

configure_drive() {
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
	if [ "$target" = file ]; then
		check_image || return 1
		put "$F/lun.0/removable" 1 || return 1
		put "$F/lun.0/cdrom" "$cdrom" || return 1
		put "$F/lun.0/ro" "$ro" || return 1
		check_image || return 1
		put "$F/lun.0/file" "$easy_root/isos/$image" || return 1
		ln -s "$F" "$G/configs/b.1/f1" || return 1
		return 0
	fi
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
}

close_drive() {
	# Do not hand anything back until the LUNs have closed their backing files.
	if [ -e "$STATE/owned" ] || [ -d "$F" ]; then
		unbind_udc normal || return 1
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

relock_emmc() {
	locked=1
	if [ -e "$SYS/mmcblk2/andr36oid_unlock" ]; then
		put "$SYS/mmcblk2/andr36oid_unlock" lock || locked=0
		for boot in 0 1; do
			[ ! -e "$SYS/mmcblk2boot$boot/force_ro" ] ||
				put "$SYS/mmcblk2boot$boot/force_ro" 1 || locked=0
		done
	fi
	[ "$locked" = 1 ]
}

restore_android() {
	restored=1
	if [ -f "$STATE/role" ]; then
		role=$(cat "$STATE/role")
		# A charger or a manual switch to host wins over the saved role.
		if [ "$(cat "$OTG")" = peripheral ] && [ "$role" != peripheral ]; then
			case $role in host|otg) put "$OTG" "$role" || restored=0 ;; *) restored=0 ;; esac
		fi
	fi
	put "$G/idProduct" "$(cat "$STATE/product")" || restored=0
	put "$G/os_desc/use" "$(cat "$STATE/os_desc")" || restored=0
	# Force a fresh Android none -> functions setup, even if its config is unchanged.
	config=$(getprop sys.usb.config)
	setprop sys.usb.config none || restored=0
	setprop sys.usb.configfs 1 || restored=0
	setprop sys.usb.config "$config" || restored=0
	setprop "$P.gated" 0 || restored=0
	[ "$restored" = 1 ] || return 1
	rm -f "$STATE/owned" "$STATE/role"
}

restore_storage() {
	# Re-read a changed partition table before remounting any old volume.
	if [ -f "$STATE/rescan" ]; then
		n=$(cat "$STATE/rescan")
		blockdev --rereadpt "$DEV/$n" || log_step "restore_storage: rescan failed for $n"
		rm -f "$STATE/rescan"
	fi
	if [ -f "$STATE/restore" ]; then
		while read -r id; do
			# The old volume might no longer exist after repartitioning.
			sm mount "$id" || log_step "restore_storage: mount failed for $id"
		done <"$STATE/restore"
		rm -f "$STATE/restore"
	fi
}

cleanup() {
	status stopping
	closed=1 cleanup_ok=1
	step close_drive || closed=0
	# Flush accepted writes before relocking, even after a failed detach.
	step sync || cleanup_ok=0
	step relock_emmc || cleanup_ok=0
	# Keep the journal and ownership if backing files might still be open.
	[ "$closed" = 1 ] || return 1
	if [ -e "$STATE/owned" ]; then
		step restore_android || cleanup_ok=0
	fi
	step restore_storage || cleanup_ok=0
	[ "$cleanup_ok" = 1 ]
}

prepare_state() {
	umask 022
	mkdir -p "$STATE" || return 1
	chmod 0755 "$STATE"
	boot_id=$(cat "$BOOT_ID") || return 1
	if [ "$(cat "$STATE/boot_id" 2>/dev/null)" != "$boot_id" ]; then
		# A full restart has fresh configfs and vold; never replay old disk IDs.
		rm -f "$STATE/owned" "$STATE/restore" "$STATE/rescan" "$STATE/product" "$STATE/os_desc" "$STATE/role"
	fi
	put "$STATE/boot_id" "$boot_id"
}

finish_request() {
	setprop "$P.request" stop
	cleanup || result=error
	if [ "$result" = error ]; then
		[ -n "$error" ] || fail session
	else
		setprop "$P.error" ''
	fi
	status "$result"
}

handle_request() {
	error=
	setprop "$P.error" ''
	# A new request retries a retained recovery journal before opening any LUN.
	result=error
	if cleanup && start_drive; then
		result=idle
		while lease && ! mounted && [ "$(getprop sys.usb.configfs)" = 2 ]; do
			if ! bind_drive; then result=error; break; fi
			sleep 0.2
		done
		if [ "$result" != error ] && [ "$(prop request)" = start ]; then
			fail watch_drive
			result=error
		fi
	fi
	finish_request
}

main() {
	if ! step prepare_state; then
		setprop "$P.request" stop
		status error
		return 0
	fi
	# A helper restart recovers the old session, never its old start request.
	if [ -e "$STATE/owned" ] || [ -e "$STATE/restore" ] || [ -d "$F" ]; then
		result=idle
		finish_request
	else
		status idle
	fi
	trap 'result=idle; finish_request; exit 0' HUP INT TERM
	while :; do
		if [ "$(prop scan)" = start ]; then
			if scan_images; then setprop "$P.scan" 'done'; else setprop "$P.scan" error; fi
		fi
		if [ "$(prop request)" = start ]; then
			handle_request
		fi
		sleep 0.2
	done
}

[ "${USB_DRIVE_TEST:-0}" = 1 ] || main
