#!/system/bin/sh
# Runs after persistent properties are loaded, before zygote. HOTFIX_* overrides
# point the host tests at a fake root, cards and unpacker.

DIR=${HOTFIX_STATE:-/data/misc/andr36oid-hotfix}
BOOT_MNT=${HOTFIX_BOOT_MNT:-/mnt/andr36oid-hotfix-boot}
EASYROMS_MNT=${HOTFIX_EASYROMS_MNT:-/mnt/andr36oid-hotfix-roms}
BOOT_LOCK=${HOTFIX_BOOT_LOCK:-/dev/andr36oid-boot.lock}
UNPACK=${HOTFIX_UNPACK:-/system/bin/andr36oid-hotfix-unpack}
LIB=${HOTFIX_LIB:-/system/etc/andr36oid-hotfix-lib.sh}
SH=${HOTFIX_SH:-/system/bin/sh}
R=${HOTFIX_ROOT:-}
BOOT=
LOCKED=
MOUNTED=
ROMS_MOUNTED=
APPLYING=
export HOTFIX_DIR HOTFIX_NAME HOTFIX_STORE
umask 077

cleanup() {
	[ -z "$APPLYING" ] || setprop sys.andr36oid.hotfix.applying 0
	[ -z "$ROMS_MOUNTED" ] || release_roms
	[ -z "$MOUNTED" ] || umount "$BOOT_MNT"
	[ -z "$LOCKED" ] || rm -rf "$BOOT_LOCK"
}
trap 'cleanup' EXIT
trap 'exit 0' HUP INT TERM

release_roms() {
	if ! "$UNPACK" --wait 5 umount "$EASYROMS_MNT" 2>>"$DIR/log"; then
		note "EASYROMS: unmount failed, detaching private mount"
		"$UNPACK" --wait 5 umount -l "$EASYROMS_MNT" 2>>"$DIR/log" || return 1
	fi
	ROMS_MOUNTED=
}

hotfix_folder() {
	local d n
	for d in "$1"/*/; do
		n=${d%/}
		n=${n##*/}
		if [ "$(printf '%s' "$n" | tr '[:upper:]' '[:lower:]')" = hotfix ]; then
			printf '%s\n' "${d%/}"
			return
		fi
	done
}

collect() {
	local folder zip bytes lower
	source=$2
	folder=$(hotfix_folder "$1")
	[ -n "$folder" ] || return 0
	for zip in "$folder/"*.fix.zip; do
		[ -f "$zip" ] || continue
		HOTFIX_NAME=$(printf '%s' "${zip##*/}" | tr '\t\r\n' '___')
		description=
		lower=$(printf '%s' "${zip##*/}" | tr '[:upper:]' '[:lower:]')
		if [ "$source" = EASYROMS ] && [ -e "$DIR/work/boot-files/$lower" ]; then
			status skipped duplicate
			continue
		fi
		[ "$source" != BOOT ] || mkdir "$DIR/work/boot-files/$lower"
		count=$((count + 1))
		if [ "$count" -gt 16 ]; then status skipped limit; continue; fi
		bytes=$("$UNPACK" --wait 5 stat -c %s "$zip" 2>>"$DIR/log")
		case $bytes in ''|*[!0-9]*) status failed storage; continue ;; esac
		if [ "$bytes" -gt "$3" ]; then status failed invalid; continue; fi
		if [ "$((staged + bytes))" -gt 268435456 ]; then status skipped limit; continue; fi
		if "$UNPACK" --wait 10 cp "$zip" "$DIR/work/$source/" 2>>"$DIR/log"; then
			staged=$((staged + bytes))
		else
			rm -f "$DIR/work/$source/${zip##*/}"
			status failed storage
		fi
	done
}

easyroms_collect() {
	local dev
	if [ "$(getprop sys.andr36oid.easyroms.ready)" != 1 ]; then
		note "EASYROMS: lookup not ready, skipping"
		return 0
	fi
	dev=$(getprop sys.andr36oid.easyroms.dev)
	case $dev in
	'') note "EASYROMS: no partition on this install"; return 0 ;;
	*/*) note "EASYROMS: invalid device, skipping"; return 0 ;;
	mmcblk[0-9]*p7) ;;
	*) note "EASYROMS: invalid device, skipping"; return 0 ;;
	esac
	dev=$R/dev/block/$dev
	# Read the exFAT dirty/error flags without repairing or writing the card.
	if ! "$UNPACK" --wait 5 "$UNPACK" --check-exfat "$dev" 2>>"$DIR/log"; then
		note "EASYROMS: dirty or unreadable, skipping"
		return 0
	fi
	mkdir -p "$EASYROMS_MNT" || return 0
	# vold uses the in-kernel exfat driver, with these flags and media_rw's mask.
	if "$UNPACK" --wait 5 mount -t exfat -o ro,nodev,nosuid,noexec,noatime,uid=0,gid=1023,fmask=0007,dmask=0007 "$dev" "$EASYROMS_MNT" 2>>"$DIR/log"; then
		ROMS_MOUNTED=1
		collect "$EASYROMS_MNT" EASYROMS 134217728
		release_roms || return 1
	else
		note "EASYROMS: read-only mount failed or timed out, skipping"
		# A timeout may have arrived just as mount succeeded.
		"$UNPACK" --wait 5 umount -l "$EASYROMS_MNT" 2>/dev/null
	fi
	return 0
}

boot_mount() {
	if [ -n "${HOTFIX_BOOT:-}" ]; then BOOT=$HOTFIX_BOOT; return 0; fi
	local n=0 pid dev devs
	while ! mkdir "$BOOT_LOCK" 2>/dev/null; do
		pid=$(cat "$BOOT_LOCK/pid" 2>/dev/null)
		if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
			rm -rf "$BOOT_LOCK"
			continue
		fi
		n=$((n + 1))
		[ "$n" -gt 150 ] && return 1
		sleep 0.2
	done
	LOCKED=1
	echo $$ >"$BOOT_LOCK/pid"
	grep -o ' /mnt/andr36oid-[a-z]*-boot ' /proc/mounts 2>/dev/null | while read -r m; do
		umount "$m" 2>/dev/null
	done
	# The system disk, including an eMMC install with /data on a separate SD card.
	case $(getprop ro.boot.boot_devices) in
	ff390000.dwmmc) devs="$R/dev/block/platform/ff390000.dwmmc/by-name/BOOT $R/dev/block/mmcblk2p1" ;;
	''|ff370000.dwmmc) devs="$R/dev/block/platform/ff370000.dwmmc/by-name/BOOT $R/dev/block/mmcblk0p1" ;;
	*) return 1 ;;
	esac
	mkdir -p "$BOOT_MNT" || return 1
	for dev in ${HOTFIX_BOOT_DEVS:-$devs}; do
		[ -e "$dev" ] || continue
		mount -t vfat -o ro,noatime "$dev" "$BOOT_MNT" 2>/dev/null || continue
		BOOT=$BOOT_MNT
		MOUNTED=1
		return 0
	done
	return 1
}

note() {
	log -t andr36oid-hotfix "$*"
	printf '%s\n' "$*" >>"$DIR/log"
}

status() {
	# TSV: name, result, reason code, description, source.
	printf '%s\t%s\t%s\t%s\t%s\n' "$HOTFIX_NAME" "$1" "$2" "$description" "$source" >>"$DIR/status"
	note "$source/$HOTFIX_NAME: $1 ($2)"
	[ "$2" != duplicate ] || note "BOOT wins over EASYROMS for $HOTFIX_NAME"
}

mkdir -p "$DIR" || exit 0
chown root:system "$DIR"
chmod 0750 "$DIR"
# No mounts survive a restart. Never remove this directory during the same boot.
[ -e "$DIR/started" ] && [ "$(cat "$DIR/started")" = "$(cat /proc/sys/kernel/random/boot_id)" ] && exit 0
rm -rf "$DIR/work" "$DIR/bind"
mkdir -p "$DIR/work/BOOT" "$DIR/work/EASYROMS" "$DIR/work/boot-files" "$DIR/work/boot-names" "$DIR/bind" || exit 0
cat /proc/sys/kernel/random/boot_id >"$DIR/started"
: >"$DIR/status"
: >"$DIR/log"
chown root:system "$DIR/status" "$DIR/log"
chmod 0640 "$DIR/status" "$DIR/log"

version=$(getprop ro.andr36oid.version)
incremental=$(getprop ro.product.build.version.incremental)
build=$version'|'$incremental
note "running build: $build"
reason=
case $(getprop persist.andr36oid.hotfix.paused) in
crash) reason=paused ;;
1) reason=disabled ;;
esac
tries=$(getprop persist.andr36oid.hotfix.tries)
case $tries in 1|2) ;; *) tries=0 ;; esac
if [ -z "$reason" ] && [ "$tries" -ge 2 ]; then
	if setprop persist.andr36oid.hotfix.paused crash; then
		setprop persist.andr36oid.hotfix.tries 0
	fi
	reason=paused
	note "two starts did not finish, pausing hotfixes"
fi
# A safe start still records the pause after two failed starts.
if [ "$(getprop ro.boot.andr36oid_safe)" = 1 ]; then reason=safe; fi
count=0
staged=0
source=BOOT
if boot_mount; then
	collect "$BOOT" BOOT 67108864
else
	HOTFIX_NAME=BOOT
	description=
	status skipped unavailable
fi
if [ -n "$MOUNTED" ]; then umount "$BOOT_MNT"; MOUNTED=; fi
if [ -n "$LOCKED" ]; then rm -rf "$BOOT_LOCK"; LOCKED=; fi
easyroms_collect || exit 0

count=0
used=0
counted=
for zip in "$DIR/work/BOOT/"*.fix.zip "$DIR/work/EASYROMS/"*.fix.zip; do
	[ -f "$zip" ] || continue
	source=${zip%/*}
	source=${source##*/}
	count=$((count + 1))
	HOTFIX_NAME=$(printf '%s' "${zip##*/}" | tr '\t\r\n' '___')
	description=
	HOTFIX_DIR=$DIR/work/$count
	HOTFIX_STORE=$DIR/bind/$count
	if ! size=$("$UNPACK" --wait 10 "$UNPACK" "$zip" "$HOTFIX_DIR" "$((268435456 - used))" 2>>"$DIR/log"); then
		rm -rf "$HOTFIX_DIR"
		status failed invalid
		continue
	fi
	case $size in ''|*[!0-9]*) status failed invalid; continue ;; esac
	used=$((used + size))
	wanted=
	while IFS='=' read -r key value; do
		case $key in
		name) HOTFIX_NAME=$value ;;
		description) description=$value ;;
		build) wanted=$value ;;
		esac
	done <"$HOTFIX_DIR/manifest.txt"
	if [ "$source" = BOOT ]; then
		mkdir -p "$DIR/work/boot-names/$HOTFIX_NAME"
	elif [ -e "$DIR/work/boot-names/$HOTFIX_NAME" ]; then
		status skipped duplicate
		continue
	fi
	if [ -n "$reason" ]; then status skipped "$reason"; continue; fi
	if [ -z "$version" ] || [ -z "$incremental" ] || [ "$wanted" != "$build" ]; then
		status skipped build
		continue
	fi
	if [ -z "$counted" ]; then
		# Write before running anything: even a power cut in the first hotfix counts.
		if ! setprop persist.andr36oid.hotfix.tries "$((tries + 1))"; then
			status skipped counter
			continue
		fi
		counted=1
	fi
	mkdir -p "$HOTFIX_STORE" || { status failed storage; continue; }
	APPLYING=1
	setprop sys.andr36oid.hotfix.applying 1
	note "$HOTFIX_NAME: starting"
	"$UNPACK" --run "$SH" "$LIB" >>"$DIR/log" 2>&1
	result=$?
	setprop sys.andr36oid.hotfix.applying 0
	APPLYING=
	case $result in
	0) status ran ok ;;
	124|137) status failed timeout ;;
	*) status failed exit ;;
	esac
done
rm -rf "$DIR/work/BOOT" "$DIR/work/EASYROMS"
exit 0
