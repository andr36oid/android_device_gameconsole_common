#!/system/bin/sh
# shellcheck shell=ksh
#
# EASYROMS belongs to the disk holding Android's userdata, never to another card
# with the same label. Run by init after firstresize and the data card helper,
# before mount_all --late and the public volumes. Paths can move for the host test.

SYS=${EASYROMS_SYS:-/sys/class/block}
DEV=${EASYROMS_DEV:-/dev/block}
id=
dev=
part=

resolve() {
	local data disk node numbers major minor info
	case $(getprop ro.boot.fstab_suffix) in
	""|rk30board)
		# fstab.rk30board: the SD card Android started from
		case $(getprop ro.boot.boot_devices) in
		""|ff370000.dwmmc) ;;
		*) return ;;
		esac
		data=$DEV/platform/ff370000.dwmmc/by-name/userdata
		;;
	emmcsd)
		# The installer selected this card by its userdata GUID, in either slot
		data=$DEV/by-name/a36data
		;;
	*) return ;;
	esac
	data=$(readlink -f "$data") || return
	data=${data##*/}
	case $data in mmcblk[0-9]*p[0-9]*) ;; *) return ;; esac
	[ -f "$SYS/$data/partition" ] || return
	disk=${data%p*}
	node=${disk}p7
	[ "$(cat "$SYS/$node/partition" 2>/dev/null)" = 7 ] || return
	numbers=$(cat "$SYS/$node/dev" 2>/dev/null) || return
	major=${numbers%:*}
	minor=${numbers#*:}
	case $major in ""|*[!0-9]*) return ;; esac
	case $minor in ""|*[!0-9]*) return ;; esac
	[ "$numbers" = "$major:$minor" ] || return
	# Its GPT name, as the kernel read it from the table (firstresize names it
	# EASYROMS); nothing is read from the card itself. A partition named otherwise is
	# not ours; one without a name is, it is partition 7 of our disk.
	info=$(sed -n 's/^PARTNAME=//p' "$SYS/$node/uevent" 2>/dev/null)
	case $info in ""|EASYROMS) ;; *) return ;; esac
	id=public:$major,$minor
	dev=$node
	part=/sys/class/block/$node
}

resolve
setprop sys.andr36oid.easyroms.id "$id"
setprop sys.andr36oid.easyroms.dev "$dev"
setprop sys.andr36oid.easyroms.part "$part"
# An empty id after ready=1 means there is none (.noroms or a pure eMMC install).
# Consumers use the old fixed numbers only when this resolver hasn't run.
setprop sys.andr36oid.easyroms.ready 1
