#!/system/bin/sh
# shellcheck shell=ksh
#
# andr36oid-report: saves a problem report (logs, kernel log, the previous start's
# logs, system state) as one .zip on the BOOT drive of the SD card (FAT, the drive
# every PC sees), else on EASYROMS, else in the internal Download folder. Runs as
# root because dmesg, tombstones and pstore need it. Personal things are taken out
# before saving (scrub below); README.txt in the zip says what is in it.
#
# Started two ways:
#   (no argument)  by the Problem report page (Settings > System) through ctl.start
#   boot           by init at every start (post-fs-data). Does nothing unless BOOT
#                  has a file make-report.txt: then it deletes the file, waits for
#                  the start to finish (or 5 minutes) and saves a report. For
#                  consoles whose buttons or screen don't work (make the file on a PC).
#
# Talks to the app through /data/misc/andr36oid-report:
#   note.txt  what the user typed (optional, written by the app)
#   status    one line: "running <step> <steps> <name>", "done <where>" or "error <why>"
#
# REPORT_* variables are only for the test on a PC (tests/test-report.sh).

DIR=/data/misc/andr36oid-report
EASYROMS_ID="public:179,7"
STEPS=8
NAME=report-$(date +%Y%m%d-%H%M%S)
WORK=$DIR/$NAME
MODE=${1:-app}
BOOT_MNT=${REPORT_BOOT_MNT:-/mnt/andr36oid-report-boot}
BOOT_DEVS=${REPORT_BOOT_DEVS:-"/dev/block/by-name/BOOT /dev/block/platform/ff370000.dwmmc/by-name/BOOT /dev/block/mmcblk0p1"}
# a folder that stands in for BOOT in the test (nothing is mounted then)
BOOT_TEST=${REPORT_TEST_BOOT:-}
# BOOT keeps this much free after the report (kernel and DTB updates need room)
BOOT_KEEP_KB=32768
# reports kept on BOOT, the oldest go
BOOT_KEEP_REPORTS=3
# boot mode: how long to wait for the start to finish, then for things to settle
BOOT_WAIT=${REPORT_BOOT_WAIT:-300}
SETTLE=${REPORT_SETTLE:-30}
LOCK=${REPORT_LOCK:-/dev/andr36oid-report.lock}
# one BOOT user at a time, see boot_lock
BOOT_LOCK=${REPORT_BOOT_LOCK:-/dev/andr36oid-boot.lock}
BOOT_LOCK_WAIT=${REPORT_BOOT_LOCK_WAIT:-30}
ROM_FOLDERS=/system/etc/gameconsole/rom-folders.txt
BOOT=
BOOT_RW=
BOOT_MOUNTED=

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
		{
			echo "== $p"
			cap 1048576 <"$p" 2>&1
			echo
		} >>"$WORK/$f"
	done
}

# ---- BOOT (partition 1, FAT) ----

# boot_lock <seconds> ; the same in andr36oid-report.sh, andr36oid-dtbhint.sh and
# andr36oid-cardsetup.sh. The start-up helpers take turns with BOOT: Linux won't
# mount it a second time with the other ro/rw flag. The lock is a folder in /dev
# (a restart clears it) with the holder's pid; a holder that was killed is
# noticed by its pid, and its BOOT mount is taken away. False after waiting
# this long.
boot_lock() {
	local n=0 pid m
	while ! mkdir "$BOOT_LOCK" 2>/dev/null; do
		pid=$(cat "$BOOT_LOCK/pid" 2>/dev/null)
		if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
			rm -rf "$BOOT_LOCK"
			continue
		fi
		n=$((n + 1))
		[ "$n" -gt $(($1 * 5)) ] && return 1
		sleep 0.2
	done
	echo $$ >"$BOOT_LOCK/pid"
	grep -o ' /mnt/andr36oid-[a-z]*-boot ' /proc/mounts 2>/dev/null | while read -r m; do
		umount "$m" 2>/dev/null
	done
	return 0
}

boot_unlock() {
	[ "$(cat "$BOOT_LOCK/pid" 2>/dev/null)" = "$$" ] && rm -rf "$BOOT_LOCK"
}

# boot_open ; mounts BOOT read-only at $BOOT_MNT (boot_rw makes it writable while
# something is written). Sets BOOT (the folder). Holds the BOOT lock until
# boot_close.
boot_open() {
	local dev
	[ -n "$BOOT" ] && return 0
	BOOT_RW=
	if ! boot_lock "$BOOT_LOCK_WAIT"; then
		log "BOOT is busy (another start-up helper)"
		return 1
	fi
	if [ -n "$BOOT_TEST" ]; then
		BOOT=$BOOT_TEST
		return 0
	fi
	mkdir -p "$BOOT_MNT"
	for dev in $BOOT_DEVS; do
		[ -e "$dev" ] || continue
		mount -t vfat -o ro,noatime "$dev" "$BOOT_MNT" 2>/dev/null || continue
		BOOT=$BOOT_MNT
		BOOT_MOUNTED=1
		return 0
	done
	boot_unlock
	return 1
}

boot_close() {
	if [ -n "$BOOT_MOUNTED" ]; then
		sync
		umount "$BOOT_MNT" 2>/dev/null
	fi
	BOOT=
	BOOT_RW=
	BOOT_MOUNTED=
	boot_unlock
}

# boot_rw ; makes the open BOOT writable (until boot_close)
boot_rw() {
	[ -n "$BOOT" ] || return 1
	[ -n "$BOOT_RW" ] && return 0
	if [ -n "$BOOT_TEST" ]; then
		[ -e "$BOOT/.readonly" ] || BOOT_RW=1
	elif mount -o remount,rw "$BOOT_MNT" 2>/dev/null; then
		BOOT_RW=1
	fi
	[ -n "$BOOT_RW" ]
}

# free_kb <folder> ; free space in KB
free_kb() {
	df -k "$1" 2>/dev/null | awk 'NR == 2 { print $4 }'
}

# trigger_file ; the make-report file on BOOT, any case, also "make-report.txt.txt"
# (Windows hides the .txt it adds) or "make_report"
trigger_file() {
	local f n
	for f in "$BOOT"/*; do
		[ -f "$f" ] || continue
		# shellcheck disable=SC2018,SC2019 # ASCII file names
		n=$(printf '%s' "${f##*/}" | tr 'A-Z' 'a-z')
		case $n in
		make-report | make-report.* | make_report | make_report.* | "make report"*)
			echo "$f"
			return 0
			;;
		esac
	done
	return 1
}

# target [noboot] ; where the report goes: BOOT if it's open and keeps enough room
# (it is made writable only to save the zip), else EASYROMS if it's mounted,
# else internal Download
target() {
	local id state uuid free
	if [ -z "${1:-}" ] && [ -n "$BOOT" ]; then
		free=$(free_kb "$BOOT")
		case $free in
		'' | *[!0-9]*) free=0 ;;
		esac
		if [ "$free" -gt $((BOOT_KEEP_KB + 16384)) ]; then
			echo "$BOOT BOOT"
			return 0
		fi
		log "BOOT has only ${free} KB free, not saving there"
	fi
	sm list-volumes public </dev/null 2>/dev/null | while read -r id state uuid; do
		[ "$id" = "$EASYROMS_ID" ] && [ "$state" = mounted ] && [ -n "$uuid" ] &&
			[ -d "/mnt/media_rw/$uuid" ] && echo "/mnt/media_rw/$uuid EASYROMS"
	done | head -n 1 | grep . && return 0
	[ -d /data/media/0/Download ] && echo "/data/media/0/Download Download" && return 0
	return 1
}

# easyroms ; where EASYROMS is mounted, nothing if it isn't
easyroms() {
	local id state uuid
	sm list-volumes public </dev/null 2>/dev/null | while read -r id state uuid; do
		[ "$id" = "$EASYROMS_ID" ] && [ "$state" = mounted ] && [ -n "$uuid" ] &&
			[ -d "/mnt/media_rw/$uuid" ] && echo "/mnt/media_rw/$uuid"
	done | head -n 1
}

# boot_mode ; at every start: returns 1 (nothing to do) without a make-report file.
# With one: deletes it first (so a report that crashes the console isn't tried at
# every start), keeps its text as the note, then waits for the start to finish.
boot_mode() {
	local f waited=0
	# read-only just to look: other start-up helpers read BOOT at the same time
	boot_open || return 1
	if ! f=$(trigger_file); then
		boot_close
		return 1
	fi
	log "found ${f##*/} on BOOT, saving a report after the start"
	mkdir -p "$DIR"
	# its text is the description (the file may just be empty)
	tr -d '\r' <"$f" | head -c 65536 >"$DIR/note.txt"
	if ! boot_rw || ! rm -f "$f"; then
		log "BOOT is read-only, can't delete ${f##*/}: no report (it would run at every start)"
		boot_close
		return 1
	fi
	echo "Saving a problem report, started $(date)." >"$BOOT/make-report-result.txt"
	# not mounted while waiting: other start-up helpers use BOOT too
	boot_close
	while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$waited" -lt "$BOOT_WAIT" ]; do
		sleep 5
		waited=$((waited + 5))
	done
	[ "$(getprop sys.boot_completed)" = 1 ] || log "the start didn't finish in $BOOT_WAIT s, saving what there is"
	sleep "$SETTLE"
	return 0
}

# boot_result <text> ; boot mode: one line on BOOT saying where the report went
boot_result() {
	[ "$MODE" = boot ] || return 0
	boot_open && boot_rw || return 0
	printf '%s\r\n' "$1" >"$BOOT/make-report-result.txt"
}

# card_id <cid hex> ; the SD card ID with the product serial number (PSN, hex
# digits 19-26) and the checksum (31-32) blanked; maker, OEM, name, revision and
# date stay (the same fields SD card check shows)
card_id() {
	case $1 in
	????????????????????????????????) ;;
	*)
		echo "(not a CID)"
		return
		;;
	esac
	printf '%s\n' "$1" | sed 's/^\(.\{18\}\).\{8\}\(.\{4\}\)..$/\1xxxxxxxx\2xx/'
}

# rom_counts ; how many files are in each ROM folder on EASYROMS. Only the
# folder names from our own list are shown, never the names of games or files.
rom_counts() {
	local e name rest n other=0 top=0 d
	e=$(easyroms)
	echo "ROM folders on EASYROMS: number of files in each (no names)"
	if [ -z "$e" ]; then
		echo "EASYROMS is not mounted"
		return 0
	fi
	echo "EASYROMS: $(df -h "$e" 2>/dev/null | awk 'NR == 2 { print $2 " size, " $4 " free" }')"
	if [ -r "$ROM_FOLDERS" ]; then
		while read -r name rest; do
			case $name in '' | \#*) continue ;; esac
			[ -d "$e/$name" ] || continue
			n=$(timeout 20 find "$e/$name" -type f 2>/dev/null | wc -l)
			echo "$name: $n"
		done <"$ROM_FOLDERS"
	fi
	for d in "$e"/*; do
		if [ -f "$d" ]; then
			top=$((top + 1))
		elif [ -d "$d" ] && ! grep -q "^${d##*/}[[:space:]]" "$ROM_FOLDERS" 2>/dev/null; then
			other=$((other + 1))
		fi
	done
	echo "other folders: $other"
	echo "files at the top: $top"
}

# scrub_rules ; sed -E rules that take personal things out of every file:
# Wi-Fi network names and keys, e-mail addresses (accounts), serial numbers, the
# device and host name, the last half of MAC addresses, and every path or
# document link into the user's storage (game and file names). A path is cut to
# the closing quote, or to the end of the line (names can have spaces).
scrub_rules() {
	local p
	p='(/storage/emulated/[0-9]+|/storage/self/primary|/storage/[0-9A-Fa-f-]{9,36}|/sdcard'
	p=$p'|/mnt/media_rw/[0-9A-Fa-f-]+|/data/media/[0-9]+|/mnt/user/[0-9]+/[A-Za-z0-9._-]+'
	p=$p'|/mnt/pass_through/[0-9]+/[A-Za-z0-9._-]+|/mnt/runtime/[a-z]+/[A-Za-z0-9._-]+'
	p=$p'|/mnt/installer/[0-9]+/[A-Za-z0-9._-]+|/mnt/androidwritable/[0-9]+/[A-Za-z0-9._-]+)'
	# only lines with one of these go through the rules (sed is slow on the console)
	printf '%s\n' '\#[Ss][Ss][Ii][Dd]|extra: |"-?(WPA_|NONE|SAE|OWE|WEP|IEEE|SUITE|WAPI|DPP)|[Pp][Ss][Kk]|[Pp]ass|[Ww]ep[Kk]ey|Account|@|[Ss]erial|hostname|device_name|bluetooth_|[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:|content:|primary|/storage/|/sdcard|/mnt/|/data/media/|^\[#{'
	cat <<'EOF'
s/([Ss][Ss][Ii][Dd][^"'<>]{0,12})"[^"]*"/\1"<ssid>"/g
s/([Ss][Ss][Ii][Dd][^"'<>]{0,12})'[^']*'/\1'<ssid>'/g
s/(extra: )"[^"]*"/\1"<ssid>"/g
s/"[^"]*"-?(WPA_PSK|WPA_EAP|IEEE8021X|NONE|SAE|OWE|WEP|SUITE_B_192|WAPI_PSK|WAPI_CERT|DPP)/"<ssid>"\1/g
s/([Pp][Ss][Kk]|[Pp]ass[Ww]ord|[Pp]assphrase|[Pp]re[Ss]hared[Kk]ey|[Ww]ep[Kk]ey[s0-9]*)(["']?[[:space:]]*[:=][[:space:]]*)[^[:space:],;)}]+/\1\2<hidden>/g
s/(Account[[:space:]]*\{name=)[^,}]*/\1<hidden>/g
s/[A-Za-z0-9._%+-]+@[A-Za-z0-9-]*[A-Za-z][A-Za-z0-9-]*(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/<email>/g
s/^(\[[^]]*([Ss]erial|hostname|device_name|bt\.name|ssid)[^]]*\]: \[).*\]$/\1<hidden>]/
s/^(device_name|bluetooth_name|bluetooth_address|[a-z_]*ssid[a-z_]*)=.*/\1=<hidden>/
s/([Ss]erial[A-Za-z_]*[[:space:]]*[=:][[:space:]]*)[^[:space:],;)}]+/\1<hidden>/g
s/(([0-9A-Fa-f]{2}:){3})[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}/\1xx:xx:xx/g
s#(content://[A-Za-z0-9._-]+)/[^[:space:]"']*#\1/<hidden>#g
s#(primary(%3A|:))[^[:space:]"'&]+#\1<hidden>#g
EOF
	printf 's#"%s/[^"]*"#"\\1/<hidden>"#g\n' "$p"
	printf 's#'\''%s/[^'\'']*'\''#'\''\\1/<hidden>'\''#g\n' "$p"
	printf 's#%s/[^<].*$#\\1/<hidden>#\n' "$p"
	echo '}'
}

# readme ; README.txt in the zip: what is in the report and what is left out
readme() {
	cat <<'EOF'
andr36oid problem report
========================

Saved by Settings > System > Problem report, or at the start of the console
because a file make-report.txt was on the BOOT drive. Please send the whole
.zip file with your bug report.

What is in it
-------------
summary.txt   version, build, kernel, battery, which console and DTB (device
              tree model, the DTB boot.ini loads and which Panels/Experimental
              copy it matches), and what you typed
system.txt    system properties, kernel command line, CPU, memory, mounts,
              free space, running processes, interrupts, kernel modules
logcat.txt    the Android log since the start (all buffers)
logcat-previous-start.txt  the Android log of the start before, if kept
kernel.txt    the kernel log (dmesg)
previous-start-*.txt  the kernel log and crash record of the start before,
              if the console restarted (pstore/ramoops)
crashes.txt   crash reports (tombstones, ANRs): the list and the newest few,
              and Android's crash records (dropbox)
power.txt     battery and charger (power_supply), sleep, temperatures
              (thermal zones), CPU speeds, dumpsys battery/power/alarm
devices.txt   USB devices, input devices (buttons, sticks) and their events,
              the SD cards (maker, name, date, ID without the serial number),
              network addresses, dumpsys input/usb/ethernet/audio/display,
              storage volumes
apps.txt      installed apps and versions, open screens, memory use, Magisk
              modules, system settings (global, system)
roms.txt      how many files are in each ROM folder on EASYROMS

What is left out or blanked
---------------------------
- Wi-Fi network names and passwords; the Wi-Fi and Bluetooth dumps
- accounts and e-mail addresses
- contacts, messages, photos: nothing is read from them
- the names of your games and files: every path into your storage is cut
  to "<hidden>"; roms.txt has only numbers
- serial numbers of the console, the SD card and USB devices, the device
  name, the second half of every MAC address

The logs can still mention the names of Bluetooth devices or apps you used.
Have a look before you post it in public.
EOF
}

finish() {
	boot_close
	rmdir "$LOCK" 2>/dev/null
}

if [ "$MODE" = boot ]; then
	boot_mode || exit 0
fi

# one report at a time (the page and a start-up report); the lock is in /dev, so a
# restart clears it
tries=0
while ! mkdir "$LOCK" 2>/dev/null; do
	if [ "$MODE" != boot ] || [ "$tries" -ge 60 ]; then
		log "another report is being saved"
		# the page shows why nothing happens
		[ "$MODE" != boot ] && status "error busy"
		exit 1
	fi
	sleep 5
	tries=$((tries + 1))
done
trap finish EXIT

rm -rf "$DIR"/report-*
mkdir -p "$WORK" || { status "error cant_write"; exit 1; }

# read-only while the report is made; BOOT is only written to save it
boot_open || log "BOOT not found"
if ! T=$(target); then
	status "error no_storage"
	boot_result "No problem report: no room on BOOT, EASYROMS or internal storage."
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
	# shellcheck disable=SC2012 # plain sysfs names, sorted like ls
	echo "Previous start's logs: $(ls /sys/fs/pstore 2>/dev/null | tr '\n' ' ')"
	echo "Battery: $(cat /sys/class/power_supply/battery/capacity 2>/dev/null)%," \
		"$(cat /sys/class/power_supply/battery/status 2>/dev/null)," \
		"$(cat /sys/class/power_supply/battery/voltage_now 2>/dev/null) uV"
	echo
	echo "What went wrong (typed by the user):"
	if [ -s "$DIR/note.txt" ]; then cap 65536 <"$DIR/note.txt"; else echo "(nothing typed)"; fi
	echo
} >"$WORK/summary.txt"
# Which console and which DTB: clones differ only there
{
	echo "Started by: $MODE"
	echo "Device tree model: $(tr -d '\0' </proc/device-tree/model 2>/dev/null)"
	echo "Device tree compatible: $(tr '\0' ' ' </proc/device-tree/compatible 2>/dev/null)"
	echo "Board revision (U-Boot hwrev): $(getprop ro.boot.revision)"
	if [ -n "$BOOT" ]; then
		echo "DTB loaded by boot.ini: $(sed -n 's/^[[:space:]]*load .* \([^ ]*\.dtb\).*/\1/p' \
			"$BOOT/boot.ini" 2>/dev/null | tr -d '\r' | tr '\n' ' ')"
		echo "DTB files on BOOT (and the Panels/Experimental copy each one is the same as):"
		find "$BOOT" -name '*.dtb' -type f 2>/dev/null | while IFS= read -r f; do
			echo "$(sha1sum <"$f" | cut -c 1-12) ${f#"$BOOT"/}"
		done >"$WORK/dtb.list"
		# shellcheck disable=SC2094 # both only read dtb.list
		while read -r h f; do
			case $f in */*) continue ;; esac
			echo "  $f sha1 $h, same as: $(grep "^$h .*/" "$WORK/dtb.list" | cut -d ' ' -f 2- |
				tr '\n' ';' | sed 's/;$//; s/;/; /g')"
		done <"$WORK/dtb.list"
		rm -f "$WORK/dtb.list"
	else
		echo "BOOT: not readable"
	fi
	echo
} >>"$WORK/summary.txt"
# not mounted while the rest is collected (minutes): other helpers use BOOT too
boot_close
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
# shellcheck disable=SC2010,SC2012 # newest first needs ls -t; names are tombstone_NN and anr_*
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
# fuel gauge: how the level was picked at boot, capacity and charge limit knobs
B=/sys/class/power_supply/battery/device
show power.txt $B/gauge_boot $B/design_capacity_mah $B/charge_limit_percent \
	$B/charge_limit_active
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
# the card's ID without its serial number and checksum (see card_id)
for c in /sys/block/mmcblk*/device/cid; do
	[ -r "$c" ] && echo "$c $(card_id "$(cat "$c")")" >>"$WORK/devices.txt"
done
show devices.txt /sys/block/mmcblk*/device/csd /sys/block/mmcblk*/device/scr \
	/sys/block/mmcblk*/device/type /sys/block/mmcblk*/device/hwrev \
	/sys/block/mmcblk*/device/fwrev /sys/block/mmcblk*/device/preferred_erase_size
run devices.txt 10 getevent -lp
run devices.txt 10 ip addr
run devices.txt 20 dumpsys input
run devices.txt 20 dumpsys usb
run devices.txt 20 dumpsys ethernet
# Wi-Fi and Bluetooth dumps are left out: they are full of network and device names
run devices.txt 5 settings get global wifi_on
run devices.txt 5 settings get global bluetooth_on
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
rom_counts >"$WORK/roms.txt"

step 8 saving
scrub_rules >"$DIR/scrub.sed"
for f in "$WORK"/*; do
	[ -f "$f" ] || continue
	if sed -E -f "$DIR/scrub.sed" "$f" >"$f.s"; then
		mv -f "$f.s" "$f"
	else
		# never save a file that couldn't be cleaned
		rm -f "$f" "$f.s"
		log "couldn't clean ${f##*/}, left out"
	fi
done
rm -f "$DIR/scrub.sed"
readme >"$WORK/README.txt"

if command -v zip >/dev/null 2>&1; then
	FILE=$NAME.zip
else
	FILE=$NAME.tar.gz
fi
# made on /data first; BOOT is writable only for the copy
ZIP=$DIR/$FILE
rm -f "$ZIP"
if ! case $FILE in
	*.zip) (cd "$DIR" && zip -q -r -X "$ZIP" "$NAME") ;;
	*) tar -czf "$ZIP" -C "$DIR" "$NAME" ;;
	esac then
	rm -f "$ZIP"
	log "packing the report failed"
	status "error cant_write"
	boot_result "No problem report: it could not be packed."
	rm -rf "$WORK"
	exit 1
fi
if [ "$SHOWN" = BOOT ] && ! { boot_open && boot_rw; }; then
	log "BOOT can't be written, saving the report elsewhere"
	boot_close
	if ! T=$(target noboot); then
		status "error no_storage"
		rm -rf "$WORK" "$ZIP"
		exit 1
	fi
	ROOT=${T% *}
	SHOWN=${T##* }
fi
mkdir -p "$ROOT/andr36oid-reports"
OUT=$ROOT/andr36oid-reports/$FILE
rm -f "$OUT.tmp"
if cp "$ZIP" "$OUT.tmp" && mv -f "$OUT.tmp" "$OUT"; then
	case $ROOT in
	/data/media/*)
		chown -R media_rw:media_rw "$ROOT/andr36oid-reports"
		am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
			-d "file:///sdcard/Download/andr36oid-reports/$FILE" >/dev/null 2>&1
		;;
	esac
	# BOOT is small: keep this one and the newest few others, by name (the date
	# in it), not by file time. Never this one: after an empty battery the clock
	# can be behind, and its name then looks older than the others.
	if [ "$SHOWN" = BOOT ]; then
		for f in "$ROOT/andr36oid-reports"/report-*.zip; do
			[ -f "$f" ] && [ "$f" != "$OUT" ] && echo "$f"
		done | sort -r | tail -n +"$BOOT_KEEP_REPORTS" | while IFS= read -r f; do rm -f "$f"; done
	fi
	sync
	log "saved $OUT ($(wc -c <"$OUT") bytes)"
	status "done $SHOWN/andr36oid-reports/$FILE"
	boot_result "Problem report saved: $SHOWN/andr36oid-reports/$FILE"
else
	rm -f "$OUT.tmp"
	log "saving $OUT failed"
	status "error cant_write"
	boot_result "No problem report: $SHOWN/andr36oid-reports/$FILE could not be written."
fi
boot_close
rm -f "$ZIP"
rm -rf "$WORK"
