#!/system/bin/sh
# shellcheck shell=ksh
#
# andr36oid-storagecare: the root half of Settings > Storage > Free up space. The app runs
# as the system user, which can't read EASYROMS, the internal storage or RetroArch's
# settings. This looks at how EASYROMS is mounted and what the kernel said about it, then
# runs the engine (Java, in the app's own APK) as root with app_process.
#
# The app writes $DIR/request (action=scan|delete|health) and, for a delete, $DIR/delete,
# and starts us with ctl.start. We write $DIR/health; the engine writes $DIR/scan or
# $DIR/result, and $DIR/status ("running …", "done …", "error CODE").
#
# STORAGECARE_* variables are for testing on a PC (tests/run-tests.sh):
#   STORAGECARE_RUN       command that runs the engine instead of app_process
#   STORAGECARE_EASYROMS  EASYROMS folder ("" = not mounted)
#   STORAGECARE_MOUNTS / STORAGECARE_DMESG / STORAGECARE_VOLD  files in place of
#                         /proc/mounts, dmesg and vold's log

DIR=${STORAGECARE_DIR:-/data/misc/andr36oid-storagecare}
MEDIA=${STORAGECARE_MEDIA:-/data/media/0}
RA_DATA=${STORAGECARE_RA_DATA:-/data/user/0/com.retroarch}
PKG=org.andr36oid.storagecare
APK=/system/app/StorageCare/StorageCare.apk
EASYROMS_ID=$(getprop sys.andr36oid.easyroms.id 2>/dev/null)
if [ -z "$EASYROMS_ID" ] && [ "$(getprop sys.andr36oid.easyroms.ready 2>/dev/null)" != 1 ]; then
	EASYROMS_ID="public:179,7"
fi
# The kernel uses the block name in its file system error messages
EASYROMS_DEV=$(getprop sys.andr36oid.easyroms.dev 2>/dev/null)
if [ -z "$EASYROMS_DEV" ] && [ "$(getprop sys.andr36oid.easyroms.ready 2>/dev/null)" != 1 ]; then
	EASYROMS_DEV=mmcblk0p7
fi

log() {
	[ -x /system/bin/log ] && /system/bin/log -t andr36oid-storagecare "$*"
	return 0
}

testing() {
	[ -n "$STORAGECARE_RUN" ]
}

status() {
	echo "$*" >"$DIR/status.tmp"
	mv -f "$DIR/status.tmp" "$DIR/status"
}

# "STATE UUID" of EASYROMS as vold sees it (mounted, unmountable, checking, removed, …)
easyroms_volume() {
	local id state uuid
	if testing; then
		if [ -n "$STORAGECARE_EASYROMS" ]; then
			echo "mounted ${STORAGECARE_EASYROMS##*/}"
		else
			echo "${STORAGECARE_EASYROMS_STATE:-removed} -"
		fi
		return 0
	fi
	sm list-volumes public </dev/null 2>/dev/null | while read -r id state uuid; do
		[ "$id" = "$EASYROMS_ID" ] && echo "$state ${uuid:--}"
	done
}

easyroms_path() {
	if testing; then
		echo "$STORAGECARE_EASYROMS"
	else
		[ "$1" != - ] && [ -d "/mnt/media_rw/$1" ] && echo "/mnt/media_rw/$1"
	fi
}

mounts() {
	if testing; then cat "${STORAGECARE_MOUNTS:-/dev/null}"; else cat /proc/mounts; fi
}

kernel_log() {
	if testing; then cat "${STORAGECARE_DMESG:-/dev/null}"; else dmesg 2>/dev/null; fi
}

vold_log() {
	if testing; then
		cat "${STORAGECARE_VOLD:-/dev/null}"
	else
		logcat -d -b main -b system -s vold 2>/dev/null
	fi
}

# key=value lines about the card's health, for the app
health() {
	local vol state uuid path opts fs ro errors fsck
	vol=$(easyroms_volume)
	state=${vol%% *}
	uuid=${vol#* }
	path=$(easyroms_path "$uuid")
	echo "easyroms_state=${state:-none}"
	fs=""
	ro=0
	if [ -n "$path" ]; then
		# "<dev> <mount point> <type> <options> 0 0"
		opts=$(mounts | while read -r _ mnt type o _; do
			[ "$mnt" = "$path" ] && echo "$type $o"
		done | head -n 1)
		fs=${opts%% *}
		case ",${opts#* }," in
		*,ro,*) ro=1 ;;
		esac
	fi
	echo "easyroms_fs=$fs"
	echo "easyroms_ro=$ro"
	# kernel file system errors on EASYROMS since boot: "exFAT-fs (<device>): error, …"
	errors=$(kernel_log | grep -c -E "FAT-fs \($EASYROMS_DEV\): error")
	echo "fs_errors=${errors:-0}"
	# vold's check before mounting: "Check OK", "Check OK, errors fixed" or "Check failed"
	fsck=$(vold_log | grep -E "Check (OK|failed)|errors fixed|errors left|Filesystem check" | tail -n 1)
	case $fsck in
	*"errors left"*) echo "fsck=errors_left" ;;
	*"errors fixed"*|*"rechecking"*) echo "fsck=fixed" ;;
	*"Check failed"*|*"check failed"*) echo "fsck=failed" ;;
	*"OK"*) echo "fsck=ok" ;;
	*) echo "fsck=unknown" ;;
	esac
}

# the APK holding the engine: the one in /system, or an update if there is one
apk() {
	local p
	p=$(pm path "$PKG" </dev/null 2>/dev/null | sed -n 's/^package://p' | head -n 1)
	[ -n "$p" ] && [ -f "$p" ] && { echo "$p"; return 0; }
	echo "$APK"
}

engine() {
	if testing; then
		# shellcheck disable=SC2086 # a command line on purpose
		$STORAGECARE_RUN org.andr36oid.storagecare.Engine "$@"
	else
		CLASSPATH=$(apk) app_process /system/bin org.andr36oid.storagecare.Engine "$@"
	fi
}

umask 022
mkdir -p "$DIR"
status "running start"
ACTION=$(sed -n 's/^action=//p' "$DIR/request" 2>/dev/null | head -n 1)
log "start: $ACTION"

health >"$DIR/health.tmp" && mv -f "$DIR/health.tmp" "$DIR/health"

case $ACTION in
health)
	status "done health"
	;;
scan|delete)
	VOL=$(easyroms_volume)
	EASYROMS=$(easyroms_path "${VOL#* }")
	set -- --dir "$DIR" --media "$MEDIA" --ra-data "$RA_DATA"
	[ -n "$EASYROMS" ] && set -- "$@" --easyroms "$EASYROMS"
	engine "$@"
	;;
*)
	status "error request"
	;;
esac

testing || { sync; chown system:system "$DIR"/* 2>/dev/null; }

# the engine always ends with done or error; anything else means it didn't run
case $(cat "$DIR/status" 2>/dev/null) in
done*|error*) ;;
*) status "error crashed" ;;
esac
log "end: $(cat "$DIR/status" 2>/dev/null)"
exit 0
