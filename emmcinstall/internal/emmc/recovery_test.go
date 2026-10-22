package emmc

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRecoveryRestore(t *testing.T) {
	f := newFake(t)
	internal := filepath.Join(f.dir, "media", "0")
	must(t, os.MkdirAll(internal, 0755))
	dir, err := f.cfg.Backup(f.progress(), internal)
	must(t, err)
	c := f.cfg.ForRecovery()
	if c.StateDir != "/tmp/andr36oid-emmcinstall" || c.StateDir == f.cfg.StateDir {
		t.Fatal("recovery depends on Android's records")
	}
	c.StateDir = filepath.Join(f.dir, "recovery")
	writeFile(t, f.cfg.statePath(testCID), "broken Android records")
	// Recovery has no Android battery service and need not have battery sysfs.
	must(t, os.RemoveAll(f.cfg.PowerDir))
	var events bytes.Buffer
	p, err := NewProgress("", "restore")
	must(t, err)
	p.AddWriter(&events)

	in, err := c.InspectRecovery([]string{filepath.Join(f.dir, "missing"), internal})
	must(t, err)
	if in.Backup != dir || in.Emmc.CID != testCID || in.Problem != "" || len(f.writes) != 0 {
		t.Fatalf("inspection: %+v, writes %v", in, f.writes)
	}

	// A backup copied to EASYROMS is found without the old state file's path.
	card := filepath.Join(f.dir, "EASYROMS")
	moved := filepath.Join(card, "eMMC backup", "console", filepath.Base(dir))
	must(t, os.MkdirAll(filepath.Dir(moved), 0755))
	must(t, os.Rename(dir, moved))
	in, err = c.InspectRecovery([]string{internal, card})
	must(t, err)
	if in.Backup != moved {
		t.Fatalf("moved backup: %+v", in)
	}

	refused := func(want string) {
		t.Helper()
		mustFail(t, c.RestoreRecovery(p, testCID, moved), want)
		if len(f.writes) != 0 || !f.locked {
			t.Fatal("refused restore wrote or unlocked the eMMC")
		}
	}
	f.emmcStart()
	refused("SD card")
	f.sdStart()
	writeFile(t, c.Mountinfo, "20 1 179:17 / /mnt/emmc ro - vfat /dev/block/mmcblk2p1 ro\n")
	in, err = c.InspectRecovery([]string{card})
	must(t, err)
	if !strings.Contains(in.Problem, "in use") {
		t.Fatalf("mounted eMMC offered: %+v", in)
	}
	refused("in use")
	writeFile(t, c.Mountinfo, "")
	mustFail(t, c.RestoreRecovery(p, strings.Repeat("2", 32), moved), "another eMMC")
	writeFile(t, filepath.Join(c.EmmcSys, "device/cid"), strings.Repeat("2", 32))
	in, err = c.InspectRecovery([]string{card})
	must(t, err)
	if in.Backup != "" {
		t.Fatal("found another chip's backup")
	}
	mustFail(t, c.RestoreRecovery(p, strings.Repeat("2", 32), moved), "another eMMC")
	writeFile(t, filepath.Join(c.EmmcSys, "device/cid"), testCID)

	chunk := filepath.Join(moved, "user.001")
	b, err := os.ReadFile(chunk)
	must(t, err)
	b[12345] ^= 1
	must(t, os.WriteFile(chunk, b, 0644))
	refused("damaged")
	b[12345] ^= 1
	must(t, os.WriteFile(chunk, b, 0644))
	if len(f.writes) != 0 || !f.locked {
		t.Fatal("a safety check wrote or unlocked the eMMC")
	}

	// Simulate a broken install without going through Android's installer.
	must(t, os.WriteFile(c.EmmcDev, make([]byte, len(f.stock)), 0644))
	must(t, c.RestoreRecovery(p, testCID, moved))
	if !bytes.Equal(f.emmc(0, mib(64)), f.stock) || !f.locked {
		t.Fatal("recovery did not restore and relock the stock eMMC")
	}
	st, err := c.LoadState()
	must(t, err)
	if !st.Restored || st.Running != "" {
		t.Fatalf("restore state: %+v", st)
	}
	seenRun, seenOK := false, false
	dec := json.NewDecoder(&events)
	for dec.More() {
		var e Event
		must(t, dec.Decode(&e))
		seenRun = seenRun || e.State == "run" && e.Total > 0
		seenOK = seenOK || e.Op == "Check the eMMC against the backup" && e.State == "ok"
	}
	if !seenRun || !seenOK {
		t.Fatal("recovery did not stream progress and the final readback")
	}
}
