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
# 2. Seeds the bundled libretro cores into RetroArch's core directory, which is
#    where both RetroArch and Daijishou's RetroArch players look for them.
#    Cores the user replaced (e.g. via RetroArch's online updater) are kept.

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
seed_cores
