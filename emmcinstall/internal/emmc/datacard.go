package emmc

import (
	"encoding/binary"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// The data card: andr36oid on the eMMC, Android's data (userdata) on an SD
// card. Chosen after the backup (DatacardChoose), made after step 3:
//
//	original  the SD card andr36oid runs from keeps its userdata. Finish
//	          writes datacard.txt to the eMMC's BOOT, makes the card unable to
//	          start (boot.ini renamed, loader blanked) and leaves its system
//	          partitions to the eMMC system's next start (Confirm), as they
//	          are in use until the console is off.
//	new       a card in slot 2 gets the SD card's table and BOOT, EASYROMS
//	          (copied for A, fresh for B and C) and AUTOAPK if wanted
//	          (DatacardPrepare). userdata is copied (A, B) at one start from
//	          the eMMC, where neither card's userdata is mounted
//	          (DatacardCopy); C leaves it blank for Android to format.
//	          Finish then writes datacard.txt; the old card stays as it is
//	          and has to come out.
//
// The eMMC's boot.ini (the boot side) reads datacard.txt and has Android
// wait for the card with that userdata PARTUUID.
const (
	DatacardFile   = "datacard.txt"           // eMMC BOOT: datacard=<PARTUUID>
	DatacardMarker = "andr36oid-datacard.txt" // the data card's BOOT
	DatacardCopy   = "datacard-copy.txt"      // SD BOOT: the copy for the eMMC start
)

// DatacardMarkerText explains the card to whoever looks at its BOOT.
const DatacardMarkerText = `This is the data card of andr36oid installed on the console's built-in eMMC.

It holds Android's data: apps, settings and files. It can't start the console
by itself: the console starts from the eMMC and uses this card for its data.
Leave it in the console (either slot).

Updating this card with the andr36oid SD card flasher makes it start the
console again, from the card instead of the eMMC. andr36oid then offers to
update the eMMC to the card's version and make the card a data card again.
`

// Datacard is the choice and how far it got.
type Datacard struct {
	Kind    string `json:"kind"`             // emmc, original, new
	Option  string `json:"option,omitempty"` // new: A (all), B (Android data), C (fresh)
	AutoAPK bool   `json:"autoapk,omitempty"`
	// Stage: "" chosen; prepared (new: card made, copy set up); blank
	// (original: finished, system partitions blanked at the next eMMC
	// start); done.
	Stage string `json:"stage,omitempty"`
	UUID  string `json:"uuid,omitempty"` // the data card's userdata PARTUUID
}

// renamed on a data card's BOOT, so U-Boot doesn't start from it
var bootScripts = []string{"boot.ini", "boot.scr"}

// partitions a data card keeps; everything else is blanked
var dataParts = map[string]bool{"BOOT": true, "userdata": true, "EASYROMS": true, "AUTOAPK": true}

// blankHead is how much of each blanked partition is zeroed: every file
// system's superblock is in it, and a whole system partition would take
// minutes.
const blankHead = 16 << 20 / Sector

// a new card's EASYROMS: at least this, and this much room over the copy
// (sectors; the tests make them smaller)
var (
	easyMin   int64 = 256 << 20 / Sector
	easySlack int64 = 256 << 20 / Sector
)

func spanOf(p Part) span { return span{p.First, p.Last + 1} }

func (c *Config) cmdlineValue(key string) string {
	for _, f := range strings.Fields(c.cmdline()) {
		if v, ok := strings.CutPrefix(f, key+"="); ok {
			return v
		}
	}
	return ""
}

// partOf is the kernel's name for partition p of the disk in sys.
func partOf(sys string, p Part) string { return filepath.Base(sys) + "p" + strconv.Itoa(p.Num) }

// Slot 2

// Slot2 is the card in the second slot.
type Slot2 struct {
	Present bool   `json:"present"`
	Sectors int64  `json:"sectors,omitempty"`
	Name    string `json:"name,omitempty"`
}

func (c *Config) slot2() Slot2 {
	if t, _ := readTrim(filepath.Join(c.SD2Sys, "device/type")); t != "SD" {
		return Slot2{}
	}
	s, _ := readTrim(filepath.Join(c.SD2Sys, "size"))
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil || n <= 0 {
		return Slot2{}
	}
	name, _ := readTrim(filepath.Join(c.SD2Sys, "device/name"))
	return Slot2{Present: true, Sectors: n, Name: name}
}

// Options

// DatacardOptions is what the choice page shows: the SD card andr36oid runs
// from, the card in slot 2 and what each choice needs there.
type DatacardOptions struct {
	UUID         string           `json:"uuid"`
	Userdata     int64            `json:"userdata_bytes"`
	UserdataUsed int64            `json:"userdata_used"`
	Easyroms     int64            `json:"easyroms_bytes"`
	EasyromsUsed int64            `json:"easyroms_used"`
	AutoAPK      int64            `json:"autoapk_bytes"` // 0: gone (the apps were installed)
	Slot2        Slot2            `json:"slot2"`
	Need         map[string]int64 `json:"need"` // bytes a new card needs: A, B, C (B, C without AUTOAPK)
	Chosen       *Datacard        `json:"chosen,omitempty"`
}

func (c *Config) sdParts() ([]Part, error) {
	sd, err := OpenDisk(c.SDDev, false, c.Direct)
	if err != nil {
		return nil, err
	}
	defer sd.Close()
	parts, err := ReadGPT(sd)
	if err != nil {
		return nil, fmt.Errorf("SD card: %w", err)
	}
	return parts, nil
}

// withEasyroms runs fn with the SD card's EASYROMS: where vold has it, else
// mounted read-only here.
func (c *Config) withEasyroms(parts []Part, fn func(dir string) error) error {
	ez, ok := Find(parts, "EASYROMS")
	if !ok {
		return errors.New("the SD card has no EASYROMS")
	}
	m, err := c.mounts(c.SDSys, partOf(c.SDSys, ez))
	if err != nil {
		return err
	}
	for _, x := range m {
		if strings.HasPrefix(x.Point, "/mnt/media_rw/") || len(m) == 1 {
			return fn(x.Point)
		}
	}
	if len(m) > 0 {
		return fn(m[0].Point)
	}
	return c.fsops().mount(c, c.SDDev, spanOf(ez), "exfat", false, fn)
}

// newCardLayout is a new data card's table: the SD card's partitions up to
// and with userdata, where they are; AUTOAPK (if kept) right after; EASYROMS
// over the rest, at least easyNeed sectors.
func newCardLayout(sd []Part, cardSecs int64, autoapk bool, easyNeed int64) ([]Part, error) {
	ud, ok := Find(sd, "userdata")
	if !ok {
		return nil, errors.New("the SD card has no userdata partition")
	}
	var out []Part
	for _, p := range sd {
		if p.Last < ud.First && !dataParts[p.Name] || p.Name == "BOOT" {
			out = append(out, Part{Num: p.Num, Name: p.Name, Type: p.Type, First: p.First, Last: p.Last, Attr: p.Attr})
		}
	}
	out = append(out, Part{Num: ud.Num, Name: ud.Name, Type: ud.Type, First: ud.First, Last: ud.Last, Attr: ud.Attr})
	next := roundUp(ud.Last + 1)
	if autoapk {
		aa, ok := Find(sd, "AUTOAPK")
		if !ok {
			return nil, errors.New("AUTOAPK isn't on the SD card any more")
		}
		out = append(out, Part{Num: aa.Num, Name: aa.Name, Type: aa.Type, First: next, Last: next + aa.Sectors() - 1, Attr: aa.Attr})
		next = roundUp(next + aa.Sectors())
	}
	ezType, ezNum := TypeBasicData, 7
	if ez, ok := Find(sd, "EASYROMS"); ok {
		ezType, ezNum = ez.Type, ez.Num
	}
	last := cardSecs - 2 - gptTableSecs
	if last-next+1 < easyNeed {
		return nil, fmt.Errorf("the card in slot 2 is too small: it needs %d MiB, it has %d MiB",
			(next+easyNeed+gptTableSecs+1)*Sector>>20, cardSecs*Sector>>20)
	}
	out = append(out, Part{Num: ezNum, Name: "EASYROMS", Type: ezType, First: next, Last: last})
	sort.Slice(out, func(i, j int) bool { return out[i].Num < out[j].Num })
	for i := 1; i < len(out); i++ {
		if out[i].Num == out[i-1].Num {
			return nil, fmt.Errorf("partitions %s and %s are both number %d", out[i-1].Name, out[i].Name, out[i].Num)
		}
	}
	return out, nil
}

// cardNeed is the size a new card needs, in bytes.
func cardNeed(sd []Part, autoapk bool, easyNeed int64) int64 {
	ud, _ := Find(sd, "userdata")
	next := roundUp(ud.Last + 1)
	if aa, ok := Find(sd, "AUTOAPK"); ok && autoapk {
		next = roundUp(next + aa.Sectors())
	}
	return (next + easyNeed + gptTableSecs + 1) * Sector
}

func (c *Config) DatacardOptions() (*DatacardOptions, error) {
	if err := c.needSD(); err != nil {
		return nil, err
	}
	parts, err := c.sdParts()
	if err != nil {
		return nil, err
	}
	o := &DatacardOptions{Slot2: c.slot2(), Need: map[string]int64{}}
	if st, err := c.LoadState(); err == nil {
		o.Chosen = st.Datacard
	}
	ud, ok := Find(parts, "userdata")
	if !ok {
		// only the eMMC can take the data (the page still shows)
		return o, nil
	}
	o.UUID, o.Userdata = ud.PartUUID(), ud.Sectors()*Sector
	o.UserdataUsed, _ = c.fsops().used(c.DataDir)
	if aa, ok := Find(parts, "AUTOAPK"); ok {
		o.AutoAPK = aa.Sectors() * Sector
	}
	if ez, ok := Find(parts, "EASYROMS"); ok {
		o.Easyroms = ez.Sectors() * Sector
		if err := c.withEasyroms(parts, func(dir string) error {
			o.EasyromsUsed, err = c.fsops().used(dir)
			return err
		}); err != nil {
			o.EasyromsUsed = o.Easyroms // can't tell: all of it
		}
	}
	o.Need["A"] = cardNeed(parts, true, o.EasyromsUsed/Sector+easySlack)
	o.Need["B"] = cardNeed(parts, false, easyMin)
	o.Need["C"] = o.Need["B"]
	return o, nil
}

// Choice

// DatacardChoose records where Android keeps its data: kind emmc, original
// or new (option A, B or C, autoapk for B and C). Writes nothing else.
func (c *Config) DatacardChoose(p *Progress, kind, option string, autoapk bool) error {
	if err := c.needSD(); err != nil {
		return err
	}
	info, err := c.Emmc()
	if err != nil {
		return err
	}
	st, err := c.LoadState()
	if err != nil {
		return err
	}
	if st.CID != "" && st.CID != info.CID {
		return fmt.Errorf("the installer's records are for another eMMC (CID %s, this one %s)", st.CID, info.CID)
	}
	if st.Backup == nil {
		return errors.New("make the backup of the stock eMMC first")
	}
	if st.Datacard != nil && st.Datacard.Stage != "" {
		return errors.New("the data card is already being made")
	}
	d := &Datacard{Kind: kind}
	switch kind {
	case "emmc":
	case "original":
		parts, err := c.sdParts()
		if err != nil {
			return err
		}
		if _, ok := Find(parts, "userdata"); !ok {
			return errors.New("the SD card has no userdata partition")
		}
	case "new":
		o, err := c.DatacardOptions()
		if err != nil {
			return err
		}
		if option != "A" && option != "B" && option != "C" {
			return fmt.Errorf("option %q: A, B or C", option)
		}
		if !o.Slot2.Present {
			return errors.New("no card in slot 2")
		}
		if o.UUID == "" {
			return errors.New("the SD card has no userdata partition")
		}
		d.Option = option
		d.AutoAPK = o.AutoAPK > 0 && (autoapk || option == "A")
		need := o.Need[option]
		if d.AutoAPK && option != "A" {
			need += o.AutoAPK
		}
		if have := o.Slot2.Sectors * Sector; have < need {
			return fmt.Errorf("the card in slot 2 is too small: it needs %d MiB, it has %d MiB", need>>20, have>>20)
		}
	default:
		return fmt.Errorf("%q: emmc, original or new", kind)
	}
	st.Datacard = d
	p.Logf("Android's data: %s %s (AUTOAPK %v)", kind, d.Option, d.AutoAPK)
	return c.Save(st)
}

// The new card

// DatacardPrepare makes the card in slot 2 a data card, after step 3: the
// SD card's table with new GUIDs, its BOOT (unable to start), EASYROMS and
// AUTOAPK as chosen, every other partition and the loader area blanked.
// userdata is copied at one start from the eMMC (DatacardCopy), set up here;
// run again, it only sets that up again.
func (c *Config) DatacardPrepare(p *Progress) error {
	r, err := c.begin(p, "datacard-prepare")
	if err != nil {
		return err
	}
	d := r.st.Datacard
	if d == nil || d.Kind != "new" {
		return errors.New("no new data card was chosen")
	}
	if r.st.Step3 != "done" {
		return errors.New("andr36oid isn't on the eMMC yet (step 3)")
	}
	if d.Stage == "done" {
		return errors.New("the data card is made already")
	}
	sdParts, err := c.sdParts()
	if err != nil {
		return err
	}
	src, _ := Find(sdParts, "userdata")
	slot := c.slot2()
	if !slot.Present {
		return errors.New("no card in slot 2")
	}

	if d.Stage == "prepared" {
		if card, err := OpenDisk(c.SD2Dev, false, c.Direct); err == nil {
			parts, err := ReadGPT(card)
			card.Close()
			if ud, ok := Find(parts, "userdata"); err == nil && ok && ud.PartUUID() == d.UUID {
				if d.Option == "C" {
					p.Logf("the card in slot 2 is made already")
					return nil
				}
				p.Logf("the card in slot 2 is made already: only the copy is set up again")
				return c.armCopy(p, d, src)
			}
		}
		p.Logf("the card in slot 2 isn't the one made before: making it again")
	}

	var easyUsed, easyFiles int64
	if d.Option == "A" {
		if err := c.withEasyroms(sdParts, func(dir string) error {
			if easyUsed, err = c.fsops().used(dir); err != nil {
				return err
			}
			easyFiles, err = treeBytes(dir)
			return err
		}); err != nil {
			return fmt.Errorf("the SD card's EASYROMS: %w", err)
		}
	}
	need := easyMin
	if d.Option == "A" {
		need = easyUsed/Sector + easySlack
	}
	layout, err := newCardLayout(sdParts, slot.Sectors, d.AutoAPK, need)
	if err != nil {
		return err
	}
	boot, _ := Find(sdParts, "BOOT")
	m, err := c.mounts(c.SDSys, partOf(c.SDSys, boot))
	if err != nil {
		return err
	}
	for _, x := range m {
		if x.RW {
			return fmt.Errorf("the SD card's BOOT is in use (mounted at %s), try again in a moment", x.Point)
		}
	}
	if err := c.releaseSlot2(p); err != nil {
		return err
	}

	if err := r.mark("datacard-prepare"); err != nil {
		return err
	}
	if err := c.makeCard(p, r.st, sdParts, layout, slot, easyFiles); err != nil {
		return r.stop(err)
	}
	if d.Option == "C" {
		r.st.Running = ""
		p.Logf("data card made; Android sets up its data on the first start from the eMMC")
		return c.Save(r.st)
	}
	r.st.Running = ""
	if err := c.Save(r.st); err != nil {
		return err
	}
	return c.armCopy(p, d, src)
}

// releaseSlot2 has vold unmount the card in slot 2 (it is about to be
// erased) and checks nothing of it is mounted any more.
func (c *Config) releaseSlot2(p *Progress) error {
	m, err := c.mounts(c.SD2Sys, "")
	if err != nil {
		return err
	}
	done := map[string]bool{}
	for _, x := range m {
		if done[x.Dev] {
			continue
		}
		done[x.Dev] = true
		sys := filepath.Join(c.SD2Sys, x.Dev)
		if x.Dev == filepath.Base(c.SD2Sys) {
			sys = c.SD2Sys
		}
		mm, err := readTrim(filepath.Join(sys, "dev"))
		if err != nil {
			return err
		}
		if err := c.fsops().unmountVol(c, mm); err != nil {
			p.Logf("%s: %v", x.Dev, err)
		}
	}
	if m, err = c.mounts(c.SD2Sys, ""); err != nil {
		return err
	}
	if len(m) > 0 {
		return fmt.Errorf("the card in slot 2 is in use: %s is mounted at %s (eject it in Settings > Storage first)",
			m[0].Dev, m[0].Point)
	}
	return nil
}

// makeCard writes the new data card; st.Datacard gets its userdata PARTUUID.
func (c *Config) makeCard(p *Progress, st *State, sdParts, layout []Part, slot Slot2, easyFiles int64) error {
	d := st.Datacard
	card, err := OpenDisk(c.SD2Dev, true, c.Direct)
	if err != nil {
		return err
	}
	defer card.Close()
	if card.Sectors() != slot.Sectors {
		return fmt.Errorf("the card in slot 2 is %d sectors, sysfs says %d", card.Sectors(), slot.Sectors)
	}
	sd, err := OpenDisk(c.SDDev, false, c.Direct)
	if err != nil {
		return err
	}
	defer sd.Close()

	// the table, the new card's old one at the end first
	if err := zeroOn(p, "the old partition table", "new card", card, span{card.Sectors() - 1 - gptTableSecs, card.Sectors()}); err != nil {
		return err
	}
	g, err := BuildGPT(card.Sectors(), layout, c.random())
	if err != nil {
		return err
	}
	if err := card.WriteBytes(g.Tail, TailOffset(card.Sectors())); err != nil {
		return err
	}
	if err := card.WriteBytes(g.Head, 0); err != nil {
		return err
	}
	if err := card.Sync(); err != nil {
		return err
	}
	parts, err := ReadGPT(card)
	if err != nil || !samePlan(parts, layout) {
		return fmt.Errorf("the new card's partition table doesn't read back as written (%v)", err)
	}
	ud, _ := Find(parts, "userdata")
	d.UUID = ud.PartUUID()
	p.Logf("new card: partition table written, userdata PARTUUID %s", d.UUID)

	// what can't start or be mounted from it
	if err := zeroOn(p, "the loader area", "new card", card, spanLoader); err != nil {
		return err
	}
	for _, x := range parts {
		if dataParts[x.Name] || x.Last < LoaderEnd {
			continue
		}
		s := spanOf(x)
		if s.hi-s.lo > blankHead {
			s.hi = s.lo + blankHead
		}
		if err := zeroOn(p, x.Name, "new card", card, s); err != nil {
			return err
		}
	}
	if err := zeroOn(p, "userdata's start", "new card", card, span{ud.First, ud.First + align}); err != nil {
		return err
	}

	// BOOT: the SD card's, unable to start
	from, _ := Find(sdParts, "BOOT")
	to, _ := Find(parts, "BOOT")
	if _, err := copySpanAs(p, "BOOT", sd, spanOf(from), "SD card", card, spanOf(to), "new card"); err != nil {
		return err
	}
	if err := card.Sync(); err != nil {
		return err
	}
	if err := c.fsops().mount(c, c.SD2Dev, spanOf(to), "vfat", true, func(dir string) error {
		for _, f := range []string{TestFile, DatacardCopy, DatacardFile} {
			if err := removeBootFile(dir, f); err != nil {
				return err
			}
		}
		return makeDatacardBoot(p, dir)
	}); err != nil {
		return fmt.Errorf("the new card's BOOT: %w", err)
	}

	// EASYROMS
	ez, _ := Find(parts, "EASYROMS")
	op := p.Start("Format EASYROMS", "new card", ez.First, ez.Last+1, 0)
	if err := c.fsops().mkfs(c, c.SD2Dev, spanOf(ez), "exfat", "EASYROMS"); err != nil {
		return op.Fail(err)
	}
	op.OK("", "exFAT")
	if d.Option == "A" {
		if err := c.withEasyroms(sdParts, func(src string) error {
			return c.fsops().mount(c, c.SD2Dev, spanOf(ez), "exfat", true, func(dst string) error {
				return copyTree(p, "EASYROMS", src, dst, easyFiles)
			})
		}); err != nil {
			return fmt.Errorf("EASYROMS: %w", err)
		}
	}

	// AUTOAPK: mounted read-only by the first start's setup, so a copy of
	// the partition is the same as its files
	if d.AutoAPK {
		from, _ := Find(sdParts, "AUTOAPK")
		to, _ := Find(parts, "AUTOAPK")
		if _, err := copySpanAs(p, "AUTOAPK", sd, spanOf(from), "SD card", card, spanOf(to), "new card"); err != nil {
			return err
		}
	}
	if err := card.Sync(); err != nil {
		return err
	}
	d.Stage = "prepared"
	return nil
}

// makeDatacardBoot renames the start scripts on a BOOT folder and puts the
// marker there.
func makeDatacardBoot(p *Progress, dir string) error {
	for _, f := range bootScripts {
		from := filepath.Join(dir, f)
		if _, err := os.Stat(from); err != nil {
			continue
		}
		if err := removeBootFile(dir, f+".datacard"); err != nil {
			return err
		}
		if err := os.Rename(from, from+".datacard"); err != nil {
			return err
		}
		p.Logf("BOOT: %s renamed to %s.datacard", f, f)
	}
	if err := writeBootFile(dir, DatacardMarker, DatacardMarkerText); err != nil {
		return err
	}
	return syncDir(dir)
}

// armCopy sets up the copy of userdata at the next start, from the eMMC.
func (c *Config) armCopy(p *Progress, d *Datacard, src Part) error {
	req := fmt.Sprintf("src=%s\ndst=%s\nsectors=%d\n", src.PartUUID(), d.UUID, src.Sectors())
	err := c.withSDBoot(true, func(dir string) error {
		if err := writeBootFile(dir, DatacardCopy, req); err != nil {
			return err
		}
		return writeTest(dir, "emmc_test=start\n")
	})
	if err != nil {
		return err
	}
	p.Logf("userdata is copied at the next start, from the eMMC")
	return nil
}

// DatacardCopy runs on andr36oid started from the eMMC, where neither card's
// userdata is mounted: the copy the SD card's BOOT asks for, block by block,
// checked on the new card and on the SD card, then marked done there.
func (c *Config) DatacardCopy(p *Progress) (string, error) {
	if c.BootedFrom() != "emmc" {
		return "", errors.New("the copy runs on andr36oid started from the eMMC")
	}
	if err := c.powerOK(); err != nil {
		return "", err
	}
	var f map[string]string
	if err := c.withSDBoot(false, func(dir string) error {
		b, err := os.ReadFile(filepath.Join(dir, DatacardCopy))
		if errors.Is(err, os.ErrNotExist) {
			return errors.New("no copy asked for")
		}
		f = parseTest(b)
		return err
	}); err != nil {
		return "", err
	}
	if f["copy"] == "done" {
		return "done", nil
	}
	n, _ := strconv.ParseInt(f["sectors"], 10, 64)
	sd, sp, sdSys, err := c.findPart(f["src"], false)
	if err != nil {
		return "", fmt.Errorf("the SD card's userdata: %w", err)
	}
	defer sd.Close()
	card, dp, cardSys, err := c.findPart(f["dst"], true)
	if err != nil {
		return "", fmt.Errorf("the new card's userdata: %w", err)
	}
	defer card.Close()
	if sd.Path == card.Path {
		return "", errors.New("both userdata partitions are on the same card")
	}
	if sp.Name != "userdata" || dp.Name != "userdata" || sp.Sectors() != n || dp.Sectors() != n {
		return "", fmt.Errorf("the partitions don't match the copy asked for (%s %d, %s %d, %d sectors)",
			sp.Name, sp.Sectors(), dp.Name, dp.Sectors(), n)
	}
	for _, x := range []struct {
		sys string
		p   Part
	}{{sdSys, sp}, {cardSys, dp}} {
		m, err := c.mounts(x.sys, partOf(x.sys, x.p))
		if err != nil {
			return "", err
		}
		if len(m) > 0 {
			return "", fmt.Errorf("userdata is in use (mounted at %s)", m[0].Point)
		}
	}
	sha, err := copySpanAs(p, "userdata", sd, spanOf(sp), "SD card", card, spanOf(dp), "new card")
	if err != nil {
		return "", err
	}
	if err := card.Sync(); err != nil {
		return "", err
	}
	if err := c.withSDBoot(true, func(dir string) error {
		return writeBootFile(dir, DatacardCopy,
			fmt.Sprintf("src=%s\ndst=%s\nsectors=%d\ncopy=done\nsha256=%s\n", f["src"], f["dst"], n, sha))
	}); err != nil {
		return "", err
	}
	p.Logf("userdata copied to the new card")
	return "copied", nil
}

// findPart looks for the partition with that PARTUUID on both card slots.
func (c *Config) findPart(uuid string, write bool) (*Disk, Part, string, error) {
	if uuid == "" {
		return nil, Part{}, "", errors.New("no PARTUUID")
	}
	for _, x := range [][2]string{{c.SDDev, c.SDSys}, {c.SD2Dev, c.SD2Sys}} {
		d, err := OpenDisk(x[0], write, c.Direct)
		if err != nil {
			continue
		}
		parts, err := ReadGPT(d)
		if err == nil {
			for _, p := range parts {
				if p.PartUUID() == strings.ToLower(uuid) {
					return d, p, x[1], nil
				}
			}
		}
		d.Close()
	}
	return nil, Part{}, "", fmt.Errorf("no card has the partition %s", uuid)
}

// Finish

// DatacardFinish is the last step, after step 3 (and, for a new card, its
// copy): datacard.txt on the eMMC's BOOT, and for the original card its BOOT
// unable to start and its loader blanked.
func (c *Config) DatacardFinish(p *Progress) error {
	r, err := c.begin(p, "datacard-finish")
	if err != nil {
		return err
	}
	d := r.st.Datacard
	if d == nil || (d.Kind != "original" && d.Kind != "new") {
		return errors.New("no data card was chosen")
	}
	if r.st.Custom != nil || r.st.Step3 != "done" {
		return errors.New("andr36oid isn't fully installed on the eMMC yet")
	}
	sdParts, err := c.sdParts()
	if err != nil {
		return err
	}
	switch d.Kind {
	case "original":
		ud, ok := Find(sdParts, "userdata")
		if !ok {
			return errors.New("the SD card has no userdata partition")
		}
		d.UUID = ud.PartUUID()
	case "new":
		if d.Stage != "prepared" {
			return errors.New("the new card isn't made yet")
		}
		if d.Option != "C" {
			var f map[string]string
			if err := c.withSDBoot(false, func(dir string) error {
				b, err := os.ReadFile(filepath.Join(dir, DatacardCopy))
				f = parseTest(b)
				if errors.Is(err, os.ErrNotExist) {
					return nil
				}
				return err
			}); err != nil {
				return err
			}
			if f["copy"] != "done" || f["dst"] != d.UUID {
				return errors.New("the data isn't copied to the new card yet")
			}
		}
		card, _, _, err := c.findPart(d.UUID, false)
		if err != nil {
			return fmt.Errorf("the new card: %w", err)
		}
		card.Close()
	}
	boot, ok := Find(r.st.Plan, "BOOT")
	if !ok {
		return errors.New("no BOOT in the eMMC's plan")
	}
	if err := r.mark("datacard-finish"); err != nil {
		return err
	}
	err = r.unlocked(func(e *Disk) error { return c.writeDatacardFile(p, boot, d.UUID) })
	if err != nil {
		return r.stop(err)
	}
	err = c.withSDBoot(true, func(dir string) error {
		for _, f := range []string{TestFile, DatacardCopy} {
			if err := removeBootFile(dir, f); err != nil {
				return err
			}
		}
		if d.Kind == "original" {
			return makeDatacardBoot(p, dir)
		}
		return nil
	})
	if err != nil {
		return r.stop(err)
	}
	if d.Kind == "original" {
		sd, err := OpenDisk(c.SDDev, true, c.Direct)
		if err != nil {
			return r.stop(err)
		}
		err = zeroOn(p, "the SD card's loader area", "SD card", sd, spanLoader)
		if err == nil {
			err = sd.Sync()
		}
		sd.Close()
		if err != nil {
			return r.stop(err)
		}
		// system, vendor and the rest are in use until the console is off
		d.Stage = "blank"
		p.Logf("data card made: the console starts from the eMMC now, the SD card's system partitions are blanked at that start")
	} else {
		d.Stage = "done"
		p.Logf("data card made: shut down and take the old SD card out")
	}
	r.st.Running = ""
	return c.Save(r.st)
}

// writeDatacardFile puts datacard.txt on the eMMC's BOOT (unlocked).
func (c *Config) writeDatacardFile(p *Progress, boot Part, uuid string) error {
	return c.fsops().mount(c, c.EmmcDev, spanOf(boot), "vfat", true, func(dir string) error {
		if err := writeBootFile(dir, DatacardFile, "datacard="+uuid+"\n"); err != nil {
			return err
		}
		p.Logf("eMMC BOOT: %s says datacard=%s", DatacardFile, uuid)
		return nil
	})
}

// readDatacardFile reads the eMMC's datacard.txt ("" without one).
func (c *Config) readDatacardFile() (string, error) {
	e, err := OpenDisk(c.EmmcDev, false, c.Direct)
	if err != nil {
		return "", err
	}
	parts, err := ReadGPT(e)
	e.Close()
	if err != nil {
		return "", fmt.Errorf("eMMC: %w", err)
	}
	boot, ok := Find(parts, "BOOT")
	if !ok {
		return "", errors.New("the eMMC has no BOOT")
	}
	var uuid string
	err = c.fsops().mount(c, c.EmmcDev, spanOf(boot), "vfat", false, func(dir string) error {
		b, err := os.ReadFile(filepath.Join(dir, DatacardFile))
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		uuid = strings.ToLower(parseTest(b)["datacard"])
		return err
	})
	return uuid, err
}

// DatacardBlank runs on andr36oid started from the eMMC with a data card
// (from Confirm): an original card finished from the SD card gets its system
// partitions blanked now that nothing runs from them.
func (c *Config) DatacardBlank(p *Progress) error {
	uuid := strings.ToLower(c.cmdlineValue("androidboot.datacard"))
	if c.BootedFrom() != "emmc" || uuid == "" {
		return nil
	}
	st, err := c.LoadState()
	if err != nil || st.Datacard == nil || st.Datacard.Stage != "blank" || st.Datacard.UUID != uuid {
		return err
	}
	card, ud, sys, err := c.findPart(uuid, true)
	if err != nil {
		return err
	}
	defer card.Close()
	if ud.Name != "userdata" {
		return fmt.Errorf("the data card's partition %s is %s, not userdata", uuid, ud.Name)
	}
	parts, err := ReadGPT(card)
	if err != nil {
		return err
	}
	for _, x := range parts {
		if dataParts[x.Name] || x.Last < LoaderEnd {
			continue
		}
		if m, err := c.mounts(sys, partOf(sys, x)); err != nil || len(m) > 0 {
			return fmt.Errorf("the data card's %s is in use (%v)", x.Name, err)
		}
		s := spanOf(x)
		if s.hi-s.lo > blankHead {
			s.hi = s.lo + blankHead
		}
		if err := zeroOn(p, x.Name, "data card", card, s); err != nil {
			return err
		}
	}
	if err := zeroOn(p, "the loader area", "data card", card, spanLoader); err != nil {
		return err
	}
	if err := card.Sync(); err != nil {
		return err
	}
	st.Datacard.Stage = "done"
	p.Logf("data card: its system partitions are blanked")
	return c.Save(st)
}

// The way back, after the flasher made a data card start again

// DatacardCheck says whether this SD card start is a data card made
// bootable again: its BOOT has the marker, and the eMMC's datacard.txt names
// this card's userdata.
func (c *Config) DatacardCheck() (map[string]any, error) {
	no := func(why string) (map[string]any, error) { return map[string]any{"offer": false, "why": why}, nil }
	if c.BootedFrom() != "sd" {
		return no("not started from an SD card")
	}
	if _, err := c.Emmc(); err != nil {
		return no(err.Error())
	}
	marker := false
	if err := c.withSDBoot(false, func(dir string) error {
		_, err := os.Stat(filepath.Join(dir, DatacardMarker))
		marker = err == nil
		return nil
	}); err != nil {
		return no(err.Error())
	}
	if !marker {
		return no("not a data card")
	}
	parts, err := c.sdParts()
	if err != nil {
		return no(err.Error())
	}
	ud, ok := Find(parts, "userdata")
	if !ok {
		return no("no userdata")
	}
	uuid, err := c.readDatacardFile()
	if err != nil {
		return no(err.Error())
	}
	if uuid != ud.PartUUID() {
		return no("the eMMC's data card is another one (" + uuid + ")")
	}
	out := map[string]any{"offer": true, "uuid": uuid, "back": false, "backup": ""}
	if st, err := c.LoadState(); err == nil {
		out["back"] = st.Back
		if info, err := c.Emmc(); err == nil {
			out["backup"] = c.findBackup(st, info.CID)
		}
	}
	return out, nil
}

// findBackup is the stock eMMC's backup for the eMMC with this CID: the one
// the records name, else the newest one on the cards' storage ("" if none).
func (c *Config) findBackup(st *State, cid string) string {
	ok := func(dir string) bool {
		info, err := loadBackup(dir)
		return err == nil && info.CID == cid
	}
	if st != nil && st.Backup != nil && ok(st.Backup.Dir) {
		return st.Backup.Dir
	}
	if c.BackupGlob == "" {
		return ""
	}
	found, _ := filepath.Glob(c.BackupGlob)
	sort.Sort(sort.Reverse(sort.StringSlice(found))) // stock-<time>: newest first
	for _, dir := range found {
		if ok(dir) {
			return dir
		}
	}
	return ""
}

// DatacardReturn updates the eMMC from this card (update-emmc) and makes the
// card a data card again (datacard-finish). A card without the installer's
// records (made fresh, C) takes the eMMC as it is.
func (c *Config) DatacardReturn(p *Progress) error {
	chk, err := c.DatacardCheck()
	if err != nil {
		return err
	}
	if chk["offer"] != true {
		return fmt.Errorf("this isn't the eMMC's data card: %v", chk["why"])
	}
	info, err := c.Emmc()
	if err != nil {
		return err
	}
	st, err := c.LoadState()
	if err != nil {
		return err
	}
	if st.Custom != nil {
		return errors.New("the eMMC holds another system")
	}
	if st.Step3 != "done" || len(st.Plan) == 0 {
		e, err := OpenDisk(c.EmmcDev, false, c.Direct)
		if err != nil {
			return err
		}
		parts, err := ReadGPT(e)
		e.Close()
		if err != nil {
			return fmt.Errorf("eMMC: %w", err)
		}
		for i := range parts {
			parts[i].GUID, parts[i].Attr = "", 0
		}
		*st = State{CID: info.CID, Sectors: info.Sectors, Backup: st.Backup, Plan: parts,
			Step1: "passed", Step2: "passed", Step3: "done"}
		p.Logf("no records of this eMMC on the card: taking it as installed")
	}
	st.Datacard = &Datacard{Kind: "original", UUID: chk["uuid"].(string)}
	st.Back = false
	if err := c.Save(st); err != nil {
		return err
	}
	if err := c.UpdateEmmc(p); err != nil {
		return err
	}
	return c.DatacardFinish(p)
}

// Going back to the stock system from the eMMC system

// DatacardBack runs on andr36oid started from the eMMC with a data card: it
// makes the data card start the console by itself, so that the stock eMMC can
// be put back from it (a restore only runs from an SD card start). The card
// gets the eMMC's BOOT, system and vendor and the eMMC's loader, written last
// so a cut-off copy leaves a card that doesn't start rather than one that
// starts half; its other system partitions are blanked for Android to set up
// and userdata (apps, saves, settings) isn't touched. Its BOOT keeps the
// data card's marker, so its start offers the restore (DatacardCheck). Our
// U-Boot on the eMMC starts from the card in slot 1 whenever it has a
// boot.ini. The result: "slot 1" or "slot 2", where the card is now.
func (c *Config) DatacardBack(p *Progress) (string, error) {
	uuid := strings.ToLower(c.cmdlineValue("androidboot.datacard"))
	if c.BootedFrom() != "emmc" || uuid == "" {
		return "", errors.New("this only works on andr36oid started from the eMMC with a data card")
	}
	info, err := c.Emmc()
	if err != nil {
		return "", err
	}
	if err := c.powerOK(); err != nil {
		return "", err
	}
	st, err := c.LoadState()
	if err != nil {
		return "", err
	}
	if st.CID != "" && st.CID != info.CID {
		return "", fmt.Errorf("the installer's records are for another eMMC (CID %s, this one %s)", st.CID, info.CID)
	}
	e, err := OpenDisk(c.EmmcDev, false, c.Direct)
	if err != nil {
		return "", err
	}
	defer e.Close()
	emmcParts, err := ReadGPT(e)
	if err != nil {
		return "", fmt.Errorf("eMMC: %w", err)
	}
	if kind, err := LoaderKind(e, st.LoaderSHA); err != nil {
		return "", err
	} else if kind != "andr36oid" && kind != "rockchip" {
		return "", fmt.Errorf("the eMMC's loader area holds no loader (%s)", kind)
	}
	card, ud, sys, err := c.findPart(uuid, true)
	if err != nil {
		return "", fmt.Errorf("the data card: %w", err)
	}
	defer card.Close()
	if ud.Name != "userdata" {
		return "", fmt.Errorf("the data card's partition %s is %s, not userdata", uuid, ud.Name)
	}
	parts, err := ReadGPT(card)
	if err != nil {
		return "", fmt.Errorf("data card: %w", err)
	}
	type partCopy struct {
		name     string
		from, to span
	}
	var copies []partCopy
	for _, name := range []string{"BOOT", "system", "vendor"} {
		src, ok := Find(emmcParts, name)
		dst, ok2 := Find(parts, name)
		if !ok || !ok2 {
			return "", fmt.Errorf("the eMMC or the data card has no %s partition", name)
		}
		// BOOT whole; system and vendor as far as their file system goes (the
		// eMMC's partitions are bigger than the card's)
		n := src.Sectors()
		if name != "BOOT" {
			if b := st.CopiedLn[name]; b > 0 && b%Sector == 0 {
				n = b / Sector
			} else if b, err := extBytes(e, src); err != nil {
				return "", err
			} else if b > 0 {
				n = (b + Sector - 1) / Sector
			}
		}
		if n > src.Sectors() || n > dst.Sectors() {
			return "", fmt.Errorf("the eMMC's %s doesn't fit in the data card's", name)
		}
		copies = append(copies, partCopy{name, span{src.First, src.First + n}, span{dst.First, dst.First + n}})
	}
	copied := map[string]bool{"BOOT": true, "system": true, "vendor": true}
	for _, x := range parts {
		if dataParts[x.Name] || x.Last < LoaderEnd {
			continue
		}
		if m, err := c.mounts(sys, partOf(sys, x)); err != nil || len(m) > 0 {
			return "", fmt.Errorf("the data card's %s is in use (%v)", x.Name, err)
		}
	}
	for _, cp := range copies {
		if _, err := copySpanAs(p, cp.name, e, cp.from, "eMMC", card, cp.to, "data card"); err != nil {
			return "", err
		}
	}
	if err := card.Sync(); err != nil {
		return "", err
	}
	fix := func(dir string) error {
		for _, f := range []string{TestFile, DatacardCopy, DatacardFile} {
			if err := removeBootFile(dir, f); err != nil {
				return err
			}
		}
		if err := writeBootFile(dir, DatacardMarker, DatacardMarkerText); err != nil {
			return err
		}
		return syncDir(dir)
	}
	boot, _ := Find(parts, "BOOT")
	if sys == c.SDSys {
		err = c.withSDBoot(true, fix)
	} else {
		err = c.fsops().mount(c, c.SD2Dev, spanOf(boot), "vfat", true, fix)
	}
	if err != nil {
		return "", fmt.Errorf("the data card's BOOT: %w", err)
	}
	for _, x := range parts {
		if dataParts[x.Name] || copied[x.Name] || x.Last < LoaderEnd {
			continue
		}
		s := spanOf(x)
		if s.hi-s.lo > blankHead {
			s.hi = s.lo + blankHead
		}
		if err := zeroOn(p, x.Name, "data card", card, s); err != nil {
			return "", err
		}
	}
	if _, err := copySpanAs(p, "the loader", e, spanLoader, "eMMC", card, spanLoader, "data card"); err != nil {
		return "", err
	}
	if err := card.Sync(); err != nil {
		return "", err
	}
	st.Back = true
	if err := c.Save(st); err != nil {
		return "", err
	}
	where := "slot 1"
	if sys != c.SDSys {
		where = "slot 2"
	}
	p.Logf("the data card (%s) starts the console by itself now, with the eMMC's andr36oid and its own data", where)
	return where, nil
}

// extBytes is the size of the ext2/3/4 file system at the start of p, 0 if
// there is none.
func extBytes(d *Disk, p Part) (int64, error) {
	sb, err := d.ReadBytes(p.First*Sector+1024, 1024)
	if err != nil {
		return 0, err
	}
	if binary.LittleEndian.Uint16(sb[56:]) != 0xef53 {
		return 0, nil
	}
	blocks := uint64(binary.LittleEndian.Uint32(sb[4:]))
	if binary.LittleEndian.Uint32(sb[96:])&0x80 != 0 { // 64bit
		blocks |= uint64(binary.LittleEndian.Uint32(sb[336:])) << 32
	}
	return int64(blocks) << (10 + binary.LittleEndian.Uint32(sb[24:])), nil
}

// Helpers

// copySpanAs is copySpan between any two disks, named for the progress.
func copySpanAs(p *Progress, what string, src *Disk, from span, srcName string, dst *Disk, to span, dstName string) (string, error) {
	n := from.n()
	if to.n() < n {
		return "", fmt.Errorf("%s: %d bytes don't fit in %d", what, n, to.n())
	}
	op := p.Start("Copy "+what, srcName+" > "+dstName, to.lo, to.lo+n/Sector, n)
	sha, err := src.CopyTo(from.off(), dst, to.off(), n, op.Tick)
	if err != nil {
		return "", op.Fail(err)
	}
	op.OK(sha, "copied")
	op = p.Start("Check "+what+" on the "+dstName, dstName, to.lo, to.lo+n/Sector, n)
	got, err := dst.HashRange(to.off(), n, op.Tick)
	if err != nil {
		return "", op.Fail(err)
	}
	if got != sha {
		return "", op.Fail(fmt.Errorf("%s on the %s reads back different (%s, written %s)", what, dstName, got[:12], sha[:12]))
	}
	op.OK(got, "same as written")
	op = p.Start("Check "+what+" on the "+srcName+" is unchanged", srcName, from.lo, from.hi, n)
	again, err := src.HashRange(from.off(), n, op.Tick)
	if err != nil {
		return "", op.Fail(err)
	}
	if again != sha {
		return "", op.Fail(fmt.Errorf("%s on the %s changed while it was copied", what, srcName))
	}
	op.OK(again, "unchanged")
	return sha, nil
}

// zeroOn writes zeros over a span of any disk and checks they read back.
func zeroOn(p *Progress, what, dev string, d *Disk, s span) error {
	op := p.Start("Blank "+what, dev, s.lo, s.hi, s.n())
	if err := d.Zero(s.off(), s.n(), op.Tick); err != nil {
		return op.Fail(err)
	}
	op.OK("", "written")
	zero, err := d.IsZero(s.off(), s.n(), nil)
	if err != nil {
		return op.Fail(err)
	}
	if !zero {
		return op.Fail(fmt.Errorf("%s doesn't read back blank", what))
	}
	return nil
}

// DatacardCopyPending says the SD card's BOOT asks for a userdata copy not
// done yet (for the eMMC system's Settings, after the test start check).
func (c *Config) DatacardCopyPending() bool {
	pending := false
	c.withSDBoot(false, func(dir string) error {
		b, err := os.ReadFile(filepath.Join(dir, DatacardCopy))
		pending = err == nil && parseTest(b)["copy"] != "done"
		return nil
	})
	return pending
}

// After a restore

// DatacardRevive makes the data card a restore left behind (State.Revive)
// start the console again, Android's data on it kept. The SD card this runs
// from may be that card already (the flasher made it start again): then only
// the marker goes. A card in slot 2 gets this SD card's system: BOOT, system
// and vendor copied, the other system partitions blanked for Android to set
// up, and the loader last, so a cut-off copy leaves a card that doesn't start
// rather than one that starts half. Forget leaves the card as it is and
// stops offering it. The result: "this card" or "slot 2".
func (c *Config) DatacardRevive(p *Progress, forget bool) (string, error) {
	r, err := c.begin(p, "datacard-revive")
	if err != nil {
		return "", err
	}
	d := r.st.Revive
	if d == nil {
		return "", errors.New("there is no data card to make start again")
	}
	if forget {
		p.Logf("the data card is left as it is")
		return "", c.clearRevive(r)
	}
	card, ud, sys, err := c.findPart(d.UUID, false)
	if err != nil {
		return "", fmt.Errorf("the data card isn't in the console: put it in slot 2 (%v)", err)
	}
	card.Close()
	if ud.Name != "userdata" {
		return "", fmt.Errorf("the data card's partition %s is %s, not userdata", d.UUID, ud.Name)
	}
	if sys == c.SDSys {
		if err := c.withSDBoot(true, reviveBoot); err != nil {
			return "", err
		}
		p.Logf("this SD card is the data card and starts already: its marker is gone")
		return "this card", c.clearRevive(r)
	}
	if err := c.releaseSlot2(p); err != nil {
		return "", err
	}
	if card, err = OpenDisk(c.SD2Dev, true, c.Direct); err != nil {
		return "", err
	}
	defer card.Close()
	sd, err := OpenDisk(c.SDDev, false, c.Direct)
	if err != nil {
		return "", err
	}
	defer sd.Close()
	sdParts, err := ReadGPT(sd)
	if err != nil {
		return "", fmt.Errorf("SD card: %w", err)
	}
	parts, err := ReadGPT(card)
	if err != nil {
		return "", fmt.Errorf("data card: %w", err)
	}
	copied := map[string]bool{"BOOT": true, "system": true, "vendor": true}
	for _, name := range []string{"BOOT", "system", "vendor"} {
		src, ok := Find(sdParts, name)
		dst, ok2 := Find(parts, name)
		if !ok || !ok2 {
			return "", fmt.Errorf("the SD card or the data card has no %s partition", name)
		}
		if dst.Sectors() < src.Sectors() {
			return "", fmt.Errorf("the data card's %s is smaller than this SD card's", name)
		}
	}
	if err := r.mark("datacard-revive"); err != nil {
		return "", err
	}
	for _, name := range []string{"BOOT", "system", "vendor"} {
		src, _ := Find(sdParts, name)
		dst, _ := Find(parts, name)
		to := span{dst.First, dst.First + src.Sectors()}
		if _, err := copySpanAs(p, name, sd, spanOf(src), "SD card", card, to, "data card"); err != nil {
			return "", r.stop(err)
		}
	}
	if err := card.Sync(); err != nil {
		return "", r.stop(err)
	}
	boot, _ := Find(parts, "BOOT")
	if err := c.fsops().mount(c, c.SD2Dev, spanOf(boot), "vfat", true, func(dir string) error {
		for _, f := range []string{TestFile, DatacardCopy, DatacardFile} {
			if err := removeBootFile(dir, f); err != nil {
				return err
			}
		}
		return reviveBoot(dir)
	}); err != nil {
		return "", r.stop(fmt.Errorf("the data card's BOOT: %w", err))
	}
	for _, x := range parts {
		if dataParts[x.Name] || copied[x.Name] || x.Last < LoaderEnd {
			continue
		}
		s := spanOf(x)
		if s.hi-s.lo > blankHead {
			s.hi = s.lo + blankHead
		}
		if err := zeroOn(p, x.Name, "data card", card, s); err != nil {
			return "", r.stop(err)
		}
	}
	if _, err := copySpanAs(p, "the loader", sd, spanLoader, "SD card", card, spanLoader, "data card"); err != nil {
		return "", r.stop(err)
	}
	if err := card.Sync(); err != nil {
		return "", r.stop(err)
	}
	r.st.Running = ""
	p.Logf("the data card starts the console again, with this SD card's andr36oid and its own data")
	return "slot 2", c.clearRevive(r)
}

func (c *Config) clearRevive(r *run) error {
	r.st.Revive = nil
	return c.Save(r.st)
}

// reviveBoot undoes makeDatacardBoot on a BOOT folder: the start scripts
// back (unless the flasher wrote new ones) and the marker gone.
func reviveBoot(dir string) error {
	for _, f := range bootScripts {
		from := filepath.Join(dir, f+".datacard")
		if _, err := os.Stat(from); err != nil {
			continue
		}
		if _, err := os.Stat(filepath.Join(dir, f)); err == nil {
			if err := removeBootFile(dir, f+".datacard"); err != nil {
				return err
			}
			continue
		}
		if err := os.Rename(from, filepath.Join(dir, f)); err != nil {
			return err
		}
	}
	if err := removeBootFile(dir, DatacardMarker); err != nil {
		return err
	}
	return syncDir(dir)
}
