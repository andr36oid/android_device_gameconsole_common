package magisk

import (
	"archive/zip"
	"bytes"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

// the APK the image ships, and the last build's plain ramdisk (if there is one)
const (
	shippedAPK   = "../../../../magisk/Magisk.apk"
	builtRamdisk = "/andr36oid/src/out/target/product/r36s/ramdisk.img"
)

func TestUImageRoundTrip(t *testing.T) {
	old := uImage(make([]byte, 64), []byte("old"), time.Unix(1, 0))
	copy(old[32:], "andr36oid ramdisk (Magisk)")
	img := uImage(old, []byte("new data"), time.Unix(2, 0))
	path := filepath.Join(t.TempDir(), "r.uimg")
	os.WriteFile(path, img, 0o600)
	data, err := readUImage(path)
	if err != nil || string(data) != "new data" {
		t.Fatalf("got %q, %v", data, err)
	}
	if !bytes.Equal(img[32:58], []byte("andr36oid ramdisk (Magisk)")) {
		t.Errorf("name not kept: %q", img[32:64])
	}
	img[70] ^= 1
	os.WriteFile(path, img, 0o600)
	if _, err := readUImage(path); err == nil {
		t.Error("a damaged image passed")
	}
}

func TestAppVersion(t *testing.T) {
	dir := t.TempDir()
	code := filepath.Join(dir, "app", "~~a==", pkg+"-b==")
	os.MkdirAll(code, 0o755)
	os.WriteFile(filepath.Join(code, "base.apk"), []byte("x"), 0o644)
	xml := `<packages>
<package name="com.other" codePath="/x" version="5" />
<package name="` + pkg + `" codePath="` + code + `" nativeLibraryPath="/y" publicFlags="1" version="31000" sharedUserId="0">
</package></packages>`
	r := &Rebuilder{P: Paths{Packages: filepath.Join(dir, "packages.xml")}}
	os.WriteFile(r.P.Packages, []byte(xml), 0o644)
	vc, apk := r.appVersion()
	if vc != 31000 || apk != filepath.Join(code, "base.apk") {
		t.Fatalf("got %d %q", vc, apk)
	}
}

// fakeMagisk is a "magisk" that prints a version
func fakeMagisk(t *testing.T, dir, version string) string {
	p := filepath.Join(dir, "magisk")
	os.WriteFile(p, []byte("#!/bin/sh\necho "+version+"\n"), 0o755)
	return p
}

// hostMagiskboot is the APK's own magiskboot for this machine
func hostMagiskboot(t *testing.T, dir string) string {
	if runtime.GOOS != "linux" || runtime.GOARCH != "amd64" {
		t.Skip("needs an x86_64 Linux build machine")
	}
	z, err := zip.OpenReader(shippedAPK)
	if err != nil {
		t.Skip("no Magisk.apk:", err)
	}
	defer z.Close()
	for _, f := range z.File {
		if f.Name == "lib/x86_64/libmagiskboot.so" {
			rc, _ := f.Open()
			p := filepath.Join(dir, "magiskboot-host")
			out, _ := os.OpenFile(p, os.O_CREATE|os.O_WRONLY, 0o755)
			io.Copy(out, rc)
			out.Close()
			rc.Close()
			return p
		}
	}
	t.Skip("APK has no x86_64 magiskboot")
	return ""
}

func TestRebuildWithRealMagiskboot(t *testing.T) {
	stock, err := os.ReadFile(builtRamdisk)
	if err != nil {
		t.Skip("no built ramdisk:", err)
	}
	dir := t.TempDir()
	host := hostMagiskboot(t, dir)
	boot := filepath.Join(dir, "BOOT")
	os.MkdirAll(boot, 0o755)
	hdr := make([]byte, 64)
	hdr[28], hdr[29], hdr[30] = 5, 22, 3 // linux, arm64, ramdisk
	copy(hdr[32:], "andr36oid ramdisk (Magisk)")
	os.WriteFile(filepath.Join(boot, "ramdisk-stock.uimg"), uImage(hdr, stock, time.Now()), 0o644)
	oldImg := uImage(hdr, []byte("the old magisk ramdisk"), time.Now())
	os.WriteFile(filepath.Join(boot, "ramdisk.uimg"), oldImg, 0o644)

	code := filepath.Join(dir, "app")
	os.MkdirAll(code, 0o755)
	apk, _ := os.ReadFile(shippedAPK)
	os.WriteFile(filepath.Join(code, "base.apk"), apk, 0o644)
	packages := filepath.Join(dir, "packages.xml")
	os.WriteFile(packages, []byte(`<package name="`+pkg+`" codePath="`+code+`" version="31000">`), 0o644)

	var props []string
	r := &Rebuilder{
		P: Paths{
			Magisk:   fakeMagisk(t, dir, "29000"),
			Packages: packages,
			BootDev:  boot,
			Work:     filepath.Join(dir, "work"),
			Tried:    filepath.Join(dir, "tried"),
		},
		Log:     t.Logf,
		SetProp: func(n, v string) error { props = append(props, n+"="+v); return nil },
		// the arm64 magiskboot can't run here: the APK's x86_64 one stands in
		Run: func(d, name string, args ...string) error {
			if filepath.Base(name) == "magiskboot" {
				name = host
			}
			return run(d, name, args...)
		},
	}
	done, err := r.Check()
	if err != nil || !done {
		t.Fatalf("Check: %v %v", done, err)
	}
	if len(props) != 2 || props[0] != "sys.andr36oid.resizing=3" || props[1] != "sys.andr36oid.resizing=0" {
		t.Errorf("boot note props: %v", props)
	}
	prev, _ := os.ReadFile(filepath.Join(boot, "ramdisk-magisk-previous.uimg"))
	if !bytes.Equal(prev, oldImg) {
		t.Error("the old ramdisk was not kept")
	}
	data, err := readUImage(filepath.Join(boot, "ramdisk.uimg"))
	if err != nil {
		t.Fatal(err)
	}
	// magiskboot's own check: "cpio test" exits 1 for a Magisk-patched ramdisk
	check := t.TempDir()
	os.WriteFile(filepath.Join(check, "r.gz"), data, 0o644)
	if err := run(check, host, "decompress", "r.gz", "r.cpio"); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(host, "cpio", "r.cpio", "test")
	cmd.Dir = check
	err = cmd.Run()
	if ee, ok := err.(*exec.ExitError); !ok || ee.ExitCode() != 1 {
		t.Errorf("cpio test: %v, want exit 1 (Magisk patched)", err)
	}
	if _, err := os.Stat(r.P.Work); !os.IsNotExist(err) {
		t.Error("work folder left behind")
	}

	// the same app version again: tried already, nothing happens
	if done, err := r.Check(); done || err != nil {
		t.Errorf("second Check: %v %v", done, err)
	}
	// root as new as the app: nothing to do
	os.Remove(r.P.Tried)
	r.P.Magisk = fakeMagisk(t, dir, "31000")
	if done, err := r.Check(); done || err != nil {
		t.Errorf("up to date: %v %v", done, err)
	}
}
