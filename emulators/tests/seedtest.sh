# shellcheck shell=ksh
# shellcheck disable=SC2015,SC2034 # ok/bad never fail; the variables are the script's
# Runs the Daijishou steps of seed/gameconsole-emu.sh in a fake /data (sourced by
# run-daijishou-tests.sh under bash and busybox ash). Argument: the emulators dir.

EMU=$1
HERE=$EMU/tests
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fails=0

GAMECONSOLE_EMU_TEST=1
# shellcheck source=emulators/seed/gameconsole-emu.sh
. "$EMU/seed/gameconsole-emu.sh"

UUID=ABCD-1234
APPS=org.ppsspp.ppsspp

# a fresh fake console: cores (all but $1), ROM folders (all but $2), Daijishou data dir
setup() {
	local skipcore=$1 skipfolder=$2 c f
	rm -rf "$T/c"
	mkdir -p "$T/c"
	BASE=$T/c/system
	STATE=$T/c/misc
	RA_DATA=$T/c/data/com.retroarch
	DJ_DATA=$T/c/data/$DJ_PKG
	DJ_DB=$DJ_DATA/databases/Daijishou.db
	DJ_PREFS=$DJ_DATA/shared_prefs/${DJ_PKG}_preferences.xml
	DJ_SEED=$EMU/daijishou/daijishou-seed.txt
	DJ_SCHEMA=$EMU/daijishou/schema.sql
	DJ_DONE=$STATE/daijishou.done
	URI_GRANTS=$T/c/system_data/urigrants.xml
	EASYROMS_PART=$T/c/sys/mmcblk0p7
	EASYROMS=$T/c/media_rw/$UUID
	NOROMS=
	mkdir -p "$STATE" "$RA_DATA/cores" "$DJ_DATA" "${URI_GRANTS%/*}" "$EASYROMS" "${EASYROMS_PART%/*}"
	touch "$EASYROMS_PART"
	# shellcheck disable=SC2013
	for c in $(awk '!/^#/ && NF { print $1 }' "$EMU/cores.lock"); do
		[ "$c" = "$skipcore" ] || touch "$RA_DATA/cores/${c}_libretro_android.so"
	done
	# shellcheck disable=SC2013
	for f in $(awk '!/^#/ && NF { print $1 }' "$EMU/seed/rom-folders.txt"); do
		[ "$f" = "$skipfolder" ] || mkdir "$EASYROMS/$f"
	done
	rm -f "$T/running" "$T/log"
	DJ_VC=416
}

# stubs for what only exists on the console
log() { echo "$*" >>"$T/log"; }
installed_vc() {
	case $1 in
	"$DJ_PKG") echo "$DJ_VC" ;;
	org.ppsspp.ppsspp) case ",$APPS," in *",$1,"*) echo 119030000 ;; esac ;;
	esac
}
pidof() { [ -e "$T/running" ]; }
restorecon() { :; }
chown() { :; }
timeout() { shift; "$@"; }
blkid() { echo "$EASYROMS_DEV: LABEL=\"EASYROMS\" UUID=\"$UUID\" TYPE=\"exfat\""; }

ok() { echo "ok   $*"; }
bad() {
	echo "FAIL $*"
	[ -f "$T/log" ] && sed 's/^/     log: /' "$T/log"
	fails=$((fails + 1))
}

checklib() {
	python3 "$HERE/checklib.py" "$DJ_SEED" "$DJ_SCHEMA" "$DJ_DB" "$DJ_PREFS" "$URI_GRANTS" \
		"$EASYROMS" "$RA_DATA/cores" "$APPS"
}

# 1. fresh console, everything there
setup "" ""
daijishou_grants
seed_daijishou
if [ -f "$DJ_DB" ] && [ -f "$DJ_DONE" ] && checklib; then
	ok "fresh console: library, settings and grants"
else
	bad "fresh console"
fi
n=$(grep -c "uri-grant " "$URI_GRANTS" 2>/dev/null; true)
[ "$(grep -o '<uri-grant ' "$URI_GRANTS" | wc -l)" -eq "$(dj_folders | wc -l)" ] &&
	ok "one grant per platform folder" || bad "grants: $n"

# 2. running again changes nothing
sum=$(cat "$DJ_DB" "$URI_GRANTS" "$DJ_PREFS" | cksum)
daijishou_grants
seed_daijishou
[ "$(cat "$DJ_DB" "$URI_GRANTS" "$DJ_PREFS" | cksum)" = "$sum" ] &&
	ok "second run changes nothing" || bad "second run changed something"

# 3. a core missing (picodrive: 32X has no other), a folder missing (gbc), no PPSSPP
APPS=
setup picodrive gbc
daijishou_grants
seed_daijishou
if checklib && ! sqlite3 "$DJ_DB" "SELECT unique_id FROM PlatformEntity" | grep -qx 'sega32x\|gbc\|psp'; then
	ok "platforms without player or folder are left out"
else
	bad "missing core / folder / app"
fi
sqlite3 "$DJ_DB" "SELECT player_id_list, default_player_id FROM PlatformEntity WHERE unique_id='genesis'" |
	grep -q '^\[[0-9]*,[0-9]*\]|' && ok "genesis falls back to the other two cores" || bad "genesis players"
APPS=org.ppsspp.ppsspp

# 4. only FOSS cores (no snes9x2010, gpgx, picodrive): genesis gets clownmdemu
setup "" ""
rm -f "$RA_DATA/cores/snes9x2010_libretro_android.so" "$RA_DATA/cores/genesis_plus_gx_libretro_android.so" \
	"$RA_DATA/cores/picodrive_libretro_android.so"
daijishou_grants
seed_daijishou
if checklib && [ "$(sqlite3 "$DJ_DB" "SELECT p.unique_id FROM PlatformEntity f JOIN PlayerEntity p ON p.id = f.default_player_id WHERE f.unique_id='genesis'")" = genesis.andr36oid.clownmdemu ]; then
	ok "FOSS-only image: genesis plays with ClownMDEmu, no SNES"
else
	bad "FOSS-only image"
fi

# 5. Daijishou has a library already: not touched, never again
setup "" ""
mkdir -p "${DJ_DB%/*}"
echo user >"$DJ_DB"
daijishou_grants
seed_daijishou
[ "$(cat "$DJ_DB")" = user ] && [ ! -e "$URI_GRANTS" ] && [ ! -e "$DJ_PREFS" ] && [ -e "$DJ_DONE" ] &&
	ok "existing library left alone" || bad "existing library"

# 6. another Daijishou version
setup "" ""
DJ_VC=417
daijishou_grants
seed_daijishou
[ ! -e "$DJ_DB" ] && [ -e "$DJ_DONE" ] && grep -q "made for 416" "$T/log" &&
	ok "other Daijishou version: nothing added" || bad "other version"

# 7. .noroms card
setup "" ""
rm -f "$EASYROMS_PART"
NOROMS=1
EASYROMS=
daijishou_grants
seed_daijishou
[ ! -e "$DJ_DB" ] && [ ! -e "$URI_GRANTS" ] && [ ! -e "$DJ_DONE" ] &&
	ok ".noroms: nothing" || bad ".noroms"

# 8. Daijishou running: try again next start
setup "" ""
daijishou_grants
touch "$T/running"
seed_daijishou
[ ! -e "$DJ_DB" ] && [ ! -e "$DJ_DONE" ] && ok "running Daijishou: waits" || bad "running"
rm -f "$T/running"
seed_daijishou
[ -e "$DJ_DB" ] && ok "... and adds them on the next start" || bad "next start after running"

# 9. no grants yet (card mounted but post-fs-data had no UUID): waits
setup "" ""
seed_daijishou
[ ! -e "$DJ_DB" ] && [ ! -e "$DJ_DONE" ] && ok "no grants yet: waits" || bad "no grants"

# 10. EASYROMS not mounted at boot_completed: waits
setup "" ""
daijishou_grants
EASYROMS=
seed_daijishou
[ ! -e "$DJ_DB" ] && [ ! -e "$DJ_DONE" ] && ok "EASYROMS not mounted: waits" || bad "not mounted"

# 11. settings the user has are kept
setup "" ""
mkdir -p "${DJ_PREFS%/*}"
echo mine >"$DJ_PREFS"
daijishou_grants
seed_daijishou
[ "$(cat "$DJ_PREFS")" = mine ] && [ -e "$DJ_DB" ] && ok "existing settings kept" || bad "settings"

# 12. grants merged into what system_server wrote (one line, no indent)
setup "" ""
other='<uri-grant sourceUserId="0" targetUserId="0" sourcePkg="com.android.externalstorage" targetPkg="org.example" uri="content://com.android.externalstorage.documents/tree/primary%3AMusic" prefix="true" modeFlags="1" createdTime="1" />'
echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><uri-grants>$other</uri-grants>" >"$URI_GRANTS"
daijishou_grants
seed_daijishou
if grep -qF "$other" "$URI_GRANTS" && checklib; then ok "grants merged, others kept"; else bad "merge"; fi

# 13. empty grants file as system_server writes it
setup "" ""
echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><uri-grants />" >"$URI_GRANTS"
daijishou_grants
seed_daijishou
checklib >/dev/null && ok "empty grants file" || bad "empty grants file"

# 14. a grant the user made already isn't doubled
setup "" ""
mine="<uri-grant sourceUserId=\"0\" targetUserId=\"0\" sourcePkg=\"com.android.externalstorage\" targetPkg=\"$DJ_PKG\" uri=\"$(dj_tree_uri $UUID nes)\" prefix=\"true\" modeFlags=\"3\" createdTime=\"5\" />"
echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><uri-grants>$mine</uri-grants>" >"$URI_GRANTS"
daijishou_grants
[ "$(grep -o "uri=\"$(dj_tree_uri $UUID nes)\"" "$URI_GRANTS" | wc -l)" -eq 1 ] && grep -qF "$mine" "$URI_GRANTS" &&
	ok "existing grant not doubled" || bad "double grant"

# 15. half-written grants file (.bak): not touched
setup "" ""
echo x >"$URI_GRANTS"
echo y >"$URI_GRANTS.bak"
daijishou_grants
[ "$(cat "$URI_GRANTS")" = x ] && [ ! -e "$STATE/daijishou-grants.$UUID" ] && ok ".bak: left alone" || bad ".bak"

# 16. garbage UUID from blkid
setup "" ""
UUID='AB|CD'
daijishou_grants
[ ! -e "$URI_GRANTS" ] && ok "bad UUID: no grants" || bad "bad UUID"
UUID=ABCD-1234

# 17. a grants file that isn't what we expect
setup "" ""
echo "<something/>" >"$URI_GRANTS"
daijishou_grants
[ "$(cat "$URI_GRANTS")" = "<something/>" ] && ok "odd grants file left alone" || bad "odd grants file"

# 18. SQL quoting: every value of the seed file survives the trip into sqlite
setup "" ""
daijishou_grants
seed_daijishou
a=$(sqlite3 "$DJ_DB" "SELECT am_start_arguments FROM PlayerEntity WHERE unique_id='psx.ra.pcsx_rearmed'")
r=$(sqlite3 "$DJ_DB" "SELECT accept_filename_regex FROM PlatformEntity WHERE unique_id='snes'")
[ "$(printf '%s' "$a" | wc -l)" -eq 3 ] && [ "$r" = '^(?!(?:\._|\.).*).*(?<!bml)$' ] &&
	ok "newlines and backslashes kept" || bad "quoting: $r"

[ $fails -eq 0 ]
