package setup

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"crypto/rand"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"
)

// ROM folders, free games and .nomedia on a fake EASYROMS (was rom-nomedia-test.sh and
// FreeGames/tests/run-tests.sh).

const freeGamesArchive = "/../FreeGames/free-games.tar.gz"

// romCard is a console with EASYROMS mounted at MediaRW/ABCD-1234. withDoom adds the doom
// folder to the list (the archive has Freedoom).
func romCard(t *testing.T, withDoom bool, archive string) (*Setup, string) {
	f := newFake()
	s, _ := newSetup(t, f)
	var list []string
	for _, l := range splitLines(read(t, emu+"/seed/rom-folders.txt")) {
		if strings.HasPrefix(l, "doom ") && !withDoom {
			continue
		}
		list = append(list, l)
	}
	if withDoom && !strings.Contains(strings.Join(list, "\n"), "\ndoom ") {
		list = append(list, "doom              Doom - RetroArch (PrBoom)")
	}
	write(t, s.Base+"/rom-folders.txt", strings.Join(list, "\n")+"\n")
	if archive != "" {
		must(t, os.Symlink(archive, s.Base+"/free-games.tar.gz"))
	}
	s.easyroms = s.MediaRW + "/ABCD-1234"
	must(t, os.MkdirAll(s.easyroms, 0o755))
	return s, s.easyroms
}

// sameAsArchive: the file at rel on the card is the archive's.
func sameAsArchive(t *testing.T, root, rel string) bool {
	var want bytes.Buffer
	if err := tarGzFile(emu+freeGamesArchive, rel, &want); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(root + "/" + rel)
	return err == nil && bytes.Equal(got, want.Bytes())
}

var plainGames = []string{"gb/2048.gb", "gb/Libbet and the Magic Floor.gb", "gbc/Tobu Tobu Girl Deluxe.gb",
	"gbc/uCity.gbc", "nes/RHDE - Furniture Fight.nes", "nes/Thwaite.nes", "free-games-licenses.txt"}

func noPart(root string) bool {
	found := false
	_ = filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
		if strings.HasSuffix(p, ".part") {
			found = true
		}
		return nil
	})
	return !found
}

// filesBut lists the regular files under root except README-andr36oid.txt.
func filesBut(root string) []string {
	var out []string
	_ = filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
		if err == nil && d.Type().IsRegular() && d.Name() != "README-andr36oid.txt" {
			out = append(out, p)
		}
		return nil
	})
	return out
}

func countLog(s *Setup, sub string) int {
	n := 0
	for _, l := range s.Lines {
		if strings.Contains(l, sub) {
			n++
		}
	}
	return n
}

func TestFreeGames(t *testing.T) {
	archive := emu + freeGamesArchive
	if _, err := os.Stat(archive); err != nil {
		t.Skip("no free-games.tar.gz")
	}
	t.Run("fresh card, no doom folder", func(t *testing.T) {
		s, r := romCard(t, false, archive)
		s.MakeRomFolders()
		for _, g := range plainGames {
			if !sameAsArchive(t, r, g) {
				t.Errorf("%s differs or is missing", g)
			}
		}
		if sys_exists(r+"/doom") || !sys_exists(r+"/README-andr36oid.txt") || !sys_exists(s.state("rom-folders.ABCD-1234")) ||
			!logHas(s, "no doom/ folder on EASYROMS, left out freedoom1.wad") || !logHas(s, "copied 7 files") || !noPart(r) {
			t.Errorf("log: %v", s.Lines)
		}
	})
	t.Run("doom folder: Freedoom too", func(t *testing.T) {
		s, r := romCard(t, true, archive)
		s.MakeRomFolders()
		if !sameAsArchive(t, r, "doom/freedoom1.wad") || !logHas(s, "copied 8 files") || !noPart(r) {
			t.Errorf("log: %v", s.Lines)
		}
	})
	t.Run("ArkOS layout: nothing touched", func(t *testing.T) {
		s, r := romCard(t, false, archive)
		write(t, r+"/nes/game.nes", "mine\n")
		s.MakeRomFolders()
		if f := filesBut(r); len(f) != 1 || sys_exists(r+"/README-andr36oid.txt") || !sys_exists(s.state("rom-folders.ABCD-1234")) {
			t.Errorf("files %v", f)
		}
	})
	t.Run("a deleted game stays deleted", func(t *testing.T) {
		s, r := romCard(t, false, archive)
		s.MakeRomFolders()
		must(t, os.Remove(r+"/gb/2048.gb"))
		must(t, os.Remove(r+"/free-games-licenses.txt"))
		s.MakeRomFolders()
		if sys_exists(r+"/gb/2048.gb") || sys_exists(r+"/free-games-licenses.txt") || countLog(s, "copied") != 1 {
			t.Errorf("log: %v", s.Lines)
		}
	})
	t.Run("never overwrites", func(t *testing.T) {
		s, r := romCard(t, false, archive)
		for _, n := range s.romFolders() {
			must(t, os.MkdirAll(r+"/"+n, 0o755))
		}
		write(t, r+"/gb/2048.gb", "mine\n")
		write(t, r+"/free-games-licenses.txt", "mine\n")
		s.seedFreeGames(r)
		if read(t, r+"/gb/2048.gb") != "mine\n" || read(t, r+"/free-games-licenses.txt") != "mine\n" ||
			!sameAsArchive(t, r, "gb/Libbet and the Magic Floor.gb") || !logHas(s, "copied 5 files") {
			t.Errorf("log: %v", s.Lines)
		}
	})
	t.Run("power cut while copying", func(t *testing.T) {
		s, r := romCard(t, false, archive)
		write(t, s.state("rom-folders.pending.ABCD-1234"), "")
		for _, n := range s.romFolders() {
			must(t, os.MkdirAll(r+"/"+n, 0o755))
		}
		write(t, r+"/gb/2048.gb.part", "half\n")
		write(t, r+"/gbc/uCity.gbc.part", "half\n")
		s.MakeRomFolders()
		for _, g := range plainGames {
			if !sameAsArchive(t, r, g) {
				t.Errorf("%s differs", g)
			}
		}
		if !noPart(r) || !sys_exists(s.state("rom-folders.ABCD-1234")) || sys_exists(s.state("rom-folders.pending.ABCD-1234")) ||
			!logHas(s, "copied 7 files") {
			t.Errorf("log: %v", s.Lines)
		}
	})
	t.Run("no archive: folders only", func(t *testing.T) {
		s, r := romCard(t, false, "")
		s.MakeRomFolders()
		if !sys_exists(r+"/gb") || len(filesBut(r)) != 0 || logHas(s, "copied") {
			t.Errorf("files %v", filesBut(r))
		}
	})
	t.Run("broken archive", func(t *testing.T) {
		bad := t.TempDir() + "/bad.tar.gz"
		junk := make([]byte, 5000)
		_, _ = rand.Read(junk)
		must(t, os.WriteFile(bad, junk, 0o644))
		s, r := romCard(t, false, bad)
		s.MakeRomFolders()
		if !sys_exists(r+"/gb") || !logHas(s, "can't read") || len(filesBut(r)) != 0 || !noPart(r) {
			t.Errorf("log: %v", s.Lines)
		}
	})
	t.Run("card too full", func(t *testing.T) {
		s, r := romCard(t, false, archive)
		s.FreeKB = func(string) (int64, error) { return 1000, nil }
		s.MakeRomFolders()
		if !sys_exists(r+"/gb") || !logHas(s, "only 1000 KB free") || len(filesBut(r)) != 0 {
			t.Errorf("log: %v", s.Lines)
		}
	})
	t.Run(".noroms and not mounted: nothing", func(t *testing.T) {
		s, r := romCard(t, false, archive)
		s.noroms = true
		s.MakeRomFolders()
		s.noroms = false
		s.easyroms = ""
		s.MakeRomFolders()
		s.seedFreeGames("")
		ents, _ := os.ReadDir(r)
		if len(ents) != 0 || logHas(s, "copied") {
			t.Errorf("wrote %v", ents)
		}
	})
}

func noted(t *testing.T, s *Setup) []string {
	b, _ := os.ReadFile(s.state("rom-nomedia.ABCD-1234"))
	l := splitLines(string(b))
	sort.Strings(l)
	return l
}

func TestRomNomedia(t *testing.T) {
	boot := func(s *Setup) {
		s.MakeRomFolders()
		s.RomNomedia()
	}
	t.Run("fresh card", func(t *testing.T) {
		s, r := romCard(t, false, "")
		boot(s)
		names := s.romFolders()
		for _, n := range names {
			if st, err := os.Stat(r + "/" + n + "/.nomedia"); err != nil || st.Size() != 0 {
				t.Errorf("no empty .nomedia in %s", n)
			}
		}
		if len(noted(t, s)) != len(names) || sys_exists(r+"/.nomedia") || !sys_exists(r+"/README-andr36oid.txt") {
			t.Errorf("noted %v", noted(t, s))
		}
		if strings.Join(s.Lines, "|") != "made the ROM folders on EASYROMS (ABCD-1234)" {
			t.Errorf("log %v", s.Lines)
		}
	})
	t.Run("ArkOS card: only folders that exist, games untouched", func(t *testing.T) {
		s, r := romCard(t, false, "")
		write(t, r+"/nes/Game (USA).nes", "rom\n")
		write(t, r+"/nes/images/Game.png", "png\n")
		must(t, os.MkdirAll(r+"/gba", 0o755))
		must(t, os.MkdirAll(r+"/ports", 0o755))
		boot(s)
		if !sys_exists(r+"/nes/.nomedia") || !sys_exists(r+"/gba/.nomedia") || sys_exists(r+"/ports/.nomedia") ||
			sys_exists(r+"/nes/images/.nomedia") || sys_exists(r+"/snes") || logHas(s, "couldn") {
			t.Error("wrong folders")
		}
		if read(t, r+"/nes/Game (USA).nes") != "rom\n" || read(t, r+"/nes/images/Game.png") != "png\n" {
			t.Error("games changed")
		}
		if strings.Join(noted(t, s), " ") != "gba nes" {
			t.Errorf("noted %v", noted(t, s))
		}
	})
	t.Run("second start writes nothing; a deleted one stays deleted", func(t *testing.T) {
		s, r := romCard(t, false, "")
		boot(s)
		old := time.Date(2001, 1, 1, 0, 0, 0, 0, time.UTC)
		must(t, os.Chtimes(r+"/nes/.nomedia", old, old))
		before := read(t, s.state("rom-nomedia.ABCD-1234"))
		must(t, os.Remove(r+"/snes/.nomedia"))
		boot(s)
		if st, _ := os.Stat(r + "/nes/.nomedia"); !st.ModTime().Equal(old) {
			t.Error(".nomedia written again")
		}
		if read(t, s.state("rom-nomedia.ABCD-1234")) != before || sys_exists(r+"/snes/.nomedia") {
			t.Error("noted list changed or deleted .nomedia back")
		}
	})
	t.Run("folder copied in later", func(t *testing.T) {
		s, r := romCard(t, false, "")
		must(t, os.MkdirAll(r+"/nes", 0o755))
		boot(s)
		write(t, r+"/gba/x.gba", "g\n")
		boot(s)
		if !sys_exists(r+"/gba/.nomedia") || !sys_exists(r+"/gba/x.gba") || !strings.Contains(" "+strings.Join(noted(t, s), " ")+" ", " gba ") {
			t.Error("later folder")
		}
	})
	t.Run("the user's own .nomedia kept", func(t *testing.T) {
		s, r := romCard(t, false, "")
		write(t, r+"/nes/.nomedia", "mine\n")
		boot(s)
		if read(t, r+"/nes/.nomedia") != "mine\n" {
			t.Error("changed")
		}
	})
	t.Run("not mounted or .noroms: nothing", func(t *testing.T) {
		s, r := romCard(t, false, "")
		s.easyroms = ""
		boot(s)
		s.noroms = true
		boot(s)
		_ = os.Remove(s.LogFile)
		a, _ := os.ReadDir(s.State)
		b, _ := os.ReadDir(r)
		if len(a) != 0 || len(b) != 0 {
			t.Errorf("wrote %v %v", a, b)
		}
	})
	t.Run("a folder that can't take it is tried again", func(t *testing.T) {
		s, r := romCard(t, false, "")
		must(t, os.MkdirAll(r+"/nes", 0o755))
		must(t, os.MkdirAll(r+"/snes", 0o755))
		must(t, os.Symlink(r+"/missing/x", r+"/nes/.nomedia"))
		boot(s)
		if !logHas(s, "couldn't put .nomedia into nes/") || strings.Contains(" "+strings.Join(noted(t, s), " ")+" ", " nes ") ||
			!sys_exists(r+"/snes/.nomedia") {
			t.Fatalf("first: %v", s.Lines)
		}
		must(t, os.Remove(r+"/nes/.nomedia"))
		boot(s)
		if !sys_exists(r+"/nes/.nomedia") || !strings.Contains(" "+strings.Join(noted(t, s), " ")+" ", " nes ") {
			t.Error("not tried again")
		}
	})
}

// FindEasyroms reads /proc/mounts: vold's device for partition 7 at /mnt/media_rw/<uuid>
func TestFindEasyroms(t *testing.T) {
	f := newFake()
	s, _ := newSetup(t, f)
	write(t, s.EasyromsPart, "")
	write(t, s.Mounts, "/dev/block/vold/public:179,65 "+s.MediaRW+"/OTHER vfat rw 0 0\n")
	s.FindEasyroms(0)
	if s.easyroms != "" || !s.EasyromsLate() {
		t.Fatal("found the wrong card")
	}
	must(t, os.MkdirAll(s.MediaRW+"/1234-ABCD", 0o755))
	write(t, s.Mounts, "/dev/block/vold/public:179,7 "+s.MediaRW+"/1234-ABCD exfat rw 0 0\n")
	s.FindEasyroms(0)
	if s.easyroms != s.MediaRW+"/1234-ABCD" || s.EasyromsLate() {
		t.Fatalf("easyroms %q", s.easyroms)
	}
	must(t, os.Remove(s.EasyromsPart))
	s.FindEasyroms(0)
	if !s.noroms || s.easyroms != "" {
		t.Fatal(".noroms not seen")
	}
}

func TestFindEasyromsProperties(t *testing.T) {
	f := newFake()
	s, root := newSetup(t, f)
	// Another andr36oid card has the old id. The data card is in slot 2.
	write(t, s.EasyromsPart, "")
	part := root + "/sys/mmcblk1p7"
	write(t, part, "")
	for _, uuid := range []string{"OTHER", "DATA"} {
		must(t, os.MkdirAll(s.MediaRW+"/"+uuid, 0o755))
	}
	write(t, s.Mounts, "/dev/block/vold/public:179,7 "+s.MediaRW+"/OTHER exfat rw 0 0\n"+
		"/dev/block/vold/public:179,103 "+s.MediaRW+"/DATA exfat rw 0 0\n")
	f.props["sys.andr36oid.easyroms.id"] = "public:179,103"
	f.props["sys.andr36oid.easyroms.part"] = part
	f.props["sys.andr36oid.easyroms.ready"] = "1"
	must(t, os.Remove(s.EasyromsPart))
	s.FindEasyroms(0)
	if s.easyroms != s.MediaRW+"/DATA" || s.noroms || s.EasyromsLate() {
		t.Fatalf("wrong card: %q, noroms=%v, late=%v", s.easyroms, s.noroms, s.EasyromsLate())
	}
	// The selected card is still being checked; never fall back to the other one.
	write(t, s.Mounts, "/dev/block/vold/public:179,7 "+s.MediaRW+"/OTHER exfat rw 0 0\n")
	s.FindEasyroms(0)
	if s.easyroms != "" || s.noroms || !s.EasyromsLate() {
		t.Fatal("selected card not mounted: didn't wait for it")
	}
	// Intentional absence also wins over an existing legacy partition and mount.
	write(t, s.EasyromsPart, "")
	delete(f.props, "sys.andr36oid.easyroms.id")
	delete(f.props, "sys.andr36oid.easyroms.part")
	s.FindEasyroms(0)
	if !s.noroms || s.easyroms != "" || s.EasyromsLate() || s.easyromsMounted() != "" {
		t.Fatal("empty published EASYROMS used the legacy card")
	}
}

// tarGzFile copies one member of a .tar.gz to w.
func tarGzFile(archive, member string, w io.Writer) error {
	f, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer f.Close()
	zr, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	tr := tar.NewReader(zr)
	for {
		h, err := tr.Next()
		if errors.Is(err, io.EOF) {
			return fmt.Errorf("%s not in %s", member, archive)
		}
		if err != nil {
			return err
		}
		if strings.TrimPrefix(h.Name, "./") == member && h.Typeflag == tar.TypeReg {
			_, err := io.Copy(w, tr)
			return err
		}
	}
}
