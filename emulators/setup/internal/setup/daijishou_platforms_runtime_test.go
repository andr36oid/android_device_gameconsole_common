package setup

import (
	"errors"
	"os"
	"strings"
	"testing"
)

func reconcileSystems(t *testing.T, c *djConsole) {
	t.Helper()
	if err := c.s.djReconcilePlatforms(); err != nil {
		t.Fatal(err)
	}
}

func TestDaijishouRuntimeSystems(t *testing.T) {
	c := newDJ(t, "", "")
	c.run()
	s := c.s
	platforms := c.sql("SELECT * FROM PlatformEntity ORDER BY id;")
	players := c.sql("SELECT * FROM PlayerEntity ORDER BY id;")
	write(t, s.easyroms+"/fds/Nested/Game.FDS", "rom")
	c.running = true
	c.f.reset()
	reconcileSystems(t, c)
	if c.sql("SELECT is_visible FROM PlatformEntity WHERE unique_id='fds';") != "1" {
		t.Fatal("FDS not added")
	}
	if c.sql("SELECT * FROM PlatformEntity WHERE unique_id!='fds' ORDER BY id;") != platforms || c.sql("SELECT * FROM PlayerEntity WHERE unique_id NOT LIKE 'fds.%' ORDER BY id;") != players {
		t.Fatal("unrelated rows changed")
	}
	if c.f.count("am force-stop") != 2 || c.f.count("am start") != 1 {
		t.Fatalf("refresh calls: %v", c.f.calls)
	}
	id := c.sql("SELECT id FROM PlatformEntity WHERE unique_id='fds';")
	if !c.exists(s.djData() + "/files/platform_wallpapers/" + id + ".png") {
		t.Fatal("art missing")
	}
	libraryGame(t, c, "fds", "Game.FDS", djLibraryState{true, 7, 12345})
	history := c.sql("SELECT * FROM PlayableItemEntity ORDER BY id;")
	c.sql("UPDATE PlatformEntity SET name='My FDS' WHERE unique_id='fds';")
	row := c.sql("SELECT id,name,default_player_id,player_id_list,extra FROM PlatformEntity WHERE unique_id='fds';")
	must(t, os.Remove(s.easyroms+"/fds/Nested/Game.FDS"))
	reconcileSystems(t, c)
	if c.sql("SELECT is_visible FROM PlatformEntity WHERE unique_id='fds';") != "0" || c.sql("SELECT * FROM PlayableItemEntity ORDER BY id;") != history {
		t.Fatal("hide lost history or failed")
	}
	before := read(t, s.djDB())
	c.f.reset()
	reconcileSystems(t, c)
	if read(t, s.djDB()) != before || c.f.count("am") != 0 {
		t.Fatal("settled scan changed DB/home")
	}
	write(t, s.easyroms+"/fds/New.fds", "rom")
	reconcileSystems(t, c)
	if c.sql("SELECT is_visible FROM PlatformEntity WHERE unique_id='fds';") != "1" || c.sql("SELECT id,name,default_player_id,player_id_list,extra FROM PlatformEntity WHERE unique_id='fds';") != row || c.sql("SELECT * FROM PlayableItemEntity ORDER BY id;") != history {
		t.Fatal("reveal replaced metadata/history")
	}
	c.sql("UPDATE PlatformEntity SET is_visible=0 WHERE unique_id='fds';")
	reconcileSystems(t, c)
	if c.sql("SELECT is_visible FROM PlatformEntity WHERE unique_id='fds';") != "0" {
		t.Fatal("manual hide overridden")
	}
}

func TestDaijishouRuntimeLegacyAndForeign(t *testing.T) {
	c := newDJ(t, "", "")
	write(t, c.s.easyroms+"/fds/Game.fds", "rom")
	c.run()
	must(t, os.Remove(c.s.djPlatformStatePath()))
	must(t, os.Remove(c.s.easyroms+"/fds/Game.fds"))
	reconcileSystems(t, c)
	if c.sql("SELECT is_visible FROM PlatformEntity WHERE unique_id='fds';") != "0" {
		t.Fatal("legacy system not hidden")
	}
	write(t, c.s.easyroms+"/fds/Game.fds", "rom")
	reconcileSystems(t, c)
	if c.sql("SELECT is_visible FROM PlatformEntity WHERE unique_id='fds';") != "1" {
		t.Fatal("legacy reveal failed")
	}
	c.sql("UPDATE PlatformEntity SET items_sync_tree_uri_list='[\"content://foreign/tree\"]' WHERE unique_id='fds';")
	must(t, os.Remove(c.s.easyroms+"/fds/Game.fds"))
	before := read(t, c.s.djDB())
	c.f.reset()
	reconcileSystems(t, c)
	if read(t, c.s.djDB()) != before || c.f.count("am") != 0 {
		t.Fatal("foreign folder changed")
	}
}

func TestDaijishouRuntimeDeletionAndReset(t *testing.T) {
	c := newDJ(t, "", "")
	write(t, c.s.easyroms+"/fds/Game.fds", "rom")
	c.run()
	c.sql("DELETE FROM PlatformEntity WHERE unique_id IN ('fds','nes');")
	reconcileSystems(t, c)
	if c.sql("SELECT count(*) FROM PlatformEntity WHERE unique_id IN ('fds','nes');") != "0" {
		t.Fatal("deleted system recreated")
	}
	write(t, c.s.easyroms+"/pcengine/Game.pce", "rom")
	path := c.s.djDB()
	data := read(t, path)
	must(t, os.Rename(path, path+".old"))
	write(t, path, data)
	before := read(t, path)
	c.f.reset()
	reconcileSystems(t, c)
	if read(t, path) != before || c.f.count("am") != 0 {
		t.Fatal("replacement library changed")
	}
}

func TestDaijishouRuntimeFailureAndRace(t *testing.T) {
	for _, mode := range []string{"copy-sql", "stop", "race", "card"} {
		t.Run(mode, func(t *testing.T) {
			c := newDJ(t, "", "")
			c.run()
			s := c.s
			write(t, s.easyroms+"/fds/Game.fds", "rom")
			before := read(t, s.djDB())
			realSQL := c.f.handlers["sqlite3"]
			if mode == "copy-sql" {
				c.f.handlers["sqlite3"] = func(args []string) (string, error) {
					if strings.Contains(strings.Join(args, " "), "platforms.sql") {
						return "", errors.New("SQL failure")
					}
					return realSQL(args)
				}
			}
			first := true
			c.f.handlers["am"] = func(args []string) (string, error) {
				if len(args) > 0 && args[0] == "force-stop" && first {
					first = false
					switch mode {
					case "stop":
						return "", errors.New("stop failed")
					case "race":
						c.sql("UPDATE PlatformEntity SET is_visible=0 WHERE unique_id='nes';")
					case "card":
						must(t, os.Rename(s.easyroms, s.easyroms+".removed"))
					}
				}
				return "", nil
			}
			if err := s.djReconcilePlatforms(); err == nil {
				t.Fatal("injected failure succeeded")
			}
			if mode != "race" && read(t, s.djDB()) != before {
				t.Fatal("failed update changed DB")
			}
			if c.sql("SELECT count(*) FROM PlatformEntity WHERE unique_id='fds';") != "0" {
				t.Fatal("stale plan applied")
			}
			if mode == "race" && c.sql("SELECT is_visible FROM PlatformEntity WHERE unique_id='nes';") != "0" {
				t.Fatal("user edit lost")
			}
		})
	}
}

func TestDaijishouRuntimePendingRecovery(t *testing.T) {
	c := newDJ(t, "", "")
	s := c.s
	write(t, s.easyroms+"/fds/Game.fds", "rom")
	c.run()
	must(t, os.Remove(s.easyroms+"/fds/Game.fds"))
	must(t, os.MkdirAll(s.djPlatformStatePath()+".tmp/block", 0700))
	if err := s.djReconcilePlatforms(); err == nil {
		t.Fatal("state failure hidden")
	}
	if !c.exists(s.djPlatformStatePath()+".pending") || c.sql("SELECT is_visible FROM PlatformEntity WHERE unique_id='fds';") != "0" {
		t.Fatal("pending boundary fixture failed")
	}
	must(t, os.RemoveAll(s.djPlatformStatePath()+".tmp"))
	reconcileSystems(t, c)
	if c.exists(s.djPlatformStatePath() + ".pending") {
		t.Fatal("pending not retired")
	}
	write(t, s.easyroms+"/fds/Game.fds", "rom")
	reconcileSystems(t, c)
	if c.sql("SELECT is_visible FROM PlatformEntity WHERE unique_id='fds';") != "1" {
		t.Fatal("hide ownership lost")
	}
}

func TestDaijishouRuntimeSkipUnownedUnavailableOrGuest(t *testing.T) {
	for _, mode := range []string{"unowned", "other-card", "version", "guest", "no-card"} {
		t.Run(mode, func(t *testing.T) {
			c := newDJ(t, "", "")
			c.run()
			s := c.s
			write(t, s.easyroms+"/fds/Game.fds", "rom")
			switch mode {
			case "unowned":
				must(t, os.Remove(s.djSeeded()))
			case "other-card":
				write(t, s.djSeeded(), "FFFF-0000\n")
			case "version":
				c.vc = "415"
			case "guest":
				c.f.props["sys.andr36oid.subsystem.session"] = "running"
			case "no-card":
				s.easyroms = ""
			}
			before := read(t, s.djDB())
			c.f.reset()
			reconcileSystems(t, c)
			if read(t, s.djDB()) != before || c.f.count("am") != 0 {
				t.Fatal("unowned/unavailable library changed")
			}
		})
	}
}

func TestDaijishouRuntimeRetryMarker(t *testing.T) {
	c := newDJ(t, "", "")
	c.run()
	s := c.s
	write(t, s.easyroms+"/fds/Game.fds", "rom")
	real := c.f.handlers["sqlite3"]
	c.f.handlers["sqlite3"] = func(args []string) (string, error) {
		if strings.Contains(strings.Join(args, " "), "platforms.sql") {
			return "", errors.New("temporarily busy")
		}
		return real(args)
	}
	s.ReconcilePlatforms()
	if !c.exists(s.state("daijishou-platforms.retry")) {
		t.Fatal("failed queued update lost its retry")
	}
	c.f.handlers["sqlite3"] = real
	s.ReconcilePlatforms()
	if c.exists(s.state("daijishou-platforms.retry")) || c.sql("SELECT count(*) FROM PlatformEntity WHERE unique_id='fds';") != "1" {
		t.Fatal("retry did not recover/clear marker")
	}
}
