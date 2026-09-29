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
#    retroarch.cfg that exists is never touched.
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
RA_PKG=com.retroarch
RA_DATA=/data/user/0/$RA_PKG
FS_PKG=org.andr36oid.firststart
FS_APK=$BASE/firststart/FirstStart.apk

log() {
	/system/bin/log -t gameconsole-emu "$*"
	echo "$*"
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
	else
		NOROMS=1
		log "no EASYROMS partition on this card (.noroms)"
	fi
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
# done: set once we added the platforms, or decided never to (Daijishou had a
# library already, another Daijishou version)
DJ_DONE=$STATE/daijishou.done
# the card UUID if we made the library (dj_new_games only runs then)
DJ_SEEDED=$STATE/daijishou.seeded
URI_GRANTS=/data/system/urigrants.xml
SAF_TREE=content://com.android.externalstorage.documents/tree
EASYROMS_DEV=/dev/block/mmcblk0p7

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
	[ -e "$DJ_DONE" ] && return 0
	# .noroms: games live on the internal storage, no platforms
	[ -e "$EASYROMS_PART" ] || return 0
	# Daijishou has its own library already: leave it to the user
	[ -e "$DJ_DB" ] && return 0

	uuid=$(timeout 10 blkid -c /dev/null -s UUID "$EASYROMS_DEV" 2>/dev/null |
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

# dj_sql <easyroms dir> <uuid> ; the rows for Daijishou's library, as SQL. A
# platform gets the folders that are on the card and the players that can run
# here; the first player is its default. Without either, no platform (and no
# players for it).
dj_sql() {
	local root=$1 uuid=$2 kind n arg vals f
	local pid=0 plat=0 pnum='' pvals='' players='' uris=''

	echo 'BEGIN;'
	while IFS='	' read -r kind n arg vals; do
		case $kind in
		platform)
			if [ -n "$pnum" ] && [ -n "$players" ]; then
				plat=$((plat + 1))
				dj_platform_sql "$plat" "$players" "$uris" "$pvals"
			fi
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
	fi
	echo 'COMMIT;'
}

# dj_prefs ; Daijishou's settings file for its first start: it asks once to
# sync the whole library, which finds the games in the folders. Everything else
# stays at Daijishou's defaults.
dj_prefs() {
	cat <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="sync_entirely_on_start" value="true" />
    <string name="sync_entirely_on_start_hint">Your game systems are set up with their folders on EASYROMS. Press OK to look for games now. After adding games later, use Sync entirely in the library settings.</string>
    <string name="sync_entirely_on_start_cancel_hint">Canceled. Sync entirely in the library settings looks for games later.</string>
</map>
EOF
}

# seed_daijishou: fills Daijishou's library before its first start. Only
# once, only while Daijishou has no library yet (a database is never touched),
# and only for the Daijishou version the schema was taken from.
seed_daijishou() {
	local uuid want vc owner tmp count

	[ -f "$DJ_SEED" ] && [ -f "$DJ_SCHEMA" ] || return 0
	[ -e "$DJ_DONE" ] && return 0
	[ -n "$NOROMS" ] && return 0
	[ -n "$EASYROMS" ] || { log "EASYROMS not mounted, Daijishou's platforms next start"; return 0; }
	[ -d "$DJ_DATA" ] || return 0
	if [ -e "$DJ_DB" ]; then
		log "Daijishou has a library already, no platforms added"
		touch "$DJ_DONE"
		return 0
	fi
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
	if pidof "$DJ_PKG" >/dev/null 2>&1; then
		log "Daijishou is running, platforms next start"
		return 0
	fi
	command -v sqlite3 >/dev/null || { log "no sqlite3, no Daijishou platforms"; return 0; }

	tmp=$STATE/daijishou.tmp
	rm -rf "$tmp"
	mkdir -p "$tmp"
	dj_sql "$EASYROMS" "$uuid" >"$tmp/rows.sql"
	if ! sqlite3 -bail "$tmp/Daijishou.db" <"$DJ_SCHEMA" ||
		! sqlite3 -bail "$tmp/Daijishou.db" <"$tmp/rows.sql"; then
		log "making Daijishou's library failed"
		rm -rf "$tmp"
		return 0
	fi
	count=$(sqlite3 "$tmp/Daijishou.db" "SELECT count(*) FROM PlatformEntity;")
	if [ "${count:-0}" -eq 0 ]; then
		log "no Daijishou platform to add (no ROM folders or players)"
		rm -rf "$tmp"
		touch "$DJ_DONE"
		return 0
	fi

	# Daijishou may have started meanwhile
	if [ -e "$DJ_DB" ] || pidof "$DJ_PKG" >/dev/null 2>&1; then
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
	if [ ! -e "$DJ_PREFS" ]; then
		mkdir -p "${DJ_PREFS%/*}"
		chown "$owner" "${DJ_PREFS%/*}"
		chmod 0771 "${DJ_PREFS%/*}"
		dj_prefs >"$DJ_PREFS"
		chown "$owner" "$DJ_PREFS"
		chmod 0660 "$DJ_PREFS"
	fi
	restorecon -R "$DJ_DATA/databases" "${DJ_PREFS%/*}" 2>/dev/null
	rm -rf "$tmp"
	touch "$DJ_DONE"
	# the first start's sync sees what's there now; later changes: dj_new_games
	echo "$uuid" >"$DJ_SEEDED"
	dj_games >"$STATE/daijishou-games.$uuid"
	log "added $count platforms to Daijishou's library"
}

# dj_games ; the game files in the platform folders on EASYROMS, one path per
# line, without what the emulators write next to them (saves, states) and
# without scraped pictures, videos and game lists
dj_games() {
	local f
	for f in $(dj_folders); do
		[ -d "$EASYROMS/$f" ] && find "$EASYROMS/$f" -type f 2>/dev/null
	done | sed "s|^$EASYROMS/||" |
		grep -v -i -E '(^|/)\.|\.(srm|sav|rtc|ldci|dsv|mcd|mcr|state[0-9]*|auto|png|jpe?g|gif|mp4|txt|cfg|opt|bak|xml|nfo|log)$' |
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
seed_cores
find_easyroms
make_rom_folders
# after the ROM folders: they are only made on a card without any of them
bios_folder
# after the cores and ROM folders: a platform needs both
seed_daijishou
dj_new_games
