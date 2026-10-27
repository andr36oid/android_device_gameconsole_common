package setup

import (
	"crypto/md5"
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func newDJPorts(t *testing.T) *djConsole {
	t.Helper()
	c := newDJ(t, "", "")
	c.apps += ",org.andr36oid.subsystem"
	c.run()
	return c
}

func TestDaijishouReconcilePorts(t *testing.T) {
	c := newDJPorts(t)
	s := c.s
	write(t, s.djPrefs(), androidPrefs)
	write(t, s.easyroms+"/ports/Old.sh", "old port\n")
	s.ReconcilePorts()
	if got := c.sql("SELECT name FROM PlayableItemEntity ORDER BY name;"); got != "Old\nPortMaster" {
		c.fail("initial Ports games: %q", got)
	}
	if got := c.sql("SELECT uri FROM PlayableItemEntity WHERE name = 'PortMaster';"); got != s.djPortMasterURI() {
		c.fail("PortMaster URI: %q", got)
	}
	uriPM, err := url.Parse(s.djPortMasterURI())
	must(t, err)
	if filepath.Base(uriPM.Path) != "PM" || !c.exists(uriPM.Path) {
		c.fail("PortMaster must supply an existing path with basename PM: %q", uriPM.Path)
	}
	if got := c.sql("SELECT p.unique_id FROM PlayerEntity p JOIN PlatformEntity f ON p.id = f.default_player_id WHERE f.unique_id = 'ports';"); got != "ports.andr36oid.subsystem" {
		c.fail("Ports player: %q", got)
	}
	// A scanned game with user edits/history must survive byte for byte.
	c.sql("UPDATE PlayableItemEntity SET name = 'My port', description = 'Mine', is_favorite = 1, play_count = 7, last_played_timestamp = 42, extra = 'custom' WHERE name = 'Old';")
	old := c.sql("SELECT * FROM PlayableItemEntity WHERE name = 'My port';")
	// A user platform, and a game with the very same URI as a Ports game.
	c.sql("INSERT INTO PlatformEntity (unique_id, name, shortname, is_visible, scrape_preview_media, accept_filename_regex, default_player_id, player_id_list, scraper_source_list, box_art_aspect_ratio_id, use_custom_box_art_aspect_ratio, screen_aspect_ratio_id, use_custom_screen_aspect_ratio, items_sync_tree_uri_list, retro_achievements_console_id_list, extra) VALUES ('mine', 'Mine', 'mine', 1, 0, '', 0, '[]', '[]', 0, 0, 0, 0, '[]', '[]', '');")
	c.sql("INSERT INTO PlayableItemEntity (is_singleton_disjoint_set, use_disjoint_set_primary_playable_item, playable_item_type, name, do_scrape_name, scraper_keyword_list, scrape_scraper_keywords, is_favorite, is_searchable, is_visible, attached_platform_id, uri, sync_tree_uri, md5, use_custom_player, play_count, added_date_timestamp, extra) SELECT 1, 0, 1, 'User game', 0, '[]', 0, 1, 1, 1, p.id, g.uri, g.sync_tree_uri, g.md5, 0, 3, 1, '' FROM PlayableItemEntity g CROSS JOIN PlatformEntity p WHERE g.name = 'My port' AND p.unique_id = 'mine';")
	platforms := c.sql("SELECT * FROM PlatformEntity ORDER BY id;")
	players := c.sql("SELECT * FROM PlayerEntity ORDER BY id;")
	user := c.sql("SELECT * FROM PlayableItemEntity WHERE name = 'User game';")

	name := "New 'port' + #1 (日本語)"
	body := "new port\n"
	write(t, s.easyroms+"/ports/"+name+".SH", body)
	touchAll(t, s.easyroms, "ports/.hidden.sh", "ports/._resource.sh", "ports/.cache/hidden.sh", "ports/readme.txt", "ports/mixed.Sh")
	must(t, os.MkdirAll(s.easyroms+"/ports/Directory.sh", 0o755))
	s.Now = func() time.Time { return time.UnixMilli(123456789) }
	c.running = true
	c.f.reset()
	s.ReconcilePorts()
	if got := c.sql("SELECT count(*) FROM PlayableItemEntity WHERE attached_platform_id = (SELECT id FROM PlatformEntity WHERE unique_id = 'ports');"); got != "3" {
		c.fail("addition/filtering left %s Ports entries", got)
	}
	// Check the complete file-row defaults against Daijishou's schema. The SAF
	// document URI, name/keywords and LazyMd5 are what its own Sync would create.
	tree := djTreeURI(djUUID, "ports")
	uri := tree + "/document/ABCD-1234%3Aports%2FNew%20'port'%20%2B%20%231%20(%E6%97%A5%E6%9C%AC%E8%AA%9E).SH"
	keywords, err := json.Marshal([]string{name})
	must(t, err)
	expect := fmt.Sprintf("1|0|1|%s|1|1|NULL|%s|1|0|1|1|NULL|%s|%s|%x|NULL|0|NULL|0|NULL|123456789|", name, keywords, uri, tree, md5.Sum([]byte(body)))
	got := c.sql("SELECT is_singleton_disjoint_set, use_disjoint_set_primary_playable_item, disjoint_set_primary_playable_item_id = id, name, playable_item_type, do_scrape_name, quote(description), scraper_keyword_list, scrape_scraper_keywords, is_favorite, is_searchable, is_visible, quote(preview_media_path), uri, sync_tree_uri, md5, quote(activity_component_string), use_custom_player, quote(custom_player_id), play_count, quote(last_played_timestamp), added_date_timestamp, extra FROM PlayableItemEntity WHERE name = " + djQuote(name) + ";")
	if got != expect {
		c.fail("new game row:\n got %q\nwant %q", got, expect)
	}
	if c.sql("SELECT * FROM PlayableItemEntity WHERE name = 'My port';") != old {
		c.fail("existing port's metadata/history changed")
	}
	if c.f.count("am force-stop") != 2 || c.f.count("am start") != 1 {
		c.fail("changed library not refreshed on screen: %v", c.f.calls)
	}
	if read(t, s.djPrefs()) != androidPrefs {
		c.fail("reconcile changed the user's preferences")
	}

	// The second reconcile is a no-op, including on-disk bytes and restarts.
	before := read(t, s.djDB())
	c.f.reset()
	s.ReconcilePorts()
	if read(t, s.djDB()) != before || c.f.count("am") != 0 {
		c.fail("second reconcile changed the database or restarted home")
	}
	// A removed launch script disappears; a removed manager comes back.
	must(t, os.Remove(s.easyroms+"/ports/Old.sh"))
	c.sql("DELETE FROM PlayableItemEntity WHERE uri = " + djQuote(s.djPortMasterURI()) + ";")
	must(t, os.Remove(s.djPortMasterPath()))
	s.ReconcilePorts()
	if c.sql("SELECT count(*) FROM PlayableItemEntity WHERE name = 'My port';") != "0" ||
		c.sql("SELECT count(*) FROM PlayableItemEntity WHERE name = 'PortMaster' AND uri = "+djQuote(s.djPortMasterURI())+";") != "1" ||
		!c.exists(s.djPortMasterPath()) {
		c.fail("removed port survived or PortMaster was not restored")
	}
	if c.sql("SELECT * FROM PlatformEntity ORDER BY id;") != platforms ||
		c.sql("SELECT * FROM PlayerEntity ORDER BY id;") != players ||
		c.sql("SELECT * FROM PlayableItemEntity WHERE name = 'User game';") != user {
		c.fail("platforms, players or the user's other game changed")
	}
	// Empty ports folder still has exactly the synthetic PortMaster launcher.
	must(t, os.Remove(s.easyroms+"/ports/"+name+".SH"))
	s.ReconcilePorts()
	if got := c.sql("SELECT name FROM PlayableItemEntity WHERE attached_platform_id = (SELECT id FROM PlatformEntity WHERE unique_id = 'ports');"); got != "PortMaster" {
		c.fail("empty folder: %q", got)
	}
}

func TestDaijishouReconcilePortsRespectsUserState(t *testing.T) {
	for _, state := range []string{"missing database", "empty library", "deleted Ports", "unreadable database", "unmounted", "missing folder", "different card", "bad folder", "no seeded marker"} {
		t.Run(state, func(t *testing.T) {
			c := newDJPorts(t)
			s := c.s
			write(t, s.easyroms+"/ports/Game.sh", "game")
			s.ReconcilePorts()
			switch state {
			case "missing database":
				must(t, os.Remove(s.djDB()))
			case "empty library":
				c.sql("DELETE FROM PlatformEntity; DELETE FROM PlayableItemEntity;")
			case "deleted Ports":
				c.sql("DELETE FROM PlayableItemEntity WHERE attached_platform_id = (SELECT id FROM PlatformEntity WHERE unique_id = 'ports'); DELETE FROM PlatformEntity WHERE unique_id = 'ports';")
			case "unreadable database":
				write(t, s.djDB(), "unreadable")
			case "unmounted":
				s.easyroms = ""
			case "missing folder":
				must(t, os.RemoveAll(s.easyroms+"/ports"))
			case "different card":
				s.easyroms = s.MediaRW + "/FFFF-0000"
				touchAll(t, s.easyroms, "ports/Other.sh")
			case "bad folder":
				c.sql("UPDATE PlatformEntity SET items_sync_tree_uri_list = '[\"" + djTreeURI(djUUID, "..%2Foutside") + "\"]' WHERE unique_id = 'ports';")
			case "no seeded marker":
				must(t, os.Remove(s.djSeeded()))
				c.sql("DELETE FROM PlayableItemEntity WHERE uri = " + djQuote(s.djPortMasterURI()) + ";")
			}
			before := c.sum(s.djDB())
			c.f.reset()
			s.ReconcilePorts()
			if state == "no seeded marker" {
				if c.sql("SELECT count(*) FROM PlayableItemEntity WHERE uri = "+djQuote(s.djPortMasterURI())+";") != "1" {
					c.fail("reconcile depends on the seeded marker")
				}
				return
			}
			if c.sum(s.djDB()) != before || c.f.count("am") != 0 || (state == "missing database" && c.exists(s.djDB())) {
				c.fail("user state %s changed", state)
			}
		})
	}
}

func TestDaijishouReconcilePortsSyncFolderAndOnDemandMount(t *testing.T) {
	c := newDJPorts(t)
	s := c.s
	// Use the actual platform folder, rather than assuming /roms/ports or a UUID.
	folder := "other ports"
	write(t, s.easyroms+"/"+folder+"/Game.sh", "game")
	c.sql("UPDATE PlatformEntity SET items_sync_tree_uri_list = '[\"" + djTreeURI(djUUID, "other%20ports") + "\"]' WHERE unique_id = 'ports';")
	write(t, s.Mounts, "/dev/block/vold/public:8,7 "+s.easyroms+" exfat rw 0 0\n")
	c.f.props["sys.andr36oid.easyroms.ready"] = "1"
	c.f.props["sys.andr36oid.easyroms.id"] = "public:8,7"
	s.easyroms = "" // a fresh one-shot process has not run FindEasyroms
	s.ReconcilePorts()
	if got := c.sql("SELECT uri FROM PlayableItemEntity WHERE name = 'Game';"); got != djTreeURI(djUUID, "other%20ports")+"/document/ABCD-1234%3Aother%20ports%2FGame.sh" {
		c.fail("on-demand sync folder: %q", got)
	}
}

func TestDaijishouReconcilePortsEveryBoot(t *testing.T) {
	for _, late := range []bool{false, true} {
		t.Run(fmt.Sprintf("late=%t", late), func(t *testing.T) {
			c := newDJPorts(t)
			s := c.s
			write(t, s.Mounts, "/dev/block/vold/public:179,7 "+s.easyroms+" exfat rw 0 0\n")
			run := s.Main
			if late {
				run = s.RunEasyromsLate
			}
			touchAll(t, s.easyroms, "ports/First.sh")
			run()
			if c.sql("SELECT count(*) FROM PlayableItemEntity WHERE name IN ('First', 'PortMaster');") != "2" {
				c.fail("boot did not reconcile after the one-time seed")
			}
			must(t, os.Remove(s.easyroms+"/ports/First.sh"))
			touchAll(t, s.easyroms, "ports/Second.sh")
			run()
			if got := c.sql("SELECT name FROM PlayableItemEntity ORDER BY name;"); got != "PortMaster\nSecond" {
				c.fail("next boot did not reconcile: %q", got)
			}
		})
	}
}

func TestDaijishouReconcilePortsNoSyncPrompt(t *testing.T) {
	c := newDJPorts(t)
	s := c.s
	write(t, s.djPrefs(), androidPrefs)
	touchAll(t, s.easyroms, "ports/First.sh")
	s.ReconcilePorts()
	c.f.reset()
	s.DJNewGames()
	if read(t, s.djPrefs()) != androidPrefs || c.f.count("am") != 0 {
		c.fail("Ports-only addition asked for a full-library Sync")
	}
	touchAll(t, s.easyroms, "nes/Game.nes", "ports/Second.sh")
	s.DJNewGames()
	if !c.prefsAsk() || !strings.Contains(c.hint(), "nes (1)") || strings.Contains(c.hint(), "ports") {
		c.fail("other platform's new-game prompt changed: %q", c.hint())
	}
}

func TestDaijishouReconcilePortsWAL(t *testing.T) {
	c := newDJPorts(t)
	s := c.s
	wal := c.root + "/wal"
	must(t, os.MkdirAll(wal, 0o755))
	// Keep both the platform and games in the WAL, as with the live home screen.
	script := "PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0;\n" + c.sql(".dump") +
		"\n.shell cp \"" + wal + "/Daijishou.db\" \"" + wal + "/Daijishou.db-wal\" \"" + dirOf(s.djDB()) + "/\"\n"
	cmd := exec.Command("sqlite3", wal+"/Daijishou.db")
	cmd.Stdin = strings.NewReader(script)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("%s", out)
	}
	touchAll(t, s.easyroms, "ports/Game.sh")
	s.ReconcilePorts()
	if got := c.sql("SELECT name FROM PlayableItemEntity ORDER BY name;"); got != "Game\nPortMaster" {
		c.fail("WAL library: %q", got)
	}
	if got := c.sql("PRAGMA integrity_check;"); got != "ok" {
		c.fail("WAL database integrity: %q", got)
	}
}

func TestDaijishouReconcilePortsConcurrentLibraryRemoval(t *testing.T) {
	c := newDJPorts(t)
	s := c.s
	touchAll(t, s.easyroms, "ports/Game.sh")
	// Clear the library after the snapshot, just as we stop the app to apply it.
	c.f.handlers["am"] = func(a []string) (string, error) {
		if a[0] == "force-stop" {
			c.sql("DELETE FROM PlayableItemEntity; DELETE FROM PlatformEntity;")
		}
		return "", nil
	}
	s.ReconcilePorts()
	if got := c.sql("SELECT (SELECT count(*) FROM PlayableItemEntity) + (SELECT count(*) FROM PlatformEntity);"); got != "0" {
		c.fail("recreated a concurrently deleted library: %s rows", got)
	}
}

func TestDaijishouReconcilePortsRollback(t *testing.T) {
	c := newDJPorts(t)
	s := c.s
	touchAll(t, s.easyroms, "ports/Old.sh")
	s.ReconcilePorts()
	before := c.sql("SELECT * FROM PlayableItemEntity ORDER BY id;")
	must(t, os.Remove(s.easyroms+"/ports/Old.sh"))
	touchAll(t, s.easyroms, "ports/New.sh")
	// Inject an insert failure only in the live database. The preceding deletion
	// must roll back too; failing the copy's validation alone would not test this.
	c.f.handlers["am"] = func(a []string) (string, error) {
		if a[0] == "force-stop" {
			c.sql("CREATE TRIGGER IF NOT EXISTS reject_new_port BEFORE INSERT ON PlayableItemEntity BEGIN SELECT RAISE(ABORT, 'test failure'); END;")
		}
		return "", nil
	}
	s.ReconcilePorts()
	if got := c.sql("SELECT * FROM PlayableItemEntity ORDER BY id;"); got != before || !logHas(s, "test failure") {
		c.fail("failed reconcile did not roll back: %q", got)
	}
}

// An upgraded console seeded its library before the Ports platform existed. The
// first-boot seed will not re-add it (the library is already seeded) and the
// reconcile never recreates a platform, so djMigratePorts must add it once, after
// which the reconcile fills it. A later user deletion must then be respected.
func TestDaijishouMigratePorts(t *testing.T) {
	c := newDJPorts(t)
	s := c.s
	write(t, s.djPrefs(), androidPrefs)

	// Simulate the pre-Ports library: drop the Ports platform, its player and games,
	// and make sure the one-time migration marker is not set.
	c.sql("DELETE FROM PlayableItemEntity WHERE attached_platform_id IN (SELECT id FROM PlatformEntity WHERE unique_id = 'ports');")
	c.sql("DELETE FROM PlayerEntity WHERE id IN (SELECT default_player_id FROM PlatformEntity WHERE unique_id = 'ports');")
	c.sql("DELETE FROM PlatformEntity WHERE unique_id = 'ports';")
	_ = os.Remove(s.state("daijishou.ports-migrated"))
	if got := c.sql("SELECT count(*) FROM PlatformEntity WHERE unique_id = 'ports';"); got != "0" {
		c.fail("setup: Ports platform should be gone, got %q", got)
	}
	users := c.sql("SELECT count(*) FROM PlatformEntity;")

	write(t, s.easyroms+"/ports/Old.sh", "old port\n")
	s.ReconcilePorts()

	// The Ports platform is back, with its subsystem player, and is populated.
	if got := c.sql("SELECT p.unique_id FROM PlayerEntity p JOIN PlatformEntity f ON p.id = f.default_player_id WHERE f.unique_id = 'ports';"); got != "ports.andr36oid.subsystem" {
		c.fail("migrated Ports player: %q", got)
	}
	if got := c.sql("SELECT name FROM PlayableItemEntity WHERE attached_platform_id IN (SELECT id FROM PlatformEntity WHERE unique_id = 'ports') ORDER BY name;"); got != "Old\nPortMaster" {
		c.fail("migrated Ports games: %q", got)
	}
	// The user's other platforms are untouched (we only added one).
	if got := c.sql("SELECT count(*) FROM PlatformEntity;"); got != fmt.Sprint(atoiTest(users)+1) {
		c.fail("migration added more than the Ports platform: now %q, was %q", got, users)
	}
	if !c.exists(s.state("daijishou.ports-migrated")) {
		c.fail("migration marker not set")
	}

	// A user who now deletes the Ports platform must not get it back (marker set).
	c.sql("DELETE FROM PlatformEntity WHERE unique_id = 'ports';")
	s.ReconcilePorts()
	if got := c.sql("SELECT count(*) FROM PlatformEntity WHERE unique_id = 'ports';"); got != "0" {
		c.fail("Ports platform recreated after the user deleted it: %q", got)
	}
}

// A fresh install already has the Ports platform from the seed: the migration must
// not add a duplicate, and must set its marker so it stops checking.
func TestDaijishouMigratePortsFreshIsNoop(t *testing.T) {
	c := newDJPorts(t)
	s := c.s
	write(t, s.djPrefs(), androidPrefs)
	_ = os.Remove(s.state("daijishou.ports-migrated"))
	write(t, s.easyroms+"/ports/Old.sh", "old port\n")
	s.ReconcilePorts()
	if got := c.sql("SELECT count(*) FROM PlatformEntity WHERE unique_id = 'ports';"); got != "1" {
		c.fail("fresh install should keep exactly one Ports platform, got %q", got)
	}
	if !c.exists(s.state("daijishou.ports-migrated")) {
		c.fail("migration marker not set on a fresh install")
	}
}

func atoiTest(s string) int { n, _ := strconv.Atoi(s); return n }
