package emmc

import (
	"archive/zip"
	"compress/gzip"
	"crypto/sha256"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync/atomic"

	"github.com/ulikunitz/xz"
)

// CustomImage is an SD card image the advanced mode wrote to the eMMC in
// place of andr36oid.
type CustomImage struct {
	Name string `json:"name"`
	SHA  string `json:"sha256"`
	Size int64  `json:"size"` // bytes written, padded to whole sectors
}

// countFile counts how far into the image file the reading got, for the
// progress of compressed images.
type countFile struct {
	*os.File
	n atomic.Int64
}

func (f *countFile) Read(b []byte) (int, error) {
	n, err := f.File.Read(b)
	f.n.Add(int64(n))
	return n, err
}

func (f *countFile) ReadAt(b []byte, off int64) (int, error) {
	n, err := f.File.ReadAt(b, off)
	for end := off + int64(n); ; {
		old := f.n.Load()
		if end <= old || f.n.CompareAndSwap(old, end) {
			break
		}
	}
	return n, err
}

// imageReader is an image file's sectors: .img as it is, .img.gz, .img.xz,
// or the one .img in a .zip, padded with zeros to a whole last sector.
type imageReader struct {
	io.Reader
	file  *countFile
	total int64 // file size, for progress
	close func() error
}

func (r *imageReader) Close() error { return r.close() }

// read is how far into the file the reading got.
func (r *imageReader) read() int64 { return r.file.n.Load() }

func openImage(path string) (*imageReader, error) {
	fh, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	fi, err := fh.Stat()
	if err != nil || !fi.Mode().IsRegular() {
		fh.Close()
		return nil, fmt.Errorf("%s isn't a file", filepath.Base(path))
	}
	f := &countFile{File: fh}
	r := &imageReader{file: f, total: fi.Size(), close: fh.Close}
	name := strings.ToLower(path)
	switch {
	case strings.HasSuffix(name, ".img"):
		r.Reader = f
	case strings.HasSuffix(name, ".img.gz"):
		z, err := gzip.NewReader(f)
		if err != nil {
			fh.Close()
			return nil, fmt.Errorf("%s: %w", filepath.Base(path), err)
		}
		r.Reader = z
	case strings.HasSuffix(name, ".img.xz"):
		z, err := xz.NewReader(f)
		if err != nil {
			fh.Close()
			return nil, fmt.Errorf("%s: %w", filepath.Base(path), err)
		}
		r.Reader = z
	case strings.HasSuffix(name, ".zip"):
		z, err := zip.NewReader(f, fi.Size())
		if err != nil {
			fh.Close()
			return nil, fmt.Errorf("%s: %w", filepath.Base(path), err)
		}
		var imgs []*zip.File
		for _, e := range z.File {
			if !e.FileInfo().IsDir() && strings.HasSuffix(strings.ToLower(e.Name), ".img") {
				imgs = append(imgs, e)
			}
		}
		if len(imgs) != 1 {
			fh.Close()
			return nil, fmt.Errorf("%s holds %d .img files, not one", filepath.Base(path), len(imgs))
		}
		rc, err := imgs[0].Open()
		if err != nil {
			fh.Close()
			return nil, fmt.Errorf("%s: %w", filepath.Base(path), err)
		}
		r.Reader = rc
		r.close = func() error { rc.Close(); return fh.Close() }
	default:
		fh.Close()
		return nil, fmt.Errorf("%s: not an .img, .img.gz, .img.xz or .zip", filepath.Base(path))
	}
	r.Reader = &padReader{r: r.Reader}
	return r, nil
}

// padReader ends a stream on a whole sector, with zeros.
type padReader struct {
	r   io.Reader
	n   int64
	eof bool
}

func (p *padReader) Read(b []byte) (int, error) {
	if !p.eof {
		n, err := p.r.Read(b)
		p.n += int64(n)
		if err == io.EOF {
			p.eof = true
			err = nil
		}
		if n > 0 || err != nil {
			return n, err
		}
	}
	pad := (Sector - p.n%Sector) % Sector
	if pad == 0 {
		return 0, io.EOF
	}
	n := int(min(pad, int64(len(b))))
	clear(b[:n])
	p.n += int64(n)
	return n, nil
}

// readImage streams the image through fn in chunks, each at its byte offset,
// and gives its length and hash.
func readImage(op *Op, path string, fn func(b []byte, off int64) error) (int64, string, error) {
	r, err := openImage(path)
	if err != nil {
		return 0, "", err
	}
	defer r.Close()
	h := sha256.New()
	b := buf(chunk)
	var off int64
	for {
		n, err := io.ReadFull(r, b)
		if n > 0 {
			h.Write(b[:n])
			if fn != nil {
				if err := fn(b[:n], off); err != nil {
					return 0, "", err
				}
			}
			off += int64(n)
			op.Tick(r.read())
		}
		if err == io.EOF || err == io.ErrUnexpectedEOF {
			break
		}
		if err != nil {
			return 0, "", fmt.Errorf("%s: %w", filepath.Base(path), err)
		}
	}
	return off, sum(h.Sum(nil)), nil
}

func fileSize(path string) int64 {
	fi, err := os.Stat(path)
	if err != nil {
		return 0
	}
	return fi.Size()
}

// ImageFile is an image ListImages found.
type ImageFile struct {
	Name string `json:"name"`
	Path string `json:"path"` // as WriteImage takes it
	Size int64  `json:"size"` // file size: packed images are bigger once unpacked
}

// sdBootPrefix marks an image on the SD card's BOOT partition, which is only
// mounted while it is read.
const sdBootPrefix = "sdboot:"

func imageName(name string) bool {
	n := strings.ToLower(name)
	for _, ext := range []string{".img", ".img.gz", ".img.xz", ".zip"} {
		if strings.HasSuffix(n, ext) {
			return true
		}
	}
	return false
}

// ListImages lists the images in dirs and on the SD card's BOOT partition
// (not in their subfolders), biggest first. A folder that can't be read is
// left out.
func (c *Config) ListImages(dirs []string) []ImageFile {
	out := []ImageFile{}
	scan := func(dir string) {
		ents, _ := os.ReadDir(dir)
		for _, e := range ents {
			if !e.Type().IsRegular() || !imageName(e.Name()) {
				continue
			}
			fi, err := e.Info()
			if err != nil {
				continue
			}
			out = append(out, ImageFile{e.Name(), filepath.Join(dir, e.Name()), fi.Size()})
		}
	}
	for _, d := range dirs {
		scan(d)
	}
	c.withSDBoot(false, func(dir string) error {
		n := len(out)
		scan(dir)
		for i := n; i < len(out); i++ {
			out[i].Path = sdBootPrefix + out[i].Name
		}
		return nil
	})
	sort.SliceStable(out, func(i, j int) bool { return out[i].Size > out[j].Size })
	return out
}

// WriteImage writes any SD card image to the eMMC, in place of andr36oid
// (the advanced mode: the user takes the risk). It needs a checked stock
// backup of this eMMC, so restore stays possible. The image is read once for
// its size and hash, written with its loader area (sectors 64 to 32767)
// blanked first and written last, header sector last of all, so a cut-off
// write leaves no half loader that starts, then read back whole. A last
// partial sector is padded with zeros.
func (c *Config) WriteImage(p *Progress, path string) error {
	if name, ok := strings.CutPrefix(path, sdBootPrefix); ok {
		// on BOOT: mounted for the whole write, which also takes away the
		// test start file there
		if name != filepath.Base(name) || !imageName(name) {
			return fmt.Errorf("%q isn't an image on BOOT", name)
		}
		return c.withSDBoot(true, func(dir string) error {
			return c.writeImage(p, filepath.Join(dir, name), func() error { return removeTest(dir) })
		})
	}
	return c.writeImage(p, path, func() error { return c.withSDBoot(true, removeTest) })
}

func (c *Config) writeImage(p *Progress, path string, noTest func() error) error {
	r, err := c.begin(p, "write-image")
	if err != nil {
		return err
	}
	if err := r.backupReady(); err != nil {
		return err
	}
	name := filepath.Base(path)
	op := p.Start("Read the image", "SD", 0, 0, fileSize(path))
	size, sha, err := readImage(op, path, nil)
	if err != nil {
		return op.Fail(err)
	}
	switch {
	case size == 0:
		return op.Fail(fmt.Errorf("%s is empty", name))
	case size > r.info.Sectors*Sector:
		return op.Fail(fmt.Errorf("%s is %d MiB, the eMMC only %d MiB", name, size>>20, r.info.Sectors*Sector>>20))
	}
	op.OK(sha, "%d MiB", size>>20)
	p.Logf("image %s: %d bytes, sha256 %s", name, size, sha)
	if err := noTest(); err != nil {
		return err
	}
	r.st.LoaderSHA, r.st.Step1, r.st.Step1BootID = "", "", ""
	r.st.Step2, r.st.Plan, r.st.Copied, r.st.CopiedLn, r.st.Step3 = "", nil, nil, nil, ""
	r.st.Custom, r.st.Incomplete = nil, ""
	if err := r.mark("write-image"); err != nil {
		return err
	}
	err = r.unlocked(func(e *Disk) error {
		if err := zeroSpan(p, "the loader area", e, span{LoaderStart, LoaderEnd}); err != nil {
			return err
		}
		// the old partition table's copy at the eMMC's end, if the image
		// doesn't reach it
		if tail := TailOffset(r.info.Sectors); size <= tail {
			if err := zeroSpan(p, "the old partition table's copy", e, span{tail / Sector, r.info.Sectors}); err != nil {
				return err
			}
		}
		lo, hi := int64(LoaderStart*Sector), min(int64(LoaderEnd*Sector), size)
		var loader []byte
		if hi > lo {
			loader = buf(int(hi - lo))
		}
		op := p.Start("Write "+name, "eMMC", 0, (size+Sector-1)/Sector, fileSize(path))
		write := func(b []byte, off int64) error {
			if len(b) == 0 {
				return nil
			}
			return e.WriteAt(b, off)
		}
		n, got, err := readImage(op, path, func(b []byte, off int64) error {
			end := off + int64(len(b))
			// before, inside and after the loader area
			if err := write(b[:max(0, min(end, lo)-off)], off); err != nil {
				return err
			}
			if a, z := max(off, lo), min(end, hi); a < z {
				copy(loader[a-lo:], b[a-off:z-off])
			}
			if a := max(off, int64(LoaderEnd*Sector)); a < end {
				return write(b[a-off:], a)
			}
			return nil
		})
		if err != nil {
			return op.Fail(err)
		}
		if n != size || got != sha {
			return op.Fail(fmt.Errorf("%s reads differently the second time: is the SD card failing?", name))
		}
		if len(loader) > Sector {
			if err := e.WriteAt(loader[Sector:], lo+Sector); err != nil {
				return op.Fail(err)
			}
		}
		if len(loader) > 0 {
			if err := e.WriteAt(loader[:Sector], lo); err != nil {
				return op.Fail(err)
			}
		}
		if err := e.Sync(); err != nil {
			return op.Fail(err)
		}
		op.OK(sha, "written")
		op = p.Start("Check "+name+" on the eMMC", "eMMC", 0, size/Sector, size)
		back, err := e.HashRange(0, size, op.Tick)
		if err != nil {
			return op.Fail(err)
		}
		if back != sha {
			return op.Fail(fmt.Errorf("%s reads back different from the eMMC: it may not start; write it again or restore the stock system", name))
		}
		op.OK(sha, "the same")
		return nil
	})
	if err != nil {
		return r.stop(err)
	}
	r.st.Custom, r.st.Running = &CustomImage{Name: name, SHA: sha, Size: size}, ""
	p.Logf("%s is on the eMMC; take the SD card out to start it", name)
	return c.Save(r.st)
}
