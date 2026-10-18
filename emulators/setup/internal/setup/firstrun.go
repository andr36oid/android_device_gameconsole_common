package setup

import (
	"archive/zip"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

// First-start settings for the bundled apps: AUTOAPK/.andr36oid/firstrun/<app>.list, with
// the files they name in <app>/ next to it. A list's lines:
//
//	package <pkg>               first; the list stops if the app isn't installed
//	unless data|ext <path>      stop if that file exists (the app was used already)
//	data <src> <path>           copy into /data/user/0/<pkg>/<path> unless it exists
//	ext <src> <path>            the same into the app's folder on the internal storage
//	extzip <zip> <path>         unpack into that folder, once, existing top-level entries kept
//	notifications on|off        the app's notification default, once
//	appop <op> <mode>           appops set, once
//
// <path> may contain spaces.
func (s *Setup) firstrunDir() string { return s.Apps + "/.andr36oid/firstrun" }

// errExists: firstrunCopy found the destination there already.
type copyResult int

const (
	copied copyResult = iota
	copyFailed
	copyExisted
)

// firstrunCopy copies src to dest unless dest exists, making missing folders.
func (s *Setup) firstrunCopy(src, dest string, owner fsx.Owner, dirMode os.FileMode) copyResult {
	if sys.Exists(dest) {
		return copyExisted
	}
	if !sys.IsFile(src) {
		s.Log("first-start file %s is missing", src)
		return copyFailed
	}
	if err := fsx.MkdirAllAs(dirOf(dest), owner, dirMode); err != nil {
		s.Log("making %s failed", dirOf(dest))
		return copyFailed
	}
	if err := fsx.CopyAtomic(src, dest); err != nil {
		s.Log("writing %s failed", dest)
		return copyFailed
	}
	if st, err := os.Stat(dest); err == nil {
		s.Progress.Wrote(st.Size())
	}
	_ = fsx.SetOwnerMode(dest, owner, 0o660)
	return copied
}

// firstrunUnzip unpacks zip into dir, leaving every top-level entry that is there already
// alone.
func (s *Setup) firstrunUnzip(zipPath, dir string, owner fsx.Owner) bool {
	if !sys.IsFile(zipPath) {
		s.Log("first-start file %s is missing", zipPath)
		return false
	}
	if err := fsx.MkdirAllAs(dir, owner, extDirMode); err != nil {
		s.Log("making %s failed", dir)
		return false
	}
	tmp := dir + "/.firstrun-unzip"
	_ = os.RemoveAll(tmp)
	if err := os.Mkdir(tmp, 0o700); err != nil {
		s.Log("making %s failed", tmp)
		return false
	}
	defer os.RemoveAll(tmp)
	if err := s.unzip(zipPath, tmp); err != nil {
		s.Log("unpacking %s failed", zipPath)
		return false
	}
	_ = fsx.ChownR(tmp, owner)
	_ = fsx.ChmodTree(tmp, extDirMode, 0o660)
	ents, _ := os.ReadDir(tmp)
	for _, e := range ents {
		dest := dir + "/" + e.Name()
		if sys.Exists(dest) {
			s.Log("kept the existing %s", dest)
			continue
		}
		if err := os.Rename(tmp+"/"+e.Name(), dest); err != nil {
			s.Log("moving %s into %s failed", e.Name(), dir)
			return false
		}
	}
	return true
}

// unzip unpacks a zip into dir: folders and regular files only.
func (s *Setup) unzip(zipPath, dir string) error {
	zr, err := zip.OpenReader(zipPath)
	if err != nil {
		return err
	}
	defer zr.Close()
	for _, f := range zr.File {
		rel, ok := safeRel(f.Name)
		if !ok {
			continue
		}
		dest := filepath.Join(dir, rel)
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(dest, 0o700); err != nil {
				return err
			}
			continue
		}
		if !f.Mode().IsRegular() {
			continue
		}
		if err := os.MkdirAll(filepath.Dir(dest), 0o700); err != nil {
			return err
		}
		rc, err := f.Open()
		if err != nil {
			return err
		}
		out, err := os.OpenFile(dest, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
		if err != nil {
			rc.Close()
			return err
		}
		n, err := io.Copy(out, rc)
		rc.Close()
		if cerr := out.Close(); err == nil {
			err = cerr
		}
		if err != nil {
			return err
		}
		s.Progress.Wrote(n)
	}
	return nil
}

// firstrunList runs one list, see above.
func (s *Setup) firstrunList(list string) {
	b, err := os.ReadFile(list)
	if err != nil {
		return
	}
	name := baseOf(list)
	dir := strings.TrimSuffix(list, ".list")
	var pkg, data, ext string
	var owner fsx.Owner
	wroteData, wroteExt := false, false
	left := func() { s.firstrunLeft = true }

lines:
	for _, line := range splitLines(string(b)) {
		// kind, src and the rest (dest may contain spaces), like `read -r kind src dest`
		fields := strings.Fields(line)
		if len(fields) == 0 || strings.HasPrefix(fields[0], "#") {
			continue
		}
		kind := fields[0]
		src, dest := "", ""
		if len(fields) > 1 {
			src = fields[1]
		}
		if len(fields) > 2 {
			t := strings.TrimLeft(line, " \t")
			t = strings.TrimLeft(t[len(kind):], " \t")
			t = strings.TrimLeft(t[len(src):], " \t")
			dest = strings.TrimRight(t, " \t\r")
		}
		if kind == "package" {
			pkg = src
			data = s.AppData + "/" + pkg
			ext = s.Media + "/Android/data/" + pkg + "/files"
			// not installed (or removed by the user)
			if !sys.IsDir(data) {
				return
			}
			if owner, err = fsx.OwnerOf(data); err != nil {
				return
			}
			continue
		}
		if pkg == "" {
			s.Log("%s: no package line first", name)
			return
		}
		extOwn := extOwner(owner)
		switch kind {
		case "unless":
			switch src {
			case "data":
				if sys.Exists(data + "/" + dest) {
					return
				}
			case "ext":
				if sys.Exists(ext + "/" + dest) {
					return
				}
			default:
				s.Log("%s: unknown place %s", name, src)
				return
			}
		case "data":
			switch s.firstrunCopy(dir+"/"+src, data+"/"+dest, owner, 0o771) {
			case copyFailed:
				left()
				break lines
			case copied:
				wroteData = true
				s.Log("seeded %s's %s", pkg, dest)
			}
		case "notifications":
			stamp := s.state("notifications." + pkg)
			if sys.Exists(stamp) {
				continue
			}
			var enabled string
			switch src {
			case "on":
				enabled = "true"
			case "off":
				enabled = "false"
			default:
				s.Log("%s: invalid notification default %s", name, src)
				left()
				break lines
			}
			out, err := s.R.Run("cmd", "notification", "set_notifications_enabled", pkg, enabled, "0")
			if err != nil {
				s.Log("setting %s notifications failed: %s", pkg, out)
				left()
				break lines
			}
			_ = fsx.Touch(stamp)
			s.Log("%s notification default set (%s)", pkg, src)
		case "appop":
			stamp := s.state("appop." + pkg + "." + src)
			if sys.Exists(stamp) {
				continue
			}
			out, err := s.R.Run("appops", "set", "--user", "0", pkg, src, dest)
			if err != nil {
				s.Log("appops set %s %s %s failed: %s", pkg, src, dest, out)
				left()
				break lines
			}
			_ = fsx.Touch(stamp)
			s.Log("%s may %s now (%s)", pkg, src, dest)
		case "extzip":
			if !s.androidData() {
				left()
				break lines
			}
			stamp := s.state("extzip." + pkg + "." + src)
			if sys.Exists(stamp) {
				continue
			}
			if !s.firstrunUnzip(dir+"/"+src, ext+"/"+dest, extOwn) {
				left()
				break lines
			}
			_ = fsx.Touch(stamp)
			wroteExt = true
			s.Log("unpacked %s into %s's %s (internal storage)", src, pkg, dest)
		case "ext":
			if !s.androidData() {
				left()
				break lines
			}
			switch s.firstrunCopy(dir+"/"+src, ext+"/"+dest, extOwn, extDirMode) {
			case copyFailed:
				left()
				break lines
			case copied:
				wroteExt = true
				s.Log("seeded %s's %s (internal storage)", pkg, dest)
			}
		default:
			s.Log("%s: unknown line %s", name, kind)
			break lines
		}
	}

	if wroteData {
		s.restorecon(true, data)
	}
	if wroteExt {
		s.restorecon(true, dirOf(ext))
		s.fixupAppDir("/storage/emulated/0/Android/data/" + pkg + "/files")
	}
}

// SeedFirstrun runs every first-start list on AUTOAPK.
func (s *Setup) SeedFirstrun() {
	s.firstrunLeft = false
	lists, _ := filepath.Glob(s.firstrunDir() + "/*.list")
	sort.Strings(lists)
	for _, l := range lists {
		if sys.IsFile(l) {
			s.firstrunList(l)
		}
	}
}
