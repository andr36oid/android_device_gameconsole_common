package emmc

import (
	"os"
	"path/filepath"
	"testing"
)

// A new andr36oid card in a console whose eMMC another card installed: the
// wizard finds andr36oid there and takes it over, and then updates it.
func TestAdoptInstallFromAnotherCard(t *testing.T) {
	f := newDatacardFake(t)
	f.cfg.BackupGlob = filepath.Join(f.dir, "roms", "eMMC backup", "*", "stock-*")
	backup, err := f.cfg.Backup(f.progress(), filepath.Join(f.dir, "roms"))
	must(t, err)
	f.installFromStep1(t)
	plan := f.state().Plan
	eb, _ := Find(plan, "BOOT")
	emmcBoot := f.fsDir(f.cfg.EmmcDev, spanOf(eb))
	must(t, os.MkdirAll(emmcBoot, 0755))
	writeFile(t, filepath.Join(emmcBoot, "boot.ini-android"), "andr36oid\n")

	// the new card: no records of this eMMC
	must(t, os.RemoveAll(f.cfg.StateDir))
	in := f.cfg.Inspect()
	if in.Found == nil || in.Found.Backup != backup || in.Found.Datacard != "" {
		t.Fatalf("inspect found %+v", in.Found)
	}

	must(t, f.cfg.Adopt(f.progress()))
	st := f.state()
	if st.Step3 != "done" || st.Backup == nil || st.Backup.Dir != backup || !samePlan(st.Plan, plan) ||
		st.Datacard == nil || st.Datacard.Kind != "emmc" || st.Datacard.Stage != "done" {
		t.Fatalf("after adopt: %+v", st)
	}
	if in := f.cfg.Inspect(); in.Found != nil {
		t.Fatal("still offered to take over after taking over")
	}
	mustFail(t, f.cfg.Adopt(f.progress()), "already has records")
	must(t, f.cfg.UpdateEmmc(f.progress()))
}

// The stock system on the eMMC: nothing to take over.
func TestAdoptNothingOnStock(t *testing.T) {
	f := newDatacardFake(t)
	if in := f.cfg.Inspect(); in.Found != nil {
		t.Fatalf("found andr36oid on a stock eMMC: %+v", in.Found)
	}
	mustFail(t, f.cfg.Adopt(f.progress()), "no andr36oid")
}
