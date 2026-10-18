package setup

import (
	"encoding/binary"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

func sprintf(format string, args ...any) string { return fmt.Sprintf(format, args...) }

// dtU32 is a one-cell device tree property as a number.
func dtU32(path string) (uint32, bool) {
	b, err := os.ReadFile(path)
	if err != nil || len(b) < 4 {
		return 0, false
	}
	return binary.BigEndian.Uint32(b[:4]), true
}

// panelHz is the screen's real refresh rate, from the display timing the kernel uses (the
// DTB's native mode): pixel clock / (htotal * vtotal). The clock counts in whole kHz, like
// DRM does. "" if there is no such timing. Six decimals, like RetroArch writes it.
func (s *Setup) panelHz() string {
	timings, _ := filepath.Glob(s.DTRoot + "/dsi@*/panel@*/display-timings")
	sort.Strings(timings)
	for _, t := range timings {
		if !sys.IsDir(t) {
			continue
		}
		native, hasNative := dtU32(t + "/native-mode")
		ents, _ := os.ReadDir(t)
		var first, use string
		for _, e := range ents {
			n := t + "/" + e.Name()
			if !sys.IsFile(n + "/clock-frequency") {
				continue
			}
			if first == "" {
				first = n
			}
			if ph, ok := dtU32(n + "/phandle"); hasNative && ok && ph == native {
				use = n
				break
			}
		}
		// without native-mode the kernel takes the first timing
		if use == "" {
			use = first
		}
		if use == "" {
			continue
		}
		clk, _ := dtU32(use + "/clock-frequency")
		var ht, vt uint64
		for _, p := range []string{"hactive", "hfront-porch", "hsync-len", "hback-porch"} {
			v, _ := dtU32(use + "/" + p)
			ht += uint64(v)
		}
		for _, p := range []string{"vactive", "vfront-porch", "vsync-len", "vback-porch"} {
			v, _ := dtU32(use + "/" + p)
			vt += uint64(v)
		}
		if clk > 0 && ht > 0 && vt > 0 {
			hz := float64(clk/1000) * 1000 / float64(ht*vt)
			return strconv.FormatFloat(hz, 'f', 6, 64)
		}
	}
	return ""
}

// num reads a number like awk does: what isn't one counts as 0.
func num(s string) float64 {
	f, err := strconv.ParseFloat(strings.TrimSpace(s), 64)
	if err != nil {
		return 0
	}
	return f
}

// sameHz: both set and closer than 0.001 Hz (RetroArch stores a float and writes it back
// with 6 decimals).
func sameHz(a, b string) bool {
	return a != "" && b != "" && math.Abs(num(a)-num(b)) < 0.001
}

// RefreshRate tells RetroArch the screen's real refresh rate. RetroArch assumes 60 Hz on
// Android; with the real rate it resamples the sound so games run exactly one frame per
// screen refresh. Set when unset, when RetroArch's default 60 is still there and we never
// set it, or when it is still what we wrote last time (another panel's DTB). A rate the
// user set is kept.
func (s *Setup) RefreshRate() {
	cfg := s.raCfg()
	if !sys.IsFile(cfg) {
		return
	}
	mark := s.state("retroarch-refresh-rate")
	want := s.panelHz()
	if want == "" {
		s.Log("no panel timing found, RetroArch's refresh rate left as it is")
		return
	}
	cur := cfgGet(cfg, "video_refresh_rate")
	if sameHz(cur, want) {
		_ = fsx.WriteLine(mark, want)
		return
	}
	if cur != "" {
		if sys.IsFile(mark) {
			if !sameHz(cur, fsx.ReadTrim(mark)) {
				return
			}
		} else if !sameHz(cur, "60") {
			return
		}
	}
	if err := s.cfgSet(cfg, "video_refresh_rate", want); err != nil {
		s.Log("writing retroarch.cfg failed")
		return
	}
	_ = fsx.WriteLine(mark, want)
	s.Log("RetroArch's refresh rate is %s Hz now (was: %s)", want, orUnset(cur))
}

// systemDirOurs: system_directory is unset, or what we wrote last time. RetroArch's own
// default only counts until we have set the folder once: after that, the default is a
// folder the user picked on purpose.
func (s *Setup) systemDirOurs(cur string) bool {
	if cur == "" {
		return true
	}
	mark := s.state("retroarch-system-dir")
	if sys.IsFile(mark) {
		return cur == fsx.ReadTrim(mark)
	}
	switch cur {
	case "default", "/storage/emulated/0/RetroArch/system", "/sdcard/RetroArch/system",
		raExtPublic + "/system", s.raData() + "/system":
		return true
	}
	return false
}

// biosName is the name of dir's bios folder in any case (BIOS, Bios), else "bios".
func biosName(dir string) string {
	ents, _ := os.ReadDir(dir)
	names := make([]string, 0, len(ents))
	for _, e := range ents {
		if strings.HasPrefix(e.Name(), ".") {
			continue
		}
		if sys.IsDir(dir + "/" + e.Name()) {
			names = append(names, e.Name())
		}
	}
	sort.Strings(names)
	for _, n := range names {
		if strings.ToLower(n) == "bios" {
			return n
		}
	}
	return "bios"
}

// BIOSFolder points RetroArch's system_directory (where the cores look for BIOS files) at
// the bios folder on EASYROMS, or on the internal storage for a card without EASYROMS.
// Updated when that changes (a new card has another UUID, so another path), unless the
// user set a folder.
func (s *Setup) BIOSFolder() {
	cfg := s.raCfg()
	if !sys.IsFile(cfg) {
		return
	}
	var dir, want string
	switch {
	case s.noroms:
		name := biosName(s.Media)
		dir = s.Media + "/" + name
		want = "/storage/emulated/0/" + name
	case s.easyroms != "":
		name := biosName(s.easyroms)
		dir = s.easyroms + "/" + name
		// RetroArch sees EASYROMS at /storage/<uuid>, root at /mnt/media_rw/<uuid>
		want = "/storage/" + baseOf(s.easyroms) + "/" + name
	default:
		// no bogus path: leave it and try again next start
		s.Log("EASYROMS not mounted, RetroArch's BIOS folder left as it is")
		return
	}
	cur := cfgGet(cfg, "system_directory")
	if cur != want {
		if !s.systemDirOurs(cur) {
			return
		}
		if !sys.IsDir(dir) {
			if err := os.Mkdir(dir, 0o770); err != nil {
				s.Log("couldn't make %s", dir)
				return
			}
			// on the internal storage: owner and mode like its parent, as if the user had
			// made it (a no-op on exFAT)
			if o, err := fsx.OwnerOf(dirOf(dir)); err == nil {
				_ = fsx.Chown(dir, o)
			}
			if m, err := fsx.ModeOf(dirOf(dir)); err == nil {
				_ = os.Chmod(dir, m)
			}
			s.restorecon(false, dir)
		}
		if err := s.cfgSet(cfg, "system_directory", want); err != nil {
			s.Log("writing retroarch.cfg failed")
			return
		}
		s.Log("RetroArch reads BIOS files from %s now (was: %s)", want, orUnset(cur))
	}
	_ = fsx.WriteLine(s.state("retroarch-system-dir"), want)
}

// RetroArch's cheat files from libretro-database, one folder per system. /data needs this
// much free space for them, in KB (about 130 MB used once unpacked).
const cheatsMinFreeKB = 400000

// cheatsOurs: we may point cheat_database_path at our folder: unset, "default", ours
// already, or the folder RetroArch picks when opened from its icon while that folder has
// no cheat files in it.
func (s *Setup) cheatsOurs(cur string) bool {
	switch cur {
	case "", "default", raExtPublic + "/cheats":
		return true
	case "/storage/emulated/0/RetroArch/cheats", "/sdcard/RetroArch/cheats":
		has := false
		_ = filepath.WalkDir(s.Media+"/RetroArch/cheats", func(p string, d os.DirEntry, err error) error {
			if err == nil && d.Type().IsRegular() {
				has = true
				return filepath.SkipAll
			}
			return nil
		})
		return !has
	}
	return false
}

// cheatsMerge moves the unpacked files into the cheat folder, over our older copies;
// everything else in it stays.
func cheatsMerge(from, to string) error {
	ents, err := os.ReadDir(from)
	if err != nil {
		return err
	}
	for _, e := range ents {
		src := from + "/" + e.Name()
		dst := to + "/" + e.Name()
		if e.IsDir() && sys.IsDir(dst) {
			if err := cheatsMerge(src, dst); err != nil {
				return err
			}
			continue
		}
		if sys.IsDir(dst) {
			if err := os.RemoveAll(dst); err != nil {
				return err
			}
		}
		if err := os.Rename(src, dst); err != nil {
			return err
		}
	}
	return nil
}

// cheatsEstimate is the cheat archive's size while it was never unpacked for this
// RetroArch install, for the first plan (no hashing: that reads the whole archive).
func (s *Setup) cheatsEstimate() int64 {
	st, err := os.Stat(s.Base + "/libretro/cheats.tar.gz")
	if err != nil || sys.Exists(s.raOwn()+"/cheats.sha1") {
		return 0
	}
	return st.Size()
}

// SeedCheats: RetroArch's online updater can't fetch cheats on an offline console, so the
// Cheats menu would be empty. Points cheat_database_path at
// Android/data/com.retroarch/files/cheats, unless the user picked another folder, and
// unpacks the cheat files there, once per archive. Files the user added there stay. The
// archive is unpacked into cheats.new first and moved in only when that worked, and the
// path is set only then: a full disk or a power cut leaves no half folder RetroArch
// points at.
func (s *Setup) SeedCheats() {
	archive := s.Base + "/libretro/cheats.tar.gz"
	cfg := s.raCfg()
	if !sys.IsFile(archive) || !sys.IsDir(s.raData()) || !sys.IsFile(cfg) {
		return
	}
	dir := s.raExt() + "/cheats"
	want := raExtPublic + "/cheats"
	stamp := s.raOwn() + "/cheats.sha1"
	tmp := s.raExt() + "/cheats.new"
	cur := cfgGet(cfg, "cheat_database_path")
	// a folder the user set: theirs, and no cheats unpacked
	if cur != want && !s.cheatsOurs(cur) {
		return
	}
	// a run cut off while unpacking
	_ = os.RemoveAll(tmp)

	sum, err := fsx.SHA1(archive)
	if err != nil {
		return
	}
	if fsx.ReadTrim(stamp) != sum {
		free, err := s.freeKB(s.raExt())
		if err != nil || free < cheatsMinFreeKB {
			freeText := "?"
			if err == nil {
				freeText = strconv.FormatInt(free, 10)
			}
			s.Log("only %s KB free, cheat files not unpacked", freeText)
			return
		}
		owner, err := fsx.OwnerOf(s.raData())
		if err != nil {
			return
		}
		if err := os.Mkdir(tmp, 0o770); err != nil {
			s.Log("making %s failed", tmp)
			return
		}
		_ = os.MkdirAll(dirOf(stamp), 0o700)
		if st, err := os.Stat(archive); err == nil {
			s.Progress.Plan("cheats", st.Size(), 0)
		}
		s.Progress.Begin()
		err = extractTarGz(archive, tmp, s.Progress.Advance)
		s.Progress.Finish(0, false)
		s.Progress.Done("cheats")
		if err != nil {
			_ = os.RemoveAll(tmp)
			s.Log("unpacking the cheat files failed")
			return
		}
		if !sys.IsDir(dir) {
			// a new folder: the whole unpacked one
			if err := os.Rename(tmp, dir); err != nil {
				_ = os.RemoveAll(tmp)
				s.Log("moving the cheat files failed")
				return
			}
		} else if err := cheatsMerge(tmp, dir); err != nil {
			_ = os.RemoveAll(tmp)
			s.Log("moving the cheat files failed, tried again next start")
			return
		}
		_ = os.RemoveAll(tmp)
		_ = fsx.ChownR(dir, extOwner(owner))
		_ = fsx.ChmodTree(dir, extDirMode, 0o660)
		s.restorecon(true, dir)
		s.fixupAppDir(want)
		_ = fsx.WriteLine(stamp, sum)
		_ = fsx.Chown(dirOf(stamp), owner)
		_ = fsx.Chown(stamp, owner)
		s.Log("unpacked the cheat files into %s", want)
	}
	if cur != want {
		if err := s.cfgSet(cfg, "cheat_database_path", want); err != nil {
			s.Log("writing retroarch.cfg failed")
			return
		}
		s.Log("RetroArch's cheat folder is %s now (was: %s)", want, orUnset(cur))
	}
}
