package setup

import (
	"encoding/binary"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

func inode(t *testing.T, p string) uint64 {
	st, err := os.Stat(p)
	must(t, err)
	return st.Sys().(*syscall.Stat_t).Ino
}

func TestCfg(t *testing.T) {
	f := newFake()
	s, root := newSetup(t, f)
	cfg := root + "/retroarch.cfg"
	write(t, cfg, "a = \"1\"\nvideo_refresh_rate = 60.000000\n  b=\"x y\"\nweird = \"has\"quote\"\na = \"2\"\n")
	if cfgGet(cfg, "a") != "2" || cfgGet(cfg, "video_refresh_rate") != "60.000000" || cfgGet(cfg, "b") != "x y" ||
		cfgGet(cfg, "weird") != "" || cfgGet(cfg, "none") != "" {
		t.Fatal("cfgGet")
	}
	ino := inode(t, cfg)
	must(t, s.cfgSet(cfg, "a", "3"))
	if inode(t, cfg) != ino {
		t.Fatal("cfgSet replaced the file (owner, mode and ACL would be lost)")
	}
	if got := read(t, cfg); got != "video_refresh_rate = 60.000000\n  b=\"x y\"\nweird = \"has\"quote\"\na = \"3\"\n" {
		t.Fatalf("cfg:\n%s", got)
	}
	if sys_exists(cfg + ".bak") {
		t.Fatal(".bak left")
	}
	// a key that is a prefix of another stays apart
	must(t, s.cfgSet(cfg, "b", "z"))
	if cfgGet(cfg, "weird") != "" || cfgGet(cfg, "b") != "z" || !strings.Contains(read(t, cfg), "weird") {
		t.Fatal("prefix key")
	}
}

func TestManaged(t *testing.T) {
	f := newFake()
	s, root := newSetup(t, f)
	cfg := root + "/retroarch.cfg"
	write(t, cfg, "menu_swap_ok_cancel_buttons = \"true\"\nall_users_control_menu = \"mine\"\n")
	s.managed(cfg, "menu_swap_ok_cancel_buttons", "false", false, "true")
	s.managed(cfg, "all_users_control_menu", "true", false, "false")
	s.managed(cfg, "input_quit_gamepad_combo", "4", false, "0")
	if cfgGet(cfg, "menu_swap_ok_cancel_buttons") != "false" || cfgGet(cfg, "all_users_control_menu") != "mine" ||
		cfgGet(cfg, "input_quit_gamepad_combo") != "4" {
		t.Fatalf("first:\n%s", read(t, cfg))
	}
	// the user changes ours: kept; force overrides
	must(t, s.cfgSet(cfg, "input_quit_gamepad_combo", "2"))
	s.managed(cfg, "input_quit_gamepad_combo", "4", false, "0")
	if cfgGet(cfg, "input_quit_gamepad_combo") != "2" {
		t.Fatal("user's value overwritten")
	}
	s.managed(cfg, "input_quit_gamepad_combo", "4", true, "0")
	if cfgGet(cfg, "input_quit_gamepad_combo") != "4" {
		t.Fatal("force ignored")
	}
}

func TestPlayers(t *testing.T) {
	f := newFake()
	s, _ := newSetup(t, f)
	cfg := s.raCfg()
	write(t, cfg, "\n")
	s.RetroArchPlayers(false)
	if cfgGet(cfg, "input_player1_reserved_device") != builtinPad || cfgGet(cfg, "input_player1_device_reservation_type") != "2" {
		t.Fatalf("built-in first:\n%s", read(t, cfg))
	}
	f.props["persist.andr36oid.ra_player1"] = "external"
	s.RetroArchPlayers(true)
	if cfgGet(cfg, "input_player2_reserved_device") != builtinPad || cfgGet(cfg, "input_player1_device_reservation_type") != "0" {
		t.Fatalf("external first:\n%s", read(t, cfg))
	}
}

func TestHotkeys(t *testing.T) {
	f := newFake()
	s, _ := newSetup(t, f)
	cfg := s.raCfg()
	write(t, cfg, "input_toggle_fast_forward_btn = \"106\"\n")
	f.settings["global andr36oid_missing_buttons"] = "l3,r3"
	s.RAHotkeys()
	if cfgGet(cfg, "input_toggle_fast_forward_btn") != "105" {
		t.Fatal("no L3: not R2")
	}
	f.settings["global andr36oid_missing_buttons"] = "l3,r3,l2,r2"
	s.RAHotkeys()
	if cfgGet(cfg, "input_toggle_fast_forward_btn") != "22" {
		t.Fatal("no L3, no R2: not Right")
	}
	f.settings["global andr36oid_missing_buttons"] = ""
	s.RAHotkeys()
	if cfgGet(cfg, "input_toggle_fast_forward_btn") != "106" {
		t.Fatal("back to L3")
	}
	// unset: the device tree's hint
	delete(f.settings, "global andr36oid_missing_buttons")
	write(t, s.DTRoot+"/odroidgo3-joypad/andr36oid,missing-buttons", "l3\x00r3\x00")
	s.RAHotkeys()
	if cfgGet(cfg, "input_toggle_fast_forward_btn") != "105" {
		t.Fatal("DT hint ignored")
	}
}

func dtWrite(t *testing.T, p string, v uint32) {
	b := make([]byte, 4)
	binary.BigEndian.PutUint32(b, v)
	write(t, p, string(b))
}

func TestRefreshRate(t *testing.T) {
	f := newFake()
	s, _ := newSetup(t, f)
	timing := s.DTRoot + "/dsi@ff450000/panel@0/display-timings"
	mode := func(name string, clk, ph uint32) {
		m := timing + "/" + name
		dtWrite(t, m+"/clock-frequency", clk)
		for p, v := range map[string]uint32{"hactive": 640, "hfront-porch": 40, "hsync-len": 2, "hback-porch": 38,
			"vactive": 480, "vfront-porch": 18, "vsync-len": 2, "vback-porch": 20} {
			dtWrite(t, m+"/"+p, v)
		}
		dtWrite(t, m+"/phandle", ph)
	}
	mode("timing0", 24000000, 1)
	mode("timing1", 30000000, 2)
	dtWrite(t, timing+"/native-mode", 2)
	// 30000 kHz / (720 * 520)
	if hz := s.panelHz(); hz != "80.128205" {
		t.Fatalf("hz %s", hz)
	}
	cfg := s.raCfg()
	write(t, cfg, "video_refresh_rate = \"60.000000\"\n")
	s.RefreshRate()
	if cfgGet(cfg, "video_refresh_rate") != "80.128205" {
		t.Fatal("60 not replaced")
	}
	// another panel's DTB: ours is updated
	dtWrite(t, timing+"/native-mode", 1)
	s.RefreshRate()
	if cfgGet(cfg, "video_refresh_rate") != "64.102564" {
		t.Fatalf("not updated: %s", cfgGet(cfg, "video_refresh_rate"))
	}
	// the user's own rate stays
	must(t, s.cfgSet(cfg, "video_refresh_rate", "59.5"))
	dtWrite(t, timing+"/native-mode", 2)
	s.RefreshRate()
	if cfgGet(cfg, "video_refresh_rate") != "59.5" {
		t.Fatal("user's rate overwritten")
	}
}

func TestBIOSFolder(t *testing.T) {
	f := newFake()
	s, _ := newSetup(t, f)
	cfg := s.raCfg()
	write(t, cfg, "system_directory = \"default\"\n")
	s.easyroms = s.MediaRW + "/AAAA-1111"
	must(t, os.MkdirAll(s.easyroms+"/BIOS", 0o755))
	s.BIOSFolder()
	if cfgGet(cfg, "system_directory") != "/storage/AAAA-1111/BIOS" {
		t.Fatalf("got %s", cfgGet(cfg, "system_directory"))
	}
	// a new card: follows it
	s.easyroms = s.MediaRW + "/BBBB-2222"
	must(t, os.MkdirAll(s.easyroms, 0o755))
	s.BIOSFolder()
	if cfgGet(cfg, "system_directory") != "/storage/BBBB-2222/bios" || !sys_exists(s.easyroms+"/bios") {
		t.Fatalf("got %s", cfgGet(cfg, "system_directory"))
	}
	// the user's own folder stays
	must(t, s.cfgSet(cfg, "system_directory", "/storage/emulated/0/mybios"))
	s.easyroms = s.MediaRW + "/AAAA-1111"
	s.BIOSFolder()
	if cfgGet(cfg, "system_directory") != "/storage/emulated/0/mybios" {
		t.Fatal("user's folder overwritten")
	}
	// not mounted: left alone
	s.easyroms = ""
	s.BIOSFolder()
	if !logHas(s, "EASYROMS not mounted") {
		t.Fatal("no log")
	}
}

func TestSeedOwnedManifest(t *testing.T) {
	f := newFake()
	s, root := newSetup(t, f)
	must(t, os.MkdirAll(s.raOwn(), 0o700))
	src := root + "/src.cfg"
	dest := root + "/dest/pad.cfg"
	must(t, os.MkdirAll(dirOf(dest), 0o755))
	write(t, src, "v1\n")
	s.seedOwned(src, dest)
	write(t, src, "v2\n")
	s.seedOwned(src, dest)
	if read(t, dest) != "v2\n" {
		t.Fatal("our file not updated")
	}
	write(t, dest, "mine\n")
	write(t, src, "v3\n")
	s.seedOwned(src, dest)
	if read(t, dest) != "mine\n" || !logHas(s, "keeping user provided pad.cfg") {
		t.Fatal("user's file overwritten")
	}
}

func TestProgressETA(t *testing.T) {
	f := newFake()
	s, _ := newSetup(t, f)
	now := time.Unix(1000, 0)
	s.Now = func() time.Time { return now }
	p := s.Progress
	p.Enabled = true
	if p.ETA() != 0 {
		t.Fatal("nothing planned: not 0")
	}
	p.Plan("apps", 100e6, 4)
	if p.ETA() != -1 {
		t.Fatal("no rate yet: not unknown")
	}
	p.CardSpeed(20)
	if eta := p.ETA(); eta != 21 {
		t.Fatalf("from card speed: %d", eta)
	}
	p.Begin()
	now = now.Add(10 * time.Second)
	p.Finish(25e6, true)
	// 25 MB in 10 s, 75 MB left: 30 s
	if eta := p.ETA(); eta != 31 {
		t.Fatalf("measured: %d", eta)
	}
	// the step knows better: 50 MB left, the estimate goes
	p.Plan("apps", 50e6, 2)
	if eta := p.ETA(); eta != 21 {
		t.Fatalf("replanned: %d", eta)
	}
	p.Done("apps")
	if p.ETA() != 0 {
		t.Fatal("done: not 0")
	}
	stats := f.props["sys.andr36oid.setup_stats"]
	if !strings.HasPrefix(stats, "card=20.0 rd=0.0 wrs=0.0 wr=0 apps=1/3 t=10 free=") || len(stats) >= 92 {
		t.Fatalf("stats %q", stats)
	}
	if f.props["sys.andr36oid.setup_pct"] != "100" {
		t.Fatalf("pct %s", f.props["sys.andr36oid.setup_pct"])
	}
}

// The live read and write speed comes from the card's sector counters, so it moves while pm
// reads the APKs, not only for what the setup writes itself.
func TestProgressLiveIO(t *testing.T) {
	dir := t.TempDir()
	stat := filepath.Join(dir, "stat")
	write := func(rd, wr int) {
		line := fmt.Sprintf("  100 0 %d 50 20 0 %d 40 0 90 90\n", rd, wr)
		if err := os.WriteFile(stat, []byte(line), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	now := time.Unix(1000, 0)
	s := &Setup{Paths: Paths{BlockStat: stat}, Now: func() time.Time { return now }}
	p := &Progress{s: s, Enabled: true}
	write(0, 0)
	p.mu.Lock()
	p.sampleIOLocked(now)
	p.mu.Unlock()
	// 2 s later: 40000 sectors read (20.48 MB), 10000 written (5.12 MB)
	now = now.Add(2 * time.Second)
	write(40000, 10000)
	p.mu.Lock()
	p.sampleIOLocked(now)
	rd, wr := p.rdMBs, p.wrMBs
	p.mu.Unlock()
	if fmt.Sprintf("%.2f %.2f", rd, wr) != "10.24 2.56" {
		t.Fatalf("rd %.2f wr %.2f", rd, wr)
	}
	// no file (an emulator, a USB boot): stays at the last value, no panic
	s.BlockStat = filepath.Join(dir, "none")
	p.mu.Lock()
	p.sampleIOLocked(now.Add(time.Second))
	p.mu.Unlock()
}
