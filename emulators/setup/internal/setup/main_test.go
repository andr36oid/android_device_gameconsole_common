package setup

import (
	"archive/tar"
	"compress/gzip"
	"errors"
	"os"
	"strings"
	"testing"
)

func makeTarGz(t *testing.T, path string, files map[string]string) {
	t.Helper()
	f, err := os.Create(path)
	must(t, err)
	zw := gzip.NewWriter(f)
	tw := tar.NewWriter(zw)
	for name, body := range files {
		must(t, tw.WriteHeader(&tar.Header{Name: name, Mode: 0o644, Size: int64(len(body)), Typeflag: tar.TypeReg}))
		_, err := tw.Write([]byte(body))
		must(t, err)
	}
	must(t, tw.Close())
	must(t, zw.Close())
	must(t, f.Close())
}

// A whole first start on a fake console, then a second one that finds nothing to do.
func TestMainTwice(t *testing.T) {
	f := newFake()
	s, root := newSetup(t, f)
	s.FreeKB = func(string) (int64, error) { return 10 << 20, nil }
	for _, d := range []string{"retroarch", "ppsspp"} {
		must(t, os.Symlink(emu+"/seed/"+d, s.Base+"/"+d))
	}
	must(t, os.Symlink(emu+"/seed/rom-folders.txt", s.Base+"/rom-folders.txt"))
	must(t, os.MkdirAll(s.Base+"/libretro", 0o755))
	makeTarGz(t, s.Base+"/libretro/cores-a.tar.gz", map[string]string{"cores/nestopia_libretro_android.so": "core"})
	makeTarGz(t, s.Base+"/libretro/cheats.tar.gz", map[string]string{"Nintendo - NES/Game.cht": "cheat"})
	write(t, s.Apps+"/RetroArch.apk", strings.Repeat("r", 1000))
	write(t, s.Apps+"/RetroArch.yml", "package: com.retroarch\nversion_code: 10\nrequired: true\n")
	write(t, s.Apps+"/Extra.apk", strings.Repeat("e", 1000))
	write(t, s.Apps+"/Extra.yml", "package: org.extra\nversion_code: 1\n")
	f.settings["global andr36oid_apps_chosen"] = "1"
	must(t, os.MkdirAll(s.Media, 0o771))
	write(t, s.EasyromsPart, "")
	must(t, os.MkdirAll(s.MediaRW+"/ABCD-1234", 0o755))
	write(t, s.Mounts, "/dev/block/vold/public:179,7 "+s.MediaRW+"/ABCD-1234 exfat rw 0 0\n")

	installed := map[string]string{}
	f.handlers["pm"] = func(a []string) (string, error) {
		switch a[0] {
		case "list":
			if vc, ok := installed[a[3]]; ok {
				return "package:" + a[3] + " versionCode:" + vc, nil
			}
			return "", nil
		case "install":
			if strings.HasSuffix(a[len(a)-1], "RetroArch.apk") {
				installed["com.retroarch"] = "10"
				must(t, os.MkdirAll(s.raData(), 0o700))
			}
			if strings.HasSuffix(a[len(a)-1], "Extra.apk") {
				installed["org.extra"] = "1"
			}
			return "Success", nil
		}
		return "", nil
	}
	f.handlers["content"] = func(a []string) (string, error) {
		if a[0] == "query" {
			return "No result found.", nil
		}
		return "", nil
	}
	s.Prepare()
	if !s.Progress.Enabled {
		t.Fatal("progress off on a first start")
	}
	s.Main()

	cfg := s.raCfg()
	checks := map[string]bool{
		"both apps installed":    installed["org.extra"] == "1" && installed["com.retroarch"] == "10",
		"retroarch.cfg seeded":   sys_exists(cfg),
		"core unpacked":          read(t, s.raData()+"/cores/nestopia_libretro_android.so") == "core",
		"cheats unpacked":        sys_exists(s.raExt() + "/cheats/Nintendo - NES/Game.cht"),
		"cheat path set":         cfgGet(cfg, "cheat_database_path") == raExtPublic+"/cheats",
		"bios path set":          cfgGet(cfg, "system_directory") == "/storage/ABCD-1234/bios",
		"rom folders made":       sys_exists(s.MediaRW + "/ABCD-1234/README-andr36oid.txt"),
		"apps done":              sys_exists(s.AppsDoneFile),
		"cards released":         f.props["persist.andr36oid.setup_done"] == "1",
		"guide told":             f.settings["global andr36oid_daijishou_ready"] == "1",
		"browser cmdline":        strings.Contains(read(t, s.ChromeCmdline), "RestrictGamepadAccess"),
		"progress at 100":        f.props["sys.andr36oid.setup_pct"] == "100" && f.props["sys.andr36oid.setup_eta"] == "0",
		"stats with both apps":   strings.Contains(f.props["sys.andr36oid.setup_stats"], "apps=2/2"),
		"stage ends with cheats": f.props["sys.andr36oid.setup_stage"] == "cheats",
	}
	for name, ok := range checks {
		if !ok {
			t.Errorf("%s\nlog:\n  %s", name, strings.Join(s.Lines, "\n  "))
		}
	}

	// the second start: no install, no write to retroarch.cfg
	before := read(t, cfg)
	f.reset()
	s.Lines = nil
	s.Main()
	if n := f.count("pm install"); n != 0 {
		t.Errorf("second start installed %d apps", n)
	}
	if read(t, cfg) != before {
		t.Error("second start changed retroarch.cfg")
	}
	for _, l := range s.Lines {
		// no panel in the fake device tree: said every start, like the script did
		if !strings.HasPrefix(l, "no panel timing found") {
			t.Errorf("second start logged: %s", l)
		}
	}
	_ = root
}

// The setup screen shows nothing once the setup was done.
func TestProgressOffAfterSetup(t *testing.T) {
	f := newFake()
	s, _ := newSetup(t, f)
	f.props["persist.andr36oid.setup_done"] = "1"
	s.Prepare()
	s.Progress.Stage("apps")
	if len(f.propLog) != 1 { // only the one Set above... none from Stage
		for _, l := range f.propLog {
			if strings.HasPrefix(l, "sys.andr36oid.setup_") {
				t.Fatalf("progress written: %v", f.propLog)
			}
		}
	}
	_ = errors.New
}
