#!/bin/sh
# Fake configfs/sysfs, vold and properties. No host storage command is reachable.
# shellcheck disable=SC2218
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
T=$1
command mkdir -p "$T"
USB_DRIVE_SYS=$T/sys USB_DRIVE_DEV=$T/dev USB_DRIVE_GADGET=$T/gadget
USB_DRIVE_STATE=$T/state USB_DRIVE_MOUNTS=$T/mounts USB_DRIVE_FSTABS=$T/fstabs
USB_DRIVE_OTG=$T/otg USB_DRIVE_POWER=$T/power USB_DRIVE_DT=$T/dt
USB_DRIVE_MEDIA=$T/media
USB_DRIVE_TEST=1
export USB_DRIVE_TEST USB_DRIVE_SYS USB_DRIVE_DEV USB_DRIVE_GADGET USB_DRIVE_STATE
export USB_DRIVE_MOUNTS USB_DRIVE_FSTABS USB_DRIVE_OTG USB_DRIVE_POWER USB_DRIVE_DT
export USB_DRIVE_MEDIA
command mkdir -p "$T/props" "$T/state" "$T/fstabs" "$T/sys" "$T/dev" "$T/power/ac"
# shellcheck disable=SC1091
. "$HERE/andr36oid-usb-drive.sh"
block_node() { [ -f "$DEV/$1" ]; }
recover() { (unset target disk cid owner; cleanup); }
count=0
check() { count=$((count + 1)); "$@" || { echo "FAIL ($count): $*" >&2; exit 1; }; }
getprop() { cat "$T/props/$1" 2>/dev/null || true; }
setprop() {
	printf '%s\n' "$2" >"$T/props/$1"
	printf 'prop %s %s\n' "$1" "$2" >>"$T/log"
	if [ "$1:$2" = sys.usb.configfs:2 ] && [ "${fail_gate:-0}" = 0 ]; then
		printf '1\n' >"$T/props/$P.gated"
	fi
}
sm() {
	printf 'sm %s %s\n' "$1" "${2:-}" >>"$T/log"
	case $1 in
	list-volumes) cat "$T/volumes" ;;
	unmount)
		[ "${fail_unmount:-0}" = 0 ] || return 1
		[ "${keep_mounted:-0}" = 0 ] || return 0
		devnums=$(printf '%s' "$2" | sed 's/public://;s/,/:/')
		awk -v dev="$devnums" '$3!=dev' "$MOUNTS" >"$T/newmounts"
		mv "$T/newmounts" "$MOUNTS"
		;;
	mount) ;;
	*) return 1 ;;
	esac
}
blockdev() { printf 'blockdev %s %s\n' "$1" "$2" >>"$T/log"; }
log() { printf 'log %s\n' "$*" >>"$T/log"; }
sync() { echo 'stub sync' >>"$T/log"; }
sleep() {
	printf 'sleep %s\n' "$1" >>"$T/log"
	if [ "$1" = 0.5 ] && [ "${replace_image:-0}" = 1 ]; then
		mv "$MEDIA/UUID/isos/Alpine Linux.iso" "$MEDIA/UUID/old.iso"
		ln -s "$MEDIA/UUID/old.iso" "$MEDIA/UUID/isos/Alpine Linux.iso"
	fi
	if [ "${auto_stop:-0}" = 1 ] && [ "$(prop state)" = sharing ]; then
		setprop "$P.request" stop
	fi
}
# Emulate configfs's implicit lun.0 and attributes, and sysfs command readback.
mkdir() {
	command mkdir "$@"
	if [ "$#" = 1 ] && [ "$1" = "$F" ]; then command mkdir "$F/lun.0"; fi
}
rmdir() {
	printf 'rmdir %s\n' "$1" >>"$T/log"
	[ "${fail_close:-0}" = 0 ] || return 1
	if [ "$1" = "$F" ]; then command rm -rf "$F/lun.0"; fi
	command rm -f "$1"/file "$1"/ro "$1"/removable "$1"/cdrom
	command rmdir "$1"
}
put() {
	printf 'write %s %s\n' "$1" "$2" >>"$T/log"
	if [ "${fail_file:-0}" = 1 ]; then case $1 in */lun.*/file) [ -z "$2" ] || return 1 ;; esac; fi
	if [ "$1" = "$OTG" ] && [ "$2" = peripheral ] && [ "${fail_role:-0}" = 1 ]; then return 1; fi
	if [ "$1" = "$G/UDC" ]; then
		if [ "$2" = none ]; then
			[ "${fail_unbind:-0}" = 0 ] || return 1
			# Real configfs returns ENODEV for an already unbound gadget.
			[ -n "$(cat "$G/UDC")" ] || return 1
			printf '\n' >"$G/UDC"
			return 0
		fi
		bind_writes=$((bind_writes + 1))
		[ "${fail_bind:-0}" = 0 ] || return 1
		[ "$bind_writes" -gt "${delayed_bind:-0}" ] || return 1
		[ "$(cat "$OTG")" = peripheral ] || return 1
	fi
	case $1:$2 in
	*/andr36oid_unlock:unlock*)
		[ "${fail_unlock:-0}" = 0 ] || return 1
		printf 'unlocked\n' >"$1" ;;
	*/andr36oid_unlock:lock) printf 'locked\n' >"$1" ;;
	*) printf '%s\n' "$2" >"$1" ;;
	esac
}
reset_fixture() {
	command rm -rf "$SYS" "$DEV" "$G" "$STATE" "$T/props" "$T/platform" "$MEDIA"
	command mkdir -p "$SYS" "$DEV" "$G/configs/b.1/strings/0x409" "$G/functions/ffs.adb" "$G/os_desc" "$STATE" "$T/props"
	: >"$T/log"; : >"$T/volumes"
	for spec in '0 ff370000.dwmmc 0' '1 ff380000.dwmmc 32' '2 ff390000.dwmmc 64'; do
		# shellcheck disable=SC2086
		set -- $spec
		d=$1 c=$2 base=$3
		command mkdir -p "$T/platform/$c/mmc_host/block/mmcblk$d/device"
		ln -s "$T/platform/$c/mmc_host/block/mmcblk$d" "$SYS/mmcblk$d"
		printf '0123456789abcdef0123456789abcde%s\n' "$d" >"$SYS/mmcblk$d/device/cid"
		printf '33554432\n' >"$SYS/mmcblk$d/size"
		printf '179:%s\n' "$base" >"$SYS/mmcblk$d/dev"
		: >"$DEV/mmcblk$d"
		for part in 2 6 7; do
			command mkdir -p "$SYS/mmcblk$d/mmcblk${d}p$part/holders"
			ln -s "$SYS/mmcblk$d/mmcblk${d}p$part" "$SYS/mmcblk${d}p$part"
			printf '%s\n' "$part" >"$SYS/mmcblk${d}p$part/partition"
			printf '179:%s\n' "$((base + part))" >"$SYS/mmcblk${d}p$part/dev"
			printf 'PARTNAME=EASYROMS\n' >"$SYS/mmcblk${d}p$part/uevent"
			: >"$DEV/mmcblk${d}p$part"
		done
	done
	for boot in 0 1; do
		command mkdir -p "$SYS/mmcblk2boot$boot"
		printf '1\n' >"$SYS/mmcblk2boot$boot/force_ro"
		printf '179:%s\n' "$((96 + boot * 32))" >"$SYS/mmcblk2boot$boot/dev"
		: >"$DEV/mmcblk2boot$boot"
	done
	printf 'locked\n' >"$SYS/mmcblk2/andr36oid_unlock"
	printf '1 0 179:2 / / ro - ext4 /dev/root ro\n2 1 179:6 / /data rw - ext4 /dev/root rw\n' >"$MOUNTS"
	printf '# active fstab (mounts identify this fixture)\n' >"$FSTABS/fstab.rk30board"
	printf 'peripheral\n' >"$OTG"
	printf 'Mains\n' >"$POWER/ac/type"; printf '0\n' >"$POWER/ac/online"
	printf '0x4ee2\n' >"$G/idProduct"; printf '1\n' >"$G/os_desc/use"
	: >"$G/UDC"
	setprop sys.usb.configfs 1
	setprop sys.usb.controller ff300000.usb
	setprop persist.sys.usb.config none
	setprop sys.andr36oid.easyroms.ready 1
	setprop sys.andr36oid.easyroms.dev mmcblk0p7
	setprop "$P.target" mmcblk1
	setprop "$P.cid" "$(cat "$SYS/mmcblk1/device/cid")"
	setprop "$P.ro" 0
	setprop "$P.owner" "$$"
	setprop "$P.request" start
	fail_unmount=0 keep_mounted=0 fail_unlock=0 fail_file=0 fail_close=0
	fail_bind=0 fail_unbind=0 fail_gate=0 fail_role=0 delayed_bind=0 bind_writes=0 auto_stop=0
	replace_image=0
	# shellcheck disable=SC2034
	error=
}
select_target() {
	setprop "$P.target" "$1"
	d=${1%p7}
	setprop "$P.cid" "$(cat "$SYS/$d/device/cid")"
}
reset_fixture
check validate
select_target mmcblk0
if validate; then echo 'FAIL: whole Android disk allowed'; exit 1; fi
select_target mmcblk0p7
check validate
setprop sys.andr36oid.easyroms.dev ''
if validate; then echo 'FAIL: missing EASYROMS allowed'; exit 1; fi
reset_fixture
printf 'private:179,39 unmounted uuid\n' >"$T/volumes"
if validate; then echo 'FAIL: adopted card allowed'; exit 1; fi
reset_fixture
printf 'public:179,39 mounted UUID\npublic:179,38 mounted UUID2\n' >"$T/volumes"
printf '3 1 179:39 / /mnt/media_rw/UUID rw - exfat /dev/block/vold/public:179,39 rw\n4 1 179:38 / /mnt/media_rw/UUID2 rw - vfat /dev/block/vold/public:179,38 rw\n' >>"$MOUNTS"
check start_drive
check test "$(cat "$F/lun.0/file")" = "$DEV/mmcblk1"
check test "$(cat "$F/lun.0/ro")" = 0
check test "$(cat "$F/lun.0/removable")" = 1
check test "$(getprop sys.usb.configfs)" = 2
check test "$(wc -l <"$STATE/restore")" = 2
check test "$(cat "$G/UDC")" = ff300000.usb
check cleanup
check test "$(getprop sys.usb.configfs)" = 1
check test ! -d "$F"
check test "$(cat "$G/idProduct")" = 0x4ee2
check test "$(cat "$G/os_desc/use")" = 1
check test "$(awk '$1=="sm" && $2=="mount" {n++} END {print n}' "$T/log")" = 2
reset_fixture
select_target mmcblk0p7
printf 'public:179,7 mounted UUID\n' >"$T/volumes"
printf '3 1 179:7 / /mnt/media_rw/UUID rw - exfat /dev/block/vold/public:179,7 rw\n' >>"$MOUNTS"
setprop "$P.ro" 1
check start_drive
check test "$(cat "$F/lun.0/file")" = "$DEV/mmcblk0p7"
check test "$(cat "$F/lun.0/ro")" = 1
check cleanup
for slot in 0 1; do
	reset_fixture
	printf '1 0 179:66 / / ro - ext4 /dev/root ro\n2 1 179:%s / /data rw - ext4 /dev/root rw\n' "$((slot * 32 + 6))" >"$MOUNTS"
	select_target "mmcblk${slot}p7"
	setprop sys.andr36oid.easyroms.dev "mmcblk${slot}p7"
	check start_drive
	check test "$(cat "$F/lun.0/file")" = "$DEV/mmcblk${slot}p7"
	check cleanup
done
reset_fixture
select_target mmcblk2
check start_drive
for lun in 0 1 2; do check test "$(cat "$F/lun.$lun/ro")" = 0; done
check test "$(cat "$F/lun.1/file")" = "$DEV/mmcblk2boot0"
check test "$(cat "$F/lun.2/file")" = "$DEV/mmcblk2boot1"
check test "$(cat "$SYS/mmcblk2/andr36oid_unlock")" = unlocked
check test "$(cat "$SYS/mmcblk2boot0/force_ro")" = 0
check test "$(cat "$SYS/mmcblk2boot1/force_ro")" = 0
check cleanup
check test "$(cat "$SYS/mmcblk2/andr36oid_unlock")" = locked
check test "$(cat "$SYS/mmcblk2boot0/force_ro")" = 1
check test "$(cat "$SYS/mmcblk2boot1/force_ro")" = 1
reset_fixture
select_target mmcblk2
setprop "$P.ro" 1
check start_drive
for lun in 0 1 2; do check test "$(cat "$F/lun.$lun/ro")" = 1; done
check test "$(cat "$SYS/mmcblk2/andr36oid_unlock")" = locked
check cleanup
reset_fixture
printf '1 0 179:66 / / ro - ext4 /dev/root ro\n2 1 179:70 / /data rw - f2fs /dev/root rw\n' >"$MOUNTS"
select_target mmcblk2
check start_drive
for lun in 0 1 2; do check test "$(cat "$F/lun.$lun/ro")" = 1; done
check test "$(cat "$SYS/mmcblk2/andr36oid_unlock")" = locked
check cleanup
reset_fixture
setprop persist.sys.usb.config adb
setprop sys.usb.ffs.ready 1
check start_drive
check test -L "$G/configs/b.1/f2"
setprop sys.usb.ffs.ready 0
put "$G/UDC" ''
if bind_drive; then echo 'FAIL: missing functionfs readiness accepted'; exit 1; fi
setprop sys.usb.ffs.ready 1
check bind_drive
check test "$(cat "$G/UDC")" = ff300000.usb
setprop persist.sys.usb.config none
check bind_drive
check test ! -L "$G/configs/b.1/f2"
check cleanup
for failure in unmount busy unlock file; do
	reset_fixture
	select_target mmcblk2
	case $failure in
	unmount|busy)
		printf 'public:179,71 mounted UUID\n' >"$T/volumes"
		printf '3 1 179:71 / /mnt/media_rw/UUID rw - exfat /dev/block/vold/public:179,71 rw\n' >>"$MOUNTS"
		if [ "$failure" = unmount ]; then fail_unmount=1; else keep_mounted=1; fi ;;
	unlock) fail_unlock=1 ;;
	file) fail_file=1 ;;
	esac
	if start_drive; then echo "FAIL: accepted $failure failure"; exit 1; fi
	check cleanup
	check test "$(cat "$SYS/mmcblk2/andr36oid_unlock")" = locked
	check test "$(cat "$SYS/mmcblk2boot0/force_ro")" = 1
	check test "$(getprop sys.usb.configfs)" = 1
done
reset_fixture
check start_drive
printf '1\n' >"$POWER/ac/online"
if lease; then echo 'FAIL: charger did not stop drive'; exit 1; fi
check cleanup
reset_fixture
check start_drive
put "$OTG" host
if lease; then echo 'FAIL: host mode did not stop drive'; exit 1; fi
check cleanup
reset_fixture
check start_drive
# shellcheck disable=SC2034
owner_start=wrong
if lease; then echo 'FAIL: process restart kept drive'; exit 1; fi
check cleanup
reset_fixture
check start_drive
setprop "$P.request" stop
if lease; then echo 'FAIL: Stop did not cancel lease'; exit 1; fi
check cleanup
reset_fixture
check start_drive
put "$SYS/mmcblk1/device/cid" replaced
if lease; then echo 'FAIL: replacement card kept drive'; exit 1; fi
check cleanup
# Simulate a new helper process using only the persistent journal.
reset_fixture
select_target mmcblk2
check start_drive
check recover
check test "$(cat "$SYS/mmcblk2/andr36oid_unlock")" = locked
# Failed detach must relock eMMC and retain the journal without remounting.
reset_fixture
select_target mmcblk2
check start_drive
fail_close=1
if cleanup; then echo 'FAIL: detach failure ignored'; exit 1; fi
check test -f "$STATE/owned"
check test "$(cat "$SYS/mmcblk2/andr36oid_unlock")" = locked
check test "$(cat "$SYS/mmcblk2boot0/force_ro")" = 1
check test "$(getprop sys.usb.configfs)" = 2
fail_close=0
check cleanup
# Other Android cards and adopted cards never become whole-disk offers.
reset_fixture
printf 'PARTNAME=userdata\n' >"$SYS/mmcblk1p6/uevent"
if validate; then echo 'FAIL: inactive Android card exported whole'; exit 1; fi
printf 'PARTNAME=android_expand\n' >"$SYS/mmcblk1p6/uevent"
if validate; then echo 'FAIL: adopted card without vold volume exported'; exit 1; fi
# Boot areas are part of the same unmount/refusal scope.
reset_fixture
select_target mmcblk2
printf '3 1 179:96 / /mnt/bootarea rw - vfat /dev/block/mmcblk2boot0 rw\n' >>"$MOUNTS"
if start_drive; then echo 'FAIL: mounted boot area shared'; exit 1; fi
check cleanup
# Both fstab.emmcsd links and mount numbers can identify a data card in slot 2.
reset_fixture
: >"$MOUNTS"
command mkdir -p "$DEV/by-name"
ln -s "$DEV/mmcblk1p6" "$DEV/by-name/a36data"
printf '%s /system ext4 ro wait\n%s /data f2fs rw wait\n' "$DEV/mmcblk2p2" "$DEV/by-name/a36data" >"$FSTABS/fstab.emmcsd"
setprop ro.boot.fstab_suffix emmcsd
setprop sys.andr36oid.easyroms.dev mmcblk1p7
select_target mmcblk1
if validate; then echo 'FAIL: fstab data card whole disk shared'; exit 1; fi
select_target mmcblk1p7
check validate
# Charging is allowed only on the DT's single-port consoles.
reset_fixture
printf '1\n' >"$POWER/ac/online"
if validate; then echo 'FAIL: charging share allowed'; exit 1; fi
command mkdir -p "$DT/syscon/usb2-phy@100/otg-port"
check validate
# Fail closed without enough mount/fstab evidence.
reset_fixture
: >"$MOUNTS"
if validate; then echo 'FAIL: unknown layout shared'; exit 1; fi
# Same-boot recovery closes files before relocking and remounting.
reset_fixture
select_target mmcblk2
printf 'public:179,71 mounted UUID\n' >"$T/volumes"
printf '3 1 179:71 / /mnt/media_rw/UUID rw - exfat /dev/block/vold/public:179,71 rw\n' >>"$MOUNTS"
check start_drive
check recover
# shellcheck disable=SC2016
check awk '$1=="rmdir" && $2 ~ /mass_storage.andr36oid$/ {closed=NR}
    $1=="stub" && $2=="sync" {flushed=NR}
    $1=="write" && $2 ~ /andr36oid_unlock$/ && $3=="lock" {locked=NR}
    $1=="blockdev" {rescanned=NR}
    $1=="sm" && $2=="mount" {mounted=NR}
    END {exit !(closed<flushed && flushed<locked && locked<rescanned && rescanned<mounted)}' "$T/log"
# Android must be gated and all storage released before the helper changes role.
reset_fixture
printf 'host\n' >"$OTG"
setprop sys.usb.config mtp
select_target mmcblk0p7
printf 'public:179,7 mounted UUID\n' >"$T/volumes"
printf '3 1 179:7 / /mnt/media_rw/UUID rw - exfat /dev/block/vold/public:179,7 rw\n' >>"$MOUNTS"
check start_drive
# shellcheck disable=SC2016
check awk '$1=="prop" && $2=="sys.usb.configfs" && $3==2 {gated=NR}
    $1=="sm" && $2=="unmount" {released=NR}
    $1=="write" && $2 ~ /otg$/ && $3=="peripheral" {role=NR}
    $1=="write" && $2 ~ /UDC$/ && $3=="ff300000.usb" {bound=NR}
    END {exit !(gated>0 && gated<released && released<role && role<bound)}' "$T/log"
check cleanup
check test "$(cat "$OTG")" = host
check test "$(getprop sys.usb.config)" = mtp
check test "$(prop gated)" = 0
# shellcheck disable=SC2016
check awk '$1=="prop" && $2=="sys.usb.config" && $3=="none" {none=NR}
    $1=="prop" && $2=="sys.usb.configfs" && $3==1 {released=NR}
    $1=="prop" && $2=="sys.usb.config" && $3=="mtp" {applied=NR}
    END {exit !(none>0 && none<released && released<applied)}' "$T/log"
# Transient controller failures retry; a partial bind gets a none/rebind recovery.
reset_fixture
delayed_bind=3
check start_drive
check test "$bind_writes" = 4
check cleanup
reset_fixture
delayed_bind=10
check start_drive
check test "$bind_writes" = 11
check test -n "$(sed -n '/^write .*\/UDC none$/p' "$T/log")"
check cleanup
reset_fixture
check start_drive
bind_writes=0
fail_unbind=1
check bind_drive
check test "$bind_writes" = 0
fail_unbind=0
check cleanup
# Failed bind, gate, role and detach each settle in error, preserving the first reason.
for failure in bind gate role close; do
	reset_fixture
	printf 'host\n' >"$OTG"
	setprop sys.usb.config mtp
	case $failure in
	bind) fail_bind=1; reason=bind_udc ;;
	gate) fail_gate=1; reason=gate_android ;;
	role) fail_role=1; reason=device_role ;;
	close) fail_close=1; reason=close_drive ;;
	esac
	auto_stop=1
	check handle_request
	check test "$(prop state)" = error
	check test "$(prop error)" = "$reason"
	check test "$(prop request)" = stop
	check test "$(cat "$SYS/mmcblk2/andr36oid_unlock")" = locked
	if [ "$failure" = close ]; then
		check test "$(getprop sys.usb.configfs)" = 2
		check test -e "$STATE/owned"
	else
		check test "$(getprop sys.usb.configfs)" = 1
		check test "$(cat "$OTG")" = host
	fi
	if [ "$failure" = bind ]; then check test "$bind_writes" = 20; fi
	check test -n "$(sed -n '/log .*failed:/p' "$T/log")"
	# A fresh start in the same helper can recover without a reboot.
	fail_bind=0 fail_gate=0 fail_role=0 fail_close=0
	setprop "$P.request" start
	check handle_request
	check test "$(prop state)" = idle
	check test "$(prop error)" = ''
	check test "$(getprop sys.usb.configfs)" = 1
done
# A UDC that cannot detach stays gated, keeps the journal and never remounts.
reset_fixture
check start_drive
fail_unbind=1
# shellcheck disable=SC2034
result=idle
check finish_request
check test "$(prop state)" = error
check test "$(prop error)" = close_drive
check test "$(getprop sys.usb.configfs)" = 2
check test -e "$STATE/owned"
check test "$(cat "$SYS/mmcblk2/andr36oid_unlock")" = locked
check test -z "$(sed -n '/^sm mount /p' "$T/log")"
fail_unbind=0
check cleanup
# Run the actual watch loop: no repeated cleanup/start after an error, then retry.
reset_fixture
printf 'host\n' >"$OTG"
fail_bind=1
# A subshell supplies bounded clock ticks; production main has no test branch.
(
	phase=0 ticks=0
	# Called indirectly by the sourced helper's watch loop.
	# shellcheck disable=SC2317
	sleep() {
		ticks=$((ticks + 1))
		[ "$ticks" -lt 100 ] || exit 1
		case $phase:$(prop state) in
		0:error) phase=1; before=$(wc -l <"$T/log") ;;
		1:error)
			[ "$(wc -l <"$T/log")" = "$before" ] || exit 1
			phase=2; fail_bind=0; setprop "$P.request" start ;;
		2:sharing) phase=3; setprop "$P.request" stop ;;
		3:idle) exit 0 ;;
		esac
	}
	main
)
check test "$(prop state)" = idle
check test "$(prop error)" = ''
# Init must also leave an unexpected exit stopped until an explicit start.
check awk '/^service andr36oid-usb-drive / {service=1; next}
    service && /^    oneshot$/ {found=1}
    service && /^on / {service=0}
    END {exit !found}' "$HERE/../init.rk30board.rc"
# Failed startup recovery is attempted once and waits for a fresh request.
reset_fixture
check start_drive
fail_close=1
BOOT_ID=$T/recovery-boot
printf 'same-boot\n' >"$BOOT_ID"
printf 'same-boot\n' >"$STATE/boot_id"
(
	# shellcheck disable=SC2317
	sleep() {
		[ "$(prop state)" = error ] || exit 1
		[ "$(prop request)" = stop ] || exit 1
		exit 0
	}
	main
)
check test "$(prop error)" = close_drive
check test -e "$STATE/owned"
# A state-directory/boot-ID failure also returns success to init, with an error.
reset_fixture
BOOT_ID=$T/missing-boot-id
check main 2>"$T/expected-error"
check test "$(prop state)" = error
check test "$(prop error)" = prepare_state
check test "$(prop request)" = stop
# A new boot discards old disk IDs; a helper restart keeps its recovery journal.
reset_fixture
BOOT_ID=$T/boot-id
printf 'new-boot\n' >"$BOOT_ID"
printf 'old-boot\n' >"$STATE/boot_id"
touch "$STATE/owned" "$STATE/restore" "$STATE/rescan"
check prepare_state
check test ! -e "$STATE/owned"
check test ! -e "$STATE/restore"
check test ! -e "$STATE/rescan"
touch "$STATE/owned" "$STATE/restore"
check prepare_state
check test -e "$STATE/owned"
check test -e "$STATE/restore"

# File sharing keeps EASYROMS mounted and never requests a block rescan.
image_fixture() {
	reset_fixture
	command mkdir -p "$MEDIA/UUID/isos"
	# A small valid CD track, sparse; no host storage or real sync.
	truncate -s 1048576 "$MEDIA/UUID/isos/Alpine Linux.iso"
	printf 'public:179,7 mounted UUID\n' >"$T/volumes"
	printf '3 1 179:7 / %s/UUID rw - exfat /dev/block/vold/public:179,7 rw\n' "$MEDIA" >>"$MOUNTS"
	setprop "$P.target" file
	setprop "$P.image" 'Alpine Linux.iso'
	setprop "$P.stamp" "$(stat -c '%d:%i:%s' "$MEDIA/UUID/isos/Alpine Linux.iso")"
	setprop "$P.cid" "$(cat "$SYS/mmcblk0/device/cid")"
	setprop "$P.cdrom" 1
}
image_fixture
check scan_images
check test "$(head -n 1 "$STATE/images")" = "$(printf 'ready\t%s\t0' "$(prop cid)")"
check test "$(tail -n 1 "$STATE/images")" = "$(printf 'file\t%s\tAlpine Linux.iso' "$(prop stamp)")"
check test "$(stat -c %a "$STATE/images")" = 644
truncate -s 1048576 "$MEDIA/UUID/isos/Hybrid.IMG"
truncate -s 1048576 "$MEDIA/UUID/outside.iso"
ln -s "$MEDIA/UUID/outside.iso" "$MEDIA/UUID/isos/link.iso"
command mkdir "$MEDIA/UUID/isos/folder.iso"
mkfifo "$MEDIA/UUID/isos/pipe.img"
: >"$MEDIA/UUID/isos/empty.iso"
: >"$MEDIA/UUID/isos/wrong.txt"
truncate -s 1048576 "$MEDIA/UUID/isos/bad;name.iso"
truncate -s 1048576 "$MEDIA/UUID/isos/$(printf 'bad\nname.iso')"
check scan_images
check test "$(wc -l <"$STATE/images")" = 3
check test -n "$(sed -n '/Hybrid.IMG$/p' "$STATE/images")"
printf 'host\n' >"$OTG"
check start_drive
check test "$(cat "$F/lun.0/file")" = "$MEDIA/UUID/isos/Alpine Linux.iso"
check test "$(cat "$F/lun.0/cdrom")" = 1
check test "$(cat "$F/lun.0/ro")" = 1
check test "$(cat "$F/lun.0/removable")" = 1
check test ! -e "$STATE/restore"
check test ! -e "$STATE/rescan"
check test -n "$(awk '$3=="179:7" {print}' "$MOUNTS")"
# shellcheck disable=SC2016
check awk '$1=="prop" && $2=="sys.usb.configfs" && $3==2 {gated=NR}
    $1=="write" && $2 ~ /lun.0\/file$/ {opened=NR}
    $1=="write" && $2 ~ /otg$/ && $3=="peripheral" {role=NR}
    $1=="write" && $2 ~ /UDC$/ && $3=="ff300000.usb" {bound=NR}
    END {exit !(gated>0 && gated<opened && opened<role && role<bound)}' "$T/log"
check recover
check test ! -d "$F"
check test "$(getprop sys.usb.configfs)" = 1
check test "$(cat "$OTG")" = host
check test -z "$(sed -n '/^sm \(unmount\|mount\) /p;/^blockdev /p' "$T/log")"
# Both extensions can be disk LUNs, following the Read-only switch.
for ext in iso img; do
	for readonly in 0 1; do
		image_fixture
		mv "$MEDIA/UUID/isos/Alpine Linux.iso" "$MEDIA/UUID/isos/test.$ext"
		setprop "$P.image" "test.$ext"
		setprop "$P.cdrom" 0
		setprop "$P.ro" "$readonly"
		check start_drive
		check test "$(cat "$F/lun.0/cdrom")" = 0
		check test "$(cat "$F/lun.0/ro")" = "$readonly"
		check cleanup
	done
done
# The kernel uses large file offsets for disk LUNs, but truncates oversized CDs.
for size in 3221225472 4294967296 5368709120; do
	image_fixture
	truncate -s "$size" "$MEDIA/UUID/isos/Alpine Linux.iso"
	setprop "$P.stamp" "$(stat -c '%d:%i:%s' "$MEDIA/UUID/isos/Alpine Linux.iso")"
	if start_drive; then echo 'FAIL: oversized CD image accepted'; exit 1; fi
	check cleanup
	setprop "$P.cdrom" 0
	check start_drive
	check test "$(cat "$F/lun.0/file")" = "$MEDIA/UUID/isos/Alpine Linux.iso"
	check cleanup
done
for size in 512 614401 2359296000; do
	image_fixture
	truncate -s "$size" "$MEDIA/UUID/isos/Alpine Linux.iso"
	setprop "$P.stamp" "$(stat -c '%d:%i:%s' "$MEDIA/UUID/isos/Alpine Linux.iso")"
	if validate; then echo "FAIL: CD size $size accepted"; exit 1; fi
done
image_fixture
truncate -s 2359293952 "$MEDIA/UUID/isos/Alpine Linux.iso"
setprop "$P.stamp" "$(stat -c '%d:%i:%s' "$MEDIA/UUID/isos/Alpine Linux.iso")"
check validate
# Root rejects paths and special files even when properties bypass the app.
for bad in symlink parent directory extension fifo empty newline odd long folder mount nested stamp cid; do
	image_fixture
	case $bad in
	symlink) mv "$MEDIA/UUID/isos/Alpine Linux.iso" "$MEDIA/UUID/old.iso"
		ln -s "$MEDIA/UUID/old.iso" "$MEDIA/UUID/isos/Alpine Linux.iso" ;;
	parent) setprop "$P.image" '../old.iso' ;;
	directory) setprop "$P.image" "$MEDIA/UUID/isos/Alpine Linux.iso" ;;
	extension) setprop "$P.image" 'bad.exe' ;;
	fifo) rm "$MEDIA/UUID/isos/Alpine Linux.iso"; mkfifo "$MEDIA/UUID/isos/Alpine Linux.iso" ;;
	empty) : >"$MEDIA/UUID/isos/Alpine Linux.iso" ;;
	newline) setprop "$P.image" "$(printf 'bad\nname.iso')" ;;
	odd) setprop "$P.image" 'bad;name.iso' ;;
	long) setprop "$P.image" "$(printf '%092d.iso' 0)" ;;
	folder) mv "$MEDIA/UUID/isos" "$MEDIA/UUID/other"; ln -s other "$MEDIA/UUID/isos" ;;
	mount) sed '/179:7 /d' "$MOUNTS" >"$T/newmounts"; mv "$T/newmounts" "$MOUNTS" ;;
	nested) printf '4 1 179:7 / %s/UUID/isos rw - exfat /dev/block/x rw\n' "$MEDIA" >>"$MOUNTS" ;;
	stamp) setprop "$P.stamp" 1:2:1048576 ;;
	cid) setprop "$P.cid" 0123456789abcdef0123456789abcdef ;;
	esac
	if validate; then echo "FAIL: $bad image request accepted"; exit 1; fi
	check scan_images
	check cleanup
done
image_fixture
setprop sys.andr36oid.easyroms.ready 0
if validate; then echo 'FAIL: unresolved EASYROMS accepted'; exit 1; fi
check scan_images
check test "$(cat "$STATE/images")" = unavailable
image_fixture
printf 'public:179,7 unmounted UUID\n' >"$T/volumes"
if validate; then echo 'FAIL: unmounted EASYROMS accepted'; exit 1; fi
image_fixture
printf 'public:179,7 mounted_ro UUID\n' >"$T/volumes"
setprop "$P.cdrom" 0
if validate; then echo 'FAIL: writable file on read-only volume accepted'; exit 1; fi
setprop "$P.ro" 1
check validate
check scan_images
check test "$(head -n 1 "$STATE/images")" = "$(printf 'ready\t%s\t1' "$(prop cid)")"
image_fixture
command rm -rf "$MEDIA/UUID/isos"
check scan_images
check test "$(wc -l <"$STATE/images")" = 1
check test ! -e "$MEDIA/UUID/isos"
# A change after opening the LUN is caught before binding and cleaned up.
image_fixture
replace_image=1
check handle_request
check test "$(prop state)" = error
check test "$(prop error)" = device_role
check test "$bind_writes" = 0
check test ! -d "$F"
check test "$(getprop sys.usb.configfs)" = 1
image_fixture
fail_file=1
check handle_request
check test "$(prop error)" = configure_drive
check test "$(prop request)" = stop
check test ! -d "$F"
image_fixture
check start_drive
truncate -s 2097152 "$MEDIA/UUID/isos/Alpine Linux.iso"
if lease; then echo 'FAIL: resized backing file kept lease'; exit 1; fi
check cleanup
image_fixture
check start_drive
printf '4 1 179:7 / %s/UUID rw - exfat /dev/block/vold/public:179,7 rw\n' "$MEDIA" >"$MOUNTS"
if lease; then echo 'FAIL: remounted EASYROMS kept lease'; exit 1; fi
check cleanup
printf '%s helper checks passed (%s)\n'   "$count" "${KSH_VERSION:-${BASH_VERSION:-sh}}"
