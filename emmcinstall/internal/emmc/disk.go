package emmc

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"syscall"
	"unsafe"
)

// Sector is the unit of every offset here: 512 bytes, as the GPT and the
// bootloader offsets count.
const Sector = 512

// chunk is how much is read or written at a time.
const chunk = 4 << 20

// Disk is a block device (or, in the host tests, an image file). Reads and
// writes go around the page cache (O_DIRECT), so a read after a write comes
// from the device, not from memory.
type Disk struct {
	f    *os.File
	Path string
	Size int64 // bytes
}

// OpenDisk opens path for reading, or for reading and writing. direct=false
// is only for host tests on file systems without O_DIRECT.
func OpenDisk(path string, write, direct bool) (*Disk, error) {
	flag := os.O_RDONLY
	if write {
		flag = os.O_RDWR
	}
	if direct {
		flag |= syscall.O_DIRECT
	}
	f, err := os.OpenFile(path, flag, 0)
	if err != nil {
		return nil, err
	}
	size, err := f.Seek(0, io.SeekEnd)
	if err != nil {
		f.Close()
		return nil, err
	}
	if size <= 0 || size%Sector != 0 {
		f.Close()
		return nil, fmt.Errorf("%s: size %d is not whole sectors", path, size)
	}
	return &Disk{f: f, Path: path, Size: size}, nil
}

func (d *Disk) Close() error { return d.f.Close() }

// Sectors is the disk's size in sectors.
func (d *Disk) Sectors() int64 { return d.Size / Sector }

// Sync makes sure everything written is on the device (a cache flush on eMMC).
func (d *Disk) Sync() error { return d.f.Sync() }

func (d *Disk) check(off, n int64) error {
	if off < 0 || n < 0 || off%Sector != 0 || n%Sector != 0 || off+n > d.Size {
		return fmt.Errorf("%s: range %d+%d outside the disk (%d bytes) or not whole sectors",
			d.Path, off, n, d.Size)
	}
	return nil
}

// testHooks let the host tests see and spoil reads and writes.
var testHooks struct {
	read  func(d *Disk, b []byte, off int64)
	write func(d *Disk, b []byte, off int64) ([]byte, error)
}

// ReadAt reads exactly len(b) bytes at off. b must come from buf().
func (d *Disk) ReadAt(b []byte, off int64) error {
	if err := d.check(off, int64(len(b))); err != nil {
		return err
	}
	n, err := d.f.ReadAt(b, off)
	if n == len(b) {
		if testHooks.read != nil {
			testHooks.read(d, b, off)
		}
		return nil
	}
	if err == nil || err == io.EOF {
		err = io.ErrUnexpectedEOF
	}
	return fmt.Errorf("%s: read at sector %d: %w", d.Path, off/Sector, err)
}

// WriteAt writes all of b at off. b must come from buf().
func (d *Disk) WriteAt(b []byte, off int64) error {
	if err := d.check(off, int64(len(b))); err != nil {
		return err
	}
	if testHooks.write != nil {
		var err error
		if b, err = testHooks.write(d, b, off); err != nil {
			return fmt.Errorf("%s: write at sector %d: %w", d.Path, off/Sector, err)
		}
	}
	n, err := d.f.WriteAt(b, off)
	if n == len(b) && err == nil {
		return nil
	}
	if err == nil {
		err = io.ErrShortWrite
	}
	return fmt.Errorf("%s: write at sector %d: %w", d.Path, off/Sector, err)
}

// buf returns a zeroed buffer of n bytes aligned for O_DIRECT.
func buf(n int) []byte {
	b := make([]byte, n+4096)
	skip := int(uintptr(unsafe.Pointer(&b[0])) & 4095)
	if skip != 0 {
		skip = 4096 - skip
	}
	return b[skip : skip+n]
}

func sum(h []byte) string { return hex.EncodeToString(h) }

// Ticker reports bytes done for one operation; it is called after every chunk.
type Ticker func(done int64)

// HashRange reads n bytes at off and returns their sha256.
func (d *Disk) HashRange(off, n int64, tick Ticker) (string, error) {
	h := sha256.New()
	b := buf(chunk)
	for done := int64(0); done < n; {
		m := min(int64(chunk), n-done)
		if err := d.ReadAt(b[:m], off+done); err != nil {
			return "", err
		}
		h.Write(b[:m])
		done += m
		if tick != nil {
			tick(done)
		}
	}
	return sum(h.Sum(nil)), nil
}

// IsZero reports whether n bytes at off are all zero.
func (d *Disk) IsZero(off, n int64, tick Ticker) (bool, error) {
	b := buf(chunk)
	for done := int64(0); done < n; {
		m := min(int64(chunk), n-done)
		if err := d.ReadAt(b[:m], off+done); err != nil {
			return false, err
		}
		for _, c := range b[:m] {
			if c != 0 {
				return false, nil
			}
		}
		done += m
		if tick != nil {
			tick(done)
		}
	}
	return true, nil
}

// Zero writes n zero bytes at off.
func (d *Disk) Zero(off, n int64, tick Ticker) error {
	b := buf(chunk)
	for done := int64(0); done < n; {
		m := min(int64(chunk), n-done)
		if err := d.WriteAt(b[:m], off+done); err != nil {
			return err
		}
		done += m
		if tick != nil {
			tick(done)
		}
	}
	return d.Sync()
}

// CopyTo copies n bytes at srcOff to dst at dstOff and returns the sha256 of
// what was read.
func (d *Disk) CopyTo(srcOff int64, dst *Disk, dstOff, n int64, tick Ticker) (string, error) {
	h := sha256.New()
	b := buf(chunk)
	for done := int64(0); done < n; {
		m := min(int64(chunk), n-done)
		if err := d.ReadAt(b[:m], srcOff+done); err != nil {
			return "", err
		}
		h.Write(b[:m])
		if err := dst.WriteAt(b[:m], dstOff+done); err != nil {
			return "", err
		}
		done += m
		if tick != nil {
			tick(done)
		}
	}
	return sum(h.Sum(nil)), dst.Sync()
}

// ReadBytes returns n bytes at off.
func (d *Disk) ReadBytes(off, n int64) ([]byte, error) {
	b := buf(int(n))
	if err := d.ReadAt(b, off); err != nil {
		return nil, err
	}
	out := make([]byte, n)
	copy(out, b)
	return out, nil
}

// WriteBytes writes p at off and syncs.
func (d *Disk) WriteBytes(p []byte, off int64) error {
	b := buf(len(p))
	copy(b, p)
	if err := d.WriteAt(b, off); err != nil {
		return err
	}
	return d.Sync()
}

var errVerify = errors.New("verify failed")
