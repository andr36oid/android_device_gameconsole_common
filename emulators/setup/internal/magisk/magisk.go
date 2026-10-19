// Package magisk keeps root as new as the Magisk app. Root lives in ramdisk.uimg on the
// BOOT partition (device mkimg.sh, common/magisk/inject_magisk.sh), and there is no boot
// image for the app's own "Direct install" to patch. So when the app was updated (F-Droid,
// GitHub) past the root the console starts with, the start rebuilds ramdisk.uimg from the
// plain ramdisk-stock.uimg with the new app's files, the same steps as inject_magisk.sh,
// shows "Rebuilding Magisk RAM disk ..." on the boot animation and restarts into it.
//
// Each app version is tried once (a marker in State): a rebuild that somehow still starts
// the old root can't loop. The ramdisk it replaces stays on BOOT as
// ramdisk-magisk-previous.uimg.
package magisk

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"hash/crc32"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const pkg = "com.topjohnwu.magisk"

// Paths are every place the rebuild reads or writes; the tests point them into a folder.
type Paths struct {
	Magisk   string // the running root's magisk binary (prints its version with -V)
	Packages string // /data/system/packages.xml: the installed app's version and folder
	BootDev  string // the BOOT partition
	BootMnt  string // where it is mounted for the rebuild ("" = BootDev is a folder already)
	Work     string // scratch folder, removed afterwards
	Tried    string // marker: the app versionCode last tried
}

// Defaults are the console's paths.
func Defaults() Paths {
	return Paths{
		Magisk:   "/debug_ramdisk/magisk",
		Packages: "/data/system/packages.xml",
		BootDev:  "/dev/block/platform/ff370000.dwmmc/by-name/BOOT",
		BootMnt:  "/mnt/andr36oid-magisk-boot",
		Work:     "/data/local/tmp/andr36oid-magisk",
		Tried:    "/data/misc/gameconsole/magisk-rebuild.tried",
	}
}

// Rebuilder does one check per start.
type Rebuilder struct {
	P       Paths
	Log     func(format string, args ...any)
	SetProp func(name, value string) error
	// Run runs magiskboot (the app's arm64 one) in dir; tests swap it
	Run func(dir, name string, args ...string) error
}

// New is the console's Rebuilder.
func New(log func(string, ...any), setProp func(string, string) error) *Rebuilder {
	return &Rebuilder{P: Defaults(), Log: log, SetProp: setProp, Run: run}
}

func run(dir, name string, args ...string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Dir = dir
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s %s: %v: %s", filepath.Base(name), strings.Join(args, " "), err,
			strings.TrimSpace(string(out)))
	}
	return nil
}

// Check rebuilds the ramdisk when the app is newer than the root. It reports whether a new
// ramdisk was written (the caller restarts then).
func (r *Rebuilder) Check() (bool, error) {
	rootVC := r.rootVersion()
	if rootVC == 0 {
		return false, nil // no Magisk root (stock ramdisk): nothing to keep up to date
	}
	appVC, apk := r.appVersion()
	if appVC <= rootVC || apk == "" {
		return false, nil
	}
	if b, err := os.ReadFile(r.P.Tried); err == nil && strings.TrimSpace(string(b)) == strconv.Itoa(appVC) {
		return false, nil
	}
	if err := os.WriteFile(r.P.Tried, []byte(strconv.Itoa(appVC)+"\n"), 0o600); err != nil {
		return false, fmt.Errorf("marker: %w", err)
	}
	r.Log("magisk: root %d, app %d: rebuilding the ramdisk from %s", rootVC, appVC, apk)
	r.SetProp("sys.andr36oid.resizing", "3")
	defer r.SetProp("sys.andr36oid.resizing", "0")
	if err := r.rebuild(apk); err != nil {
		return false, err
	}
	r.Log("magisk: ramdisk.uimg now has Magisk %d", appVC)
	return true, nil
}

func (r *Rebuilder) rootVersion() int {
	out, err := exec.Command(r.P.Magisk, "-V").Output()
	if err != nil {
		return 0
	}
	n, _ := strconv.Atoi(strings.TrimSpace(string(out)))
	return n
}

var packageTag = regexp.MustCompile(`<package\s[^>]*name="` + regexp.QuoteMeta(pkg) + `"[^>]*>`)
var attr = regexp.MustCompile(`\s(codePath|version)="([^"]*)"`)

// appVersion reads packages.xml (text XML on Android 11): the package manager may not be
// up yet this early in the start.
func (r *Rebuilder) appVersion() (int, string) {
	b, err := os.ReadFile(r.P.Packages)
	if err != nil {
		return 0, ""
	}
	tag := packageTag.Find(b)
	if tag == nil {
		return 0, ""
	}
	var vc int
	var dir string
	for _, m := range attr.FindAllSubmatch(tag, -1) {
		switch string(m[1]) {
		case "version":
			vc, _ = strconv.Atoi(string(m[2]))
		case "codePath":
			dir = string(m[2])
		}
	}
	apk := filepath.Join(dir, "base.apk")
	if dir == "" || !isFile(apk) {
		return vc, ""
	}
	return vc, apk
}

func (r *Rebuilder) rebuild(apk string) error {
	boot := r.P.BootDev
	if r.P.BootMnt != "" {
		if err := os.MkdirAll(r.P.BootMnt, 0o700); err != nil {
			return err
		}
		if err := syscall.Mount(r.P.BootDev, r.P.BootMnt, "vfat", syscall.MS_NOATIME, ""); err != nil {
			return fmt.Errorf("mount BOOT: %w", err)
		}
		defer syscall.Unmount(r.P.BootMnt, 0)
		boot = r.P.BootMnt
	}
	stock, err := readUImage(filepath.Join(boot, "ramdisk-stock.uimg"))
	if err != nil {
		return err
	}
	cur := filepath.Join(boot, "ramdisk.uimg")
	header, err := os.ReadFile(cur)
	if err != nil || len(header) < 64 {
		return fmt.Errorf("ramdisk.uimg: %v", err)
	}

	os.RemoveAll(r.P.Work)
	if err := os.MkdirAll(r.P.Work, 0o700); err != nil {
		return err
	}
	defer os.RemoveAll(r.P.Work)
	w := r.P.Work
	if err := extract(apk, w, map[string]string{
		"lib/arm64-v8a/libmagiskboot.so": "magiskboot",
		"lib/arm64-v8a/libmagiskinit.so": "magiskinit",
		"lib/arm64-v8a/libmagisk.so":     "magisk",
		"lib/arm64-v8a/libinit-ld.so":    "init-ld",
		"assets/stub.apk":                "stub.apk",
	}); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(w, "ramdisk.img"), stock, 0o600); err != nil {
		return err
	}
	// the same config as inject_magisk.sh writes
	config := "KEEPVERITY=false\nKEEPFORCEENCRYPT=false\nRECOVERYMODE=false\nVENDORBOOT=false\nPREINITDEVICE=metadata\n"
	if err := os.WriteFile(filepath.Join(w, "config"), []byte(config), 0o600); err != nil {
		return err
	}
	mb := filepath.Join(w, "magiskboot")
	if r.Run(w, mb, "decompress", "ramdisk.img", "ramdisk.cpio") != nil {
		// already an uncompressed cpio
		if err := os.Rename(filepath.Join(w, "ramdisk.img"), filepath.Join(w, "ramdisk.cpio")); err != nil {
			return err
		}
	}
	for _, f := range []string{"magisk", "stub.apk", "init-ld"} {
		xz := strings.TrimSuffix(f, ".apk") + ".xz"
		if err := r.Run(w, mb, "compress=xz", f, xz); err != nil {
			return err
		}
	}
	if err := copyFile(filepath.Join(w, "ramdisk.cpio"), filepath.Join(w, "ramdisk.cpio.orig")); err != nil {
		return err
	}
	if err := r.Run(w, mb, "cpio", "ramdisk.cpio",
		"add 0750 init magiskinit",
		"mkdir 0750 overlay.d",
		"mkdir 0750 overlay.d/sbin",
		"add 0644 overlay.d/sbin/magisk.xz magisk.xz",
		"add 0644 overlay.d/sbin/stub.xz stub.xz",
		"add 0644 overlay.d/sbin/init-ld.xz init-ld.xz",
		"patch",
		"backup ramdisk.cpio.orig",
		"mkdir 000 .backup",
		"add 000 .backup/.magisk config"); err != nil {
		return err
	}
	if err := r.Run(w, mb, "compress=gzip", "ramdisk.cpio", "ramdisk.cpio.gz"); err != nil {
		return err
	}
	data, err := os.ReadFile(filepath.Join(w, "ramdisk.cpio.gz"))
	if err != nil || len(data) < 1024 {
		return fmt.Errorf("new ramdisk: %d bytes, %v", len(data), err)
	}

	img := uImage(header[:64], data, time.Now())
	tmp := cur + ".new"
	if err := writeSync(tmp, img); err != nil {
		os.Remove(tmp)
		return err
	}
	prev := filepath.Join(boot, "ramdisk-magisk-previous.uimg")
	os.Remove(prev)
	if err := os.Rename(cur, prev); err != nil {
		os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, cur); err != nil {
		os.Rename(prev, cur)
		return err
	}
	// the renames are on the card before the restart
	return syncDir(boot)
}

func syncDir(dir string) error {
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}

const uimageMagic = 0x27051956

// readUImage is the data of a legacy U-Boot image, its checksum checked.
func readUImage(path string) ([]byte, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if len(b) < 64 || binary.BigEndian.Uint32(b[0:]) != uimageMagic {
		return nil, fmt.Errorf("%s: not a U-Boot image", filepath.Base(path))
	}
	size := binary.BigEndian.Uint32(b[12:])
	if uint64(len(b)) < 64+uint64(size) {
		return nil, fmt.Errorf("%s: cut short", filepath.Base(path))
	}
	data := b[64 : 64+size]
	if crc32.ChecksumIEEE(data) != binary.BigEndian.Uint32(b[24:]) {
		return nil, fmt.Errorf("%s: checksum wrong", filepath.Base(path))
	}
	return data, nil
}

// uImage wraps data like mkimage, keeping the old header's type, OS, architecture,
// addresses and name.
func uImage(old []byte, data []byte, now time.Time) []byte {
	h := make([]byte, 64)
	copy(h, old[:64])
	binary.BigEndian.PutUint32(h[0:], uimageMagic)
	binary.BigEndian.PutUint32(h[4:], 0)
	binary.BigEndian.PutUint32(h[8:], uint32(now.Unix()))
	binary.BigEndian.PutUint32(h[12:], uint32(len(data)))
	binary.BigEndian.PutUint32(h[24:], crc32.ChecksumIEEE(data))
	binary.BigEndian.PutUint32(h[4:], crc32.ChecksumIEEE(h))
	return append(h, data...)
}

func extract(apk, dir string, want map[string]string) error {
	z, err := zip.OpenReader(apk)
	if err != nil {
		return err
	}
	defer z.Close()
	found := 0
	for _, f := range z.File {
		name, ok := want[f.Name]
		if !ok {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return err
		}
		out, err := os.OpenFile(filepath.Join(dir, name), os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o700)
		if err == nil {
			_, err = io.Copy(out, rc)
			if cerr := out.Close(); err == nil {
				err = cerr
			}
		}
		rc.Close()
		if err != nil {
			return err
		}
		found++
	}
	if found != len(want) {
		return errors.New("the Magisk app lacks some of its arm64 files")
	}
	return nil
}

func copyFile(from, to string) error {
	b, err := os.ReadFile(from)
	if err != nil {
		return err
	}
	return os.WriteFile(to, b, 0o600)
}

func writeSync(path string, b []byte) error {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, bytes.NewReader(b)); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

func isFile(path string) bool {
	st, err := os.Stat(path)
	return err == nil && st.Mode().IsRegular()
}
