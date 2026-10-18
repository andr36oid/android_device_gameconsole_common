package setup

import (
	"os"
	"regexp"
	"strings"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

const (
	ppPkg       = "org.ppsspp.ppsspp"
	ppExtPublic = "/storage/emulated/0/Android/data/" + ppPkg + "/files"
)

func (s *Setup) ppData() string { return s.AppData + "/" + ppPkg }
func (s *Setup) ppExt() string  { return s.Media + "/Android/data/" + ppPkg + "/files" }

// SeedPPSSPP: PPSSPP asks for a folder on its first start. Its memory stick is set to its
// own folder on the internal storage instead (memstick_dir.txt), with our ppsspp.ini,
// controls.ini and compat.ini in PSP/SYSTEM. Only while PPSSPP has no memory stick yet.
func (s *Setup) SeedPPSSPP() {
	data := s.ppData()
	if !sys.IsDir(data) || sys.Exists(data+"/files/memstick_dir.txt") ||
		!sys.IsFile(s.Base+"/ppsspp/ppsspp.ini") || !s.androidData() {
		return
	}
	owner, err := fsx.OwnerOf(data)
	if err != nil {
		return
	}
	ext := s.ppExt()
	// its folder the way vold makes it, then PSP/SYSTEM like PPSSPP makes them
	for _, dir := range []string{dirOf(ext), ext, ext + "/PSP", ext + "/PSP/SYSTEM"} {
		if sys.IsDir(dir) {
			continue
		}
		if err := fsx.MkdirAs(dir, extOwner(owner), extDirMode); err != nil {
			s.Log("making %s failed", dir)
			return
		}
	}
	for _, f := range []string{"ppsspp.ini", "controls.ini", "compat.ini"} {
		src := s.Base + "/ppsspp/" + f
		dest := ext + "/PSP/SYSTEM/" + f
		if !sys.IsFile(src) || sys.Exists(dest) {
			continue
		}
		if err := fsx.CopyAtomic(src, dest); err != nil {
			s.Log("writing PPSSPP's %s failed", f)
			return
		}
		_ = fsx.SetOwnerMode(dest, extOwner(owner), 0o660)
		s.Log("seeded PPSSPP's %s", f)
	}
	s.restorecon(true, dirOf(ext))
	s.fixupAppDir(ppExtPublic)

	// last, so a failure above leaves PPSSPP asking as usual. No newline: PPSSPP uses the
	// whole file as the path.
	files := data + "/files"
	if !sys.IsDir(files) {
		if err := fsx.MkdirAs(files, owner, 0o771); err != nil {
			s.Log("making PPSSPP's files folder failed")
			return
		}
	}
	ms := files + "/memstick_dir.txt"
	if err := fsx.WriteFileAtomic(ms, []byte(ppExtPublic)); err != nil {
		s.Log("writing PPSSPP's memstick_dir.txt failed")
		return
	}
	_ = fsx.SetOwnerMode(ms, owner, 0o600)
	s.restorecon(true, files)
	s.Log("PPSSPP keeps its data in %s", ppExtPublic)
}

// ppsspp memory stick as root sees it, "" if unknown or not a folder.
func (s *Setup) ppMemstick() string {
	b, err := os.ReadFile(s.ppData() + "/files/memstick_dir.txt")
	if err != nil {
		return ""
	}
	dir := strings.TrimRight(string(b), "\n")
	switch {
	case strings.HasPrefix(dir, "content://com.android.externalstorage.documents/tree/"):
		// tree/primary%3APSP or tree/1234-ABCD%3Agames%2Fpsp
		d := dir[strings.Index(dir, "/tree/")+len("/tree/"):]
		d = strings.NewReplacer("%3A", ":", "%3a", ":", "%2F", "/", "%2f", "/", "%20", " ").Replace(d)
		d = strings.ReplaceAll(d, "%25", "%")
		vol, rel, _ := strings.Cut(d, ":")
		if vol == "primary" {
			dir = s.Media + "/" + rel
		} else {
			dir = s.MediaRW + "/" + vol + "/" + rel
		}
	case strings.HasPrefix(dir, "/storage/emulated/0/"):
		dir = s.Media + "/" + strings.TrimPrefix(dir, "/storage/emulated/0/")
	case strings.HasPrefix(dir, "/storage/"):
		dir = s.MediaRW + "/" + strings.TrimPrefix(dir, "/storage/")
	case strings.HasPrefix(dir, "/"):
	default:
		return ""
	}
	if !sys.IsDir(dir) {
		return ""
	}
	return strings.TrimSuffix(dir, "/")
}

// PPSSPP's pad device is 10; 96/97/99/100 are BUTTON_A/B/X/Y.
var ppFace = []struct {
	name     string
	from, to string
}{
	{"Cross", "10-96", "10-97"},
	{"Circle", "10-97", "10-96"},
	{"Square", "10-99", "10-100"},
	{"Triangle", "10-100", "10-99"},
}

func ppFaceRe(name, code string) *regexp.Regexp {
	return regexp.MustCompile(`^(` + name + ` *=.*[= ,])` + code + `(,.*|.?)$`)
}

// PPSSPPControls: PSP buttons by position on the built-in pad. PPSSPP's Android default
// puts Cross on BUTTON_A, the bottom button on Xbox and PlayStation pads but the right
// one on this console, which sends the printed letter. Once PPSSPP has written its
// controls.ini, and only while the four face buttons are still PPSSPP's default, they move
// to Cross = B, Circle = A, Square = Y, Triangle = X, like the PSP. Once per controls.ini
// (State/ppsspp-controls), so a mapping the user makes later stays.
func (s *Setup) PPSSPPControls() {
	root := s.ppMemstick()
	if root == "" {
		return
	}
	ini := root + "/PSP/SYSTEM/controls.ini"
	if !sys.IsFile(ini) {
		return
	}
	done := s.state("ppsspp-controls")
	if b, err := os.ReadFile(done); err == nil {
		for _, l := range splitLines(string(b)) {
			if l == ini {
				return
			}
		}
	}
	b, err := os.ReadFile(ini)
	if err != nil {
		return
	}
	lines := splitLines(string(b))
	all := true
	for _, f := range ppFace {
		re := ppFaceRe(f.name, f.from)
		found := false
		for _, l := range lines {
			if re.MatchString(l) {
				found = true
				break
			}
		}
		all = all && found
	}
	if all {
		for i, l := range lines {
			for _, f := range ppFace {
				if re := ppFaceRe(f.name, f.from); re.MatchString(l) {
					lines[i] = re.ReplaceAllString(l, "${1}"+f.to+"${2}")
					break
				}
			}
		}
		if err := writeInPlace(ini, []byte(strings.Join(lines, "\n")+"\n")); err == nil {
			s.Log("PPSSPP: face buttons by position (Cross = B) in %s", ini)
		}
	} else {
		s.Log("PPSSPP: face buttons in %s already changed, left alone", ini)
	}
	f, err := os.OpenFile(done, os.O_WRONLY|os.O_APPEND|os.O_CREATE, 0o600)
	if err == nil {
		_, _ = f.WriteString(ini + "\n")
		f.Close()
	}
}
