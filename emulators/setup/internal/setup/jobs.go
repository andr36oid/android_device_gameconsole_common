package setup

import (
	"os"
	"time"
)

// The jobs the worker runs. Only one runs at a time (see package worker), so no step ever
// races another: two installs, two copies of the example music or two writes of
// retroarch.cfg can't overlap, however slow the card is.

// Early runs from post-fs-data, before system_server: the folder access for Daijishou.
// init waits for it, and nothing else runs that early.
func (s *Setup) Early() {
	_ = os.MkdirAll(s.State, 0o700)
	s.DaijishouGrants()
}

// Prepare is the start of every boot-time job: the log and whether the setup screen
// shows our progress.
func (s *Setup) Prepare() {
	_ = os.MkdirAll(s.State, 0o700)
	s.LogTrim()
	s.Progress.Enabled = s.Props.Get("persist.andr36oid.setup_done") != "1"
	s.Progress.Tick()
}

// measureCard reads the start of the APKs once for the card's speed (the screen's first time
// left), in every job that installs: the app page's job runs in a process of its own.
func (s *Setup) measureCard() {
	if s.Progress.Enabled && s.Progress.cardSpeed() == 0 {
		s.Progress.CardSpeed(MeasureCard(s.apksOnAutoapk(), 16<<20, s.Now))
	}
}

// WaitPackageManager waits up to two minutes for the package manager (boot_completed is
// set, but it may need a moment).
func (s *Setup) WaitPackageManager() bool {
	for i := 0; ; i++ {
		if _, err := s.R.Run("pm", "path", "android"); err == nil {
			return true
		}
		if i >= 60 {
			s.Log("package manager not up, giving up")
			return false
		}
		s.Sleep(2 * time.Second)
	}
}

// AppsJob: the setup's app page was left with Next, install what was picked.
func (s *Setup) AppsJob() {
	s.Log("installing the apps picked in the setup")
	// The app page only comes once, in the first setup, and the setup screen waits for these
	// installs: report them even when the first setup already counts as done (the main job
	// often finishes while the wizard is still open)
	if !s.Progress.Enabled {
		s.Progress.Enabled = true
		s.Progress.Tick()
	}
	s.measureCard()
	s.Progress.Stage("apps")
	s.InstallAPKs()
	s.SeedCromiteSearch()
	s.Progress.Stage("appsettings")
	s.SeedFirstrun()
	s.SeedExampleMusic()
	s.AppsDone()
}

// Players: the player 1 choice in Settings > Controllers changed. The caller waits until
// RetroArch is closed (it writes its old values back when it quits).
func (s *Setup) Players() {
	s.RetroArchPlayers(true)
}

// Main is every start's work, after boot_completed.
func (s *Setup) Main() {
	if s.Progress.Enabled {
		s.measureCard()
		s.Estimate()
	}
	s.Progress.Stage("apps")
	s.SeedCromiteGamepad()
	s.SeedNetTraffic()
	s.FirstStart()
	s.InstallAPKs()
	// after the installs: the browsers are normal apps installed by InstallAPKs
	s.SeedCromiteSearch()
	s.poll()
	s.Progress.Stage("controls")
	s.SeedPPSSPP()
	s.SeedRetroArch()
	s.SeedCoreOptions()
	s.Progress.Stage("cores")
	s.SeedCores()
	s.SeedGameOptions()
	s.SeedLook()
	s.poll()
	s.Progress.Stage("storage")
	s.FindEasyroms(time.Minute)
	s.RefreshRate()
	s.RAFonts()
	s.Progress.Stage("folders")
	s.MakeRomFolders()
	s.HotfixFolder()
	// stand-alone apps' first-start settings, after the installs
	s.Progress.Stage("appsettings")
	s.SeedFirstrun()
	s.SeedExampleMusic()
	s.AppsDone()
	s.RomNomedia()
	// after the ROM folders: they are only made on a card without any of them
	s.BIOSFolder()
	s.poll()
	// after the cores and ROM folders: a platform needs both
	s.Progress.Stage("library")
	s.SeedDaijishou()
	s.DJNewGames()
	s.RetroArchPlayers(false)
	s.RAHotkeys()
	// PPSSPP isn't running this early; it writes controls.ini back when it quits
	s.PPSSPPControls()
	// last: the first unpack takes a while
	s.Progress.Stage("cheats")
	s.SeedCheats()
	s.DaijishouReady()
	// the cards held back for the first start can be mounted now (the late EASYROMS
	// steps do this themselves when they finish)
	if !s.easyromsLate {
		s.ReleaseCards()
	}
	for _, part := range []string{"apps", "cores", "cheats"} {
		s.Progress.Done(part)
	}
	s.Progress.MainDone()
}
