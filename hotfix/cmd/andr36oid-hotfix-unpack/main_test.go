package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestCheckExfat(t *testing.T) {
	file := filepath.Join(t.TempDir(), "partition")
	for _, tc := range []struct {
		name  string
		flags byte
		bad   bool
	}{
		{"clean", 0, false}, {"active second FAT", 1, false},
		{"dirty", 2, true}, {"media error", 4, true}, {"both", 6, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var boot [512]byte
			copy(boot[3:11], "EXFAT   ")
			boot[106], boot[510], boot[511] = tc.flags, 0x55, 0xaa
			if err := os.WriteFile(file, boot[:], 0600); err != nil {
				t.Fatal(err)
			}
			if err := checkExfat(file); (err != nil) != tc.bad {
				t.Fatal(err)
			}
			got, _ := os.ReadFile(file)
			if string(got) != string(boot[:]) {
				t.Fatal("wrote to the partition")
			}
		})
	}
	for _, b := range [][]byte{nil, make([]byte, 512), []byte("EXFAT   ")} {
		if err := os.WriteFile(file, b, 0600); err != nil {
			t.Fatal(err)
		}
		if checkExfat(file) == nil {
			t.Fatal("accepted a short or invalid boot sector")
		}
	}
	if checkExfat(file+".missing") == nil {
		t.Fatal("accepted a missing partition")
	}
}
