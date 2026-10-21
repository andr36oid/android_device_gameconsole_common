package emmc

import (
	"os"
	"path/filepath"
	"testing"
)

// The battery is checked once, at a run's start: a run that chains steps
// (datacard-return) must not stop once it has written because the battery went
// down meanwhile.
func TestPowerCheckedOnce(t *testing.T) {
	dir := t.TempDir()
	bat := filepath.Join(dir, "battery")
	if err := os.MkdirAll(bat, 0o755); err != nil {
		t.Fatal(err)
	}
	level := func(s string) {
		if err := os.WriteFile(filepath.Join(bat, "capacity"), []byte(s+"\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	os.WriteFile(filepath.Join(bat, "status"), []byte("Discharging\n"), 0o644)

	low := &Config{PowerDir: dir}
	level("40")
	if low.powerOK() == nil {
		t.Fatal("a run starting at 40% without a charger went ahead")
	}

	c := &Config{PowerDir: dir}
	level("61")
	if err := c.powerOK(); err != nil {
		t.Fatalf("start at 61%%: %v", err)
	}
	level("59")
	if err := c.powerOK(); err != nil {
		t.Fatalf("the same run stopped later at 59%%: %v", err)
	}
}
