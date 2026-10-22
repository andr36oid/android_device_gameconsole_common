// Package archive checks and unpacks a hotfix into a new, private directory.
package archive

import (
	"archive/zip"
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

const MaxSize = 128 << 20
const MaxZip = 128 << 20

var Name = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`)

func Manifest(b []byte) (map[string]string, error) {
	if len(b) > 8192 || !utf8.Valid(b) {
		return nil, errors.New("invalid manifest size or encoding")
	}
	m := make(map[string]string)
	s := bufio.NewScanner(strings.NewReader(string(b)))
	for s.Scan() {
		line := strings.TrimSuffix(s.Text(), "\r")
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		k, v = strings.TrimSpace(k), strings.TrimSpace(v)
		if !ok || m[k] != "" || v == "" || len(v) > 1024 || strings.IndexFunc(v, unicode.IsControl) >= 0 {
			return nil, errors.New("invalid manifest field")
		}
		switch k {
		case "name", "description", "build":
			m[k] = v
		default:
			return nil, fmt.Errorf("unknown manifest field %q", k)
		}
	}
	if s.Err() != nil || !Name.MatchString(m["name"]) || m["description"] == "" || m["build"] == "" {
		return nil, errors.New("manifest needs name, description and build")
	}
	return m, nil
}

// Unpack rejects links, special files, ambiguous paths and duplicate entries before
// writing anything. The byte limit is checked both in the directory and while reading.
func Unpack(file, dir string, budget uint64) (size uint64, err error) {
	st, err := os.Stat(file)
	if err != nil {
		return 0, err
	}
	if !st.Mode().IsRegular() || st.Size() > MaxZip {
		return 0, errors.New("archive exceeds 128 MiB")
	}
	z, err := zip.OpenReader(file)
	if err != nil {
		return 0, err
	}
	defer z.Close()
	if len(z.File) > 512 {
		return 0, errors.New("too many entries")
	}
	if budget > MaxSize {
		budget = MaxSize
	}
	seen := make(map[string]bool)
	var manifest, script *zip.File
	for _, f := range z.File {
		n := strings.TrimSuffix(f.Name, "/")
		if n == "" || strings.Contains(f.Name, "\\") || strings.HasPrefix(n, "/") || path.Clean(n) != n ||
			strings.IndexFunc(n, unicode.IsControl) >= 0 || !utf8.ValidString(n) {
			return 0, errors.New("unsafe archive path")
		}
		for _, part := range strings.Split(n, "/") {
			if part == ".." || part == "." {
				return 0, errors.New("unsafe archive path")
			}
		}
		if seen[n] || (!f.Mode().IsRegular() && !f.Mode().IsDir()) ||
			(n != "manifest.txt" && n != "hotfix.sh" && n != "files" && !strings.HasPrefix(n, "files/")) ||
			((n == "manifest.txt" || n == "hotfix.sh") && f.Mode().IsDir()) {
			return 0, errors.New("unexpected or duplicate entry")
		}
		seen[n] = true
		if f.UncompressedSize64 > budget-size {
			return 0, errors.New("unpacked size limit exceeded")
		}
		size += f.UncompressedSize64
		if n == "manifest.txt" {
			manifest = f
		}
		if n == "hotfix.sh" {
			script = f
		}
	}
	if manifest == nil || script == nil || script.UncompressedSize64 == 0 || manifest.UncompressedSize64 > 8192 {
		return 0, errors.New("missing manifest or script")
	}
	r, err := manifest.Open()
	if err != nil {
		return 0, err
	}
	b, err := io.ReadAll(io.LimitReader(r, 8193))
	r.Close()
	if err != nil {
		return 0, err
	}
	m, err := Manifest(b)
	if err != nil {
		return 0, err
	}
	if err = os.Mkdir(dir, 0700); err != nil {
		return 0, err
	}
	defer func() {
		if err != nil {
			os.RemoveAll(dir)
		}
	}()
	for _, f := range z.File {
		dest := filepath.Join(dir, filepath.FromSlash(f.Name))
		if f.Mode().IsDir() {
			err = os.MkdirAll(dest, 0700)
		} else {
			err = extract(f, dest)
		}
		if err != nil {
			return 0, err
		}
	}
	// Use one representation for the shell reader (also accepts CRLF manifests).
	b = []byte("name=" + m["name"] + "\ndescription=" + m["description"] + "\nbuild=" + m["build"] + "\n")
	if err = os.WriteFile(filepath.Join(dir, "manifest.txt"), b, 0600); err != nil {
		return 0, err
	}
	return size, nil
}

func extract(f *zip.File, dest string) error {
	if err := os.MkdirAll(filepath.Dir(dest), 0700); err != nil {
		return err
	}
	r, err := f.Open()
	if err != nil {
		return err
	}
	defer r.Close()
	w, err := os.OpenFile(dest, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	n, err := io.Copy(w, io.LimitReader(r, int64(f.UncompressedSize64)+1))
	ce := w.Close()
	if err != nil {
		return err
	}
	if ce != nil {
		return ce
	}
	if uint64(n) != f.UncompressedSize64 {
		return errors.New("entry size mismatch")
	}
	return nil
}
