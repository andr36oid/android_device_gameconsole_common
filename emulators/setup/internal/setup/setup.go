// Package setup is the first-start and every-start provisioning of andr36oid: bundled
// apps, RetroArch, PPSSPP, the ROM folders on EASYROMS and Daijishou's library. It was the
// shell script gameconsole-emu.sh; the state it keeps (marker files in State, RetroArch's
// .gameconsole folder, /metadata/andr36oid/apps-done) is the same, so a console set up by
// the script carries on where it was.
package setup

import (
	"fmt"
	"os"
	"strings"
	"sync"
	"time"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

// Paths are every place the setup reads or writes. Defaults() has the console's; the tests
// point them into a temporary folder.
type Paths struct {
	Base          string // /system/etc/gameconsole: what the image brings
	State         string // /data/misc/gameconsole: our markers and log
	LogFile       string
	Apps          string // AUTOAPK, mounted read-only by the first start's resizer
	AppsDoneFile  string // /metadata/andr36oid/apps-done: firstresize gives AUTOAPK away then
	AppData       string // /data/user/0
	Media         string // /data/media/0, the internal storage as root sees it
	MediaRW       string // /mnt/media_rw: other volumes as root sees them
	Mounts        string // /proc/mounts
	EasyromsPart  string // legacy sysfs path, before init published EASYROMS properties
	EasyromsDev   string // legacy block device path
	DTRoot        string // /proc/device-tree
	BlockStat     string // /sys/block/mmcblk0/stat: the card's I/O counters
	URIGrants     string // /data/system/urigrants.xml
	ChromeCmdline string // /data/local/chrome-command-line
	FirstStartAPK string
}

// Defaults are the console's paths.
func Defaults() Paths {
	base := "/system/etc/gameconsole"
	state := "/data/misc/gameconsole"
	return Paths{
		Base:          base,
		State:         state,
		LogFile:       state + "/gameconsole-emu.log",
		Apps:          "/mnt/andr36oid-apps",
		AppsDoneFile:  "/metadata/andr36oid/apps-done",
		AppData:       "/data/user/0",
		Media:         "/data/media/0",
		MediaRW:       "/mnt/media_rw",
		Mounts:        "/proc/mounts",
		EasyromsPart:  "/sys/block/mmcblk0/mmcblk0p7",
		EasyromsDev:   "/dev/block/mmcblk0p7",
		DTRoot:        "/proc/device-tree",
		BlockStat:     "/sys/block/mmcblk0/stat",
		URIGrants:     "/data/system/urigrants.xml",
		ChromeCmdline: "/data/local/chrome-command-line",
		FirstStartAPK: base + "/firststart/FirstStart.apk",
	}
}

// Setup holds what the steps share.
type Setup struct {
	Paths
	R        sys.Runner
	Props    sys.Props
	Settings sys.Settings
	// Logcat writes one line to Android's log; nil in tests.
	Logcat func(string)
	Now    func() time.Time
	Sleep  func(time.Duration)
	// Progress is on while the first setup isn't done (the setup screen shows it)
	Progress *Progress
	// Poll runs between long steps: the worker takes requests that came in meanwhile
	// (e.g. the app page was left). nil: nothing to do.
	Poll func()
	// FreeKB is the free space of a file system in KB (statfs); tests fake a full card
	FreeKB func(path string) (int64, error)
	// SQLite is the sqlite3 program; "" picks gameconsole-sqlite3 or sqlite3
	SQLite string

	// what this run found out
	appsLeft     bool   // install left an app on AUTOAPK for later
	firstrunLeft bool   // a first-start list stopped before its end
	easyroms     string // where EASYROMS is mounted, "" if not
	noroms       bool   // the card has no EASYROMS partition
	easyromsLate bool   // EASYROMS wasn't mounted yet: its steps run when it is
	installing   bool   // InstallAPKs is running: poll waits

	logMu sync.Mutex
	// Lines collects the log in tests
	Lines []string
}

// New is a Setup for the console.
func New() *Setup {
	r := sys.ExecRunner{}
	s := &Setup{
		Paths:    Defaults(),
		R:        r,
		Props:    sys.CmdProps{R: r},
		Settings: sys.Settings{R: r},
		Logcat:   logcat,
		Now:      time.Now,
		Sleep:    time.Sleep,
	}
	s.Progress = NewProgress(s)
	return s
}

// Log writes a line to logcat and to the log file on the card (logcat is gone after a
// restart), with the date like the script did.
func (s *Setup) Log(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	s.logMu.Lock()
	defer s.logMu.Unlock()
	s.Lines = append(s.Lines, msg)
	if s.Logcat != nil {
		s.Logcat(msg)
	}
	if s.LogFile == "" || !sys.IsDir(dirOf(s.LogFile)) {
		return
	}
	f, err := os.OpenFile(s.LogFile, os.O_WRONLY|os.O_APPEND|os.O_CREATE, 0o600)
	if err != nil {
		return
	}
	fmt.Fprintf(f, "%s %s\n", s.Now().Format("01-02 15:04:05"), msg)
	f.Close()
}

// LogTrim keeps the last 400 lines of the log file.
func (s *Setup) LogTrim() {
	b, err := os.ReadFile(s.LogFile)
	if err != nil {
		return
	}
	lines := strings.SplitAfter(string(b), "\n")
	if len(lines) > 0 && lines[len(lines)-1] == "" {
		lines = lines[:len(lines)-1]
	}
	if len(lines) <= 400 {
		return
	}
	_ = fsx.WriteFileAtomic(s.LogFile, []byte(strings.Join(lines[len(lines)-400:], "")))
}

// state is a path in the State folder.
func (s *Setup) state(name string) string { return s.State + "/" + name }

func dirOf(p string) string {
	if i := strings.LastIndexByte(p, '/'); i > 0 {
		return p[:i]
	}
	return "/"
}

func baseOf(p string) string {
	if i := strings.LastIndexByte(p, '/'); i >= 0 {
		return p[i+1:]
	}
	return p
}

// run is R.Run for commands whose output only matters on failure.
func (s *Setup) run(name string, args ...string) (string, error) { return s.R.Run(name, args...) }

// restorecon puts the SELinux labels back on what we made; failures don't matter (the
// script ignored them too).
func (s *Setup) restorecon(recursive bool, paths ...string) {
	args := []string{}
	if recursive {
		args = append(args, "-R")
	}
	_, _ = s.R.Run("restorecon", append(args, paths...)...)
}

// fixupAppDir is vold's own fixup for an app's folder on the internal storage (default
// ACL, quota project): IStorageManager.fixupAppDir, transaction 89 + 1.
func (s *Setup) fixupAppDir(publicPath string) {
	_, _ = s.R.Run("service", "call", "mount", "90", "s16", publicPath)
}

// setupDone is Settings.Secure user_setup_complete.
func (s *Setup) setupDone() bool {
	return s.Settings.Get("secure", "user_setup_complete") == "1"
}

// poll gives the worker a chance to pick up new requests.
func (s *Setup) poll() {
	if s.Poll != nil && !s.installing {
		s.Poll()
	}
}

// freeKB is FreeKB, or statfs.
func (s *Setup) freeKB(path string) (int64, error) {
	if s.FreeKB != nil {
		return s.FreeKB(path)
	}
	return fsx.FreeKB(path)
}
