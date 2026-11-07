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
	downgrade            bool // pass -d so pm accepts a lower versionCode (Magisk repin)
}

// magiskPkg is pinned: only a new andr36oid image updates it. The app may not update
// itself (see internal/magisk for the matching root-is-pinned policy).
const magiskPkg = "com.topjohnwu.magisk"

// InstallAPKs installs the APKs from AUTOAPK as regular user apps. They keep their own
// signature, so F-Droid can update them, and the user can uninstall them: an app the
// user removed stays removed (the installed.<package> mark). One left out on the setup's
// page gets the same mark. A version that failed with the package's own error isn't
// tried again.
func (s *Setup) InstallAPKs() {
	s.appsLeft = false
	if !sys.IsDir(s.Apps) || len(s.apksOnAutoapk()) == 0 {
		s.noteAutoapkProblem()
		return
	}
	s.clearAutoapkProblem()
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
			todo = append(todo, install{apk, "apk." + name, "0", false})
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
		// Magisk is pinned to the baked version. If the installed app has drifted
		// (self-update, F-Droid, sideload) -- to a higher OR lower versionCode -- revert
		// it by reinstalling the baked APK, downgrading when the drift was upward. Root
		// stays the baked ramdisk (never repatched; see internal/magisk), so app and root
		// remain matched and an app update can never brick root.
		if pkg == magiskPkg && cur != "" && atoi(cur) != atoi(vc) {
			_ = fsx.Touch(s.state("installed." + pkg))
			if fsx.ReadTrim(s.state("failed."+pkg)) == vc {
				continue
			}
			s.Log("repinning Magisk to baked %s (installed %s drifted; only the image updates Magisk)", vc, cur)
			todo = append(todo, install{apk, pkg, vc, atoi(cur) > atoi(vc)})
			continue
		}
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
		todo = append(todo, install{apk, pkg, vc, false})
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
	s.pinMagiskUpdates()
}

// pinMagiskUpdates turns off the Magisk app's self-update check so it never fetches or
// prompts a newer Magisk. Policy: only a new andr36oid image updates Magisk -- internal/magisk
// pins root to the baked ramdisk and the repin above reverts any app that drifts. check_update
// lives in the daemon DB (/data/adb/magisk.db settings); the magisk binary writes it.
// Best-effort and idempotent: on a boot where Magisk root is not up yet it just retries next
// boot. Not unit-tested (needs a real magisk binary) -- confirm on hardware.
func (s *Setup) pinMagiskUpdates() {
	if s.installedVC(magiskPkg) == "" {
		return // the app is not installed (yet)
	}
	if out, err := s.R.Run("magisk", "--sqlite", "REPLACE INTO settings (key,value) VALUES('check_update',0)"); err != nil {
		s.Log("magisk: could not disable the update check (will retry next boot): %s", strings.TrimSpace(out))
		return
	}
	if !sys.Exists(s.state("magisk-update-off")) {
		s.Log("magisk: self-update check disabled (only the image updates Magisk)")
		_ = fsx.Touch(s.state("magisk-update-off"))
	}
}

// AUTOAPK that can't be read. Nothing installs from it then, apps-done is never
// written and firstresize never gives the partition to userdata: "the apps aren't
// installed and AUTOAPK is still there" (issue #9), with nothing in the log to say
// why. InstallAPKs tells the two apart: the partition gone (reclaimed, or an image
// without one) is the normal quiet case; the partition there and nothing readable at
// Apps is the problem, and gets a log line, the autoapk-problem mark in State (for
// problem reports) and the property below. Nothing else changes: the install and the
// reclaim run as before, this only makes the next report diagnosable.
const (
	propAutoapkProblem = "sys.andr36oid.autoapk_problem"
	autoapkProblemMark = "autoapk-problem"
)

// autoapkProblem is why nothing can be read from AUTOAPK although the card still has
// the partition; "" when there is no problem to report.
func (s *Setup) autoapkProblem() string {
	if len(s.apksOnAutoapk()) > 0 {
		return ""
	}
	if s.AutoapkDev == "" || !sys.Exists(s.AutoapkDev) {
		return ""
	}
	switch {
	case !sys.IsDir(s.Apps):
		return "AUTOAPK is on the card but isn't mounted (" + s.Apps + " doesn't exist)"
	case !s.mountedAt(s.Apps):
		return "AUTOAPK is on the card but nothing is mounted at " + s.Apps
	default:
		return "AUTOAPK is mounted at " + s.Apps + " but holds no .apk files"
	}
}

// mountedAt reads /proc/mounts for a file system mounted at dir.
func (s *Setup) mountedAt(dir string) bool {
	b, err := os.ReadFile(s.Mounts)
	if err != nil {
		return false
	}
	sc := bufio.NewScanner(strings.NewReader(string(b)))
	for sc.Scan() {
		if f := strings.Fields(sc.Text()); len(f) >= 2 && f[1] == dir {
			return true
		}
	}
	return false
}

func (s *Setup) noteAutoapkProblem() {
	why := s.autoapkProblem()
	if why == "" {
		s.clearAutoapkProblem()
		return
	}
	s.Log("%s: the bundled apps can't be installed and the partition can't be given to userdata (no apps-done); check the SD card (f3/h2testw) and the first start's resize log (BOOT/resize-log.txt)", why)
	_ = fsx.WriteLine(s.state(autoapkProblemMark), why)
	_ = s.Props.Set(propAutoapkProblem, "1")
}

// clearAutoapkProblem takes a problem back once AUTOAPK reads, or is gone.
func (s *Setup) clearAutoapkProblem() {
	mark := s.state(autoapkProblemMark)
	if !sys.Exists(mark) {
		return
	}
	_ = os.Remove(mark)
	_ = s.Props.Set(propAutoapkProblem, "")
	s.Log("AUTOAPK reads again (or is gone)")
}

// installOne is pm install, then the installed. or failed. mark.
func (s *Setup) installOne(t install) {
	s.Progress.App(baseOf(t.apk))
	defer s.Progress.App("")
	args := []string{"install", "-r", "-g", "--user", "0"}
	if t.downgrade {
		args = append(args, "-d") // allow a lower versionCode (revert a drifted Magisk)
	}
	args = append(args, t.apk)
	out, err := s.R.Run("pm", args...)
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
	// the very end: every app is installed, Daijishou opens next
	s.djWidgetsAtEnd()
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
