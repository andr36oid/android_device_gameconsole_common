package setup

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

// emu is the emulators folder of the device tree, where the image's files come from.
var emu, _ = filepath.Abs("../../..")

func TestMain(m *testing.M) {
	// the tests don't run as root: owners are not checked
	fsx.Chown = func(string, fsx.Owner) error { return nil }
	os.Exit(m.Run())
}

// fake is Android for the tests: a command handler per program, every call recorded,
// sqlite3 run for real.
type fake struct {
	mu       sync.Mutex
	calls    []string
	handlers map[string]func(args []string) (string, error)
	props    map[string]string
	propLog  []string          // "<name>\t<value>" per Set
	settings map[string]string // "<namespace> <key>"
}

func newFake() *fake {
	f := &fake{
		handlers: map[string]func([]string) (string, error){},
		props:    map[string]string{},
		settings: map[string]string{},
	}
	f.handlers["restorecon"] = func([]string) (string, error) { return "", nil }
	f.handlers["service"] = func([]string) (string, error) { return "", nil }
	f.handlers["sqlite3"] = func(args []string) (string, error) {
		out, err := exec.Command("sqlite3", args...).CombinedOutput()
		return strings.TrimRight(string(out), "\n"), err
	}
	f.handlers["settings"] = func(a []string) (string, error) {
		f.mu.Lock()
		defer f.mu.Unlock()
		switch a[0] {
		case "get":
			if v, ok := f.settings[a[1]+" "+a[2]]; ok {
				return v, nil
			}
			return "null", nil
		case "put":
			f.settings[a[1]+" "+a[2]] = a[3]
			return "", nil
		}
		return "", errors.New("settings " + a[0])
	}
	f.handlers["pidof"] = func([]string) (string, error) { return "", errors.New("exit status 1") }
	return f
}

func (f *fake) Run(name string, args ...string) (string, error) {
	f.mu.Lock()
	f.calls = append(f.calls, strings.TrimSpace(name+" "+strings.Join(args, " ")))
	h := f.handlers[name]
	f.mu.Unlock()
	if h == nil {
		return "", nil
	}
	return h(args)
}

func (f *fake) Get(name string) string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.props[name]
}

func (f *fake) Set(name, value string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.props[name] = value
	f.propLog = append(f.propLog, name+"\t"+value)
	return nil
}

// count is how many calls start with prefix.
func (f *fake) count(prefix string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := 0
	for _, c := range f.calls {
		if strings.HasPrefix(c, prefix) {
			n++
		}
	}
	return n
}

func (f *fake) reset() {
	f.mu.Lock()
	f.calls = nil
	f.mu.Unlock()
}

// packages fakes pm list packages: package -> versionCode.
func (f *fake) packages(pk map[string]string) {
	f.handlers["pm"] = func(a []string) (string, error) {
		if len(a) >= 4 && a[0] == "list" && a[1] == "packages" {
			if vc, ok := pk[a[3]]; ok {
				return "package:" + a[3] + " versionCode:" + vc, nil
			}
			return "", nil
		}
		if len(a) >= 1 && a[0] == "path" {
			return "package:/system/framework/framework-res.apk", nil
		}
		return "", nil
	}
}

// newSetup is a Setup in a temporary console: every path under root.
func newSetup(t *testing.T, f *fake) (*Setup, string) {
	t.Helper()
	root := t.TempDir()
	p := Paths{
		Base:          root + "/system",
		State:         root + "/misc",
		LogFile:       root + "/misc/gameconsole-emu.log",
		Apps:          root + "/apps",
		AppsDoneFile:  root + "/metadata/apps-done",
		AppData:       root + "/data",
		Media:         root + "/media",
		MediaRW:       root + "/media_rw",
		Mounts:        root + "/mounts",
		EasyromsPart:  root + "/sys/mmcblk0p7",
		EasyromsDev:   "/dev/block/mmcblk0p7",
		DTRoot:        root + "/dt",
		URIGrants:     root + "/system_data/urigrants.xml",
		ChromeCmdline: root + "/local/chrome-command-line",
		FirstStartAPK: root + "/system/firststart/FirstStart.apk",
		SubsystemRoot: root + "/darkfull",
		BrokerState:   root + "/portmaster",
	}
	for _, d := range []string{p.Base, p.State, p.AppData, p.MediaRW, dirOf(p.URIGrants), dirOf(p.EasyromsPart), dirOf(p.ChromeCmdline)} {
		must(t, os.MkdirAll(d, 0o755))
	}
	s := &Setup{
		Paths:    p,
		R:        f,
		Props:    f,
		Settings: sys.Settings{R: f},
		Now:      time.Now,
		Sleep:    func(time.Duration) {},
		SQLite:   "sqlite3",
	}
	s.Progress = NewProgress(s)
	return s, root
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func write(t *testing.T, path, text string) {
	t.Helper()
	must(t, os.MkdirAll(dirOf(path), 0o755))
	must(t, os.WriteFile(path, []byte(text), 0o644))
}

func read(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func logHas(s *Setup, sub string) bool {
	for _, l := range s.Lines {
		if strings.Contains(l, sub) {
			return true
		}
	}
	return false
}
