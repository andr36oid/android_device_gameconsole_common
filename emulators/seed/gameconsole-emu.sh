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
# 2. Gives RetroArch profiles for the built-in pad and external pads and, on a fresh install
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
# 6. Controllers in RetroArch: the built-in pad is player 1 (or player 2 when
#    Settings > Controllers says so), and every pad can open the menu and
#    quit. See retroarch_players below; "players" as argument only does this.
# 7. PPSSPP: once it has written its controls.ini, the PSP buttons by
#    position on the built-in pad (Cross = B), if still PPSSPP's default.

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
	local cfg=$RA_EXT/retroarch.cfg owner pad dir sum

	[ -d "$RA_DATA" ] || return 0
	owner=$(stat -c %u:%g "$RA_DATA")
	mkdir -p "$own" "$auto"
	touch "$own/seeded.sha1"

	# RetroArch looks for pad profiles in autoconfig/android of its data dir:
	# the built-in pad's, ours for external pads and the upstream set. Only
	# when the ROM brings other profiles than last time (a stamp, as for the
	# cores), so a normal boot doesn't hash ~220 files.
	sum=$(cat "$BASE"/retroarch/autoconfig/*.cfg 2>/dev/null | sha1sum | cut -d' ' -f1)
	if [ "$(cat "$own/autoconfig.sha1" 2>/dev/null)" != "$sum" ]; then
		for pad in "$BASE"/retroarch/autoconfig/*.cfg; do
			[ -f "$pad" ] && seed_owned "$pad" "$auto/${pad##*/}"
		done
		chown -R "$owner" "$RA_DATA/autoconfig" "$own"
		restorecon -R "$RA_DATA/autoconfig" "$own" 2>/dev/null
		echo "$sum" >"$own/autoconfig.sha1"
	fi

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

# One retroarch.cfg writer at a time: the start-up run and gameconsole-ra-players
# (Settings > Controllers, when RetroArch closes) can overlap, and one change
# would be lost. The lock is in /dev, so a restart clears it.
CFG_LOCK=${CFG_LOCK:-/dev/gameconsole-emu-cfg.lock}

cfg_lock() {
	local n=0
	while ! mkdir "$CFG_LOCK" 2>/dev/null; do
		# nowhere to lock (a test on a PC): go on
		[ -d "$CFG_LOCK" ] || return 0
		n=$((n + 1))
		# a holder that was killed: go on after 10 s
		if [ "$n" -ge 50 ]; then
			log "retroarch.cfg was locked for 10 s, going on"
			return 0
		fi
		sleep 0.2
	done
}

cfg_unlock() {
	rmdir "$CFG_LOCK" 2>/dev/null
}

# cfg_set <file> <key> <value> ; see cfg_write, under the lock
cfg_set() {
	local rc
	cfg_lock
	cfg_write "$@"
	rc=$?
	cfg_unlock
	return $rc
}

# cfg_write <file> <key> <value> ; written in place, so the file keeps its owner,
# mode and the ACL vold gave it. The new text is made in <file>.new first and
# only goes in when it is complete (every old line but the key's, plus the
# key). A copy of the old file (<file>.bak) is kept until the new text is in;
# if writing it fails (full /data), the old text is put back, or stays in
# <file>.bak if even that fails.
cfg_write() {
	local want
	want=$(($(grep -c '' "$1") - $(grep -c "^[ 	]*$2[ 	]*=" "$1") + 1))
	if ! { grep -v "^[ 	]*$2[ 	]*=" "$1"; echo "$2 = \"$3\""; } >"$1.new" ||
		[ "$(($(wc -l <"$1.new")))" -ne "$want" ] ||
		! { cp "$1" "$1.bak.tmp" && mv -f "$1.bak.tmp" "$1.bak"; }; then
		rm -f "$1.new" "$1.bak.tmp"
		return 1
	fi
	if cat "$1.new" >"$1"; then
		rm -f "$1.new" "$1.bak"
		return 0
	fi
	rm -f "$1.new"
	if cat "$1.bak" >"$1"; then
		rm -f "$1.bak"
		log "writing $1 failed, the old one is back"
	else
		chown "$(stat -c %u:%g "${1%/*}")" "$1.bak" 2>/dev/null
		log "writing $1 failed, the old one is in $1.bak"
	fi
	return 1
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

PP_PKG=org.ppsspp.ppsspp
PP_DATA=/data/user/0/$PP_PKG
# controls.ini files whose face buttons we already put in place
PP_DONE=$STATE/ppsspp-controls

# ppsspp_memstick ; prints PPSSPP's memory stick folder as root sees it. PPSSPP
# asks for it on its first start and keeps it in files/memstick_dir.txt: a
# plain path or a folder picked with the Android file picker (content URI).
ppsspp_memstick() {
	local dir vol rel
	dir=$(cat "$PP_DATA/files/memstick_dir.txt" 2>/dev/null) || return 1
	case $dir in
	content://com.android.externalstorage.documents/tree/*)
		# tree/primary%3APSP or tree/1234-ABCD%3Agames%2Fpsp
		dir=$(echo "${dir#*/tree/}" |
			sed -e 's/%3[Aa]/:/g' -e 's#%2[Ff]#/#g' -e 's/%20/ /g' -e 's/%25/%/g')
		vol=${dir%%:*}
		rel=${dir#*:}
		if [ "$vol" = primary ]; then
			dir=$MEDIA/$rel
		else
			dir=/mnt/media_rw/$vol/$rel
		fi
		;;
	/storage/emulated/0/*) dir=$MEDIA/${dir#/storage/emulated/0/} ;;
	/storage/*) dir=/mnt/media_rw/${dir#/storage/} ;;
	/*) ;;
	*) return 1 ;;
	esac
	[ -d "$dir" ] && echo "${dir%/}"
}

# ppsspp_controls ; PSP buttons by position on the built-in pad. PPSSPP's
# Android default puts Cross on BUTTON_A, which is the bottom button on Xbox
# and PlayStation pads but the right one on this console (and on Switch pads),
# where it sends the printed letter. So Cross, Circle, Square and Triangle
# landed on the right, bottom, top and left buttons. Once PPSSPP has written
# its controls.ini (after its first start), and only while the four face
# buttons are still PPSSPP's default for pads, they are moved to Cross = B,
# Circle = A, Square = Y, Triangle = X, like the PSP. PPSSPP reads every
# controller as the one player. Once per controls.ini, so a mapping the user
# makes later stays.
ppsspp_controls() {
	local root ini
	root=$(ppsspp_memstick) || return 0
	ini=$root/PSP/SYSTEM/controls.ini
	[ -f "$ini" ] || return 0
	grep -qxF "$ini" "$PP_DONE" 2>/dev/null && return 0
	# 10 = PPSSPP's pad device, 96/97/99/100 = BUTTON_A/B/X/Y
	if grep -Eq '^Cross *=.*[= ,]10-96(,|.?$)' "$ini" &&
		grep -Eq '^Circle *=.*[= ,]10-97(,|.?$)' "$ini" &&
		grep -Eq '^Square *=.*[= ,]10-99(,|.?$)' "$ini" &&
		grep -Eq '^Triangle *=.*[= ,]10-100(,|.?$)' "$ini"; then
		sed -E -e '/^Cross *=/s/([= ,])10-96(,|.?$)/\110-97\2/' \
			-e '/^Circle *=/s/([= ,])10-97(,|.?$)/\110-96\2/' \
			-e '/^Square *=/s/([= ,])10-99(,|.?$)/\110-100\2/' \
			-e '/^Triangle *=/s/([= ,])10-100(,|.?$)/\110-99\2/' "$ini" >"$ini.new" &&
			cat "$ini.new" >"$ini" &&
			log "PPSSPP: face buttons by position (Cross = B) in $ini"
		rm -f "$ini.new"
	else
		log "PPSSPP: face buttons in $ini already changed, left alone"
	fi
	echo "$ini" >>"$PP_DONE"
}

# What we last wrote into each controller setting of retroarch.cfg
MANAGED=$STATE/retroarch-managed
# 0x484b:0x1100, the built-in pad (odroidgo3-joypad, name "GO-Super Gamepad")
BUILTIN_PAD=484b:1100

# managed <file> <key> <value> <force> <RetroArch default>... ; sets a setting
# when it is unset, what we wrote last time, or (before we ever wrote it)
# RetroArch's default. Anything else is the user's and stays, unless forced.
managed() {
	local cfg=$1 key=$2 want=$3 force=$4 cur mark d ours=
	shift 4
	cur=$(cfg_get "$cfg" "$key")
	[ "$cur" = "$want" ] && { echo "$want" >"$MANAGED/$key"; return 0; }
	mark=$MANAGED/$key
	if [ -n "$force" ] || [ -z "$cur" ]; then
		ours=1
	elif [ -f "$mark" ]; then
		[ "$cur" = "$(cat "$mark")" ] && ours=1
	else
		for d in "$@"; do [ "$cur" = "$d" ] && ours=1; done
	fi
	[ -n "$ours" ] || return 0
	cfg_set "$cfg" "$key" "$want" || { log "writing $key failed"; return 0; }
	echo "$want" >"$mark"
	log "RetroArch: $key = $want (was: ${cur:-unset})"
}

# retroarch_players [force] ; the built-in pad is player 1 in RetroArch, external
# pads player 2 and up, in the order their first button is pressed. With
# persist.andr36oid.ra_player1=external (Settings > Controllers) it is the
# other way round: the first external pad is player 1, the built-in pad
# player 2. RetroArch's device reservation does this (Settings > Input >
# Port 1 Controls > Device Reservation in RetroArch). "Reserved" keeps that
# player free for the pad until it sends its first input.
# Also for every pad, not only player 1's: L1+R1+Start+Select (already set)
# and a pad's Guide/PS button open the menu, Start+Select (twice) quits, and
# every player can use the menu. The Select hotkeys (save states etc.) only
# work on player 1's pad; RetroArch 1.22 reads hotkeys from player 1 only.
# "force" (the setting was just changed in Settings) overrides values the
# user set in RetroArch itself for the two player settings.
retroarch_players() {
	local cfg=$RA_EXT/retroarch.cfg force=${1:-} p1=$BUILTIN_PAD p2='' t1=2 t2=0
	[ -f "$cfg" ] || return 0
	mkdir -p "$MANAGED"
	# reservation type: 2 = reserved, 0 = none
	if [ "$(getprop persist.andr36oid.ra_player1)" = external ]; then
		p1='' t1=0
		p2=$BUILTIN_PAD t2=2
	fi
	managed "$cfg" input_player1_device_reservation_type $t1 "$force" 0
	managed "$cfg" input_player1_reserved_device "$p1" "$force" ""
	managed "$cfg" input_player2_device_reservation_type $t2 "$force" 0
	managed "$cfg" input_player2_reserved_device "$p2" "$force" ""
	managed "$cfg" all_users_control_menu true "" false
	# 4 = Start+Select, with quit_press_twice (RetroArch's default) on
	managed "$cfg" input_quit_gamepad_combo 4 "" 0
	# RetroArch on Android swaps OK and back in its menu by default: OK on B
	# (bottom), back on A (right). Unswapped, A confirms and B goes back, as
	# printed on the pad and as everywhere else on the console.
	managed "$cfg" menu_swap_ok_cancel_buttons false "" true
}

# retroarch_running ; RetroArch writes all its settings back when it quits
retroarch_running() {
	pidof "$RA_PKG" >/dev/null 2>&1
}

mkdir -p "$STATE"

# gameconsole-ra-players: the player 1 choice in Settings > Controllers changed.
# Applied once RetroArch is closed, as it would write its old values back.
if [ "${1:-}" = players ]; then
	[ "$(getprop sys.boot_completed)" = 1 ] || exit 0
	while retroarch_running; do sleep 5; done
	retroarch_players force
	exit 0
fi

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
retroarch_players
# PPSSPP isn't running this early; it writes controls.ini back when it quits
ppsspp_controls
