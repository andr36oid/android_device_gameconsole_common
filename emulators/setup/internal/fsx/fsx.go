// Package fsx has the file operations the setup repeats everywhere: copies that are never
// seen half written (a power cut can come at any time on these consoles), folders made
// with an owner and mode, and checksums.
package fsx

import (
	"crypto/sha1"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
)

// Owner is a uid and gid. Chown is a variable so tests on a PC, which aren't root, can
// leave owners alone.
type Owner struct{ UID, GID int }

func (o Owner) String() string { return fmt.Sprintf("%d:%d", o.UID, o.GID) }

// Chown is os.Lchown, or a no-op in tests.
var Chown = func(path string, o Owner) error { return os.Lchown(path, o.UID, o.GID) }

// OwnerOf is stat -c %u:%g.
func OwnerOf(path string) (Owner, error) {
	st, err := os.Stat(path)
	if err != nil {
		return Owner{}, err
	}
	s, ok := st.Sys().(*syscall.Stat_t)
	if !ok {
		return Owner{}, errors.New("no owner for " + path)
	}
	return Owner{int(s.Uid), int(s.Gid)}, nil
}

// ModeOf is the permission bits with setuid, setgid and sticky, like stat -c %a.
func ModeOf(path string) (os.FileMode, error) {
	st, err := os.Stat(path)
	if err != nil {
		return 0, err
	}
	return st.Mode() & (os.ModePerm | os.ModeSetgid | os.ModeSetuid | os.ModeSticky), nil
}

// Chmod sets the mode including setgid (os.Chmod maps os.ModeSetgid).
func Chmod(path string, mode os.FileMode) error { return os.Chmod(path, mode) }

// SetOwnerMode chowns and chmods, both always tried; the first error is returned.
func SetOwnerMode(path string, o Owner, mode os.FileMode) error {
	err1 := Chown(path, o)
	err2 := Chmod(path, mode)
	return errors.Join(err1, err2)
}

// MkdirAs makes dir with owner and mode if it isn't there. The parent must exist.
func MkdirAs(dir string, o Owner, mode os.FileMode) error {
	if st, err := os.Stat(dir); err == nil && st.IsDir() {
		return nil
	}
	if err := os.Mkdir(dir, 0o700); err != nil && !errors.Is(err, fs.ErrExist) {
		return err
	}
	return SetOwnerMode(dir, o, mode)
}

// MkdirAllAs makes dir and its missing parents, each with owner and mode.
func MkdirAllAs(dir string, o Owner, mode os.FileMode) error {
	if st, err := os.Stat(dir); err == nil && st.IsDir() {
		return nil
	}
	parent := filepath.Dir(dir)
	if parent != dir {
		if err := MkdirAllAs(parent, o, mode); err != nil {
			return err
		}
	}
	return MkdirAs(dir, o, mode)
}

// CopyAtomic copies src to dst through dst.tmp and a rename, so dst is either the old
// file or the whole new one. dst.tmp is removed on failure.
func CopyAtomic(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	return WriteAtomic(dst, func(w io.Writer) error {
		_, err := io.Copy(w, in)
		return err
	})
}

// WriteFileAtomic writes data to path through path.tmp and a rename.
func WriteFileAtomic(path string, data []byte) error {
	return WriteAtomic(path, func(w io.Writer) error {
		_, err := w.Write(data)
		return err
	})
}

// WriteAtomic writes path.tmp with fill, syncs it and renames it over path. The new file
// gets mode 0600; callers set owner and mode afterwards.
func WriteAtomic(path string, fill func(io.Writer) error) error {
	tmp := path + ".tmp"
	_ = os.Remove(tmp)
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return err
	}
	err = fill(f)
	if err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Rename(tmp, path)
	}
	if err != nil {
		_ = os.Remove(tmp)
	}
	return err
}

// Touch makes an empty file if it isn't there (touch without changing times matters
// little here: the markers are only tested for existence).
func Touch(path string) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE, 0o600)
	if err != nil {
		return err
	}
	return f.Close()
}

// ReadTrim is $(cat file): the content without trailing newlines, "" if unreadable.
func ReadTrim(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimRight(string(b), "\n")
}

// WriteLine is echo "s" >file.
func WriteLine(path, s string) error {
	return os.WriteFile(path, []byte(s+"\n"), 0o600)
}

// SHA1 is sha1sum's hex digest of a file.
func SHA1(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha1.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// SHA1Bytes is the hex digest of data.
func SHA1Bytes(data []byte) string {
	s := sha1.Sum(data)
	return hex.EncodeToString(s[:])
}

// SameContent reports whether both files have the same SHA-1 (both must be readable).
func SameContent(a, b string) bool {
	x, err1 := SHA1(a)
	y, err2 := SHA1(b)
	return err1 == nil && err2 == nil && x == y
}

// ChownR is chown -R.
func ChownR(root string, o Owner) error {
	return filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		return Chown(p, o)
	})
}

// ChmodTree sets dirMode on every folder and fileMode on every file under root
// (find -type d -exec chmod, find -type f -exec chmod).
func ChmodTree(root string, dirMode, fileMode os.FileMode) error {
	return filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		switch {
		case d.IsDir():
			return Chmod(p, dirMode)
		case d.Type().IsRegular():
			return Chmod(p, fileMode)
		}
		return nil
	})
}

// FreeKB is the space an unprivileged app may still use on the file system of path, in
// KB, like df's Available column.
func FreeKB(path string) (int64, error) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err != nil {
		return 0, err
	}
	return int64(st.Bavail) * int64(st.Bsize) / 1024, nil
}

// ParseMode reads an octal mode like "2770" into an os.FileMode with the setgid bit.
func ParseMode(s string) (os.FileMode, error) {
	n, err := strconv.ParseUint(s, 8, 32)
	if err != nil {
		return 0, err
	}
	m := os.FileMode(n & 0o777)
	if n&0o4000 != 0 {
		m |= os.ModeSetuid
	}
	if n&0o2000 != 0 {
		m |= os.ModeSetgid
	}
	if n&0o1000 != 0 {
		m |= os.ModeSticky
	}
	return m, nil
}
