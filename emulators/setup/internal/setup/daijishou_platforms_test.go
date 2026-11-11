package setup

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestDaijishouMainstreamEmpty(t *testing.T) {
	c := newDJ(t, "", "gbc")
	c.apps += ",org.andr36oid.subsystemsetup"
	c.run()
	want := "gb\ngba\ngbc\ngenesis\nn64\nnes\nports\npsp\npsx\nsnes"
	if got := c.sql("SELECT unique_id FROM PlatformEntity ORDER BY unique_id;"); got != want {
		t.Fatalf("empty card systems:\n%s\nwant:\n%s", got, want)
	}
	if !c.exists(c.s.easyroms + "/gbc") {
		t.Fatal("mainstream sync folder not created")
	}
	if err := c.checklib(); err != nil {
		t.Fatal(err)
	}
}

func TestDaijishouNicheProvisionOnlyWithGames(t *testing.T) {
	c := newDJ(t, "", "")
	touchAll(t, c.s.easyroms, "fds/subdir/Mario.FDS", "atari2600/README.txt", "pcengine/.hidden.pce", "pcengine/.cache/Game.pce", "dos/Game.pure.zip", "msx/Saved.srm", "amiga/Box.png")
	must(t, os.MkdirAll(c.s.easyroms+"/pcengine/Directory.pce", 0755))
	must(t, os.Symlink(c.s.easyroms+"/fds/subdir/Mario.FDS", c.s.easyroms+"/pcengine/Link.pce"))
	c.run()
	if c.sql("SELECT count(*) FROM PlatformEntity WHERE unique_id='fds';") != "1" {
		t.Fatal("nested uppercase game did not provision FDS")
	}
	for _, id := range []string{"atari2600", "tg16", "dos", "msx", "amiga"} {
		if c.sql("SELECT count(*) FROM PlatformEntity WHERE unique_id="+djQuote(id)+";") != "0" {
			t.Fatalf("junk provisioned %s", id)
		}
	}
	if err := c.checklib(); err != nil {
		t.Fatal(err)
	}
}

func TestDaijishouNicheAliasAndPlayers(t *testing.T) {
	c := newDJ(t, "", "")
	write(t, c.s.easyroms+"/turbografx/Game.pce", "ROM")
	c.run()
	if c.sql("SELECT count(*) FROM PlatformEntity WHERE unique_id='tg16';") != "1" {
		t.Fatal("alias folder ignored")
	}
	if c.sql("SELECT count(*) FROM PlayerEntity WHERE unique_id LIKE 'fds.%';") != "0" {
		t.Fatal("players for omitted platform leaked")
	}
	// A game does not create a broken platform with no installed player.
	c = newDJ(t, "", "")
	for _, line := range c.s.djSeedLines() {
		if line.kind == "player" && line.n == "2" && strings.HasPrefix(line.arg, "core:") {
			_ = os.Remove(c.s.raData() + "/cores/" + strings.TrimPrefix(line.arg, "core:") + "_libretro_android.so")
		}
	}
	write(t, c.s.easyroms+"/fds/Game.fds", "ROM")
	c.run()
	if c.sql("SELECT count(*) FROM PlatformEntity WHERE unique_id='fds';") != "0" {
		t.Fatal("missing player ignored")
	}
}

func TestDaijishouPresenceScanErrors(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "not-dir"), "x")
	if _, err := djFolderHasGames(filepath.Join(dir, "not-dir", "child"), djGamePattern("zip")); err == nil {
		t.Fatal("I/O error treated as empty")
	}
	found, err := djFolderHasGames(filepath.Join(dir, "missing"), djGamePattern("zip"))
	if err != nil || found {
		t.Fatalf("missing folder: %v %v", found, err)
	}
}
