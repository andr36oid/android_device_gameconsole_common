package setup

import (
	"bufio"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

// AUTOAPK: <Name>.apk, most with a <Name>.yml (package, version_code, required, checked,
// and the setup's description), and the first-start settings in .andr36oid/firstrun.
// People can add their own APKs from a PC, without a .yml.
//
// The setup's app page (QuickStart, SetupAppsActivity) picks which of them get installed;
// the "required: true" ones (RetroArch, PPSSPP, Magisk) always are. Settings.Global:
const (
	settingAppsChosen = "andr36oid_apps_chosen" // 1 once the page was left with Next
	// the apps left unticked, by file name without .apk, comma separated. Unset (setup
	// done some other way): the ones unticked at first ("checked: false") and the ones
	// without a .yml.
	settingAppsSkip = "andr36oid_apps_skip"
)

// installedVC is the installed versionCode of pkg, "" if it isn't installed.
func (s *Setup) installedVC(pkg string) string {
	out, err := s.R.Run("pm", "list", "packages", "--show-versioncode", pkg)
	if err != nil && out == "" {
		return ""
	}
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		rest, ok := strings.CutPrefix(line, "package:"+pkg+" versionCode:")
		if !ok {
			continue
		}
		n := 0
		for n < len(rest) && rest[n] >= '0' && rest[n] <= '9' {
			n++
		}
		return rest[:n]
	}
	return ""
}

// ymlGet is a top-level value of a .yml, without quotes ("" if missing).
func ymlGet(path, key string) string {
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		rest, ok := strings.CutPrefix(sc.Text(), key+":")
		if !ok {
			continue
		}
		v := strings.TrimRight(strings.TrimLeft(rest, " \t"), " \t\r")
		if len(v) >= 2 && v[0] == '"' && v[len(v)-1] == '"' {
			v = v[1 : len(v)-1]
		}
		return v
	}
	return ""
}

// appsWait: the bundled apps still wait for the setup's choice.
func (s *Setup) appsWait() bool {
	if s.setupDone() {
		return false
	}
	return s.Settings.Get("global", settingAppsChosen) != "1"
}

// apksOnAutoapk lists AUTOAPK's APKs; none once it is gone.
func (s *Setup) apksOnAutoapk() []string {
	apks, _ := filepath.Glob(s.Apps + "/*.apk")
	sort.Strings(apks)
	var files []string
	for _, a := range apks {
		if sys.IsFile(a) {
			files = append(files, a)
		}
	}
	return files
}

// installOrder: the required ones first (RetroArch, PPSSPP, Magisk), then the others in
// name order, then the ones without a .yml.
func (s *Setup) installOrder() []string {
	ymls, _ := filepath.Glob(s.Apps + "/*.yml")
	sort.Strings(ymls)
	var req, other, bare []string
	for _, y := range ymls {
		if !sys.IsFile(y) {
			continue
		}
		apk := strings.TrimSuffix(y, ".yml") + ".apk"
		if ymlGet(y, "required") == "true" {
			req = append(req, apk)
		} else {
			other = append(other, apk)
		}
	}
	for _, a := range s.apksOnAutoapk() {
		if !sys.Exists(strings.TrimSuffix(a, ".apk") + ".yml") {
			bare = append(bare, a)
		}
	}
	return append(append(req, other...), bare...)
}

type install struct {
	apk, marker, version string
}

// InstallAPKs installs the APKs from AUTOAPK as regular user apps. They keep their own
// signature, so F-Droid can update them, and the user can uninstall them: an app the
// user removed stays removed (the installed.<package> mark). One left out on the setup's
// page gets the same mark. A version that failed with the package's own error isn't
// tried again.
func (s *Setup) InstallAPKs() {
	s.appsLeft = false
	if !sys.IsDir(s.Apps) || len(s.apksOnAutoapk()) == 0 {
		return
	}
	wait := s.appsWait()
	skipRaw := s.Settings.Get("global", settingAppsSkip)
	chosen := true
	if skipRaw == "" && s.Settings.Get("global", settingAppsChosen) != "1" {
		chosen = false
	}
	skip := "," + skipRaw + ","
	skipped := func(name string) bool { return strings.Contains(skip, ","+name+",") }

	var todo []install
	for _, apk := range s.installOrder() {
		if !sys.IsFile(apk) {
			continue
		}
		name := strings.TrimSuffix(baseOf(apk), ".apk")
		yml := strings.TrimSuffix(apk, ".apk") + ".yml"

		if !sys.IsFile(yml) {
			// someone's own APK: only when ticked on the setup's page
			if sys.Exists(s.state("installed.apk."+name)) || sys.Exists(s.state("failed.apk."+name)) {
				continue
			}
			if wait {
				s.appsLeft = true
				continue
			}
			if skipped(name) || !chosen {
				continue
			}
			s.Log("installing %s.apk (added to AUTOAPK, no .yml)", name)
			todo = append(todo, install{apk, "apk." + name, "0"})
			continue
		}

		pkg := ymlGet(yml, "package")
		vc := ymlGet(yml, "version_code")
		required := ymlGet(yml, "required")
		if pkg == "" || vc == "" {
			s.Log("%s.yml has no package or version_code, skipping %s.apk", name, name)
			continue
		}
		cur := s.installedVC(pkg)
		switch {
		case cur != "":
			// remember we have seen it, so an uninstall later on sticks
			_ = fsx.Touch(s.state("installed." + pkg))
			if atoi(cur) >= atoi(vc) {
				continue
			}
		case sys.Exists(s.state("installed." + pkg)):
			continue
		case required != "true":
			if wait {
				s.appsLeft = true
				continue
			}
			var want bool
			if chosen {
				want = !skipped(name)
			} else {
				want = ymlGet(yml, "checked") != "false"
			}
			if !want {
				s.Log("not installing %s, it was left out in the setup", pkg)
				_ = fsx.Touch(s.state("installed." + pkg))
				continue
			}
		}
		// don't retry a version that failed before (e.g. a differently signed build of
		// the same package is installed)
		if fsx.ReadTrim(s.state("failed."+pkg)) == vc {
			continue
		}
		curText := cur
		if curText == "" {
			curText = "not installed"
		}
		s.Log("installing %s.apk (%s %s, currently: %s)", name, pkg, vc, curText)
		todo = append(todo, install{apk, pkg, vc})
	}

	var bytes int64
	for _, t := range todo {
		if st, err := os.Stat(t.apk); err == nil {
			bytes += st.Size()
		}
	}
	s.Progress.Plan("apps", bytes, len(todo))
	defer s.Progress.Done("apps")
	// no other job in the middle of the installs (the app page's job installs too)
	s.installing = true
	defer func() { s.installing = false }()
	for _, t := range todo {
		var size int64
		if st, err := os.Stat(t.apk); err == nil {
			size = st.Size()
		}
		s.Progress.Begin()
		s.installOne(t)
		s.Progress.Wrote(size)
		s.Progress.Finish(size, true)
		if !sys.Exists(s.state("installed."+t.marker)) && !sys.Exists(s.state("failed."+t.marker)) {
			s.appsLeft = true
		}
	}
	if wait {
		s.Log("the bundled apps wait for the setup's app page")
	}
}

// installOne is pm install, then the installed. or failed. mark.
func (s *Setup) installOne(t install) {
	s.Progress.App(baseOf(t.apk))
	defer s.Progress.App("")
	out, err := s.R.Run("pm", "install", "-r", "-g", "--user", "0", t.apk)
	if err == nil {
		_ = fsx.Touch(s.state("installed." + t.marker))
		_ = os.Remove(s.state("failed." + t.marker))
		return
	}
	s.Log("installing %s failed: %s", baseOf(t.apk), out)
	// Only the package's own failures ("Failure [INSTALL_...]") are kept. When Android's
	// package service is gone (system_server restarting) or the console is shutting down
	// (setup's "Shut down now"), the next start tries again.
	if strings.Contains(out, "Failure [") && s.Props.Get("sys.shutdown.requested") == "" {
		_ = fsx.WriteLine(s.state("failed."+t.marker), t.version)
	}
}

// AppsDone: after the installs and first-start settings, when nothing on AUTOAPK waits
// any more, the next start may give its space to userdata (firstresize).
func (s *Setup) AppsDone() {
	if !sys.IsDir(s.Apps) || len(s.apksOnAutoapk()) == 0 {
		return
	}
	if sys.Exists(s.AppsDoneFile) {
		s.preparationReady()
		return
	}
	if s.appsWait() || s.appsLeft {
		return
	}
	if s.firstrunLeft {
		s.Log("first-start settings not all written yet, AUTOAPK stays")
		return
	}
	if sys.IsDir(s.exampleMusic()) && !sys.Exists(s.state("example-music.done")) {
		s.Log("example music not copied yet, AUTOAPK stays")
		return
	}
	if err := os.MkdirAll(dirOf(s.AppsDoneFile), 0o755); err != nil {
		return
	}
	if err := fsx.Touch(s.AppsDoneFile); err != nil {
		return
	}
	s.Log("the apps are done, the next start gives AUTOAPK's space to userdata")
	s.preparationReady()
}

// preparationReady: the setup screen waits until Daijishou's library and the chosen apps
// (with their first-start settings) are ready, whichever comes last.
func (s *Setup) preparationReady() {
	if !sys.Exists(s.state("preparation-library-ready")) {
		return
	}
	if sys.IsDir(s.Apps) && len(s.apksOnAutoapk()) > 0 && !sys.Exists(s.AppsDoneFile) {
		s.Progress.Stage("apps")
		return
	}
	if err := s.Settings.Put("global", "andr36oid_daijishou_ready", "1"); err != nil {
		s.Log("couldn't tell the guide that game preparation is done")
	}
}

// DaijishouReady: Daijishou's library is made, or will be made when EASYROMS mounts.
func (s *Setup) DaijishouReady() {
	if fsx.Touch(s.state("preparation-library-ready")) == nil {
		s.preparationReady()
	}
}

// Example music (examplemusic/ in the device tree): a few free tracks and their
// CREDITS.txt on AUTOAPK, copied into Music on the internal storage once, before AppsDone
// gives AUTOAPK away. Files that are there already are kept, and a deleted track stays
// deleted (the done mark).
func (s *Setup) exampleMusic() string { return s.Apps + "/examplemusic" }

func (s *Setup) SeedExampleMusic() {
	src := s.exampleMusic()
	done := s.state("example-music.done")
	if !sys.IsDir(src) || sys.Exists(done) {
		return
	}
	// the internal storage isn't there yet: next time
	if !sys.IsDir(s.Media) {
		return
	}
	dir := s.Media + "/Music"
	parentOwner, _ := fsx.OwnerOf(s.Media)
	if !sys.IsDir(dir) {
		// owner and mode like its parent, as if the user had made it
		if err := os.Mkdir(dir, 0o700); err != nil {
			s.Log("couldn't make %s", dir)
			return
		}
		mode, _ := fsx.ModeOf(s.Media)
		_ = fsx.SetOwnerMode(dir, parentOwner, mode)
	}
	ents, _ := os.ReadDir(src)
	for _, e := range ents {
		f := src + "/" + e.Name()
		if !sys.IsFile(f) {
			continue
		}
		dest := dir + "/" + e.Name()
		if sys.Exists(dest) {
			continue
		}
		if err := fsx.CopyAtomic(f, dest); err != nil {
			s.Log("copying the example music failed at %s", e.Name())
			return
		}
		if st, err := os.Stat(dest); err == nil {
			s.Progress.Wrote(st.Size())
		}
		_ = fsx.SetOwnerMode(dest, parentOwner, 0o660)
	}
	s.restorecon(true, dir)
	_ = fsx.Touch(done)
	s.Log("copied the example music into Music")
	// the music player sees it now instead of after the next start's scan
	_, _ = s.R.Run("am", "broadcast", "--receiver-include-background", "-a",
		"android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///storage/emulated/0/Music")
}

// androidData makes sure Media/Android/data exists. vold only makes it when it mounts the
// internal storage through FUSE; with sdcardfs it waits for the first app, so the setup's
// app settings waited for it forever. Made the way vold makes it (media_rw, 02771).
// False only while the internal storage isn't there yet.
func (s *Setup) androidData() bool {
	if sys.IsDir(s.Media + "/Android/data") {
		return true
	}
	if !sys.IsDir(s.Media) {
		return false
	}
	for _, d := range []string{s.Media + "/Android", s.Media + "/Android/data"} {
		if sys.IsDir(d) {
			continue
		}
		if err := fsx.MkdirAs(d, fsx.Owner{UID: 1023, GID: 1023}, 0o771|os.ModeSetgid); err != nil {
			s.Log("making %s failed", d)
			return false
		}
	}
	s.restorecon(true, s.Media+"/Android")
	s.Log("made %s/Android/data (sdcardfs doesn't make it before an app does)", s.Media)
	return true
}

// appsEstimate is the size of the APKs on AUTOAPK not seen installed yet, for the first
// plan.
func (s *Setup) appsEstimate() (int64, int) {
	var n int64
	apps := 0
	for _, a := range s.apksOnAutoapk() {
		name := strings.TrimSuffix(baseOf(a), ".apk")
		yml := strings.TrimSuffix(a, ".apk") + ".yml"
		mark := "apk." + name
		if pkg := ymlGet(yml, "package"); pkg != "" {
			mark = pkg
		}
		if sys.Exists(s.state("installed."+mark)) || sys.Exists(s.state("failed."+mark)) {
			continue
		}
		if st, err := os.Stat(a); err == nil {
			n += st.Size()
			apps++
		}
	}
	return n, apps
}

// Estimate makes the first plan for the setup screen: what the start will likely do.
func (s *Setup) Estimate() {
	b, n := s.appsEstimate()
	s.Progress.Plan("apps", b, n)
	s.Progress.Plan("cores", s.coresEstimate(), 0)
	s.Progress.Plan("cheats", s.cheatsEstimate(), 0)
}

// atoi is the number at the start of s, 0 if none (versionCodes fit in int64).
func atoi(s string) int64 {
	n, _ := strconv.ParseInt(strings.TrimSpace(s), 10, 64)
	return n
}
