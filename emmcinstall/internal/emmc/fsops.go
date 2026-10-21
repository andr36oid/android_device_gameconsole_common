package emmc

import (
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"unsafe"
)

// File systems inside a partition of a whole disk: the data card steps make
// and fill BOOT, EASYROMS and the eMMC's BOOT through a loop device over the
// disk at the partition's offset. The kernel's own partition nodes aren't
// used: a table written here is only read again at the next start, and vold
// would mount new partitions while they are being filled. The host tests
// replace all of it (fsOps) with folders.
type fsOps struct {
	// mount runs fn with the file system in span s of dev mounted at a
	// folder, read-only unless rw.
	mount func(c *Config, dev string, s span, fstype string, rw bool, fn func(dir string) error) error
	// mkfs makes a new, empty file system in span s of dev.
	mkfs func(c *Config, dev string, s span, fstype, label string) error
	// unmountVol has vold let go of a partition ("major:minor").
	unmountVol func(c *Config, majMin string) error
	// used is what the file system mounted at dir holds, in bytes.
	used func(dir string) (int64, error)
}

func (c *Config) fsops() *fsOps {
	if c.fs != nil {
		return c.fs
	}
	return &fsOps{mount: loopMount, mkfs: loopMkfs, unmountVol: smUnmount, used: usedBytes}
}

// Loop devices (linux/loop.h)
const (
	loopSetFd       = 0x4C00
	loopClrFd       = 0x4C01
	loopSetStatus64 = 0x4C04
	loopCtlGetFree  = 0x4C82
	loFlagsReadOnly = 1
)

type loopInfo64 struct {
	Device, Inode, Rdevice, Offset, SizeLimit  uint64
	Number, EncryptType, EncryptKeySize, Flags uint32
	FileName, CryptName                        [64]byte
	EncryptKey                                 [32]byte
	Init                                       [2]uint64
}

func ioctl(fd uintptr, req, arg uintptr) error {
	if _, _, e := syscall.Syscall(syscall.SYS_IOCTL, fd, req, arg); e != 0 {
		return e
	}
	return nil
}

// withLoop runs fn with a loop device over span s of dev.
func (c *Config) withLoop(dev string, s span, rw bool, fn func(loop string) error) (err error) {
	ctl, err := os.OpenFile("/dev/loop-control", os.O_RDWR, 0)
	if err != nil {
		return err
	}
	n, _, e := syscall.Syscall(syscall.SYS_IOCTL, ctl.Fd(), loopCtlGetFree, 0)
	ctl.Close()
	if e != 0 {
		return fmt.Errorf("no free loop device: %w", e)
	}
	path := ""
	for _, p := range []string{"/dev/block/loop", "/dev/loop"} {
		if _, err := os.Stat(p + strconv.Itoa(int(n))); err == nil {
			path = p + strconv.Itoa(int(n))
			break
		}
	}
	if path == "" {
		// ueventd hasn't made the node yet
		if err := os.MkdirAll(c.BootMnt+".dev", 0700); err != nil {
			return err
		}
		path = filepath.Join(c.BootMnt+".dev", "loop"+strconv.Itoa(int(n)))
		os.Remove(path)
		if err := syscall.Mknod(path, syscall.S_IFBLK|0600, int(7<<8|n&0xff|(n&^0xff)<<12)); err != nil {
			return err
		}
		defer os.Remove(path)
	}
	flag := os.O_RDONLY
	if rw {
		flag = os.O_RDWR
	}
	back, err := os.OpenFile(dev, flag, 0)
	if err != nil {
		return err
	}
	defer back.Close()
	lf, err := os.OpenFile(path, flag, 0)
	if err != nil {
		return err
	}
	defer lf.Close()
	if err := ioctl(lf.Fd(), loopSetFd, back.Fd()); err != nil {
		return fmt.Errorf("loop device %s: %w", path, err)
	}
	defer func() {
		if cerr := ioctl(lf.Fd(), loopClrFd, 0); cerr != nil && err == nil {
			err = fmt.Errorf("loop device %s doesn't let go: %w", path, cerr)
		}
	}()
	info := loopInfo64{Offset: uint64(s.off()), SizeLimit: uint64(s.n())}
	if !rw {
		info.Flags = loFlagsReadOnly
	}
	if err := ioctl(lf.Fd(), loopSetStatus64, uintptr(unsafe.Pointer(&info))); err != nil {
		return fmt.Errorf("loop device %s: %w", path, err)
	}
	if err := fn(path); err != nil {
		return err
	}
	if rw {
		// the loop device writes through the disk's page cache
		if err := lf.Sync(); err != nil {
			return err
		}
		return back.Sync()
	}
	return nil
}

func loopMount(c *Config, dev string, s span, fstype string, rw bool, fn func(dir string) error) error {
	return c.withLoop(dev, s, rw, func(loop string) error {
		dir := c.BootMnt + ".fs"
		if err := os.MkdirAll(dir, 0700); err != nil {
			return err
		}
		flags := uintptr(syscall.MS_NOATIME | syscall.MS_NOSUID | syscall.MS_NODEV | syscall.MS_NOEXEC)
		if !rw {
			flags |= syscall.MS_RDONLY
		}
		data := ""
		if fstype == "vfat" || fstype == "exfat" {
			data = "utf8"
		}
		if err := syscall.Mount(loop, dir, fstype, flags, data); err != nil {
			return fmt.Errorf("%s doesn't mount: %w", fstype, err)
		}
		err := fn(dir)
		if uerr := syscall.Unmount(dir, 0); uerr != nil && err == nil {
			err = fmt.Errorf("%s doesn't unmount: %w", fstype, uerr)
		}
		return err
	})
}

func loopMkfs(c *Config, dev string, s span, fstype, label string) error {
	return c.withLoop(dev, s, true, func(loop string) error {
		var cmd *exec.Cmd
		switch fstype {
		case "exfat":
			cmd = exec.Command("/system/bin/mkfs.exfat", "-L", label, loop)
		default:
			return fmt.Errorf("can't make %s", fstype)
		}
		if out, err := cmd.CombinedOutput(); err != nil {
			return fmt.Errorf("mkfs.%s: %v: %s", fstype, err, strings.TrimSpace(string(out)))
		}
		return nil
	})
}

// smUnmount asks vold, through the storage service, to unmount the public
// volume on a partition (vold names them public:major,minor).
func smUnmount(c *Config, majMin string) error {
	out, err := exec.Command("/system/bin/sm", "unmount", "public:"+strings.Replace(majMin, ":", ",", 1)).CombinedOutput()
	if err != nil {
		return fmt.Errorf("sm unmount: %v: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

// writeBootFile writes a small file on a FAT folder and reads it back.
func writeBootFile(dir, name, content string) error {
	path := filepath.Join(dir, name)
	f, err := os.OpenFile(path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0644)
	if err != nil {
		return err
	}
	_, err = f.WriteString(content)
	if serr := f.Sync(); err == nil {
		err = serr
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return err
	}
	if err := syncDir(dir); err != nil {
		return err
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	if string(b) != content {
		return fmt.Errorf("%s reads back as %q", name, b)
	}
	return nil
}

// removeBootFile takes a file away; not there is fine.
func removeBootFile(dir, name string) error {
	err := os.Remove(filepath.Join(dir, name))
	if err == nil {
		return syncDir(dir)
	}
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

// copyTree copies the files and folders under src into dst (FAT or exFAT:
// no links, owners or modes), with progress over total bytes.
func copyTree(p *Progress, what, src, dst string, total int64) error {
	op := p.Start("Copy "+what, "SD > new card", 0, 0, total)
	var done int64
	b := make([]byte, chunk)
	err := filepath.Walk(src, func(path string, fi os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(src, path)
		if rel == "." {
			return nil
		}
		if strings.EqualFold(strings.Split(rel, string(filepath.Separator))[0], "System Volume Information") {
			if fi.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		to := filepath.Join(dst, rel)
		switch {
		case fi.IsDir():
			return os.MkdirAll(to, 0755)
		case !fi.Mode().IsRegular():
			p.Logf("left out (not a plain file): %s", rel)
			return nil
		}
		in, err := os.Open(path)
		if err != nil {
			return err
		}
		defer in.Close()
		out, err := os.OpenFile(to, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0644)
		if err != nil {
			return err
		}
		n, err := io.CopyBuffer(out, &tickReader{r: in, done: &done, tick: op.Tick}, b)
		if err == nil && n != fi.Size() {
			err = fmt.Errorf("%s: %d bytes copied, %d expected", rel, n, fi.Size())
		}
		if serr := out.Sync(); err == nil {
			err = serr
		}
		if cerr := out.Close(); err == nil {
			err = cerr
		}
		if err == nil {
			os.Chtimes(to, fi.ModTime(), fi.ModTime())
		}
		return err
	})
	if err != nil {
		return op.Fail(err)
	}
	op.OK("", "%d MiB copied", done>>20)
	return nil
}

type tickReader struct {
	r    io.Reader
	done *int64
	tick Ticker
}

func (t *tickReader) Read(b []byte) (int, error) {
	n, err := t.r.Read(b)
	*t.done += int64(n)
	t.tick(*t.done)
	return n, err
}

// usedBytes is what a mounted file system holds.
func usedBytes(dir string) (int64, error) {
	var s syscall.Statfs_t
	if err := syscall.Statfs(dir, &s); err != nil {
		return 0, err
	}
	return int64(s.Blocks-s.Bfree) * int64(s.Bsize), nil
}

// treeBytes is the size of the files under dir.
func treeBytes(dir string) (int64, error) {
	var n int64
	err := filepath.Walk(dir, func(_ string, fi os.FileInfo, err error) error {
		if err == nil && fi.Mode().IsRegular() {
			n += fi.Size()
		}
		return err
	})
	return n, err
}
