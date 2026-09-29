#!/system/bin/sh
# shellcheck shell=ksh
#
# andr36oid-report-check: after every start (sys.boot_completed, which also comes after a
# system_server restart), looks for signs that the last one ended in a crash: a kernel crash
# record kept by pstore (oops or panic, the kernel then restarts after 7 s), or Android's own
# restart records in the dropbox (system_server crashed or hung). Only if there is a new one
# it wakes the Problem report app, which offers to save a report while the logs are still
# there. Looking here (root, a shell for a moment) instead of in the app keeps the app from
# starting at every boot.
#
# Remembers in $DIR/boot-check what it has seen:
#   pstore <sha1 of the first 4 kB of the kernel crash record>
#   dropbox <time of the last look, s>
# The first time it takes over what the app remembered, so an old crash isn't news again.
# Times are in seconds: the shell (mksh) only has 32-bit numbers, dropbox names are in ms.

PSTORE=${PSTORE:-/sys/fs/pstore}
DROPBOX=${DROPBOX:-/data/system/dropbox}
DIR=${DIR:-/data/misc/andr36oid-report}
APP_PREFS=${APP_PREFS:-/data/user/0/org.andr36oid.report/shared_prefs/boot.xml}
UPTIME=${UPTIME:-/proc/uptime}
CMD=${CMD:-/system/bin/cmd}
LOG=${LOG:-/system/bin/log}
STATE=$DIR/boot-check
RESTART_TAGS="system_server_crash system_server_watchdog system_server_native_crash"

log() {
	"$LOG" -t andr36oid-report "$*"
}

uptime_s() {
	local up rest
	read -r up rest <"$UPTIME"
	echo "${up%%.*}"
}

# ms_to_s <ms> : whole seconds, without arithmetic
ms_to_s() {
	case $1 in
	???*) echo "${1%???}" ;;
	*) echo 0 ;;
	esac
}

# state <key> : a value from the state file, or from the app's own preferences before that
state() {
	if [ -f "$STATE" ]; then
		sed -n "s/^$1 //p" "$STATE" | head -n 1
	elif [ -f "$APP_PREFS" ]; then
		case $1 in
		pstore) sed -n 's/.*name="pstore_record">\([0-9a-f]*\)<.*/\1/p' "$APP_PREFS" | head -n 1 ;;
		dropbox) ms_to_s "$(sed -n 's/.*name="dropbox_time" value="\([0-9]*\)".*/\1/p' \
			"$APP_PREFS" | head -n 1)" ;;
		esac
	fi
}

main() {
	local seen since now crashed=0 f fp t tag

	seen=$(state pstore)
	since=$(state dropbox)
	now=$(date +%s)
	# The first time, only look at this kernel start: older entries are history.
	case $since in
	'' | *[!0-9]*) since=$((now - $(uptime_s))) ;;
	esac

	for f in "$PSTORE"/dmesg-ramoops*; do
		[ -f "$f" ] || continue
		fp=$(head -c 4096 "$f" | sha1sum | cut -d ' ' -f 1)
		if [ -n "$fp" ] && [ "$fp" != "$seen" ]; then
			log "kernel crash record from the last start: $f"
			seen=$fp
			crashed=1
		fi
		break
	done

	for tag in $RESTART_TAGS; do
		for f in "$DROPBOX/$tag"@*; do
			[ -f "$f" ] || continue
			t=${f##*@}
			t=$(ms_to_s "${t%%.*}")
			case $t in
			'' | *[!0-9]*) continue ;;
			esac
			if [ "$t" -gt "$since" ]; then
				log "Android restarted after $tag at $t"
				crashed=1
				break
			fi
		done
	done

	mkdir -p "$DIR"
	printf 'pstore %s\ndropbox %s\n' "$seen" "$now" >"$STATE.tmp" && mv -f "$STATE.tmp" "$STATE"

	if [ "$crashed" = 1 ]; then
		"$CMD" activity broadcast -n org.andr36oid.report/.CrashNoticeReceiver \
			>/dev/null 2>&1 </dev/null || log "couldn't tell the app"
	fi
}

main
