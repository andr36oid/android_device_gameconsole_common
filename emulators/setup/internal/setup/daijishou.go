package setup

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"syscall"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

// Daijishou, the home screen. On the first start its library gets one platform per ROM
// folder system we can play, with the folder on EASYROMS as its sync folder and our
// RetroArch core (or PPSSPP) as its player, so the user never has to add platforms or
// pick folders. See emulators/README.md, "Daijishou platforms".
const (
	djPkg   = "com.magneticchen.daijishou"
	safTree = "content://com.android.externalstorage.documents/tree"
	djHint  = "Your game systems are set up with their folders on EASYROMS. Press OK to look for games now. After adding games later, use Sync entirely in the library settings."
	// a sync of the whole library reads the start of every game file again: roughly a
	// minute or two per 1000 games. From this many games on, the question says so.
	djBig = 2000
)

// Daijishou's home page on the first start: app shortcuts, in this order. An app that
// isn't installed is left out.
var djWidgets = []string{
	"com.retroarch/com.retroarch.browser.mainmenu.MainMenuActivity",
	"com.android.documentsui/com.android.documentsui.LauncherActivity",
	"org.videolan.vlc/org.videolan.vlc.StartActivity",
	"com.android.settings/com.android.settings.Settings",
}

func (s *Setup) djData() string     { return s.AppData + "/" + djPkg }
func (s *Setup) djDB() string       { return s.djData() + "/databases/Daijishou.db" }
func (s *Setup) djWidgetDB() string { return s.djData() + "/databases/Widget.db" }
func (s *Setup) djPrefs() string {
	return s.djData() + "/shared_prefs/" + djPkg + "_preferences.xml"
}
func (s *Setup) djSeed() string { return s.Base + "/daijishou/daijishou-seed.txt" }
func (s *Setup) djArt() string  { return s.Base + "/daijishou/art" }

// djDone: set once we added the platforms, or decided never to. djSeeded: the card UUID
// if we made the library (DJNewGames only runs then).
func (s *Setup) djDone() string   { return s.state("daijishou.done") }
func (s *Setup) djSeeded() string { return s.state("daijishou.seeded") }

// sqlite is the sqlite3 to use: Android's quits in this service (it can't load ICU), so
// the image has its own without ICU; plain sqlite3 is for tests on a PC.
func (s *Setup) sqlite() string {
	if s.SQLite != "" {
		return s.SQLite
	}
	if _, err := exec.LookPath("gameconsole-sqlite3"); err == nil {
		return "gameconsole-sqlite3"
	}
	return "sqlite3"
}

// sqlRun runs a SQL file on a database, stopping at the first error.
func (s *Setup) sqlRun(db, file string) error {
	out, err := s.R.Run(s.sqlite(), "-bail", db, ".read "+file)
	if err != nil {
		return fmt.Errorf("%v: %s", err, out)
	}
	if strings.Contains(out, "Error:") || strings.Contains(out, "Parse error") {
		return fmt.Errorf("%s", out)
	}
	return nil
}

// sqlQuery is one query's output, trimmed.
func (s *Setup) sqlQuery(db, query string) (string, error) {
	out, err := s.R.Run(s.sqlite(), db, query)
	return strings.TrimSpace(out), err
}

// djLibraryCount is systems + games in Daijishou's library, -1 if it can't be read.
// Counted in a copy: the live one is open in Daijishou, in WAL mode.
func (s *Setup) djLibraryCount() int {
	tmp := s.state("daijishou-check")
	_ = os.RemoveAll(tmp)
	if err := os.MkdirAll(tmp, 0o700); err != nil {
		return -1
	}
	defer os.RemoveAll(tmp)
	if err := copyPlain(s.djDB(), tmp+"/Daijishou.db"); err != nil {
		return -1
	}
	if sys.Exists(s.djDB() + "-wal") {
		_ = copyPlain(s.djDB()+"-wal", tmp+"/Daijishou.db-wal")
	}
	out, err := s.sqlQuery(tmp+"/Daijishou.db",
		"SELECT (SELECT count(*) FROM PlatformEntity) + (SELECT count(*) FROM PlayableItemEntity);")
	n, perr := strconv.Atoi(out)
	if err != nil || perr != nil {
		if len(out) > 200 {
			out = out[:200]
		}
		s.Log("can't read Daijishou's library: %s", orNoAnswer(out))
		return -1
	}
	return n
}

func orNoAnswer(s string) string {
	if s == "" {
		return "no answer"
	}
	return s
}

func copyPlain(src, dst string) error {
	b, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	return os.WriteFile(dst, b, 0o600)
}

type libState int

const (
	libOwn     libState = iota // a system or a game in it: the user's
	libEmpty                   // none, or the empty one Daijishou makes before we filled it
	libUnknown                 // can't tell: leave it, try again next start
)

func (s *Setup) djUserLibrary() libState {
	if !sys.Exists(s.djDB()) {
		return libEmpty
	}
	switch n := s.djLibraryCount(); {
	case n == 0:
		return libEmpty
	case n < 0:
		return libUnknown
	}
	return libOwn
}

// djSettled: nothing left to do for Daijishou's library: we made it, the user has one, or
// we decided never to.
func (s *Setup) djSettled() bool {
	if sys.Exists(s.djSeeded()) {
		return true
	}
	if s.djUserLibrary() == libOwn {
		return true
	}
	return sys.Exists(s.djDone()) && !sys.Exists(s.djDB())
}

// seedLine is a tab-separated line of daijishou-seed.txt.
type seedLine struct{ kind, n, arg, vals string }

func (s *Setup) djSeedLines() []seedLine {
	b, err := os.ReadFile(s.djSeed())
	if err != nil {
		return nil
	}
	var out []seedLine
	for _, l := range splitLines(string(b)) {
		f := strings.SplitN(l, "\t", 4)
		for len(f) < 4 {
			f = append(f, "")
		}
		out = append(out, seedLine{f[0], f[1], f[2], f[3]})
	}
	return out
}

// djFolders is every ROM folder that gets a Daijishou platform.
func (s *Setup) djFolders() []string {
	var out []string
	for _, l := range s.djSeedLines() {
		if l.kind != "platform" || l.n == "" || strings.Trim(l.n, "0123456789") != "" {
			continue
		}
		out = append(out, strings.Split(l.arg, ",")...)
	}
	return out
}

func djTreeURI(uuid, folder string) string { return safTree + "/" + uuid + "%3A" + folder }

var (
	uuidOK      = regexp.MustCompile(`^[0-9A-Fa-f-]+$`)
	djGrantUUID = regexp.MustCompile(`<uri-grant\s[^>]*\btargetPkg="` + regexp.QuoteMeta(djPkg) +
		`" uri="` + regexp.QuoteMeta(safTree) + `/([0-9A-Fa-f-]+)%3A[^"<>]*"`)
)

// DaijishouGrants: Daijishou reads its games through Android's storage access framework,
// so it may only open a folder the user picked for it in the file picker, which remembers
// that as a persisted URI grant in /data/system/urigrants.xml. We write the same grants
// the picker would for every folder we give a platform, adding missing ones on upgrades.
// system_server reads the file only when it starts, so this runs from post-fs-data,
// before it does. EASYROMS isn't mounted yet then: its UUID is the exFAT boot sector's
// serial, as vold formats it.
func (s *Setup) DaijishouGrants() {
	if !sys.IsFile(s.djSeed()) {
		return
	}
	if sys.Exists(s.djDone()) && s.djSettled() {
		// Existing grants identify the card without probing its boot sector again.
		cur, err := os.ReadFile(s.URIGrants)
		if err != nil {
			return
		}
		text := string(cur)
		if match := djGrantUUID.FindStringSubmatch(text); match != nil {
			s.addMissingGrants(match[1], text, cur, false)
		}
		return
	}
	// .noroms: games live on the internal storage, no platforms
	if !sys.Exists(s.easyromsProp("part", s.EasyromsPart)) {
		return
	}
	if s.djUserLibrary() == libOwn {
		return
	}
	// init waits for this step: KILL 2 s after TERM for a read stuck on a failing card
	dev := s.easyromsProp("dev", baseOf(s.EasyromsDev))
	if dev == "" {
		return
	}
	out, err := s.R.Run("timeout", "-k", "2", "10", "dd", "if="+dirOf(s.EasyromsDev)+"/"+dev,
		"bs=512", "count=1", "status=none")
	uuid := ""
	if err == nil && len(out) == 512 && out[3:11] == "EXFAT   " && out[510:] == "\x55\xaa" {
		uuid = fmt.Sprintf("%02X%02X-%02X%02X", out[103], out[102], out[101], out[100])
	}
	if !uuidOK.MatchString(uuid) {
		s.Log("no usable UUID for EASYROMS (%s), no folder access for Daijishou", orNone(uuid))
		return
	}
	stamp := s.state("daijishou-grants." + uuid)
	if sys.Exists(stamp) {
		return
	}
	cur, _ := os.ReadFile(s.URIGrants)
	if s.addMissingGrants(uuid, string(cur), cur, true) {
		_ = fsx.Touch(stamp)
	}
}

// addMissingGrants preserves existing grants and adds any missing platform folders.
// It reports whether the grants are complete, so first setup can record its stamp.
// First setup restores labels with the Runner; reconcile preserves the existing label
// directly so a settled console never needs another Runner command for grants.
func (s *Setup) addMissingGrants(uuid, text string, cur []byte, restoreLabels bool) bool {
	f := s.URIGrants
	// a write system_server didn't finish: it recovers from it on start, not us
	if sys.Exists(f+".bak") || sys.Exists(f+".new") {
		s.Log("%s is being written, Daijishou's folder access next start", f)
		return false
	}
	now := strconv.FormatInt(s.Now().Unix(), 10) + "000"
	var lines strings.Builder
	for _, folder := range s.djFolders() {
		uri := djTreeURI(uuid, folder)
		// already there (the user picked the folder, or an earlier try)
		if strings.Contains(text, `targetPkg="`+djPkg+`" uri="`+uri+`"`) {
			continue
		}
		lines.WriteString(`<uri-grant sourceUserId="0" targetUserId="0" sourcePkg="com.android.externalstorage" targetPkg="` +
			djPkg + `" uri="` + uri + `" prefix="true" modeFlags="3" createdTime="` + now + `" />`)
	}
	if lines.Len() > 0 {
		add := lines.String()
		var next string
		emptyTag := regexp.MustCompile(`<uri-grants */>`)
		switch {
		case len(cur) > 0 && strings.Contains(text, "</uri-grants>"):
			next = strings.Replace(text, "</uri-grants>", add+"</uri-grants>", 1)
		case len(cur) > 0 && emptyTag.MatchString(text):
			loc := emptyTag.FindStringIndex(text)
			next = text[:loc[0]] + "<uri-grants>" + add + "</uri-grants>" + text[loc[1]:]
		case len(cur) > 0:
			s.Log("%s looks odd, Daijishou gets no folder access", f)
			return false
		default:
			next = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><uri-grants>" + add + "</uri-grants>\n"
		}
		tmp := f + ".tmp"
		if err := os.WriteFile(tmp, []byte(next), 0o600); err != nil {
			_ = os.Remove(tmp)
			s.Log("writing %s failed", f)
			return false
		}
		_ = fsx.SetOwnerMode(tmp, fsx.Owner{UID: 1000, GID: 1000}, 0o600)
		if !restoreLabels {
			if err := copySELinuxLabel(f, tmp); err != nil {
				_ = os.Remove(tmp)
				s.Log("preserving %s SELinux label failed: %v", f, err)
				return false
			}
		}
		if err := syncRename(tmp, f); err != nil {
			_ = os.Remove(tmp)
			s.Log("writing %s failed", f)
			return false
		}
		if restoreLabels {
			s.restorecon(false, f)
		}
		s.Log("Daijishou may read the ROM folders on EASYROMS (%s)", uuid)
	}
	return true
}

// copySELinuxLabel keeps an existing file's label across an atomic replacement.
// Test hosts may have no SELinux attribute or no extended-attribute support.
func copySELinuxLabel(src, dst string) error {
	const attr = "security.selinux"
	n, err := syscall.Getxattr(src, attr, nil)
	if err == syscall.ENODATA || err == syscall.ENOTSUP {
		return nil
	}
	if err != nil {
		return err
	}
	label := make([]byte, n)
	n, err = syscall.Getxattr(src, attr, label)
	if err != nil {
		return err
	}
	return syscall.Setxattr(dst, attr, label[:n], 0)
}

func orNone(v string) string {
	if v == "" {
		return "none"
	}
	return v
}

// syncRename syncs a file and renames it over another.
func syncRename(tmp, dst string) error {
	f, err := os.OpenFile(tmp, os.O_RDWR, 0)
	if err != nil {
		return err
	}
	err = f.Sync()
	f.Close()
	if err != nil {
		return err
	}
	return os.Rename(tmp, dst)
}

// djHas: the core (core:<name>) or app (app:<package>) is there.
func (s *Setup) djHas(need string) bool {
	switch {
	case strings.HasPrefix(need, "core:"):
		return sys.IsFile(s.raData() + "/cores/" + strings.TrimPrefix(need, "core:") + "_libretro_android.so")
	case strings.HasPrefix(need, "app:"):
		return s.installedVC(strings.TrimPrefix(need, "app:")) != ""
	}
	return false
}

type djArtItem struct {
	id   int
	file string
}

// djSQL is the rows for Daijishou's library. A platform gets the folders that are on the
// card and the players that can run here; the first player is its default. Without
// either, no platform (and no players for it).
func (s *Setup) djSQL(root, uuid string) (string, []djArtItem) {
	var b strings.Builder
	var arts []djArtItem
	pid, plat := 0, 0
	pnum, pvals, art := "", "", ""
	var players, uris []string
	data := s.djData()
	flush := func() {
		if pnum == "" || len(players) == 0 {
			return
		}
		plat++
		fmt.Fprintf(&b, "INSERT INTO PlatformEntity (id, default_player_id, player_id_list, items_sync_tree_uri_list, unique_id, name, shortname, description, is_visible, scrape_preview_media, wallpaper_path, thumbnail_path, color_theme, color_on_theme, accept_filename_regex, scraper_source_list, box_art_aspect_ratio_id, use_custom_box_art_aspect_ratio, custom_box_art_aspect_ratio, screen_aspect_ratio_id, use_custom_screen_aspect_ratio, custom_screen_aspect_ratio, retro_achievements_console_id_list, extra) VALUES (%d, %s, '[%s]', '[%s]', %s);\n",
			plat, players[0], strings.Join(players, ","), strings.Join(uris, ","), pvals)
		if art != "" {
			fmt.Fprintf(&b, "UPDATE PlatformEntity SET wallpaper_path = '%s/files/platform_wallpapers/%d.png', thumbnail_path = '%s/files/platform_thumbnails/%d.png' WHERE id = %d;\n",
				data, plat, data, plat, plat)
			arts = append(arts, djArtItem{plat, art})
		}
	}
	b.WriteString("BEGIN;\n")
	for _, l := range s.djSeedLines() {
		switch l.kind {
		case "platform":
			flush()
			art, uris, players, pnum = "", nil, nil, ""
			for _, f := range strings.Split(l.arg, ",") {
				if f != "" && sys.IsDir(root+"/"+f) {
					uris = append(uris, `"`+djTreeURI(uuid, f)+`"`)
				}
			}
			// no folder on this card: skip the platform and its players
			if len(uris) > 0 {
				pnum = l.n
			}
			pvals = l.vals
		case "art":
			if pnum != "" && l.n == pnum && sys.IsFile(s.djArt()+"/"+l.arg) {
				art = l.arg
			}
		case "player":
			if pnum == "" || l.n != pnum || !s.djHas(l.arg) {
				continue
			}
			pid++
			players = append(players, strconv.Itoa(pid))
			fmt.Fprintf(&b, "INSERT INTO PlayerEntity (id, unique_id, player, name, description, am_start_arguments, kill_package_processes, kill_package_processes_warning, accepted_filename_regex, extra) VALUES (%d, %s);\n", pid, l.vals)
		}
	}
	flush()
	b.WriteString("COMMIT;\n")
	return b.String(), arts
}

// djCopyArt copies the platforms' pictures into Daijishou's folders, where it keeps the
// ones it downloads (it reads them by content, so a JPEG is fine). A picture that can't be
// copied only leaves that system plain.
func (s *Setup) djCopyArt(arts []djArtItem, owner fsx.Owner) {
	if len(arts) == 0 {
		return
	}
	files := s.djData() + "/files"
	for _, d := range []string{files, files + "/platform_wallpapers", files + "/platform_thumbnails"} {
		if !sys.IsDir(d) {
			_ = fsx.MkdirAs(d, owner, 0o771)
		}
	}
	for _, a := range arts {
		for _, d := range []string{"platform_wallpapers", "platform_thumbnails"} {
			dest := fmt.Sprintf("%s/%s/%d.png", files, d, a.id)
			if err := fsx.CopyAtomic(s.djArt()+"/"+a.file, dest); err != nil {
				s.Log("copying the picture %s for platform %d failed", a.file, a.id)
				continue
			}
			_ = fsx.SetOwnerMode(dest, owner, 0o660)
		}
	}
	s.restorecon(true, files)
}

// djTheme is Daijishou's theme color on the first start. It has no orange (the system's
// accent); yellow is the nearest of its purple, yellow, red, blue, pink, grey and green.
const djTheme = "yellow"

// djNoKillWarning is Daijishou's "Disable player warnings" (Library settings): without
// it, starting a game asks first whether the player app may be stopped.
const djNoKillWarning = "disable_player_kill_package_processes_warning"

// djPrefsText is Daijishou's settings file for its first start: it asks once to sync the
// whole library, which finds the games in the folders.
const djPrefsText = `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="sync_entirely_on_start" value="true" />
    <string name="theme_id">` + djTheme + `</string>
    <boolean name="` + djNoKillWarning + `" value="true" />
    <string name="sync_entirely_on_start_hint">` + djHint + `</string>
    <string name="sync_entirely_on_start_cancel_hint">Canceled. Sync entirely in the library settings looks for games later.</string>
</map>
`

// djPref sets one setting in a shared prefs text. Only an element on one line can be
// dropped by line (Android writes one per line); false if the text looks odd.
func djPref(text, tag, name, element string) (string, bool) {
	open := `<` + tag + ` name="` + name + `"`
	var keep []string
	for _, l := range splitLines(text) {
		if strings.Contains(l, open) {
			if !strings.Contains(l, "</"+tag+">") && !strings.Contains(l, "/>") {
				return "", false
			}
			continue
		}
		keep = append(keep, l)
	}
	cur := strings.Join(keep, "\n")
	if !strings.Contains(cur, "</map>") {
		return "", false
	}
	return strings.Replace(cur, "</map>", element+"</map>", 1) + "\n", true
}

// djAskSync makes Daijishou ask to sync the whole library on its next start, with hint as
// the question (Daijishou's own if ours can't go in). False if the file looks odd.
func djAskSync(p, hint string) bool {
	b, err := os.ReadFile(p)
	if err != nil {
		return false
	}
	text, ok := djPref(string(b), "boolean", "sync_entirely_on_start", `<boolean name="sync_entirely_on_start" value="true" />`)
	if !ok {
		return false
	}
	if t, ok := djPref(text, "string", "sync_entirely_on_start_hint",
		`<string name="sync_entirely_on_start_hint">`+xmlEscape(hint)+`</string>`); ok {
		text = t
	}
	// player warnings off, unless the user set them already
	if !strings.Contains(text, `name="`+djNoKillWarning+`"`) {
		if t, ok := djPref(text, "boolean", djNoKillWarning, `<boolean name="`+djNoKillWarning+`" value="true" />`); ok {
			text = t
		}
	}
	// a theme color picked already stays
	if !strings.Contains(text, `name="theme_id"`) {
		if t, ok := djPref(text, "string", "theme_id", `<string name="theme_id">`+djTheme+`</string>`); ok {
			text = t
		}
	}
	return fsx.WriteFileAtomic(p, []byte(text)) == nil
}

func xmlEscape(s string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;").Replace(s)
}

// djWidgetsMake puts Daijishou's widgets into a new Widget.db, with Daijishou stopped. A
// Widget.db with widgets in it, or one that can't be read, is the user's and stays.
func (s *Setup) djWidgetsMake(tmp string, owner fsx.Owner) {
	s.djWidgetsWrite(tmp, owner, false)
}

// djWidgetsWrite is djWidgetsMake; replace also writes over a Widget.db that has widgets
// (the caller checked they are only ours).
func (s *Setup) djWidgetsWrite(tmp string, owner fsx.Owner, replace bool) {
	schema := s.Base + "/daijishou/widget-schema.sql"
	if !sys.IsFile(schema) {
		return
	}
	live := s.djWidgetDB()
	if sys.Exists(live) && !replace {
		// read on a copy: sqlite as root would leave root's -shm next to Daijishou's
		if copyPlain(live, tmp+"/check.db") != nil {
			return
		}
		if sys.Exists(live + "-wal") {
			_ = copyPlain(live+"-wal", tmp+"/check.db-wal")
		}
		n, err := s.sqlQuery(tmp+"/check.db", "SELECT count(*) FROM WidgetInstanceEntity;")
		if err != nil || n != "0" {
			return
		}
	}
	sch, err := os.ReadFile(schema)
	if err != nil {
		return
	}
	now := s.Now().Unix()
	var b strings.Builder
	b.Write(sch)
	if len(sch) > 0 && sch[len(sch)-1] != '\n' {
		b.WriteByte('\n')
	}
	pos := 0
	for _, c := range djWidgets {
		if s.installedVC(pkgOf(c)) == "" {
			continue
		}
		pos++
		fmt.Fprintf(&b, "INSERT INTO AppShortcutWidgetInstanceEntity (id, resolve_info_string_id, alternative_image_path, last_update_timestamp) VALUES (%d, '%s', NULL, %d000);\n", pos, c, now)
		fmt.Fprintf(&b, "INSERT INTO WidgetInstanceEntity (id, widget_id, widget_instance_id, added_date_timestamp, position) VALUES (%d, 'app_shortcut', %d, %d000, %d);\n", pos, pos, now, pos-1)
	}
	if pos == 0 {
		return
	}
	sqlFile := tmp + "/widgets.sql"
	db := tmp + "/Widget.db"
	if err := os.WriteFile(sqlFile, []byte(b.String()), 0o600); err != nil {
		s.Log("making Daijishou's widgets failed: %v", err)
		return
	}
	if err := s.sqlRun(db, sqlFile); err != nil {
		s.Log("making Daijishou's widgets failed: %v", err)
		return
	}
	for _, x := range []string{"-wal", "-shm", "-journal"} {
		_ = os.Remove(live + x)
	}
	if err := moveInto(db, live, owner); err != nil {
		s.Log("making Daijishou's widgets failed (moving them in): %v", err)
		return
	}
	s.Log("added %d app shortcuts to Daijishou's home page", pos)
}

// moveInto puts a database made in our temp folder into an app's folder, owned by the app
// and 0660. A rename that fails (another file system, for one) becomes a copy, like mv.
func moveInto(src, dst string, owner fsx.Owner) error {
	_ = fsx.SetOwnerMode(src, owner, 0o660)
	if err := os.Rename(src, dst); err == nil {
		return nil
	}
	if err := fsx.CopyAtomic(src, dst); err != nil {
		return err
	}
	_ = os.Remove(src)
	return fsx.SetOwnerMode(dst, owner, 0o660)
}

// djWidgetsAtEnd makes Daijishou's home page (app shortcuts) at the very end of the first
// setup, once the apps picked on the setup's app page (VLC) are installed too. Only with
// the library we made, while Daijishou isn't running (it keeps the page in memory and would
// write it back), and over a page that has nothing or just our shortcuts in our order.
func (s *Setup) djWidgetsAtEnd() {
	// once: a shortcut the user removes later stays removed
	done := s.state("daijishou-widgets.done")
	if sys.Exists(done) || !sys.Exists(s.djSeeded()) || s.djRunning() {
		return
	}
	defer fsx.Touch(done)
	owner, err := fsx.OwnerOf(s.djData())
	if err != nil {
		return
	}
	live := s.djWidgetDB()
	tmp := s.state("daijishou-widgets.tmp")
	_ = os.RemoveAll(tmp)
	if os.MkdirAll(tmp, 0o700) != nil {
		return
	}
	defer os.RemoveAll(tmp)
	if !sys.Exists(live) {
		s.djWidgetsMake(tmp, owner)
		s.restorecon(true, dirOf(live))
		return
	}
	if copyPlain(live, tmp+"/check.db") != nil {
		return
	}
	if sys.Exists(live + "-wal") {
		_ = copyPlain(live+"-wal", tmp+"/check.db-wal")
	}
	all, err := s.sqlQuery(tmp+"/check.db", "SELECT count(*) FROM WidgetInstanceEntity;")
	if err != nil {
		return
	}
	have, err := s.sqlQuery(tmp+"/check.db", "SELECT a.resolve_info_string_id FROM WidgetInstanceEntity w JOIN AppShortcutWidgetInstanceEntity a ON a.id = w.widget_instance_id WHERE w.widget_id = 'app_shortcut' ORDER BY w.position;")
	if err != nil {
		return
	}
	var got []string
	if have != "" {
		got = strings.Split(have, "\n")
	}
	if all != strconv.Itoa(len(got)) {
		return // other widgets: the user's page
	}
	var want []string
	for _, c := range djWidgets {
		if s.installedVC(pkgOf(c)) != "" {
			want = append(want, c)
		}
	}
	if strings.Join(got, "\n") == strings.Join(want, "\n") {
		return
	}
	// ours only if what is there is our list in our order with some left out
	i := 0
	for _, c := range djWidgets {
		if i < len(got) && got[i] == c {
			i++
		}
	}
	if i != len(got) {
		return
	}
	s.djWidgetsWrite(tmp, owner, true)
	s.restorecon(true, dirOf(live))
}

// djOnTop: Daijishou is the app in front.
func (s *Setup) djOnTop() bool {
	out, _ := s.R.Run("dumpsys", "activity", "activities")
	for _, l := range strings.Split(out, "\n") {
		if strings.Contains(l, "mResumedActivity") && strings.Contains(l, djPkg+"/") {
			return true
		}
	}
	return false
}

func (s *Setup) djRunning() bool {
	_, err := s.R.Run("pidof", djPkg)
	return err == nil
}

func (s *Setup) goHome() {
	_, _ = s.R.Run("am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
}

// SeedDaijishou fills Daijishou's library before its first start, or right after it if
// Daijishou came up before EASYROMS did and its library is still empty. Only once, never
// over a library with a system or game in it, and only for the Daijishou version the
// schema was taken from.
func (s *Setup) SeedDaijishou() {
	schema := s.Base + "/daijishou/schema.sql"
	if !sys.IsFile(s.djSeed()) || !sys.IsFile(schema) {
		return
	}
	if sys.Exists(s.djDone()) && s.djSettled() {
		return
	}
	if s.noroms {
		return
	}
	if s.easyroms == "" {
		s.Log("EASYROMS not mounted, Daijishou's platforms next start")
		return
	}
	data := s.djData()
	if !sys.IsDir(data) {
		return
	}
	switch s.djUserLibrary() {
	case libOwn:
		s.Log("Daijishou has a library already, no platforms added")
		_ = fsx.Touch(s.djDone())
		return
	case libUnknown:
		s.Log("Daijishou's library can't be read, platforms next start")
		return
	}
	// Daijishou started first and made an empty library: replaced below, with Daijishou
	// stopped around it
	late := sys.Exists(s.djDB())
	uuid := baseOf(s.easyroms)
	if !sys.Exists(s.state("daijishou-grants." + uuid)) {
		// post-fs-data writes them on the next start (the card may be new)
		s.Log("no folder access for Daijishou on %s yet, platforms next start", uuid)
		return
	}
	want := ""
	for _, l := range s.djSeedLines() {
		if l.kind == "daijishou" {
			want = strings.TrimRight(strings.Join([]string{l.n, l.arg, l.vals}, "\t"), "\t")
		}
	}
	vc := s.installedVC(djPkg)
	if vc != want {
		v := vc
		if v == "" {
			v = "missing"
		}
		s.Log("Daijishou %s, the platforms are made for %s: none added", v, want)
		_ = fsx.Touch(s.djDone())
		return
	}
	if !late && s.djRunning() {
		s.Log("Daijishou is running, platforms next start")
		return
	}

	tmp := s.state("daijishou.tmp")
	_ = os.RemoveAll(tmp)
	if err := os.MkdirAll(tmp, 0o700); err != nil {
		return
	}
	defer os.RemoveAll(tmp)
	rows, arts := s.djSQL(s.easyroms, uuid)
	db := tmp + "/Daijishou.db"
	if err := os.WriteFile(tmp+"/rows.sql", []byte(rows), 0o600); err != nil {
		s.Log("making Daijishou's library failed: %v", err)
		return
	}
	if err := s.sqlRun(db, schema); err != nil {
		s.Log("making Daijishou's library failed (schema): %v", err)
		return
	}
	if err := s.sqlRun(db, tmp+"/rows.sql"); err != nil {
		s.Log("making Daijishou's library failed (platforms): %v", err)
		return
	}
	countText, _ := s.sqlQuery(db, "SELECT count(*) FROM PlatformEntity;")
	count, _ := strconv.Atoi(countText)
	if count == 0 {
		s.Log("no Daijishou platform to add (no ROM folders or players)")
		_ = fsx.Touch(s.djDone())
		return
	}

	top := false
	if late {
		// it keeps the library in memory while it runs; as the home screen it may come
		// straight back, so it's stopped again once the files are in
		top = s.djOnTop()
		_, _ = s.R.Run("am", "force-stop", djPkg)
		switch s.djUserLibrary() {
		case libOwn:
			s.Log("Daijishou has a library already, no platforms added")
			_ = fsx.Touch(s.djDone())
			if top {
				s.goHome()
			}
			return
		case libUnknown:
			s.Log("Daijishou's library can't be read, platforms next start")
			if top {
				s.goHome()
			}
			return
		}
		for _, x := range []string{"-wal", "-shm", "-journal"} {
			_ = os.Remove(s.djDB() + x)
		}
	} else if sys.Exists(s.djDB()) || s.djRunning() {
		// Daijishou may have started meanwhile
		s.Log("Daijishou started meanwhile, platforms next start")
		return
	}
	owner, err := fsx.OwnerOf(data)
	if err != nil {
		return
	}
	dbs := data + "/databases"
	_ = os.MkdirAll(dbs, 0o771)
	_ = fsx.SetOwnerMode(dbs, owner, 0o771)
	if err := moveInto(db, s.djDB(), owner); err != nil {
		s.Log("making Daijishou's library failed (moving it in): %v", err)
		return
	}
	s.djCopyArt(arts, owner)
	prefs := s.djPrefs()
	prefsDir := dirOf(prefs)
	if !sys.Exists(prefs) {
		_ = os.MkdirAll(prefsDir, 0o771)
		_ = fsx.SetOwnerMode(prefsDir, owner, 0o771)
		_ = fsx.WriteFileAtomic(prefs, []byte(djPrefsText))
		_ = fsx.SetOwnerMode(prefs, owner, 0o660)
	} else if djAskSync(prefs, djHint) {
		// Daijishou's own settings file from its first start
		_ = fsx.SetOwnerMode(prefs, owner, 0o660)
	} else {
		s.Log("Daijishou's settings look odd, it won't ask to look for games")
	}
	s.restorecon(true, dbs, prefsDir)
	if late {
		_, _ = s.R.Run("am", "force-stop", djPkg)
		if top {
			s.goHome()
		}
	}
	_ = fsx.Touch(s.djDone())
	// the first start's sync sees what's there now; later changes: DJNewGames
	_ = fsx.WriteLine(s.djSeeded(), uuid)
	_ = fsx.WriteFileAtomic(s.state("daijishou-games."+uuid), []byte(joinLines(s.djGames())))
	s.Log("added %d platforms to Daijishou's library", count)
}

func joinLines(l []string) string {
	if len(l) == 0 {
		return ""
	}
	return strings.Join(l, "\n") + "\n"
}

var djExtRe = regexp.MustCompile(`\(\?:([^)]*)\)\$`)

// djExts is the file endings each platform folder's players accept (their
// accepted_filename_regex in the seed), "a|b|c" per folder.
func (s *Setup) djExts() map[string]string {
	dirs := map[string]string{}
	ext := map[string]string{}
	for _, l := range s.djSeedLines() {
		switch l.kind {
		case "platform":
			dirs[l.n] = l.arg
		case "player":
			line := strings.Join([]string{l.kind, l.n, l.arg, l.vals}, "\t")
			// the last match wins, like awk's match() on the leftmost-longest... the
			// regex sits at the end of the line, so take the one that ends there
			m := djExtRe.FindAllStringSubmatchIndex(line, -1)
			if len(m) == 0 {
				continue
			}
			e := line[m[len(m)-1][2]:m[len(m)-1][3]]
			if ext[l.n] == "" {
				ext[l.n] = e
			} else {
				ext[l.n] += "|" + e
			}
		}
	}
	out := map[string]string{}
	for n, d := range dirs {
		if ext[n] == "" {
			continue
		}
		for _, f := range strings.Split(d, ",") {
			if prev, ok := out[f]; ok {
				out[f] = prev + "|" + ext[n]
			} else {
				out[f] = ext[n]
			}
		}
	}
	return out
}

// djGames is the game files in the platform folders on EASYROMS, relative to it, sorted:
// only files with an ending the platform's players accept, so saves and states the cores
// write next to the games, scraped pictures and game lists don't count. Hidden files and
// DOSBox Pure's saves (.pure.zip) neither.
func (s *Setup) djGames() []string {
	root := s.easyroms
	var out []string
	exts := s.djExts()
	folders := make([]string, 0, len(exts))
	for f := range exts {
		folders = append(folders, f)
	}
	sort.Strings(folders)
	for _, f := range folders {
		dir := root + "/" + f
		if !sys.IsDir(dir) {
			continue
		}
		re, err := regexp.Compile(`(?i)\.(` + exts[f] + `)$`)
		if err != nil {
			continue
		}
		_ = filepath.WalkDir(dir, func(p string, d os.DirEntry, err error) error {
			if err != nil || !d.Type().IsRegular() {
				return nil
			}
			rel := strings.TrimPrefix(p, root+"/")
			if !re.MatchString(rel) || strings.HasSuffix(strings.ToLower(rel), ".pure.zip") {
				return nil
			}
			if strings.HasPrefix(rel, ".") || strings.Contains(rel, "/.") {
				return nil
			}
			out = append(out, rel)
			return nil
		})
	}
	sort.Strings(out)
	return out
}

// djHintText is the question's text: which systems got new games (most first, at most
// six), and for a big library how long the whole sync takes.
func djHintText(added []string, total int) string {
	count := map[string]int{}
	for _, g := range added {
		sys, _, _ := strings.Cut(g, "/")
		count[sys]++
	}
	names := make([]string, 0, len(count))
	for n := range count {
		names = append(names, n)
	}
	sort.Slice(names, func(i, j int) bool {
		if count[names[i]] != count[names[j]] {
			return count[names[i]] > count[names[j]]
		}
		return names[i] < names[j]
	})
	var b strings.Builder
	for i, n := range names {
		if i == 6 {
			b.WriteString(" and more")
			break
		}
		if i > 0 {
			b.WriteString(", ")
		}
		fmt.Fprintf(&b, "%s (%d)", n, count[n])
	}
	text := "The games on EASYROMS changed. New: " + b.String() + ". Press OK to update the library."
	if total >= djBig {
		mins := (total + 599) / 600
		text += fmt.Sprintf(" This reads every game again: with %d games it takes about %d minutes. To update only the systems above, press Cancel, open each one and use its Sync button.", total, mins)
	}
	return text
}

// DJNewGames: Daijishou only looks for games when asked to. On a console we set up, when
// games were added to EASYROMS since the last start, Daijishou asks again on its next
// start, like on the first one. Games that were only removed don't count (a sync keeps
// their entries anyway). Ports are handled separately by ReconcilePorts. Daijishou
// keeps its settings in memory while it runs, so it's
// restarted around the change. persist.andr36oid.daijishou_autosync=0 turns this off.
func (s *Setup) DJNewGames() {
	if !sys.IsFile(s.djSeeded()) || s.easyroms == "" {
		return
	}
	if s.Props.Get("persist.andr36oid.daijishou_autosync") == "0" {
		return
	}
	seen := s.state("daijishou-games." + baseOf(s.easyroms))
	now := s.djGames()
	nowText := joinLines(now)
	save := func() { _ = fsx.WriteFileAtomic(seen, []byte(nowText)) }
	old, err := os.ReadFile(seen)
	if err != nil || string(old) == nowText {
		// same games, or another card: nothing to ask
		save()
		return
	}
	p := s.djPrefs()
	// Daijishou's library isn't ours any more (data cleared): leave it
	if !sys.IsFile(p) || !sys.IsFile(s.djDB()) {
		save()
		return
	}
	had := map[string]bool{}
	for _, l := range splitLines(string(old)) {
		had[l] = true
	}
	var added []string
	for _, g := range now {
		// Ports are reconciled directly, without asking for a library-wide Sync.
		if !had[g] && !strings.HasPrefix(g, "ports/") {
			added = append(added, g)
		}
	}
	if len(added) == 0 {
		s.Log("only removed games or ports changed on EASYROMS, Daijishou not asked to look again")
		save()
		return
	}
	hint := djHintText(added, len(now))
	top := s.djOnTop()
	_, _ = s.R.Run("am", "force-stop", djPkg)
	owner, _ := fsx.OwnerOf(p)
	if djAskSync(p, hint) {
		_ = fsx.SetOwnerMode(p, owner, 0o660)
		s.restorecon(false, p)
		s.Log("new games on EASYROMS, Daijishou looks for them on its next start")
	} else {
		s.Log("Daijishou's settings look odd, not asking it to look for new games")
	}
	// a Daijishou that started meanwhile has the old settings in memory
	_, _ = s.R.Run("am", "force-stop", djPkg)
	if top {
		s.goHome()
	}
	save()
}
