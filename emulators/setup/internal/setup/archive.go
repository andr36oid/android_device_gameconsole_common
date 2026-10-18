package setup

import (
	"archive/tar"
	"compress/gzip"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// countingReader counts the bytes read, for the setup screen's progress.
type countingReader struct {
	r  io.Reader
	fn func(int64)
}

func (c *countingReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	if n > 0 && c.fn != nil {
		c.fn(int64(n))
	}
	return n, err
}

// safeRel checks an archive path: relative, no "..", no leading "/".
func safeRel(name string) (string, bool) {
	name = strings.TrimPrefix(name, "./")
	if name == "" || strings.HasPrefix(name, "/") {
		return "", false
	}
	for _, part := range strings.Split(name, "/") {
		if part == ".." {
			return "", false
		}
	}
	return strings.TrimSuffix(name, "/"), true
}

// extractTarGz unpacks a .tar.gz into dir (which must exist): folders and regular files
// only, owned by whoever runs this (tar -o). It reads the whole archive: a cut-off or
// broken one is an error (toybox's tar said nothing and exited 0 on those). progress is
// called with the compressed bytes read.
func extractTarGz(archive, dir string, progress func(int64)) error {
	f, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer f.Close()
	zr, err := gzip.NewReader(&countingReader{r: f, fn: progress})
	if err != nil {
		return err
	}
	defer zr.Close()
	tr := tar.NewReader(zr)
	for {
		h, err := tr.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return err
		}
		rel, ok := safeRel(h.Name)
		if !ok {
			continue
		}
		dest := filepath.Join(dir, rel)
		switch h.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(dest, 0o755); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
				return err
			}
			out, err := os.OpenFile(dest, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o644)
			if err != nil {
				return err
			}
			_, err = io.Copy(out, tr)
			if cerr := out.Close(); err == nil {
				err = cerr
			}
			if err != nil {
				return err
			}
		}
	}
	// the gzip stream must end properly too
	if _, err := io.Copy(io.Discard, zr); err != nil {
		return err
	}
	return nil
}

// tarGzList lists the regular files of a .tar.gz; an error for a broken archive.
func tarGzList(archive string) ([]string, error) {
	f, err := os.Open(archive)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	zr, err := gzip.NewReader(f)
	if err != nil {
		return nil, err
	}
	tr := tar.NewReader(zr)
	var names []string
	for {
		h, err := tr.Next()
		if errors.Is(err, io.EOF) {
			return names, nil
		}
		if err != nil {
			return nil, err
		}
		if h.Typeflag == tar.TypeReg {
			names = append(names, strings.TrimPrefix(h.Name, "./"))
		}
	}
}

// tarGzEach calls fn for every regular file of a .tar.gz, in archive order, with a reader
// for its content.
func tarGzEach(archive string, fn func(name string, r io.Reader) error) error {
	f, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer f.Close()
	zr, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	tr := tar.NewReader(zr)
	for {
		h, err := tr.Next()
		if errors.Is(err, io.EOF) {
			return nil
		}
		if err != nil {
			return err
		}
		if h.Typeflag != tar.TypeReg {
			continue
		}
		if err := fn(strings.TrimPrefix(h.Name, "./"), tr); err != nil {
			return err
		}
	}
}
