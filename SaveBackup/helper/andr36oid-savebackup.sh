#!/system/bin/sh
# shellcheck shell=ksh
#
# andr36oid-savebackup: the root half of Settings > Save backup. The app runs as the
# system user, which can't read EASYROMS, the internal storage or the emulators' folders.
# This finds the mounted drives, runs the backup engine (Java, in the app's own APK) as
# root with app_process, and then gives the files it made on the internal storage the
# owner of their folder, so the Files app and the emulators can use them.
#
# The app writes $DIR/request and starts us with ctl.start; see Engine.java for the
# request and the files the engine writes back ($DIR/status, last, index, plan, result).
#
# Everything the engine reads or writes is under the internal storage (/data/media/0),
# EASYROMS or a USB drive (/mnt/media_rw/<uuid>); the engine checks every path it restores
# to. The owner fix-up here only touches paths under the internal storage.
#
# Sync saves: action=sync_prefs stops the Syncthing app (it would write its old settings
# back), lets it reach all files, and after the engine wrote its settings gives the file
# back to the app. action=sync_key copies its REST key for the app.
#
# SAVEBACKUP_* variables are for testing on a PC (tests/run-tests.sh):
#   SAVEBACKUP_RUN       command that runs the engine instead of app_process
#   SAVEBACKUP_EASYROMS  EASYROMS folder ("" = not mounted), SAVEBACKUP_USB a USB folder

DIR=${SAVEBACKUP_DIR:-/data/misc/andr36oid-savebackup}
MEDIA=${SAVEBACKUP_MEDIA:-/data/media/0}
RA_DATA=${SAVEBACKUP_RA_DATA:-/data/user/0/com.retroarch}
PPSSPP_DATA=${SAVEBACKUP_PPSSPP_DATA:-/data/user/0/org.ppsspp.ppsspp}
ST=com.github.catfriend1.syncthingfork
ST_DATA=${SAVEBACKUP_ST_DATA:-/data/user/0/$ST}
PKG=org.andr36oid.savebackup
APK=/system/app/SaveBackup/SaveBackup.apk
EASYROMS_ID=$(getprop sys.andr36oid.easyroms.id 2>/dev/null)
if [ -z "$EASYROMS_ID" ] && [ "$(getprop sys.andr36oid.easyroms.ready 2>/dev/null)" != 1 ]; then
	EASYROMS_ID="public:179,7"
fi

log() {
	[ -x /system/bin/log ] && /system/bin/log -t andr36oid-savebackup "$*"
	return 0
}

testing() {
	[ -n "$SAVEBACKUP_RUN" ]
}

status() {
	echo "$*" >"$DIR/status.tmp"
	mv -f "$DIR/status.tmp" "$DIR/status"
}

# "easyroms <path>" and "usb <path>" for every mounted drive
volumes() {
	local dev dir _
	if testing; then
		[ -n "$SAVEBACKUP_EASYROMS" ] && echo "easyroms $SAVEBACKUP_EASYROMS"
		[ -n "$SAVEBACKUP_USB" ] && echo "usb $SAVEBACKUP_USB"
		return 0
	fi
	# vold mounts each drive from /dev/block/vold/public:<major>,<minor> at
	# /mnt/media_rw/<uuid>, so /proc/mounts tells; `sm list-volumes` started a
	# second Java VM next to the engine's at each run
	while read -r dev dir _; do
		case $dev in /dev/block/vold/public:*) ;; *) continue ;; esac
		case $dir in /mnt/media_rw/?*) [ -d "$dir" ] || continue ;; *) continue ;; esac
		if [ "$dev" = "/dev/block/vold/$EASYROMS_ID" ]; then
			echo "easyroms $dir"
		else
			echo "usb $dir"
		fi
	done </proc/mounts
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
		$SAVEBACKUP_RUN org.andr36oid.savebackup.Engine "$@"
	else
		CLASSPATH=$(apk) app_process /system/bin org.andr36oid.savebackup.Engine "$@"
	fi
}

# what the engine made on the internal storage gets its folder's owner and SELinux label
fix_owners() {
	local p
	[ -f "$DIR/touched" ] || return 0
	while IFS= read -r p; do
		case $p in
		*/../*|*/..|*/./*) continue ;;
		"$MEDIA"/*) ;;
		*) continue ;;
		esac
		[ -e "$p" ] || continue
		testing && continue
		chown "$(stat -c %u:%g "${p%/*}")" "$p" 2>/dev/null
		if [ -d "$p" ]; then
			chmod 0775 "$p" 2>/dev/null
		else
			chmod 0664 "$p" 2>/dev/null
		fi
		restorecon "$p" 2>/dev/null
	done <"$DIR/touched"
	rm -f "$DIR/touched"
}

# before the engine writes the Syncthing app's settings: stop it, and let it (or not) run
# in the background and reach EASYROMS and the internal storage
sync_before() {
	local on=$1
	testing && return 0
	am force-stop "$ST" </dev/null >/dev/null 2>&1
	if [ "$on" = 1 ]; then
		appops set "$ST" MANAGE_EXTERNAL_STORAGE allow </dev/null >/dev/null 2>&1
		dumpsys deviceidle whitelist +"$ST" </dev/null >/dev/null 2>&1
	else
		dumpsys deviceidle whitelist -"$ST" </dev/null >/dev/null 2>&1
	fi
	return 0
}

# the settings file the engine wrote belongs to the Syncthing app
sync_after() {
	local owner
	testing && return 0
	[ -d "$ST_DATA/shared_prefs" ] || return 0
	owner=$(stat -c %u:%g "$ST_DATA") || return 0
	chown -R "$owner" "$ST_DATA/shared_prefs" 2>/dev/null
	chmod 0771 "$ST_DATA/shared_prefs" 2>/dev/null
	chmod 0660 "$ST_DATA/shared_prefs/"* 2>/dev/null
	restorecon -R "$ST_DATA/shared_prefs" 2>/dev/null
	return 0
}

umask 022
mkdir -p "$DIR"
rm -f "$DIR/touched"
status "running start"
ACTION=$(sed -n 's/^action=//p' "$DIR/request" 2>/dev/null | head -n 1)

VOLS=$(volumes)
set -- --dir "$DIR" --media "$MEDIA" --ra-data "$RA_DATA" --ppsspp-data "$PPSSPP_DATA" \
	--st-data "$ST_DATA"
while read -r kind path; do
	case $kind in
	easyroms) set -- "$@" --easyroms "$path" ;;
	usb) set -- "$@" --usb "$path" ;;
	esac
done <<EOF
$VOLS
EOF

log "start: $(tr '\n' ' ' <"$DIR/request" 2>/dev/null)"
[ "$ACTION" = sync_prefs ] && sync_before "$(sed -n 's/^on=//p' "$DIR/request" | head -n 1)"
engine "$@"
[ "$ACTION" = sync_prefs ] && sync_after
fix_owners
testing || { sync; chown system:system "$DIR"/* 2>/dev/null; }

# the engine always ends with done or error; anything else means it didn't run
case $(cat "$DIR/status" 2>/dev/null) in
done*|error*) ;;
*) status "error crashed" ;;
esac
log "end: $(cat "$DIR/status" 2>/dev/null)"
exit 0
