package setup

import (
	"os"
	"strings"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

// Daijishou's home activity. While the first-start launcher is home it is disabled, so the
// launcher is the only home app with priority 0.
const (
	djHome      = "com.magneticchen.daijishou/com.magneticchen.daijishou.activities.BootstrapActivity"
	fsPkg       = "org.andr36oid.firststart"
	roleHome    = "android.app.role.HOME"
	djHiddenMk  = "firststart.daijishou-hidden"
	laHiddenMk  = "firststart.launcher-hidden"
	firstDoneMk = "firststart.done"
)

func pkgOf(component string) string {
	if i := strings.IndexByte(component, '/'); i >= 0 {
		return component[:i]
	}
	return component
}

// launcherHome is our launcher's home activity, when the build ships it.
func (s *Setup) launcherHome() string { return s.Props.Get("ro.andr36oid.launcher_home") }

// showHome enables a home activity again and drops its hidden mark.
func (s *Setup) showHome(component, marker string) {
	out, err := s.R.Run("pm", "enable", "--user", "0", component)
	if err == nil && strings.HasSuffix(strings.TrimSpace(out), "enabled") {
		_ = os.Remove(s.state(marker))
		s.Log("home activity %s is enabled again", pkgOf(component))
		return
	}
	s.Log("enabling home activity %s failed: %s", pkgOf(component), out)
}

// homeRoleAgain: with two home apps on again, the home role goes to the one chosen in the
// setup wizard (Daijishou without a choice). That writes the "always use" home entry for
// both, so Android doesn't ask which one to use.
func (s *Setup) homeRoleAgain() {
	home := s.Settings.Get("global", "andr36oid_home")
	if home == "" {
		home = pkgOf(djHome)
	}
	if out, err := s.R.Run("cmd", "role", "add-role-holder", "--user", "0", roleHome, home); err != nil {
		s.Log("giving %s the home role failed: %s", home, out)
	}
}

// FirstStart: the first-start launcher (org.andr36oid.firststart, FirstStart/ in the
// common device tree) is a tiny user app whose home activity opens the quick start
// guide's tutorial. It is installed while the setup wizard runs and made the only home
// app besides the wizard: Daijishou's home activity (and our launcher's, if the build
// ships it) is disabled first. So there is never a moment with two home apps of the same
// priority, and Android never asks which one to use, however fast the wizard is. If the
// wizard ends between disabling Daijishou and the install, Settings' FallbackHome shows
// for a moment and hands over to the launcher as soon as it's installed.
// The guide undoes all of it when the tutorial is done. Only on the first start, once
// (the marker file), so an uninstall sticks.
func (s *Setup) FirstStart() {
	done := s.state(firstDoneMk)
	djHidden := s.state(djHiddenMk)
	laHidden := s.state(laHiddenMk)
	laHome := s.launcherHome()

	// Repair, every start: Daijishou and our launcher stay hidden only while the
	// first-start launcher is there (a restart in the middle, or the guide didn't get to
	// it)
	if (sys.Exists(djHidden) || sys.Exists(laHidden)) && s.installedVC(fsPkg) == "" {
		if sys.Exists(djHidden) {
			s.showHome(djHome, djHiddenMk)
		}
		if sys.Exists(laHidden) && laHome != "" {
			s.showHome(laHome, laHiddenMk)
		}
		s.homeRoleAgain()
	}

	if !sys.IsFile(s.FirstStartAPK) || sys.Exists(done) {
		return
	}
	if s.setupDone() {
		s.Log("setup was done before, no first-start launcher")
		_ = fsx.Touch(done)
		return
	}

	// The marker first, so a restart right after this still gets repaired
	_ = fsx.Touch(djHidden)
	out, err := s.R.Run("pm", "disable", "--user", "0", djHome)
	if err != nil || !strings.HasSuffix(strings.TrimSpace(out), "disabled") {
		s.Log("disabling Daijishou's home activity failed, no first-start launcher: %s", out)
		s.showHome(djHome, djHiddenMk)
		_ = fsx.Touch(done)
		return
	}
	if laHome != "" && s.installedVC(pkgOf(laHome)) != "" {
		_ = fsx.Touch(laHidden)
		out, err := s.R.Run("pm", "disable", "--user", "0", laHome)
		if err != nil || !strings.HasSuffix(strings.TrimSpace(out), "disabled") {
			s.Log("disabling our launcher's home activity failed, no first-start launcher: %s", out)
			s.showHome(laHome, laHiddenMk)
			s.showHome(djHome, djHiddenMk)
			s.homeRoleAgain()
			_ = fsx.Touch(done)
			return
		}
	}
	if out, err := s.R.Run("pm", "install", "-r", "--user", "0", s.FirstStartAPK); err != nil {
		s.Log("installing the first-start launcher failed: %s", out)
		s.showHome(djHome, djHiddenMk)
		if sys.Exists(laHidden) {
			s.showHome(laHome, laHiddenMk)
		}
		s.homeRoleAgain()
		_ = fsx.Touch(done)
		return
	}
	_ = fsx.Touch(done)
	// Not needed for it to be home (it's the only home app with priority 0), but keeps
	// the role in line with what is home
	if out, err := s.R.Run("cmd", "role", "add-role-holder", "--user", "0", roleHome, fsPkg); err != nil {
		s.Log("giving the first-start launcher the home role failed: %s", out)
	}
	s.Log("first-start launcher installed, it is the home app until the tutorial is done")
}
