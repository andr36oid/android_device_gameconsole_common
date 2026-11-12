package setup

import (
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func firstBootCase(t *testing.T) (*Setup, *fake, string) {
	f := newFake()
	s, root := newSetup(t, f)
	s.BootDev = filepath.Join(root, "boot")
	s.BootLock = filepath.Join(root, "boot.lock")
	s.BootMount = ""
	s.SubsystemControl = filepath.Join(root, "control")
	s.BlockSys = filepath.Join(root, "sysclass")
	s.SystemDev = filepath.Join(root, "mmcblk9p2")
	s.ErofsSupport = "support"
	must(t, os.MkdirAll(s.BootDev, 0700))
	write(t, s.SystemDev, "")
	f.props["ro.andr36oid.version"] = "v2026-11-11-release"
	f.props["ro.product.build.version.incremental"] = "eng.test.20261111.220000"
	f.props["ro.build.version.incremental"] = "eng.test.20261111.220000"
	f.settings["global andr36oid_daijishou_ready"] = "1"
	write(t, s.state("preparation-library-ready"), "")
	return s, f, filepath.Join(s.BootDev, "andr36oid-firstboot.txt")
}
func TestFirstBootWaitsForRealAppsAndLibrary(t *testing.T) {
	s, f, marker := firstBootCase(t)
	write(t, s.AutoapkDev, "")
	write(t, s.Apps+"/Test.apk", "apk")
	s.FirstBootRecord()
	if sys_exists(marker) {
		t.Fatal("published before apps done")
	}
	write(t, s.AppsDoneFile, "")
	delete(f.settings, "global andr36oid_daijishou_ready")
	s.FirstBootRecord()
	if sys_exists(marker) {
		t.Fatal("published before library ready")
	}
	f.settings["global andr36oid_daijishou_ready"] = "1"
	s.FirstBootRecord()
	want := "done=1\nbuild=v2026-11-11-release|eng.test.20261111.220000\nlayout=legacy\n"
	if read(t, marker) != want || f.props["sys.andr36oid.firstboot.done"] != "1" {
		t.Fatal("bad completion record", read(t, marker))
	}
	must(t, os.Remove(s.AutoapkDev))
	must(t, os.Remove(s.AppsDoneFile))
	must(t, os.RemoveAll(s.Apps))
	s.FirstBootRecord()
	if read(t, marker) != want || sys_exists(s.BootLock) || sys_exists(marker+".tmp") {
		t.Fatal("reclaim/idempotency/lock failed")
	}
}
func TestFirstBootRequiresThisPartitionsDurableReadyKey(t *testing.T) {
	s, f, marker := firstBootCase(t)
	write(t, filepath.Join(s.BootDev, "andr36oid-layout.txt"), "prepared-1\n")
	write(t, filepath.Join(s.BootDev, "andr36oid-darkosen.json"), `{"format":1}`)
	family, gen := strings.Repeat("a", 64), strings.Repeat("b", 64)
	part := strings.TrimSuffix(s.SystemDev, "p2") + "p14"
	ready := filepath.Join(s.SubsystemControl, "ready", family+"-"+gen+"-native1")
	f.props["sys.andr36oid.subsystem.ready"] = "1" // another root's ephemeral state cannot authorize it
	f.handlers["support"] = func(args []string) (string, error) { return "", errors.New("not validated") }
	s.FirstBootRecord()
	if sys_exists(marker) {
		t.Fatal("published before partition validation")
	}
	f.handlers["support"] = func(args []string) (string, error) {
		if args[0] != "partition-cached" || args[3] != part {
			t.Fatalf("wrong partition args: %v", args)
		}
		return "origin=release\ntransport=partition\nsource_id=" + family + "\ngeneration=" + gen + "\nsource_path=" + part + "\n", nil
	}
	s.FirstBootRecord()
	if sys_exists(marker) {
		t.Fatal("published before durable SETUP key")
	}
	write(t, ready, "/other/card/p14\n")
	s.FirstBootRecord()
	if sys_exists(marker) {
		t.Fatal("accepted another source's ready record")
	}
	write(t, ready, part+"\n")
	s.FirstBootRecord()
	if !strings.Contains(read(t, marker), "layout=prepared-1\n") {
		t.Fatal("required SETUP didn't unblock prepared completion")
	}
	// An updated manifest/cache/generation cannot inherit an old generation's key.
	must(t, os.Remove(marker))
	gen = strings.Repeat("c", 64)
	s.FirstBootRecord()
	if sys_exists(marker) {
		t.Fatal("new generation reused old ready key")
	}
}
func TestFirstBootLockAndFailedWritePreserveExistingState(t *testing.T) {
	s, _, marker := firstBootCase(t)
	must(t, os.Mkdir(s.BootLock, 0700))
	write(t, filepath.Join(s.BootLock, "pid"), strconv.Itoa(os.Getpid()))
	s.FirstBootRecord()
	if sys_exists(marker) || !sys_exists(s.BootLock) {
		t.Fatal("stole a live BOOT lock")
	}
	write(t, filepath.Join(s.BootLock, "pid"), "999999999")
	s.FirstBootRecord()
	if !sys_exists(marker) || sys_exists(s.BootLock) {
		t.Fatal("did not recover stale lock")
	}
	must(t, os.Remove(marker))
	must(t, os.Mkdir(marker, 0700))
	write(t, marker+"/sentinel", "keep")
	s.FirstBootRecord()
	if read(t, marker+"/sentinel") != "keep" {
		t.Fatal("overwrote nonregular marker")
	}
}

func TestFirstBootMissingGuestJSONCannotBypassRequiredPartition(t *testing.T) {
	s, _, marker := firstBootCase(t)
	write(t, filepath.Join(s.BootDev, "andr36oid-layout.txt"), "prepared-1\nDARKOS 100 200\n")
	s.FirstBootRecord()
	if sys_exists(marker) {
		t.Fatal("missing manifest bypassed required guest")
	}
	must(t, os.Remove(filepath.Join(s.BootDev, "andr36oid-layout.txt")))
	write(t, filepath.Join(s.BlockSys, "mmcblk9p14/uevent"), "PARTNAME=DARKOS\n")
	s.FirstBootRecord()
	if sys_exists(marker) {
		t.Fatal("DARKOS label bypassed required guest")
	}
}
