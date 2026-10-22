package emmc

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// A step's writes go in an order that never leaves a half loader the boot ROM
// would run: the ROM only takes the eMMC's loader when sector 64 (the
// idbloader's header) is valid. Blanking clears sector 64 first; writing a
// loader writes it last, after everything it starts.
type span struct{ lo, hi int64 } // sectors, hi excluded

var (
	spanHeader = span{LoaderStart, LoaderStart + 1}
	spanIdb    = span{LoaderStart + 1, UbootStart}
	spanUboot  = span{UbootStart, LoaderEnd}
	spanLoader = span{LoaderStart, LoaderEnd}
)

func (s span) off() int64 { return s.lo * Sector }
func (s span) n() int64   { return (s.hi - s.lo) * Sector }

// run is one write command: the checks every one of them makes first.
type run struct {
	c    *Config
	p    *Progress
	st   *State
	info *EmmcInfo
}

// wizardStart: the commands a wizard starts with, the only ones that check the
// battery. Steps 1 to 3, the data card's steps and the restore (the way out after
// a stop, or the end of going back to stock) never stop for it.
var wizardStart = map[string]bool{
	"backup": true, "update-emmc": true, "update-uboot": true, "write-image": true,
}

func (c *Config) begin(p *Progress, cmd string) (*run, error) {
	if err := c.needSD(); err != nil {
		return nil, err
	}
	info, err := c.Emmc()
	if err != nil {
		return nil, err
	}
	if err := c.emmcUnused(); err != nil {
		return nil, err
	}
	// the battery only where a wizard starts: 60% carries it through all its steps,
	// so no later step stops for the battery
	if wizardStart[cmd] {
		if err := c.powerOK(); err != nil {
			return nil, err
		}
	}
	st, err := c.LoadState()
	if err != nil {
		return nil, err
	}
	if cmd != "restore" {
		// a new run after a restore: the wizard goes on from here
		st.Restored = false
	}
	if st.CID != "" && st.CID != info.CID {
		return nil, fmt.Errorf("the installer's records are for another eMMC (CID %s, this one %s)", st.CID, info.CID)
	}
	if st.Running != "" && st.Running != cmd && cmd != "restore" {
		return nil, fmt.Errorf("%s was cut off: restore the stock system, or run %s again", st.Running, st.Running)
	}
	if st.Incomplete != "" && st.Incomplete != cmd && cmd != "restore" &&
		!(st.Incomplete == "update-uboot" && cmd == "update-emmc") {
		return nil, fmt.Errorf("%s didn't finish: run it again, or restore the stock system", st.Incomplete)
	}
	if st.Backup != nil && cmd != "backup" && cmd != "restore" {
		// every step's events also go to the log next to the backup
		if err := p.AddLog(filepath.Join(st.Backup.Dir, "install.log")); err != nil {
			if st.Datacard == nil || st.Datacard.Kind == "emmc" {
				return nil, fmt.Errorf("the backup folder isn't there: %w", err)
			}
			// a data card is a copy of the card the backup was made on, or
			// that card made bootable again by the flasher
			p.Logf("the backup folder isn't on this card (%v)", err)
		}
	}
	p.Logf("eMMC %s, CID %s, %d MiB; battery %d%%, charger %v",
		info.Name, info.CID, info.Sectors*Sector>>20, c.Battery().Capacity, c.Battery().Charger)
	return &run{c: c, p: p, st: st, info: info}, nil
}

// mark records the write under way before it starts.
func (r *run) mark(cmd string) error {
	r.st.CID, r.st.Sectors = r.info.CID, r.info.Sectors
	r.st.Running, r.st.Failed = cmd, ""
	return r.c.Save(r.st)
}

// stop is a hard stop after writing began: recorded, nothing more is done.
func (r *run) stop(err error) error {
	r.st.Failed = err.Error()
	if serr := r.c.Save(r.st); serr != nil {
		r.p.Logf("state not saved: %v", serr)
	}
	r.p.Logf("STOPPED: %v", err)
	return err
}

// redoStep2 is for step 3 finding the eMMC changed since step 2 copied it:
// step 2 is undone, so the wizard offers it again instead of a step 3 that
// can never pass. Nothing was written, the eMMC still has no loader.
func (r *run) redoStep2(err error) error {
	r.st.Step2 = ""
	if serr := r.c.Save(r.st); serr != nil {
		r.p.Logf("state not saved: %v", serr)
	}
	r.p.Logf("step 2 has to run again: %v", err)
	return fmt.Errorf("%w; run step 2 again", err)
}

// unlocked opens the eMMC for writing for fn and locks it again after,
// whatever happens.
func (r *run) unlocked(fn func(e *Disk) error) (err error) {
	if err := r.c.Lock(r.info.CID, true); err != nil {
		return err
	}
	r.p.Logf("eMMC unlocked for writing (CID %s)", r.info.CID)
	defer func() {
		if lerr := r.c.Lock(r.info.CID, false); lerr != nil && err == nil {
			err = lerr
		}
		r.p.Logf("eMMC locked")
	}()
	e, err := OpenDisk(r.c.EmmcDev, true, r.c.Direct)
	if err != nil {
		return err
	}
	defer e.Close()
	if e.Sectors() != r.info.Sectors {
		return fmt.Errorf("eMMC is %d sectors, sysfs says %d", e.Sectors(), r.info.Sectors)
	}
	return fn(e)
}

func (r *run) openSD() (*Disk, error) { return OpenDisk(r.c.SDDev, false, r.c.Direct) }

func (r *run) openEmmc() (*Disk, error) { return OpenDisk(r.c.EmmcDev, false, r.c.Direct) }

// Loader areas

// checkLoader makes sure the SD card's loader area holds a loader (idbloader,
// U-Boot and trust headers where they belong) and returns its sha256.
func checkLoader(p *Progress, sd *Disk) (string, error) {
	for _, m := range []struct {
		at   int64
		what string
		want []byte
	}{
		{LoaderStart, "idbloader", []byte{0x3b, 0x8c, 0xdc, 0xfc}},
		{UbootStart, "U-Boot", []byte("LOADER  ")},
		{TrustStart, "trust", []byte("BL3X")},
	} {
		b, err := sd.ReadBytes(m.at*Sector, Sector)
		if err != nil {
			return "", err
		}
		if !bytes.HasPrefix(b, m.want) {
			return "", fmt.Errorf("the SD card has no %s at sector %d", m.what, m.at)
		}
	}
	op := p.Start("Read the SD card's loader", "SD", spanLoader.lo, spanLoader.hi, spanLoader.n())
	sha, err := sd.HashRange(spanLoader.off(), spanLoader.n(), op.Tick)
	if err != nil {
		return "", op.Fail(err)
	}
	op.OK(sha, "idbloader, U-Boot and trust found")
	return sha, nil
}

// LoaderKind says what is in a disk's loader area: blank, andr36oid (the
// same as the SD card's), rockchip (another Rockchip loader, stock) or
// unknown.
func LoaderKind(d *Disk, sdSHA string) (string, error) {
	zero, err := d.IsZero(spanLoader.off(), spanLoader.n(), nil)
	if err != nil {
		return "", err
	}
	if zero {
		return "blank", nil
	}
	sha, err := d.HashRange(spanLoader.off(), spanLoader.n(), nil)
	if err != nil {
		return "", err
	}
	if sha == sdSHA {
		return "andr36oid", nil
	}
	b, err := d.ReadBytes(LoaderStart*Sector, Sector)
	if err != nil {
		return "", err
	}
	if bytes.HasPrefix(b, []byte{0x3b, 0x8c, 0xdc, 0xfc}) || bytes.HasPrefix(b, []byte("RKNS")) {
		return "rockchip", nil
	}
	return "unknown", nil
}

// copySpan copies a span from src to dst and checks it on both.
func copySpan(p *Progress, what string, src *Disk, from span, dst *Disk, to span) (string, error) {
	n := from.n()
	if to.n() < n {
		return "", fmt.Errorf("%s: %d bytes don't fit in %d", what, n, to.n())
	}
	op := p.Start("Copy "+what, "SD > eMMC", to.lo, to.lo+n/Sector, n)
	sha, err := src.CopyTo(from.off(), dst, to.off(), n, op.Tick)
	if err != nil {
		return "", op.Fail(err)
	}
	op.OK(sha, "copied")
	op = p.Start("Check "+what+" on the eMMC", "eMMC", to.lo, to.lo+n/Sector, n)
	got, err := dst.HashRange(to.off(), n, op.Tick)
	if err != nil {
		return "", op.Fail(err)
	}
	if got != sha {
		return "", op.Fail(fmt.Errorf("%s on the eMMC reads back different (%s, written %s)", what, got[:12], sha[:12]))
	}
	op.OK(got, "same as written")
	op = p.Start("Check "+what+" on the SD card is unchanged", "SD", from.lo, from.hi, n)
	again, err := src.HashRange(from.off(), n, op.Tick)
	if err != nil {
		return "", op.Fail(err)
	}
	if again != sha {
		return "", op.Fail(fmt.Errorf("%s on the SD card changed while it was copied", what))
	}
	op.OK(again, "unchanged")
	return sha, nil
}

// zeroSpan writes zeros over a span and checks they read back.
func zeroSpan(p *Progress, what string, e *Disk, s span) error {
	op := p.Start("Blank "+what, "eMMC", s.lo, s.hi, s.n())
	if err := e.Zero(s.off(), s.n(), op.Tick); err != nil {
		return op.Fail(err)
	}
	op.OK("", "written")
	op = p.Start("Check "+what+" is blank", "eMMC", s.lo, s.hi, s.n())
	zero, err := e.IsZero(s.off(), s.n(), op.Tick)
	if err != nil {
		return op.Fail(err)
	}
	if !zero {
		return op.Fail(fmt.Errorf("%s doesn't read back blank", what))
	}
	op.OK("", "blank")
	return nil
}

// Inspect

// Inspection is everything the wizard shows before anything is written.
type Inspection struct {
	BootedFrom string    `json:"booted_from"`
	Emmc       *EmmcInfo `json:"emmc,omitempty"`
	EmmcError  string    `json:"emmc_error,omitempty"`
	Loader     string    `json:"emmc_loader,omitempty"`
	EmmcTable  []Part    `json:"emmc_table,omitempty"`
	EmmcMounts []Mount   `json:"emmc_mounts,omitempty"`
	SDLoader   string    `json:"sd_loader_sha256,omitempty"`
	SDTable    []Part    `json:"sd_table,omitempty"`
	Plan       []Part    `json:"plan,omitempty"`
	Power      Power     `json:"power"`
	State      *State    `json:"state,omitempty"`
	TestFile   string    `json:"test_file,omitempty"`
	Problems   []string  `json:"problems,omitempty"`
}

// Inspect looks at everything and writes nothing.
func (c *Config) Inspect() *Inspection {
	in := &Inspection{BootedFrom: c.BootedFrom(), Power: c.Battery()}
	bad := func(err error) { in.Problems = append(in.Problems, err.Error()) }
	if err := c.needSD(); err != nil {
		bad(err)
	}
	if err := c.powerOK(); err != nil {
		bad(err)
	}
	st, err := c.LoadState()
	if err != nil {
		bad(err)
	}
	in.State = st
	p, _ := NewProgress("", "inspect")
	if sd, err := OpenDisk(c.SDDev, false, c.Direct); err != nil {
		bad(err)
	} else {
		defer sd.Close()
		if in.SDLoader, err = checkLoader(p, sd); err != nil {
			bad(err)
		}
		if in.SDTable, err = ReadGPT(sd); err != nil {
			bad(fmt.Errorf("SD card: %w", err))
		}
	}
	info, err := c.Emmc()
	if err != nil {
		in.EmmcError = err.Error()
		bad(err)
		return in
	}
	in.Emmc = info
	if in.EmmcMounts, err = c.mounts(c.EmmcSys, ""); err != nil {
		bad(err)
	} else if len(in.EmmcMounts) > 0 {
		bad(c.emmcUnused())
	}
	if e, err := OpenDisk(c.EmmcDev, false, c.Direct); err != nil {
		bad(err)
	} else {
		defer e.Close()
		if in.Loader, err = LoaderKind(e, in.SDLoader); err != nil {
			bad(err)
		}
		in.EmmcTable, _ = ReadGPT(e)
	}
	if in.SDTable != nil {
		if in.Plan, err = c.MakePlan(info.Sectors, in.SDTable); err != nil {
			bad(err)
		}
	}
	if in.BootedFrom == "sd" {
		c.withSDBoot(false, func(dir string) error {
			in.TestFile, _, _ = readTest(dir)
			return nil
		})
	}
	return in
}

// Step 0: backup

type backupFile struct {
	Name  string `json:"name"`
	Bytes int64  `json:"bytes"`
	SHA   string `json:"sha256"`
}

type backupInfo struct {
	CID     string       `json:"cid"`
	Name    string       `json:"name"`
	Sectors int64        `json:"sectors"`
	Time    string       `json:"time"`
	SHA     string       `json:"sha256"`
	Files   []backupFile `json:"files"`
	Boot    []backupFile `json:"boot_areas,omitempty"`
}

const backupInfoName = "backup.json"

func dropCache(f *os.File) {
	const fadvDontNeed = 4
	syscall.Syscall6(syscall.SYS_FADVISE64, f.Fd(), 0, 0, fadvDontNeed, 0, 0)
}

// hashFile reads a file back from the card (not from memory) and hashes it;
// also copies what it reads to also, when not nil.
func hashFile(path string, tick Ticker, base int64, also io.Writer) (string, int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()
	dropCache(f)
	h := sha256.New()
	b := make([]byte, chunk)
	var n int64
	for {
		m, err := f.Read(b)
		h.Write(b[:m])
		if also != nil {
			also.Write(b[:m])
		}
		n += int64(m)
		if tick != nil && m > 0 {
			tick(base + n)
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return "", n, err
		}
	}
	return sum(h.Sum(nil)), n, nil
}

// readToFile copies all of a disk to files of at most per bytes each, named
// prefix.000, ... (or just prefix when per is 0).
func readToFile(d *Disk, dir, prefix string, per int64, all io.Writer, tick Ticker) ([]backupFile, error) {
	var out []backupFile
	b := buf(chunk)
	for i, off := 0, int64(0); off < d.Size; i++ {
		n, name := d.Size-off, prefix
		if per > 0 {
			n = min(per, n)
			name = fmt.Sprintf("%s.%03d", prefix, i)
		}
		f, err := os.OpenFile(filepath.Join(dir, name), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0644)
		if err != nil {
			return nil, err
		}
		h := sha256.New()
		for done := int64(0); done < n; {
			m := min(int64(chunk), n-done)
			if err := d.ReadAt(b[:m], off+done); err != nil {
				f.Close()
				return nil, err
			}
			h.Write(b[:m])
			if all != nil {
				all.Write(b[:m])
			}
			if _, err := f.Write(b[:m]); err != nil {
				f.Close()
				return nil, err
			}
			done += m
			if tick != nil {
				tick(off + done)
			}
		}
		if err := f.Sync(); err != nil {
			f.Close()
			return nil, err
		}
		if err := f.Close(); err != nil {
			return nil, err
		}
		out = append(out, backupFile{Name: name, Bytes: n, SHA: sum(h.Sum(nil))})
		off += n
	}
	return out, nil
}

// backupLabel names a console's backup folder: the eMMC's model name and the
// end of its CID, such as P1J95K-dd0e00ab.
func backupLabel(info *EmmcInfo) string {
	name := strings.Map(func(r rune) rune {
		if r >= 'A' && r <= 'Z' || r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '-' || r == '_' {
			return r
		}
		return -1
	}, info.Name)
	if name == "" {
		name = "eMMC"
	}
	return name + "-" + info.CID[len(info.CID)-8:]
}

// Backup copies the whole eMMC (user area and boot areas) into a new folder
// "eMMC backup/<backupLabel>/stock-<time>" under parent, reads the eMMC a
// second time to make sure it reads the same, and reads the copy back.
func (c *Config) Backup(p *Progress, parent string) (string, error) {
	r, err := c.begin(p, "backup")
	if err != nil {
		return "", err
	}
	if c.BackupChunk <= 0 || c.BackupChunk%Sector != 0 {
		return "", errors.New("backup chunk size wrong")
	}
	e, err := r.openEmmc()
	if err != nil {
		return "", err
	}
	defer e.Close()
	var boots []*Disk
	for _, n := range []string{"boot0", "boot1"} {
		d, err := OpenDisk(c.EmmcDev+n, false, c.Direct)
		if errors.Is(err, os.ErrNotExist) {
			p.Logf("no %s boot area", n)
			continue
		}
		if err != nil {
			return "", err
		}
		defer d.Close()
		boots = append(boots, d)
	}
	need := e.Size + 64<<20
	for _, d := range boots {
		need += d.Size
	}
	var fs syscall.Statfs_t
	if err := syscall.Statfs(parent, &fs); err != nil {
		return "", err
	}
	if free := int64(fs.Bavail) * int64(fs.Bsize); free < need {
		return "", fmt.Errorf("the backup needs %d MiB free on %s, there are %d MiB", need>>20, parent, free>>20)
	}
	// one folder per console's eMMC, so backups from several consoles made
	// with one SD card can't be mixed up
	dir := filepath.Join(parent, "eMMC backup", backupLabel(r.info), "stock-"+time.Now().Format("20060102-150405"))
	if err := os.MkdirAll(filepath.Dir(dir), 0755); err != nil {
		return "", err
	}
	if err := os.Mkdir(dir, 0755); err != nil {
		return "", err
	}
	defer shareWithUser(filepath.Join(parent, "eMMC backup"))
	p.AddLog(filepath.Join(dir, "install.log"))
	p.Logf("backup to %s", dir)

	info := backupInfo{CID: r.info.CID, Name: r.info.Name, Sectors: e.Sectors(), Time: time.Now().UTC().Format(time.RFC3339)}
	all := sha256.New()
	op := p.Start("Read the eMMC into the backup", "eMMC", 0, e.Sectors(), e.Size)
	if info.Files, err = readToFile(e, dir, "user", c.BackupChunk, all, op.Tick); err != nil {
		return "", op.Fail(err)
	}
	info.SHA = sum(all.Sum(nil))
	op.OK(info.SHA, "%d files", len(info.Files))
	for _, d := range boots {
		name := filepath.Base(d.Path)
		name = name[len(name)-5:] // boot0, boot1
		op := p.Start("Read the eMMC's "+name+" area", name, 0, d.Sectors(), d.Size)
		f, err := readToFile(d, dir, name+".img", 0, nil, op.Tick)
		if err != nil {
			return "", op.Fail(err)
		}
		op.OK(f[0].SHA, "")
		info.Boot = append(info.Boot, f[0])
	}

	op = p.Start("Read the eMMC again: does it read the same?", "eMMC", 0, e.Sectors(), e.Size)
	again, err := e.HashRange(0, e.Size, op.Tick)
	if err != nil {
		return "", op.Fail(err)
	}
	if again != info.SHA {
		return "", op.Fail(fmt.Errorf("the eMMC read differently the second time (%s, first %s)", again[:12], info.SHA[:12]))
	}
	op.OK(again, "same")
	for i, d := range boots {
		op := p.Start("Read the "+info.Boot[i].Name+" area again", "eMMC", 0, d.Sectors(), d.Size)
		again, err := d.HashRange(0, d.Size, op.Tick)
		if err != nil {
			return "", op.Fail(err)
		}
		if again != info.Boot[i].SHA {
			return "", op.Fail(fmt.Errorf("%s read differently the second time", info.Boot[i].Name))
		}
		op.OK(again, "same")
	}

	var total int64
	files := append(append([]backupFile{}, info.Files...), info.Boot...)
	for _, f := range files {
		total += f.Bytes
	}
	op = p.Start("Read the backup back from the SD card", "SD", 0, 0, total)
	var base int64
	for _, f := range files {
		got, n, err := hashFile(filepath.Join(dir, f.Name), op.Tick, base, nil)
		if err != nil {
			return "", op.Fail(err)
		}
		if got != f.SHA || n != f.Bytes {
			return "", op.Fail(fmt.Errorf("%s reads back different from what was written", f.Name))
		}
		base += n
	}
	op.OK("", "%d files read back, all the same", len(files))

	j, _ := json.MarshalIndent(info, "", "  ")
	if err := writeFileSync(filepath.Join(dir, backupInfoName), j); err != nil {
		return "", err
	}
	var sums strings.Builder
	for _, f := range files {
		fmt.Fprintf(&sums, "%s  %s\n", f.SHA, f.Name)
	}
	if err := writeFileSync(filepath.Join(dir, "SHA256SUMS"), []byte(sums.String())); err != nil {
		return "", err
	}
	r.st.CID, r.st.Sectors = r.info.CID, r.info.Sectors
	r.st.Backup = &Backup{Dir: dir, SHA: info.SHA, Sectors: info.Sectors, Verified: true}
	if err := c.Save(r.st); err != nil {
		return "", err
	}
	p.Logf("backup done: %s", dir)
	return dir, nil
}

func loadBackup(dir string) (*backupInfo, error) {
	b, err := os.ReadFile(filepath.Join(dir, backupInfoName))
	if err != nil {
		return nil, fmt.Errorf("no backup in %s: %w", dir, err)
	}
	var info backupInfo
	if err := json.Unmarshal(b, &info); err != nil {
		return nil, fmt.Errorf("%s broken: %w", backupInfoName, err)
	}
	var n int64
	for _, f := range info.Files {
		st, err := os.Stat(filepath.Join(dir, f.Name))
		if err != nil {
			return nil, err
		}
		if st.Size() != f.Bytes {
			return nil, fmt.Errorf("backup file %s is %d bytes, should be %d", f.Name, st.Size(), f.Bytes)
		}
		n += f.Bytes
	}
	if n != info.Sectors*Sector {
		return nil, fmt.Errorf("backup files hold %d bytes, the eMMC had %d", n, info.Sectors*Sector)
	}
	return &info, nil
}

// checkBackup reads every file of a backup back from the card and checks it
// against the hashes recorded when it was made: the eMMC's files, and the boot
// areas' files when boot is set.
func checkBackup(p *Progress, dir string, info *backupInfo, boot bool) error {
	files := info.Files
	if boot {
		files = append(append([]backupFile{}, info.Files...), info.Boot...)
	}
	var total int64
	for _, f := range files {
		total += f.Bytes
	}
	op := p.Start("Check the backup", "SD", 0, info.Sectors, total)
	all := sha256.New()
	var base int64
	for i, f := range files {
		var also io.Writer
		if i < len(info.Files) {
			also = all
		}
		got, n, err := hashFile(filepath.Join(dir, f.Name), op.Tick, base, also)
		if err != nil {
			return op.Fail(err)
		}
		if got != f.SHA || n != f.Bytes {
			return op.Fail(fmt.Errorf("backup file %s is damaged", f.Name))
		}
		base += n
	}
	if sum(all.Sum(nil)) != info.SHA {
		return op.Fail(errors.New("the backup files don't add up to the backed up eMMC"))
	}
	op.OK(info.SHA, "%d files, all good", len(files))
	return nil
}

// backupReady makes sure the state's backup is there and whole before a step
// that writes: the backup is the only way back, so it is read back in full
// every time, not trusted from when it was made.
func (r *run) backupReady() error {
	b := r.st.Backup
	if b == nil || !b.Verified {
		return errors.New("no checked backup of the eMMC yet: make the backup first")
	}
	info, err := loadBackup(b.Dir)
	if err != nil {
		return err
	}
	if info.CID != r.info.CID || info.SHA != b.SHA || info.Sectors != r.info.Sectors {
		return fmt.Errorf("the backup in %s is of another eMMC", b.Dir)
	}
	if err := checkBackup(r.p, b.Dir, info, true); err != nil {
		return fmt.Errorf("the backup in %s doesn't read back as it was made, nothing was written: %w", b.Dir, err)
	}
	return nil
}

// Step 1: blank the eMMC's loader

// BlankLoader clears the eMMC's loader area, so the console has to start from
// the SD card's loader alone at the next start (step 1).
func (c *Config) BlankLoader(p *Progress) error {
	r, err := c.begin(p, "blank-loader")
	if err != nil {
		return err
	}
	if err := r.backupReady(); err != nil {
		return err
	}
	sd, err := r.openSD()
	if err != nil {
		return err
	}
	defer sd.Close()
	sha, err := checkLoader(p, sd)
	if err != nil {
		return err
	}
	if err := r.mark("blank-loader"); err != nil {
		return err
	}
	err = r.unlocked(func(e *Disk) error {
		for _, s := range []struct {
			what string
			s    span
		}{{"idbloader header", spanHeader}, {"idbloader area", spanIdb}, {"U-Boot and trust area", spanUboot}} {
			if err := zeroSpan(p, s.what, e, s.s); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return r.stop(err)
	}
	e, err := r.openEmmc()
	if err != nil {
		return r.stop(err)
	}
	defer e.Close()
	if kind, err := LoaderKind(e, sha); err != nil || kind != "blank" {
		return r.stop(fmt.Errorf("eMMC loader area not blank after blanking (%s, %v)", kind, err))
	}
	r.st.LoaderSHA, r.st.Step1, r.st.Step1BootID = sha, "blanked", c.bootID()
	r.st.Step2, r.st.Plan, r.st.Copied, r.st.CopiedLn, r.st.Step3 = "", nil, nil, nil, ""
	r.st.Custom = nil
	r.st.Running = ""
	p.Logf("step 1: the eMMC's loader area is blank; restart, the SD card's loader must start the console")
	return c.Save(r.st)
}

// CheckLoader runs after the restart: the console came back up with a blank
// eMMC loader area, so the SD card's loader works on this console (step 1
// passed).
func (c *Config) CheckLoader(p *Progress) error {
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
	if st.CID != info.CID || st.Step1 != "blanked" {
		return errors.New("step 1 wasn't started on this eMMC")
	}
	if st.Step1BootID == c.bootID() {
		return errors.New("restart the console first")
	}
	sd, err := OpenDisk(c.SDDev, false, c.Direct)
	if err != nil {
		return err
	}
	defer sd.Close()
	sha, err := checkLoader(p, sd)
	if err != nil {
		return err
	}
	if sha != st.LoaderSHA {
		return errors.New("the SD card's loader changed since step 1 began: start step 1 again")
	}
	e, err := OpenDisk(c.EmmcDev, false, c.Direct)
	if err != nil {
		return err
	}
	defer e.Close()
	if kind, err := LoaderKind(e, sha); err != nil || kind != "blank" {
		return fmt.Errorf("the eMMC's loader area isn't blank any more (%s, %v)", kind, err)
	}
	st.Step1 = "passed"
	p.Logf("step 1 passed: the console started from the SD card's loader alone (loader %s)", sha[:12])
	return c.Save(st)
}

// Step 2: andr36oid onto the eMMC

// WriteOS writes the partition table and andr36oid's partitions to the eMMC
// and sets up the one-time test start from it (step 2).
func (c *Config) WriteOS(p *Progress) error {
	r, err := c.begin(p, "write-os")
	if err != nil {
		return err
	}
	if r.st.Step1 != "passed" {
		return errors.New("step 1 hasn't passed")
	}
	if err := r.backupReady(); err != nil {
		return err
	}
	sd, err := r.openSD()
	if err != nil {
		return err
	}
	defer sd.Close()
	sha, err := checkLoader(p, sd)
	if err != nil {
		return err
	}
	if sha != r.st.LoaderSHA {
		return errors.New("the SD card's loader changed since step 1: start again from step 1")
	}
	sdParts, err := ReadGPT(sd)
	if err != nil {
		return fmt.Errorf("SD card: %w", err)
	}
	plan, err := c.MakePlan(r.info.Sectors, sdParts)
	if err != nil {
		return err
	}
	boot, _ := Find(sdParts, "BOOT")
	bootName := filepath.Base(c.SDSys) + "p" + strconv.Itoa(boot.Num)
	if m, err := c.mounts(c.SDSys, bootName); err != nil {
		return err
	} else {
		for _, x := range m {
			if x.RW {
				return fmt.Errorf("the SD card's BOOT is in use (mounted at %s), try again in a moment", x.Point)
			}
		}
	}
	if err := c.withSDBoot(true, removeTest); err != nil {
		return err
	}
	for _, q := range plan {
		p.Logf("plan: %d %-8s sectors %d-%d (%d MiB)", q.Num, q.Name, q.First, q.Last, q.Sectors()*Sector>>20)
	}
	if err := r.mark("write-os"); err != nil {
		return err
	}
	copied, copiedLn := map[string]string{}, map[string]int64{}
	err = r.unlocked(func(e *Disk) error {
		g, err := BuildGPT(r.info.Sectors, plan, c.random())
		if err != nil {
			return err
		}
		op := p.Start("Write the partition table", "eMMC", 0, gptFirstUsable, int64(len(g.Head)+len(g.Tail)))
		if err := e.WriteBytes(g.Head, 0); err != nil {
			return op.Fail(err)
		}
		if err := e.WriteBytes(g.Tail, TailOffset(r.info.Sectors)); err != nil {
			return op.Fail(err)
		}
		head, err := e.ReadBytes(0, int64(len(g.Head)))
		if err != nil {
			return op.Fail(err)
		}
		tail, err := e.ReadBytes(TailOffset(r.info.Sectors), int64(len(g.Tail)))
		if err != nil {
			return op.Fail(err)
		}
		if !bytes.Equal(head, g.Head) || !bytes.Equal(tail, g.Tail) {
			return op.Fail(errors.New("the partition table reads back different"))
		}
		got, err := ReadGPT(e)
		if err != nil || !samePlan(got, plan) {
			return op.Fail(fmt.Errorf("the partition table doesn't read back as planned (%v)", err))
		}
		op.OK("", "%d partitions", len(plan))
		for _, name := range []string{"BOOT", "system", "vendor"} {
			src, _ := Find(sdParts, name)
			dst, _ := Find(plan, name)
			s, err := copySpan(p, name, sd, span{src.First, src.Last + 1}, e, span{dst.First, dst.Last + 1})
			if err != nil {
				return err
			}
			copied[name], copiedLn[name] = s, src.Sectors()*Sector
		}
		// Android formats these itself when their start reads blank
		for _, b := range []struct {
			name string
			max  int64
		}{{"metadata", 0}, {"misc", 0}, {"cache", align}, {"userdata", align}} {
			q, _ := Find(plan, b.name)
			s := span{q.First, q.Last + 1}
			if b.max > 0 && s.hi-s.lo > b.max {
				s.hi = s.lo + b.max
			}
			if err := zeroSpan(p, b.name, e, s); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return r.stop(err)
	}
	r.st.Plan, r.st.Copied, r.st.CopiedLn, r.st.Step2 = plan, copied, copiedLn, "written"
	if err := c.Save(r.st); err != nil {
		return r.stop(err)
	}
	if err := c.withSDBoot(true, func(dir string) error { return writeTest(dir, "emmc_test=start\n") }); err != nil {
		return r.stop(fmt.Errorf("test start not set up: %w", err))
	}
	r.st.Running = ""
	p.Logf("step 2: andr36oid is on the eMMC; restart, it starts from the eMMC once")
	return c.Save(r.st)
}

// U-Boot's part of the loader area holds ubootCopies identical copies, and the
// idbloader starts the first one that checks out. UpdateUboot writes them last
// to first, so a cut-off write always leaves one whole copy, old or new.
const ubootCopies = 4

// UpdateUboot brings U-Boot on the eMMC up to the SD card's after step 3, for
// a U-Boot update (an update of the SD card's image). The idbloader and trust
// must be the same on both: those are never written here.
func (c *Config) UpdateUboot(p *Progress) error {
	return c.update(p, "update-uboot", false)
}

// UpdateEmmc brings andr36oid on the eMMC up to the SD card's after step 3:
// BOOT, system and vendor copied over, U-Boot as UpdateUboot. Apps and data
// (userdata, metadata, cache) stay. Cut off, the eMMC may not start until it
// runs again; the SD card still starts the console.
func (c *Config) UpdateEmmc(p *Progress) error {
	return c.update(p, "update-emmc", true)
}

func (c *Config) update(p *Progress, cmd string, system bool) error {
	r, err := c.begin(p, cmd)
	if err != nil {
		return err
	}
	if r.st.Custom != nil {
		return fmt.Errorf("the eMMC holds %s, not andr36oid: install andr36oid on it first", r.st.Custom.Name)
	}
	if r.st.Step3 != "done" {
		return errors.New("the eMMC has no andr36oid loader yet (step 3)")
	}
	sd, err := r.openSD()
	if err != nil {
		return err
	}
	defer sd.Close()
	sha, err := checkLoader(p, sd)
	if err != nil {
		return err
	}
	type partCopy struct {
		name     string
		from, to span
	}
	var copies []partCopy
	if system {
		sdParts, err := ReadGPT(sd)
		if err != nil {
			return fmt.Errorf("SD card: %w", err)
		}
		for _, name := range []string{"BOOT", "system", "vendor"} {
			src, ok := Find(sdParts, name)
			dst, ok2 := Find(r.st.Plan, name)
			if !ok || !ok2 {
				return fmt.Errorf("no %s partition on the SD card or in the eMMC's plan", name)
			}
			if src.Sectors() > dst.Sectors() {
				return fmt.Errorf("the SD card's %s is bigger than the eMMC's: put the stock eMMC back and install again", name)
			}
			copies = append(copies, partCopy{name, span{src.First, src.Last + 1}, span{dst.First, dst.Last + 1}})
		}
		boot, _ := Find(sdParts, "BOOT")
		m, err := c.mounts(c.SDSys, filepath.Base(c.SDSys)+"p"+strconv.Itoa(boot.Num))
		if err != nil {
			return err
		}
		for _, x := range m {
			if x.RW {
				return fmt.Errorf("the SD card's BOOT is in use (mounted at %s), try again in a moment", x.Point)
			}
		}
	}
	e, err := r.openEmmc()
	if err != nil {
		return err
	}
	if system {
		got, err := ReadGPT(e)
		if err != nil || !samePlan(got, r.st.Plan) {
			e.Close()
			return fmt.Errorf("the eMMC's partition table changed since step 2 (%v)", err)
		}
	}
	ub, err := ubootToWrite(sd, e, sha)
	e.Close()
	if err != nil {
		return err
	}
	if !system && !ub {
		r.st.LoaderSHA = sha
		r.st.finished(cmd)
		p.Logf("U-Boot on the eMMC is already the SD card's")
		return c.Save(r.st)
	}
	if err := r.mark(cmd); err != nil {
		return err
	}
	if r.st.Copied == nil {
		r.st.Copied, r.st.CopiedLn = map[string]string{}, map[string]int64{}
	}
	err = r.unlocked(func(e *Disk) error {
		for _, cp := range copies {
			h, err := copySpan(p, cp.name, sd, cp.from, e, cp.to)
			if err != nil {
				return err
			}
			r.st.Copied[cp.name], r.st.CopiedLn[cp.name] = h, cp.from.n()
		}
		if d := r.st.Datacard; system && d != nil && d.UUID != "" && (d.Stage == "blank" || d.Stage == "done") {
			// the SD card's BOOT has no datacard.txt: the eMMC keeps using its data card
			boot, _ := Find(r.st.Plan, "BOOT")
			if err := c.writeDatacardFile(p, boot, d.UUID); err != nil {
				return err
			}
		}
		if !ub {
			p.Logf("U-Boot on the eMMC is already the SD card's")
			return nil
		}
		return writeUboot(p, sd, e, sha)
	})
	if err != nil {
		return r.stop(err)
	}
	r.st.LoaderSHA, r.st.Running = sha, ""
	r.st.finished(cmd)
	if system {
		p.Logf("andr36oid on the eMMC updated from the SD card")
	} else {
		p.Logf("U-Boot on the eMMC updated")
	}
	return c.Save(r.st)
}

// ubootToWrite says if the eMMC's U-Boot differs from the SD card's (loader
// area hash sha), after checking that only U-Boot does: the idbloader and
// trust are never updated, and every U-Boot copy is where it should be.
func ubootToWrite(sd, e *Disk, sha string) (bool, error) {
	esha, err := e.HashRange(spanLoader.off(), spanLoader.n(), nil)
	if err != nil || esha == sha {
		return false, err
	}
	for _, s := range []struct {
		what string
		s    span
	}{{"idbloader", spanIdb}, {"idbloader header", spanHeader}, {"trust", span{TrustStart, LoaderEnd}}} {
		a, err := sd.HashRange(s.s.off(), s.s.n(), nil)
		if err != nil {
			return false, err
		}
		b, err := e.HashRange(s.s.off(), s.s.n(), nil)
		if err != nil {
			return false, err
		}
		if a != b {
			return false, fmt.Errorf("the %s differs from the SD card's: only U-Boot can be updated here", s.what)
		}
	}
	// The eMMC's copies are not checked here: an update cut off mid-copy
	// leaves one torn, and running the update again is what mends it.
	copySectors := int64(TrustStart-UbootStart) / ubootCopies
	for i := int64(0); i < ubootCopies; i++ {
		b, err := sd.ReadBytes((UbootStart+i*copySectors)*Sector, Sector)
		if err != nil {
			return false, err
		}
		if !bytes.HasPrefix(b, []byte("LOADER  ")) {
			return false, fmt.Errorf("the SD card's U-Boot copy %d isn't where it should be (sector %d)", i+1, UbootStart+i*copySectors)
		}
	}
	return true, nil
}

// ubootOrder is the order writeUboot writes the eMMC's U-Boot copies in: the
// damaged ones first (nothing to lose), then the whole ones last to first, so
// a cut-off write always leaves a whole copy, old or new, while there was one.
func ubootOrder(e *Disk) ([]int64, error) {
	copySectors := int64(TrustStart-UbootStart) / ubootCopies
	var bad, good []int64
	for i := int64(ubootCopies - 1); i >= 0; i-- {
		b, err := e.ReadBytes((UbootStart+i*copySectors)*Sector, Sector)
		if err != nil {
			return nil, err
		}
		if bytes.HasPrefix(b, []byte("LOADER  ")) {
			good = append(good, i)
		} else {
			bad = append(bad, i)
		}
	}
	return append(bad, good...), nil
}

// writeUboot writes U-Boot's copies in ubootOrder, one at a time and each
// checked, then checks the whole loader.
func writeUboot(p *Progress, sd, e *Disk, sha string) error {
	copySectors := int64(TrustStart-UbootStart) / ubootCopies
	order, err := ubootOrder(e)
	if err != nil {
		return err
	}
	for _, i := range order {
		s := span{UbootStart + i*copySectors, UbootStart + (i+1)*copySectors}
		if _, err := copySpan(p, fmt.Sprintf("U-Boot copy %d", i+1), sd, s, e, s); err != nil {
			return err
		}
	}
	op := p.Start("Check the whole loader on the eMMC", "eMMC", spanLoader.lo, spanLoader.hi, spanLoader.n())
	h, err := e.HashRange(spanLoader.off(), spanLoader.n(), op.Tick)
	if err != nil {
		return op.Fail(err)
	}
	if h != sha {
		return op.Fail(errors.New("the loader on the eMMC differs from the SD card's"))
	}
	op.OK(h, "same as the SD card's")
	return nil
}

// StartTest sets up the eMMC test start again (after it was put off).
func (c *Config) StartTest(p *Progress) error {
	if err := c.needSD(); err != nil {
		return err
	}
	st, err := c.LoadState()
	if err != nil {
		return err
	}
	if st.Step2 != "written" && st.Step2 != "deferred" {
		return errors.New("andr36oid isn't written to the eMMC")
	}
	// the file first: written in the records means a test start is set up
	if err := c.withSDBoot(true, func(dir string) error { return writeTest(dir, "emmc_test=start\n") }); err != nil {
		return err
	}
	st.Step2 = "written"
	p.Logf("test start set up")
	return c.Save(st)
}

// CancelTest takes the test start away; step 2 waits as deferred until
// StartTest sets it up again.
func (c *Config) CancelTest(p *Progress) error {
	if err := c.needSD(); err != nil {
		return err
	}
	st, err := c.LoadState()
	if err != nil {
		return err
	}
	if st.Step2 == "written" {
		st.Step2 = "deferred"
		if err := c.Save(st); err != nil {
			return err
		}
	}
	p.Logf("test start taken away")
	return c.withSDBoot(true, removeTest)
}

// CheckTest runs on the SD card after the test start: ok means the eMMC
// system came up (step 2 passed), tried means it didn't.
func (c *Config) CheckTest(p *Progress) (string, error) {
	if err := c.needSD(); err != nil {
		return "", err
	}
	st, err := c.LoadState()
	if err != nil {
		return "", err
	}
	if st.Step2 == "deferred" {
		return "deferred", nil
	}
	if st.Step2 != "written" {
		return st.Step2, fmt.Errorf("no eMMC test under way (step 2 %q)", st.Step2)
	}
	var v string
	var f map[string]string
	err = c.withSDBoot(true, func(dir string) error {
		if v, f, err = readTest(dir); err != nil {
			return err
		}
		if v == "ok" || v == "tried" || v == "" {
			return removeTest(dir)
		}
		return nil
	})
	if err != nil {
		return "", err
	}
	switch {
	case v == "start":
		return "waiting", nil
	case v == "ok" && f["emmc_cid"] == st.CID:
		st.Step2 = "passed"
		p.Logf("step 2 passed: andr36oid started from the eMMC")
	case v == "ok":
		st.Step2, st.Failed = "failed", "the test start was confirmed by another eMMC ("+f["emmc_cid"]+")"
	case v == "tried":
		st.Step2, st.Failed = "failed", "andr36oid didn't start from the eMMC"
	default:
		st.Step2, st.Failed = "failed", "the test file is gone or unreadable ("+v+")"
	}
	if err := c.Save(st); err != nil {
		return "", err
	}
	if st.Failed != "" {
		return st.Step2, errors.New(st.Failed)
	}
	return st.Step2, nil
}

// Confirm runs on andr36oid started from the eMMC, at every start: when the
// SD card's test file says tried, this is the test start and it got this far.
func (c *Config) Confirm(p *Progress) (string, error) {
	if c.BootedFrom() != "emmc" {
		return "not started from the eMMC", nil
	}
	info, err := c.Emmc()
	if err != nil {
		return "", err
	}
	res := "no test"
	err = c.withSDBoot(true, func(dir string) error {
		v, _, err := readTest(dir)
		if err != nil || v != "tried" {
			return err
		}
		res = "confirmed"
		return writeTest(dir, "emmc_test=ok\nemmc_cid="+info.CID+"\n")
	})
	if err != nil && res == "no test" {
		res, err = "no andr36oid SD card", nil
	}
	if err == nil && res == "confirmed" {
		p.Logf("eMMC test start confirmed on the SD card")
	}
	if res != "confirmed" {
		// a test start leaves the cards alone
		err = c.DatacardBlank(p)
	}
	return res, err
}

// Step 3: the loader onto the eMMC

// WriteLoader copies the SD card's loader to the eMMC (step 3), after
// checking steps 1 and 2 passed with this very loader and nothing on the eMMC
// changed. confirm must be the first 12 characters of the loader's sha256.
func (c *Config) WriteLoader(p *Progress, confirm string) error {
	r, err := c.begin(p, "write-loader")
	if err != nil {
		return err
	}
	if r.st.Step1 != "passed" || r.st.Step2 != "passed" {
		return fmt.Errorf("steps 1 and 2 haven't both passed (%q, %q)", r.st.Step1, r.st.Step2)
	}
	if len(r.st.LoaderSHA) < 12 || confirm != r.st.LoaderSHA[:12] {
		return errors.New("not confirmed for this loader")
	}
	if err := r.backupReady(); err != nil {
		return err
	}
	sd, err := r.openSD()
	if err != nil {
		return err
	}
	defer sd.Close()
	sha, err := checkLoader(p, sd)
	if err != nil {
		return err
	}
	if sha != r.st.LoaderSHA {
		return errors.New("the SD card's loader changed since step 1: start again from step 1")
	}
	e, err := r.openEmmc()
	if err != nil {
		return err
	}
	got, err := ReadGPT(e)
	if err != nil || !samePlan(got, r.st.Plan) {
		e.Close()
		return r.redoStep2(fmt.Errorf("the eMMC's partition table changed since step 2 (%v)", err))
	}
	for _, name := range []string{"system", "vendor"} {
		q, _ := Find(r.st.Plan, name)
		n := r.st.CopiedLn[name]
		op := p.Start("Check "+name+" on the eMMC is as written", "eMMC", q.First, q.First+n/Sector, n)
		h, err := e.HashRange(q.First*Sector, n, op.Tick)
		if err != nil || h != r.st.Copied[name] {
			e.Close()
			return r.redoStep2(op.Fail(fmt.Errorf("%s on the eMMC changed since step 2 (%v)", name, err)))
		}
		op.OK(h, "unchanged")
	}
	kind, err := LoaderKind(e, sha)
	e.Close()
	if err != nil || kind != "blank" {
		return fmt.Errorf("the eMMC's loader area isn't blank (%s, %v)", kind, err)
	}
	if err := r.mark("write-loader"); err != nil {
		return err
	}
	err = r.unlocked(func(e *Disk) error {
		for _, s := range []struct {
			what string
			s    span
		}{{"U-Boot and trust", spanUboot}, {"idbloader", spanIdb}, {"idbloader header", spanHeader}} {
			if _, err := copySpan(p, s.what, sd, s.s, e, s.s); err != nil {
				return err
			}
		}
		op := p.Start("Check the whole loader on the eMMC", "eMMC", spanLoader.lo, spanLoader.hi, spanLoader.n())
		h, err := e.HashRange(spanLoader.off(), spanLoader.n(), op.Tick)
		if err != nil {
			return op.Fail(err)
		}
		if h != sha {
			return op.Fail(errors.New("the loader on the eMMC differs from the SD card's"))
		}
		op.OK(h, "same as the SD card's")
		return nil
	})
	if err != nil {
		return r.stop(err)
	}
	r.st.Step3, r.st.Running = "done", ""
	p.Logf("step 3 done: the eMMC has the SD card's loader; shut down, then take the SD card out")
	return c.Save(r.st)
}

// Restore

// NotExactError is a restore that wrote the whole backup back and checked it,
// but can't promise the eMMC is exactly stock: its boot areas (never written
// by andr36oid) differ from the backup or couldn't be read.
type NotExactError struct{ Why string }

func (e *NotExactError) Error() string {
	return "the stock eMMC is written back, but not exactly: " + e.Why
}

// Restore writes a backup back to the eMMC, after checking every file of it,
// and checks the whole eMMC against it. The loader goes back body first and
// checked, its header last, so the boot ROM never finds a header in front of
// a half-written loader.
func (c *Config) Restore(p *Progress, dir string) error {
	r, err := c.begin(p, "restore")
	if err != nil {
		return err
	}
	info, err := loadBackup(dir)
	if err != nil {
		return err
	}
	if info.CID != r.info.CID || info.Sectors != r.info.Sectors {
		return fmt.Errorf("the backup is of another eMMC (CID %s)", info.CID)
	}
	p.AddLog(filepath.Join(dir, "install.log"))
	if err := checkBackup(p, dir, info, false); err != nil {
		return err
	}
	stockLoader, err := backupSpanSHA(dir, info, spanLoader)
	if err != nil {
		return err
	}
	// The boot areas, before anything is written: andr36oid never writes
	// them, so the restore can't mend them, but it can say they differ.
	var notExact []string
	for _, b := range info.Boot {
		name := strings.TrimSuffix(b.Name, ".img")
		op := p.Start("Compare the eMMC's "+name+" area with the backup", name, 0, b.Bytes/Sector, b.Bytes)
		why := ""
		if got, n, err := hashFile(filepath.Join(dir, b.Name), nil, 0, nil); err != nil || got != b.SHA || n != b.Bytes {
			why = fmt.Sprintf("the backup's %s file is damaged", name)
		} else if d, err := OpenDisk(c.EmmcDev+name, false, c.Direct); err != nil {
			why = fmt.Sprintf("the eMMC's %s area can't be read (%v)", name, err)
		} else {
			h, err := d.HashRange(0, d.Size, op.Tick)
			d.Close()
			if err != nil {
				why = fmt.Sprintf("the eMMC's %s area can't be read (%v)", name, err)
			} else if h != b.SHA {
				why = fmt.Sprintf("the eMMC's %s area differs from the backup", name)
			}
		}
		if why != "" {
			op.Fail(errors.New(why))
			notExact = append(notExact, why)
		} else {
			op.OK(b.SHA, "same as the backup")
		}
	}
	if err := r.mark("restore"); err != nil {
		return err
	}
	type part struct {
		what  string
		s     span
		check bool // read back against the backup right after writing
	}
	err = r.unlocked(func(e *Disk) error {
		end := info.Sectors
		parts := []part{
			{"partitions", span{LoaderEnd, end}, false},
			{"partition table", span{0, LoaderStart}, true},
		}
		now, err := e.HashRange(spanLoader.off(), spanLoader.n(), nil)
		if err != nil {
			return err
		}
		if now == stockLoader {
			p.Logf("the eMMC's loader area is already the stock one: not written")
		} else {
			// A loader that starts while the stock one is half written back
			// would hang the console with no way to the SD card: without its
			// header the boot ROM skips the eMMC until the stock header is
			// back, written last, after the rest of the loader checked out.
			if err := zeroSpan(p, "idbloader header", e, spanHeader); err != nil {
				return err
			}
			parts = append(parts,
				part{"U-Boot and trust", spanUboot, true},
				part{"idbloader", spanIdb, true},
				part{"idbloader header", spanHeader, true})
		}
		for _, q := range parts {
			op := p.Start("Write back the "+q.what, "eMMC", q.s.lo, q.s.hi, q.s.n())
			if err := writeFromBackup(dir, info, e, q.s, op.Tick); err != nil {
				return op.Fail(err)
			}
			op.OK("", "written")
			if !q.check {
				continue
			}
			want, err := backupSpanSHA(dir, info, q.s)
			if err != nil {
				return err
			}
			op = p.Start("Check the "+q.what+" against the backup", "eMMC", q.s.lo, q.s.hi, q.s.n())
			h, err := e.HashRange(q.s.off(), q.s.n(), op.Tick)
			if err != nil {
				return op.Fail(err)
			}
			if h != want {
				return op.Fail(fmt.Errorf("the %s on the eMMC reads back different from the backup", q.what))
			}
			op.OK(h, "same as the backup")
		}
		op := p.Start("Check the eMMC against the backup", "eMMC", 0, end, end*Sector)
		h, err := e.HashRange(0, end*Sector, op.Tick)
		if err != nil {
			return op.Fail(err)
		}
		if h != info.SHA {
			return op.Fail(errors.New("the eMMC differs from the backup after writing it back"))
		}
		op.OK(h, "same as the backup")
		return nil
	})
	if err != nil {
		return r.stop(err)
	}
	revive := r.st.Revive
	if d := r.st.Datacard; d != nil && d.UUID != "" && (d.Kind == "original" || d.Kind == "new") &&
		(d.Stage == "blank" || d.Stage == "done") {
		revive = &Datacard{Kind: d.Kind, UUID: d.UUID}
	}
	if c.thisCardIsDatacard(revive) {
		// started from the data card itself (made to start by the flasher or
		// from the eMMC system): it starts already, only its marker goes
		if err := c.withSDBoot(true, reviveBoot); err != nil {
			p.Logf("this card's BOOT: the data card's marker stays (%v)", err)
		} else {
			p.Logf("this card is andr36oid's data card and starts by itself: its marker is gone")
		}
		revive = nil
	}
	*r.st = State{CID: r.st.CID, Sectors: r.st.Sectors, Backup: r.st.Backup, Revive: revive,
		Restored: true}
	if err := c.Save(r.st); err != nil {
		return err
	}
	if len(notExact) > 0 {
		err := &NotExactError{Why: strings.Join(notExact, "; ")}
		p.Logf("%v", err)
		return err
	}
	p.Logf("the stock eMMC is back")
	return nil
}

// eachBackupChunk hands span s of the backed up eMMC to fn, chunk by chunk,
// with each chunk's byte offset on the eMMC.
func eachBackupChunk(dir string, info *backupInfo, s span, fn func(b []byte, pos int64) error) error {
	b := buf(chunk)
	var at int64 // byte offset of the current file on the eMMC
	for _, f := range info.Files {
		lo, hi := max(s.off(), at), min(s.off()+s.n(), at+f.Bytes)
		if lo < hi {
			fh, err := os.Open(filepath.Join(dir, f.Name))
			if err != nil {
				return err
			}
			for pos := lo; pos < hi; {
				m := min(int64(chunk), hi-pos)
				if _, err := fh.ReadAt(b[:m], pos-at); err != nil {
					fh.Close()
					return err
				}
				if err := fn(b[:m], pos); err != nil {
					fh.Close()
					return err
				}
				pos += m
			}
			fh.Close()
		}
		at += f.Bytes
	}
	return nil
}

// backupSpanSHA is the sha256 of span s of the backed up eMMC.
func backupSpanSHA(dir string, info *backupInfo, s span) (string, error) {
	h := sha256.New()
	err := eachBackupChunk(dir, info, s, func(b []byte, _ int64) error {
		h.Write(b)
		return nil
	})
	return sum(h.Sum(nil)), err
}

// writeFromBackup writes span s of the eMMC from the backup files.
func writeFromBackup(dir string, info *backupInfo, e *Disk, s span, tick Ticker) error {
	err := eachBackupChunk(dir, info, s, func(b []byte, pos int64) error {
		if err := e.WriteAt(b, pos); err != nil {
			return err
		}
		if tick != nil {
			tick(pos + int64(len(b)) - s.off())
		}
		return nil
	})
	if err != nil {
		return err
	}
	return e.Sync()
}

// Leave clears a stop or a cut-off write from the records, writing nothing:
// what is on the eMMC stays as it is.
func (c *Config) Leave(p *Progress) error {
	st, err := c.LoadState()
	if err != nil {
		return err
	}
	p.Logf("left as it is: running %q, failed %q", st.Running, st.Failed)
	switch st.Running {
	case "update-emmc", "update-uboot", "restore", "write-image":
		// half old, half new: not installed, not stock, until it runs again
		st.Incomplete = st.Running
	}
	st.Running, st.Failed = "", ""
	return c.Save(st)
}

// finished clears an incomplete record that a successful cmd mends.
func (st *State) finished(cmd string) {
	if st.Incomplete == cmd || (cmd == "update-emmc" && st.Incomplete == "update-uboot") {
		st.Incomplete = ""
	}
}

// thisCardIsDatacard: the SD card this runs from is andr36oid's data card,
// the one revive names or one with the data card's marker on its BOOT.
func (c *Config) thisCardIsDatacard(revive *Datacard) bool {
	if parts, err := c.sdParts(); err == nil && revive != nil {
		if ud, ok := Find(parts, "userdata"); ok && ud.PartUUID() == revive.UUID {
			return true
		}
	}
	marker := false
	c.withSDBoot(false, func(dir string) error {
		_, err := os.Stat(filepath.Join(dir, DatacardMarker))
		marker = err == nil
		return nil
	})
	return marker
}
