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
#    Then, on a fresh install only, how games look: a sharp scaling shader
#    and 3x for the Game Boy, and core options for the few games that need
#    their own on this chip.
# 4. Makes the usual ROM folders (ArkOS/dArkOS names) on a fresh EASYROMS
#    partition, with a README telling which system goes where. Only once per
#    card, and never on a card that already has any of them.
# 5. Points RetroArch's system folder (where the cores look for BIOS files) at
#    the bios folder on EASYROMS, like ArkOS, or on the internal storage for a
#    card made without EASYROMS (.noroms). A folder the user picked in
#    RetroArch is kept.

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

PP_PKG=org.ppsspp.ppsspp
PP_DATA=/data/user/0/$PP_PKG
# PPSSPP's own folder on the internal storage (getExternalFilesDir), the one
# its first-start screen calls "Use App Private Data"
PP_EXT=/data/media/0/Android/data/$PP_PKG/files
PP_EXT_PUBLIC=/storage/emulated/0/Android/data/$PP_PKG/files

# seed_ppsspp ; on a fresh PPSSPP install: its memory stick folder and
# settings for this console (OpenGL, 1x, auto frameskip, hotkeys like
# RetroArch's). PPSSPP targets Android 15, so on Android 11 it runs with scoped
# storage and asks on its first start where to keep its data; a game started
# from Daijishou would open that question instead of the game. Without a
# memstick_dir.txt, PPSSPP never ran (or its data was cleared): then it gets
# its own folder PP_EXT, the same one the "Skip" choice on that screen picks,
# and ppsspp.ini/controls.ini in PSP/SYSTEM there, each only if missing. A
# folder the user picked (memstick_dir.txt exists) is never touched.
# compat.ini there is PPSSPP's own per-game fix list for the user, read by
# disc ID after its built-in one (see game-compat in the README).
seed_ppsspp() {
	local owner uid dir f

	[ -d "$PP_DATA" ] || return 0
	[ -e "$PP_DATA/files/memstick_dir.txt" ] && return 0
	[ -f "$BASE/ppsspp/ppsspp.ini" ] || return 0
	# vold makes Android/data when it mounts the internal storage
	[ -d "${PP_EXT%/*/*}" ] || return 0
	owner=$(stat -c %u:%g "$PP_DATA")
	uid=${owner%:*}

	# its folder the way vold makes it (owner the app, group ext_data_rw,
	# setgid), then PSP/SYSTEM like PPSSPP makes them
	for dir in "${PP_EXT%/*}" "$PP_EXT"; do
		[ -d "$dir" ] && continue
		mkdir "$dir" || { log "making $dir failed"; return 0; }
		chown "$uid:$AID_EXT_DATA_RW" "$dir"
		chmod 2770 "$dir"
	done
	for dir in "$PP_EXT/PSP" "$PP_EXT/PSP/SYSTEM"; do
		[ -d "$dir" ] && continue
		mkdir "$dir" || { log "making $dir failed"; return 0; }
		chown "$uid:$AID_EXT_DATA_RW" "$dir"
		chmod 2770 "$dir"
	done
	for f in ppsspp.ini controls.ini compat.ini; do
		[ -f "$BASE/ppsspp/$f" ] || continue
		[ -e "$PP_EXT/PSP/SYSTEM/$f" ] && continue
		if ! { cp "$BASE/ppsspp/$f" "$PP_EXT/PSP/SYSTEM/$f.tmp" &&
			mv -f "$PP_EXT/PSP/SYSTEM/$f.tmp" "$PP_EXT/PSP/SYSTEM/$f"; }; then
			log "writing PPSSPP's $f failed"
			rm -f "$PP_EXT/PSP/SYSTEM/$f.tmp"
			return 0
		fi
		chown "$uid:$AID_EXT_DATA_RW" "$PP_EXT/PSP/SYSTEM/$f"
		chmod 0660 "$PP_EXT/PSP/SYSTEM/$f"
		log "seeded PPSSPP's $f"
	done
	restorecon -R "${PP_EXT%/*}" 2>/dev/null
	# vold's own fixup for app folders (default ACL, quota project), as for
	# RetroArch: IStorageManager.fixupAppDir, transaction 89 + 1
	service call mount 90 s16 "$PP_EXT_PUBLIC" </dev/null >/dev/null 2>&1

	# last, so a failure above leaves PPSSPP asking as usual. No newline:
	# PPSSPP uses the whole file as the path.
	[ -d "$PP_DATA/files" ] || {
		mkdir "$PP_DATA/files" && chown "$owner" "$PP_DATA/files" &&
			chmod 0771 "$PP_DATA/files"
	} || { log "making PPSSPP's files folder failed"; return 0; }
	if ! { printf '%s' "$PP_EXT_PUBLIC" >"$PP_DATA/files/memstick_dir.txt.tmp" &&
		mv -f "$PP_DATA/files/memstick_dir.txt.tmp" "$PP_DATA/files/memstick_dir.txt"; }; then
		log "writing PPSSPP's memstick_dir.txt failed"
		rm -f "$PP_DATA/files/memstick_dir.txt.tmp"
		return 0
	fi
	chown "$owner" "$PP_DATA/files/memstick_dir.txt"
	chmod 0600 "$PP_DATA/files/memstick_dir.txt"
	restorecon -R "$PP_DATA/files" 2>/dev/null
	log "PPSSPP keeps its data in $PP_EXT_PUBLIC"
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
	# this install gets our picture settings too, see seed_look
	echo new >"$own/look"
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

# game_opt_drop <key> ; removes key's line from the seed manifest. Keys hold
# ROM names (spaces, brackets), so no grep patterns.
game_opt_drop() {
	local manifest=$RA_DATA/.gameconsole/seeded.sha1 l

	while IFS= read -r l; do
		[ "${l#* }" = "$1" ] || printf '%s\n' "$l"
	done <"$manifest" >"$manifest.new"
	mv -f "$manifest.new" "$manifest"
}

# game_opt_write <core> <prefix> <ROM name> <defaults> <game lines> <owner>
# ; writes config/<core>/<ROM name>.opt: our defaults for the core (keys that
# start with prefix), then the game's own lines, which win
game_opt_write() {
	local core=$1 prefix=$2 name=$3 defaults=$4 sets=$5 owner=$6
	local manifest=$RA_DATA/.gameconsole/seeded.sha1
	local key="games/$core/$name.opt" dest="$RA_EXT/config/$core/$name.opt"
	local tmp=$RA_DATA/.gameconsole/game.opt l k dir

	{
		echo "# $name: settings for this game on this console, see Game tips.txt"
		if [ -f "$defaults" ]; then
			grep "^$prefix" "$defaults" | while IFS= read -r l; do
				k=${l%% *}
				grep -q "^$k " "$sets" || printf '%s\n' "$l"
			done
		fi
		cat "$sets"
	} >"$tmp"
	if [ -e "$dest" ]; then
		[ "$(sha1 "$dest")" = "$(sha1 "$tmp")" ] && return 0
		if ! grep -qxF "$(sha1 "$dest") $key" "$manifest"; then
			log "keeping user provided config/$core/$name.opt"
			return 0
		fi
	elif cut -d' ' -f2- "$manifest" | grep -qxF "$key"; then
		# we put it there before, so the user removed it
		return 0
	fi
	for dir in "$RA_EXT/config" "$RA_EXT/config/$core"; do
		[ -d "$dir" ] && continue
		mkdir "$dir" || { log "making $dir failed"; return 0; }
		chown "$owner:$AID_EXT_DATA_RW" "$dir"
		chmod 2770 "$dir"
		restorecon "$dir" 2>/dev/null
	done
	if ! { cp "$tmp" "$dest.tmp" && mv -f "$dest.tmp" "$dest"; }; then
		log "writing $dest failed"
		rm -f "$dest.tmp"
		return 0
	fi
	chown "$owner:$AID_EXT_DATA_RW" "$dest"
	chmod 0660 "$dest"
	restorecon "$dest" 2>/dev/null
	game_opt_drop "$key"
	echo "$(sha1 "$dest") $key" >>"$manifest"
	log "seeded config/$core/$name.opt"
}

# seed_game_options ; on a fresh install (the retroarch.cfg we wrote, see
# seed_look): core options for single games that need them on this chip,
# listed in $BASE/retroarch/game-options.txt. RetroArch uses
# config/<core>/<ROM file name>.opt instead of the core's options file for
# that game only. Options missing there get the core's own defaults, not
# ours, so each file starts with our defaults for that core (the
# retroarch-core-options.cfg next to retroarch.cfg), then the game's lines.
# Same rules as seed_look: a file the user changed or removed stays so. Runs
# before seed_look, which marks a new retroarch.cfg as seeded.
seed_game_options() {
	local src=$BASE/retroarch/game-options.txt own=$RA_DATA/.gameconsole
	local manifest=$RA_DATA/.gameconsole/seeded.sha1
	local line l core='' prefix='' defaults owner sets names last=''

	[ -f "$src" ] && [ -d "$RA_EXT" ] && [ -e "$own/look" ] || return 0
	owner=$(stat -c %u "$RA_DATA")
	touch "$manifest"
	# a new retroarch.cfg starts over: files removed before come back
	if [ "$(cat "$own/look")" = new ]; then
		grep -v " games/" "$manifest" >"$manifest.new"
		mv -f "$manifest.new" "$manifest"
	fi
	defaults=$RA_EXT/retroarch-core-options.cfg
	[ -f "$defaults" ] || defaults=$BASE/retroarch/retroarch-core-options.cfg
	sets=$own/game.sets names=$own/game.names
	: >"$sets"
	: >"$names"
	# blocks of "game <ROM name>" lines, then their "set <key> = <value>"
	# lines, under a "core <option prefix> <core name>" line
	while IFS= read -r line || [ -n "$line" ]; do
		case $line in
		'core '* | 'game '*)
			if [ "$last" = opt ]; then
				while IFS= read -r l; do
					game_opt_write "$core" "$prefix" "$l" "$defaults" "$sets" "$owner"
				done <"$names"
				: >"$sets"
				: >"$names"
			fi
			case $line in
			'core '*)
				: >"$names"
				line=${line#core }
				prefix=${line%% *}
				core=${line#* }
				last=core
				;;
			*)
				printf '%s\n' "${line#game }" >>"$names"
				last=game
				;;
			esac
			;;
		'set '*)
			printf '%s\n' "${line#set }" >>"$sets"
			last=opt
			;;
		esac
	done <"$src"
	if [ "$last" = opt ]; then
		while IFS= read -r l; do
			game_opt_write "$core" "$prefix" "$l" "$defaults" "$sets" "$owner"
		done <"$names"
	fi
	rm -f "$sets" "$names" "$own/game.opt"
	chown "$(stat -c %u:%g "$RA_DATA")" "$manifest"
}

# seed_look ; how games look ($BASE/retroarch/look): the sharp bilinear shader
# for every core (config/global.glslp), 3x for the Game Boy
# (config/Gambatte/Gambatte.cfg) and a few shaders to pick from in RetroArch,
# next to retroarch.cfg. Only on installs whose retroarch.cfg we wrote: that
# one turns shaders on and points RetroArch at these folders. An install with
# its own retroarch.cfg keeps its picture as it is. A newer ROM updates a file
# while it is unchanged; a file the user changed or deleted stays that way.
seed_look() {
	local src=$BASE/retroarch/look manifest=$RA_DATA/.gameconsole/seeded.sha1
	local f rel key dest dir part owner

	[ -d "$src" ] && [ -d "$RA_EXT" ] && [ -e "$RA_DATA/.gameconsole/look" ] || return 0
	owner=$(stat -c %u "$RA_DATA")
	touch "$manifest"
	# a new retroarch.cfg starts over: files removed before come back
	if [ "$(cat "$RA_DATA/.gameconsole/look")" = new ]; then
		grep -v " look/" "$manifest" >"$manifest.new"
		mv -f "$manifest.new" "$manifest"
		echo seeded >"$RA_DATA/.gameconsole/look"
	fi
	find "$src" -type f | sort | while read -r f; do
		rel=${f#"$src"/}
		key=look/$rel
		dest=$RA_EXT/$rel
		if [ -e "$dest" ]; then
			[ "$(sha1 "$dest")" = "$(sha1 "$f")" ] && continue
			if ! grep -q "^$(sha1 "$dest") $key\$" "$manifest"; then
				log "keeping user provided $rel"
				continue
			fi
		elif grep -q " $key\$" "$manifest"; then
			# we put it there before, so the user removed it
			continue
		fi
		# missing folders, made the way vold makes them (see seed_retroarch)
		dir=$RA_EXT
		for part in $(echo "${rel%/*}" | tr / ' '); do
			dir=$dir/$part
			[ -d "$dir" ] && continue
			mkdir "$dir" || break
			chown "$owner:$AID_EXT_DATA_RW" "$dir"
			chmod 2770 "$dir"
			restorecon "$dir" 2>/dev/null
		done
		if ! { cp "$f" "$dest.tmp" && mv -f "$dest.tmp" "$dest"; }; then
			log "writing $dest failed"
			rm -f "$dest.tmp"
			continue
		fi
		chown "$owner:$AID_EXT_DATA_RW" "$dest"
		chmod 0660 "$dest"
		restorecon "$dest" 2>/dev/null
		grep -v " $key\$" "$manifest" >"$manifest.new"
		echo "$(sha1 "$dest") $key" >>"$manifest.new"
		mv -f "$manifest.new" "$manifest"
		log "seeded $rel"
	done
	chown "$(stat -c %u:%g "$RA_DATA")" "$manifest"
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
	# which heavy games need a setting, and which run too slow (game-compat)
	[ -f "$BASE/game-tips.txt" ] && cp "$BASE/game-tips.txt" "$root/Game tips.txt"
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
seed_ppsspp
seed_retroarch
seed_cores
seed_game_options
seed_look
find_easyroms
make_rom_folders
# after the ROM folders: they are only made on a card without any of them
bios_folder
