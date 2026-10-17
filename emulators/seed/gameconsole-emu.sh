#!/system/bin/sh
# shellcheck shell=ksh
#
# gameconsole-emu: provisions the bundled emulators after boot.
#
# 0. On the very first start only, while the setup wizard is still on screen:
#    installs the first-start launcher and makes it the only home app, so the
#    tutorial comes up when the wizard ends. See first_start below.
# 1. Installs the APKs from the AUTOAPK partition (/mnt/andr36oid-apps, there
#    until the first start's apps are installed) as regular user apps. They keep
#    their own signature, so F-Droid can update them, and the user can uninstall
#    them. An app the user removed stays removed. Once nothing is left to do with
#    them, /metadata/andr36oid/apps-done lets the next start give AUTOAPK's space
#    to userdata (common/firstresize).
# 2. Gives RetroArch profiles for the built-in pad and external pads and, on a fresh install
#    only, ArkOS-like settings (hotkeys, saves next to the game). A
#    retroarch.cfg that exists is never touched. Core option defaults for
#    the slow Cortex-A35 (retroarch-core-options.cfg) go next to it.
# 3. Seeds the bundled libretro cores into RetroArch's core directory, which is
#    where both RetroArch and Daijishou's RetroArch players look for them.
#    Cores the user replaced (e.g. via RetroArch's online updater) are kept.
#    Then, on a fresh install only, how games look: a sharp scaling shader
#    and 3x for the Game Boy, and core options for the few games that need
#    their own on this chip.
# 4. Makes the usual ROM folders (ArkOS/dArkOS names) on a fresh EASYROMS
#    partition, with a README telling which system goes where. Only once per
#    card, and never on a card that already has any of them. Such a new card
#    also gets a few free games (FreeGames/games.txt) and their licences.
# 5. Points RetroArch's system folder (where the cores look for BIOS files) at
#    the bios folder on EASYROMS, like ArkOS, or on the internal storage for a
#    card made without EASYROMS (.noroms). A folder the user picked in
#    RetroArch is kept.
# 6. Gives Daijishou a platform per ROM folder system with our players, once,
#    before its first start. Run as "gameconsole-emu.sh post-fs-data" (before
#    system_server) it only writes the folder access that needs. Later, when
#    the games on EASYROMS changed, Daijishou asks to look for games again.
# 7. Controllers in RetroArch: the built-in pad is player 1 (or player 2 when
#    Settings > Controllers says so), and every pad can open the menu and
#    quit. See retroarch_players below; "players" as argument only does this.
# 8. PPSSPP: once it has written its controls.ini, the PSP buttons by
#    position on the built-in pad (Cross = B), if still PPSSPP's default.

BASE=/system/etc/gameconsole
STATE=/data/misc/gameconsole
LOG_FILE=$STATE/gameconsole-emu.log
RA_PKG=com.retroarch
RA_DATA=/data/user/0/$RA_PKG
FS_PKG=org.andr36oid.firststart
FS_APK=$BASE/firststart/FirstStart.apk

# Short status codes for FirstStart's translated progress screen. Native property
# writes avoid starting a Java VM for each update. No updates after first setup.
SETUP_PROGRESS_ENABLED=0
setup_progress() {
	[ "$SETUP_PROGRESS_ENABLED" = 1 ] || return 0
	setprop sys.andr36oid.setup_stage "$1"
}
setup_installing() {
	[ "$SETUP_PROGRESS_ENABLED" = 1 ] || return 0
	setprop sys.andr36oid.setup_app "${1%.apk}"
}

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

# netplay_name ; the name the other player sees in RetroArch netplay: the device
# name from Settings > About. While that is still the model name every console
# has, R36S and 4 characters of this console's Android ID, e.g. R36S-3F9A.
netplay_name() {
	local name id dev
	name=$(settings get global device_name </dev/null 2>/dev/null)
	if [ -z "$name" ] || [ "$name" = null ] || [ "$name" = "$(getprop ro.product.model)" ]; then
		dev=$(getprop ro.product.device | tr '[:lower:]' '[:upper:]')
		id=$(settings get secure android_id </dev/null 2>/dev/null | cut -c1-4 |
			tr '[:lower:]' '[:upper:]')
		[ "$id" = NULL ] && id=
		name=${dev:-R36S}${id:+-$id}
	fi
	# RetroArch keeps 31 characters; quotes and backslashes would break the cfg
	echo "$name" | sed 's/["\\]//g' | cut -c1-31
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

# Our launcher's home activity, when the build ships it (apps/Andr36oidLauncher
# sets the property). Hidden like Daijishou's while the first-start launcher is
# home. The setup wizard asks which of the two becomes home; the quick start
# guide applies that when the tutorial is done (QuickStart HomeChoice.java).
LA_HOME=$(getprop ro.andr36oid.launcher_home 2>/dev/null)
LA_HIDDEN=$STATE/firststart.launcher-hidden

# show_home <component> <marker> ; enables a home activity again
show_home() {
	local out
	if out=$(pm enable --user 0 "$1" </dev/null 2>&1) &&
		[ "${out%enabled}" != "$out" ]; then
		rm -f "$2"
		log "home activity ${1%%/*} is enabled again"
	else
		log "enabling home activity ${1%%/*} failed: $out"
	fi
}

# show_daijishou ; enables Daijishou's home activity again
show_daijishou() {
	show_home "$DJ_HOME" "$DJ_HIDDEN"
}

# show_launcher ; enables our launcher's home activity again
show_launcher() {
	show_home "$LA_HOME" "$LA_HIDDEN"
}

# home_role_again ; with two home apps on again, gives the home role to the one
# chosen in the setup wizard (Daijishou without a choice). That writes the
# "always use" home entry for both, so Android doesn't ask which one to use.
home_role_again() {
	local home out
	home=$(settings get global andr36oid_home </dev/null 2>/dev/null)
	case $home in ''|null) home=${DJ_HOME%%/*} ;; esac
	if ! out=$(cmd role add-role-holder --user 0 android.app.role.HOME "$home" </dev/null 2>&1); then
		log "giving $home the home role failed: $out"
	fi
}

# first_start: the first-start launcher (org.andr36oid.firststart, built from
# FirstStart/ in the common device tree) is a tiny user app with a home
# activity that opens the quick start guide's tutorial. It is installed while
# the setup wizard runs, and made the only home app besides the wizard:
# Daijishou's home activity (and our launcher's, if the build ships it) is
# disabled first. So there is never a moment with
# two home apps of the same priority, and Android never asks which one to
# use, however fast the wizard is:
# - the wizard's home activity has priority 9 and wins until it disables it,
# - after that, home is the one app with priority 0 that's there: the
#   launcher, or Daijishou if something failed. If the wizard ends between
#   disabling Daijishou and the install, Settings' FallbackHome shows for a
#   moment (it checks every 500 ms for a real home app) and hands over to the
#   launcher as soon as it's installed.
# The guide undoes all of it when the tutorial is done: it disables the
# launcher's home activity, enables Daijishou's (and our launcher's), gives the
# home role to the one chosen in the wizard and uninstalls the launcher.
# Only on the first start: consoles set up before this build don't get it,
# and it runs once (the marker file), so an uninstall sticks.
first_start() {
	local out done=$STATE/firststart.done

	# Repair, every start: Daijishou and our launcher stay hidden only while
	# the first-start launcher is there (a restart in the middle, or the guide
	# didn't get to it)
	if { [ -e "$DJ_HIDDEN" ] || [ -e "$LA_HIDDEN" ]; } &&
		[ -z "$(installed_vc "$FS_PKG")" ]; then
		[ -e "$DJ_HIDDEN" ] && show_daijishou
		[ -e "$LA_HIDDEN" ] && [ -n "$LA_HOME" ] && show_launcher
		home_role_again
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
	if [ -n "$LA_HOME" ] && [ -n "$(installed_vc "${LA_HOME%%/*}")" ]; then
		touch "$LA_HIDDEN"
		if ! out=$(pm disable --user 0 "$LA_HOME" </dev/null 2>&1) ||
			[ "${out%disabled}" = "$out" ]; then
			log "disabling our launcher's home activity failed, no first-start launcher: $out"
			show_launcher
			show_daijishou
			home_role_again
			touch "$done"
			return 0
		fi
	fi
	if ! out=$(pm install -r --user 0 "$FS_APK" </dev/null 2>&1); then
		log "installing the first-start launcher failed: $out"
		show_daijishou
		[ -e "$LA_HIDDEN" ] && show_launcher
		home_role_again
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

# Cromite blocks the Gamepad API (its RestrictGamepadAccess feature, on by
# default), so browser games don't see the pad, and opens with a welcome screen
# that is no use on a console (--disable-fre skips it). Chromium on Android reads extra
# switches from /data/local/chrome-command-line on any build (only root can
# write there); the first word is a placeholder for the program name. Written
# once, and only if there is no such file: a file the user made is left alone,
# and one the user deleted stays deleted. Delete it to block the pad again: the
# switch beats chrome://flags.
# The marker says "written" when we made the file ("user" when it was there
# already). A power cut can leave our file empty: then it is written again.
CHROME_CMDLINE=/data/local/chrome-command-line
CROMITE_GAMEPAD_DONE=$STATE/cromite-gamepad.done
CROMITE_SWITCHES="--disable-features=RestrictGamepadAccess --disable-fre"
# what earlier builds wrote; a file with exactly this is ours and gets the new line
CROMITE_SWITCHES_OLD="--disable-features=RestrictGamepadAccess"
cromite_cmdline_write() {
	local tmp=$CHROME_CMDLINE.tmp
	rm -f "$tmp"
	# a temp file and a rename, so the file is never there half written.
	# Cromite reads it as its own app user: 644.
	if ! echo "_ $CROMITE_SWITCHES" >"$tmp" ||
		! chmod 644 "$tmp" || ! mv -f "$tmp" "$CHROME_CMDLINE"; then
		rm -f "$tmp"
		log "couldn't write $CHROME_CMDLINE, the browser keeps blocking the pad"
		return 1
	fi
	restorecon "$CHROME_CMDLINE" 2>/dev/null
	echo written >"$CROMITE_GAMEPAD_DONE"
	log "browser games can use the pad now ($CHROME_CMDLINE)"
}
seed_cromite_gamepad() {
	local mark
	if [ -e "$CROMITE_GAMEPAD_DONE" ]; then
		mark=$(cat "$CROMITE_GAMEPAD_DONE" 2>/dev/null)
		# ours, but emptied by a power cut: write it again (deleted stays deleted)
		if [ "$mark" = written ] && [ -f "$CHROME_CMDLINE" ] && [ ! -s "$CHROME_CMDLINE" ]; then
			cromite_cmdline_write
		elif [ "$mark" = written ] && [ "$(cat "$CHROME_CMDLINE" 2>/dev/null)" = "_ $CROMITE_SWITCHES_OLD" ]; then
			cromite_cmdline_write
		fi
		return 0
	fi
	# an empty file does nothing for Chromium: it is ours from a power cut
	# before the marker, or an empty one the user made
	if [ ! -e "$CHROME_CMDLINE" ] || { [ -f "$CHROME_CMDLINE" ] && [ ! -s "$CHROME_CMDLINE" ]; }; then
		cromite_cmdline_write
	else
		echo user >"$CROMITE_GAMEPAD_DONE"
	fi
	return 0
}

# The browsers' default search engine (Thorium, Cromite): DuckDuckGo Light, one
# of Chromium's built-in engines. Chromium keeps the choice in its profile's
# Preferences file, so one is written before the browser's first start (no
# app_chrome folder yet). After that the file is the browser's and never
# touched; the user changes the engine in its settings.
CHROMIUM_PKGS='org.chromium.thorium org.cromite.cromite'
seed_cromite_search() {
	local pkg
	for pkg in $CHROMIUM_PKGS; do
		chromium_search "$pkg"
	done
}
chromium_search() {
	local data=/data/user/0/$1 owner dir
	[ -d "$data" ] || return 0
	[ -e "$data/app_chrome" ] && return 0
	owner=$(stat -c %u:%g "$data") || return 0
	dir=$data/app_chrome/Default
	if ! mkdir -p "$dir" || ! cat >"$dir/Preferences" <<'PREFS'
{"default_search_provider_data":{"template_url_data":{"short_name":"DuckDuckGo Light","keyword":"duckduckgo.com/lite","url":"https://duckduckgo.com/lite/?q={searchTerms}","favicon_url":"https://duckduckgo.com/favicon.ico","safe_for_autoreplace":true,"id":"1000","prepopulate_id":0,"date_created":"0","last_modified":"0","last_visited":"0","usage_count":0,"alternate_urls":[],"input_encodings":["UTF-8"]}}}
PREFS
	then
		rm -rf "$data/app_chrome"
		log "couldn't set $1's search engine"
		return 0
	fi
	chown -R "$owner" "$data/app_chrome"
	chmod 700 "$data/app_chrome" "$dir"
	chmod 600 "$dir/Preferences"
	restorecon -R "$data/app_chrome" 2>/dev/null
	log "$1 searches with DuckDuckGo Light"
}

# The network speed in the status bar (LineageOS' indicator): upload and
# download, in KB/s with the unit, hidden while nothing moves. Each setting is
# written only while it is unset, so a choice made in Settings stays; once all
# four are there it isn't looked at again (each `content` call starts a Java VM).
LINEAGE_SECURE=content://lineagesettings/secure
NET_TRAFFIC='network_traffic_mode:3 network_traffic_autohide:1 network_traffic_units:2 network_traffic_show_units:1'
seed_net_traffic() {
	local pair name value failed=''
	[ -e "$STATE/net-traffic.done" ] && return 0
	for pair in $NET_TRAFFIC; do
		name=${pair%%:*}
		value=${pair#*:}
		content query --uri "$LINEAGE_SECURE" --projection value --where "name='$name'" \
			</dev/null 2>/dev/null | grep -q 'value=' && continue
		if content insert --uri "$LINEAGE_SECURE" --bind "name:s:$name" \
			--bind "value:s:$value" </dev/null >/dev/null 2>&1; then
			log "status bar network speed: $name=$value"
		else
			log "setting $name failed"
			failed=1
		fi
	done
	[ -z "$failed" ] && touch "$STATE/net-traffic.done"
}

# AUTOAPK: <Name>.apk, most with a <Name>.yml (package, version_code, required,
# checked, and the setup's description), and the first-start settings in
# .andr36oid/firstrun. People can add their own APKs from a PC, without a .yml.
APPS=/mnt/andr36oid-apps
APPS_DONE=/metadata/andr36oid/apps-done
# The setup's app page (QuickStart, SetupAppsActivity) picks which of them get
# installed; the "required: true" ones (RetroArch, PPSSPP, Magisk) always are.
# Settings.Global:
APPS_CHOSEN=andr36oid_apps_chosen # 1 once the page was left with Next
APPS_SKIP=andr36oid_apps_skip     # the apps left unticked, by file name without .apk,
                                  # comma separated. Unset (setup done some other
                                  # way): the ones unticked at first, "checked: false"
                                  # and the ones without a .yml.
# Until then (and while the setup isn't done) those apps wait; the page starts
# gameconsole-apps (sys.andr36oid.apps_chosen=1) to install them right away. An app left
# out gets the "seen" mark, so like one the user uninstalled it never comes back.

# apps_wait ; whether the bundled apps still wait for the setup's choice
apps_wait() {
	setup_done && return 1
	[ "$(settings get global $APPS_CHOSEN </dev/null 2>/dev/null)" = 1 ] && return 1
	return 0
}

# One install or first-start step at a time: the main run and gameconsole-apps can
# overlap. In /dev, so a power cut can't leave it behind.
INSTALL_LOCK=/dev/gameconsole-install.lock
install_lock() {
	local i=0
	until mkdir "$INSTALL_LOCK" 2>/dev/null; do
		i=$((i + 1))
		# a run that died holding it: an install takes a minute at most
		if [ $i -gt 450 ]; then
			log "the install lock was held for 15 minutes, taking it"
			rmdir "$INSTALL_LOCK" 2>/dev/null
		fi
		sleep 2
	done
}
install_unlock() {
	rmdir "$INSTALL_LOCK" 2>/dev/null
}

# yml_get <file> <key> ; a top-level value of a .yml, without quotes
yml_get() {
	sed -n "s/^$2:[ 	]*//p" "$1" 2>/dev/null | head -n 1 | sed 's/[ 	]*$//; s/^"\(.*\)"$/\1/'
}

# install_one <apk> <marker> <version> ; pm install, then the installed. or failed. mark
install_one() {
	local out
	setup_installing "${1##*/}"
	if out=$(pm install -r -g --user 0 "$1" </dev/null 2>&1); then
		touch "$STATE/installed.$2"
		rm -f "$STATE/failed.$2"
	else
		log "installing ${1##*/} failed: $out"
		# Only the package's own failures ("Failure [INSTALL_...]") are kept.
		# When Android's package service is gone (system_server restarting) or
		# the console is shutting down (setup's "Shut down now"), the next start
		# tries again.
		case $out in
		*"Failure ["*)
			[ -n "$(getprop sys.shutdown.requested)" ] || echo "$3" >"$STATE/failed.$2"
			;;
		esac
	fi
	setup_installing ""
}

# APPS_LEFT: 1 when install_apks left an app on AUTOAPK for later
APPS_LEFT=''

install_apks() {
	local apk name yml pkg vc cur wait='' skip chosen=1 required

	APPS_LEFT=''
	[ -d "$APPS" ] && ls "$APPS"/*.apk >/dev/null 2>&1 || return 0
	apps_wait && wait=1
	skip=$(settings get global $APPS_SKIP </dev/null 2>/dev/null)
	[ "$skip" = null ] || [ -z "$skip" ] && [ "$(settings get global $APPS_CHOSEN </dev/null 2>/dev/null)" != 1 ] && chosen=''
	skip=",$skip,"
	install_lock
	# the required ones first (RetroArch, PPSSPP, Magisk), then the others in name
	# order, then the ones without a .yml. One per line: names may have spaces.
	# No grep -L: toybox's grep doesn't have it (the list came out empty).
	while IFS= read -r apk; do
		[ -f "$apk" ] || continue
		name=${apk##*/}
		name=${name%.apk}
		yml=${apk%.apk}.yml

		if [ ! -f "$yml" ]; then
			# someone's own APK: only when ticked on the setup's page
			[ -e "$STATE/installed.apk.$name" ] && continue
			[ -e "$STATE/failed.apk.$name" ] && continue
			if [ -n "$wait" ]; then
				APPS_LEFT=1
				continue
			fi
			case $skip in *",$name,"*) continue ;; esac
			[ -n "$chosen" ] || continue
			log "installing $name.apk (added to AUTOAPK, no .yml)"
			install_one "$apk" "apk.$name" 0
			[ -e "$STATE/installed.apk.$name" ] || [ -e "$STATE/failed.apk.$name" ] || APPS_LEFT=1
			continue
		fi

		pkg=$(yml_get "$yml" package)
		vc=$(yml_get "$yml" version_code)
		required=$(yml_get "$yml" required)
		if [ -z "$pkg" ] || [ -z "$vc" ]; then
			log "$name.yml has no package or version_code, skipping $name.apk"
			continue
		fi
		cur=$(installed_vc "$pkg")
		if [ -n "$cur" ]; then
			# remember we have seen it, so an uninstall later on sticks
			touch "$STATE/installed.$pkg"
			[ "$cur" -ge "$vc" ] && continue
		elif [ -e "$STATE/installed.$pkg" ]; then
			continue
		elif [ "$required" != true ]; then
			if [ -n "$wait" ]; then
				APPS_LEFT=1
				continue
			fi
			if [ -n "$chosen" ]; then
				case $skip in *",$name,"*) false ;; *) true ;; esac
			else
				[ "$(yml_get "$yml" checked)" != false ]
			fi || {
				log "not installing $pkg, it was left out in the setup"
				touch "$STATE/installed.$pkg"
				continue
			}
		fi
		# don't retry a version that failed before (e.g. a differently signed
		# build of the same package is installed)
		[ "$(cat "$STATE/failed.$pkg" 2>/dev/null)" = "$vc" ] && continue

		log "installing $name.apk ($pkg $vc, currently: ${cur:-not installed})"
		install_one "$apk" "$pkg" "$vc"
		[ -e "$STATE/installed.$pkg" ] || [ -e "$STATE/failed.$pkg" ] || APPS_LEFT=1
	done <<EOF
$(grep -l '^required:[ 	]*true' "$APPS"/*.yml 2>/dev/null | sed 's/\.yml$/.apk/'
for yml in "$APPS"/*.yml; do [ -f "$yml" ] && ! grep -q '^required:[ 	]*true' "$yml" && echo "${yml%.yml}.apk"; done
for apk in "$APPS"/*.apk; do [ -f "${apk%.apk}.yml" ] || echo "$apk"; done)
EOF
	[ -n "$wait" ] && log "the bundled apps wait for the setup's app page"
	install_unlock
}

# apps_done ; after the installs and first-start settings: when nothing on AUTOAPK
# waits any more, the next start may give its space to userdata
apps_done() {
	[ -d "$APPS" ] && ls "$APPS"/*.apk >/dev/null 2>&1 || return 0
	[ -e "$APPS_DONE" ] && return 0
	apps_wait && return 0
	[ -z "$APPS_LEFT" ] || return 0
	[ -z "$FIRSTRUN_LEFT" ] || { log "first-start settings not all written yet, AUTOAPK stays"; return 0; }
	mkdir -p "${APPS_DONE%/*}" && touch "$APPS_DONE" &&
		log "the apps are done, the next start gives AUTOAPK's space to userdata"
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
	cfg_set "$cfg" netplay_nickname "$(netplay_name)" || log "setting the netplay name failed"
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
	local manifest archive sum stamp tmp so f dest part=0 total=0

	[ -d "$RA_DATA" ] || return 0

	# state lives inside RetroArch's data dir: clearing its data or
	# reinstalling it re-seeds the cores
	mkdir -p "$cores" "$own"
	manifest=$own/seeded.sha1
	touch "$manifest"

	for archive in "$BASE"/libretro/cores-*.tar.gz; do
		[ -f "$archive" ] && total=$((total + 1))
	done
	for archive in "$BASE"/libretro/cores-*.tar.gz; do
		[ -f "$archive" ] || continue
		part=$((part + 1))
		sum=$(sha1 "$archive")
		stamp=$own/${archive##*/}.sha1
		[ "$(cat "$stamp" 2>/dev/null)" = "$sum" ] && continue
		setup_progress "cores:$part:$total"

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
# gives userdata (p6) the whole card (firstresize/andr36oid-firstresize.sh).
EASYROMS_ID="public:179,7"
EASYROMS_PART=/sys/block/mmcblk0/mmcblk0p7
# the internal storage as root sees it; apps see /storage/emulated/0
MEDIA=/data/media/0
ROM_FOLDERS=$BASE/rom-folders.txt

# RetroArch's menu (RGUI) draws Russian, Chinese, Polish and Turkish only with
# font files from its assets folder. Without them it shows English, and says
# "Missing fonts for selected language". The F-Droid APK doesn't have them.
RA_FONTS=$BASE/retroarch/rgui-font
# RetroArch's own assets folder (it unpacks the APK's assets there when opened
# from its icon). Started from Daijishou it gets no folders at all, so it only
# finds the fonts when retroarch.cfg names this folder.
RA_ASSETS=$RA_DATA/assets

# ra_language <locale> ; RetroArch's number (user_language) for the system
# languages it needs our fonts or our help for, nothing for the others.
# Indonesian: Android reports it as "in", which RetroArch doesn't know.
ra_language() {
	case $1 in
	ru|ru-*) echo 9 ;;
	zh-TW|zh-TW-*|zh-HK|zh-HK-*|zh-MO|zh-MO-*|zh-Hant|zh-Hant-*) echo 11 ;;
	zh|zh-*) echo 12 ;;
	pl|pl-*) echo 14 ;;
	tr|tr-*) echo 18 ;;
	in|in-*|id|id-*) echo 24 ;;
	esac
}

# ra_fonts ; copies the RGUI fonts into RetroArch's assets folder, points
# retroarch.cfg at that folder while it names none, and fixes the menu
# language once, see ra_language
ra_fonts() {
	local own=$RA_DATA/.gameconsole cfg=$RA_EXT/retroarch.cfg
	local fonts=$RA_ASSETS/rgui/font owner dir font cur loc lang

	[ -d "$RA_DATA" ] && [ -d "$RA_FONTS" ] || return 0
	owner=$(stat -c %u:%g "$RA_DATA")
	mkdir -p "$own"
	touch "$own/seeded.sha1"
	for dir in "$RA_ASSETS" "${fonts%/*}" "$fonts"; do
		[ -d "$dir" ] && continue
		mkdir "$dir" || { log "making $dir failed"; return 0; }
		chown "$owner" "$dir"
		chmod 0700 "$dir"
	done
	for font in "$RA_FONTS"/*.bin; do
		[ -f "$font" ] && seed_owned "$font" "$fonts/${font##*/}"
	done
	chown -R "$owner" "$fonts" "$own"
	chmod 0600 "$fonts"/*.bin 2>/dev/null
	restorecon "$RA_ASSETS" "${fonts%/*}" 2>/dev/null
	restorecon -R "$fonts" "$own" 2>/dev/null

	[ -f "$cfg" ] || return 0
	# unset, or "default" (what RetroArch writes back when it had no folder)
	cur=$(cfg_get "$cfg" assets_directory)
	case $cur in
	''|default)
		cfg_set "$cfg" assets_directory "$RA_ASSETS" || { log "writing retroarch.cfg failed"; return 0; }
		log "RetroArch's assets folder is $RA_ASSETS now (was: ${cur:-unset})"
		;;
	esac

	# The language, once per RetroArch install. RetroArch takes the system
	# language itself on its first start, but not Indonesian, and it saves
	# English for good when the fonts were missing. So "unset" or English in
	# a system language from ra_language gets that language, one time only:
	# English picked in RetroArch's menu after that stays.
	[ -e "$own/language" ] && return 0
	loc=$(getprop persist.sys.locale)
	lang=$(ra_language "$loc")
	[ -n "$lang" ] || return 0
	cur=$(cfg_get "$cfg" user_language)
	if [ -z "$cur" ] || [ "$cur" = 0 ]; then
		cfg_set "$cfg" user_language "$lang" || { log "writing retroarch.cfg failed"; return 0; }
		log "RetroArch's menu language is $lang now, for $loc (was: ${cur:-unset})"
	fi
	echo "$loc" >"$own/language"
	chown "$owner" "$own/language"
}

# easyroms_path ; prints where EASYROMS is mounted, waits up to a minute for it
easyroms_path() {
	# vold mounts it from /dev/block/vold/public:179,7 at /mnt/media_rw/<uuid>,
	# so /proc/mounts tells. (Asking `sm list-volumes` started a Java VM each
	# time, about a second of CPU per try, while Daijishou starts after boot.)
	local i=0 dev dir _
	while [ $i -lt 60 ]; do
		while read -r dev dir _; do
			[ "$dev" = "/dev/block/vold/$EASYROMS_ID" ] || continue
			case $dir in
			/mnt/media_rw/?*) [ -d "$dir" ] && { echo "$dir"; return 0; } ;;
			esac
		done <"${MOUNTS:-/proc/mounts}"
		i=$((i + 1))
		sleep 1
	done
	return 1
}

# rom_nomedia: an empty .nomedia file in each ROM folder on EASYROMS (ours and
# ArkOS-style ones the user copied). Android's media scanner then files the
# pictures, music and videos in there (box art, snaps, EasyRPG and DOS game
# files, PICO-8 carts) as plain files: Gallery and the music player don't fill
# up with game files, and the scanner skips reading their tags and pictures.
# Only the media scanner reads .nomedia; Daijishou (storage access framework),
# RetroArch (file paths) and PPSSPP list the folders themselves, and all three
# skip hidden files. Each folder once per card: a .nomedia the user deletes
# stays deleted.
rom_nomedia() {
	local root=$EASYROMS noted name
	[ -f "$ROM_FOLDERS" ] && [ -n "$root" ] || return 0
	noted=$STATE/rom-nomedia.${root##*/}
	touch "$noted" || return 0
	while read -r name _; do
		case $name in ''|\#*) continue ;; esac
		[ -d "$root/$name" ] || continue
		grep -qxF "$name" "$noted" && continue
		# touch, not a redirection: a failed redirection on a special
		# built-in (:) ends the whole script in mksh and dash
		if [ -e "$root/$name/.nomedia" ] || touch "$root/$name/.nomedia"; then
			echo "$name" >>"$noted"
		else
			log "couldn't put .nomedia into $name/"
		fi
	done <"$ROM_FOLDERS"
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
			EASYROMS_LATE=1
			trap easyroms_late EXIT
		fi
	else
		NOROMS=1
		log "no EASYROMS partition on this card (.noroms)"
	fi
}

# a few games whose authors allow sharing them (FreeGames/games.txt), so a new
# console has something to play before the user copies games
FREE_GAMES=$BASE/free-games.tar.gz
# free space EASYROMS needs for them, in KB (about 30 MB unpacked)
FREE_GAMES_MIN_FREE=65536

# seed_free_games <EASYROMS root> ; copies each game into its ROM folder, and
# free-games-licenses.txt to the top. A file that is already there is never
# overwritten, and a game whose folder doesn't exist is left out.
seed_free_games() {
	local root=$1 m dir free n=0
	[ -f "$FREE_GAMES" ] && [ -n "$root" ] && [ -d "$root" ] || return 0
	setup_progress games
	free=$(df -k "$root" 2>/dev/null | awk 'NR == 2 { print $4 }')
	case $free in
	''|*[!0-9]*) ;;
	*)
		if [ "$free" -lt "$FREE_GAMES_MIN_FREE" ]; then
			log "EASYROMS has only $free KB free, no free games"
			return 0
		fi
		;;
	esac
	# toybox tar says nothing and exits 0 on a broken archive: an empty list
	if ! tar -tzf "$FREE_GAMES" >"$STATE/free-games.list" 2>/dev/null ||
		[ ! -s "$STATE/free-games.list" ]; then
		log "can't read $FREE_GAMES"
		rm -f "$STATE/free-games.list"
		return 0
	fi
	while IFS= read -r m; do
		case $m in ''|*/|/*|*..*) continue ;; esac
		case $m in
		*/*)
			dir=${m%/*}
			[ -d "$root/$dir" ] || { log "no $dir/ folder on EASYROMS, left out ${m##*/}"; continue; }
			;;
		esac
		# a copy cut off by a power loss on an earlier start
		rm -f "$root/$m.part"
		[ -e "$root/$m" ] && continue
		if tar -xzOf "$FREE_GAMES" "$m" >"$root/$m.part" 2>/dev/null &&
			mv "$root/$m.part" "$root/$m"; then
			n=$((n + 1))
		else
			rm -f "$root/$m.part"
			log "couldn't copy $m to EASYROMS"
		fi
	done <"$STATE/free-games.list"
	rm -f "$STATE/free-games.list"
	log "copied $n files (free games and their licences) to EASYROMS"
}

# release_cards ; tells StorageManagerService the start's steps are done, so it
# mounts the cards it held back (anything but the boot card, see
# StorageManagerService.holdForFirstStartLocked; it gives up waiting after 10
# minutes). The hold ends for good (persist.andr36oid.setup_done) only once
# Daijishou is set up: a start cut short (setup's "Shut down now") or one that
# left Daijishou's platforms for the next start holds the cards again next time.
SETUP_DONE_PROP=persist.andr36oid.setup_done
CARDS_FREE_PROP=sys.andr36oid.cards_free
release_cards() {
	[ "$(getprop $SETUP_DONE_PROP)" = 1 ] && return 0
	if [ -z "$NOROMS" ] && [ -d "$DJ_DATA" ] && [ ! -e "$DJ_DONE" ]; then
		setprop $CARDS_FREE_PROP 1
		log "Daijishou not set up yet, other cards may be mounted now but wait again next start"
		return 0
	fi
	setprop $SETUP_DONE_PROP 1
	log "first start's steps are done, other cards may be mounted now"
}

# easyroms_late: the EASYROMS steps of a start that ended before vold mounted
# it, run from the exit trap: the ROM folders with the free games, their
# .nomedia files, the BIOS folder, Daijishou's systems. Waits up to about 20
# minutes (easyroms_path waits a minute per call).
EASYROMS_LATE_TRIES=20
easyroms_late() {
	local i=0
	trap - EXIT
	setup_progress storage
	while [ $i -lt $EASYROMS_LATE_TRIES ]; do
		EASYROMS=$(easyroms_path) && break
		EASYROMS=
		i=$((i + 1))
	done
	if [ -z "$EASYROMS" ]; then
		log "EASYROMS still not mounted, its steps next start"
		release_cards
		return 0
	fi
	log "EASYROMS mounted at $EASYROMS, doing its steps now"
	setup_progress folders
	make_rom_folders
	command -v rom_nomedia >/dev/null && rom_nomedia
	bios_folder
	setup_progress library
	seed_daijishou
	dj_new_games
	daijishou_ready
	release_cards
}

make_rom_folders() {
	local root=$EASYROMS uuid stamp pending name
	[ -f "$ROM_FOLDERS" ] || return 0
	[ -n "$NOROMS" ] && return 0
	[ -n "$root" ] || { log "EASYROMS not mounted, no ROM folders"; return 0; }
	uuid=${root##*/}
	stamp=$STATE/rom-folders.$uuid
	[ -e "$stamp" ] && return 0
	# set while we set up this card: a start cut off (power) halfway through,
	# e.g. while copying the free games, finishes the job the next time, as
	# the folders there are ours
	pending=$STATE/rom-folders.pending.$uuid

	# a card that already has a ROM folder layout (ArkOS, dArkOS, the user's own)
	# is left exactly as it is
	[ -e "$pending" ] || while read -r name _; do
		case $name in ''|\#*) continue ;; esac
		if [ -d "$root/$name" ]; then
			log "EASYROMS already has $name/, leaving its folders alone"
			touch "$stamp"
			return 0
		fi
	done <"$ROM_FOLDERS"

	touch "$pending"
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
	# only on a card we just set up, so never on a card with the user's games
	seed_free_games "$root"
	touch "$stamp"
	rm -f "$pending"
}

# what we last wrote into RetroArch's video_refresh_rate
REFRESH_MARK=$STATE/retroarch-refresh-rate
DT_ROOT=/proc/device-tree

# dt_u32 <file> ; a one-cell device tree property as a number
dt_u32() {
	od -An -tu1 -N4 "$1" 2>/dev/null |
		awk 'NF == 4 { printf "%d\n", (($1 * 256 + $2) * 256 + $3) * 256 + $4 }'
}

# panel_hz ; the screen's real refresh rate, from the display timing the
# kernel uses (the DTB's native mode): pixel clock / (htotal * vtotal). The
# clock counts in whole kHz, like DRM does. Nothing if there is no such timing.
panel_hz() {
	local t n native first use clk ht vt p v
	for t in "$DT_ROOT"/dsi@*/panel@*/display-timings; do
		[ -d "$t" ] || continue
		native=$(dt_u32 "$t/native-mode")
		first='' use=''
		for n in "$t"/*/; do
			n=${n%/}
			[ -f "$n/clock-frequency" ] || continue
			[ -n "$first" ] || first=$n
			if [ -n "$native" ] && [ "$(dt_u32 "$n/phandle")" = "$native" ]; then
				use=$n
				break
			fi
		done
		# without native-mode the kernel takes the first timing
		use=${use:-$first}
		[ -n "$use" ] || continue
		clk=$(dt_u32 "$use/clock-frequency")
		ht=0 vt=0
		for p in hactive hfront-porch hsync-len hback-porch; do
			v=$(dt_u32 "$use/$p")
			ht=$((ht + ${v:-0}))
		done
		for p in vactive vfront-porch vsync-len vback-porch; do
			v=$(dt_u32 "$use/$p")
			vt=$((vt + ${v:-0}))
		done
		if [ "${clk:-0}" -gt 0 ] && [ "$ht" -gt 0 ] && [ "$vt" -gt 0 ]; then
			awk -v c="$clk" -v h="$ht" -v v="$vt" \
				'BEGIN { printf "%.6f\n", int(c / 1000) * 1000 / (h * v) }'
			return 0
		fi
	done
}

# same_hz <a> <b> ; true if both are set and closer than 0.001 Hz (RetroArch
# stores the value as a float and writes it back with 6 decimals)
same_hz() {
	[ -n "$1" ] && [ -n "$2" ] &&
		awk -v a="$1" -v b="$2" 'BEGIN { d = a - b; exit !(d < 0.001 && d > -0.001) }'
}

# refresh_rate ; tells RetroArch the screen's real refresh rate. RetroArch
# assumes 60 Hz on Android and does not ask the system (Android only knows it
# rounded to whole Hz anyway). With the real rate it resamples the sound so
# that games run exactly one frame per screen refresh: no dropped or doubled
# frames in scrolling games. Set when unset, when RetroArch's default 60 is
# still there and we never set it, or when it is still what we wrote last time
# (e.g. after switching to another panel's DTB). A rate the user set is kept.
refresh_rate() {
	local cfg=$RA_EXT/retroarch.cfg cur want
	[ -f "$cfg" ] || return 0
	want=$(panel_hz)
	[ -n "$want" ] || { log "no panel timing found, RetroArch's refresh rate left as it is"; return 0; }
	cur=$(cfg_get "$cfg" video_refresh_rate)
	same_hz "$cur" "$want" && { echo "$want" >"$REFRESH_MARK"; return 0; }
	if [ -n "$cur" ]; then
		if [ -f "$REFRESH_MARK" ]; then
			same_hz "$cur" "$(cat "$REFRESH_MARK")" || return 0
		else
			same_hz "$cur" 60 || return 0
		fi
	fi
	cfg_set "$cfg" video_refresh_rate "$want" || { log "writing retroarch.cfg failed"; return 0; }
	echo "$want" >"$REFRESH_MARK"
	log "RetroArch's refresh rate is $want Hz now (was: ${cur:-unset})"
}

# First-start settings for the stand-alone apps (foss-apps/<App>/firstrun/,
# see foss-apps/README.md): button maps and hotkeys for the built-in pad,
# written before the app runs for the first time. Each
# $FIRSTRUN/<name>.list has one instruction per line:
#   package <package>
#   unless data|ext <path>  skip the list while this exists: the app has run
#                           and has settings of its own
#   data <file> <path>      copy $FIRSTRUN/<name>/<file> to <path> in the app's
#                           private folder (/data/user/0/<package>)
#   ext <file> <path>       the same into its folder on the internal storage
#                           (Android/data/<package>/files)
#   appop <op> <mode>       e.g. REQUEST_INSTALL_PACKAGES allow, what the app
#                           would otherwise ask for on its first start. Once per
#                           app and op: one the user takes away stays away.
#   notifications on|off   set the app's notification default once; later user
#                           choices are kept, including on subsequent starts.
#   extzip <zip> <dir>      unpack $FIRSTRUN/<name>/<zip> into <dir> in its folder
#                           on the internal storage (e.g. a Luanti game). Once
#                           per app and zip, also after the app has run, so a
#                           deleted game stays deleted; folders that are there
#                           already are kept.
# Only missing files are written, in list order, and a list stops at the first
# failure, so the file the app's own "has it run" check reads goes last.
# <path> may contain spaces.
FIRSTRUN=$APPS/.andr36oid/firstrun
# FIRSTRUN_LEFT: 1 when a list stopped before its end (e.g. the storage wasn't up)
FIRSTRUN_LEFT=''
APP_DATA=/data/user/0

# mkdirs_as <dir> <owner> <mode> ; makes dir and its missing parents
mkdirs_as() {
	[ -d "$1" ] && return 0
	[ -n "$1" ] || return 1
	mkdirs_as "${1%/*}" "$2" "$3" || return 1
	mkdir "$1" && chown "$2" "$1" && chmod "$3" "$1"
}

# firstrun_copy <src> <dest> <owner> <dir mode> ; src to dest unless dest
# exists. Returns 1 on failure, 2 if dest was there already.
firstrun_copy() {
	[ -e "$2" ] && return 2
	[ -f "$1" ] || { log "first-start file $1 is missing"; return 1; }
	mkdirs_as "${2%/*}" "$3" "$4" || { log "making ${2%/*} failed"; return 1; }
	if ! { cp "$1" "$2.tmp" && mv -f "$2.tmp" "$2"; }; then
		log "writing $2 failed"
		rm -f "$2.tmp"
		return 1
	fi
	chown "$3" "$2"
	chmod 0660 "$2"
}

# firstrun_unzip <zip> <dir> <owner> ; unpacks zip into dir, leaving every
# top-level entry that is there already alone. Returns 1 on failure.
firstrun_unzip() {
	local tmp=$2/.firstrun-unzip top
	[ -f "$1" ] || { log "first-start file $1 is missing"; return 1; }
	mkdirs_as "$2" "$3" 2770 || { log "making $2 failed"; return 1; }
	rm -rf "$tmp"
	mkdir "$tmp" || { log "making $tmp failed"; return 1; }
	if ! unzip -q -d "$tmp" "$1" </dev/null >/dev/null 2>&1; then
		log "unpacking $1 failed"
		rm -rf "$tmp"
		return 1
	fi
	chown -R "$3" "$tmp"
	find "$tmp" -type d -exec chmod 2770 {} +
	find "$tmp" -type f -exec chmod 0660 {} +
	for top in "$tmp"/* "$tmp"/.[!.]*; do
		[ -e "$top" ] || continue
		if [ -e "$2/${top##*/}" ]; then
			log "kept the existing $2/${top##*/}"
		elif ! mv "$top" "$2/"; then
			log "moving ${top##*/} into $2 failed"
			rm -rf "$tmp"
			return 1
		fi
	done
	rm -rf "$tmp"
}

# firstrun_list <list>
firstrun_list() {
	local dir=${1%.list} pkg='' data='' ext='' owner='' kind src dest rc stamp out
	local wrote_data='' wrote_ext='' enabled

	while read -r kind src dest; do
		case $kind in
		'' | \#*) continue ;;
		package)
			pkg=$src
			data=$APP_DATA/$pkg
			ext=$MEDIA/Android/data/$pkg/files
			# not installed (or removed by the user)
			[ -d "$data" ] || return 0
			owner=$(stat -c %u:%g "$data")
			continue
			;;
		esac
		[ -n "$pkg" ] || { log "${1##*/}: no package line first"; return 0; }
		case $kind in
		unless)
			case $src in
			data) [ -e "$data/$dest" ] && return 0 ;;
			ext) [ -e "$ext/$dest" ] && return 0 ;;
			*) log "${1##*/}: unknown place $src"; return 0 ;;
			esac
			;;
		data)
			firstrun_copy "$dir/$src" "$data/$dest" "$owner" 0771
			rc=$?
			[ $rc = 1 ] && { FIRSTRUN_LEFT=1; break; }
			[ $rc = 0 ] && wrote_data=1 && log "seeded $pkg's $dest"
			;;
		notifications)
			stamp=$STATE/notifications.$pkg
			[ -e "$stamp" ] && continue
			case $src in
			on) enabled=true ;;
			off) enabled=false ;;
			*) log "${1##*/}: invalid notification default $src"; FIRSTRUN_LEFT=1; break ;;
			esac
			if out=$(cmd notification set_notifications_enabled "$pkg" "$enabled" 0 </dev/null 2>&1); then
				touch "$stamp"
				log "$pkg notification default set ($src)"
			else
				log "setting $pkg notifications failed: $out"
				FIRSTRUN_LEFT=1
				break
			fi
			;;
		appop)
			stamp=$STATE/appop.$pkg.$src
			[ -e "$stamp" ] && continue
			if out=$(appops set --user 0 "$pkg" "$src" "$dest" </dev/null 2>&1); then
				touch "$stamp"
				log "$pkg may $src now ($dest)"
			else
				log "appops set $pkg $src $dest failed: $out"
				FIRSTRUN_LEFT=1
				break
			fi
			;;
		extzip)
			[ -d "$MEDIA/Android/data" ] || { FIRSTRUN_LEFT=1; break; }
			stamp=$STATE/extzip.$pkg.$src
			[ -e "$stamp" ] && continue
			firstrun_unzip "$dir/$src" "$ext/$dest" "${owner%:*}:$AID_EXT_DATA_RW" || { FIRSTRUN_LEFT=1; break; }
			touch "$stamp"
			wrote_ext=1
			log "unpacked $src into $pkg's $dest (internal storage)"
			;;
		ext)
			# vold makes Android/data when it mounts the internal storage
			[ -d "$MEDIA/Android/data" ] || { FIRSTRUN_LEFT=1; break; }
			firstrun_copy "$dir/$src" "$ext/$dest" "${owner%:*}:$AID_EXT_DATA_RW" 2770
			rc=$?
			[ $rc = 1 ] && { FIRSTRUN_LEFT=1; break; }
			[ $rc = 0 ] && wrote_ext=1 && log "seeded $pkg's $dest (internal storage)"
			;;
		*)
			log "${1##*/}: unknown line $kind"
			break
			;;
		esac
	done <"$1"

	[ -n "$wrote_data" ] && restorecon -R "$data" 2>/dev/null
	if [ -n "$wrote_ext" ]; then
		restorecon -R "${ext%/*}" 2>/dev/null
		# vold's own fixup for app folders (default ACL, quota project), as
		# for RetroArch: IStorageManager.fixupAppDir, transaction 89 + 1
		service call mount 90 s16 "/storage/emulated/0/Android/data/$pkg/files" </dev/null >/dev/null 2>&1
	fi
	return 0
}

seed_firstrun() {
	local list
	install_lock
	for list in "$FIRSTRUN"/*.list; do
		[ -f "$list" ] && firstrun_list "$list"
	done
	install_unlock
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

# RetroArch's cheat files from libretro-database, one folder per system
CHEATS_ARCHIVE=$BASE/libretro/cheats.tar.gz
# free space /data needs for them, in KB (about 130 MB used once unpacked)
CHEATS_MIN_FREE=400000

# cheats_ours <cheat_database_path> ; true if we may point it at our folder:
# unset, RetroArch's "default", ours already, or the folder RetroArch picks
# when opened from its icon while that folder has no cheat files in it
cheats_ours() {
	case $1 in
	''|default|"$RA_EXT_PUBLIC/cheats") return 0 ;;
	/storage/emulated/0/RetroArch/cheats|/sdcard/RetroArch/cheats)
		[ -z "$(find "$MEDIA/RetroArch/cheats" -type f 2>/dev/null | head -n 1)" ]
		return
		;;
	esac
	return 1
}

# cheats_merge <from> <to> ; moves the unpacked files into the cheat folder,
# over our older copies; everything else in it stays
cheats_merge() {
	local e
	for e in "$1"/*; do
		[ -e "$e" ] || continue
		if [ -d "$e" ] && [ -d "$2/${e##*/}" ]; then
			cheats_merge "$e" "$2/${e##*/}" || return 1
		else
			mv -f "$e" "$2/${e##*/}" || return 1
		fi
	done
}

# seed_cheats ; RetroArch's online updater can't fetch cheats on an offline
# console, so the Cheats menu (Load Cheat File) would be empty. Points
# cheat_database_path at Android/data/com.retroarch/files/cheats, unless the
# user picked another folder, and unpacks the cheat files there, once per
# archive: a newer ROM with other cheats unpacks again over the old ones.
# Files the user added or saved there stay. A folder the user deleted stays
# deleted until a ROM brings a new archive. The archive is unpacked into
# cheats.new next to it first and moved in only when that worked, and the path
# is set only then: a full disk or a power cut leaves no half folder that
# RetroArch points at.
seed_cheats() {
	local cfg=$RA_EXT/retroarch.cfg dir=$RA_EXT/cheats want=$RA_EXT_PUBLIC/cheats
	local stamp=$RA_DATA/.gameconsole/cheats.sha1 tmp=$RA_EXT/cheats.new cur sum owner free

	[ -f "$CHEATS_ARCHIVE" ] && [ -d "$RA_DATA" ] && [ -f "$cfg" ] || return 0
	cur=$(cfg_get "$cfg" cheat_database_path)
	# a folder the user set: theirs, and no cheats unpacked
	[ "$cur" = "$want" ] || cheats_ours "$cur" || return 0
	# a run cut off while unpacking
	rm -rf "$tmp"

	sum=$(sha1 "$CHEATS_ARCHIVE")
	if [ "$(cat "$stamp" 2>/dev/null)" != "$sum" ]; then
		# the last line, counted from the end: a long device name wraps the line
		free=$(df -k "$RA_EXT" 2>/dev/null | tail -n 1 | awk '{ print $(NF - 2) }')
		if [ "${free:-0}" -lt "$CHEATS_MIN_FREE" ]; then
			log "only ${free:-?} KB free, cheat files not unpacked"
			return 0
		fi
		owner=$(stat -c %u "$RA_DATA")
		mkdir "$tmp" || { log "making $tmp failed"; return 0; }
		# the pipe only returns tar's status, and some tars take a cut-off
		# stream quietly: gzip leaves a mark when it fails
		mkdir -p "${stamp%/*}"
		rm -f "$stamp.err"
		if ! { gzip -dc "$CHEATS_ARCHIVE" || echo failed >"$stamp.err"; } | tar -xof - -C "$tmp" ||
			[ -e "$stamp.err" ]; then
			rm -rf "$stamp.err" "$tmp"
			log "unpacking the cheat files failed"
			return 0
		fi
		if [ ! -d "$dir" ]; then
			# a new folder: the whole unpacked one, made the way vold makes them
			# (see seed_retroarch)
			mv "$tmp" "$dir" || { rm -rf "$tmp"; log "moving the cheat files failed"; return 0; }
		elif ! cheats_merge "$tmp" "$dir"; then
			rm -rf "$tmp"
			log "moving the cheat files failed, tried again next start"
			return 0
		fi
		rm -rf "$tmp"
		chown -R "$owner:$AID_EXT_DATA_RW" "$dir"
		find "$dir" -type d -exec chmod 2770 {} +
		find "$dir" -type f -exec chmod 0660 {} +
		restorecon -R "$dir" 2>/dev/null
		service call mount 90 s16 "$want" </dev/null >/dev/null 2>&1
		echo "$sum" >"$stamp"
		chown "$(stat -c %u:%g "$RA_DATA")" "${stamp%/*}" "$stamp"
		log "unpacked the cheat files into $want"
	fi
	if [ "$cur" != "$want" ]; then
		cfg_set "$cfg" cheat_database_path "$want" || { log "writing retroarch.cfg failed"; return 0; }
		log "RetroArch's cheat folder is $want now (was: ${cur:-unset})"
	fi
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

# missing_buttons ; the buttons this console lacks, from fn,l3,r3,l2,r2,vol, comma
# separated: Settings.Global andr36oid_missing_buttons once Quick start or Settings >
# Controls asked, before that the hint in the joypad's device tree node (missing: none)
missing_buttons() {
	local m
	m=$(settings get global andr36oid_missing_buttons </dev/null 2>/dev/null)
	if [ "$m" = null ]; then
		m=$(tr '\0' ',' 2>/dev/null <"$DT_ROOT/odroidgo3-joypad/andr36oid,missing-buttons")
	fi
	echo "$m" | tr -d ' ' | tr '[:upper:]' '[:lower:]'
}

# ra_hotkeys ; the seeded fast forward hotkey is Select + L3. On a console without L3
# it moves to Select + R2, or Select + Right without R2 as well, and back to L3 once the
# console has it again (the buttons check in Settings > Controls was redone). At every
# boot, so also on the fresh install's retroarch.cfg, but only while the hotkey is still
# what the seed or this wrote (see managed): one set in RetroArch stays.
ra_hotkeys() {
	local cfg=$RA_EXT/retroarch.cfg missing want=106
	[ -f "$cfg" ] || return 0
	missing=,$(missing_buttons),
	case $missing in
	*,l3,*)
		want=105
		case $missing in *,r2,*) want=22 ;; esac
		;;
	*)
		# never moved on this console: nothing to do
		[ -f "$MANAGED/input_toggle_fast_forward_btn" ] || return 0
		;;
	esac
	mkdir -p "$MANAGED"
	managed "$cfg" input_toggle_fast_forward_btn $want "" 106
}

# retroarch_running ; RetroArch writes all its settings back when it quits
retroarch_running() {
	pidof "$RA_PKG" >/dev/null 2>&1
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
# Daijishou's home page (widgets page) on the first start: app shortcuts, in this
# order (Files is documentsui). Set up like that on a console by hand, then copied
# from its Widget.db. An app that isn't installed is left out.
DJ_WIDGET_DB=$DJ_DATA/databases/Widget.db
DJ_WIDGET_SCHEMA=$BASE/daijishou/widget-schema.sql
DJ_WIDGETS='com.retroarch/com.retroarch.browser.mainmenu.MainMenuActivity
com.android.documentsui/com.android.documentsui.LauncherActivity
org.videolan.vlc/org.videolan.vlc.StartActivity
com.android.settings/com.android.settings.Settings'
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

# dj_widgets <tmp dir> <owner> ; Daijishou's widgets (DJ_WIDGETS) in a new Widget.db,
# with Daijishou stopped. A Widget.db with widgets in it, or one that can't be read,
# is the user's and stays.
dj_widgets() {
	local db=$1/Widget.db now c pos=0 n
	[ -f "$DJ_WIDGET_SCHEMA" ] || return 0
	if [ -e "$DJ_WIDGET_DB" ]; then
		# read on a copy: sqlite as root would leave root's -shm next to Daijishou's
		cp "$DJ_WIDGET_DB" "$1/check.db" 2>/dev/null || return 0
		[ -e "$DJ_WIDGET_DB-wal" ] && cp "$DJ_WIDGET_DB-wal" "$1/check.db-wal" 2>/dev/null
		n=$("$SQLITE" "$1/check.db" "SELECT count(*) FROM WidgetInstanceEntity;" 2>/dev/null)
		[ "$n" = 0 ] || return 0
	fi
	now=$(date +%s)000
	{
		cat "$DJ_WIDGET_SCHEMA"
		for c in $DJ_WIDGETS; do
			[ -n "$(installed_vc "${c%%/*}")" ] || continue
			pos=$((pos + 1))
			echo "INSERT INTO AppShortcutWidgetInstanceEntity (id, resolve_info_string_id, alternative_image_path, last_update_timestamp) VALUES ($pos, '$c', NULL, $now);"
			echo "INSERT INTO WidgetInstanceEntity (id, widget_id, widget_instance_id, added_date_timestamp, position) VALUES ($pos, 'app_shortcut', $pos, $now, $((pos - 1)));"
		done
	} >"$1/widgets.sql"
	[ $pos -gt 0 ] || return 0
	if ! "$SQLITE" -bail "$db" <"$1/widgets.sql"; then
		log "making Daijishou's widgets failed"
		return 0
	fi
	chown "$2" "$db"
	chmod 0660 "$db"
	rm -f "$DJ_WIDGET_DB-wal" "$DJ_WIDGET_DB-shm" "$DJ_WIDGET_DB-journal"
	mv -f "$db" "$DJ_WIDGET_DB"
	log "added $pos app shortcuts to Daijishou's home page"
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
	dj_widgets "$tmp" "$owner"
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

# a sync of the whole library reads the start (up to 4 MB) of every game file
# again, Daijishou's md5: roughly a minute or two per 1000 games on this card
# reader. From this many games on, the question says so and how to sync only
# the systems that changed (each system's own Sync button).
DJ_BIG=2000

# dj_hint <old list> <new list> ; the question's text: which systems got new
# games, and for a big library how long the whole sync takes
dj_hint() {
	local total systems mins
	total=$(grep -c . "$2")
	# most new games first, at most six systems
	systems=$(comm -13 "$1" "$2" | cut -d/ -f1 | sort | uniq -c | sort -k1,1nr -k2,2 |
		awk 'NR <= 6 { printf "%s%s (%d)", (NR > 1 ? ", " : ""), $2, $1 } NR == 7 { printf " and more" }')
	printf 'The games on EASYROMS changed. New: %s. Press OK to update the library.' "$systems"
	if [ "$total" -ge "$DJ_BIG" ]; then
		mins=$(((total + 599) / 600))
		printf ' This reads every game again: with %s games it takes about %s minutes. To update only the systems above, press Cancel, open each one and use its Sync button.' "$total" "$mins"
	fi
}

# dj_new_games: Daijishou only looks for games when asked to. On a console we set
# up, when games were added to EASYROMS since the last start (copied on a
# computer, say), Daijishou asks again on its next start, like on the first one.
# Games that were only removed don't count: a sync keeps their entries anyway
# (Daijishou's "clear disjointed items" is off), so it would read every game
# for nothing. Daijishou keeps its settings in memory while it runs, so it's
# restarted around the change; the home screen comes back by itself.
# persist.andr36oid.daijishou_autosync=0 turns this off.
dj_new_games() {
	local uuid seen now top owner hint p=$DJ_PREFS

	[ -f "$DJ_SEEDED" ] && [ -n "$EASYROMS" ] || return 0
	[ "$(getprop persist.andr36oid.daijishou_autosync)" = 0 ] && return 0
	uuid=${EASYROMS##*/}
	seen=$STATE/daijishou-games.$uuid
	now=$seen.new
	# one walk of the folders per start (thousands of games on a slow card)
	dj_games >"$now"
	if [ ! -f "$seen" ] || cmp -s "$seen" "$now"; then
		# same games, or another card (EASYROMS formatted again): nothing to ask
		mv -f "$now" "$seen"
		return 0
	fi
	# Daijishou's library isn't ours any more (data cleared): leave it
	if [ ! -f "$p" ] || [ ! -f "$DJ_DB" ]; then
		mv -f "$now" "$seen"
		return 0
	fi
	if [ -z "$(comm -13 "$seen" "$now" | head -n 1)" ]; then
		log "games on EASYROMS were only removed, Daijishou not asked to look again"
		mv -f "$now" "$seen"
		return 0
	fi
	hint=$(dj_hint "$seen" "$now")

	top=
	dumpsys activity activities 2>/dev/null | grep -q "mResumedActivity.*$DJ_PKG/" && top=1
	am force-stop "$DJ_PKG"
	owner=$(stat -c %u:%g "$p")
	if dj_pref "$p" boolean sync_entirely_on_start '<boolean name="sync_entirely_on_start" value="true" />' &&
		mv -f "$p.new" "$p"; then
		# the dialog's text; Daijishou's own if ours can't go in
		dj_pref "$p" string sync_entirely_on_start_hint "<string name=\"sync_entirely_on_start_hint\">$hint</string>" &&
			mv -f "$p.new" "$p"
		chown "$owner" "$p"
		chmod 0660 "$p"
		restorecon "$p" 2>/dev/null
		log "new games on EASYROMS, Daijishou looks for them on its next start"
	else
		rm -f "$p.new"
		log "Daijishou's settings look odd, not asking it to look for new games"
	fi
	# a Daijishou that started meanwhile has the old settings in memory
	am force-stop "$DJ_PKG"
	[ -n "$top" ] && am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
	mv -f "$now" "$seen"
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

# gameconsole-apps: the setup's app page was left with Next, install what was picked
if [ "$(getprop persist.andr36oid.setup_done)" != 1 ]; then
	SETUP_PROGRESS_ENABLED=1
fi

if [ "${1:-}" = apps ]; then
	log "installing the apps picked in the setup"
	install_apks
	seed_cromite_search
	seed_firstrun
	apps_done
	exit 0
fi

setup_progress apps
seed_cromite_gamepad
seed_net_traffic
first_start
install_apks
# after the installs: the browsers are normal apps installed by install_apks
seed_cromite_search
setup_progress controls
seed_ppsspp
seed_retroarch
seed_core_options
setup_progress cores
seed_cores
seed_game_options
seed_look
setup_progress storage
find_easyroms
refresh_rate
ra_fonts
setup_progress folders
make_rom_folders
# stand-alone apps' first-start settings, after the installs
setup_progress appsettings
seed_firstrun
apps_done
rom_nomedia
# after the ROM folders: they are only made on a card without any of them
bios_folder
# after the cores and ROM folders: a platform needs both
setup_progress library
seed_daijishou
dj_new_games
daijishou_ready
retroarch_players
ra_hotkeys
# PPSSPP isn't running this early; it writes controls.ini back when it quits
ppsspp_controls
# last: the first unpack takes a while
setup_progress cheats
seed_cheats
# the cards held back for the first start can be mounted now (the late EASYROMS
# steps do this themselves when they finish)
[ -n "${EASYROMS_LATE:-}" ] || release_cards
