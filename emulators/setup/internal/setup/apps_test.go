package setup

import (
	"errors"
	"os"
	"strings"
	"testing"
)

// preparation: the setup screen waits for the library, the apps with their first-start
// settings, and the example music, in either order (was preparation-ready-test.sh)
func TestPreparationReady(t *testing.T) {
	newCase := func() (*Setup, *fake) {
		f := newFake()
		s, _ := newSetup(t, f)
		write(t, s.Apps+"/Example.apk", "apk")
		f.settings["global andr36oid_apps_chosen"] = "1"
		return s, f
	}
	ready := func(f *fake) bool { return f.settings["global andr36oid_daijishou_ready"] == "1" }

	// the library finishes while the app choice and installs are still pending
	s, f := newCase()
	delete(f.settings, "global andr36oid_apps_chosen")
	s.DaijishouReady()
	s.AppsDone()
	if ready(f) {
		t.Fatal("ready while the app page waits")
	}
	f.settings["global andr36oid_apps_chosen"] = "1"
	s.appsLeft = true
	s.AppsDone()
	if ready(f) {
		t.Fatal("ready with apps left")
	}
	s.appsLeft, s.firstrunLeft = false, true
	s.AppsDone()
	if ready(f) {
		t.Fatal("ready with first-start settings left")
	}
	s.firstrunLeft = false
	s.AppsDone()
	if !ready(f) {
		t.Fatal("not ready at the end")
	}

	// apps finish before the library
	s, f = newCase()
	s.AppsDone()
	if !sys_exists(s.AppsDoneFile) || ready(f) {
		t.Fatal("apps first: wrong state")
	}
	s.DaijishouReady()
	if !ready(f) {
		t.Fatal("apps first: not ready after the library")
	}
	// again with apps-done there already
	delete(f.settings, "global andr36oid_daijishou_ready")
	s.AppsDone()
	if !ready(f) {
		t.Fatal("apps-done there: not announced")
	}

	// an empty or reclaimed AUTOAPK doesn't keep the guide waiting
	s, f = newCase()
	must(t, os.Remove(s.Apps+"/Example.apk"))
	s.DaijishouReady()
	if !ready(f) {
		t.Fatal("empty AUTOAPK waits")
	}
	must(t, os.RemoveAll(s.Apps))
	delete(f.settings, "global andr36oid_daijishou_ready")
	s.DaijishouReady()
	if !ready(f) {
		t.Fatal("no AUTOAPK waits")
	}

	// AUTOAPK stays until the example music is in Music; a track that is there is kept
	s, _ = newCase()
	write(t, s.exampleMusic()+"/a.mp3", "new\n")
	write(t, s.exampleMusic()+"/CREDITS.txt", "new\n")
	s.AppsDone()
	if sys_exists(s.AppsDoneFile) {
		t.Fatal("apps-done before the music")
	}
	s.SeedExampleMusic()
	if sys_exists(s.AppsDoneFile) || sys_exists(s.state("example-music.done")) {
		t.Fatal("music done without internal storage")
	}
	write(t, s.Media+"/Music/a.mp3", "mine\n")
	s.SeedExampleMusic()
	if read(t, s.Media+"/Music/a.mp3") != "mine\n" || !sys_exists(s.Media+"/Music/CREDITS.txt") {
		t.Fatal("music copy wrong")
	}
	s.AppsDone()
	if !sys_exists(s.AppsDoneFile) {
		t.Fatal("no apps-done after the music")
	}
}

func sys_exists(p string) bool { _, err := os.Lstat(p); return err == nil }

// progress: stage codes and app names only while the setup runs; the installed and
// failed marks (was preparing-progress-test.sh)
func TestProgressAndInstallMarks(t *testing.T) {
	f := newFake()
	s, _ := newSetup(t, f)
	fail := false
	f.handlers["pm"] = func(a []string) (string, error) {
		if fail {
			return "Failure [INSTALL_FAILED_TEST]", errors.New("exit status 1")
		}
		return "Success", nil
	}
	s.Progress.Stage("apps")
	s.installOne(install{"/fake/game.apk", "x", "1"})
	if len(f.propLog) != 0 {
		t.Fatalf("props while disabled: %v", f.propLog)
	}
	s.Progress.Enabled = true
	s.Progress.Stage("cores:2:3")
	s.installOne(install{"/fake/My Game.apk", "example.game", "1"})
	if !sys_exists(s.state("installed.example.game")) || sys_exists(s.state("failed.example.game")) {
		t.Fatal("install marks wrong")
	}
	fail = true
	s.installOne(install{"/fake/Broken.apk", "example.broken", "2"})
	if read(t, s.state("failed.example.broken")) != "2\n" {
		t.Fatal("failed mark wrong")
	}
	var got []string
	for _, l := range f.propLog {
		if strings.HasPrefix(l, "sys.andr36oid.setup_stage") || strings.HasPrefix(l, "sys.andr36oid.setup_app") {
			got = append(got, l)
		}
	}
	want := []string{
		"sys.andr36oid.setup_stage\tcores:2:3",
		"sys.andr36oid.setup_app\tMy Game",
		"sys.andr36oid.setup_app\t",
		"sys.andr36oid.setup_app\tBroken",
		"sys.andr36oid.setup_app\t",
	}
	if strings.Join(got, "\n") != strings.Join(want, "\n") {
		t.Fatalf("props:\n%s", strings.Join(got, "\n"))
	}
	// a failure while shutting down isn't remembered
	f.props["sys.shutdown.requested"] = "0userrequested"
	s.installOne(install{"/fake/Other.apk", "example.other", "3"})
	if sys_exists(s.state("failed.example.other")) {
		t.Fatal("failure during shutdown kept")
	}
}

// first-start lists: the notification default once, a later user choice kept, a failed
// call tried again (was notification-defaults-test.sh)
func TestNotificationDefaults(t *testing.T) {
	f := newFake()
	s, _ := newSetup(t, f)
	must(t, os.MkdirAll(s.AppData+"/com.looker.droidify", 0o755))
	enabled := ""
	cmdFail := false
	calls := 0
	f.handlers["cmd"] = func(a []string) (string, error) {
		if strings.Join(a, " ") != "notification set_notifications_enabled com.looker.droidify false 0" {
			return "", errors.New("unexpected cmd " + strings.Join(a, " "))
		}
		calls++
		if cmdFail {
			return "", errors.New("exit status 1")
		}
		enabled = "false"
		return "", nil
	}
	list := emu + "/../foss-apps/Droidify/firstrun/droidify.list"
	stamp := s.state("notifications.com.looker.droidify")
	s.firstrunList(list)
	if !sys_exists(stamp) || enabled != "false" || calls != 1 || f.count("appops set --user 0 com.looker.droidify REQUEST_INSTALL_PACKAGES allow") != 1 {
		t.Fatalf("first pass: stamp %v enabled %s calls %d", sys_exists(stamp), enabled, calls)
	}
	enabled = "true"
	s.firstrunList(list)
	if enabled != "true" || calls != 1 || f.count("appops") != 1 {
		t.Fatal("a later choice was overwritten")
	}
	must(t, os.Remove(stamp))
	cmdFail = true
	s.firstrunList(list)
	if sys_exists(stamp) || !s.firstrunLeft || enabled != "true" {
		t.Fatal("failed call counted as done")
	}
	cmdFail = false
	s.firstrunLeft = false
	s.firstrunList(list)
	if !sys_exists(stamp) || enabled != "false" || calls != 3 {
		t.Fatal("retry didn't apply")
	}
	must(t, os.Remove(stamp))
	write(t, s.State+"/invalid.list", "package com.looker.droidify\nnotifications maybe\n")
	s.firstrunList(s.State + "/invalid.list")
	if sys_exists(stamp) || !s.firstrunLeft || calls != 3 {
		t.Fatal("invalid default applied")
	}
}

// InstallAPKs: required apps first, the page's choice, the marks that make an uninstall
// stick
func TestInstallAPKs(t *testing.T) {
	f := newFake()
	s, _ := newSetup(t, f)
	installed := map[string]string{}
	var order []string
	f.handlers["pm"] = func(a []string) (string, error) {
		switch a[0] {
		case "list":
			if vc, ok := installed[a[3]]; ok {
				return "package:" + a[3] + " versionCode:" + vc, nil
			}
			return "", nil
		case "install":
			apk := a[len(a)-1]
			order = append(order, baseOf(apk))
			return "Success", nil
		}
		return "", nil
	}
	app := func(name, pkg, vc, required, checked string) {
		write(t, s.Apps+"/"+name+".apk", strings.Repeat("x", 100))
		yml := "package: " + pkg + "\nversion_code: " + vc + "\n"
		if required != "" {
			yml += "required: " + required + "\n"
		}
		if checked != "" {
			yml += "checked: " + checked + "\n"
		}
		write(t, s.Apps+"/"+name+".yml", yml)
	}
	app("Aaa", "org.aaa", "5", "", "")
	app("RetroArch", "com.retroarch", "10", "true", "")
	app("Off", "org.off", "1", "", "false")
	write(t, s.Apps+"/Mine.apk", "mine")

	// the page hasn't been left: only the required one
	s.InstallAPKs()
	if strings.Join(order, ",") != "RetroArch.apk" || !s.appsLeft {
		t.Fatalf("waiting: installed %v, left %v", order, s.appsLeft)
	}
	installed["com.retroarch"] = "10"

	// left with Next, Aaa unticked, Mine ticked
	f.settings["global andr36oid_apps_chosen"] = "1"
	f.settings["global andr36oid_apps_skip"] = "Aaa"
	order = nil
	s.InstallAPKs()
	if strings.Join(order, ",") != "Off.apk,Mine.apk" || s.appsLeft {
		t.Fatalf("chosen: installed %v, left %v", order, s.appsLeft)
	}
	if !sys_exists(s.state("installed.org.aaa")) || !sys_exists(s.state("installed.apk.Mine")) {
		t.Fatal("marks missing")
	}
	// an uninstalled app stays uninstalled; a newer version updates an installed one
	installed["org.off"] = "1"
	installed["com.retroarch"] = "9"
	order = nil
	s.InstallAPKs()
	if strings.Join(order, ",") != "RetroArch.apk" {
		t.Fatalf("update pass installed %v", order)
	}
}
