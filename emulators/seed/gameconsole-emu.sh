#!/system/bin/sh
# shellcheck shell=ksh
#
# gameconsole-emu: provisions the bundled emulators after boot.
#
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

BASE=/system/etc/gameconsole
STATE=/data/misc/gameconsole
RA_PKG=com.retroarch
RA_DATA=/data/user/0/$RA_PKG

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

# EASYROMS is partition 7 of the boot card (mmcblk0p7, 179:7), a public volume
EASYROMS_ID="public:179,7"
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

make_rom_folders() {
	local root uuid stamp dir name
	[ -f "$ROM_FOLDERS" ] || return 0
	root=$(easyroms_path) || { log "EASYROMS not mounted, no ROM folders"; return 0; }
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

mkdir -p "$STATE"

# boot_completed is set, but give the package manager a moment if needed
i=0
until pm path android </dev/null >/dev/null 2>&1; do
	i=$((i + 1))
	[ $i -gt 60 ] && { log "package manager not up, giving up"; exit 1; }
	sleep 2
done

install_apks
seed_retroarch
seed_cores
make_rom_folders
