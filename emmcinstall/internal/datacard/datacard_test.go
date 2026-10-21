package datacard

import (
	"bytes"
	"fmt"
	"math/rand"
	"os"
	"path/filepath"
	"testing"

	"andr36oid/emmcinstall/internal/emmc"
)

// A card laid out like andr36oid's: the GUID of one of its partitions as text.
func card(t *testing.T, seed int64) (*emmc.GPT, []string) {
	t.Helper()
	parts := []emmc.Part{
		{Num: 1, Name: "BOOT", Type: emmc.TypeBasicData, First: 2048, Last: 4095},
		{Num: 2, Name: "system", Type: emmc.TypeLinux, First: 4096, Last: 8191},
		{Num: 3, Name: "userdata", Type: emmc.TypeLinux, First: 8192, Last: 16383},
		{Num: 4, Name: "EASYROMS", Type: emmc.TypeBasicData, First: 16384, Last: 32767},
	}
	g, err := emmc.BuildGPT(40000, parts, rand.New(rand.NewSource(seed)))
	if err != nil {
		t.Fatal(err)
	}
	var guids []string
	for i := range parts {
		e := g.Head[2*512+i*128:]
		u := e[16:32]
		guids = append(guids, fmt.Sprintf("%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
			u[3], u[2], u[1], u[0], u[5], u[4], u[7], u[6], u[8], u[9], u[10], u[11], u[12], u[13], u[14], u[15]))
	}
	return g, guids
}

func TestPartWithGUID(t *testing.T) {
	g, guids := card(t, 1)
	for i, s := range guids {
		want, err := GUIDBytes(s)
		if err != nil {
			t.Fatal(err)
		}
		n, err := PartWithGUID(bytes.NewReader(g.Head), want)
		if err != nil || n != i+1 {
			t.Fatalf("%s: got %d %v, want %d", s, n, err, i+1)
		}
	}
	// upper case works too, another card's GUID doesn't match
	up, _ := GUIDBytes("  " + string(bytes.ToUpper([]byte(guids[2]))) + "\n")
	if n, _ := PartWithGUID(bytes.NewReader(g.Head), up); n != 3 {
		t.Fatalf("upper case: %d", n)
	}
	_, other := card(t, 2)
	o, _ := GUIDBytes(other[2])
	if n, err := PartWithGUID(bytes.NewReader(g.Head), o); n != 0 || err != nil {
		t.Fatalf("other card matched: %d %v", n, err)
	}
	// no GPT (blank or MBR card): no match, no error
	if n, err := PartWithGUID(bytes.NewReader(make([]byte, 34*512)), o); n != 0 || err != nil {
		t.Fatalf("blank: %d %v", n, err)
	}
	// a damaged table is an error, never a match
	bad := append([]byte(nil), g.Head...)
	bad[2*512+2*128+20] ^= 0xff
	if n, err := PartWithGUID(bytes.NewReader(bad), up); n != 0 || err == nil {
		t.Fatalf("damaged: %d %v", n, err)
	}
	for _, s := range []string{"", "xyz", "0123456789abcdef0123456789abcdef"} {
		if _, err := GUIDBytes(s); err == nil {
			t.Fatalf("%q accepted", s)
		}
	}
}

func TestFindAndLink(t *testing.T) {
	dir := t.TempDir()
	sys, dev := filepath.Join(dir, "sys"), filepath.Join(dir, "dev")
	os.MkdirAll(dev, 0755)
	g, guids := card(t, 3)
	other, _ := card(t, 4)
	// the eMMC (mmcblk2) has another table; the data card is in slot 2 (mmcblk1);
	// slot 1 (mmcblk0) is empty: no node
	for _, n := range []string{"mmcblk0", "mmcblk1", "mmcblk2", "mmcblk2boot0", "zram0"} {
		os.MkdirAll(filepath.Join(sys, n), 0755)
	}
	os.WriteFile(filepath.Join(dev, "mmcblk1"), g.Head, 0644)
	os.WriteFile(filepath.Join(dev, "mmcblk2"), other.Head, 0644)
	os.WriteFile(filepath.Join(dev, "mmcblk2boot0"), g.Head, 0644) // never looked at
	want, _ := GUIDBytes(guids[2])
	got, err := Find(sys, dev, want)
	if err != nil || got != "" {
		t.Fatalf("found before its node is there: %q %v", got, err)
	}
	os.WriteFile(filepath.Join(dev, "mmcblk1p3"), nil, 0644)
	got, err = Find(sys, dev, want)
	if err != nil || got != filepath.Join(dev, "mmcblk1p3") {
		t.Fatalf("got %q %v", got, err)
	}
	link := filepath.Join(dev, "by-name", "a36data")
	for i := 0; i < 2; i++ {
		if err := Link(got, link); err != nil {
			t.Fatal(err)
		}
	}
	if to, _ := os.Readlink(link); to != got {
		t.Fatalf("link to %q", to)
	}
}
