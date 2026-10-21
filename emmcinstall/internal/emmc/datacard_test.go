package emmc

import (
	"bytes"
	"fmt"
	"math/rand"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// the first start's partitions on the test SD card
var datacardParts = []fakePart{
	{6, "userdata", mib(35), mib(6), "8300"},
	{13, "AUTOAPK", mib(41), mib(1), "0700"},
	{7, "EASYROMS", mib(42), mib(5), "0700"},
}

// newDatacardFake is a console with the first start's partitions on its SD
// card, a 64 MiB card in slot 2 (vold has its old partition mounted), and
// folders for every file system the data card steps mount.
func newDatacardFake(t *testing.T) *fake {
	t.Helper()
	oldMin, oldSlack := easyMin, easySlack
	easyMin, easySlack = mib(2), mib(1)
	t.Cleanup(func() { easyMin, easySlack = oldMin, oldSlack })

	f := newFakeWith(t, datacardParts)
	r := rand.New(rand.NewSource(7))
	card := filepath.Join(f.dir, "card2")
	if err := os.WriteFile(card, randBytes(r, mib(64)*Sector), 0644); err != nil {
		t.Fatal(err)
	}
	sys := filepath.Join(f.dir, "sys")
	writeFile(t, filepath.Join(sys, "mmcblk1/size"), fmt.Sprint(mib(64)))
	writeFile(t, filepath.Join(sys, "mmcblk1/dev"), "179:32\n")
	writeFile(t, filepath.Join(sys, "mmcblk1/device/type"), "SD\n")
	writeFile(t, filepath.Join(sys, "mmcblk1/device/name"), "SN64G\n")
	writeFile(t, filepath.Join(sys, "mmcblk1/mmcblk1p1/dev"), "179:33\n")
	writeFile(t, filepath.Join(sys, "mmcblk0/mmcblk0p6/dev"), "179:6\n")
	writeFile(t, filepath.Join(sys, "mmcblk0/mmcblk0p7/dev"), "179:7\n")
	roms := filepath.Join(f.dir, "media_rw")
	writeFile(t, filepath.Join(roms, "roms/snes/game.sfc"), strings.Repeat("rom", 1000))
	writeFile(t, filepath.Join(roms, "bios/bios.bin"), "bios")
	writeFile(t, filepath.Join(f.dir, "mountinfo"),
		"20 1 179:2 / /system ro,relatime - ext4 /dev/block/dm-0 ro\n"+
			"21 1 179:6 / /data rw,relatime - f2fs /dev/block/mmcblk0p6 rw\n"+
			"22 1 179:7 / "+roms+" rw,relatime - exfat /dev/block/vold/public:179,7 rw\n"+
			"23 1 179:33 / /mnt/media_rw/OLD rw,relatime - vfat /dev/block/vold/public:179,33 rw\n")
	writeFile(t, filepath.Join(f.cfg.SDBootDir, "boot.ini"), "odroidgoa-uboot-config\n")
	writeFile(t, filepath.Join(f.cfg.SDBootDir, "rk3326-r36s-android.dtb"), "dtb")

	f.cfg.SD2Sys, f.cfg.SD2Dev = filepath.Join(sys, "mmcblk1"), card
	f.cfg.DataDir = f.dir
	f.cfg.fs = &fsOps{
		mount: func(c *Config, dev string, s span, fstype string, rw bool, fn func(string) error) error {
			dir := f.fsDir(dev, s)
			if err := os.MkdirAll(dir, 0755); err != nil {
				return err
			}
			return fn(dir)
		},
		mkfs: func(c *Config, dev string, s span, fstype, label string) error {
			dir := f.fsDir(dev, s)
			os.RemoveAll(dir)
			return os.MkdirAll(dir, 0755)
		},
		unmountVol: func(c *Config, majMin string) error {
			b, _ := os.ReadFile(c.Mountinfo)
			var keep []string
			for _, l := range strings.Split(strings.TrimSpace(string(b)), "\n") {
				if strings.Fields(l)[2] != majMin {
					keep = append(keep, l)
				}
			}
			return os.WriteFile(c.Mountinfo, []byte(strings.Join(keep, "\n")+"\n"), 0644)
		},
		used: treeBytes,
	}
	return f
}

// fsDir is the folder standing in for the file system in span s of dev.
func (f *fake) fsDir(dev string, s span) string {
	return filepath.Join(f.dir, "fs", fmt.Sprintf("%s-%d", filepath.Base(dev), s.lo))
}

// the new card's BOOT after the block copy: the SD card's files
func (f *fake) seedCardBoot() {
	dir := f.fsDir(f.cfg.SD2Dev, span{mib(16), mib(20)})
	os.MkdirAll(dir, 0755)
	ents, _ := os.ReadDir(f.cfg.SDBootDir)
	for _, e := range ents {
		b, _ := os.ReadFile(filepath.Join(f.cfg.SDBootDir, e.Name()))
		writeFile(f.t, filepath.Join(dir, e.Name()), string(b))
	}
}

func exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func readParts(t *testing.T, path string) []Part {
	t.Helper()
	d, err := OpenDisk(path, false, false)
	must(t, err)
	defer d.Close()
	parts, err := ReadGPT(d)
	must(t, err)
	return parts
}

func TestDatacardLayout(t *testing.T) {
	sd := []Part{
		{Num: 1, Name: "BOOT", First: 32768, Last: 557055},
		{Num: 2, Name: "system", First: 600000, Last: 5701007},
		{Num: 3, Name: "idbloader", First: 64, Last: 16383},
		{Num: 6, Name: "userdata", First: 5701632, Last: 40000000, Attr: 1 << 63},
		{Num: 7, Name: "EASYROMS", First: 42000000, Last: 60000000},
		{Num: 13, Name: "AUTOAPK", First: 40001536, Last: 41000000},
	}
	got, err := newCardLayout(sd, 62333952, true, mib(256))
	must(t, err)
	var names []string
	for _, p := range got {
		names = append(names, fmt.Sprintf("%d:%s:%d-%d", p.Num, p.Name, p.First, p.Last))
	}
	want := "1:BOOT:32768-557055 2:system:600000-5701007 3:idbloader:64-16383 6:userdata:5701632-40000000 " +
		"7:EASYROMS:41000960-62333918 13:AUTOAPK:40001536-41000000"
	if strings.Join(names, " ") != want {
		t.Fatalf("layout\n%s\nwant\n%s", strings.Join(names, " "), want)
	}
	if u, _ := Find(got, "userdata"); u.Attr != 1<<63 {
		t.Fatal("userdata's attributes not kept")
	}
	_, err = newCardLayout(sd, 40400000, false, mib(256))
	mustFail(t, err, "too small")
	// B and C without AUTOAPK: EASYROMS right after userdata
	got, err = newCardLayout(sd, 62333952, false, mib(256))
	must(t, err)
	if e, _ := Find(got, "EASYROMS"); e.First != 40001536 {
		t.Fatalf("EASYROMS at %d", e.First)
	}
}

func TestDatacardNewCard(t *testing.T) {
	f := newDatacardFake(t)
	o, err := f.cfg.DatacardOptions()
	must(t, err)
	if !o.Slot2.Present || o.AutoAPK != mib(1)*Sector || o.Userdata != mib(6)*Sector || o.EasyromsUsed != 3004 {
		t.Fatalf("options %+v", o)
	}
	mustFail(t, f.cfg.DatacardChoose(f.progress(), "new", "A", false), "backup")
	_, err = f.cfg.Backup(f.progress(), filepath.Join(f.dir, "roms"))
	must(t, err)
	mustFail(t, f.cfg.DatacardChoose(f.progress(), "new", "D", false), "A, B or C")
	must(t, f.cfg.DatacardChoose(f.progress(), "new", "A", false))
	if d := f.state().Datacard; d.Kind != "new" || d.Option != "A" || !d.AutoAPK {
		t.Fatalf("choice %+v", d)
	}
	mustFail(t, f.cfg.DatacardPrepare(f.progress()), "step 3")
	f.installFromStep1(t)

	f.seedCardBoot()
	mustFail(t, f.cfg.DatacardFinish(f.progress()), "isn't made yet")
	must(t, f.cfg.DatacardPrepare(f.progress()))
	d := f.state().Datacard
	if d.Stage != "prepared" || len(d.UUID) != 36 || d.UUID != strings.ToLower(d.UUID) {
		t.Fatalf("after prepare %+v", d)
	}
	out, err := exec.Command(sgdisk, "-v", f.cfg.SD2Dev).CombinedOutput()
	if err != nil || !strings.Contains(string(out), "No problems found") {
		t.Fatalf("new card table: %v\n%s", err, out)
	}
	sdParts, card := readParts(t, f.cfg.SDDev), readParts(t, f.cfg.SD2Dev)
	for _, name := range []string{"BOOT", "system", "vendor", "userdata", "AUTOAPK"} {
		a, _ := Find(sdParts, name)
		b, ok := Find(card, name)
		if !ok || a.Num != b.Num || a.Sectors() != b.Sectors() || a.GUID == b.GUID {
			t.Fatalf("%s: SD %+v, card %+v", name, a, b)
		}
	}
	if ez, _ := Find(card, "EASYROMS"); ez.Num != 7 || ez.First != mib(42) {
		t.Fatalf("EASYROMS %+v", ez)
	}
	read := func(path string, p Part, n int64) []byte { return f.read(path, p.First, n) }
	for _, name := range []string{"AUTOAPK", "BOOT"} {
		a, _ := Find(sdParts, name)
		b, _ := Find(card, name)
		if !bytes.Equal(read(f.cfg.SDDev, a, a.Sectors()), read(f.cfg.SD2Dev, b, b.Sectors())) {
			t.Fatalf("%s not copied", name)
		}
	}
	zero := func(path string, from, n int64) bool {
		return bytes.Equal(f.read(path, from, n), make([]byte, n*Sector))
	}
	sys, _ := Find(card, "system")
	ud, _ := Find(card, "userdata")
	if !zero(f.cfg.SD2Dev, LoaderStart, LoaderEnd-LoaderStart) || !zero(f.cfg.SD2Dev, sys.First, sys.Sectors()) ||
		!zero(f.cfg.SD2Dev, ud.First, align) {
		t.Fatal("loader, system or userdata's start not blank on the new card")
	}
	boot := f.fsDir(f.cfg.SD2Dev, spanOf(Part{First: mib(16), Last: mib(20) - 1}))
	if exists(filepath.Join(boot, "boot.ini")) || !exists(filepath.Join(boot, "boot.ini.datacard")) ||
		!exists(filepath.Join(boot, DatacardMarker)) {
		t.Fatal("the new card's BOOT can still start, or has no marker")
	}
	ez, _ := Find(card, "EASYROMS")
	if b, err := os.ReadFile(filepath.Join(f.fsDir(f.cfg.SD2Dev, spanOf(ez)), "roms/snes/game.sfc")); err != nil || len(b) != 3000 {
		t.Fatalf("EASYROMS not copied: %v", err)
	}
	if !strings.Contains(f.testFile(), "emmc_test=start") {
		t.Fatal("no test start for the copy")
	}

	// the copy, at the start from the eMMC
	mustFail(t, f.cfg.DatacardFinish(f.progress()), "isn't copied")
	f.uboot()
	f.emmcStart()
	res, err := f.cfg.Confirm(f.progress())
	must(t, err)
	if res != "confirmed" || !f.cfg.DatacardCopyPending() {
		t.Fatalf("confirm %s, copy pending %v", res, f.cfg.DatacardCopyPending())
	}
	mustFail(t, func() error { _, err := f.cfg.DatacardCopy(f.progress()); return err }(), "in use")
	writeFile(t, filepath.Join(f.dir, "mountinfo"), "20 1 179:2 / /system ro,relatime - ext4 /dev/block/dm-0 ro\n")
	res, err = f.cfg.DatacardCopy(f.progress())
	must(t, err)
	if res != "copied" || f.cfg.DatacardCopyPending() {
		t.Fatalf("copy %s", res)
	}
	a, _ := Find(sdParts, "userdata")
	if !bytes.Equal(read(f.cfg.SDDev, a, a.Sectors()), read(f.cfg.SD2Dev, ud, ud.Sectors())) {
		t.Fatal("userdata not copied")
	}

	f.sdStart()
	must(t, f.cfg.DatacardFinish(f.progress()))
	if d := f.state().Datacard; d.Stage != "done" {
		t.Fatalf("after finish %+v", d)
	}
	plan := f.state().Plan
	eb, _ := Find(plan, "BOOT")
	if b, _ := os.ReadFile(filepath.Join(f.fsDir(f.cfg.EmmcDev, spanOf(eb)), DatacardFile)); string(b) != "datacard="+d.UUID+"\n" {
		t.Fatalf("eMMC datacard.txt %q", b)
	}
	if exists(filepath.Join(f.cfg.SDBootDir, DatacardCopy)) || f.testFile() != "" ||
		!exists(filepath.Join(f.cfg.SDBootDir, "boot.ini")) {
		t.Fatal("the old SD card's BOOT not left as it was")
	}

	// the stock eMMC back: the new card starts the console again, data kept
	mustFail(t, func() error { _, err := f.cfg.DatacardRevive(f.progress(), false); return err }(), "no data card")
	must(t, f.cfg.Restore(f.progress(), f.state().Backup.Dir))
	if r := f.state().Revive; r == nil || r.UUID != d.UUID || r.Kind != "new" || f.state().Datacard != nil {
		t.Fatalf("after restore %+v", f.state())
	}
	userdata := read(f.cfg.SD2Dev, ud, ud.Sectors())
	writeFile(t, filepath.Join(f.dir, "mountinfo"), "20 1 179:2 / /system ro,relatime - ext4 /dev/block/dm-0 ro\n"+
		"23 1 179:33 / /mnt/media_rw/OLD rw,relatime - vfat /dev/block/vold/public:179,33 rw\n")
	if res, err := f.cfg.DatacardRevive(f.progress(), false); err != nil || res != "slot 2" {
		t.Fatalf("revive %q %v", res, err)
	}
	if f.state().Revive != nil {
		t.Fatal("still offered after revive")
	}
	if !bytes.Equal(f.read(f.cfg.SD2Dev, LoaderStart, LoaderEnd-LoaderStart), f.sd(LoaderStart, LoaderEnd-LoaderStart)) {
		t.Fatal("the card has no loader")
	}
	for _, name := range []string{"system", "vendor"} {
		a, _ := Find(sdParts, name)
		b, _ := Find(card, name)
		if !bytes.Equal(read(f.cfg.SDDev, a, a.Sectors()), read(f.cfg.SD2Dev, b, b.Sectors())) {
			t.Fatalf("%s not copied", name)
		}
	}
	if !bytes.Equal(read(f.cfg.SD2Dev, ud, ud.Sectors()), userdata) {
		t.Fatal("userdata changed")
	}
	if !exists(filepath.Join(boot, "boot.ini")) || exists(filepath.Join(boot, "boot.ini.datacard")) ||
		exists(filepath.Join(boot, DatacardMarker)) {
		t.Fatal("the card's BOOT can't start, or still has the marker")
	}
}

// installFromStep1: the install from step 1 (the backup is made).
func (f *fake) installFromStep1(t *testing.T) {
	t.Helper()
	must(t, f.cfg.BlankLoader(f.progress()))
	f.restart("boot-2")
	must(t, f.cfg.CheckLoader(f.progress()))
	must(t, f.cfg.WriteOS(f.progress()))
	f.uboot()
	f.emmcStart()
	_, err := f.cfg.Confirm(f.progress())
	must(t, err)
	f.sdStart()
	_, err = f.cfg.CheckTest(f.progress())
	must(t, err)
	must(t, f.cfg.WriteLoader(f.progress(), f.state().LoaderSHA[:12]))
}

func TestDatacardOriginalAndBack(t *testing.T) {
	f := newDatacardFake(t)
	backup, err := f.cfg.Backup(f.progress(), filepath.Join(f.dir, "roms"))
	must(t, err)
	must(t, f.cfg.DatacardChoose(f.progress(), "original", "", false))
	mustFail(t, f.cfg.DatacardFinish(f.progress()), "fully installed")
	f.installFromStep1(t)
	loader := f.sd(LoaderStart, LoaderEnd-LoaderStart)

	f.sdWritable = true
	must(t, f.cfg.DatacardFinish(f.progress()))
	sdParts := readParts(t, f.cfg.SDDev)
	ud, _ := Find(sdParts, "userdata")
	d := f.state().Datacard
	if d.Stage != "blank" || d.UUID != ud.PartUUID() {
		t.Fatalf("after finish %+v", d)
	}
	eb, _ := Find(f.state().Plan, "BOOT")
	emmcBoot := f.fsDir(f.cfg.EmmcDev, spanOf(eb))
	if b, _ := os.ReadFile(filepath.Join(emmcBoot, DatacardFile)); string(b) != "datacard="+ud.PartUUID()+"\n" {
		t.Fatalf("eMMC datacard.txt %q", b)
	}
	if exists(filepath.Join(f.cfg.SDBootDir, "boot.ini")) || !exists(filepath.Join(f.cfg.SDBootDir, DatacardMarker)) {
		t.Fatal("the SD card can still start")
	}
	if !bytes.Equal(f.sd(LoaderStart, LoaderEnd-LoaderStart), make([]byte, (LoaderEnd-LoaderStart)*Sector)) {
		t.Fatal("the SD card's loader isn't blank")
	}

	// the first start from the eMMC with the card as its data: system blanked
	writeFile(t, filepath.Join(f.dir, "cmdline"),
		"console=ttyFIQ0 androidboot.boot_devices=ff390000.dwmmc dw_mmc_rockchip.builtin_emmc=system "+
			"androidboot.fstab_suffix=emmcsd androidboot.datacard="+ud.PartUUID())
	writeFile(t, filepath.Join(f.dir, "mountinfo"), "21 1 179:6 / /data rw,relatime - f2fs /dev/block/mmcblk0p6 rw\n")
	res, err := f.cfg.Confirm(f.progress())
	must(t, err)
	sys, _ := Find(sdParts, "system")
	if res != "no test" || f.state().Datacard.Stage != "done" ||
		!bytes.Equal(f.sd(sys.First, sys.Sectors()), make([]byte, sys.Sectors()*Sector)) {
		t.Fatalf("confirm %s, stage %s, system blank?", res, f.state().Datacard.Stage)
	}
	if ud2 := f.sd(ud.First, 8); bytes.Equal(ud2, make([]byte, 8*Sector)) {
		t.Fatal("userdata was blanked")
	}

	// the flasher made it start again: boot.ini, loader and system back
	must(t, os.Rename(filepath.Join(f.cfg.SDBootDir, "boot.ini.datacard"), filepath.Join(f.cfg.SDBootDir, "boot.ini")))
	poke(t, f.cfg.SDDev, loader, LoaderStart*Sector)
	f.sdStart()
	f.restart("boot-9")
	writeFile(t, filepath.Join(f.dir, "mountinfo"), "21 1 179:6 / /data rw,relatime - f2fs /dev/block/mmcblk0p6 rw\n")
	chk, err := f.cfg.DatacardCheck()
	must(t, err)
	if chk["offer"] != true {
		t.Fatalf("check %v", chk)
	}
	// without the records (a card made fresh): the eMMC is taken as installed
	must(t, os.Remove(f.cfg.statePath(testCID)))
	must(t, f.cfg.DatacardReturn(f.progress()))
	st := f.state()
	if st.Step3 != "done" || st.Datacard.Stage != "blank" || exists(filepath.Join(f.cfg.SDBootDir, "boot.ini")) {
		t.Fatalf("after return %+v %+v", st, st.Datacard)
	}
	if b, _ := os.ReadFile(filepath.Join(emmcBoot, DatacardFile)); string(b) != "datacard="+ud.PartUUID()+"\n" {
		t.Fatalf("eMMC datacard.txt after return %q", b)
	}

	// the stock eMMC back, from this card the flasher made start: only the
	// marker goes
	st.Running = ""
	must(t, f.cfg.Save(st))
	f.sdWritable = true
	must(t, f.cfg.Restore(f.progress(), backup))
	if r := f.state().Revive; r == nil || r.UUID != ud.PartUUID() || r.Kind != "original" {
		t.Fatalf("after restore %+v", f.state())
	}
	writeFile(t, filepath.Join(f.cfg.SDBootDir, "boot.ini.datacard"), "old\n")
	writeFile(t, filepath.Join(f.cfg.SDBootDir, "boot.ini"), "odroidgoa-uboot-config\n")
	if res, err := f.cfg.DatacardRevive(f.progress(), false); err != nil || res != "this card" {
		t.Fatalf("revive %q %v", res, err)
	}
	if b, _ := os.ReadFile(filepath.Join(f.cfg.SDBootDir, "boot.ini")); string(b) != "odroidgoa-uboot-config\n" ||
		exists(filepath.Join(f.cfg.SDBootDir, "boot.ini.datacard")) || exists(filepath.Join(f.cfg.SDBootDir, DatacardMarker)) ||
		f.state().Revive != nil {
		t.Fatal("this card's BOOT not cleaned up")
	}
	writeFile(t, filepath.Join(emmcBoot, DatacardFile), "datacard="+ud.PartUUID()+"\n")
	st = f.state()
	st.Revive = &Datacard{Kind: "original", UUID: ud.PartUUID()}
	must(t, f.cfg.Save(st))
	_, err = f.cfg.DatacardRevive(f.progress(), true)
	must(t, err)
	if st := f.state(); st.Revive != nil {
		t.Fatalf("after forget %+v", st)
	}

	// another card's marker isn't this eMMC's data card
	writeFile(t, filepath.Join(emmcBoot, DatacardFile), "datacard=00000000-0000-4000-8000-000000000000\n")
	chk, err = f.cfg.DatacardCheck()
	must(t, err)
	if chk["offer"] != false {
		t.Fatalf("check %v", chk)
	}
}
