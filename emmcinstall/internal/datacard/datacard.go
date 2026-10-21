// Package datacard finds the SD card an eMMC install keeps Android's data on: the
// partition whose GPT unique GUID boot.ini passes as androidboot.datacard. The card
// can be in either slot, so it is looked for on every mmcblk disk.
package datacard

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"hash/crc32"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

const sector = 512

// GUIDBytes turns "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" (either case) into the
// GPT's mixed-endian bytes.
func GUIDBytes(s string) ([16]byte, error) {
	var g [16]byte
	h := strings.ReplaceAll(strings.TrimSpace(s), "-", "")
	if len(h) != 32 || len(strings.TrimSpace(s)) != 36 {
		return g, fmt.Errorf("bad GUID %q", s)
	}
	var b [16]byte
	for i := range b {
		var v byte
		if _, err := fmt.Sscanf(h[2*i:2*i+2], "%02x", &v); err != nil {
			return g, fmt.Errorf("bad GUID %q", s)
		}
		b[i] = v
	}
	g[0], g[1], g[2], g[3] = b[3], b[2], b[1], b[0]
	g[4], g[5] = b[5], b[4]
	g[6], g[7] = b[7], b[6]
	copy(g[8:], b[8:])
	return g, nil
}

// PartWithGUID returns the 1-based number of the partition whose unique GUID is
// guid, from a disk's first 34 sectors; 0 if there is none. A disk without a valid
// GPT (no card, a card with MBR) has none.
func PartWithGUID(r io.ReaderAt, guid [16]byte) (int, error) {
	head := make([]byte, 34*sector)
	if _, err := r.ReadAt(head, 0); err != nil {
		return 0, err
	}
	h := head[sector : 2*sector]
	if string(h[:8]) != "EFI PART" {
		return 0, nil
	}
	hs := binary.LittleEndian.Uint32(h[12:])
	if hs < 92 || hs > sector {
		return 0, nil
	}
	c := make([]byte, hs)
	copy(c, h[:hs])
	binary.LittleEndian.PutUint32(c[16:], 0)
	if crc32.ChecksumIEEE(c) != binary.LittleEndian.Uint32(h[16:]) {
		return 0, errors.New("GPT header checksum wrong")
	}
	entLBA := binary.LittleEndian.Uint64(h[72:])
	num := binary.LittleEndian.Uint32(h[80:])
	size := binary.LittleEndian.Uint32(h[84:])
	if entLBA != 2 || size != 128 || num == 0 || int(num)*int(size) > 32*sector {
		return 0, nil
	}
	entries := head[2*sector : 2*sector+int(num)*int(size)]
	if crc32.ChecksumIEEE(entries) != binary.LittleEndian.Uint32(h[88:]) {
		return 0, errors.New("GPT entries checksum wrong")
	}
	for i := 0; i < int(num); i++ {
		e := entries[i*int(size) : (i+1)*int(size)]
		if bytes.Equal(e[:16], make([]byte, 16)) {
			continue
		}
		if bytes.Equal(e[16:32], guid[:]) {
			return i + 1, nil
		}
	}
	return 0, nil
}

var diskName = regexp.MustCompile(`^mmcblk[0-9]+$`)

// Find looks at every mmcblk disk under sysBlock for the partition and returns its
// device under devBlock (/dev/block/mmcblk1p6), "" if no card has it.
func Find(sysBlock, devBlock string, guid [16]byte) (string, error) {
	disks, err := os.ReadDir(sysBlock)
	if err != nil {
		return "", err
	}
	for _, d := range disks {
		name := d.Name()
		if !diskName.MatchString(name) {
			continue // mmcblk2boot0, mmcblk2rpmb, loop, zram
		}
		f, err := os.Open(filepath.Join(devBlock, name))
		if err != nil {
			continue // a slot without a card has no node
		}
		n, err := PartWithGUID(f, guid)
		f.Close()
		if err != nil || n == 0 {
			continue
		}
		dev := filepath.Join(devBlock, fmt.Sprintf("%sp%d", name, n))
		if _, err := os.Stat(dev); err != nil {
			continue // its node isn't there yet: next look
		}
		return dev, nil
	}
	return "", nil
}

// Link points link (/dev/block/by-name/a36data) at dev, replacing what is there.
func Link(dev, link string) error {
	if err := os.MkdirAll(filepath.Dir(link), 0755); err != nil {
		return err
	}
	tmp := link + ".new"
	os.Remove(tmp)
	if err := os.Symlink(dev, tmp); err != nil {
		return err
	}
	return os.Rename(tmp, link)
}
