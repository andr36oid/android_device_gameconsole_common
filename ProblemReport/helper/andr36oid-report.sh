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
#   startlog       by init at every start (post-fs-data). Sleeps until the start is
#                  done and then ends. Only when Android hasn't finished starting
#                  after 5 minutes (20 on the first start of a card or of a new
#                  version), it saves a small start log on BOOT for the endless boot logo case, see
#                  startlog_mode.
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
# startlog mode: how long a start may take, normal and first start (the first start
# of a card sets up every app); how often to look
START_WAIT=${REPORT_START_WAIT:-300}
START_WAIT_FIRST=${REPORT_START_WAIT_FIRST:-1200}
START_STEP=${REPORT_START_STEP:-10}
# start logs kept on BOOT, the oldest go
START_KEEP=3
# made in RAM: /data may be what is broken
START_WORK=${REPORT_START_WORK:-/dev/andr36oid-startlog}
# where a start log goes when BOOT can't be written; the next report takes it along
START_KEPT=$DIR/start-logs
# the version that last finished starting; a new one gets the long limit too
START_BUILD=$DIR/start-build
# a make-report.txt start-up report is coming, which also saves after 5 minutes
START_PENDING=${REPORT_START_PENDING:-/dev/andr36oid-report.pending}
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
		echo "== $p" >>"$WORK/$f"
		cap 1048576 <"$p" >>"$WORK/$f" 2>&1
		echo >>"$WORK/$f"
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
	# the start log (startlog mode) leaves this start to us
	: >"$START_PENDING"
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

# dtb_info ; the DTB boot.ini loads, and which Panels/Experimental copy on BOOT
# each top DTB is the same as (clones differ only there). BOOT must be open.
dtb_info() {
	local f h
	if [ -z "$BOOT" ]; then
		echo "BOOT: not readable"
		return 0
	fi
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
start-log-*   the logs of a start that didn't finish, if one was saved
              while BOOT couldn't be written

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

# ---- start log (startlog mode): for a start that never finishes ----

# startlog_readme ; README.txt in a start log zip
startlog_readme() {
	cat <<'EOT'
andr36oid start log
===================

Saved because Android did not finish starting (the boot logo stayed on the
screen). Please attach this .zip file to your bug report, and say which
console you have and what the screen shows.

summary.txt   version, kernel, battery, how long the start waited, which
              console and DTB (device tree, the DTB boot.ini loads and which
              Panels/Experimental copy it matches)
system.txt    system properties (which services run or keep restarting),
              kernel command line, memory, mounts, free space, processes
logcat.txt    the Android log of this start (main, system, crash)
logcat-previous-start.txt  the Android log of the start before, if kept
kernel.txt    the kernel log (dmesg)
previous-start-*.txt  the kernel log and crash record of the start before,
              if the console restarted (pstore, last_kmsg)
crashes.txt   the list of crash reports (tombstones, ANRs) and the newest
              two tombstones

Wi-Fi names and keys, e-mail addresses, serial numbers and the names of your
games and files are taken out, like in the problem report.
EOT
}

# startlog_collect <minutes> <first start: yes|no> ; the logs of this start into
# $WORK. Only what works while Android hangs: nothing that asks system_server
# (dumpsys, sm, pm, settings would wait for it).
startlog_collect() {
	local f
	{
		echo "andr36oid start log"
		echo "Android did not finish starting in $1 minutes (first start of this card: $2)."
		echo "Saved:   $(date)"
		echo "Version: $(getprop ro.andr36oid.version)"
		echo "Build:   $(getprop ro.build.fingerprint)"
		echo "Device:  $(getprop ro.product.device) ($(getprop ro.boot.hardware))"
		echo "Kernel:  $(uname -a)"
		echo "Up:      $(uptime)"
		echo "Boot reason: $(getprop sys.boot.reason) (last: $(getprop ro.boot.bootreason))"
		echo "Boot animation: $(getprop init.svc.bootanim), zygote: $(getprop init.svc.zygote)," \
			"system_server restarts: $(getprop sys.system_server.start_count)"
		echo "Battery: $(cat /sys/class/power_supply/battery/capacity 2>/dev/null)%," \
			"$(cat /sys/class/power_supply/battery/status 2>/dev/null)," \
			"$(cat /sys/class/power_supply/battery/voltage_now 2>/dev/null) uV"
		echo
		echo "Device tree model: $(tr -d '\0' </proc/device-tree/model 2>/dev/null)"
		echo "Device tree compatible: $(tr '\0' ' ' </proc/device-tree/compatible 2>/dev/null)"
		echo "Board revision (U-Boot hwrev): $(getprop ro.boot.revision)"
		if boot_open; then
			dtb_info
			boot_close
		else
			echo "BOOT: not readable"
		fi
		echo
	} >"$WORK/summary.txt"
	run system.txt 10 getprop
	run system.txt 5 cat /proc/cmdline
	run system.txt 5 cat /proc/meminfo
	run system.txt 5 cat /proc/mounts
	run system.txt 10 df -h
	run system.txt 10 ps -A -o USER,PID,PPID,VSZ,RSS,PCY,S,TIME,NAME
	run logcat.txt 60 logcat -d -b main,system,crash -v threadtime
	run logcat-previous-start.txt 30 logcat -L -b main,system,crash -v threadtime
	run kernel.txt 10 dmesg
	for f in /sys/fs/pstore/* /proc/last_kmsg; do
		[ -f "$f" ] || continue
		cap 2097152 <"$f" >"$WORK/previous-start-${f##*/}.txt"
	done
	run crashes.txt 5 ls -la /data/tombstones /data/anr
	# shellcheck disable=SC2010,SC2012 # newest first needs ls -t; names are tombstone_NN
	for f in $(ls -t /data/tombstones/tombstone_* 2>/dev/null | grep -v '\.pb$' | head -n 2); do
		show crashes.txt "$f"
	done
	# the same cleaning as the problem report
	scrub_rules >"$START_WORK/scrub.sed"
	for f in "$WORK"/*; do
		[ -f "$f" ] || continue
		if sed -E -f "$START_WORK/scrub.sed" "$f" >"$f.s"; then
			mv -f "$f.s" "$f"
		else
			rm -f "$f" "$f.s"
			log "couldn't clean ${f##*/}, left out"
		fi
	done
	startlog_readme >"$WORK/README.txt"
}

# startlog_note <file> ; the note at the top of BOOT: what happened and where the
# logs are. In three languages: the console's language isn't known this early.
startlog_note() {
	local f
	# UTF-8 with a BOM, so old Notepad shows the accents
	printf '\357\273\277'
	{
		echo "Android did not finish starting on this console ($(date))."
		echo "Its logs were saved in the folder andr36oid-reports on this drive."
		echo "Please attach the newest start-log file to an issue at"
		echo "https://github.com/andr36oid/releases/issues and say which console you have."
		echo
		echo "Android no terminó de arrancar en esta consola."
		echo "Sus registros se guardaron en la carpeta andr36oid-reports de esta unidad."
		echo "Adjunta el archivo start-log más nuevo a un reporte (issue) en"
		echo "https://github.com/andr36oid/releases/issues y di qué consola tienes."
		echo
		echo "O Android não terminou de iniciar neste console."
		echo "Os registros foram salvos na pasta andr36oid-reports desta unidade."
		echo "Anexe o arquivo start-log mais recente a uma issue em"
		echo "https://github.com/andr36oid/releases/issues e diga qual console você tem."
		echo
		echo "Newest / Más nuevo / Mais novo: andr36oid-reports/$1"
		echo "Kept / Guardados / Guardados:"
		for f in "$BOOT/andr36oid-reports"/start-log-*; do
			[ -f "$f" ] && echo "  ${f##*/}"
		done
	} | sed 's/$/\r/'
}

# prune_start_logs <folder> <keep> ; keeps the file just saved and the newest others
# by name (the date in it), like the reports
prune_start_logs() {
	local f
	for f in "$1"/start-log-*; do
		case $f in *.tmp) continue ;; esac
		[ -f "$f" ] && [ "${f##*/}" != "$FILE" ] && echo "$f"
	done | sort -r | tail -n +"$2" | while IFS= read -r f; do rm -f "$f"; done
}

# startlog_save ; copies $ZIP to BOOT (read-write only for the copy), else keeps it
# in /data (BOOT missing, busy, full or read-only), where the next problem report
# takes it along
startlog_save() {
	local free kb
	kb=$(($(wc -c <"$ZIP") / 1024 + 1))
	if boot_open; then
		free=$(free_kb "$BOOT")
		case $free in
		'' | *[!0-9]*) free=0 ;;
		esac
		if [ "$free" -le $((BOOT_KEEP_KB + 16384 + kb)) ]; then
			log "BOOT has only ${free} KB free, not saving the start log there"
		elif boot_rw && mkdir -p "$BOOT/andr36oid-reports" &&
			cp "$ZIP" "$BOOT/andr36oid-reports/$FILE.tmp" &&
			mv -f "$BOOT/andr36oid-reports/$FILE.tmp" "$BOOT/andr36oid-reports/$FILE"; then
			prune_start_logs "$BOOT/andr36oid-reports" "$START_KEEP"
			startlog_note "$FILE" >"$BOOT/andr36oid-start-log.txt"
			sync
			boot_close
			log "start log saved on BOOT: andr36oid-reports/$FILE ($kb KB)"
			return 0
		else
			rm -f "$BOOT/andr36oid-reports/$FILE.tmp" 2>/dev/null
			log "BOOT can't be written, keeping the start log in $START_KEPT"
		fi
		boot_close
	else
		log "BOOT can't be opened, keeping the start log in $START_KEPT"
	fi
	mkdir -p "$START_KEPT" && cp "$ZIP" "$START_KEPT/$FILE" || return 1
	prune_start_logs "$START_KEPT" "$START_KEEP"
	log "start log kept in $START_KEPT/$FILE"
}

startlog_finish() {
	boot_close
	rm -rf "$START_WORK"
	rmdir "$LOCK" 2>/dev/null
}

# startlog_mode ; at every start. Sleeps while the start runs (a few getprop calls)
# and ends when it is done: that is all on a normal start. Only when Android hasn't
# finished after START_WAIT (START_WAIT_FIRST on the first start of a card or of a
# new version) it saves one start log. It runs once per start, and BOOT keeps the newest 3.
startlog_mode() {
	local limit=$START_WAIT first=no waited=0 tries=0 f build
	build=$(getprop ro.build.fingerprint)
	# the first start of this /data: PackageManager writes packages.xml during it
	# (the same test as the first-start boot animation)
	if [ ! -e /data/system/packages.xml ] && [ ! -e /data/system/packages-backup.xml ]; then
		limit=$START_WAIT_FIRST
		first=yes
	elif [ "$(cat "$START_BUILD" 2>/dev/null)" != "$build" ]; then
		# the first start of a new version (the updater keeps /data): Android
		# optimizes every app again, which can take minutes on this chip
		limit=$START_WAIT_FIRST
		first="no, first start of a new version"
	fi
	while [ "$(getprop sys.boot_completed)" != 1 ]; do
		[ "$waited" -ge "$limit" ] && break
		sleep "$START_STEP"
		waited=$((waited + START_STEP))
	done
	if [ "$(getprop sys.boot_completed)" = 1 ]; then
		[ "$first" = no ] || echo "$build" >"$START_BUILD"
		return 0
	fi
	if [ -e "$START_PENDING" ]; then
		log "the start didn't finish, but make-report.txt saves a full report"
		return 0
	fi
	log "the start didn't finish in $((limit / 60)) minutes, saving a start log"
	# one at a time with the reports
	while ! mkdir "$LOCK" 2>/dev/null; do
		[ "$tries" -ge 12 ] && { log "a report is being saved, no start log"; return 0; }
		sleep 5
		tries=$((tries + 1))
	done
	trap startlog_finish EXIT
	trap 'exit 143' TERM INT
	NAME=start-log-$(date +%Y%m%d-%H%M%S)
	WORK=$START_WORK/$NAME
	rm -rf "$START_WORK"
	mkdir -p "$WORK" || return 1
	startlog_collect $((limit / 60)) "$first"
	if command -v zip >/dev/null 2>&1; then
		FILE=$NAME.zip
		ZIP=$START_WORK/$FILE
		(cd "$START_WORK" && zip -q -r -X "$ZIP" "$NAME") || { log "packing the start log failed"; return 1; }
	else
		# no zip: the files one after the other in one text file
		FILE=$NAME.txt
		ZIP=$START_WORK/$FILE
		for f in "$WORK"/*; do
			echo "======== ${f##*/}"
			cat "$f"
		done >"$ZIP"
	fi
	startlog_save
}

finish() {
	boot_close
	rmdir "$LOCK" 2>/dev/null
}

if [ "$MODE" = startlog ]; then
	startlog_mode
	exit $?
fi
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
	dtb_info
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
# the newest start log that couldn't go to BOOT (startlog_save), already cleaned
# shellcheck disable=SC2012 # names are start-log-<date>, sorted by name
f=$(ls "$START_KEPT"/start-log-* 2>/dev/null | sort | tail -n 1)
[ -n "$f" ] && cp "$f" "$WORK/"

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
