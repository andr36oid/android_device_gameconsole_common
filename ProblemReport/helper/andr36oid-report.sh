#!/system/bin/sh
# shellcheck shell=ksh
#
# andr36oid-report: saves a problem report (logs, kernel log, the previous start's
# logs, system state) as one .tar.gz on EASYROMS, or in Download if there is no
# EASYROMS. Started by the Problem report page (Settings > System) through
# ctl.start; runs as root because dmesg, tombstones and pstore need it.
#
# Talks to the app through /data/misc/andr36oid-report:
#   note.txt  what the user typed (optional, written by the app)
#   status    one line: "running <step> <steps> <name>", "done <where>" or "error <why>"

DIR=/data/misc/andr36oid-report
EASYROMS_ID="public:179,7"
STEPS=8
NAME=report-$(date +%Y%m%d-%H%M%S)
WORK=$DIR/$NAME

log() {
	/system/bin/log -t andr36oid-report "$*"
}

status() {
	echo "$*" >"$DIR/status.tmp"
	chown system:system "$DIR/status.tmp"
	chmod 0660 "$DIR/status.tmp"
	mv -f "$DIR/status.tmp" "$DIR/status"
}

step() {
	status "running $1 $STEPS $2"
}

# cap <bytes> : pass at most this much through, so one runaway log can't fill the card
cap() {
	head -c "$1"
}

# run <file> <timeout s> <command...> : saves the output of a command, never hangs
run() {
	local f=$1 t=$2
	shift 2
	{
		echo "\$ $*"
		timeout "$t" "$@" 2>&1 </dev/null
	} | cap 8388608 >>"$WORK/$f"
	echo >>"$WORK/$f"
}

# show <file> <path...> : appends files with their names (sysfs, proc)
show() {
	local f=$1 p
	shift
	for p in "$@"; do
		[ -r "$p" ] || continue
		[ -d "$p" ] && continue
		echo "== $p" >>"$WORK/$f"
		cap 1048576 <"$p" >>"$WORK/$f" 2>&1
		echo >>"$WORK/$f"
	done
}

# where the report goes: EASYROMS if it's mounted, else internal Download
target() {
	local id state uuid
	sm list-volumes public </dev/null 2>/dev/null | while read -r id state uuid; do
		[ "$id" = "$EASYROMS_ID" ] && [ "$state" = mounted ] && [ -n "$uuid" ] &&
			[ -d "/mnt/media_rw/$uuid" ] && echo "/mnt/media_rw/$uuid EASYROMS"
	done | head -n 1 | grep . && return 0
	[ -d /data/media/0/Download ] && echo "/data/media/0/Download Download" && return 0
	return 1
}

rm -rf "$DIR"/report-*
mkdir -p "$WORK" || { status "error cant_write"; exit 1; }

if ! T=$(target); then
	status "error no_storage"
	rm -rf "$WORK"
	exit 1
fi
ROOT=${T% *}
SHOWN=${T##* }

step 1 system
{
	echo "andr36oid problem report"
	echo "Saved:   $(date)"
	echo "Version: $(getprop ro.andr36oid.version)"
	echo "Build:   $(getprop ro.build.fingerprint)"
	echo "Device:  $(getprop ro.product.device) ($(getprop ro.boot.hardware))"
	echo "Kernel:  $(uname -a)"
	echo "Up:      $(uptime)"
	echo "Boot reason: $(getprop sys.boot.reason) (last: $(getprop sys.boot.reason.last))"
	echo "Previous start's logs: $(ls /sys/fs/pstore 2>/dev/null | tr '\n' ' ')"
	echo "Battery: $(cat /sys/class/power_supply/battery/capacity 2>/dev/null)%," \
		"$(cat /sys/class/power_supply/battery/status 2>/dev/null)," \
		"$(cat /sys/class/power_supply/battery/voltage_now 2>/dev/null) uV"
	echo
	echo "What went wrong (typed by the user):"
	if [ -s "$DIR/note.txt" ]; then cap 65536 <"$DIR/note.txt"; else echo "(nothing typed)"; fi
	echo
} >"$WORK/summary.txt"
run system.txt 10 getprop
run system.txt 5 cat /proc/cmdline
run system.txt 5 cat /proc/cpuinfo
run system.txt 5 cat /proc/meminfo
run system.txt 5 cat /proc/mounts
run system.txt 10 df -h
run system.txt 10 ps -A -o USER,PID,PPID,VSZ,RSS,PCY,S,TIME,NAME
run system.txt 15 top -b -n 1 -m 30
run system.txt 5 cat /proc/interrupts
run system.txt 5 cat /proc/zoneinfo
run system.txt 5 lsmod

step 2 logcat
run logcat.txt 60 logcat -d -b all -v threadtime
# the previous start's Android log, kept in RAM across a restart (debuggable builds)
run logcat-previous-start.txt 30 logcat -L -b all -v threadtime

step 3 kernel
run kernel.txt 10 dmesg
# the previous start's kernel log and crash records, kept in RAM across a restart
for f in /sys/fs/pstore/*; do
	[ -f "$f" ] || continue
	cap 2097152 <"$f" >"$WORK/previous-start-${f##*/}.txt"
done

step 4 crashes
run crashes.txt 5 ls -la /data/tombstones /data/anr
for f in $(ls -t /data/tombstones/tombstone_* 2>/dev/null | grep -v '\.pb$' | head -n 3) \
	$(ls -t /data/anr/* 2>/dev/null | head -n 2); do
	show crashes.txt "$f"
done
run crashes.txt 20 dumpsys dropbox
# one tag per call: dumpsys dropbox only shows entries that match every argument
for tag in system_server_crash system_server_watchdog system_server_anr \
	system_server_native_crash system_app_crash system_app_anr data_app_crash \
	data_app_anr SYSTEM_TOMBSTONE SYSTEM_RESTART SYSTEM_LAST_KMSG; do
	run crashes.txt 20 dumpsys dropbox --print "$tag"
done

step 5 power
show power.txt /sys/power/state /sys/power/mem_sleep /sys/power/wakeup_count \
	/sys/power/wake_lock /sys/power/suspend_stats/* /sys/kernel/wakeup_reasons/*
show power.txt /sys/class/power_supply/*/uevent
for z in /sys/class/thermal/thermal_zone*; do
	echo "$z $(cat "$z/type" 2>/dev/null) $(cat "$z/temp" 2>/dev/null)" >>"$WORK/power.txt"
done
show power.txt /sys/devices/system/cpu/cpu0/cpufreq/scaling_* \
	/sys/devices/system/cpu/cpu0/cpufreq/stats/time_in_state \
	/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_*_freq
run power.txt 20 dumpsys battery
run power.txt 20 dumpsys power
run power.txt 20 dumpsys suspend_control_internal
run power.txt 20 dumpsys alarm

step 6 devices
run devices.txt 10 lsusb
for d in /sys/bus/usb/devices/*; do
	[ -r "$d/idVendor" ] || continue
	echo "$d $(cat "$d/idVendor"):$(cat "$d/idProduct") $(cat "$d/manufacturer" 2>/dev/null)" \
		"$(cat "$d/product" 2>/dev/null) speed $(cat "$d/speed" 2>/dev/null)" >>"$WORK/devices.txt"
done
show devices.txt /proc/bus/input/devices
show devices.txt /sys/block/mmcblk*/device/name /sys/block/mmcblk*/device/manfid \
	/sys/block/mmcblk*/device/oemid /sys/block/mmcblk*/device/date /sys/block/mmcblk*/size
run devices.txt 10 ip addr
run devices.txt 20 dumpsys input
run devices.txt 20 dumpsys usb
run devices.txt 20 dumpsys connectivity
run devices.txt 20 dumpsys ethernet
run devices.txt 20 dumpsys wifi
run devices.txt 20 dumpsys bluetooth_manager
run devices.txt 20 dumpsys audio
run devices.txt 20 dumpsys display
run devices.txt 20 dumpsys SurfaceFlinger
run devices.txt 20 sm list-volumes all

step 7 apps
run apps.txt 20 pm list packages -f --show-versioncode
run apps.txt 20 dumpsys activity activities
run apps.txt 30 dumpsys meminfo
run apps.txt 10 ls -la /data/adb/modules /data/misc/gameconsole
run apps.txt 10 settings list global
run apps.txt 10 settings list system

step 8 saving
mkdir -p "$ROOT/andr36oid-reports"
OUT=$ROOT/andr36oid-reports/$NAME.tar.gz
if tar -czf "$OUT.tmp" -C "$DIR" "$NAME" && mv -f "$OUT.tmp" "$OUT"; then
	case $ROOT in
	/data/media/*)
		chown -R media_rw:media_rw "$ROOT/andr36oid-reports"
		am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
			-d "file:///sdcard/Download/andr36oid-reports/$NAME.tar.gz" >/dev/null 2>&1
		;;
	esac
	sync
	log "saved $OUT"
	status "done $SHOWN/andr36oid-reports/$NAME.tar.gz"
else
	rm -f "$OUT.tmp"
	log "saving $OUT failed"
	status "error cant_write"
fi
rm -rf "$WORK"
