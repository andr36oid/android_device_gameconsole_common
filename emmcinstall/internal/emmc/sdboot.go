package emmc

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

// TestFile is on the SD card's BOOT while the eMMC system is tested (step 2):
// boot.ini starts from the eMMC once when it says emmc_test=start and marks it
// tried first (U-Boot fatwrite), the eMMC system sets it to ok once it is up.
const TestFile = "emmc-test.txt"

// withSDBoot runs fn with the SD card's BOOT mounted (read-only unless rw).
func (c *Config) withSDBoot(rw bool, fn func(dir string) error) error {
	if c.SDBootDir != "" {
		return fn(c.SDBootDir)
	}
	if err := os.MkdirAll(c.BootMnt, 0700); err != nil {
		return err
	}
	flags := uintptr(syscall.MS_NOATIME | syscall.MS_NOSUID | syscall.MS_NODEV | syscall.MS_NOEXEC)
	if !rw {
		flags |= syscall.MS_RDONLY
	}
	if err := syscall.Mount(c.SDBoot, c.BootMnt, "vfat", flags, "utf8"); err != nil {
		return fmt.Errorf("SD card BOOT doesn't mount: %w", err)
	}
	err := fn(c.BootMnt)
	if uerr := syscall.Unmount(c.BootMnt, 0); uerr != nil && err == nil {
		err = fmt.Errorf("SD card BOOT doesn't unmount: %w", uerr)
	}
	return err
}

// parseTest reads key=value lines (U-Boot's env export may end in a NUL).
func parseTest(b []byte) map[string]string {
	out := map[string]string{}
	for _, l := range strings.Split(strings.ReplaceAll(string(b), "\x00", ""), "\n") {
		k, v, ok := strings.Cut(strings.TrimSpace(l), "=")
		if ok {
			out[k] = v
		}
	}
	return out
}

// readTest returns the test file's emmc_test value ("" with no file) and its
// fields.
func readTest(dir string) (string, map[string]string, error) {
	b, err := os.ReadFile(filepath.Join(dir, TestFile))
	if errors.Is(err, os.ErrNotExist) {
		return "", nil, nil
	}
	if err != nil {
		return "", nil, err
	}
	f := parseTest(b)
	return f["emmc_test"], f, nil
}

// writeTest writes the test file and reads it back.
func writeTest(dir, content string) error {
	path := filepath.Join(dir, TestFile)
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
		return fmt.Errorf("%s reads back as %q", TestFile, b)
	}
	return nil
}

func removeTest(dir string) error {
	err := os.Remove(filepath.Join(dir, TestFile))
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	return syncDir(dir)
}
