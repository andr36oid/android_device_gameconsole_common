#!/system/bin/sh
# shellcheck shell=ksh
#
# andr36oid-apkinstall: the root half of the APK installer. The app runs as the system
# user, which can't read EASYROMS, so this lists and installs the APKs in its apk folder.
#
# The app writes $DIR/request and starts us with ctl.start:
#   action=scan     wait up to 90 s for EASYROMS, then write $DIR/list:
#                   "SIZE<tab>MTIME<tab>MODE<tab>NAME" per APK (top level of apk/
#                   only). MODE: "ask" for someone's own APK. One with a <Name>.yml
#                   next to it giving its package and version_code is ours (the
#                   bundled apps' .yml, see foss-apps/README.md) and never asked
#                   about: "silent" when it updates an installed older version, or
#                   is new and ticked ("checked: false" isn't); "current" when there
#                   is nothing to do: that version or a newer one is installed, it is
#                   unticked, or the console had it before and the user removed it
#                   or left it out in the setup (gameconsole-emu's installed. mark)
#   action=install  install every NAME in $DIR/install (one per line), writing
#                   "ok<tab>NAME" or "fail<tab>NAME<tab>REASON" per APK to $DIR/result
# $DIR/status says "running …", "done" or "error CODE" (noeasyroms, request).
#
# APKINSTALL_* variables are for testing on a PC (tests/run-tests.sh):
#   APKINSTALL_DIR       in place of /data/misc/andr36oid-apkinstall
#   APKINSTALL_EASYROMS  EASYROMS folder ("" = not mounted)
#   APKINSTALL_PM        command run in place of pm (gets the same arguments)

DIR=${APKINSTALL_DIR:-/data/misc/andr36oid-apkinstall}
EASYROMS_ID="public:179,7"
WAIT_S=90

log() {
	[ -x /system/bin/log ] && /system/bin/log -t andr36oid-apkinstall "$*"
	return 0
}

testing() {
	[ -n "${APKINSTALL_DIR+x}" ]
}

status() {
	echo "$*" >"$DIR/status.tmp"
	mv -f "$DIR/status.tmp" "$DIR/status"
}

pm_cmd() {
	if testing && [ -n "$APKINSTALL_PM" ]; then
		$APKINSTALL_PM "$@"
	else
		pm "$@"
	fi
}

# EASYROMS folder, empty while it isn't mounted
easyroms() {
	local id state uuid
	if testing; then
		echo "$APKINSTALL_EASYROMS"
		return 0
	fi
	sm list-volumes public </dev/null 2>/dev/null | while read -r id state uuid; do
		[ "$id" = "$EASYROMS_ID" ] && [ "$state" = mounted ] && [ -d "/mnt/media_rw/$uuid" ] &&
			echo "/mnt/media_rw/$uuid"
	done
}

# The apk folder; FAT names are case-insensitive but the exFAT driver keeps the case
apk_dir() {
	local d
	for d in "$1/apk" "$1/APK" "$1/Apk"; do
		[ -d "$d" ] && { echo "$d"; return 0; }
	done
	return 1
}

# a plain file name: no folders, no line breaks or tabs
plain_name() {
	case $1 in
	"" | . | .. | */* | *"
"* | *"	"*) return 1 ;;
	esac
	return 0
}

# yml_get <file> <key> ; a top-level value of a .yml, without quotes
yml_get() {
	sed -n "s/^$2:[ 	]*//p" "$1" 2>/dev/null | head -n 1 | sed 's/[ 	]*$//; s/^"\(.*\)"$/\1/'
}

SEEN=${APKINSTALL_SEEN:-/data/misc/gameconsole}

# apk_mode <apk> ; ask, silent or current (see the top)
apk_mode() {
	local yml=${1%.*}.yml pkg vc cur
	[ -f "$yml" ] || { echo ask; return 0; }
	pkg=$(yml_get "$yml" package)
	vc=$(yml_get "$yml" version_code)
	case $vc in '' | *[!0-9]*) echo ask; return 0 ;; esac
	[ -n "$pkg" ] || { echo ask; return 0; }
	cur=$(pm_cmd list packages --show-versioncode "$pkg" </dev/null 2>/dev/null |
		sed -n "s/^package:$pkg versionCode:\([0-9]*\).*/\1/p" | head -n 1)
	if [ -n "$cur" ]; then
		[ "$cur" -ge "$vc" ] && echo current || echo silent
	elif [ -e "$SEEN/installed.$pkg" ] || [ "$(yml_get "$yml" checked)" = false ]; then
		echo current
	else
		echo silent
	fi
}

scan() {
	local e="" i=0 dir f name
	while [ $i -lt $WAIT_S ]; do
		e=$(easyroms)
		[ -n "$e" ] && break
		testing && break
		sleep 1
		i=$((i + 1))
	done
	: >"$DIR/list.tmp"
	if [ -z "$e" ]; then
		mv -f "$DIR/list.tmp" "$DIR/list"
		status "error noeasyroms"
		return 0
	fi
	if dir=$(apk_dir "$e"); then
		for f in "$dir"/*; do
			[ -f "$f" ] || continue
			name=${f##*/}
			case $name in
			*.[aA][pP][kK]) ;;
			*) continue ;;
			esac
			case $name in .*) continue ;; esac
			plain_name "$name" || continue
			printf '%s\t%s\t%s\t%s\n' "$(stat -c %s "$f")" "$(stat -c %Y "$f")" \
				"$(apk_mode "$f")" "$name" >>"$DIR/list.tmp"
		done
	fi
	mv -f "$DIR/list.tmp" "$DIR/list"
	log "scan: $(wc -l <"$DIR/list") APKs in ${dir:-$e (no apk folder)}"
	status "done"
}

install() {
	local e dir name n=0 total out size
	e=$(easyroms)
	if [ -z "$e" ] || ! dir=$(apk_dir "$e"); then
		status "error noeasyroms"
		return 0
	fi
	total=$(grep -c . "$DIR/install" 2>/dev/null)
	: >"$DIR/result.tmp"
	while IFS= read -r name; do
		[ -n "$name" ] || continue
		n=$((n + 1))
		status "running $n $total $name"
		if ! plain_name "$name" || [ ! -f "$dir/$name" ]; then
			printf 'fail\t%s\t%s\n' "$name" "missing" >>"$DIR/result.tmp"
			continue
		fi
		# streamed in, so the package manager never needs to read EASYROMS itself
		size=$(stat -c %s "$dir/$name")
		if out=$(pm_cmd install -r --user 0 -S "$size" <"$dir/$name" 2>&1) &&
			echo "$out" | grep -q '^Success'; then
			printf 'ok\t%s\n' "$name" >>"$DIR/result.tmp"
			log "installed $name"
		else
			out=$(echo "$out" | grep -v '^$' | tail -n 1 | tr '\t' ' ')
			printf 'fail\t%s\t%s\n' "$name" "${out:-pm failed}" >>"$DIR/result.tmp"
			log "failed $name: $out"
		fi
	done <"$DIR/install"
	mv -f "$DIR/result.tmp" "$DIR/result"
	status "done"
}

mkdir -p "$DIR"
case $(grep '^action=' "$DIR/request" 2>/dev/null | head -n 1) in
action=scan) scan ;;
action=install) install ;;
*) status "error request" ;;
esac
chown system:system "$DIR"/* 2>/dev/null
chmod 0660 "$DIR"/* 2>/dev/null
exit 0
