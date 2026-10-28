package setup

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"andr36oid/gcsetup/internal/fsx"
)

func libraryGame(t *testing.T, c *djConsole, platform, path string, state djLibraryState) int64 {
	t.Helper()
	fav := 0
	if state.favorite {
		fav = 1
	}
	uri := djTreeURI(djUUID, platform) + "/document/" + djURIEncode(djUUID+":"+path)
	c.sql(fmt.Sprintf(`INSERT INTO PlayableItemEntity (is_singleton_disjoint_set, use_disjoint_set_primary_playable_item, playable_item_type, name, do_scrape_name, scraper_keyword_list, scrape_scraper_keywords, is_favorite, is_searchable, is_visible, attached_platform_id, uri, sync_tree_uri, md5, use_custom_player, play_count, last_played_timestamp, added_date_timestamp, extra)
SELECT 1, 0, 1, 'My game', 0, '[]', 0, %d, 1, 1, id, %s, %s, '', 0, %d, %d, 1, '' FROM PlatformEntity WHERE unique_id = %s;`, fav, djQuote(uri), djQuote(djTreeURI(djUUID, platform)), state.count, state.played, djQuote(platform)))
	return int64(atoiTest(c.sql("SELECT MAX(id) FROM PlayableItemEntity;")))
}

func TestDaijishouLibraryParseAndPreserve(t *testing.T) {
	text := `<?xml version="1.0"?>
<!-- scraped by ES -->
<gameList custom="yes">
  <provider><name>Scraper</name></provider>
  <folder><path>./folder</path><favorite>true</favorite></folder>
  <game id="9"><path>./A%20%2B%20B.nes</path><name>A &amp; B</name>
    <desc><![CDATA[Keep <every> byte & newline
here]]></desc><image>./images/box.png</image><rating>0.9</rating>
    <custom key="x"><nested>text</nested></custom><!-- keep this too -->
    <favorite>false</favorite><playcount>2</playcount><lastplayed>20240131T120000</lastplayed>
  </game>
</gameList>
`
	l, err := djLibraryParse([]byte(text))
	must(t, err)
	if len(l.games) != 1 || l.games[0].field("desc") != "Keep <every> byte & newline\nhere" ||
		l.games[0].state() != (djLibraryState{false, 2, time.Date(2024, 1, 31, 12, 0, 0, 0, time.UTC).UnixMilli()}) {
		t.Fatalf("gamelist parse: %#v", l.games)
	}
	g := l.games[0]
	newer := djLibraryTime("20250201T120000")
	got := string(l.render(map[*djLibraryGame]djLibraryState{g: {true, 8, newer}}))
	want := strings.NewReplacer("<favorite>false</favorite>", "<favorite>true</favorite>", "<playcount>2</playcount>", "<playcount>8</playcount>", "20240131T120000", "20250201T120000").Replace(text)
	if got != want {
		t.Fatalf("unrelated XML changed:\n%s", got)
	}
	for _, bad := range []string{"", "<gameList><game>", "<wrong/>", "<gameList/><gameList/>", "junk<gameList/>"} {
		if _, err := djLibraryParse([]byte(bad)); err == nil {
			t.Fatalf("accepted malformed XML %q", bad)
		}
	}
	for _, text := range []string{"<gameList/>", "<gameList><game><path>./a.nes</path><favorite/></game></gameList>"} {
		l, err := djLibraryParse([]byte(text))
		must(t, err)
		if len(l.games) == 0 {
			l.games = append(l.games, &djLibraryGame{fields: map[string][]djLibraryElement{"path": {{value: "./a.nes"}}}})
		}
		next := l.render(map[*djLibraryGame]djLibraryState{l.games[0]: {true, 1, newer}})
		again, err := djLibraryParse(next)
		must(t, err)
		if len(again.games) != 1 || again.games[0].state() != (djLibraryState{true, 1, newer}) {
			t.Fatalf("self-closing XML: %s", next)
		}
	}
}

func TestDaijishouLibraryMerge(t *testing.T) {
	for _, pair := range [][2]djLibraryState{
		{{}, {}}, {{true, 2, 1000}, {false, 9, 2000}}, {{false, 20, 3000}, {true, 1, 1000}},
	} {
		a, b := pair[0], pair[1]
		got := a.merge(b)
		if got.favorite != (a.favorite || b.favorite) || got.count != max(a.count, b.count) || got.played != max(a.played, b.played) || got != b.merge(a) || got.merge(a) != got || got.merge(b) != got {
			t.Fatalf("non-convergent merge: %#v + %#v = %#v", a, b, got)
		}
	}
	if djLibraryTime("invalid") != 0 || djLibraryTime("20240131T120000") != djLibraryTime("2024-01-31T12:00:00Z") {
		t.Fatal("ES timestamp conversion")
	}
}

func TestDaijishouLibraryFilenameJoin(t *testing.T) {
	for path, want := range map[string]string{
		"./sub/A%20%2B%20B%23%25.nes": "A + B#%.nes",
		"content://com.android.externalstorage.documents/tree/ABCD-1234%3Anes/document/ABCD-1234%3Anes%2FA%20%2B%20B%23%25.nes": "A + B#%.nes",
		"file:///storage/ABCD-1234/nes/A%20%2B%20B%23%25.nes":                                                                   "A + B#%.nes",
		"./日本語+game.nes": "日本語+game.nes", "bad%zz.nes": "", "": "",
	} {
		if got := djLibraryName(path); got != want {
			t.Fatalf("basename %q: %q, want %q", path, got, want)
		}
	}
	c := newDJ(t, "", "")
	c.run()
	folders := c.s.djLibraryFolders()
	if folders["genesis"] != "genesis" || folders["megadrive"] != "genesis" || folders["msx2"] != folders["msx"] || folders["doom"] != "doom" {
		c.fail("generated platform mapping: %#v", folders)
	}
}

func TestDaijishouSyncLibrary(t *testing.T) {
	for _, value := range []string{"", "1"} {
		t.Run(orUnset(value), func(t *testing.T) {
			c := newDJ(t, "", "")
			c.run()
			s := c.s
			for _, name := range []string{"favorite", "playcount", "lastplayed", "desc", "art"} {
				c.f.props["persist.andr36oid.sync."+name] = value
			}
			newer := djLibraryTime("20250201T120000") + 123
			id := libraryGame(t, c, "nes", "nes/A + # (日本語).nes", djLibraryState{false, 8, newer})
			other := libraryGame(t, c, "gba", "gba/A + # (日本語).nes", djLibraryState{false, 1, 1000})
			c.sql(fmt.Sprintf("UPDATE PlayableItemEntity SET name = 'User title', description = 'User description' WHERE id = %d;", id))
			list := s.easyroms + "/nes/gamelist.xml"
			text := `<gameList><game id="custom"><path>./A%20%2B%20%23%20(%E6%97%A5%E6%9C%AC%E8%AA%9E).nes</path><name>ES title</name><favorite>true</favorite><playcount>3</playcount><lastplayed>20240131T120000</lastplayed><desc>ES &amp; metadata</desc><image>./images/box.png</image><video>./video.mp4</video></game><game><path>./Deleted.nes</path><favorite>true</favorite></game></gameList>`
			write(t, list, text)
			must(t, os.Chmod(list, 0o664))
			write(t, s.easyroms+"/nes/images/box.png", "ES artwork")
			platforms := c.sql("SELECT * FROM PlatformEntity ORDER BY id;")
			players := c.sql("SELECT * FROM PlayerEntity ORDER BY id;")
			untouched := c.sql(fmt.Sprintf("SELECT * FROM PlayableItemEntity WHERE id = %d;", other))
			c.running = true
			c.f.reset()
			s.SyncLibrary()
			if got := c.sql(fmt.Sprintf("SELECT is_favorite, play_count, last_played_timestamp, extra, name, description FROM PlayableItemEntity WHERE id = %d;", id)); got != fmt.Sprintf("1|8|%d|ES & metadata|User title|User description", newer) {
				c.fail("merged DB row: %q", got)
			}
			art := c.sql(fmt.Sprintf("SELECT preview_media_path FROM PlayableItemEntity WHERE id = %d;", id))
			if !strings.HasSuffix(art, fmt.Sprintf("/preview_media/%d/", id)) || read(t, art+"boxart.png") != "ES artwork" {
				c.fail("art import: %q", art)
			}
			var index map[string]string
			must(t, json.Unmarshal([]byte(read(t, art+"index.json")), &index))
			if index["boxArtPath"] != "boxart.png" {
				c.fail("preview index: %#v", index)
			}
			want := strings.NewReplacer("<playcount>3</playcount>", "<playcount>8</playcount>", "20240131T120000", "20250201T120000").Replace(text)
			if read(t, list) != want {
				c.fail("ES writeback lost metadata: %s", read(t, list))
			}
			mode, err := fsx.ModeOf(list)
			must(t, err)
			if mode != 0o664 {
				c.fail("gamelist mode: %v", mode)
			}
			if c.f.count("am force-stop") != 2 || c.f.count("am start") != 1 {
				c.fail("foreground refresh: %v", c.f.calls)
			}
			if c.sql("SELECT count(*) FROM PlayableItemEntity;") != "2" || c.sql("SELECT * FROM PlatformEntity ORDER BY id;") != platforms || c.sql("SELECT * FROM PlayerEntity ORDER BY id;") != players || c.sql(fmt.Sprintf("SELECT * FROM PlayableItemEntity WHERE id = %d;", other)) != untouched {
				c.fail("sync inserted games or changed other platforms/items")
			}
			before := c.sum(s.djDB(), list, art+"index.json", art+"boxart.png")
			c.f.reset()
			s.SyncLibrary()
			if c.sum(s.djDB(), list, art+"index.json", art+"boxart.png") != before || c.f.count("am") != 0 {
				c.fail("second sync was not a no-op")
			}
			// ES can increase playcount later, but existing Daijishou art/metadata stay.
			write(t, s.easyroms+"/nes/images/box.png", "replacement artwork")
			write(t, list, strings.Replace(want, "<playcount>8</playcount>", "<playcount>12</playcount>", 1))
			c.sql(fmt.Sprintf("UPDATE PlayableItemEntity SET extra = 'My custom metadata' WHERE id = %d;", id))
			s.SyncLibrary()
			if got := c.sql(fmt.Sprintf("SELECT play_count, extra FROM PlayableItemEntity WHERE id = %d;", id)); got != "12|My custom metadata" || read(t, art+"boxart.png") != "ES artwork" {
				c.fail("existing metadata/art clobbered: %q", got)
			}
		})
	}
}

func TestDaijishouLibraryExportAndCards(t *testing.T) {
	c := newDJ(t, "", "")
	c.run()
	s := c.s
	id := libraryGame(t, c, "genesis", "megadrive/sub/Game.bin", djLibraryState{true, 5, djLibraryTime("20250301T010203")})
	second := s.MediaRW + "/EEEE-0000"
	third := s.MediaRW + "/FFFF-0000"
	write(t, second+"/megadrive/sub/Game.bin", "rom")
	write(t, third+"/nes/Other.nes", "rom")
	c.sql(fmt.Sprintf("UPDATE PlayableItemEntity SET uri = %s WHERE id = %d;", djQuote("file:///storage/EEEE-0000/megadrive/sub/Game.bin"), id))
	libraryGame(t, c, "nes", "nes/Other.nes", djLibraryState{true, 2, 0})
	c.sql("UPDATE PlayableItemEntity SET uri = 'file:///storage/FFFF-0000/nes/Other.nes' WHERE attached_platform_id = (SELECT id FROM PlatformEntity WHERE unique_id = 'nes');")
	libraryGame(t, c, "gba", "gba/NotOnCard.gba", djLibraryState{true, 9, 0})
	before := read(t, s.djDB())
	c.f.reset()
	s.SyncLibrary()
	for _, path := range []string{second + "/megadrive/gamelist.xml", third + "/nes/gamelist.xml"} {
		l, err := djLibraryParse([]byte(read(t, path)))
		must(t, err)
		if len(l.games) != 1 || !l.games[0].state().favorite {
			c.fail("missing ES entry for existing ROM: %s", path)
		}
	}
	if c.exists(s.easyroms+"/gba/gamelist.xml") || c.exists(s.easyroms+"/genesis/gamelist.xml") {
		c.fail("invented an ES game without the ROM/on the wrong card")
	}
	if read(t, s.djDB()) != before || c.f.count("am") != 0 {
		c.fail("ES-only export rewrote/restarted Daijishou")
	}
	for path, want := range map[string]string{"/roms2/megadrive/sub/Game.bin": second + "/megadrive/sub/Game.bin", "/roms3/nes/Other.nes": third + "/nes/Other.nes"} {
		if got := djLibraryPath(path, "", s.djLibraryRoots()); got != want {
			c.fail("card path %q: %q", path, got)
		}
	}
}

func TestDaijishouLibraryRespectsUserState(t *testing.T) {
	for _, state := range []string{"disabled", "missing DB", "empty library", "deleted platform", "deleted item", "bad XML", "existing preview", "existing art directory", "ES running"} {
		t.Run(state, func(t *testing.T) {
			c := newDJ(t, "", "")
			c.run()
			s := c.s
			id := libraryGame(t, c, "nes", "nes/Game.nes", djLibraryState{})
			list := s.easyroms + "/nes/gamelist.xml"
			write(t, list, `<gameList><game><path>./Game.nes</path><favorite>true</favorite><desc>ES description</desc><image>./box.png</image></game></gameList>`)
			write(t, s.easyroms+"/nes/box.png", "art")
			art := fmt.Sprintf("%s/files/preview_media/%d/", s.djData(), id)
			switch state {
			case "disabled":
				c.f.props["persist.andr36oid.subsystem_autosync"] = "0"
			case "missing DB":
				must(t, os.Remove(s.djDB()))
			case "empty library":
				c.sql("DELETE FROM PlayableItemEntity; DELETE FROM PlatformEntity;")
			case "deleted platform":
				c.sql("DELETE FROM PlatformEntity WHERE unique_id = 'nes';")
			case "deleted item":
				c.sql("DELETE FROM PlayableItemEntity;")
			case "bad XML":
				write(t, list, "<gameList><game>")
			case "existing preview":
				c.sql(fmt.Sprintf("UPDATE PlayableItemEntity SET preview_media_path = '/custom/art/', extra = 'mine' WHERE id = %d;", id))
			case "existing art directory":
				write(t, art+"index.json", `{"boxArtPath":"mine.png"}`)
				write(t, art+"mine.png", "mine")
			case "ES running":
				c.f.props["sys.andr36oid.subsystem.session"] = "running"
				c.f.handlers["pidof"] = func([]string) (string, error) { return "42", nil }
			}
			before := c.sum(s.djDB(), list, art+"index.json", art+"mine.png")
			c.f.reset()
			s.SyncLibrary()
			if state == "existing preview" || state == "existing art directory" {
				if c.sql("SELECT is_favorite FROM PlayableItemEntity;") != "1" {
					c.fail("existing art prevented state sync")
				}
				if state == "existing preview" && c.sql("SELECT preview_media_path, extra FROM PlayableItemEntity;") != "/custom/art/|mine" {
					c.fail("user metadata overwritten")
				}
				if state == "existing art directory" && (read(t, art+"index.json") != `{"boxArtPath":"mine.png"}` || read(t, art+"mine.png") != "mine") {
					c.fail("unindexed existing art overwritten")
				}
				if c.exists(art + "boxart.png") {
					c.fail("imported over existing art")
				}
			} else if c.sum(s.djDB(), list, art+"index.json", art+"mine.png") != before || c.f.count("am") != 0 {
				c.fail("user state %s changed", state)
			}
		})
	}
}

func TestDaijishouLibraryLiveRecheck(t *testing.T) {
	for _, change := range []string{"delete platform", "delete item", "replace URI", "change folders", "newer state", "rollback"} {
		t.Run(change, func(t *testing.T) {
			c := newDJ(t, "", "")
			c.run()
			s := c.s
			libraryGame(t, c, "nes", "nes/A.nes", djLibraryState{})
			libraryGame(t, c, "nes", "nes/B.nes", djLibraryState{})
			list := s.easyroms + "/nes/gamelist.xml"
			text := `<gameList><game><path>./A.nes</path><favorite>true</favorite><playcount>4</playcount><image>./box.png</image></game><game><path>./B.nes</path><favorite>true</favorite></game></gameList>`
			write(t, list, text)
			write(t, s.easyroms+"/nes/box.png", "art")
			stopped := false
			c.f.handlers["am"] = func(a []string) (string, error) {
				if a[0] != "force-stop" || stopped {
					return "", nil
				}
				stopped = true
				switch change {
				case "delete platform":
					c.sql("DELETE FROM PlatformEntity WHERE unique_id = 'nes';")
				case "delete item":
					c.sql("DELETE FROM PlayableItemEntity;")
				case "replace URI":
					c.sql("UPDATE PlayableItemEntity SET uri = 'file:///other.nes';")
				case "change folders":
					c.sql("UPDATE PlatformEntity SET items_sync_tree_uri_list = '[]' WHERE unique_id = 'nes';")
				case "newer state":
					c.sql("UPDATE PlayableItemEntity SET play_count = 99, last_played_timestamp = 1740787200000, extra = 'Live edit', preview_media_path = '/live/art/';")
				case "rollback":
					c.sql("CREATE TRIGGER reject_library BEFORE UPDATE ON PlayableItemEntity WHEN OLD.id = 2 BEGIN SELECT RAISE(ABORT, 'test failure'); END;")
				}
				return "", nil
			}
			s.SyncLibrary()
			if change == "newer state" {
				if c.sql("SELECT count(*) FROM PlayableItemEntity WHERE is_favorite = 1 AND play_count = 99 AND extra = 'Live edit' AND preview_media_path = '/live/art/';") != "2" || !strings.Contains(read(t, list), "<playcount>99</playcount>") {
					c.fail("live metadata lost or ES didn't receive latest values")
				}
			} else {
				if c.sql("SELECT count(*) FROM PlayableItemEntity WHERE is_favorite != 0 OR play_count != 0;") != "0" || read(t, list) != text {
					c.fail("live recheck failed: %s", change)
				}
				if change == "delete platform" && c.sql("SELECT count(*) FROM PlatformEntity WHERE unique_id = 'nes';") != "0" {
					c.fail("recreated deleted platform")
				}
				if change == "delete item" && c.sql("SELECT count(*) FROM PlayableItemEntity;") != "0" {
					c.fail("recreated deleted items")
				}
				if change == "rollback" && !logHas(s, "test failure") {
					c.fail("rollback wasn't exercised")
				}
			}
			if c.exists(s.djData() + "/files/preview_media/1") {
				c.fail("unused art left after live recheck/rollback")
			}
		})
	}
}

func TestDaijishouLibraryWAL(t *testing.T) {
	c := newDJ(t, "", "")
	c.run()
	s := c.s
	libraryGame(t, c, "nes", "nes/Game.nes", djLibraryState{})
	wal := c.root + "/wal"
	must(t, os.MkdirAll(wal, 0o755))
	script := "PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0;\n" + c.sql(".dump") + "\n.shell cp \"" + wal + "/Daijishou.db\" \"" + wal + "/Daijishou.db-wal\" \"" + dirOf(s.djDB()) + "/\"\n"
	cmd := exec.Command("sqlite3", wal+"/Daijishou.db")
	cmd.Stdin = strings.NewReader(script)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("%s", out)
	}
	write(t, s.easyroms+"/nes/gamelist.xml", `<gameList><game><path>./Game.nes</path><favorite>true</favorite><playcount>7</playcount></game></gameList>`)
	s.SyncLibrary()
	if c.sql("SELECT is_favorite, play_count FROM PlayableItemEntity;") != "1|7" || c.sql("PRAGMA integrity_check;") != "ok" {
		c.fail("WAL state missing/corrupted")
	}
}

func TestDaijishouLibraryEveryBoot(t *testing.T) {
	for _, late := range []bool{false, true} {
		t.Run(fmt.Sprint(late), func(t *testing.T) {
			c := newDJ(t, "", "")
			c.run()
			s := c.s
			libraryGame(t, c, "nes", "nes/Game.nes", djLibraryState{})
			write(t, s.Mounts, "/dev/block/vold/public:179,7 "+s.easyroms+" exfat rw 0 0\n")
			write(t, s.easyroms+"/nes/gamelist.xml", `<gameList><game><path>./Game.nes</path><favorite>true</favorite></game></gameList>`)
			if late {
				s.RunEasyromsLate()
			} else {
				s.Main()
			}
			if c.sql("SELECT is_favorite FROM PlayableItemEntity WHERE uri LIKE '%Game.nes';") != "1" {
				c.fail("boot didn't sync library")
			}
		})
	}
}

func TestDaijishouLibraryFileSafety(t *testing.T) {
	c := newDJ(t, "", "")
	s := c.s
	write(t, s.easyroms+"/nes/Game.nes", "rom")
	outside := c.root + "/secret.png"
	write(t, outside, "secret")
	must(t, os.Symlink(outside, s.easyroms+"/nes/link.png"))
	for _, path := range []string{"../../../../secret.png", "./link.png", outside, "https://example.com/art.png"} {
		if got := djLibraryPath(path, s.easyroms+"/nes", s.djLibraryRoots()); got != "" {
			c.fail("outside-card path accepted: %q -> %q", path, got)
		}
	}
	list := s.easyroms + "/nes/gamelist.xml"
	text := `<gameList><game><path>./Game.nes</path></game></gameList>`
	write(t, list, text)
	l, err := djLibraryParse([]byte(text))
	must(t, err)
	l.path = list
	write(t, list, "<gameList/>")
	if err := l.write(map[*djLibraryGame]djLibraryState{l.games[0]: {true, 1, 0}}); err == nil || read(t, list) != "<gameList/>" {
		c.fail("concurrent gamelist edit clobbered")
	}
	// At finish_session's trigger the session property can still say running,
	// while ES has already exited. The process check must allow the sync.
	c.run()
	libraryGame(t, c, "nes", "nes/Game.nes", djLibraryState{true, 1, 0})
	c.f.props["sys.andr36oid.subsystem.session"] = "running"
	c.f.handlers["pidof"] = func([]string) (string, error) { return "", errors.New("not running") }
	s.SyncLibrary()
	if !strings.Contains(read(t, list), "<favorite>true</favorite>") {
		c.fail("ended ES session skipped")
	}
	if entries, err := filepath.Glob(s.easyroms + "/nes/.gamelist.*"); err != nil || len(entries) != 0 {
		c.fail("atomic write left temporary files")
	}
}

func TestDaijishouLibraryArtRecovery(t *testing.T) {
	c := newDJ(t, "", "")
	c.run()
	s := c.s
	id := libraryGame(t, c, "nes", "nes/Game.nes", djLibraryState{})
	write(t, s.easyroms+"/nes/gamelist.xml", `<gameList><game><path>./Game.nes</path><image>./box.png</image></game></gameList>`)
	write(t, s.easyroms+"/nes/box.png", "art")
	items, err := s.djLibraryItems(s.djDB())
	must(t, err)
	items, err = s.djLibraryMatch(items, s.djLibraryRoots(), s.librarySyncFields())
	must(t, err)
	if len(items) != 1 || items[0].art == "" {
		c.fail("no art planned")
	}
	owner, err := fsx.OwnerOf(s.djData())
	must(t, err)
	// Simulate losing power between the atomic directory publish and DB COMMIT.
	must(t, s.djLibraryArt(items[0], owner))
	art := items[0].art
	before := c.sum(art+"index.json", art+"boxart.png", art+".subsystem-uri")
	s.SyncLibrary()
	if c.sql(fmt.Sprintf("SELECT preview_media_path FROM PlayableItemEntity WHERE id = %d;", id)) != art || c.sum(art+"index.json", art+"boxart.png", art+".subsystem-uri") != before {
		c.fail("interrupted import didn't converge without rewriting art")
	}
}

func TestDaijishouLibraryBasenameCollision(t *testing.T) {
	c := newDJ(t, "", "")
	c.run()
	s := c.s
	libraryGame(t, c, "nes", "nes/sub1/Game.nes", djLibraryState{true, 3, 0})
	libraryGame(t, c, "nes", "nes/sub2/Game.nes", djLibraryState{false, 8, 0})
	write(t, s.easyroms+"/nes/sub1/Game.nes", "first ROM")
	write(t, s.easyroms+"/nes/sub2/Game.nes", "second ROM")
	list := s.easyroms + "/nes/gamelist.xml"
	write(t, list, `<gameList><game><path>./sub1/Game.nes</path></game><game><path>./sub2/Game.nes</path></game></gameList>`)
	s.SyncLibrary()
	l, err := djLibraryParse([]byte(read(t, list)))
	must(t, err)
	if l.games[0].state() != (djLibraryState{true, 3, 0}) || l.games[1].state() != (djLibraryState{false, 8, 0}) {
		c.fail("same basename in different directories mixed state")
	}
}

func TestDaijishouLibraryFieldGates(t *testing.T) {
	for _, field := range []string{"favorite", "playcount", "lastplayed", "desc", "art", "all"} {
		t.Run(field, func(t *testing.T) {
			c := newDJ(t, "", "")
			c.run()
			s := c.s
			newer := djLibraryTime("20250201T120000")
			importID := libraryGame(t, c, "nes", "nes/Import.nes", djLibraryState{})
			libraryGame(t, c, "nes", "nes/Export.nes", djLibraryState{true, 8, newer})
			list := s.easyroms + "/nes/gamelist.xml"
			text := `<gameList><game><path>./Import.nes</path><favorite>true</favorite><playcount>8</playcount><lastplayed>20250201T120000</lastplayed><desc>ES description</desc><image>./box.png</image></game><game><path>./Export.nes</path><favorite>false</favorite><playcount>0</playcount><lastplayed>invalid</lastplayed><desc>Keep this</desc><image>./user.png</image></game></gameList>`
			write(t, list, text)
			write(t, s.easyroms+"/nes/box.png", "art")
			for name, column := range map[string]string{"favorite": "is_favorite", "playcount": "play_count", "lastplayed": "last_played_timestamp", "desc": "extra", "art": "preview_media_path"} {
				if field == name || field == "all" {
					c.f.props["persist.andr36oid.sync."+name] = "0"
					c.sql(fmt.Sprintf("CREATE TRIGGER gate_%s BEFORE UPDATE OF %s ON PlayableItemEntity BEGIN SELECT RAISE(ABORT, 'disabled field written'); END;", name, column))
				}
			}
			before := c.sum(s.djDB(), list)
			c.f.reset()
			s.SyncLibrary()
			if logHas(s, "disabled field written") {
				c.fail("SQL included a disabled field")
			}
			l, err := djLibraryParse([]byte(read(t, list)))
			must(t, err)
			want := djLibraryState{field != "favorite", 8, newer}
			if field == "playcount" {
				want.count = 0
			}
			if field == "lastplayed" {
				want.played = 0
			}
			if field == "all" {
				want = djLibraryState{}
				if c.sum(s.djDB(), list) != before || c.f.count("am") != 0 {
					c.fail("all fields disabled changed the library")
				}
			}
			fav := 0
			if want.favorite {
				fav = 1
			}
			if got := c.sql(fmt.Sprintf("SELECT is_favorite, play_count, last_played_timestamp FROM PlayableItemEntity WHERE id = %d;", importID)); got != fmt.Sprintf("%d|%d|%d", fav, want.count, want.played) {
				c.fail("gated import: %q, want %#v", got, want)
			}
			if l.games[1].state() != want {
				c.fail("gated export: %#v, want %#v", l.games[1].state(), want)
			}
			for name, value := range map[string]string{"favorite": "false", "playcount": "0", "lastplayed": "invalid", "desc": "Keep this", "image": "./user.png"} {
				if name == field || field == "all" || name == "desc" || name == "image" {
					if l.games[1].field(name) != value {
						c.fail("disabled/export-only field %s changed", name)
					}
				}
			}
			extra := c.sql(fmt.Sprintf("SELECT extra FROM PlayableItemEntity WHERE id = %d;", importID))
			if (extra == "ES description") != (field != "desc" && field != "all") {
				c.fail("description gate: %q", extra)
			}
			art := fmt.Sprintf("%s/files/preview_media/%d/", s.djData(), importID)
			if c.exists(art+"boxart.png") != (field != "art" && field != "all") {
				c.fail("art gate did not prevent/publish the import")
			}
		})
	}
}

func TestDaijishouLibraryBootDisabled(t *testing.T) {
	for _, late := range []bool{false, true} {
		t.Run(fmt.Sprint(late), func(t *testing.T) {
			c := newDJ(t, "", "")
			c.run()
			s := c.s
			libraryGame(t, c, "nes", "nes/Game.nes", djLibraryState{})
			write(t, s.Mounts, "/dev/block/vold/public:179,7 "+s.easyroms+" exfat rw 0 0\n")
			write(t, s.easyroms+"/nes/gamelist.xml", `<gameList><game><path>./Game.nes</path><favorite>true</favorite></game></gameList>`)
			c.f.props["persist.andr36oid.sync.at_boot"] = "0"
			if late {
				s.RunEasyromsLate()
			} else {
				s.Main()
			}
			if c.sql("SELECT is_favorite FROM PlayableItemEntity WHERE uri LIKE '%Game.nes';") != "0" {
				c.fail("disabled boot sync still ran")
			}
		})
	}
}
