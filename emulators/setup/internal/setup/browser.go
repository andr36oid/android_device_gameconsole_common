package setup

import (
	"os"
	"strings"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

// Thorium and Cromite block the Gamepad API (Chromium's RestrictGamepadAccess feature, on
// by default), so browser games don't see the pad, and open with a welcome screen that is
// no use on a console (--disable-fre skips it). Chromium on Android reads extra switches
// from /data/local/chrome-command-line on any build (only root can write there); the
// first word is a placeholder for the program name. Written once, and only if there is no
// such file: a file the user made is left alone, and one the user deleted stays deleted.
// The marker says "written" when we made the file ("user" when it was there already). A
// power cut can leave our file empty: then it is written again.
//
// For the slow chip: Chromium's low-end device mode (less memory, fewer background
// processes), one renderer process for all tabs (1 GB of RAM), and key scrolling without
// the animation, which drops frames here and lags behind the d-pad.
const cromiteSwitches = "--disable-features=RestrictGamepadAccess --disable-fre" +
	" --enable-low-end-device-mode --renderer-process-limit=1 --disable-smooth-scrolling"

// what earlier builds wrote: our file with one of these is brought up to date
var cromiteSwitchesOld = []string{
	"--disable-features=RestrictGamepadAccess",
	"--disable-features=RestrictGamepadAccess --disable-fre",
}

func (s *Setup) cromiteCmdlineWrite() {
	path := s.ChromeCmdline
	// the browsers read it as their own app user: 644
	err := fsx.WriteFileAtomic(path, []byte("_ "+cromiteSwitches+"\n"))
	if err == nil {
		err = os.Chmod(path, 0o644)
	}
	if err != nil {
		s.Log("couldn't write %s, the browser keeps blocking the pad", path)
		return
	}
	s.restorecon(false, path)
	_ = fsx.WriteLine(s.state("cromite-gamepad.done"), "written")
	s.Log("browser games can use the pad now (%s)", path)
}

// SeedCromiteGamepad writes the browsers' command line, see above.
func (s *Setup) SeedCromiteGamepad() {
	mark := s.state("cromite-gamepad.done")
	path := s.ChromeCmdline
	if sys.Exists(mark) {
		m := fsx.ReadTrim(mark)
		if m == "written" {
			st, err := os.Stat(path)
			switch {
			case err == nil && st.Mode().IsRegular() && st.Size() == 0:
				// ours, but emptied by a power cut (deleted stays deleted)
				s.cromiteCmdlineWrite()
			default:
				cur := fsx.ReadTrim(path)
				for _, old := range cromiteSwitchesOld {
					if cur == "_ "+old {
						s.cromiteCmdlineWrite()
						break
					}
				}
			}
		}
		return
	}
	// an empty file does nothing for Chromium: ours from a power cut before the marker,
	// or an empty one the user made
	st, err := os.Lstat(path)
	if err != nil || (st.Mode().IsRegular() && st.Size() == 0) {
		s.cromiteCmdlineWrite()
		return
	}
	_ = fsx.WriteLine(mark, "user")
}

// The browsers' default search engine: DuckDuckGo Light, one of Chromium's built-in
// engines. Chromium keeps the choice in its profile's Preferences file, so one is written
// before the browser's first start (no app_chrome folder yet). After that the file is the
// browser's and never touched.
var chromiumPkgs = []string{"org.chromium.thorium", "org.cromite.cromite"}

const chromiumPrefs = `{"default_search_provider_data":{"template_url_data":{"short_name":"DuckDuckGo Light","keyword":"duckduckgo.com/lite","url":"https://duckduckgo.com/lite/?q={searchTerms}","favicon_url":"https://duckduckgo.com/favicon.ico","safe_for_autoreplace":true,"id":"1000","prepopulate_id":0,"date_created":"0","last_modified":"0","last_visited":"0","usage_count":0,"alternate_urls":[],"input_encodings":["UTF-8"]}}}` + "\n"

// SeedCromiteSearch sets the search engine of both browsers, see above.
func (s *Setup) SeedCromiteSearch() {
	for _, pkg := range chromiumPkgs {
		s.chromiumSearch(pkg)
	}
}

func (s *Setup) chromiumSearch(pkg string) {
	data := s.AppData + "/" + pkg
	if !sys.IsDir(data) || sys.Exists(data+"/app_chrome") {
		return
	}
	owner, err := fsx.OwnerOf(data)
	if err != nil {
		return
	}
	dir := data + "/app_chrome/Default"
	if err := os.MkdirAll(dir, 0o700); err != nil || os.WriteFile(dir+"/Preferences", []byte(chromiumPrefs), 0o600) != nil {
		_ = os.RemoveAll(data + "/app_chrome")
		s.Log("couldn't set %s's search engine", pkg)
		return
	}
	_ = fsx.ChownR(data+"/app_chrome", owner)
	_ = os.Chmod(data+"/app_chrome", 0o700)
	_ = os.Chmod(dir, 0o700)
	_ = os.Chmod(dir+"/Preferences", 0o600)
	s.restorecon(true, data+"/app_chrome")
	s.Log("%s searches with DuckDuckGo Light", pkg)
}

// The network speed in the status bar (LineageOS' indicator): upload and download, in
// KB/s with the unit, hidden while nothing moves. Each setting is written only while it
// is unset, so a choice made in Settings stays; once all four are there it isn't looked
// at again (each content call starts a Java VM).
const lineageSecure = "content://lineagesettings/secure"

var netTraffic = [][2]string{
	{"network_traffic_mode", "3"},
	{"network_traffic_autohide", "1"},
	{"network_traffic_units", "2"},
	{"network_traffic_show_units", "1"},
}

// SeedNetTraffic sets the status bar's network speed, see above.
func (s *Setup) SeedNetTraffic() {
	done := s.state("net-traffic.done")
	if sys.Exists(done) {
		return
	}
	failed := false
	for _, kv := range netTraffic {
		out, _ := s.R.Run("content", "query", "--uri", lineageSecure, "--projection", "value",
			"--where", "name='"+kv[0]+"'")
		if strings.Contains(out, "value=") {
			continue
		}
		if _, err := s.R.Run("content", "insert", "--uri", lineageSecure,
			"--bind", "name:s:"+kv[0], "--bind", "value:s:"+kv[1]); err != nil {
			s.Log("setting %s failed", kv[0])
			failed = true
			continue
		}
		s.Log("status bar network speed: %s=%s", kv[0], kv[1])
	}
	if !failed {
		_ = fsx.Touch(done)
	}
}
