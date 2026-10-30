package setup

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func raSyncWrite(t *testing.T, path, text string, seconds int64) {
	t.Helper()
	write(t, path, text)
	stamp := time.Unix(seconds, 0)
	must(t, os.Chtimes(path, stamp, stamp))
}

func TestRetroArchSync(t *testing.T) {
	f := newFake()
	s, _ := newSetup(t, f)
	f.props["persist.andr36oid.sync.retroarch"] = "1"
	dir := s.BrokerState + "/images/123/upper/home/ark/.config/retroarch"
	write(t, s.BrokerState+"/retroarch-sync-dir", dir+"\n")
	a, b := s.raExt()+"/retroarch-core-options.cfg", dir+"/retroarch-core-options.cfg"
	raSyncWrite(t, a, "# Android options\nsame = \"android\"\nandroid_only = \"yes\"\ninput_player1_a_btn = \"96\"\n", 10)
	raSyncWrite(t, b, "# Linux options\nsame = \"linux\"\nlinux_only = \"yes\"\ninput_player1_a_btn = \"0\"\n", 20)
	ac, bc := s.raCfg(), dir+"/retroarch.cfg"
	raSyncWrite(t, ac, "# Android\ninput_driver = \"android\"\ninput_player1_a_btn = \"96\"\nvideo_smooth = \"true\"\nvideo_shader_enable = \"true\"\nmenu_driver = \"rgui\"\naspect_ratio_index = \"22\"\naudio_driver = \"opensl\"\nshader_directory = \"/android/shaders\"\n", 10)
	raSyncWrite(t, bc, "# Linux\ninput_driver = \"udev\"\ninput_player1_a_btn = \"0\"\nvideo_smooth = \"false\"\nvideo_shader_enable = \"false\"\nmenu_driver = \"ozone\"\naspect_ratio_index = \"0\"\naudio_driver = \"alsa\"\nshader_directory = \"/linux/shaders\"\n", 20)
	pad := dir + "/autoconfig/udev/pad.cfg"
	write(t, pad, "input_a_btn = \"0\"\n")
	s.SyncRetroArch()
	for _, path := range []string{a, b} {
		if cfgGet(path, "same") != "linux" || cfgGet(path, "android_only") != "yes" || cfgGet(path, "linux_only") != "yes" {
			t.Fatalf("core options were not merged: %s", read(t, path))
		}
	}
	if !strings.HasPrefix(read(t, a), "# Android options\n") || !strings.HasPrefix(read(t, b), "# Linux options\n") {
		t.Fatal("lost config comments")
	}
	for _, path := range []string{ac, bc} {
		if cfgGet(path, "video_smooth") != "false" || cfgGet(path, "video_shader_enable") != "false" || cfgGet(path, "menu_driver") != "ozone" || cfgGet(path, "aspect_ratio_index") != "0" {
			t.Fatalf("allowlisted settings were not merged: %s", read(t, path))
		}
	}
	for path, want := range map[string]string{a: "96", ac: "96", b: "0", bc: "0"} {
		if cfgGet(path, "input_player1_a_btn") != want {
			t.Fatalf("button mapping changed: %s", path)
		}
	}
	if cfgGet(ac, "input_driver") != "android" || cfgGet(bc, "input_driver") != "udev" || cfgGet(ac, "audio_driver") != "opensl" || cfgGet(bc, "audio_driver") != "alsa" || cfgGet(ac, "shader_directory") != "/android/shaders" || read(t, pad) != "input_a_btn = \"0\"\n" {
		t.Fatal("shared a driver, directory or autoconfig mapping")
	}
	states, err := filepath.Glob(s.State + "/retroarch-sync-*.json")
	must(t, err)
	if len(states) != 2 {
		t.Fatalf("missing per-key state: %v", states)
	}
	paths := append([]string{a, b, ac, bc, pad}, states...)
	stamps := map[string]time.Time{}
	for _, path := range paths {
		info, err := os.Stat(path)
		must(t, err)
		stamps[path] = info.ModTime()
	}
	s.SyncRetroArch()
	for _, path := range paths {
		info, err := os.Stat(path)
		must(t, err)
		if info.ModTime() != stamps[path] {
			t.Fatalf("second sync rewrote %s", path)
		}
	}
	// Each side edits a different key; the later file must not win every key.
	raSyncWrite(t, a, strings.ReplaceAll(read(t, a), `same = "linux"`, `same = "android edit"`), 30)
	raSyncWrite(t, b, strings.ReplaceAll(read(t, b), `linux_only = "yes"`, `linux_only = "linux edit"`), 40)
	raSyncWrite(t, ac, strings.ReplaceAll(read(t, ac), `video_smooth = "false"`, `video_smooth = "true"`), 30)
	raSyncWrite(t, bc, strings.ReplaceAll(read(t, bc), `menu_driver = "ozone"`, `menu_driver = "rgui"`), 40)
	s.SyncRetroArch()
	for _, path := range []string{a, b} {
		if cfgGet(path, "same") != "android edit" || cfgGet(path, "linux_only") != "linux edit" || cfgGet(path, "android_only") != "yes" {
			t.Fatalf("per-key history lost an edit: %s", read(t, path))
		}
	}
	for _, path := range []string{ac, bc} {
		if cfgGet(path, "video_smooth") != "true" || cfgGet(path, "menu_driver") != "rgui" {
			t.Fatalf("per-key video/menu history lost an edit: %s", read(t, path))
		}
	}
	// Deleting a key on one side must not delete the other side's user setting.
	raSyncWrite(t, a, strings.ReplaceAll(read(t, a), "android_only = \"yes\"\n", ""), 50)
	s.SyncRetroArch()
	if cfgGet(a, "android_only") != "yes" || cfgGet(b, "android_only") != "yes" {
		t.Fatal("a missing key deleted the union")
	}
}

func TestRetroArchSyncGates(t *testing.T) {
	for _, gate := range []string{"unset", "disabled", "master", "Android running", "Linux running", "ES running", "mounted", "bad marker"} {
		t.Run(gate, func(t *testing.T) {
			f := newFake()
			s, _ := newSetup(t, f)
			a := s.raExt() + "/retroarch-core-options.cfg"
			b := s.SubsystemRoot + "/home/ark/.config/retroarch/retroarch-core-options.cfg"
			write(t, a, "option = \"android\"\n")
			write(t, b, "option = \"linux\"\n")
			f.props["persist.andr36oid.sync.retroarch"] = "1"
			switch gate {
			case "unset":
				delete(f.props, "persist.andr36oid.sync.retroarch")
			case "disabled":
				f.props["persist.andr36oid.sync.retroarch"] = "0"
			case "master":
				f.props["persist.andr36oid.subsystem_autosync"] = "0"
			case "Android running", "Linux running", "ES running":
				f.props["sys.andr36oid.subsystem.session"] = "running"
				f.handlers["pidof"] = func(args []string) (string, error) {
					if (gate == "Android running" && args[0] == raPkg) || (gate == "Linux running" && args[0] == "retroarch") || (gate == "ES running" && args[0] == "emulationstation") {
						return "42", nil
					}
					return "", os.ErrNotExist
				}
			case "mounted":
				write(t, s.Mounts, "overlay "+s.SubsystemRoot+" overlay rw 0 0\n")
			case "bad marker":
				write(t, s.BrokerState+"/retroarch-sync-dir", "/elsewhere/home/ark/.config/retroarch")
			}
			s.SyncRetroArch()
			if read(t, a) != "option = \"android\"\n" || read(t, b) != "option = \"linux\"\n" {
				t.Fatalf("gate %s still shared settings", gate)
			}
		})
	}
}

func TestRetroArchSyncFileSafety(t *testing.T) {
	f := newFake()
	s, _ := newSetup(t, f)
	f.props["persist.andr36oid.sync.retroarch"] = "1"
	a := s.raExt() + "/retroarch-core-options.cfg"
	b := s.SubsystemRoot + "/home/ark/.config/retroarch/retroarch-core-options.cfg"
	write(t, a, "option = \"android\"\ninput_a_btn = \"96\"\n")
	must(t, os.MkdirAll(dirOf(b), 0o755))
	s.SyncRetroArch()
	if read(t, b) != "option = \"android\"\n" {
		t.Fatal("missing options file did not receive the safe union")
	}
	file, err := raSyncRead(a, true)
	must(t, err)
	write(t, a, "option = \"concurrent edit\"\n")
	if err := file.write(map[string]raSyncValue{"option": {Value: `"overwrite"`}}); err == nil || cfgGet(a, "option") != "concurrent edit" {
		t.Fatal("overwrote a concurrent config edit")
	}
	must(t, os.Remove(b))
	must(t, os.Symlink(a, b))
	if _, err := raSyncRead(b, true); err == nil {
		t.Fatal("accepted a config symlink")
	}
}
