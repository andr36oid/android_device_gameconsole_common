package setup

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"testing"
)

// The Daijishou steps in a fake console with a real sqlite3 (was tests/seedtest.sh).

const djUUID = "ABCD-1234"

type djConsole struct {
	t       *testing.T
	s       *Setup
	f       *fake
	root    string
	vc      string // Daijishou's versionCode
	apps    string // installed apps besides Daijishou, comma separated (PPSSPP)
	widgets []string
	running bool
}

func firstColumn(t *testing.T, path string) []string {
	t.Helper()
	fh, err := os.Open(path)
	must(t, err)
	defer fh.Close()
	var out []string
	sc := bufio.NewScanner(fh)
	for sc.Scan() {
		f := strings.Fields(sc.Text())
		if len(f) == 0 || strings.HasPrefix(f[0], "#") {
			continue
		}
		out = append(out, f[0])
	}
	return out
}

// newDJ is a fresh console: every core but skipCore, every ROM folder but skipFolder.
func newDJ(t *testing.T, skipCore, skipFolder string) *djConsole {
	if _, err := exec.LookPath("sqlite3"); err != nil {
		t.Skip("no sqlite3")
	}
	f := newFake()
	s, root := newSetup(t, f)
	c := &djConsole{t: t, s: s, f: f, root: root, vc: "416", apps: ppPkg,
		widgets: []string{"com.retroarch", "com.android.documentsui", "org.videolan.vlc", "com.android.settings"}}
	must(t, os.Symlink(emu+"/daijishou", s.Base+"/daijishou"))
	must(t, os.MkdirAll(s.raData()+"/cores", 0o755))
	must(t, os.MkdirAll(s.djData(), 0o755))
	write(t, s.EasyromsPart, "")
	s.EasyromsDev = root + "/dev/block/mmcblk0p7"
	boot := make([]byte, 512)
	copy(boot[3:], "EXFAT   ")
	copy(boot[100:], []byte{0x34, 0x12, 0xcd, 0xab})
	copy(boot[510:], []byte{0x55, 0xaa})
	write(t, s.EasyromsDev, string(boot))
	s.easyroms = s.MediaRW + "/" + djUUID
	must(t, os.MkdirAll(s.easyroms, 0o755))
	for _, core := range firstColumn(t, emu+"/cores.lock") {
		if core != skipCore {
			write(t, s.raData()+"/cores/"+core+"_libretro_android.so", "")
		}
	}
	for _, d := range firstColumn(t, emu+"/seed/rom-folders.txt") {
		if d != skipFolder {
			must(t, os.MkdirAll(s.easyroms+"/"+d, 0o755))
		}
	}
	f.handlers["pm"] = func(a []string) (string, error) {
		if len(a) < 4 || a[0] != "list" {
			return "", nil
		}
		pkg := a[3]
		vc := ""
		switch {
		case pkg == djPkg:
			vc = c.vc
		case strings.Contains(","+c.apps+",", ","+pkg+","):
			vc = "119030000"
		default:
			for _, w := range c.widgets {
				if w == pkg {
					vc = "1"
				}
			}
		}
		if vc == "" {
			return "", nil
		}
		return "package:" + pkg + " versionCode:" + vc, nil
	}
	f.handlers["pidof"] = func([]string) (string, error) {
		if c.running {
			return "123", nil
		}
		return "", errors.New("exit status 1")
	}
	f.handlers["dumpsys"] = func([]string) (string, error) {
		return "    mResumedActivity: ActivityRecord{1 u0 " + djPkg + "/.activities.MainActivity t9}", nil
	}
	f.handlers["timeout"] = func(a []string) (string, error) {
		out, err := exec.Command("timeout", a...).CombinedOutput()
		return strings.TrimRight(string(out), "\n"), err
	}
	return c
}

func (c *djConsole) run() {
	c.s.DaijishouGrants()
	c.s.SeedDaijishou()
}

func (c *djConsole) checklib() error {
	s := c.s
	out, err := exec.Command("python3", emu+"/tests/checklib.py", s.djSeed(), s.Base+"/daijishou/schema.sql",
		s.djDB(), s.djPrefs(), s.URIGrants, s.easyroms, s.raData()+"/cores", c.apps).CombinedOutput()
	if err != nil {
		return fmt.Errorf("checklib: %s", out)
	}
	return nil
}

func (c *djConsole) sql(q string) string {
	out, err := exec.Command("sqlite3", c.s.djDB(), q).CombinedOutput()
	if err != nil {
		c.t.Fatalf("sqlite3 %s: %s", q, out)
	}
	return strings.TrimSpace(string(out))
}

func (c *djConsole) exists(p string) bool { _, err := os.Lstat(p); return err == nil }

func (c *djConsole) sum(paths ...string) string {
	var b strings.Builder
	for _, p := range paths {
		x, _ := os.ReadFile(p)
		b.Write(x)
	}
	return b.String()
}

func (c *djConsole) fail(format string, args ...any) {
	c.t.Helper()
	c.t.Errorf(format+"\nlog:\n  %s", append(args, strings.Join(c.s.Lines, "\n  "))...)
}

func TestDaijishouFresh(t *testing.T) {
	c := newDJ(t, "", "")
	c.run()
	if !c.exists(c.s.djDone()) {
		c.fail("no done mark")
	}
	if err := c.checklib(); err != nil {
		c.fail("%v", err)
	}
	grants := strings.Count(read(t, c.s.URIGrants), "<uri-grant ")
	if grants != len(c.s.djFolders()) {
		c.fail("%d grants for %d folders", grants, len(c.s.djFolders()))
	}
	if c.f.count("timeout -k 2 10 dd") != 1 {
		c.fail("boot sector not read under timeout -k: %v", c.f.calls)
	}
	// running again changes nothing
	before := c.sum(c.s.djDB(), c.s.URIGrants, c.s.djPrefs())
	c.run()
	if c.sum(c.s.djDB(), c.s.URIGrants, c.s.djPrefs()) != before {
		c.fail("second run changed something")
	}
}

func TestDaijishouMissingCoreFolderApp(t *testing.T) {
	c := newDJ(t, "picodrive", "gbc")
	c.apps = ""
	c.run()
	if err := c.checklib(); err != nil {
		c.fail("%v", err)
	}
	for _, id := range strings.Fields(c.sql("SELECT unique_id FROM PlatformEntity")) {
		if id == "sega32x" || id == "gbc" || id == "psp" {
			c.fail("platform %s without player or folder", id)
		}
	}
	if g := c.sql("SELECT player_id_list FROM PlatformEntity WHERE unique_id='genesis'"); strings.Count(g, ",") != 1 {
		c.fail("genesis players %s", g)
	}
}

func TestDaijishouFOSSOnly(t *testing.T) {
	c := newDJ(t, "", "")
	for _, core := range []string{"snes9x2010", "genesis_plus_gx", "picodrive"} {
		must(t, os.Remove(c.s.raData()+"/cores/"+core+"_libretro_android.so"))
	}
	c.run()
	if err := c.checklib(); err != nil {
		c.fail("%v", err)
	}
	if p := c.sql("SELECT p.unique_id FROM PlatformEntity f JOIN PlayerEntity p ON p.id = f.default_player_id WHERE f.unique_id='genesis'"); p != "genesis.andr36oid.clownmdemu" {
		c.fail("genesis plays with %s", p)
	}
}

func TestDaijishouUnreadable(t *testing.T) {
	c := newDJ(t, "", "")
	write(t, c.s.djDB(), "user\n")
	c.run()
	if read(t, c.s.djDB()) != "user\n" || c.exists(c.s.djPrefs()) || c.exists(c.s.djDone()) ||
		c.exists(c.s.djSeeded()) || !logHas(c.s, "can't read Daijishou's library") {
		c.fail("unreadable library touched or done")
	}
}

func TestDaijishouEmptyEarlyLibrary(t *testing.T) {
	c := newDJ(t, "", "")
	c.s.DaijishouGrants()
	db := c.s.djDB()
	must(t, os.MkdirAll(dirOf(db), 0o755))
	out, err := exec.Command("sqlite3", db, ".read "+c.s.Base+"/daijishou/schema.sql").CombinedOutput()
	if err != nil {
		t.Fatalf("%s", out)
	}
	write(t, db+"-wal", "")
	write(t, c.s.djDone(), "")
	write(t, c.s.djPrefs(), "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <boolean name=\"first_launch\" value=\"false\" />\n</map>\n")
	c.running = true
	c.s.SeedDaijishou()
	c.running = false
	if err := c.checklib(); err != nil {
		c.fail("%v", err)
	}
	if !c.exists(c.s.djSeeded()) || c.exists(db+"-wal") ||
		!strings.Contains(read(t, c.s.djPrefs()), `name="first_launch" value="false"`) {
		c.fail("empty library not filled right")
	}
	if c.f.count("am force-stop") != 2 || c.f.count("am start -a android.intent.action.MAIN -c android.intent.category.HOME") != 1 {
		c.fail("Daijishou not stopped twice and home not back: %v", c.f.calls)
	}
	before := c.sum(db, c.s.djPrefs())
	c.s.SeedDaijishou()
	if c.sum(db, c.s.djPrefs()) != before {
		c.fail("filled twice")
	}
}

func TestDaijishouGrantsEasyromsProperties(t *testing.T) {
	c := newDJ(t, "", "")
	s, f := c.s, c.f
	part := dirOf(s.EasyromsPart) + "/mmcblk1p7"
	write(t, part, "")
	f.props["sys.andr36oid.easyroms.ready"] = "1"
	f.props["sys.andr36oid.easyroms.part"] = part
	f.props["sys.andr36oid.easyroms.dev"] = "mmcblk1p7"
	write(t, dirOf(s.EasyromsDev)+"/mmcblk1p7", read(t, s.EasyromsDev))
	must(t, os.Remove(s.EasyromsDev))
	must(t, os.Remove(s.EasyromsPart))
	s.DaijishouGrants()
	if f.count("timeout ") != 1 || !sys_exists(s.URIGrants) {
		t.Fatal("didn't grant access to the selected data card")
	}
	write(t, s.EasyromsPart, "")
	delete(f.props, "sys.andr36oid.easyroms.part")
	delete(f.props, "sys.andr36oid.easyroms.dev")
	f.reset()
	s.DaijishouGrants()
	if f.count("timeout ") != 0 {
		t.Fatal("empty published EASYROMS probed the legacy card")
	}
}

func TestDaijishouGrantsBootSector(t *testing.T) {
	for _, problem := range []string{"short", "filesystem", "signature", "missing", "read error"} {
		t.Run(problem, func(t *testing.T) {
			c := newDJ(t, "", "")
			boot := []byte(read(t, c.s.EasyromsDev))
			switch problem {
			case "short":
				boot = boot[:104]
			case "filesystem":
				copy(boot[3:], "OTHER   ")
			case "signature":
				boot[510] = 0
			case "missing":
				must(t, os.Remove(c.s.EasyromsDev))
			case "read error":
				c.f.handlers["timeout"] = func([]string) (string, error) {
					return string(boot), errors.New("read failed")
				}
			}
			if problem != "missing" {
				write(t, c.s.EasyromsDev, string(boot))
			}
			c.s.DaijishouGrants()
			if c.exists(c.s.URIGrants) {
				t.Fatal("unreadable or invalid boot sector granted folder access")
			}
		})
	}
}

func TestDaijishouEmptyWALLibrary(t *testing.T) {
	c := newDJ(t, "", "")
	c.s.DaijishouGrants()
	wal := c.root + "/wal"
	must(t, os.MkdirAll(wal, 0o755))
	must(t, os.MkdirAll(dirOf(c.s.djDB()), 0o755))
	script := "PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0;\n" + read(t, c.s.Base+"/daijishou/schema.sql") +
		"\n.shell cp \"" + wal + "/Daijishou.db\" \"" + wal + "/Daijishou.db-wal\" \"" + dirOf(c.s.djDB()) + "/\"\n"
	cmd := exec.Command("sqlite3", wal+"/Daijishou.db")
	cmd.Stdin = strings.NewReader(script)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("%s", out)
	}
	write(t, c.s.djDone(), "")
	if st, err := os.Stat(c.s.djDB() + "-wal"); err != nil || st.Size() == 0 {
		t.Fatal("setup: no WAL")
	}
	c.s.SeedDaijishou()
	if err := c.checklib(); err != nil || !c.exists(c.s.djSeeded()) {
		c.fail("empty WAL-mode library not filled: %v", err)
	}
}

func TestDaijishouUserLibrary(t *testing.T) {
	c := newDJ(t, "", "")
	db := c.s.djDB()
	must(t, os.MkdirAll(dirOf(db), 0o755))
	out, err := exec.Command("sqlite3", db, ".read "+c.s.Base+"/daijishou/schema.sql").CombinedOutput()
	if err != nil {
		t.Fatalf("%s", out)
	}
	c.sql("INSERT INTO PlatformEntity (id, default_player_id, player_id_list, items_sync_tree_uri_list, unique_id, name, shortname, description, is_visible, scrape_preview_media, wallpaper_path, thumbnail_path, color_theme, color_on_theme, accept_filename_regex, scraper_source_list, box_art_aspect_ratio_id, use_custom_box_art_aspect_ratio, custom_box_art_aspect_ratio, screen_aspect_ratio_id, use_custom_screen_aspect_ratio, custom_screen_aspect_ratio, retro_achievements_console_id_list, extra) VALUES (1, 0, '[]', '[]', 'mine', 'Mine', 'mine', '', 1, 0, '', '', '', '', '', '[]', 0, 0, '', 0, 0, '', '[]', '');")
	before := read(t, db)
	c.run()
	if read(t, db) != before || c.exists(c.s.URIGrants) || !c.exists(c.s.djDone()) || c.exists(c.s.djSeeded()) {
		c.fail("user's library touched")
	}
}

func TestDaijishouOtherVersion(t *testing.T) {
	c := newDJ(t, "", "")
	c.vc = "417"
	c.run()
	if c.exists(c.s.djDB()) || !c.exists(c.s.djDone()) || !logHas(c.s, "made for 416") {
		c.fail("other version")
	}
}

func TestDaijishouNoroms(t *testing.T) {
	c := newDJ(t, "", "")
	must(t, os.Remove(c.s.EasyromsPart))
	c.s.noroms, c.s.easyroms = true, ""
	c.run()
	if c.exists(c.s.djDB()) || c.exists(c.s.URIGrants) || c.exists(c.s.djDone()) {
		c.fail(".noroms got something")
	}
}

func TestDaijishouWaits(t *testing.T) {
	// running: next start
	c := newDJ(t, "", "")
	c.s.DaijishouGrants()
	c.running = true
	c.s.SeedDaijishou()
	if c.exists(c.s.djDB()) || c.exists(c.s.djDone()) {
		c.fail("running Daijishou: library made")
	}
	c.running = false
	c.s.SeedDaijishou()
	if !c.exists(c.s.djDB()) {
		c.fail("not made on the next start")
	}
	// no grants yet
	c = newDJ(t, "", "")
	c.s.SeedDaijishou()
	if c.exists(c.s.djDB()) || c.exists(c.s.djDone()) {
		c.fail("made without grants")
	}
	// EASYROMS not mounted
	c = newDJ(t, "", "")
	c.s.DaijishouGrants()
	c.s.easyroms = ""
	c.s.SeedDaijishou()
	if c.exists(c.s.djDB()) || c.exists(c.s.djDone()) {
		c.fail("made without EASYROMS")
	}
}

func TestDaijishouSettingsKept(t *testing.T) {
	c := newDJ(t, "", "")
	write(t, c.s.djPrefs(), "mine\n")
	c.run()
	if read(t, c.s.djPrefs()) != "mine\n" || !c.exists(c.s.djDB()) {
		c.fail("existing settings not kept")
	}
}

func TestDaijishouGrantsFile(t *testing.T) {
	other := `<uri-grant sourceUserId="0" targetUserId="0" sourcePkg="com.android.externalstorage" targetPkg="org.example" uri="content://com.android.externalstorage.documents/tree/primary%3AMusic" prefix="true" modeFlags="1" createdTime="1" />`
	head := "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"

	c := newDJ(t, "", "")
	write(t, c.s.URIGrants, head+"<uri-grants>"+other+"</uri-grants>\n")
	c.run()
	if !strings.Contains(read(t, c.s.URIGrants), other) {
		c.fail("other grants lost")
	}
	if err := c.checklib(); err != nil {
		c.fail("merge: %v", err)
	}

	c = newDJ(t, "", "")
	write(t, c.s.URIGrants, head+"<uri-grants />\n")
	c.run()
	if err := c.checklib(); err != nil {
		c.fail("empty grants file: %v", err)
	}

	c = newDJ(t, "", "")
	uri := djTreeURI(djUUID, "nes")
	mine := `<uri-grant sourceUserId="0" targetUserId="0" sourcePkg="com.android.externalstorage" targetPkg="` + djPkg + `" uri="` + uri + `" prefix="true" modeFlags="3" createdTime="5" />`
	write(t, c.s.URIGrants, head+"<uri-grants>"+mine+"</uri-grants>\n")
	c.s.DaijishouGrants()
	g := read(t, c.s.URIGrants)
	if strings.Count(g, `uri="`+uri+`"`) != 1 || !strings.Contains(g, mine) {
		c.fail("existing grant doubled")
	}

	c = newDJ(t, "", "")
	write(t, c.s.URIGrants, "x\n")
	write(t, c.s.URIGrants+".bak", "y\n")
	c.s.DaijishouGrants()
	if read(t, c.s.URIGrants) != "x\n" || c.exists(c.s.state("daijishou-grants."+djUUID)) {
		c.fail(".bak: touched")
	}

	c = newDJ(t, "", "")
	write(t, c.s.EasyromsDev, "damaged boot sector")
	c.s.DaijishouGrants()
	if c.exists(c.s.URIGrants) {
		c.fail("bad UUID: grants written")
	}

	c = newDJ(t, "", "")
	write(t, c.s.URIGrants, "<something/>\n")
	c.s.DaijishouGrants()
	if read(t, c.s.URIGrants) != "<something/>\n" {
		c.fail("odd grants file changed")
	}
}

func TestDaijishouQuoting(t *testing.T) {
	c := newDJ(t, "", "")
	c.run()
	a := c.sql("SELECT am_start_arguments FROM PlayerEntity WHERE unique_id='psx.ra.pcsx_rearmed'")
	lines := strings.Split(a, "\n")
	if len(lines) != 7 || lines[4] != " -e DATADIR /data/user/0/com.retroarch" {
		c.fail("am_start_arguments: %q", a)
	}
	if r := c.sql("SELECT accept_filename_regex FROM PlatformEntity WHERE unique_id='snes'"); r != `^(?!(?:\._|\.).*).*(?<!bml)$` {
		c.fail("regex: %q", r)
	}
}

const androidPrefs = `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="upgraded_version" value="416" />
    <boolean name="sync_entirely_on_start" value="false" />
    <string name="sync_entirely_on_start_hint">Update platforms, synchronize library, backup settings and more in one click depended on your settings.</string>
    <string name="uuid">x</string>
</map>
`

// prefsAsk: the flag is on once, the hint names a change, the other values are kept.
func (c *djConsole) prefsAsk() bool {
	p := read(c.t, c.s.djPrefs())
	return strings.Count(p, `name="sync_entirely_on_start"`) == 1 &&
		strings.Contains(p, `<boolean name="sync_entirely_on_start" value="true" />`) &&
		strings.Count(p, `name="sync_entirely_on_start_hint"`) == 1 && strings.Contains(p, "changed") &&
		strings.Contains(p, `name="uuid"`) && strings.Contains(p, `name="upgraded_version"`) &&
		strings.Count(p, `<string name="theme_id">yellow</string>`) == 1
}

func (c *djConsole) hint() string {
	p := read(c.t, c.s.djPrefs())
	i := strings.Index(p, `<string name="sync_entirely_on_start_hint">`)
	if i < 0 {
		return ""
	}
	rest := p[i+len(`<string name="sync_entirely_on_start_hint">`):]
	return rest[:strings.Index(rest, "</string>")]
}

func touchAll(t *testing.T, root string, names ...string) {
	for _, n := range names {
		write(t, root+"/"+n, "")
	}
}

func TestDaijishouNewGames(t *testing.T) {
	c := newDJ(t, "", "")
	s := c.s
	er := s.easyroms
	c.run()
	write(t, s.djPrefs(), androidPrefs)
	c.f.reset()
	s.DJNewGames()
	if read(t, s.djPrefs()) != androidPrefs || c.f.count("am") != 0 {
		c.fail("no new games: changed")
	}
	touchAll(t, er, "nes/Game (USA).nes", "nes/Other.srm", "gba/x.state1", "psx/.hidden")
	must(t, os.Remove(er+"/nes/Game (USA).nes"))
	s.DJNewGames()
	if read(t, s.djPrefs()) != androidPrefs || c.f.count("am") != 0 {
		c.fail("saves, states, hidden files counted")
	}
	touchAll(t, er, "nes/Game.pure.zip", "nes/Some RPG/Save01.lsd", "nes/prbmsav0.dsg",
		"nes/nvram/game.nv", "nes/hi/game.hi", "nes/Game.PNG", "nes/Game.eep")
	s.DJNewGames()
	if read(t, s.djPrefs()) != androidPrefs || c.f.count("am") != 0 {
		c.fail("core saves and pictures counted")
	}
	if e := s.djExts()["nes"]; !strings.Contains("|"+e+"|", "|nes|") {
		c.fail("nes endings: %s", e)
	}
	touchAll(t, er, "nes/Game (USA).nes")
	s.DJNewGames()
	if !c.prefsAsk() || c.f.count("am force-stop") != 2 || c.f.count("am start") != 1 {
		c.fail("new game: not asked, or Daijishou not restarted: %v", c.f.calls)
	}
	c.f.reset()
	s.DJNewGames()
	if c.f.count("am") != 0 {
		c.fail("asked twice")
	}
	touchAll(t, er, "psx/Game X/Game X.cue")
	c.f.props["persist.andr36oid.daijishou_autosync"] = "0"
	s.DJNewGames()
	if c.f.count("am") != 0 {
		c.fail("autosync off: asked")
	}
	delete(c.f.props, "persist.andr36oid.daijishou_autosync")
	s.DJNewGames()
	if !c.prefsAsk() || c.f.count("am") == 0 {
		c.fail("game in a subfolder not counted")
	}

	// removed only
	c.f.reset()
	write(t, s.djPrefs(), androidPrefs)
	must(t, os.Remove(er+"/nes/Game (USA).nes"))
	s.DJNewGames()
	if read(t, s.djPrefs()) != androidPrefs || c.f.count("am") != 0 || !logHas(s, "only removed") {
		c.fail("removal asked")
	}
	// the systems named
	touchAll(t, er, "gba/a.gba", "gba/b.gba", "snes/c.sfc")
	s.DJNewGames()
	if h := c.hint(); h != "The games on EASYROMS changed. New: gba (2), snes (1). Press OK to update the library." || !c.prefsAsk() {
		c.fail("hint: %s", h)
	}
	// a big library
	c.f.reset()
	write(t, s.djPrefs(), androidPrefs)
	for i := 0; i < 2000; i++ {
		write(t, fmt.Sprintf("%s/arcade/game%d.zip", er, i), "")
	}
	s.DJNewGames()
	n := len(splitLines(read(t, s.state("daijishou-games."+djUUID))))
	want := fmt.Sprintf("The games on EASYROMS changed. New: arcade (2000). Press OK to update the library. This reads every game again: with %d games it takes about 4 minutes. To update only the systems above, press Cancel, open each one and use its Sync button.", n)
	if h := c.hint(); h != want || !c.prefsAsk() || c.f.count("am") == 0 {
		c.fail("big hint: %s", h)
	}
	// more than six systems
	write(t, s.djPrefs(), androidPrefs)
	for _, f := range []string{"nes", "snes", "gb", "gbc", "megadrive", "psx", "n64"} {
		e := "zip"
		if f == "psx" {
			e = "bin"
		}
		if c.exists(er + "/" + f) {
			touchAll(t, er, f+"/new-"+f+"."+e)
		}
	}
	touchAll(t, er, "psx/second.bin")
	s.DJNewGames()
	if h := c.hint(); !strings.Contains(h, "New: psx (2), gb (1), gbc (1), megadrive (1), n64 (1), nes (1) and more. Press OK") {
		c.fail("six systems: %s", h)
	}
}

func TestDaijishouNewGamesNotOurs(t *testing.T) {
	c := newDJ(t, "", "")
	write(t, c.s.djDB(), "user\n")
	write(t, c.s.djPrefs(), androidPrefs)
	c.run()
	c.s.DJNewGames()
	touchAll(t, c.s.easyroms, "nes/new.nes")
	c.s.DJNewGames()
	if c.f.count("am") != 0 || !strings.Contains(read(t, c.s.djPrefs()), `"false"`) {
		c.fail("user's own library touched")
	}

	// a hint over several lines stays, the flag still gets set
	c = newDJ(t, "", "")
	c.run()
	write(t, c.s.djPrefs(), "<?xml version=\"1.0\"?>\n<map>\n    <boolean name=\"sync_entirely_on_start\" value=\"false\" />\n    <string name=\"sync_entirely_on_start_hint\">two\nlines</string>\n</map>\n")
	touchAll(t, c.s.easyroms, "nes/new.nes")
	c.s.DJNewGames()
	p := read(t, c.s.djPrefs())
	if !strings.Contains(p, `<boolean name="sync_entirely_on_start" value="true" />`) || strings.Contains(p, `value="false"`) ||
		!strings.Contains(p, "two\nlines") {
		c.fail("multi-line hint: %s", p)
	}
}

func TestDaijishouArt(t *testing.T) {
	check := func(c *djConsole) {
		t.Helper()
		out, err := exec.Command("python3", "-c", `
import filecmp, os, sqlite3, sys
db, seed, art, data = sys.argv[1:]
want, last = {}, None
for line in open(seed, encoding="utf-8"):
    f = line.rstrip("\n").split("\t")
    if f[0] == "platform":
        last = f[3].split(",")[0].strip().strip("'")
    elif f[0] == "art":
        want[last] = f[2]
rows = sqlite3.connect(db).execute("SELECT id, unique_id, wallpaper_path, thumbnail_path FROM PlatformEntity").fetchall()
if not rows:
    sys.exit("no platforms")
for pid, uid, wp, th in rows:
    for kind, path in (("wallpapers", wp), ("thumbnails", th)):
        expect = "%s/files/platform_%s/%d.png" % (data, kind, pid)
        if path != expect:
            sys.exit("%s: %s path %r, not %r" % (uid, kind, path, expect))
        if not filecmp.cmp(path, os.path.join(art, want[uid]), shallow=False):
            sys.exit("%s: %s is not %s" % (uid, path, want[uid]))
`, c.s.djDB(), c.s.djSeed(), c.s.djArt(), c.s.djData()).CombinedOutput()
		if err != nil {
			c.fail("pictures: %s", out)
		}
	}
	c := newDJ(t, "", "")
	c.run()
	check(c)
	c = newDJ(t, "", "gbc")
	c.run()
	check(c)
	if c.exists(fmt.Sprintf("%s/files/platform_wallpapers/%s.png", c.s.djData(), func() string {
		n := c.sql("SELECT count(*) + 1 FROM PlatformEntity")
		return n
	}())) {
		c.fail("pictures beyond the platforms")
	}
	// a picture missing from the image: only that system plain
	c = newDJ(t, "", "")
	must(t, os.Remove(c.s.Base+"/daijishou"))
	must(t, os.MkdirAll(c.s.Base+"/daijishou/art", 0o755))
	ents, _ := os.ReadDir(emu + "/daijishou")
	for _, e := range ents {
		if e.Name() != "art" {
			must(t, os.Symlink(emu+"/daijishou/"+e.Name(), c.s.Base+"/daijishou/"+e.Name()))
		}
	}
	arts, _ := os.ReadDir(emu + "/daijishou/art")
	for _, e := range arts {
		if e.Name() != "nes.jpg" {
			must(t, os.Symlink(emu+"/daijishou/art/"+e.Name(), c.s.Base+"/daijishou/art/"+e.Name()))
		}
	}
	c.run()
	if c.sql("SELECT wallpaper_path IS NULL FROM PlatformEntity WHERE unique_id = 'nes'") != "1" ||
		c.sql("SELECT count(*) FROM PlatformEntity WHERE wallpaper_path IS NOT NULL") == "0" {
		c.fail("missing picture")
	}
}

func TestDaijishouWidgets(t *testing.T) {
	widgets := func(c *djConsole) string {
		out, _ := exec.Command("sqlite3", c.s.djWidgetDB(), "SELECT w.position || ' ' || a.resolve_info_string_id FROM WidgetInstanceEntity w JOIN AppShortcutWidgetInstanceEntity a ON a.id = w.widget_instance_id WHERE w.widget_id = 'app_shortcut' ORDER BY w.position").CombinedOutput()
		return strings.ReplaceAll(strings.TrimSpace(string(out)), "\n", " ")
	}
	c := newDJ(t, "", "")
	c.run()
	c.s.djWidgetsAtEnd()
	if w := widgets(c); w != "0 com.retroarch/com.retroarch.browser.mainmenu.MainMenuActivity 1 com.android.documentsui/com.android.documentsui.LauncherActivity 2 org.videolan.vlc/org.videolan.vlc.StartActivity 3 com.android.settings/com.android.settings.Settings" {
		c.fail("widgets: %s", w)
	}
	if v, _ := exec.Command("sqlite3", c.s.djWidgetDB(), "PRAGMA user_version").Output(); strings.TrimSpace(string(v)) != "8" {
		c.fail("Widget.db version %s", v)
	}
	c = newDJ(t, "", "")
	c.widgets = []string{"com.retroarch", "com.android.documentsui", "com.android.settings"}
	c.run()
	c.s.djWidgetsAtEnd()
	if w := widgets(c); w != "0 com.retroarch/com.retroarch.browser.mainmenu.MainMenuActivity 1 com.android.documentsui/com.android.documentsui.LauncherActivity 2 com.android.settings/com.android.settings.Settings" {
		c.fail("widgets without VLC: %s", w)
	}
	c = newDJ(t, "", "")
	db := c.s.djWidgetDB()
	must(t, os.MkdirAll(dirOf(db), 0o755))
	if out, err := exec.Command("sqlite3", db, ".read "+c.s.Base+"/daijishou/widget-schema.sql").CombinedOutput(); err != nil {
		t.Fatalf("%s", out)
	}
	if out, err := exec.Command("sqlite3", db, "INSERT INTO WidgetInstanceEntity VALUES (1, 'rss', 1, 1, 0)").CombinedOutput(); err != nil {
		t.Fatalf("%s", out)
	}
	before := read(t, db)
	c.run()
	c.s.djWidgetsAtEnd()
	if read(t, db) != before || c.exists(db+"-shm") {
		c.fail("the user's widgets changed")
	}

	// VLC picked on the app page installs after the library: the home page is made at
	// the end, and a page with only some of our shortcuts gets the late ones added
	c = newDJ(t, "", "")
	c.widgets = []string{"com.retroarch", "com.android.documentsui", "com.android.settings"}
	c.run()
	if c.exists(c.s.djWidgetDB()) {
		c.fail("home page made before the end of the setup")
	}
	c.widgets = append(c.widgets, "org.videolan.vlc")
	c.s.djWidgetsAtEnd()
	if w := widgets(c); w != "0 com.retroarch/com.retroarch.browser.mainmenu.MainMenuActivity 1 com.android.documentsui/com.android.documentsui.LauncherActivity 2 org.videolan.vlc/org.videolan.vlc.StartActivity 3 com.android.settings/com.android.settings.Settings" {
		c.fail("late VLC: %s", w)
	}
	// only once: a shortcut removed later isn't put back on the next start
	must(t, exec.Command("sqlite3", c.s.djWidgetDB(), "DELETE FROM WidgetInstanceEntity WHERE position = 2").Run())
	c.s.djWidgetsAtEnd()
	if w := widgets(c); strings.Contains(w, "vlc") {
		c.fail("removed shortcut came back: %s", w)
	}
}
