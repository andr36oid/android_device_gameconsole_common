#!/system/bin/sh
# shellcheck shell=ksh
#
# gameconsole-emu: provisions the bundled emulators after boot.
#
# 0. On the very first start only, while the setup wizard is still on screen:
#    installs the first-start launcher and makes it the only home app, so the
#    tutorial comes up when the wizard ends. See first_start below.
# 1. Installs the APKs from /system/etc/gameconsole/preinstall as regular user
#    apps. They keep their F-Droid signature, so F-Droid can update them, and
#    the user can uninstall them. An app the user removed stays removed, an
#    older installed version gets upgraded when the ROM ships a newer one.
# 2. Gives RetroArch a profile for the built-in pad and, on a fresh install
#    only, ArkOS-like settings (hotkeys, saves next to the game). A
#    retroarch.cfg that exists is never touched. Core option defaults for
#    the slow Cortex-A35 (retroarch-core-options.cfg) go next to it.
# 3. Seeds the bundled libretro cores into RetroArch's core directory, which is
#    where both RetroArch and Daijishou's RetroArch players look for them.
#    Cores the user replaced (e.g. via RetroArch's online updater) are kept.
# 4. Makes the usual ROM folders (ArkOS/dArkOS names) on a fresh EASYROMS
#    partition, with a README telling which system goes where. Only once per
#    card, and never on a card that already has any of them.
# 5. Points RetroArch's system folder (where the cores look for BIOS files) at
#    the bios folder on EASYROMS, like ArkOS, or on the internal storage for a
#    card made without EASYROMS (.noroms). A folder the user picked in
#    RetroArch is kept.
# 6. Gives Daijishou a platform per ROM folder system with our players, once,
#    before its first start. Run as "gameconsole-emu.sh post-fs-data" (before
#    system_server) it only writes the folder access that needs. Later, when
#    the games on EASYROMS changed, Daijishou asks to look for games again.

BASE=/system/etc/gameconsole
STATE=/data/misc/gameconsole
LOG_FILE=$STATE/gameconsole-emu.log
RA_PKG=com.retroarch
RA_DATA=/data/user/0/$RA_PKG
FS_PKG=org.andr36oid.firststart
FS_APK=$BASE/firststart/FirstStart.apk

log() {
	/system/bin/log -t gameconsole-emu "$*"
	echo "$*"
	# also kept on the card (logcat is gone after a restart): LOG_FILE,
	# shortened at every start (log_trim)
	[ -n "${LOG_FILE:-}" ] && [ -d "${LOG_FILE%/*}" ] &&
		echo "$(date '+%m-%d %H:%M:%S') $*" >>"$LOG_FILE"
}

# log_trim ; keeps the last 400 lines of LOG_FILE
log_trim() {
	[ -f "$LOG_FILE" ] || return 0
	[ "$(wc -l <"$LOG_FILE")" -gt 400 ] || return 0
	tail -n 400 "$LOG_FILE" >"$LOG_FILE.new" && mv -f "$LOG_FILE.new" "$LOG_FILE"
}

sha1() {
	sha1sum "$1" | cut -d' ' -f1
}

# installed_vc <package> ; prints the installed versionCode, nothing if absent
installed_vc() {
	pm list packages --show-versioncode "$1" </dev/null 2>/dev/null |
		sed -n "s/^package:${1//./\\.} versionCode:\([0-9]*\).*/\1/p"
}

setup_done() {
	[ "$(settings get secure user_setup_complete </dev/null 2>/dev/null)" = 1 ]
}

# Daijishou's home activity. While the first-start launcher is home it is
# disabled, so the launcher is the only home app with priority 0.
DJ_HOME=com.magneticchen.daijishou/com.magneticchen.daijishou.activities.BootstrapActivity
DJ_HIDDEN=$STATE/firststart.daijishou-hidden

# show_daijishou ; enables Daijishou's home activity again
show_daijishou() {
	local out
	if out=$(pm enable --user 0 "$DJ_HOME" </dev/null 2>&1) &&
		[ "${out%enabled}" != "$out" ]; then
		rm -f "$DJ_HIDDEN"
		log "Daijishou's home activity is enabled again"
	else
		log "enabling Daijishou's home activity failed: $out"
	fi
}

# first_start: the first-start launcher (org.andr36oid.firststart, built from
# FirstStart/ in the common device tree) is a tiny user app with a home
# activity that opens the quick start guide's tutorial. It is installed while
# the setup wizard runs, and made the only home app besides the wizard:
# Daijishou's home activity is disabled first. So there is never a moment with
# two home apps of the same priority, and Android never asks which one to
# use, however fast the wizard is:
# - the wizard's home activity has priority 9 and wins until it disables it,
# - after that, home is the one app with priority 0 that's there: the
#   launcher, or Daijishou if something failed. If the wizard ends between
#   disabling Daijishou and the install, Settings' FallbackHome shows for a
#   moment (it checks every 500 ms for a real home app) and hands over to the
#   launcher as soon as it's installed.
# The guide undoes all of it when the tutorial is done: it disables the
# launcher's home activity, enables Daijishou's, gives it the home role and
# uninstalls the launcher.
# Only on the first start: consoles set up before this build don't get it,
# and it runs once (the marker file), so an uninstall sticks.
first_start() {
	local out done=$STATE/firststart.done

	# Repair, every start: Daijishou stays hidden only while the launcher is
	# there (a restart in the middle, or the guide didn't get to it)
	if [ -e "$DJ_HIDDEN" ] && [ -z "$(installed_vc "$FS_PKG")" ]; then
		show_daijishou
	fi

	[ -f "$FS_APK" ] || return 0
	[ -e "$done" ] && return 0
	if setup_done; then
		log "setup was done before, no first-start launcher"
		touch "$done"
		return 0
	fi

	# The marker first, so a restart right after this still gets repaired
	touch "$DJ_HIDDEN"
	if ! out=$(pm disable --user 0 "$DJ_HOME" </dev/null 2>&1) ||
		[ "${out%disabled}" = "$out" ]; then
		log "disabling Daijishou's home activity failed, no first-start launcher: $out"
		show_daijishou
		touch "$done"
		return 0
	fi
	if ! out=$(pm install -r --user 0 "$FS_APK" </dev/null 2>&1); then
		log "installing the first-start launcher failed: $out"
		show_daijishou
		touch "$done"
		return 0
	fi
	touch "$done"
	# Not needed for it to be home (it's the only home app with priority 0),
	# but keeps the role in line with what is home
	if ! out=$(cmd role add-role-holder --user 0 android.app.role.HOME "$FS_PKG" </dev/null 2>&1); then
		log "giving the first-start launcher the home role failed: $out"
	fi
	log "first-start launcher installed, it is the home app until the tutorial is done"
}

install_apks() {
	local list pkg vc apk cur out

	# preinstall.list is the emulators', other packages bring their own list
	for list in "$BASE"/preinstall/*.list; do
		[ -f "$list" ] || continue
		while read -r pkg vc apk; do
			case $pkg in ''|\#*) continue ;; esac
			[ -f "$BASE/preinstall/$apk" ] || continue

			cur=$(installed_vc "$pkg")
			if [ -n "$cur" ]; then
				# remember we have seen it, so an uninstall later on sticks
				touch "$STATE/installed.$pkg"
				[ "$cur" -ge "$vc" ] && continue
			elif [ -e "$STATE/installed.$pkg" ]; then
				continue
			fi
			# don't retry a version that failed before (e.g. a differently
			# signed build of the same package is installed)
			[ "$(cat "$STATE/failed.$pkg" 2>/dev/null)" = "$vc" ] && continue

			log "installing $apk ($pkg $vc, currently: ${cur:-not installed})"
			if out=$(pm install -r -g --user 0 "$BASE/preinstall/$apk" </dev/null 2>&1); then
				touch "$STATE/installed.$pkg"
				rm -f "$STATE/failed.$pkg"
			else
				log "installing $pkg failed: $out"
				echo "$vc" >"$STATE/failed.$pkg"
			fi
		done <"$list"
	done
}

# RetroArch reads retroarch.cfg from its folder on the internal storage
RA_EXT=/data/media/0/Android/data/$RA_PKG/files
RA_EXT_PUBLIC=/storage/emulated/0/Android/data/$RA_PKG/files
AID_EXT_DATA_RW=1078

# seed_owned <src> <dest> ; copies src to dest unless the user changed dest.
# What we put there is recorded in the manifest, so a newer ROM can update it.
seed_owned() {
	local f=${2##*/} manifest=$RA_DATA/.gameconsole/seeded.sha1

	if [ -e "$2" ]; then
		[ "$(sha1 "$2")" = "$(sha1 "$1")" ] && return 0
		if ! grep -q "^$(sha1 "$2") $f\$" "$manifest"; then
			log "keeping user provided $f"
			return 0
		fi
	fi
	if ! { cp "$1" "$2.tmp" && mv -f "$2.tmp" "$2"; }; then
		log "copying $f failed"
		return 0
	fi
	grep -v " $f\$" "$manifest" >"$manifest.new"
	echo "$(sha1 "$2") $f" >>"$manifest.new"
	mv -f "$manifest.new" "$manifest"
	log "seeded $f"
}

# seed_retroarch ; the built-in pad's profile, and on a fresh install the
# settings from $BASE/retroarch/retroarch.cfg (ArkOS hotkeys, saves next to
# the game). A retroarch.cfg that exists is never touched.
seed_retroarch() {
	local own=$RA_DATA/.gameconsole auto=$RA_DATA/autoconfig/android
	local cfg=$RA_EXT/retroarch.cfg owner pad dir

	[ -d "$RA_DATA" ] || return 0
	owner=$(stat -c %u:%g "$RA_DATA")
	mkdir -p "$own" "$auto"
	touch "$own/seeded.sha1"

	# RetroArch looks for pad profiles in autoconfig/android of its data dir
	for pad in "$BASE"/retroarch/autoconfig/*.cfg; do
		[ -f "$pad" ] && seed_owned "$pad" "$auto/${pad##*/}"
	done
	chown -R "$owner" "$RA_DATA/autoconfig" "$own"
	restorecon -R "$RA_DATA/autoconfig" "$own" 2>/dev/null

	[ -f "$BASE/retroarch/retroarch.cfg" ] || return 0
	[ -e "$cfg" ] && return 0
	# vold makes Android/data when it mounts the internal storage
	[ -d "${RA_EXT%/*/*}" ] || return 0

	# RetroArch hasn't run yet: make its folder the way vold does (owner the
	# app, group ext_data_rw, setgid), only for what we create here
	for dir in "${RA_EXT%/*}" "$RA_EXT"; do
		[ -d "$dir" ] && continue
		mkdir "$dir" || { log "making $dir failed"; return 0; }
		chown "${owner%:*}:$AID_EXT_DATA_RW" "$dir"
		chmod 2770 "$dir"
	done
	if ! { cp "$BASE/retroarch/retroarch.cfg" "$cfg.tmp" && mv -f "$cfg.tmp" "$cfg"; }; then
		log "writing $cfg failed"
		rm -f "$cfg.tmp"
		return 0
	fi
	chown "${owner%:*}:$AID_EXT_DATA_RW" "$cfg"
	chmod 0660 "$cfg"
	restorecon -R "${RA_EXT%/*}" 2>/dev/null
	# then vold fixes the folder up like its own (default ACL, quota project):
	# IStorageManager.fixupAppDir, transaction 89 + 1
	service call mount 90 s16 "$RA_EXT_PUBLIC" </dev/null >/dev/null 2>&1
	log "seeded retroarch.cfg"
}

# seed_core_options ; core option defaults tuned for the Cortex-A35
# ($BASE/retroarch/retroarch-core-options.cfg), next to retroarch.cfg.
# RetroArch reads this file for every core that has no options file of its own
# yet (config/<core>/<core>.opt); once the user changes an option, RetroArch
# writes that per-core file and this one no longer matters for the core.
# Written when missing, updated by a newer ROM only while unchanged.
seed_core_options() {
	local src=$BASE/retroarch/retroarch-core-options.cfg
	local dest=$RA_EXT/retroarch-core-options.cfg f=retroarch-core-options.cfg
	local manifest=$RA_DATA/.gameconsole/seeded.sha1 owner

	[ -f "$src" ] && [ -d "$RA_DATA" ] || return 0
	# RetroArch's folder exists once it ran or seed_retroarch made it
	[ -d "$RA_EXT" ] || return 0
	touch "$manifest"
	if [ -e "$dest" ]; then
		[ "$(sha1 "$dest")" = "$(sha1 "$src")" ] && return 0
		if ! grep -q "^$(sha1 "$dest") $f\$" "$manifest"; then
			log "keeping user provided $f"
			return 0
		fi
	fi
	owner=$(stat -c %u "$RA_DATA")
	if ! { cp "$src" "$dest.tmp" && mv -f "$dest.tmp" "$dest"; }; then
		log "writing $dest failed"
		rm -f "$dest.tmp"
		return 0
	fi
	chown "$owner:$AID_EXT_DATA_RW" "$dest"
	chmod 0660 "$dest"
	restorecon "$dest" 2>/dev/null
	grep -v " $f\$" "$manifest" >"$manifest.new"
	echo "$(sha1 "$dest") $f" >>"$manifest.new"
	mv -f "$manifest.new" "$manifest"
	chown "$(stat -c %u:%g "$RA_DATA")" "$manifest"
	log "seeded $f"
}

seed_cores() {
	local cores=$RA_DATA/cores own=$RA_DATA/.gameconsole
	local manifest archive sum stamp tmp so f dest

	[ -d "$RA_DATA" ] || return 0

	# state lives inside RetroArch's data dir: clearing its data or
	# reinstalling it re-seeds the cores
	mkdir -p "$cores" "$own"
	manifest=$own/seeded.sha1
	touch "$manifest"

	for archive in "$BASE"/libretro/cores-*.tar.gz; do
		[ -f "$archive" ] || continue
		sum=$(sha1 "$archive")
		stamp=$own/${archive##*/}.sha1
		[ "$(cat "$stamp" 2>/dev/null)" = "$sum" ] && continue

		tmp=$own/tmp
		rm -rf "$tmp"
		mkdir -p "$tmp"
		if ! gzip -dc "$archive" | tar -xf - -C "$tmp"; then
			log "unpacking $archive failed"
			rm -rf "$tmp"
			continue
		fi

		for so in "$tmp"/cores/*.so; do
			[ -f "$so" ] || continue
			f=${so##*/}
			dest=$cores/$f
			# only overwrite what we put there ourselves
			if [ -e "$dest" ] && ! grep -q "^$(sha1 "$dest") $f\$" "$manifest"; then
				log "keeping user provided $f"
				continue
			fi
			if ! { cp "$so" "$dest.tmp" && mv -f "$dest.tmp" "$dest"; }; then
				log "copying $f failed"
				continue
			fi
			grep -v " $f\$" "$manifest" >"$manifest.new"
			echo "$(sha1 "$dest") $f" >>"$manifest.new"
			mv -f "$manifest.new" "$manifest"
			log "seeded $f"
		done

		rm -rf "$tmp"
		echo "$sum" >"$stamp"
	done

	chown -R "$(stat -c %u:%g "$RA_DATA")" "$cores" "$own"
	chmod 0700 "$cores" "$own"
	chmod 0600 "$cores"/*.so 2>/dev/null
	restorecon -R "$cores" "$own" 2>/dev/null
}

# EASYROMS is partition 7 of the boot card (mmcblk0p7, 179:7), a public volume.
# A card set up with .noroms on BOOT has no partition 7 at all: the first start
# gives userdata (p6) the whole card (resizing/.../linuxrc).
EASYROMS_ID="public:179,7"
EASYROMS_PART=/sys/block/mmcblk0/mmcblk0p7
# the internal storage as root sees it; apps see /storage/emulated/0
MEDIA=/data/media/0
ROM_FOLDERS=$BASE/rom-folders.txt

# easyroms_path ; prints where EASYROMS is mounted, waits up to a minute for it
easyroms_path() {
	local i=0 id state uuid
	while [ $i -lt 30 ]; do
		sm list-volumes public </dev/null 2>/dev/null | while read -r id state uuid; do
			[ "$id" = "$EASYROMS_ID" ] && [ "$state" = mounted ] && [ -n "$uuid" ] &&
				[ -d "/mnt/media_rw/$uuid" ] && echo "/mnt/media_rw/$uuid"
		done | grep . && return 0
		i=$((i + 1))
		sleep 2
	done
	return 1
}

# find_easyroms ; sets EASYROMS to where it is mounted (empty if it isn't), and
# NOROMS when the card has no EASYROMS partition at all
find_easyroms() {
	NOROMS=
	EASYROMS=
	if [ -e "$EASYROMS_PART" ]; then
		EASYROMS=$(easyroms_path) || EASYROMS=
		# On a first start vold checks the new card for minutes before it
		# mounts EASYROMS. The steps that need it then run once the rest is
		# done, when it shows up, instead of on the next start.
		if [ -z "$EASYROMS" ] && [ -z "$GAMECONSOLE_EMU_TEST" ]; then
			log "EASYROMS not mounted yet, its steps wait for it"
			trap easyroms_late EXIT
		fi
	else
		NOROMS=1
		log "no EASYROMS partition on this card (.noroms)"
	fi
}

# easyroms_late: the EASYROMS steps of a start that ended before vold mounted
# it, run from the exit trap: the ROM folders with the free games, their
# .nomedia files, the BIOS folder, Daijishou's systems. Waits up to about 20
# minutes (easyroms_path waits a minute per call).
EASYROMS_LATE_TRIES=20
easyroms_late() {
	local i=0
	trap - EXIT
	while [ $i -lt $EASYROMS_LATE_TRIES ]; do
		EASYROMS=$(easyroms_path) && break
		EASYROMS=
		i=$((i + 1))
	done
	if [ -z "$EASYROMS" ]; then
		log "EASYROMS still not mounted, its steps next start"
		return 0
	fi
	log "EASYROMS mounted at $EASYROMS, doing its steps now"
	make_rom_folders
	command -v rom_nomedia >/dev/null && rom_nomedia
	bios_folder
	seed_daijishou
	dj_new_games
	daijishou_ready
}

make_rom_folders() {
	local root=$EASYROMS uuid stamp name
	[ -f "$ROM_FOLDERS" ] || return 0
	[ -n "$NOROMS" ] && return 0
	[ -n "$root" ] || { log "EASYROMS not mounted, no ROM folders"; return 0; }
	uuid=${root##*/}
	stamp=$STATE/rom-folders.$uuid
	[ -e "$stamp" ] && return 0

	# a card that already has a ROM folder layout (ArkOS, dArkOS, the user's own)
	# is left exactly as it is
	while read -r name _; do
		case $name in ''|\#*) continue ;; esac
		if [ -d "$root/$name" ]; then
			log "EASYROMS already has $name/, leaving its folders alone"
			touch "$stamp"
			return 0
		fi
	done <"$ROM_FOLDERS"

	while read -r name _; do
		case $name in ''|\#*) continue ;; esac
		mkdir -p "$root/$name" || { log "couldn't make $name/ on EASYROMS"; return 0; }
	done <"$ROM_FOLDERS"
	# the list itself, without the comment lines, is the README
	{
		echo "ROM folders made by andr36oid. Put each system's games into its folder."
		echo "Daijishou (the home screen) and RetroArch can then scan them."
		echo
		grep -v '^#' "$ROM_FOLDERS" | grep .
	} >"$root/README-andr36oid.txt"
	log "made the ROM folders on EASYROMS ($uuid)"
	touch "$stamp"
}

# what we last wrote into RetroArch's system_directory
SYSTEM_DIR_MARK=$STATE/retroarch-system-dir

# cfg_get <file> <key> ; a setting's value, without quotes; the last one wins
cfg_get() {
	sed -n "s/^[ 	]*$2[ 	]*=[ 	]*\"\{0,1\}\([^\"]*\)\"\{0,1\}[ 	]*\$/\1/p" "$1" | tail -n 1
}

# cfg_set <file> <key> <value> ; written in place, so the file keeps its owner,
# mode and the ACL vold gave it
cfg_set() {
	local rc
	{ grep -v "^[ 	]*$2[ 	]*=" "$1"; echo "$2 = \"$3\""; } >"$1.new" &&
		cat "$1.new" >"$1"
	rc=$?
	rm -f "$1.new"
	return $rc
}

# ours <system_directory> ; true if it's unset, or what we wrote last time.
# RetroArch's own default (as RetroArch writes it back) only counts until we
# have set the folder once: after that, the default is a folder the user picked
# on purpose. Anything else is always the user's.
ours() {
	[ -z "$1" ] && return 0
	if [ -f "$SYSTEM_DIR_MARK" ]; then
		[ "$1" = "$(cat "$SYSTEM_DIR_MARK")" ]
		return
	fi
	case $1 in
	default|/storage/emulated/0/RetroArch/system|/sdcard/RetroArch/system) return 0 ;;
	"$RA_EXT_PUBLIC/system"|"$RA_DATA/system") return 0 ;;
	esac
	return 1
}

# bios_name <dir> ; the name of its bios folder in any case (BIOS, Bios), else bios
bios_name() {
	local d
	for d in "$1"/*/; do
		d=${d%/}
		d=${d##*/}
		if [ "$(echo "$d" | tr '[:upper:]' '[:lower:]')" = bios ]; then
			echo "$d"
			return 0
		fi
	done
	echo bios
}

# bios_folder ; points RetroArch's system_directory at the bios folder on
# EASYROMS, where the cores then find the BIOS files; on a card without
# EASYROMS at the bios folder on the internal storage. Updated when that
# changes (a new card has another UUID, so another path), unless the user set a
# folder.
bios_folder() {
	local cfg=$RA_EXT/retroarch.cfg cur want name dir
	[ -f "$cfg" ] || return 0
	if [ -n "$NOROMS" ]; then
		name=$(bios_name "$MEDIA")
		dir=$MEDIA/$name
		want=/storage/emulated/0/$name
	elif [ -n "$EASYROMS" ]; then
		name=$(bios_name "$EASYROMS")
		dir=$EASYROMS/$name
		# RetroArch sees EASYROMS at /storage/<uuid>, root at /mnt/media_rw/<uuid>
		want=/storage/${EASYROMS##*/}/$name
	else
		# no bogus path: leave it and try again next boot
		log "EASYROMS not mounted, RetroArch's BIOS folder left as it is"
		return 0
	fi
	cur=$(cfg_get "$cfg" system_directory)
	if [ "$cur" != "$want" ]; then
		ours "$cur" || return 0
		if [ ! -d "$dir" ]; then
			mkdir "$dir" || { log "couldn't make $dir"; return 0; }
			# on the internal storage: owner and mode like its parent, as if
			# the user had made it (a no-op on exFAT)
			chown "$(stat -c %u:%g "${dir%/*}")" "$dir" 2>/dev/null
			chmod "$(stat -c %a "${dir%/*}")" "$dir" 2>/dev/null
			restorecon "$dir" 2>/dev/null
		fi
		cfg_set "$cfg" system_directory "$want" || { log "writing retroarch.cfg failed"; return 0; }
		log "RetroArch reads BIOS files from $want now (was: ${cur:-unset})"
	fi
	echo "$want" >"$SYSTEM_DIR_MARK"
}

# Daijishou, the home screen. On the first start its library gets one platform
# per ROM folder system we can play, with the folder on EASYROMS as its sync
# folder and our RetroArch core (or PPSSPP) as its player, so the user never has
# to add platforms or pick folders. See emulators/README.md, "Daijishou platforms".
DJ_PKG=com.magneticchen.daijishou
DJ_DATA=/data/user/0/$DJ_PKG
DJ_DB=$DJ_DATA/databases/Daijishou.db
DJ_PREFS=$DJ_DATA/shared_prefs/${DJ_PKG}_preferences.xml
DJ_SEED=$BASE/daijishou/daijishou-seed.txt
DJ_SCHEMA=$BASE/daijishou/schema.sql
# the systems' pictures (COLORFUL, Daijishou's default pack), see
# scripts/fetch-daijishou-art.py
DJ_ART=$BASE/daijishou/art
# Android's sqlite3 quits in this service ("register_localized_collators()
# failed": it can't load ICU), so the image has its own without ICU;
# sqlite3 is for tests on a PC
SQLITE=sqlite3
command -v gameconsole-sqlite3 >/dev/null 2>&1 && SQLITE=gameconsole-sqlite3
# done: set once we added the platforms, or decided never to (Daijishou had a
# library already, another Daijishou version)
DJ_DONE=$STATE/daijishou.done
# the card UUID if we made the library (dj_new_games only runs then)
DJ_SEEDED=$STATE/daijishou.seeded
URI_GRANTS=/data/system/urigrants.xml
SAF_TREE=content://com.android.externalstorage.documents/tree
EASYROMS_DEV=/dev/block/mmcblk0p7

# dj_library_count ; systems + games in Daijishou's library, empty if it can't
# be read. Counted in a copy: the live one is open in Daijishou, in WAL mode, and
# a read-only open of it can fail (it may have to recover the WAL first).
dj_library_count() {
	local tmp=$STATE/daijishou-check n err
	rm -rf "$tmp"
	mkdir -p "$tmp" || return 0
	cp "$DJ_DB" "$tmp/Daijishou.db" 2>/dev/null || { rm -rf "$tmp"; return 0; }
	[ -e "$DJ_DB-wal" ] && cp "$DJ_DB-wal" "$tmp/Daijishou.db-wal" 2>/dev/null
	n=$("$SQLITE" "$tmp/Daijishou.db" 'SELECT (SELECT count(*) FROM PlatformEntity) + (SELECT count(*) FROM PlayableItemEntity);' 2>"$tmp/err")
	err=$(head -c 200 "$tmp/err" 2>/dev/null)
	rm -rf "$tmp"
	case $n in
	'' | *[!0-9]*) log "can't read Daijishou's library: ${err:-${n:-no answer}}" >&2 ;;
	*) echo "$n" ;;
	esac
}

# dj_user_library ; 0: Daijishou has a library of its own (a system or a game
# in it), 1: it has none or an empty one, 2: can't tell. The empty one Daijishou
# makes when it starts before we could fill it (the home screen comes up while
# EASYROMS is still being checked and mounted on a first start) isn't the user's.
# "Can't tell" leaves the library alone and tries again next start.
dj_user_library() {
	local n
	[ -e "$DJ_DB" ] || return 1
	command -v "$SQLITE" >/dev/null || return 2
	n=$(dj_library_count)
	case $n in
	0) return 1 ;;
	*[!0-9]* | '') return 2 ;;
	*) return 0 ;;
	esac
}

# dj_settled ; nothing left to do for Daijishou's library: we made it, the user
# has one, or we decided never to (another Daijishou version...). Images before
# dj_user_library set done for an empty library too; that one is filled now.
dj_settled() {
	[ -e "$DJ_SEEDED" ] && return 0
	dj_user_library && return 0
	[ -e "$DJ_DONE" ] && [ ! -e "$DJ_DB" ]
}

# dj_folders ; every ROM folder that gets a Daijishou platform
dj_folders() {
	sed -n 's/^platform	[0-9]*	\([^	]*\)	.*/\1/p' "$DJ_SEED" | tr ',' '\n'
}

# dj_tree_uri <uuid> <folder> ; the folder as Android's file picker hands it out
dj_tree_uri() {
	echo "$SAF_TREE/$1%3A$2"
}

# daijishou_grants: Daijishou reads its games through Android's storage access
# framework, so it may only open a folder the user picked for it in the file
# picker, which remembers that as a persisted URI grant in
# /data/system/urigrants.xml. We write the same grants the picker would, once,
# for every folder we give a platform. system_server reads the file only when
# it starts, so this runs from post-fs-data, before it does. EASYROMS isn't
# mounted yet at that point: its UUID comes from blkid, the tool vold names the
# volume with (/storage/<UUID>).
daijishou_grants() {
	local uuid stamp folder uri now lines f=$URI_GRANTS

	[ -f "$DJ_SEED" ] || return 0
	[ -e "$DJ_DONE" ] && dj_settled && return 0
	# .noroms: games live on the internal storage, no platforms
	[ -e "$EASYROMS_PART" ] || return 0
	# Daijishou has its own library already: leave it to the user
	dj_user_library && return 0

	# init waits for this step (exec_start): KILL 2 s after TERM for a blkid
	# stuck on a failing card
	uuid=$(timeout -k 2 10 blkid -c /dev/null -s UUID "$EASYROMS_DEV" 2>/dev/null |
		sed -n 's/.*UUID="\([^"]*\)".*/\1/p')
	case $uuid in
	'' | *[!0-9A-Fa-f-]*)
		log "no usable UUID for EASYROMS (${uuid:-none}), no folder access for Daijishou"
		return 0
		;;
	esac
	stamp=$STATE/daijishou-grants.$uuid
	[ -e "$stamp" ] && return 0
	# a write system_server didn't finish: it recovers from it on start, not us
	if [ -e "$f.bak" ] || [ -e "$f.new" ]; then
		log "$f is being written, Daijishou's folder access next start"
		return 0
	fi

	now=$(date +%s)000
	lines=
	for folder in $(dj_folders); do
		uri=$(dj_tree_uri "$uuid" "$folder")
		# already there (the user picked the folder, or an earlier try)
		grep -q "targetPkg=\"$DJ_PKG\" uri=\"$uri\"" "$f" 2>/dev/null && continue
		lines="$lines<uri-grant sourceUserId=\"0\" targetUserId=\"0\" sourcePkg=\"com.android.externalstorage\" targetPkg=\"$DJ_PKG\" uri=\"$uri\" prefix=\"true\" modeFlags=\"3\" createdTime=\"$now\" />"
	done

	if [ -n "$lines" ]; then
		if [ -s "$f" ] && grep -q '</uri-grants>' "$f"; then
			sed "s|</uri-grants>|$lines</uri-grants>|" "$f" >"$f.tmp"
		elif [ -s "$f" ] && grep -q '<uri-grants */>' "$f"; then
			sed "s|<uri-grants */>|<uri-grants>$lines</uri-grants>|" "$f" >"$f.tmp"
		elif [ -s "$f" ]; then
			log "$f looks odd, Daijishou gets no folder access"
			return 0
		else
			echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><uri-grants>$lines</uri-grants>" >"$f.tmp"
		fi
		if ! grep -q "$uuid%3A" "$f.tmp"; then
			rm -f "$f.tmp"
			log "writing $f failed"
			return 0
		fi
		chown 1000:1000 "$f.tmp"
		chmod 0600 "$f.tmp"
		mv -f "$f.tmp" "$f"
		restorecon "$f" 2>/dev/null
		log "Daijishou may read the ROM folders on EASYROMS ($uuid)"
	fi
	touch "$stamp"
}

# dj_has <need> ; true if the core (core:<name>) or app (app:<package>) is there
dj_has() {
	case $1 in
	core:*) [ -f "$RA_DATA/cores/${1#core:}_libretro_android.so" ] ;;
	app:*) [ -n "$(installed_vc "${1#app:}")" ] ;;
	*) return 1 ;;
	esac
}

# dj_platform_sql <id> <player ids> <folder URIs> <values> ; one platform row
dj_platform_sql() {
	printf '%s\n' "INSERT INTO PlatformEntity (id, default_player_id, player_id_list, items_sync_tree_uri_list, unique_id, name, shortname, description, is_visible, scrape_preview_media, wallpaper_path, thumbnail_path, color_theme, color_on_theme, accept_filename_regex, scraper_source_list, box_art_aspect_ratio_id, use_custom_box_art_aspect_ratio, custom_box_art_aspect_ratio, screen_aspect_ratio_id, use_custom_screen_aspect_ratio, custom_screen_aspect_ratio, retro_achievements_console_id_list, extra) VALUES ($1, ${2%%,*}, '[$2]', '[$3]', $4);"
}

# dj_art_sql <id> <picture> ; the platform's wallpaper and thumbnail, where
# Daijishou keeps the ones it downloads (files/platform_*/<id>.png; it reads
# them by content, so a JPEG is fine). dj_art copies them there.
dj_art_sql() {
	printf '%s\n' "UPDATE PlatformEntity SET wallpaper_path = '$DJ_DATA/files/platform_wallpapers/$1.png', thumbnail_path = '$DJ_DATA/files/platform_thumbnails/$1.png' WHERE id = $1;"
	[ -n "$DJ_ART_LIST" ] && echo "$1 $2" >>"$DJ_ART_LIST"
}

# dj_sql <easyroms dir> <uuid> ; the rows for Daijishou's library, as SQL. A
# platform gets the folders that are on the card and the players that can run
# here; the first player is its default. Without either, no platform (and no
# players for it). With DJ_ART_LIST set, each platform's picture is listed
# there as "<id> <file>" for dj_art.
dj_sql() {
	local root=$1 uuid=$2 kind n arg vals f
	local pid=0 plat=0 pnum='' pvals='' players='' uris='' art=''

	echo 'BEGIN;'
	while IFS='	' read -r kind n arg vals; do
		case $kind in
		platform)
			if [ -n "$pnum" ] && [ -n "$players" ]; then
				plat=$((plat + 1))
				dj_platform_sql "$plat" "$players" "$uris" "$pvals"
				[ -n "$art" ] && dj_art_sql "$plat" "$art"
			fi
			art=''
			uris=''
			for f in $(echo "$arg" | tr ',' ' '); do
				[ -d "$root/$f" ] || continue
				uris="$uris${uris:+,}\"$(dj_tree_uri "$uuid" "$f")\""
			done
			# no folder on this card: skip the platform and its players
			pnum=''
			[ -n "$uris" ] && pnum=$n
			pvals=$vals
			players=''
			;;
		art)
			[ -n "$pnum" ] && [ "$n" = "$pnum" ] && [ -f "$DJ_ART/$arg" ] && art=$arg
			;;
		player)
			if [ -z "$pnum" ] || [ "$n" != "$pnum" ] || ! dj_has "$arg"; then
				continue
			fi
			pid=$((pid + 1))
			players="$players${players:+,}$pid"
			printf '%s\n' "INSERT INTO PlayerEntity (id, unique_id, player, name, description, am_start_arguments, kill_package_processes, kill_package_processes_warning, accepted_filename_regex, extra) VALUES ($pid, $vals);"
			;;
		esac
	done <"$DJ_SEED"
	if [ -n "$pnum" ] && [ -n "$players" ]; then
		plat=$((plat + 1))
		dj_platform_sql "$plat" "$players" "$uris" "$pvals"
		[ -n "$art" ] && dj_art_sql "$plat" "$art"
	fi
	echo 'COMMIT;'
}

# dj_art <list> <owner> ; copies the pictures dj_sql listed into Daijishou's
# folders, owned by Daijishou. A picture that can't be copied only leaves that
# system plain (Daijishou shows its default look for a missing file).
dj_art() {
	local list=$1 owner=$2 id file d
	[ -s "$list" ] || return 0
	for d in "$DJ_DATA/files" "$DJ_DATA/files/platform_wallpapers" \
		"$DJ_DATA/files/platform_thumbnails"; do
		[ -d "$d" ] || { mkdir -p "$d" && chown "$owner" "$d" && chmod 0771 "$d"; }
	done
	while read -r id file; do
		for d in platform_wallpapers platform_thumbnails; do
			cp -f "$DJ_ART/$file" "$DJ_DATA/files/$d/$id.png" &&
				chown "$owner" "$DJ_DATA/files/$d/$id.png" &&
				chmod 0660 "$DJ_DATA/files/$d/$id.png" ||
				log "copying the picture $file for platform $id failed"
		done
	done <"$list"
	restorecon -R "$DJ_DATA/files" 2>/dev/null
}

DJ_HINT='Your game systems are set up with their folders on EASYROMS. Press OK to look for games now. After adding games later, use Sync entirely in the library settings.'

# dj_prefs ; Daijishou's settings file for its first start: it asks once to
# sync the whole library, which finds the games in the folders. Everything else
# stays at Daijishou's defaults.
dj_prefs() {
	cat <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="sync_entirely_on_start" value="true" />
    <string name="sync_entirely_on_start_hint">$DJ_HINT</string>
    <string name="sync_entirely_on_start_cancel_hint">Canceled. Sync entirely in the library settings looks for games later.</string>
</map>
EOF
}

# daijishou_ready ; Daijishou's library is made, or there won't be one (no EASYROMS:
# then it's made when the card mounts, see easyroms_late): the quick start guide
# makes Daijishou the home screen only after this, so its first start already asks
# to look for games (QuickStart FirstStart.java, FirstStart/HomeActivity.java)
daijishou_ready() {
	settings put global andr36oid_daijishou_ready 1 </dev/null >/dev/null 2>&1 ||
		log "couldn't tell the guide that Daijishou is ready"
}

# seed_daijishou: fills Daijishou's library before its first start, or right
# after it if Daijishou came up before EASYROMS did and its library is still
# empty. Only once, never over a library with a system or game in it, and only
# for the Daijishou version the schema was taken from.
seed_daijishou() {
	local uuid want vc owner tmp count rc late='' top=''

	[ -f "$DJ_SEED" ] && [ -f "$DJ_SCHEMA" ] || return 0
	[ -e "$DJ_DONE" ] && dj_settled && return 0
	[ -n "$NOROMS" ] && return 0
	[ -n "$EASYROMS" ] || { log "EASYROMS not mounted, Daijishou's platforms next start"; return 0; }
	[ -d "$DJ_DATA" ] || return 0
	dj_user_library
	case $? in
	0)
		log "Daijishou has a library already, no platforms added"
		touch "$DJ_DONE"
		return 0
		;;
	2)
		log "Daijishou's library can't be read, platforms next start"
		return 0
		;;
	esac
	# Daijishou started first and made an empty library: replaced below, with
	# Daijishou stopped around it
	[ -e "$DJ_DB" ] && late=1
	uuid=${EASYROMS##*/}
	if [ ! -e "$STATE/daijishou-grants.$uuid" ]; then
		# post-fs-data writes them on the next start (the card may be new)
		log "no folder access for Daijishou on $uuid yet, platforms next start"
		return 0
	fi
	want=$(sed -n 's/^daijishou	//p' "$DJ_SEED")
	vc=$(installed_vc "$DJ_PKG")
	if [ "$vc" != "$want" ]; then
		log "Daijishou ${vc:-missing}, the platforms are made for $want: none added"
		touch "$DJ_DONE"
		return 0
	fi
	if [ -z "$late" ] && pidof "$DJ_PKG" >/dev/null 2>&1; then
		log "Daijishou is running, platforms next start"
		return 0
	fi
	command -v "$SQLITE" >/dev/null || { log "no sqlite3, no Daijishou platforms"; return 0; }

	tmp=$STATE/daijishou.tmp
	rm -rf "$tmp"
	mkdir -p "$tmp"
	DJ_ART_LIST=$tmp/art.list
	dj_sql "$EASYROMS" "$uuid" >"$tmp/rows.sql"
	DJ_ART_LIST=
	if ! "$SQLITE" -bail "$tmp/Daijishou.db" <"$DJ_SCHEMA" ||
		! "$SQLITE" -bail "$tmp/Daijishou.db" <"$tmp/rows.sql"; then
		log "making Daijishou's library failed"
		rm -rf "$tmp"
		return 0
	fi
	count=$("$SQLITE" "$tmp/Daijishou.db" "SELECT count(*) FROM PlatformEntity;")
	if [ "${count:-0}" -eq 0 ]; then
		log "no Daijishou platform to add (no ROM folders or players)"
		rm -rf "$tmp"
		touch "$DJ_DONE"
		return 0
	fi

	if [ -n "$late" ]; then
		# it keeps the library in memory while it runs; as the home screen it
		# may come straight back, so it's stopped again once the files are in
		dumpsys activity activities 2>/dev/null | grep -q "mResumedActivity.*$DJ_PKG/" && top=1
		am force-stop "$DJ_PKG"
		dj_user_library
		rc=$?
		case $rc in
		0 | 2)
			if [ $rc -eq 0 ]; then
				log "Daijishou has a library already, no platforms added"
				touch "$DJ_DONE"
			else
				log "Daijishou's library can't be read, platforms next start"
			fi
			rm -rf "$tmp"
			[ -n "$top" ] && am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
			return 0
			;;
		esac
		rm -f "$DJ_DB-wal" "$DJ_DB-shm" "$DJ_DB-journal"
	# Daijishou may have started meanwhile
	elif [ -e "$DJ_DB" ] || pidof "$DJ_PKG" >/dev/null 2>&1; then
		log "Daijishou started meanwhile, platforms next start"
		rm -rf "$tmp"
		return 0
	fi
	owner=$(stat -c %u:%g "$DJ_DATA")
	mkdir -p "$DJ_DATA/databases"
	chown "$owner" "$DJ_DATA/databases"
	chmod 0771 "$DJ_DATA/databases"
	chown "$owner" "$tmp/Daijishou.db"
	chmod 0660 "$tmp/Daijishou.db"
	mv -f "$tmp/Daijishou.db" "$DJ_DB"
	dj_art "$tmp/art.list" "$owner"
	if [ ! -e "$DJ_PREFS" ]; then
		mkdir -p "${DJ_PREFS%/*}"
		chown "$owner" "${DJ_PREFS%/*}"
		chmod 0771 "${DJ_PREFS%/*}"
		dj_prefs >"$DJ_PREFS"
		chown "$owner" "$DJ_PREFS"
		chmod 0660 "$DJ_PREFS"
	elif dj_pref "$DJ_PREFS" boolean sync_entirely_on_start '<boolean name="sync_entirely_on_start" value="true" />' &&
		mv -f "$DJ_PREFS.new" "$DJ_PREFS"; then
		# Daijishou's own settings file from its first start
		dj_pref "$DJ_PREFS" string sync_entirely_on_start_hint "<string name=\"sync_entirely_on_start_hint\">$DJ_HINT</string>" &&
			mv -f "$DJ_PREFS.new" "$DJ_PREFS"
		chown "$owner" "$DJ_PREFS"
		chmod 0660 "$DJ_PREFS"
	else
		rm -f "$DJ_PREFS.new"
		log "Daijishou's settings look odd, it won't ask to look for games"
	fi
	restorecon -R "$DJ_DATA/databases" "${DJ_PREFS%/*}" 2>/dev/null
	rm -rf "$tmp"
	if [ -n "$late" ]; then
		am force-stop "$DJ_PKG"
		[ -n "$top" ] && am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
	fi
	touch "$DJ_DONE"
	# the first start's sync sees what's there now; later changes: dj_new_games
	echo "$uuid" >"$DJ_SEEDED"
	dj_games >"$STATE/daijishou-games.$uuid"
	log "added $count platforms to Daijishou's library"
}

# dj_exts ; one line "<folder> <ext|ext|...>" per platform folder: the file
# endings its players accept (their accepted_filename_regex in $DJ_SEED)
dj_exts() {
	awk -F '\t' '
	$1 == "platform" { n = $2; dirs[n] = $3 }
	$1 == "player" && match($0, /\(\?:[^)]*\)\$/) {
		e = substr($0, RSTART + 3, RLENGTH - 5)
		ext[$2] = (ext[$2] == "" ? e : ext[$2] "|" e)
	}
	END {
		for (n in dirs) {
			k = split(dirs[n], d, ",")
			for (i = 1; i <= k; i++) if (ext[n] != "") print d[i], ext[n]
		}
	}' "$DJ_SEED"
}

# dj_games ; the game files in the platform folders on EASYROMS, one path per
# line: only files with an ending the platform's players accept, so saves and
# states the cores write next to the games (.srm, .state, .nv, .hi, .lsd,
# .dsg...), scraped pictures and game lists don't count. DOSBox Pure's saves
# end in .pure.zip, which would pass as a game.
dj_games() {
	local f exts
	dj_exts | while read -r f exts; do
		[ -d "$EASYROMS/$f" ] || continue
		find "$EASYROMS/$f" -type f 2>/dev/null | grep -i -E "\.($exts)\$"
	done | sed "s|^$EASYROMS/||" |
		grep -v -i -E '(^|/)\.|\.pure\.zip$' |
		sort
}

# dj_pref <file> <tag> <name> <element> ; sets one setting in a shared prefs file
dj_pref() {
	local cur
	# only an element on one line can be dropped by line (Android writes one per line)
	if grep "<$2 name=\"$3\"" "$1" | grep -v -q -e "</$2>" -e '/>'; then
		return 1
	fi
	cur=$(grep -v "<$2 name=\"$3\"" "$1") || true
	case $cur in
	*'</map>'*) printf '%s\n' "$cur" | sed "s|</map>|$4</map>|" >"$1.new" ;;
	*) return 1 ;;
	esac
}

# dj_new_games: Daijishou only looks for games when asked to. On a console we set
# up, when the games on EASYROMS changed since the last start (copied on a
# computer, say), Daijishou asks again on its next start, like on the first one.
# It keeps its settings in memory while it runs, so it's restarted around the
# change; the home screen comes back by itself. persist.andr36oid.daijishou_autosync=0
# turns this off.
dj_new_games() {
	local uuid seen now top owner p=$DJ_PREFS

	[ -f "$DJ_SEEDED" ] && [ -n "$EASYROMS" ] || return 0
	[ "$(getprop persist.andr36oid.daijishou_autosync)" = 0 ] && return 0
	uuid=${EASYROMS##*/}
	seen=$STATE/daijishou-games.$uuid
	now=$(dj_games | cksum)
	if [ ! -f "$seen" ]; then
		# another card (EASYROMS formatted again): nothing to compare with
		dj_games >"$seen"
		return 0
	fi
	[ "$(cksum <"$seen")" = "$now" ] && return 0
	# Daijishou's library isn't ours any more (data cleared): leave it
	if [ ! -f "$p" ] || [ ! -f "$DJ_DB" ]; then
		dj_games >"$seen"
		return 0
	fi

	top=
	dumpsys activity activities 2>/dev/null | grep -q "mResumedActivity.*$DJ_PKG/" && top=1
	am force-stop "$DJ_PKG"
	owner=$(stat -c %u:%g "$p")
	if dj_pref "$p" boolean sync_entirely_on_start '<boolean name="sync_entirely_on_start" value="true" />' &&
		mv -f "$p.new" "$p"; then
		# the dialog's text; Daijishou's own if ours can't go in
		dj_pref "$p" string sync_entirely_on_start_hint '<string name="sync_entirely_on_start_hint">The games on EASYROMS changed. Press OK to update the library.</string>' &&
			mv -f "$p.new" "$p"
		chown "$owner" "$p"
		chmod 0660 "$p"
		restorecon "$p" 2>/dev/null
		log "games on EASYROMS changed, Daijishou looks for them on its next start"
	else
		rm -f "$p.new"
		log "Daijishou's settings look odd, not asking it to look for new games"
	fi
	# a Daijishou that started meanwhile has the old settings in memory
	am force-stop "$DJ_PKG"
	[ -n "$top" ] && am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
	dj_games >"$seen"
}

# post-fs-data: only the folder access for Daijishou, before system_server starts
if [ "$1" = post-fs-data ]; then
	daijishou_grants
	exit 0
fi

# the host tests source this file for its functions
[ -n "$GAMECONSOLE_EMU_TEST" ] && return 0

mkdir -p "$STATE"
log_trim

# boot_completed is set, but give the package manager a moment if needed
i=0
until pm path android </dev/null >/dev/null 2>&1; do
	i=$((i + 1))
	[ $i -gt 60 ] && { log "package manager not up, giving up"; exit 1; }
	sleep 2
done

first_start
install_apks
seed_retroarch
seed_core_options
seed_cores
find_easyroms
make_rom_folders
# after the ROM folders: they are only made on a card without any of them
bios_folder
# after the cores and ROM folders: a platform needs both
seed_daijishou
dj_new_games
daijishou_ready
