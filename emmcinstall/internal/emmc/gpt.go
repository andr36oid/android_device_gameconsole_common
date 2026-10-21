package emmc

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"hash/crc32"
	"io"
	"strings"
	"unicode/utf16"
)

// GPT with the usual 128 entries of 128 bytes: protective MBR in sector 0,
// header in 1, entries in 2-33, and the same backwards at the end of the disk.
const (
	gptEntries     = 128
	gptEntrySize   = 128
	gptTableSecs   = gptEntries * gptEntrySize / Sector // 32
	gptFirstUsable = 2 + gptTableSecs                   // 34
)

// Partition type GUIDs, as text.
const (
	TypeBasicData = "EBD0A0A2-B9E5-4433-87C0-68B6B72699C7" // FAT (BOOT)
	TypeLinux     = "0FC63DAF-8483-4772-8E79-3D69D8477DE4"
)

// Part is one partition: first and last sector, both included.
type Part struct {
	Num   int // 1-based, as Linux numbers them (mmcblk2p<Num>)
	Name  string
	Type  string
	First int64
	Last  int64
	// GUID is the partition's own GUID (PARTUUID), read from a disk; BuildGPT
	// makes new ones. Attr are the attribute bits.
	GUID string `json:",omitempty"`
	Attr uint64 `json:",omitempty"`
}

// PartUUID is the partition's GUID as the kernel prints PARTUUID: lower case.
func (p Part) PartUUID() string { return strings.ToLower(p.GUID) }

// Sectors is the partition's length.
func (p Part) Sectors() int64 { return p.Last - p.First + 1 }

// guidBytes turns "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX" into the GPT's
// mixed-endian bytes.
func guidBytes(s string) ([16]byte, error) {
	var g [16]byte
	var raw []byte
	for _, c := range s {
		if c == '-' {
			continue
		}
		var v byte
		switch {
		case c >= '0' && c <= '9':
			v = byte(c - '0')
		case c >= 'a' && c <= 'f':
			v = byte(c-'a') + 10
		case c >= 'A' && c <= 'F':
			v = byte(c-'A') + 10
		default:
			return g, fmt.Errorf("bad GUID %q", s)
		}
		raw = append(raw, v)
	}
	if len(raw) != 32 {
		return g, fmt.Errorf("bad GUID %q", s)
	}
	var b [16]byte
	for i := range b {
		b[i] = raw[2*i]<<4 | raw[2*i+1]
	}
	g[0], g[1], g[2], g[3] = b[3], b[2], b[1], b[0]
	g[4], g[5] = b[5], b[4]
	g[6], g[7] = b[7], b[6]
	copy(g[8:], b[8:])
	return g, nil
}

func guidString(g []byte) string {
	return fmt.Sprintf("%02X%02X%02X%02X-%02X%02X-%02X%02X-%02X%02X-%02X%02X%02X%02X%02X%02X",
		g[3], g[2], g[1], g[0], g[5], g[4], g[7], g[6],
		g[8], g[9], g[10], g[11], g[12], g[13], g[14], g[15])
}

// GPT is a disk's partition table as written: the first 34 sectors and the
// last 33.
type GPT struct {
	Head []byte // sectors 0..33
	Tail []byte // sectors disk-33 .. disk-1
}

// BuildGPT makes a GPT for a disk of diskSecs sectors. rnd gives the GUIDs.
// Each partition goes in entry Num; numbers may leave gaps (an andr36oid card
// has no p12 once AUTOAPK moved to p13), but come in rising order.
func BuildGPT(diskSecs int64, parts []Part, rnd io.Reader) (*GPT, error) {
	last := diskSecs - 1
	lastUsable := last - 1 - gptTableSecs
	if len(parts) > gptEntries {
		return nil, errors.New("too many partitions")
	}
	entries := make([]byte, gptEntries*gptEntrySize)
	for i, p := range parts {
		if p.Num < 1 || p.Num > gptEntries || (i > 0 && p.Num <= parts[i-1].Num) {
			return nil, fmt.Errorf("partition %s: number %d out of order", p.Name, p.Num)
		}
		if p.First < gptFirstUsable || p.Last > lastUsable || p.Last < p.First {
			return nil, fmt.Errorf("partition %s: sectors %d-%d outside %d-%d",
				p.Name, p.First, p.Last, gptFirstUsable, lastUsable)
		}
		for _, q := range parts[:i] {
			if p.First <= q.Last && q.First <= p.Last {
				return nil, fmt.Errorf("partitions %s and %s overlap", q.Name, p.Name)
			}
		}
		e := entries[(p.Num-1)*gptEntrySize:]
		t, err := guidBytes(p.Type)
		if err != nil {
			return nil, err
		}
		copy(e[0:16], t[:])
		if _, err := io.ReadFull(rnd, e[16:32]); err != nil {
			return nil, err
		}
		e[16+7] = e[16+7]&0x0f | 0x40 // version 4 GUID
		e[16+8] = e[16+8]&0x3f | 0x80
		binary.LittleEndian.PutUint64(e[32:], uint64(p.First))
		binary.LittleEndian.PutUint64(e[40:], uint64(p.Last))
		binary.LittleEndian.PutUint64(e[48:], p.Attr)
		name := utf16.Encode([]rune(p.Name))
		if len(name) > 36 {
			return nil, fmt.Errorf("partition name %q too long", p.Name)
		}
		for j, c := range name {
			binary.LittleEndian.PutUint16(e[56+2*j:], c)
		}
	}
	entCRC := crc32.ChecksumIEEE(entries)
	var diskGUID [16]byte
	if _, err := io.ReadFull(rnd, diskGUID[:]); err != nil {
		return nil, err
	}
	diskGUID[7] = diskGUID[7]&0x0f | 0x40
	diskGUID[8] = diskGUID[8]&0x3f | 0x80

	header := func(my, alt, entLBA int64) []byte {
		h := make([]byte, Sector)
		copy(h, "EFI PART")
		binary.LittleEndian.PutUint32(h[8:], 0x00010000)
		binary.LittleEndian.PutUint32(h[12:], 92)
		binary.LittleEndian.PutUint64(h[24:], uint64(my))
		binary.LittleEndian.PutUint64(h[32:], uint64(alt))
		binary.LittleEndian.PutUint64(h[40:], gptFirstUsable)
		binary.LittleEndian.PutUint64(h[48:], uint64(lastUsable))
		copy(h[56:72], diskGUID[:])
		binary.LittleEndian.PutUint64(h[72:], uint64(entLBA))
		binary.LittleEndian.PutUint32(h[80:], gptEntries)
		binary.LittleEndian.PutUint32(h[84:], gptEntrySize)
		binary.LittleEndian.PutUint32(h[88:], entCRC)
		binary.LittleEndian.PutUint32(h[16:], crc32.ChecksumIEEE(h[:92]))
		return h
	}

	mbr := make([]byte, Sector)
	pe := mbr[446:]
	pe[1], pe[2], pe[3] = 0x00, 0x02, 0x00 // CHS of sector 1
	pe[4] = 0xEE
	pe[5], pe[6], pe[7] = 0xFF, 0xFF, 0xFF
	binary.LittleEndian.PutUint32(pe[8:], 1)
	n := uint64(diskSecs - 1)
	if n > 0xFFFFFFFF {
		n = 0xFFFFFFFF
	}
	binary.LittleEndian.PutUint32(pe[12:], uint32(n))
	mbr[510], mbr[511] = 0x55, 0xAA

	head := bytes.Join([][]byte{mbr, header(1, last, 2), entries}, nil)
	tail := bytes.Join([][]byte{entries, header(last, 1, last-gptTableSecs)}, nil)
	return &GPT{Head: head, Tail: tail}, nil
}

// TailOffset is where GPT.Tail goes on a disk of diskSecs sectors, in bytes.
func TailOffset(diskSecs int64) int64 { return (diskSecs - 1 - gptTableSecs) * Sector }

// ReadGPT reads and checks a disk's primary GPT (header and entry CRCs).
func ReadGPT(d *Disk) ([]Part, error) {
	head, err := d.ReadBytes(0, gptFirstUsable*Sector)
	if err != nil {
		return nil, err
	}
	return parseGPT(head, d.Sectors())
}

func parseGPT(head []byte, diskSecs int64) ([]Part, error) {
	h := head[Sector : 2*Sector]
	if string(h[:8]) != "EFI PART" {
		return nil, errors.New("no GPT")
	}
	hs := binary.LittleEndian.Uint32(h[12:])
	if hs < 92 || hs > Sector {
		return nil, errors.New("GPT header size wrong")
	}
	c := make([]byte, hs)
	copy(c, h[:hs])
	binary.LittleEndian.PutUint32(c[16:], 0)
	if crc32.ChecksumIEEE(c) != binary.LittleEndian.Uint32(h[16:]) {
		return nil, errors.New("GPT header checksum wrong")
	}
	entLBA := int64(binary.LittleEndian.Uint64(h[72:]))
	num := binary.LittleEndian.Uint32(h[80:])
	size := binary.LittleEndian.Uint32(h[84:])
	if entLBA != 2 || num != gptEntries || size != gptEntrySize {
		return nil, fmt.Errorf("GPT layout not the usual one (entries at %d, %d of %d bytes)",
			entLBA, num, size)
	}
	entries := head[2*Sector : (2+gptTableSecs)*Sector]
	if crc32.ChecksumIEEE(entries) != binary.LittleEndian.Uint32(h[88:]) {
		return nil, errors.New("GPT entries checksum wrong")
	}
	var parts []Part
	for i := 0; i < gptEntries; i++ {
		e := entries[i*gptEntrySize : (i+1)*gptEntrySize]
		if bytes.Equal(e[:16], make([]byte, 16)) {
			continue
		}
		var name []uint16
		for j := 56; j+1 < gptEntrySize; j += 2 {
			v := binary.LittleEndian.Uint16(e[j:])
			if v == 0 {
				break
			}
			name = append(name, v)
		}
		p := Part{
			Num:   i + 1,
			Name:  string(utf16.Decode(name)),
			Type:  guidString(e[:16]),
			First: int64(binary.LittleEndian.Uint64(e[32:])),
			Last:  int64(binary.LittleEndian.Uint64(e[40:])),
			GUID:  guidString(e[16:32]),
			Attr:  binary.LittleEndian.Uint64(e[48:]),
		}
		if p.Last < p.First || p.Last >= diskSecs {
			return nil, fmt.Errorf("partition %d (%s) outside the disk", p.Num, p.Name)
		}
		parts = append(parts, p)
	}
	return parts, nil
}

// Find returns the partition named name.
func Find(parts []Part, name string) (Part, bool) {
	for _, p := range parts {
		if p.Name == name {
			return p, true
		}
	}
	return Part{}, false
}
