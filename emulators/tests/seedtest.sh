# shellcheck shell=ksh
# shellcheck disable=SC2015,SC2034 # ok/bad never fail; the variables are the script's
# Runs the Daijishou steps of seed/gameconsole-emu.sh in a fake /data (sourced by
# run-daijishou-tests.sh under bash and busybox ash). Argument: the emulators dir.

EMU=$1
HERE=$EMU/tests
# in RAM if possible: sqlite syncs every write
T=$(mktemp -d -p /dev/shm 2>/dev/null || mktemp -d)
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
	DJ_SEEDED=$STATE/daijishou.seeded
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
	rm -f "$T/running" "$T/log" "$T/am" "$T/autosync"
	DJ_VC=416
}

# stubs for what only exists on the console
# like the real log(): also on stdout, so a log line inside $(...) shows up as a bug
log() {
	echo "$*" >>"$T/log"
	echo "$*"
}
installed_vc() {
	case $1 in
	"$DJ_PKG") echo "$DJ_VC" ;;
	org.ppsspp.ppsspp) case ",$APPS," in *",$1,"*) echo 119030000 ;; esac ;;
	esac
}
pidof() { [ -e "$T/running" ]; }
restorecon() { :; }
chown() { :; }
timeout() {
	echo "$*" >>"$T/timeout"
	while [ $# -gt 0 ]; do
		case $1 in
		-k | -s) shift 2 ;;
		-*) shift ;;
		*) break ;;
		esac
	done
	shift
	"$@"
}
getprop() { cat "$T/autosync" 2>/dev/null; }
am() { echo "$*" >>"$T/am"; }
dumpsys() { echo "    mResumedActivity: ActivityRecord{1 u0 $DJ_PKG/.activities.MainActivity t9}"; }
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

# 5. a library that can't be read: not touched, not done, tried again next start
setup "" ""
mkdir -p "${DJ_DB%/*}"
echo user >"$DJ_DB"
daijishou_grants
seed_daijishou
[ "$(cat "$DJ_DB")" = user ] && [ ! -e "$DJ_PREFS" ] && [ ! -e "$DJ_DONE" ] && [ ! -e "$DJ_SEEDED" ] &&
	grep -q "can't read Daijishou's library" "$T/log" &&
	ok "unreadable library left alone, not done" || bad "unreadable library"

# 5b. Daijishou started before EASYROMS was mounted and made an empty library;
# an older image had set done for it already: filled now, its settings kept
setup "" ""
daijishou_grants
mkdir -p "${DJ_DB%/*}" "${DJ_PREFS%/*}"
sqlite3 "$DJ_DB" <"$DJ_SCHEMA"
touch "$DJ_DB-wal" "$DJ_DONE"
printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' \
	'    <boolean name="first_launch" value="false" />' '</map>' >"$DJ_PREFS"
touch "$T/running"
seed_daijishou
rm -f "$T/running"
if checklib && [ -e "$DJ_SEEDED" ] && [ ! -e "$DJ_DB-wal" ] &&
	grep -q 'name="first_launch" value="false"' "$DJ_PREFS" &&
	[ "$(grep -c force-stop "$T/am")" -eq 2 ] && grep -q "category.HOME" "$T/am"; then
	ok "empty library from an early Daijishou start: filled, settings kept"
else
	bad "empty library"
fi
sum=$(cat "$DJ_DB" "$DJ_PREFS" | cksum)
seed_daijishou
[ "$(cat "$DJ_DB" "$DJ_PREFS" | cksum)" = "$sum" ] && ok "... once" || bad "empty library filled twice"

# 5e. as found on a console: Daijishou made its empty library in WAL mode (the
# database file is only its header, the tables are in the -wal), and an image
# before took it for the user's and set done: filled now
setup "" ""
daijishou_grants
mkdir -p "${DJ_DB%/*}" "$T/wal"
{
	echo "PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0;"
	cat "$DJ_SCHEMA"
	echo ".shell cp \"$T/wal/Daijishou.db\" \"$T/wal/Daijishou.db-wal\" \"${DJ_DB%/*}/\""
} | sqlite3 "$T/wal/Daijishou.db" >/dev/null
touch "$DJ_DONE"
[ -s "$DJ_DB-wal" ] || bad "5e setup: no WAL"
seed_daijishou
checklib && [ -e "$DJ_SEEDED" ] && ok "empty WAL-mode library, done set: filled" || bad "empty WAL-mode library"

# 5c. a real library with one system of the user's, no done yet: left alone
setup "" ""
mkdir -p "${DJ_DB%/*}"
sqlite3 "$DJ_DB" <"$DJ_SCHEMA"
sqlite3 "$DJ_DB" "INSERT INTO PlatformEntity (id, default_player_id, player_id_list, items_sync_tree_uri_list, unique_id, name, shortname, description, is_visible, scrape_preview_media, wallpaper_path, thumbnail_path, color_theme, color_on_theme, accept_filename_regex, scraper_source_list, box_art_aspect_ratio_id, use_custom_box_art_aspect_ratio, custom_box_art_aspect_ratio, screen_aspect_ratio_id, use_custom_screen_aspect_ratio, custom_screen_aspect_ratio, retro_achievements_console_id_list, extra) VALUES (1, 0, '[]', '[]', 'mine', 'Mine', 'mine', '', 1, 0, '', '', '', '', '', '[]', 0, 0, '', 0, 0, '', '[]', '');" 2>>"$T/log"
sum=$(cksum <"$DJ_DB")
daijishou_grants
seed_daijishou
[ "$(cksum <"$DJ_DB")" = "$sum" ] && [ ! -e "$URI_GRANTS" ] && [ -e "$DJ_DONE" ] && [ ! -e "$DJ_SEEDED" ] &&
	ok "user's own library with a system left alone" || bad "user's library"

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

grep -q -- '-k 2 10 blkid' "$T/timeout" 2>/dev/null && ok "blkid killed if it hangs (timeout -k)" || bad "timeout -k: $(cat "$T/timeout" 2>/dev/null)"
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
# 7 lines: component, ROM, core, config, and the folders RetroArch's own launcher passes
[ "$(printf '%s' "$a" | wc -l)" -eq 6 ] && [ "$(printf '%s\n' "$a" | sed -n 5p)" = " -e DATADIR /data/user/0/com.retroarch" ] &&
	[ "$r" = '^(?!(?:\._|\.).*).*(?<!bml)$' ] &&
	ok "newlines and backslashes kept" || bad "quoting: $r"

# 19. new games: Daijishou asks again on its next start
# what Android writes for Daijishou after its first sync
android_prefs() {
	cat >"$DJ_PREFS" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="upgraded_version" value="416" />
    <boolean name="sync_entirely_on_start" value="false" />
    <string name="sync_entirely_on_start_hint">Update platforms, synchronize library, backup settings and more in one click depended on your settings.</string>
    <string name="uuid">x</string>
</map>
XML
}
prefs_ok() {
	python3 - "$DJ_PREFS" <<'PY'
import sys, xml.etree.ElementTree as ET
m = ET.parse(sys.argv[1]).getroot()
b = [e for e in m if e.get("name") == "sync_entirely_on_start"]
h = [e for e in m if e.get("name") == "sync_entirely_on_start_hint"]
u = [e for e in m if e.get("name") in ("uuid", "upgraded_version")]
sys.exit(0 if len(b) == 1 and b[0].get("value") == "true" and len(h) == 1
         and "changed" in h[0].text and len(u) == 2 else 1)
PY
}
setup "" ""
daijishou_grants
seed_daijishou
android_prefs
cp "$DJ_PREFS" "$T/p0"
dj_new_games
cmp -s "$DJ_PREFS" "$T/p0" && [ ! -e "$T/am" ] && ok "no new games: nothing" || bad "no change"
touch "$EASYROMS/nes/Game (USA).nes" "$EASYROMS/nes/Other.srm" "$EASYROMS/gba/x.state1" "$EASYROMS/psx/.hidden"
rm -f "$EASYROMS/nes/Game (USA).nes"
dj_new_games
cmp -s "$DJ_PREFS" "$T/p0" && [ ! -e "$T/am" ] && ok "saves, states, hidden files: nothing" || bad "saves counted"
# what cores write next to the games with saves in the content folder: DOSBox
# Pure, EasyRPG, PrBoom, FBNeo/MAME (subfolders), and a scraped picture
mkdir -p "$EASYROMS/nes/nvram" "$EASYROMS/nes/hi" "$EASYROMS/nes/Some RPG"
touch "$EASYROMS/nes/Game.pure.zip" "$EASYROMS/nes/Some RPG/Save01.lsd" "$EASYROMS/nes/prbmsav0.dsg" \
	"$EASYROMS/nes/nvram/game.nv" "$EASYROMS/nes/hi/game.hi" "$EASYROMS/nes/Game.PNG" "$EASYROMS/nes/Game.eep"
dj_new_games
cmp -s "$DJ_PREFS" "$T/p0" && [ ! -e "$T/am" ] && ok "core save files and pictures: nothing" || bad "core saves counted"
dj_exts | grep -q '^nes .*|nes|' && ok "file endings from the players" || bad "dj_exts: $(dj_exts | head -n 3)"
touch "$EASYROMS/nes/Game (USA).nes"
dj_new_games
if prefs_ok && [ "$(grep -c force-stop "$T/am")" -eq 2 ] && grep -q "category.HOME" "$T/am"; then
	ok "new game: sync asked for, Daijishou restarted, home back"
else
	bad "new game"
fi
rm -f "$T/am"
dj_new_games
[ ! -e "$T/am" ] && ok "... once" || bad "asked twice"
mkdir -p "$EASYROMS/psx/Game X"
touch "$EASYROMS/psx/Game X/Game X.cue"
echo 0 >"$T/autosync"
dj_new_games
[ ! -e "$T/am" ] && ok "autosync off: nothing" || bad "autosync off"
rm -f "$T/autosync"
dj_new_games
prefs_ok && [ -e "$T/am" ] && ok "game in a subfolder counts" || bad "subfolder"
hint() {
	python3 -c "import xml.etree.ElementTree as E,sys; print([e.text for e in E.parse(sys.argv[1]).getroot() if e.get('name')=='sync_entirely_on_start_hint'][0])" "$DJ_PREFS"
}
# removed games only: a sync would read everything for nothing
rm -f "$T/am"
android_prefs
cp "$DJ_PREFS" "$T/p0"
rm -f "$EASYROMS/nes/Game (USA).nes"
dj_new_games
cmp -s "$DJ_PREFS" "$T/p0" && [ ! -e "$T/am" ] && grep -q "only removed" "$T/log" &&
	ok "games only removed: not asked" || bad "removal asked"
# the question names the systems with new games
touch "$EASYROMS/gba/a.gba" "$EASYROMS/gba/b.gba" "$EASYROMS/snes/c.sfc"
dj_new_games
h=$(hint)
[ "$h" = "The games on EASYROMS changed. New: gba (2), snes (1). Press OK to update the library." ] &&
	prefs_ok && ok "systems with new games named" || bad "hint: $h"
# a big library: how long, and how to sync one system
rm -f "$T/am"
android_prefs
mkdir -p "$EASYROMS/arcade"
i=0
while [ $i -lt 2000 ]; do
	: >"$EASYROMS/arcade/game$i.zip"
	i=$((i + 1))
done
dj_new_games
n=$(grep -c . "$STATE/daijishou-games.$UUID")
h=$(hint)
case $h in
"The games on EASYROMS changed. New: arcade (2000). Press OK to update the library. This reads every game again: with $n games it takes about 4 minutes. To update only the systems above, press Cancel, open each one and use its Sync button.")
	prefs_ok && [ -e "$T/am" ] && [ ! -e "$STATE/daijishou-games.$UUID.new" ] &&
		ok "big library: time and per-system hint ($n games)" || bad "big library"
	;;
*) bad "big hint: $h" ;;
esac
# many systems: the first six by count, then "and more"
android_prefs
# (endings the players take: PlayStation has no zip, the others take zip)
for f in nes snes gb gbc megadrive psx n64; do
	e=zip; [ $f = psx ] && e=bin
	[ -d "$EASYROMS/$f" ] && touch "$EASYROMS/$f/new-$f.$e"
done
touch "$EASYROMS/psx/second.bin"
dj_new_games
h=$(hint)
case $h in
*"New: psx (2), gb (1), gbc (1), megadrive (1), n64 (1), nes (1) and more. Press OK"*) ok "more than six systems cut short" ;;
*) bad "six systems: $h" ;;
esac
# a library we didn't make: never
setup "" ""
mkdir -p "${DJ_DB%/*}" "${DJ_PREFS%/*}"
echo user >"$DJ_DB"
android_prefs
daijishou_grants
seed_daijishou
dj_new_games
touch "$EASYROMS/nes/new.nes"
dj_new_games
[ ! -e "$T/am" ] && grep -q '"false"' "$DJ_PREFS" && ok "user's own library: never touched" || bad "user library"
# a hint over several lines stays, the flag still gets set
setup "" ""
daijishou_grants
seed_daijishou
android_prefs
sed -i 's|one click depended on your settings.</string>|one click\n</string>|' "$DJ_PREFS"
printf '%s\n' '<?xml version="1.0"?>' '<map>' '    <boolean name="sync_entirely_on_start" value="false" />' \
	'    <string name="sync_entirely_on_start_hint">two' 'lines</string>' '</map>' >"$DJ_PREFS"
touch "$EASYROMS/nes/new.nes"
dj_new_games
python3 -c "import xml.etree.ElementTree as E,sys; m=E.parse(sys.argv[1]).getroot(); sys.exit(0 if [e.get('value') for e in m if e.tag=='boolean']==['true'] else 1)" "$DJ_PREFS" &&
	ok "odd hint kept, flag set" || bad "multi-line hint"

[ $fails -eq 0 ]
