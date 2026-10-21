package emmc

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
)

// sgdisk checks the tables the tool writes (read-only: -v and -p). It can't
// make the SD card's table here: after writing it calls sync(), which hangs
// for minutes on the build host. mkgpt (Python, written apart from gpt.go)
// does that instead.
const sgdisk = "/usr/sbin/sgdisk"

const mkgpt = `
import sys, struct, uuid, zlib
path, secs = sys.argv[1], int(sys.argv[2])
types = {"0700": "EBD0A0A2-B9E5-4433-87C0-68B6B72699C7", "8300": "0FC63DAF-8483-4772-8E79-3D69D8477DE4"}
ents = bytearray(128 * 128)
for a in sys.argv[3:]:
    num, first, last, name, typ = a.split(":")
    e = uuid.UUID(types[typ]).bytes_le + uuid.uuid4().bytes_le
    e += struct.pack("<QQQ", int(first), int(last), 0) + name.encode("utf-16-le").ljust(72, b"\0")
    ents[(int(num) - 1) * 128:int(num) * 128] = e
disk = uuid.uuid4().bytes_le
def header(my, alt, at):
    h = struct.pack("<8sIII4xQQQQ16sQIII", b"EFI PART", 0x10000, 92, 0, my, alt, 34, secs - 34,
                    disk, at, 128, 128, zlib.crc32(ents))
    h = h[:16] + struct.pack("<I", zlib.crc32(h)) + h[20:]
    return h.ljust(512, b"\0")
mbr = bytearray(512)
mbr[446:462] = struct.pack("<B3sB3sII", 0, b"\0\2\0", 0xEE, b"\xff\xff\xff", 1, min(secs - 1, 0xFFFFFFFF))
mbr[510:512] = b"\x55\xaa"
with open(path, "r+b") as f:
    f.write(bytes(mbr) + header(1, secs - 1, 2) + bytes(ents))
    f.seek((secs - 33) * 512)
    f.write(bytes(ents) + header(secs - 1, 1, secs - 33))
`

const testCID = "150100503142454c0000aabbccdd0e00"

// fake is a console in a folder: an SD card image with andr36oid's layout
// (made by sgdisk, numbered like mkimg's), a stock eMMC image with a Rockchip
// loader and data, its boot areas, and the sysfs/proc files the tool reads.
type fake struct {
	t      *testing.T
	dir    string
	cfg    *Config
	locked bool
	stock  []byte // the eMMC as it came
	writes []int64
}

func mib(n int64) int64 { return n << 20 / Sector }

func writeFile(t *testing.T, path, s string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(s), 0644); err != nil {
		t.Fatal(err)
	}
}

func randBytes(r *rand.Rand, n int64) []byte {
	b := make([]byte, n)
	r.Read(b)
	return b
}

func directWorks(dir string) bool {
	p := filepath.Join(dir, "direct")
	os.WriteFile(p, make([]byte, 8192), 0644)
	defer os.Remove(p)
	d, err := OpenDisk(p, false, true)
	if err != nil {
		return false
	}
	defer d.Close()
	_, err = d.ReadBytes(0, 4096)
	return err == nil
}

func newFake(t *testing.T) *fake {
	t.Helper()
	if _, err := os.Stat(sgdisk); err != nil {
		t.Skip("no sgdisk")
	}
	f := &fake{t: t, dir: t.TempDir(), locked: true}
	r := rand.New(rand.NewSource(1))

	// SD card: loader area, then BOOT p1, system p2, loader p3-p5, vendor
	// p8, metadata p9, misc p10, cache p11, like mkimg (6 and 7 are made by
	// the first start).
	sdPath := filepath.Join(f.dir, "sd.img")
	sdSecs := mib(48)
	sd := make([]byte, sdSecs*Sector)
	copy(sd[LoaderStart*Sector:], append([]byte{0x3b, 0x8c, 0xdc, 0xfc}, randBytes(r, 176*1024)...))
	ub := append([]byte("LOADER  "), randBytes(r, 1<<20-8)...)
	for i := 0; i < ubootCopies; i++ { // like loaderimage packs it
		copy(sd[UbootStart*Sector+int64(i)<<20:], ub)
	}
	copy(sd[TrustStart*Sector:], append([]byte("BL3X"), randBytes(r, 1<<20)...))
	parts := []struct {
		num        int
		name       string
		first, len int64
		typ        string
	}{
		{1, "BOOT", mib(16), mib(4), "0700"},
		{8, "vendor", mib(20), mib(2), "8300"},
		{9, "metadata", mib(22), mib(1), "8300"},
		{10, "misc", mib(23), mib(1), "8300"},
		{11, "cache", mib(24), mib(2), "8300"},
		{2, "system", mib(26), mib(8) + 17, "8300"}, // not whole MiB, like the real one
		{3, "idbloader", 64, UbootStart - 64, "8300"},
		{4, "uboot", UbootStart, TrustStart - UbootStart, "8300"},
		{5, "trust", TrustStart, LoaderEnd - TrustStart, "8300"},
	}
	for _, p := range parts {
		if p.num != 3 && p.num != 4 && p.num != 5 {
			copy(sd[p.first*Sector:], randBytes(r, p.len*Sector))
		}
	}
	if err := os.WriteFile(sdPath, sd, 0644); err != nil {
		t.Fatal(err)
	}
	args := []string{"-c", mkgpt, sdPath, fmt.Sprint(sdSecs)}
	for _, p := range parts {
		args = append(args, fmt.Sprintf("%d:%d:%d:%s:%s", p.num, p.first, p.first+p.len-1, p.name, p.typ))
	}
	if out, err := exec.Command("python3", args...).CombinedOutput(); err != nil {
		t.Fatalf("mkgpt: %v\n%s", err, out)
	}
	if out, err := exec.Command(sgdisk, "-v", sdPath).CombinedOutput(); err != nil ||
		!strings.Contains(string(out), "No problems found") {
		t.Fatalf("test SD card table: %v\n%s", err, out)
	}

	// eMMC: a stock Rockchip loader and stock data all over, boot areas
	emmcPath := filepath.Join(f.dir, "emmc")
	f.stock = randBytes(r, mib(64)*Sector)
	copy(f.stock[LoaderStart*Sector:], []byte{0x3b, 0x8c, 0xdc, 0xfc})
	if err := os.WriteFile(emmcPath, f.stock, 0644); err != nil {
		t.Fatal(err)
	}
	for _, b := range []string{"boot0", "boot1"} {
		if err := os.WriteFile(emmcPath+b, randBytes(r, 1<<20), 0644); err != nil {
			t.Fatal(err)
		}
	}

	sys := filepath.Join(f.dir, "sys")
	writeFile(t, filepath.Join(sys, "mmcblk2/andr36oid_unlock"), "locked\n")
	writeFile(t, filepath.Join(sys, "mmcblk2/device/cid"), testCID+"\n")
	writeFile(t, filepath.Join(sys, "mmcblk2/device/type"), "MMC\n")
	writeFile(t, filepath.Join(sys, "mmcblk2/device/name"), "P1J95K\n")
	writeFile(t, filepath.Join(sys, "mmcblk2/size"), fmt.Sprint(mib(64)))
	writeFile(t, filepath.Join(sys, "mmcblk2/dev"), "179:16\n")
	writeFile(t, filepath.Join(sys, "mmcblk2/mmcblk2p1/dev"), "179:17\n")
	writeFile(t, filepath.Join(sys, "mmcblk0/dev"), "179:0\n")
	writeFile(t, filepath.Join(sys, "mmcblk0/mmcblk0p1/dev"), "179:1\n")
	writeFile(t, filepath.Join(sys, "mmcblk0/mmcblk0p2/dev"), "179:2\n")
	writeFile(t, filepath.Join(f.dir, "power/battery/capacity"), "80\n")
	writeFile(t, filepath.Join(f.dir, "power/battery/status"), "Discharging\n")
	writeFile(t, filepath.Join(f.dir, "power/ac/online"), "0\n")
	writeFile(t, filepath.Join(f.dir, "mountinfo"),
		"20 1 179:2 / /system ro,relatime - ext4 /dev/block/dm-0 ro\n")
	writeFile(t, filepath.Join(f.dir, "boot_id"), "boot-1\n")
	f.sdStart()
	os.MkdirAll(filepath.Join(f.dir, "sdboot"), 0755)
	os.MkdirAll(filepath.Join(f.dir, "roms"), 0755)

	f.cfg = &Config{
		Cmdline:     filepath.Join(f.dir, "cmdline"),
		Mountinfo:   filepath.Join(f.dir, "mountinfo"),
		BootID:      filepath.Join(f.dir, "boot_id"),
		EmmcSys:     filepath.Join(sys, "mmcblk2"),
		EmmcDev:     emmcPath,
		SDSys:       filepath.Join(sys, "mmcblk0"),
		SDDev:       sdPath,
		SDBootDir:   filepath.Join(f.dir, "sdboot"),
		PowerDir:    filepath.Join(f.dir, "power"),
		StateDir:    filepath.Join(f.dir, "state"),
		Direct:      directWorks(f.dir),
		MinEmmc:     mib(48),
		MinUserdata: mib(4),
		BigEmmc:     mib(128),
		BackupChunk: 20 << 20, // 64 MiB in 4 files, the last one shorter
		rnd:         rand.New(rand.NewSource(2)),
	}
	f.cfg.lock = func(c *Config, cid string, unlock bool) error {
		if unlock {
			if cid != testCID {
				return errors.New("wrong CID")
			}
			f.locked = false
		} else {
			f.locked = true
		}
		return nil
	}
	// the kernel refuses writes to the locked eMMC; the SD card is never written
	testHooks.write = func(d *Disk, b []byte, off int64) ([]byte, error) {
		switch d.Path {
		case f.cfg.EmmcDev:
			if f.locked {
				return nil, syscall.EROFS
			}
			f.writes = append(f.writes, off)
		case f.cfg.SDDev:
			t.Errorf("wrote to the SD card at sector %d", off/Sector)
			return nil, syscall.EPERM
		}
		return b, nil
	}
	testHooks.read = nil
	t.Cleanup(func() { testHooks.write, testHooks.read = nil, nil })
	return f
}

func (f *fake) sdStart() {
	writeFile(f.t, filepath.Join(f.dir, "cmdline"),
		"console=ttyFIQ0 androidboot.boot_devices=ff370000.dwmmc dw_mmc_rockchip.builtin_emmc=1")
}

func (f *fake) emmcStart() {
	writeFile(f.t, filepath.Join(f.dir, "cmdline"),
		"console=ttyFIQ0 androidboot.boot_devices=ff390000.dwmmc dw_mmc_rockchip.builtin_emmc=1 dw_mmc_rockchip.builtin_emmc=system androidboot.fstab_suffix=emmc")
}

func (f *fake) restart(id string) { writeFile(f.t, filepath.Join(f.dir, "boot_id"), id+"\n") }

func (f *fake) progress() *Progress {
	p, err := NewProgress(filepath.Join(f.dir, "progress.jsonl"), "test")
	if err != nil {
		f.t.Fatal(err)
	}
	return p
}

func (f *fake) read(path string, from, n int64) []byte {
	b, err := os.ReadFile(path)
	if err != nil {
		f.t.Fatal(err)
	}
	return b[from*Sector : (from+n)*Sector]
}

func (f *fake) emmc(from, n int64) []byte { return f.read(f.cfg.EmmcDev, from, n) }
func (f *fake) sd(from, n int64) []byte   { return f.read(f.cfg.SDDev, from, n) }

func (f *fake) state() *State {
	st, err := f.cfg.LoadState()
	if err != nil {
		f.t.Fatal(err)
	}
	return st
}

func (f *fake) testFile() string {
	b, err := os.ReadFile(filepath.Join(f.cfg.SDBootDir, TestFile))
	if err != nil {
		return ""
	}
	return string(b)
}

// uboot does what boot.ini does with emmc_test=start
func (f *fake) uboot() {
	if f.testFile() != "emmc_test=start\n" {
		f.t.Fatalf("test file %q, want start", f.testFile())
	}
	writeFile(f.t, filepath.Join(f.cfg.SDBootDir, TestFile), "emmc_test=tried\n\x00")
}

func mustFail(t *testing.T, err error, want string) {
	t.Helper()
	if err == nil {
		t.Fatalf("no error, want one with %q", want)
	}
	if !strings.Contains(err.Error(), want) {
		t.Fatalf("error %q, want one with %q", err, want)
	}
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

// installTo runs the install up to (not including) step 3.
func (f *fake) installTo2(t *testing.T) {
	t.Helper()
	_, err := f.cfg.Backup(f.progress(), filepath.Join(f.dir, "roms"))
	must(t, err)
	must(t, f.cfg.BlankLoader(f.progress()))
	f.restart("boot-2")
	must(t, f.cfg.CheckLoader(f.progress()))
	must(t, f.cfg.WriteOS(f.progress()))
	f.uboot()
	f.emmcStart()
	res, err := f.cfg.Confirm(f.progress())
	must(t, err)
	if res != "confirmed" {
		t.Fatalf("confirm: %s", res)
	}
	f.sdStart()
	res, err = f.cfg.CheckTest(f.progress())
	must(t, err)
	if res != "passed" {
		t.Fatalf("check-test: %s", res)
	}
}

func TestGPTReadsWithSgdisk(t *testing.T) {
	f := newFake(t)
	sd, err := OpenDisk(f.cfg.SDDev, false, false)
	must(t, err)
	defer sd.Close()
	parts, err := ReadGPT(sd)
	must(t, err)
	if p, ok := Find(parts, "vendor"); !ok || p.Num != 8 || p.First != mib(20) || p.Sectors() != mib(2) {
		t.Fatalf("vendor read as %+v", p)
	}
	if p, _ := Find(parts, "BOOT"); p.Type != TypeBasicData {
		t.Fatalf("BOOT type %s", p.Type)
	}

	plan, err := f.cfg.MakePlan(mib(64), parts)
	must(t, err)
	g, err := BuildGPT(mib(64), plan, rand.New(rand.NewSource(3)))
	must(t, err)
	img := filepath.Join(f.dir, "gpt.img")
	b := make([]byte, mib(64)*Sector)
	copy(b, g.Head)
	copy(b[TailOffset(mib(64)):], g.Tail)
	must(t, os.WriteFile(img, b, 0644))
	out, err := exec.Command(sgdisk, "-v", img).CombinedOutput()
	if err != nil || !strings.Contains(string(out), "No problems found") {
		t.Fatalf("sgdisk -v: %v\n%s", err, out)
	}
	out, _ = exec.Command(sgdisk, "-p", img).CombinedOutput()
	for _, p := range plan {
		want := fmt.Sprintf("%d %d %d", p.Num, p.First, p.Last)
		if !strings.Contains(strings.Join(strings.Fields(string(out)), " "), want) ||
			!strings.Contains(string(out), p.Name) {
			t.Fatalf("sgdisk -p doesn't show %s (%s):\n%s", p.Name, want, out)
		}
	}
}

func TestPlanSizes(t *testing.T) {
	c := DefaultConfig()
	// the SD card as mkimg makes it
	sd := []Part{
		{Num: 1, Name: "BOOT", First: 32768, Last: 557055},
		{Num: 2, Name: "system", First: 1081344, Last: 5701008},
		{Num: 8, Name: "vendor", First: 557056, Last: 884735},
		{Num: 9, Name: "metadata", First: 884736, Last: 917503},
		{Num: 10, Name: "misc", First: 917504, Last: 925695},
		{Num: 11, Name: "cache", First: 925696, Last: 1081343},
	}
	for _, tc := range []struct {
		secs           int64
		system, vendor int64 // MiB
		userdataMin    int64 // MiB
	}{
		{15269888, 2820, 200, 4000}, // Micron P1J95K, "8 GB"
		{30535680, 4512, 320, 9000}, // "16 GB": system and vendor twice
		{61071360, 4512, 320, 24000},
	} {
		plan, err := c.MakePlan(tc.secs, sd)
		must(t, err)
		if plan[0].Name != "BOOT" || plan[0].First != LoaderEnd || plan[0].Num != 1 {
			t.Fatalf("BOOT isn't partition 1 at %d: %+v", LoaderEnd, plan[0])
		}
		s, _ := Find(plan, "system")
		v, _ := Find(plan, "vendor")
		u, _ := Find(plan, "userdata")
		if s.Sectors()*Sector>>20 != tc.system || v.Sectors()*Sector>>20 != tc.vendor {
			t.Fatalf("%d sectors: system %d MiB, vendor %d MiB", tc.secs, s.Sectors()*Sector>>20, v.Sectors()*Sector>>20)
		}
		if u.Sectors()*Sector>>20 < tc.userdataMin || u.Last >= tc.secs-1-gptTableSecs {
			t.Fatalf("%d sectors: userdata %d MiB ending %d", tc.secs, u.Sectors()*Sector>>20, u.Last)
		}
		for i, p := range plan {
			if p.First%align != 0 || (p.Last+1)%align != 0 {
				t.Fatalf("%s not on whole MiB", p.Name)
			}
			if i > 0 && p.First != plan[i-1].Last+1 {
				t.Fatalf("gap or overlap before %s", p.Name)
			}
		}
	}
	_, err := c.MakePlan(7634944, sd) // "4 GB"
	mustFail(t, err, "at least")
}

func TestInstallAndRestore(t *testing.T) {
	f := newFake(t)
	in := f.cfg.Inspect()
	if len(in.Problems) != 0 || in.Loader != "rockchip" || in.BootedFrom != "sd" || len(in.Plan) != 7 {
		t.Fatalf("inspect: %+v", in)
	}

	dir, err := f.cfg.Backup(f.progress(), filepath.Join(f.dir, "roms"))
	must(t, err)
	if !strings.HasPrefix(dir, filepath.Join(f.dir, "roms", "eMMC backup", "P1J95K-ccdd0e00", "stock-")) {
		t.Fatalf("backup in %s", dir)
	}
	var got []byte
	for i := 0; i < 4; i++ {
		b, err := os.ReadFile(filepath.Join(dir, fmt.Sprintf("user.%03d", i)))
		must(t, err)
		got = append(got, b...)
	}
	if !bytes.Equal(got, f.stock) {
		t.Fatal("backup files aren't the eMMC")
	}
	if b, _ := os.ReadFile(filepath.Join(dir, "boot1.img")); !bytes.Equal(b, f.read(f.cfg.EmmcDev+"boot1", 0, 2048)) {
		t.Fatal("boot1 not backed up")
	}
	if !bytes.Equal(f.emmc(0, mib(64)), f.stock) {
		t.Fatal("backup wrote to the eMMC")
	}

	f.writes = nil
	must(t, f.cfg.BlankLoader(f.progress()))
	if f.writes[0] != LoaderStart*Sector {
		t.Fatalf("blank didn't clear the idbloader header first (sector %d)", f.writes[0]/Sector)
	}
	if !bytes.Equal(f.emmc(LoaderStart, LoaderEnd-LoaderStart), make([]byte, (LoaderEnd-LoaderStart)*Sector)) {
		t.Fatal("loader area not blank")
	}
	if !bytes.Equal(f.emmc(0, LoaderStart), f.stock[:LoaderStart*Sector]) ||
		!bytes.Equal(f.emmc(LoaderEnd, mib(48)), f.stock[LoaderEnd*Sector:]) {
		t.Fatal("blank wrote outside the loader area")
	}
	if !f.locked {
		t.Fatal("eMMC left unlocked")
	}

	mustFail(t, f.cfg.CheckLoader(f.progress()), "restart")
	mustFail(t, f.cfg.WriteOS(f.progress()), "step 1 hasn't passed")
	f.restart("boot-2")
	must(t, f.cfg.CheckLoader(f.progress()))

	must(t, f.cfg.WriteOS(f.progress()))
	st := f.state()
	if st.Step2 != "written" || st.Running != "" || f.testFile() != "emmc_test=start\n" {
		t.Fatalf("after write-os: %+v, test file %q", st, f.testFile())
	}
	out, err := exec.Command(sgdisk, "-v", f.cfg.EmmcDev).CombinedOutput()
	if err != nil || !strings.Contains(string(out), "No problems found") {
		t.Fatalf("eMMC table: %v\n%s", err, out)
	}
	sdParts, _ := ReadGPT(must2(OpenDisk(f.cfg.SDDev, false, false)))
	for _, name := range []string{"BOOT", "system", "vendor"} {
		s, _ := Find(sdParts, name)
		e, _ := Find(st.Plan, name)
		if !bytes.Equal(f.emmc(e.First, s.Sectors()), f.sd(s.First, s.Sectors())) {
			t.Fatalf("%s not copied", name)
		}
	}
	for _, name := range []string{"metadata", "misc", "cache", "userdata"} {
		e, _ := Find(st.Plan, name)
		if !bytes.Equal(f.emmc(e.First, 8), make([]byte, 8*Sector)) {
			t.Fatalf("%s not blanked", name)
		}
	}
	if !bytes.Equal(f.emmc(LoaderStart, LoaderEnd-LoaderStart), make([]byte, (LoaderEnd-LoaderStart)*Sector)) {
		t.Fatal("write-os touched the loader area")
	}

	res, err := f.cfg.CheckTest(f.progress())
	if err != nil || res != "waiting" {
		t.Fatalf("check-test before the test start: %s %v", res, err)
	}
	f.uboot()
	f.emmcStart()
	mustFail(t, f.cfg.BlankLoader(f.progress()), "isn't running from the SD card")
	res, err = f.cfg.Confirm(f.progress())
	if err != nil || res != "confirmed" || !strings.Contains(f.testFile(), "emmc_test=ok\nemmc_cid="+testCID) {
		t.Fatalf("confirm: %s %v, file %q", res, err, f.testFile())
	}
	f.sdStart()
	f.restart("boot-3")
	res, err = f.cfg.CheckTest(f.progress())
	if err != nil || res != "passed" || f.testFile() != "" {
		t.Fatalf("check-test: %s %v, file %q", res, err, f.testFile())
	}

	mustFail(t, f.cfg.WriteLoader(f.progress(), "000000000000"), "not confirmed")
	f.writes = nil
	must(t, f.cfg.WriteLoader(f.progress(), f.state().LoaderSHA[:12]))
	if !bytes.Equal(f.emmc(LoaderStart, LoaderEnd-LoaderStart), f.sd(LoaderStart, LoaderEnd-LoaderStart)) {
		t.Fatal("loader not copied")
	}
	if f.writes[len(f.writes)-1] != LoaderStart*Sector {
		t.Fatalf("idbloader header not written last (sector %d)", f.writes[len(f.writes)-1]/Sector)
	}
	if st := f.state(); st.Step3 != "done" || st.Running != "" || !f.locked {
		t.Fatalf("after write-loader: %+v locked %v", st, f.locked)
	}
	e := must2(OpenDisk(f.cfg.EmmcDev, false, false))
	if k, _ := LoaderKind(e, f.state().LoaderSHA); k != "andr36oid" {
		t.Fatalf("eMMC loader is %s", k)
	}
	e.Close()

	f.writes = nil
	must(t, f.cfg.Restore(f.progress(), dir))
	if !bytes.Equal(f.emmc(0, mib(64)), f.stock) {
		t.Fatal("restore didn't bring the stock eMMC back")
	}
	if f.writes[0] != LoaderStart*Sector {
		t.Fatalf("restore didn't clear the andr36oid loader's header first (sector %d)", f.writes[0]/Sector)
	}
	if f.writes[len(f.writes)-1] != LoaderStart*Sector {
		t.Fatal("restore didn't write the idbloader header last")
	}
	if st := f.state(); st.Step1 != "" || st.Step3 != "" || st.Backup == nil || !f.locked {
		t.Fatalf("after restore: %+v", st)
	}
	log, _ := os.ReadFile(filepath.Join(dir, "install.log"))
	if !bytes.Contains(log, []byte(`"state":"ok"`)) || !bytes.Contains(log, []byte("Write back the partitions")) {
		t.Fatalf("install log:\n%s", log)
	}
}

func must2[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}

func TestRefusals(t *testing.T) {
	roms := func(f *fake) string { return filepath.Join(f.dir, "roms") }
	t.Run("not from the SD card", func(t *testing.T) {
		f := newFake(t)
		f.emmcStart()
		_, err := f.cfg.Backup(f.progress(), roms(f))
		mustFail(t, err, "isn't running from the SD card")
	})
	t.Run("battery", func(t *testing.T) {
		f := newFake(t)
		writeFile(t, filepath.Join(f.dir, "power/battery/capacity"), "59\n")
		_, err := f.cfg.Backup(f.progress(), roms(f))
		mustFail(t, err, "battery at 59%")
		writeFile(t, filepath.Join(f.dir, "power/ac/online"), "1\n")
		_, err = f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
	})
	t.Run("eMMC switched off", func(t *testing.T) {
		f := newFake(t)
		os.Remove(filepath.Join(f.cfg.EmmcSys, "andr36oid_unlock"))
		_, err := f.cfg.Backup(f.progress(), roms(f))
		mustFail(t, err, "no built-in eMMC")
	})
	t.Run("eMMC mounted", func(t *testing.T) {
		f := newFake(t)
		writeFile(t, f.cfg.Mountinfo, "30 1 179:17 / /mnt/x rw - vfat /dev/block/mmcblk2p1 rw\n")
		_, err := f.cfg.Backup(f.progress(), roms(f))
		mustFail(t, err, "in use")
	})
	t.Run("no room for the backup", func(t *testing.T) {
		f := newFake(t)
		f.cfg.EmmcDev = filepath.Join(f.dir, "huge")
		h, _ := os.Create(f.cfg.EmmcDev)
		h.Truncate(1 << 50)
		h.Close()
		_, err := f.cfg.Backup(f.progress(), roms(f))
		mustFail(t, err, "free on")
	})
	t.Run("no backup", func(t *testing.T) {
		f := newFake(t)
		mustFail(t, f.cfg.BlankLoader(f.progress()), "no checked backup")
	})
	t.Run("backup gone", func(t *testing.T) {
		f := newFake(t)
		dir, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		os.Remove(filepath.Join(dir, "user.002"))
		mustFail(t, f.cfg.BlankLoader(f.progress()), "user.002")
		if !bytes.Equal(f.emmc(0, mib(64)), f.stock) {
			t.Fatal("eMMC written")
		}
	})
	t.Run("another eMMC", func(t *testing.T) {
		f := newFake(t)
		_, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		writeFile(t, filepath.Join(f.cfg.EmmcSys, "device/cid"), strings.Repeat("1", 32))
		// its records are its own: it has no backup yet
		mustFail(t, f.cfg.BlankLoader(f.progress()), "no checked backup")
	})
	t.Run("no loader on the SD card", func(t *testing.T) {
		f := newFake(t)
		_, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		sd, _ := os.OpenFile(f.cfg.SDDev, os.O_WRONLY, 0)
		sd.WriteAt([]byte("XXXX"), TrustStart*Sector)
		sd.Close()
		mustFail(t, f.cfg.BlankLoader(f.progress()), "no trust")
		if !bytes.Equal(f.emmc(0, mib(64)), f.stock) {
			t.Fatal("eMMC written")
		}
	})
	t.Run("SD loader changed after step 1", func(t *testing.T) {
		f := newFake(t)
		_, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		must(t, f.cfg.BlankLoader(f.progress()))
		f.restart("boot-2")
		must(t, f.cfg.CheckLoader(f.progress()))
		sd, _ := os.OpenFile(f.cfg.SDDev, os.O_WRONLY, 0)
		sd.WriteAt([]byte{1}, (UbootStart+100)*Sector)
		sd.Close()
		mustFail(t, f.cfg.WriteOS(f.progress()), "loader changed")
	})
	t.Run("SD BOOT in use", func(t *testing.T) {
		f := newFake(t)
		_, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		must(t, f.cfg.BlankLoader(f.progress()))
		f.restart("boot-2")
		must(t, f.cfg.CheckLoader(f.progress()))
		writeFile(t, f.cfg.Mountinfo, "30 1 179:1 / /mnt/b rw,noatime - vfat /dev/block/mmcblk0p1 rw\n")
		mustFail(t, f.cfg.WriteOS(f.progress()), "BOOT is in use")
	})
	t.Run("loader before step 2", func(t *testing.T) {
		f := newFake(t)
		_, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		must(t, f.cfg.BlankLoader(f.progress()))
		f.restart("boot-2")
		must(t, f.cfg.CheckLoader(f.progress()))
		mustFail(t, f.cfg.WriteLoader(f.progress(), f.state().LoaderSHA[:12]), "haven't both passed")
	})
	t.Run("eMMC system changed after step 2", func(t *testing.T) {
		f := newFake(t)
		f.installTo2(t)
		s, _ := Find(f.state().Plan, "system")
		e, _ := os.OpenFile(f.cfg.EmmcDev, os.O_WRONLY, 0)
		e.WriteAt([]byte{0x55}, (s.First+3)*Sector)
		e.Close()
		mustFail(t, f.cfg.WriteLoader(f.progress(), f.state().LoaderSHA[:12]), "system on the eMMC changed")
		if !bytes.Equal(f.emmc(LoaderStart, LoaderEnd-LoaderStart), make([]byte, (LoaderEnd-LoaderStart)*Sector)) {
			t.Fatal("loader written anyway")
		}
	})
	t.Run("test start failed", func(t *testing.T) {
		f := newFake(t)
		_, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		must(t, f.cfg.BlankLoader(f.progress()))
		f.restart("boot-2")
		must(t, f.cfg.CheckLoader(f.progress()))
		must(t, f.cfg.WriteOS(f.progress()))
		f.uboot() // the eMMC system never confirms
		res, err := f.cfg.CheckTest(f.progress())
		mustFail(t, err, "didn't start from the eMMC")
		if res != "failed" || f.testFile() != "" {
			t.Fatalf("%s, file %q", res, f.testFile())
		}
		mustFail(t, f.cfg.WriteLoader(f.progress(), f.state().LoaderSHA[:12]), "haven't both passed")
	})
	t.Run("restore from a damaged backup", func(t *testing.T) {
		f := newFake(t)
		dir, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		must(t, f.cfg.BlankLoader(f.progress()))
		before := f.emmc(0, mib(64))
		b, _ := os.OpenFile(filepath.Join(dir, "user.001"), os.O_WRONLY, 0)
		b.WriteAt([]byte{0}, 12345)
		b.Close()
		mustFail(t, f.cfg.Restore(f.progress(), dir), "damaged")
		if !bytes.Equal(f.emmc(0, mib(64)), before) {
			t.Fatal("restore wrote from a damaged backup")
		}
	})
	t.Run("restore another eMMC's backup", func(t *testing.T) {
		f := newFake(t)
		dir, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		writeFile(t, filepath.Join(f.cfg.EmmcSys, "device/cid"), strings.Repeat("2", 32))
		mustFail(t, f.cfg.Restore(f.progress(), dir), "another eMMC")
	})
}

func TestHardStops(t *testing.T) {
	roms := func(f *fake) string { return filepath.Join(f.dir, "roms") }
	t.Run("eMMC reads differently the second time", func(t *testing.T) {
		f := newFake(t)
		reads := 0
		testHooks.read = func(d *Disk, b []byte, off int64) {
			if d.Path == f.cfg.EmmcDev && off == 0 {
				if reads++; reads == 2 {
					b[100] ^= 1
				}
			}
		}
		_, err := f.cfg.Backup(f.progress(), roms(f))
		mustFail(t, err, "read differently the second time")
		if f.state().Backup != nil {
			t.Fatal("backup recorded")
		}
	})
	t.Run("system copy reads back wrong", func(t *testing.T) {
		f := newFake(t)
		_, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		must(t, f.cfg.BlankLoader(f.progress()))
		f.restart("boot-2")
		must(t, f.cfg.CheckLoader(f.progress()))
		plan, _ := f.cfg.MakePlan(mib(64), must2(ReadGPT(must2(OpenDisk(f.cfg.SDDev, false, false)))))
		s, _ := Find(plan, "system")
		w := testHooks.write
		testHooks.write = func(d *Disk, b []byte, off int64) ([]byte, error) {
			b, err := w(d, b, off)
			if d.Path == f.cfg.EmmcDev && off == s.First*Sector {
				b = append([]byte{}, b...)
				b[7] ^= 0x80 // the eMMC keeps a bad bit
			}
			return b, err
		}
		mustFail(t, f.cfg.WriteOS(f.progress()), "reads back different")
		st := f.state()
		if st.Running != "write-os" || st.Failed == "" || st.Step2 != "" || f.testFile() != "" || !f.locked {
			t.Fatalf("after the stop: %+v, test file %q, locked %v", st, f.testFile(), f.locked)
		}
		progress, _ := os.ReadFile(filepath.Join(f.dir, "progress.jsonl"))
		if !bytes.Contains(progress, []byte(`"state":"fail"`)) {
			t.Fatalf("no fail event:\n%s", progress)
		}
		// nothing else runs until it is restored or left
		mustFail(t, f.cfg.BlankLoader(f.progress()), "was cut off")
	})
	t.Run("eMMC refuses a write", func(t *testing.T) {
		f := newFake(t)
		_, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		w := testHooks.write
		testHooks.write = func(d *Disk, b []byte, off int64) ([]byte, error) {
			if d.Path == f.cfg.EmmcDev && off > LoaderStart*Sector {
				return nil, syscall.EIO
			}
			return w(d, b, off)
		}
		mustFail(t, f.cfg.BlankLoader(f.progress()), "input/output error")
		if st := f.state(); st.Running != "blank-loader" || st.Step1 != "" || !f.locked {
			t.Fatalf("after the stop: %+v", st)
		}
		// the idbloader header went first: the ROM won't take the half-blank loader
		if !bytes.Equal(f.emmc(LoaderStart, 1), make([]byte, Sector)) {
			t.Fatal("idbloader header not cleared")
		}
		testHooks.write = w
		must(t, f.cfg.Restore(f.progress(), f.state().Backup.Dir))
		if !bytes.Equal(f.emmc(0, mib(64)), f.stock) {
			t.Fatal("not restored")
		}
	})
	t.Run("restore cut off before the stock loader is back", func(t *testing.T) {
		f := newFake(t)
		f.installTo2(t)
		must(t, f.cfg.WriteLoader(f.progress(), f.state().LoaderSHA[:12]))
		dir := f.state().Backup.Dir
		w := testHooks.write
		testHooks.write = func(d *Disk, b []byte, off int64) ([]byte, error) {
			if d.Path == f.cfg.EmmcDev && off == UbootStart*Sector {
				return nil, syscall.EIO
			}
			return w(d, b, off)
		}
		mustFail(t, f.cfg.Restore(f.progress(), dir), "input/output error")
		// the boot ROM must find no loader on the eMMC and go on to the SD card
		if !bytes.Equal(f.emmc(LoaderStart, 1), make([]byte, Sector)) {
			t.Fatal("an idbloader header is left over a half restored loader")
		}
		testHooks.write = w
		must(t, f.cfg.Restore(f.progress(), dir))
		if !bytes.Equal(f.emmc(0, mib(64)), f.stock) {
			t.Fatal("second restore didn't bring the stock eMMC back")
		}
	})
	t.Run("restore leaves a stock loader alone", func(t *testing.T) {
		f := newFake(t)
		dir, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		f.writes = nil
		must(t, f.cfg.Restore(f.progress(), dir))
		for _, w := range f.writes {
			if w >= LoaderStart*Sector && w < LoaderEnd*Sector {
				t.Fatalf("restore wrote the loader area (sector %d) though it was stock", w/Sector)
			}
		}
		if !bytes.Equal(f.emmc(0, mib(64)), f.stock) {
			t.Fatal("restore changed the eMMC")
		}
	})
	t.Run("cut off, then leave", func(t *testing.T) {
		f := newFake(t)
		_, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		st := f.state()
		st.Running = "blank-loader"
		must(t, f.cfg.Save(st))
		mustFail(t, f.cfg.WriteOS(f.progress()), "was cut off")
		must(t, f.cfg.Leave(f.progress()))
		if f.state().Running != "" {
			t.Fatal("leave didn't clear it")
		}
	})
}

func TestTestFile(t *testing.T) {
	f := newFake(t)
	f.installTo2(t)
	if f.state().Step2 != "passed" {
		t.Fatal("not passed")
	}
	// a normal eMMC start with no test file changes nothing
	f.emmcStart()
	res, err := f.cfg.Confirm(f.progress())
	if err != nil || res != "no test" || f.testFile() != "" {
		t.Fatalf("%s %v %q", res, err, f.testFile())
	}
	// start, not tried: the eMMC system leaves it alone
	writeFile(t, filepath.Join(f.cfg.SDBootDir, TestFile), "emmc_test=start\n")
	res, _ = f.cfg.Confirm(f.progress())
	if res != "no test" || f.testFile() != "emmc_test=start\n" {
		t.Fatalf("%s %q", res, f.testFile())
	}
	// on an SD start Confirm does nothing
	f.sdStart()
	writeFile(t, filepath.Join(f.cfg.SDBootDir, TestFile), "emmc_test=tried\n")
	res, _ = f.cfg.Confirm(f.progress())
	if res != "not started from the eMMC" || f.testFile() != "emmc_test=tried\n" {
		t.Fatalf("%s %q", res, f.testFile())
	}
	if v := parseTest([]byte("emmc_test=tried\n\x00")); v["emmc_test"] != "tried" {
		t.Fatalf("U-Boot's export read as %v", v)
	}
}

func TestProgressEvents(t *testing.T) {
	f := newFake(t)
	_, err := f.cfg.Backup(f.progress(), filepath.Join(f.dir, "roms"))
	must(t, err)
	b, _ := os.ReadFile(filepath.Join(f.dir, "progress.jsonl"))
	for _, want := range []string{`"op":"Read the eMMC into the backup"`, `"state":"start"`,
		`"state":"run"`, `"state":"ok"`, `"total":67108864`, `"op":"Read the backup back from the SD card"`} {
		if !bytes.Contains(b, []byte(want)) {
			t.Errorf("progress has no %s", want)
		}
	}
	h := sha256.Sum256(f.stock)
	if !bytes.Contains(b, []byte(sum(h[:]))) {
		t.Error("progress doesn't carry the eMMC's checksum")
	}
}

// init starts services with umask 077; Settings (the system user) has to read
// what the installer writes for it.
func TestFilesReadableUnderInitUmask(t *testing.T) {
	old := syscall.Umask(0o077)
	defer syscall.Umask(old)
	ProcessSetup()
	f := newFake(t)
	_, err := f.cfg.Backup(f.progress(), filepath.Join(f.dir, "roms"))
	must(t, err)
	for _, path := range []string{filepath.Join(f.cfg.StateDir, "state-"+testCID+".json"), filepath.Join(f.dir, "progress.jsonl")} {
		fi, err := os.Stat(path)
		if err != nil {
			t.Fatal(err)
		}
		if fi.Mode().Perm()&0o044 != 0o044 {
			t.Fatalf("%s is %v", filepath.Base(path), fi.Mode())
		}
	}
}

// A stock eMMC with an MBR table (as EmuELEC images have): the install puts a
// whole GPT in its place, protective MBR included, and restore brings the
// MBR disk back byte for byte, its end too (where the GPT keeps its copy).
func TestStockMBR(t *testing.T) {
	f := newFake(t)
	mbr := f.stock[:Sector]
	for i := range mbr[446:] {
		mbr[446+i] = 0
	}
	part := func(n int, typ byte, first, count uint32) {
		e := mbr[446+16*n:]
		e[4] = typ
		binary.LittleEndian.PutUint32(e[8:], first)
		binary.LittleEndian.PutUint32(e[12:], count)
	}
	part(0, 0x0c, LoaderEnd, uint32(mib(16)))                           // FAT32 LBA
	part(1, 0x83, uint32(LoaderEnd+mib(16)), uint32(mib(48)-LoaderEnd)) // Linux
	mbr[510], mbr[511] = 0x55, 0xAA
	if err := os.WriteFile(f.cfg.EmmcDev, f.stock, 0644); err != nil {
		t.Fatal(err)
	}
	if in := f.cfg.Inspect(); len(in.Problems) != 0 || in.EmmcTable != nil {
		t.Fatalf("inspect of an MBR eMMC: %+v", in)
	}

	f.installTo2(t)
	s0 := f.emmc(0, 1)
	if s0[446+4] != 0xEE || s0[510] != 0x55 || s0[511] != 0xAA {
		t.Fatalf("sector 0 isn't a protective MBR: % x", s0[446:])
	}
	if !bytes.Equal(s0[446+16:510], make([]byte, 48)) || !bytes.Equal(s0[:446], make([]byte, 446)) {
		t.Fatal("old MBR entries or boot code left in sector 0")
	}
	out, err := exec.Command(sgdisk, "-v", f.cfg.EmmcDev).CombinedOutput()
	if err != nil || !strings.Contains(string(out), "No problems found") {
		t.Fatalf("eMMC table after the install: %v\n%s", err, out)
	}
	must(t, f.cfg.WriteLoader(f.progress(), f.state().LoaderSHA[:12]))

	must(t, f.cfg.Restore(f.progress(), f.state().Backup.Dir))
	if !bytes.Equal(f.emmc(0, mib(64)), f.stock) {
		t.Fatal("restore didn't bring the MBR eMMC back byte for byte")
	}
	if b := f.emmc(1, 1); bytes.HasPrefix(b, []byte("EFI PART")) {
		t.Fatal("a GPT header is left after restore")
	}
	if b := f.emmc(mib(64)-1, 1); bytes.HasPrefix(b, []byte("EFI PART")) {
		t.Fatal("the GPT's backup header is left at the end after restore")
	}
}

func TestUpdateUboot(t *testing.T) {
	f := newFake(t)
	f.installTo2(t)
	mustFail(t, f.cfg.UpdateUboot(f.progress()), "no andr36oid loader yet")
	must(t, f.cfg.WriteLoader(f.progress(), f.state().LoaderSHA[:12]))
	f.writes = nil
	must(t, f.cfg.UpdateUboot(f.progress()))
	if len(f.writes) != 0 {
		t.Fatalf("U-Boot already the same, still %d writes", len(f.writes))
	}

	// a new image on the SD card: U-Boot changed, idbloader and trust not
	copySecs := int64(TrustStart-UbootStart) / ubootCopies
	old := f.emmc(UbootStart, copySecs)
	nu := append([]byte("LOADER  "), randBytes(rand.New(rand.NewSource(7)), copySecs*Sector-8)...)
	sd, err := os.OpenFile(f.cfg.SDDev, os.O_WRONLY, 0)
	must(t, err)
	for i := int64(0); i < ubootCopies; i++ {
		_, err = sd.WriteAt(nu, (UbootStart+i*copySecs)*Sector)
		must(t, err)
	}
	must(t, sd.Close())

	// cut off at copy 2: copies 4 and 3 are new, 2 and 1 still the old ones
	w := testHooks.write
	testHooks.write = func(d *Disk, b []byte, off int64) ([]byte, error) {
		if d.Path == f.cfg.EmmcDev && off == (UbootStart+copySecs)*Sector {
			return nil, syscall.EIO
		}
		return w(d, b, off)
	}
	mustFail(t, f.cfg.UpdateUboot(f.progress()), "input/output error")
	for i, want := range [][]byte{old, old, nu, nu} {
		if !bytes.Equal(f.emmc(UbootStart+int64(i)*copySecs, copySecs), want) {
			t.Fatalf("after the cut, U-Boot copy %d is neither whole and as expected", i+1)
		}
	}
	if st := f.state(); st.Running != "update-uboot" || !f.locked {
		t.Fatalf("after the cut: %+v", st)
	}
	testHooks.write = w
	f.writes = nil
	must(t, f.cfg.UpdateUboot(f.progress()))
	if !bytes.Equal(f.emmc(LoaderStart, LoaderEnd-LoaderStart), f.sd(LoaderStart, LoaderEnd-LoaderStart)) {
		t.Fatal("U-Boot not updated")
	}
	for _, off := range f.writes {
		if off < UbootStart*Sector || off >= TrustStart*Sector {
			t.Fatalf("wrote outside U-Boot, at sector %d", off/Sector)
		}
	}
	if st := f.state(); st.Running != "" || st.Failed != "" || st.Step3 != "done" {
		t.Fatalf("after the update: %+v", st)
	}

	// a different idbloader on the SD card: refused, nothing written
	sd, err = os.OpenFile(f.cfg.SDDev, os.O_WRONLY, 0)
	must(t, err)
	_, err = sd.WriteAt([]byte{1, 2, 3}, (LoaderStart+5)*Sector)
	must(t, err)
	must(t, sd.Close())
	f.writes = nil
	mustFail(t, f.cfg.UpdateUboot(f.progress()), "idbloader differs")
	if len(f.writes) != 0 {
		t.Fatal("wrote after refusing")
	}
}

func TestUpdateEmmc(t *testing.T) {
	f := newFake(t)
	f.installTo2(t)
	mustFail(t, f.cfg.UpdateEmmc(f.progress()), "no andr36oid loader yet")
	must(t, f.cfg.WriteLoader(f.progress(), f.state().LoaderSHA[:12]))

	// apps and data on the eMMC: must stay as they are
	plan := f.state().Plan
	ud, _ := Find(plan, "userdata")
	n := ud.Sectors()
	if n > mib(1) {
		n = mib(1)
	}
	sd, err := os.OpenFile(f.cfg.SDDev, os.O_WRONLY, 0)
	must(t, err)
	e, err := os.OpenFile(f.cfg.EmmcDev, os.O_WRONLY, 0)
	must(t, err)
	data := randBytes(rand.New(rand.NewSource(9)), n*Sector)
	_, err = e.WriteAt(data, ud.First*Sector)
	must(t, err)
	must(t, e.Close())

	// a new image on the SD card: BOOT and system changed
	parts, err := ReadGPT(must2(OpenDisk(f.cfg.SDDev, false, false)))
	must(t, err)
	for _, name := range []string{"BOOT", "system"} {
		q, _ := Find(parts, name)
		_, err = sd.WriteAt([]byte("new "+name), q.First*Sector+4096)
		must(t, err)
	}
	must(t, sd.Close())

	must(t, f.cfg.UpdateEmmc(f.progress()))
	for _, name := range []string{"BOOT", "system", "vendor"} {
		src, _ := Find(parts, name)
		dst, _ := Find(plan, name)
		if !bytes.Equal(f.emmc(dst.First, src.Sectors()), f.sd(src.First, src.Sectors())) {
			t.Fatalf("%s on the eMMC isn't the SD card's", name)
		}
	}
	if !bytes.Equal(f.emmc(ud.First, n), data) {
		t.Fatal("userdata changed")
	}
	st := f.state()
	if st.Running != "" || st.Failed != "" || st.Step3 != "done" {
		t.Fatalf("after the update: %+v", st)
	}
	// the records follow: step 3's checks of system and vendor still pass
	sys, _ := Find(parts, "system")
	h := sha256.Sum256(f.sd(sys.First, sys.Sectors()))
	if st.Copied["system"] != hex.EncodeToString(h[:]) {
		t.Fatal("system's record not updated")
	}
}

// poke writes b straight into an image, past the installer (a fault or a
// change made behind its back).
func poke(t *testing.T, path string, b []byte, off int64) {
	t.Helper()
	fh, err := os.OpenFile(path, os.O_WRONLY, 0)
	must(t, err)
	_, err = fh.WriteAt(b, off)
	must(t, err)
	must(t, fh.Close())
}

func TestReviewFixes(t *testing.T) {
	roms := func(f *fake) string { return filepath.Join(f.dir, "roms") }
	t.Run("a damaged backup stops the steps that write", func(t *testing.T) {
		f := newFake(t)
		dir, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		// same length, different bytes: the sizes alone don't show it
		b := f.read(filepath.Join(dir, "user.001"), 0, 1)
		b[10] ^= 1
		poke(t, filepath.Join(dir, "user.001"), b, 0)
		f.writes = nil
		mustFail(t, f.cfg.BlankLoader(f.progress()), "doesn't read back as it was made")
		if len(f.writes) != 0 || f.state().Running != "" {
			t.Fatalf("wrote %d times with a damaged backup, state %+v", len(f.writes), f.state())
		}
	})
	t.Run("a damaged backup stops step 3", func(t *testing.T) {
		f := newFake(t)
		f.installTo2(t)
		dir := f.state().Backup.Dir
		b := f.read(filepath.Join(dir, "boot0.img"), 0, 1)
		b[3] ^= 1
		poke(t, filepath.Join(dir, "boot0.img"), b, 0)
		f.writes = nil
		mustFail(t, f.cfg.WriteLoader(f.progress(), f.state().LoaderSHA[:12]), "boot0.img is damaged")
		if len(f.writes) != 0 {
			t.Fatal("step 3 wrote with a damaged backup")
		}
	})
	t.Run("restore checks the loader before its header goes back", func(t *testing.T) {
		f := newFake(t)
		f.installTo2(t)
		must(t, f.cfg.WriteLoader(f.progress(), f.state().LoaderSHA[:12]))
		dir := f.state().Backup.Dir
		w := testHooks.write
		testHooks.write = func(d *Disk, b []byte, off int64) ([]byte, error) {
			b, err := w(d, b, off)
			if d.Path == f.cfg.EmmcDev && off == UbootStart*Sector && err == nil {
				b = append([]byte{}, b...)
				b[9] ^= 0x40 // the eMMC keeps a bad bit in U-Boot
			}
			return b, err
		}
		mustFail(t, f.cfg.Restore(f.progress(), dir), "U-Boot and trust on the eMMC reads back different")
		if !bytes.Equal(f.emmc(LoaderStart, 1), make([]byte, Sector)) {
			t.Fatal("the idbloader header went back over a loader that didn't check out")
		}
		testHooks.write = w
		must(t, f.cfg.Restore(f.progress(), dir))
		if !bytes.Equal(f.emmc(0, mib(64)), f.stock) {
			t.Fatal("second restore didn't bring the stock eMMC back")
		}
	})
	t.Run("restore says when the boot areas differ", func(t *testing.T) {
		f := newFake(t)
		dir, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		must(t, f.cfg.BlankLoader(f.progress()))
		poke(t, f.cfg.EmmcDev+"boot1", []byte("changed"), 4096)
		err = f.cfg.Restore(f.progress(), dir)
		var ne *NotExactError
		if !errors.As(err, &ne) || !strings.Contains(err.Error(), "boot1 area differs") {
			t.Fatalf("restore with a changed boot1: %v", err)
		}
		if !bytes.Equal(f.emmc(0, mib(64)), f.stock) {
			t.Fatal("the user area wasn't written back")
		}
		if st := f.state(); st.Running != "" || st.Failed != "" || st.Step1 != "" || st.Backup == nil {
			t.Fatalf("after the restore: %+v", st)
		}
	})
	t.Run("an update left half done isn't installed", func(t *testing.T) {
		f := newFake(t)
		f.installTo2(t)
		must(t, f.cfg.WriteLoader(f.progress(), f.state().LoaderSHA[:12]))
		parts, err := ReadGPT(must2(OpenDisk(f.cfg.SDDev, false, false)))
		must(t, err)
		sys, _ := Find(parts, "system")
		poke(t, f.cfg.SDDev, []byte("new system"), sys.First*Sector+4096)
		plan := f.state().Plan
		dst, _ := Find(plan, "system")
		w := testHooks.write
		testHooks.write = func(d *Disk, b []byte, off int64) ([]byte, error) {
			if d.Path == f.cfg.EmmcDev && off == dst.First*Sector {
				return nil, syscall.EIO
			}
			return w(d, b, off)
		}
		mustFail(t, f.cfg.UpdateEmmc(f.progress()), "input/output error")
		testHooks.write = w
		must(t, f.cfg.Leave(f.progress()))
		st := f.state()
		if st.Incomplete != "update-emmc" || st.Running != "" || st.Failed != "" {
			t.Fatalf("after leaving: %+v", st)
		}
		mustFail(t, f.cfg.UpdateUboot(f.progress()), "update-emmc didn't finish")
		must(t, f.cfg.UpdateEmmc(f.progress()))
		if st := f.state(); st.Incomplete != "" || st.Step3 != "done" {
			t.Fatalf("after the update ran again: %+v", st)
		}
	})
	t.Run("a restore left half done stays on record", func(t *testing.T) {
		f := newFake(t)
		_, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		st := f.state()
		st.Running, st.Failed = "restore", "cut off"
		must(t, f.cfg.Save(st))
		must(t, f.cfg.Leave(f.progress()))
		if st := f.state(); st.Incomplete != "restore" {
			t.Fatalf("after leaving: %+v", st)
		}
		mustFail(t, f.cfg.BlankLoader(f.progress()), "restore didn't finish")
		must(t, f.cfg.Restore(f.progress(), f.state().Backup.Dir))
		if st := f.state(); st.Incomplete != "" {
			t.Fatalf("after the restore: %+v", st)
		}
	})
	t.Run("a torn U-Boot copy on the eMMC can be updated", func(t *testing.T) {
		f := newFake(t)
		f.installTo2(t)
		must(t, f.cfg.WriteLoader(f.progress(), f.state().LoaderSHA[:12]))
		copySecs := int64(TrustStart-UbootStart) / ubootCopies
		nu := append([]byte("LOADER  "), randBytes(rand.New(rand.NewSource(8)), copySecs*Sector-8)...)
		for i := int64(0); i < ubootCopies; i++ {
			poke(t, f.cfg.SDDev, nu, (UbootStart+i*copySecs)*Sector)
		}
		// a power cut tore copy 1 on the eMMC
		poke(t, f.cfg.EmmcDev, make([]byte, Sector), UbootStart*Sector)
		f.writes = nil
		must(t, f.cfg.UpdateUboot(f.progress()))
		if f.writes[0] != UbootStart*Sector {
			t.Fatalf("the torn copy wasn't written first (sector %d)", f.writes[0]/Sector)
		}
		if !bytes.Equal(f.emmc(LoaderStart, LoaderEnd-LoaderStart), f.sd(LoaderStart, LoaderEnd-LoaderStart)) {
			t.Fatal("U-Boot not updated")
		}
		// the SD card's copies are still checked strictly
		poke(t, f.cfg.SDDev, make([]byte, Sector), (UbootStart+2*copySecs)*Sector)
		mustFail(t, f.cfg.UpdateUboot(f.progress()), "the SD card's U-Boot copy 3")
	})
	t.Run("a test put off waits for test now", func(t *testing.T) {
		f := newFake(t)
		_, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		must(t, f.cfg.BlankLoader(f.progress()))
		f.restart("boot-2")
		must(t, f.cfg.CheckLoader(f.progress()))
		must(t, f.cfg.WriteOS(f.progress()))
		must(t, f.cfg.CancelTest(f.progress()))
		if st := f.state(); st.Step2 != "deferred" || f.testFile() != "" {
			t.Fatalf("after putting it off: %+v, file %q", st, f.testFile())
		}
		res, err := f.cfg.CheckTest(f.progress())
		if err != nil || res != "deferred" || f.state().Step2 != "deferred" || f.state().Failed != "" {
			t.Fatalf("check-test of a put off test: %s %v %+v", res, err, f.state())
		}
		must(t, f.cfg.StartTest(f.progress()))
		if f.state().Step2 != "written" || f.testFile() != "emmc_test=start\n" {
			t.Fatalf("after test now: %+v, file %q", f.state(), f.testFile())
		}
		f.uboot()
		f.emmcStart()
		res, err = f.cfg.Confirm(f.progress())
		if err != nil || res != "confirmed" {
			t.Fatalf("confirm: %s %v", res, err)
		}
		f.sdStart()
		res, err = f.cfg.CheckTest(f.progress())
		if err != nil || res != "passed" {
			t.Fatalf("check-test: %s %v", res, err)
		}
	})
}

func TestStatePerEmmc(t *testing.T) {
	const otherCID = "150100503142454c0000112233445566"
	cidFile := func(f *fake) string { return filepath.Join(f.cfg.EmmcSys, "device/cid") }
	t.Run("two consoles, one SD card", func(t *testing.T) {
		f := newFake(t)
		_, err := f.cfg.Backup(f.progress(), filepath.Join(f.dir, "roms"))
		must(t, err)
		mine := filepath.Join(f.cfg.StateDir, "state-"+testCID+".json")
		before, err := os.ReadFile(mine)
		must(t, err)
		if _, err := os.Stat(filepath.Join(f.cfg.StateDir, "state.json")); !errors.Is(err, os.ErrNotExist) {
			t.Fatal("a shared state.json was written")
		}
		// the same SD card in a second console
		writeFile(t, cidFile(f), otherCID+"\n")
		st := f.state()
		if st.Backup != nil || st.CID != "" {
			t.Fatalf("the second console sees the first one's records: %+v", st)
		}
		mustFail(t, f.cfg.BlankLoader(f.progress()), "no checked backup")
		st.Step1 = "passed" // anything, saved for the second console
		must(t, f.cfg.Save(st))
		if after, _ := os.ReadFile(mine); !bytes.Equal(before, after) {
			t.Fatal("the first console's records changed")
		}
		// a record of the first eMMC can't be saved over the second's
		mustFail(t, f.cfg.Save(&State{CID: testCID}), "not saved over")
		// back in the first console
		writeFile(t, cidFile(f), testCID+"\n")
		if st := f.state(); st.Backup == nil || st.Step1 != "" {
			t.Fatalf("the first console's records after the second: %+v", st)
		}
	})
	t.Run("records from before are taken over by their eMMC only", func(t *testing.T) {
		f := newFake(t)
		legacy := filepath.Join(f.cfg.StateDir, "state.json")
		b, _ := json.Marshal(&State{CID: otherCID, Step1: "passed"})
		writeFile(t, legacy, string(b))
		if st := f.state(); st.Step1 != "" {
			t.Fatalf("another eMMC's old records were taken: %+v", st)
		}
		if got, _ := os.ReadFile(legacy); !bytes.Equal(got, b) {
			t.Fatal("another eMMC's old records were touched")
		}
		b, _ = json.Marshal(&State{CID: testCID, Step1: "passed"})
		writeFile(t, legacy, string(b))
		if st := f.state(); st.Step1 != "passed" || st.CID != testCID {
			t.Fatalf("this eMMC's old records weren't taken over: %+v", st)
		}
		if _, err := os.Stat(legacy); !errors.Is(err, os.ErrNotExist) {
			t.Fatal("the old records are still there to be taken again")
		}
		if _, err := os.Stat(filepath.Join(f.cfg.StateDir, "state-"+testCID+".json")); err != nil {
			t.Fatal(err)
		}
		if st := f.state(); st.Step1 != "passed" {
			t.Fatalf("records after taking them over: %+v", st)
		}
	})
}
