package emmc

import (
	"bufio"
	"crypto/rand"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
)

// ProcessSetup undoes init's umask 077 for services: Settings runs as the
// system user and must read the state folder's files (result, progress,
// state).
func ProcessSetup() {
	syscall.Umask(0o022)
}

// mediaUID owns everything on internal storage (/data/media), so a backup
// there shows up for the user and can be copied off.
const mediaUID = 1023

// shareWithUser hands a folder on internal storage to the user; elsewhere
// (SD cards: FAT or exFAT) the mount decides, and nothing is changed.
func shareWithUser(dir string) {
	if !strings.HasPrefix(dir, "/data/media/") {
		return
	}
	filepath.WalkDir(dir, func(path string, _ fs.DirEntry, err error) error {
		if err == nil {
			os.Lchown(path, mediaUID, mediaUID)
		}
		return nil
	})
}

// The bootloader area: idbloader at sector 64, U-Boot at 16384, trust at
// 24576, up to 32768 (16 MiB), where the first partition starts. The kernel
// never lets a system on the eMMC write below 32768 (see block.c).
const (
	LoaderStart = 64
	UbootStart  = 16384
	TrustStart  = 24576
	LoaderEnd   = 32768
)

// Config says where everything is. The host tests point it at image files and
// a temporary directory.
type Config struct {
	Cmdline   string // kernel command line
	Mountinfo string
	BootID    string // changes at every start
	EmmcSys   string // the built-in eMMC in sysfs
	EmmcDev   string
	SDSys     string // the SD card the system runs from
	SDDev     string
	SD2Sys    string // the card in slot 2
	SD2Dev    string
	DataDir   string // Android's /data
	SDBoot    string // the SD card's BOOT partition (vfat)
	SDBootDir string // tests: a directory standing in for the SD card's BOOT
	BootMnt   string // where SDBoot is mounted while the installer uses it
	PowerDir  string
	WakeLock  string
	WakeUnlk  string
	StateDir  string

	Direct      bool  // O_DIRECT on the disks (always on a console)
	MinEmmc     int64 // smallest eMMC taken, sectors
	MinUserdata int64 // smallest userdata, sectors
	BigEmmc     int64 // from this size on, system and vendor get twice their size
	BackupChunk int64 // bytes per backup file

	lock func(cfg *Config, cid string, unlock bool) error // tests replace it
	rnd  io.Reader
	fs   *fsOps // tests: folders in place of file systems
}

// DefaultConfig is the console's.
func DefaultConfig() *Config {
	return &Config{
		Cmdline:     "/proc/cmdline",
		Mountinfo:   "/proc/self/mountinfo",
		BootID:      "/proc/sys/kernel/random/boot_id",
		EmmcSys:     "/sys/block/mmcblk2",
		EmmcDev:     "/dev/block/mmcblk2",
		SDSys:       "/sys/block/mmcblk0",
		SDDev:       "/dev/block/mmcblk0",
		SD2Sys:      "/sys/block/mmcblk1",
		SD2Dev:      "/dev/block/mmcblk1",
		DataDir:     "/data",
		SDBoot:      "/dev/block/platform/ff370000.dwmmc/by-name/BOOT",
		BootMnt:     "/mnt/andr36oid-emmcinstall",
		PowerDir:    "/sys/class/power_supply",
		WakeLock:    "/sys/power/wake_lock",
		WakeUnlk:    "/sys/power/wake_unlock",
		StateDir:    "/data/misc/andr36oid-emmcinstall",
		Direct:      true,
		MinEmmc:     7 << 30 / Sector, // "8 GB" eMMCs have about 7.3 GiB
		MinUserdata: 2 << 30 / Sector,
		BigEmmc:     14 << 30 / Sector, // "16 GB" and up
		BackupChunk: 1 << 30,           // fits FAT32 too
	}
}

func (c *Config) random() io.Reader {
	if c.rnd != nil {
		return c.rnd
	}
	return rand.Reader
}

func readTrim(path string) (string, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(strings.TrimRight(string(b), "\x00")), nil
}

func (c *Config) cmdline() string {
	s, _ := readTrim(c.Cmdline)
	return " " + s + " "
}

// BootedFrom is "sd" for andr36oid started from the SD card, "emmc" for the
// one on the built-in eMMC (its boot.ini), else "".
func (c *Config) BootedFrom() string {
	cl := c.cmdline()
	emmcSystem := strings.Contains(cl, " dw_mmc_rockchip.builtin_emmc=system ")
	switch {
	case strings.Contains(cl, " androidboot.boot_devices=ff370000.dwmmc ") && !emmcSystem:
		return "sd"
	case strings.Contains(cl, " androidboot.boot_devices=ff390000.dwmmc ") && emmcSystem:
		return "emmc"
	}
	return ""
}

func (c *Config) needSD() error {
	if c.BootedFrom() != "sd" {
		return errors.New("andr36oid isn't running from the SD card: the eMMC is only written from an SD card start")
	}
	return nil
}

// EmmcInfo is what the kernel says about the built-in eMMC.
type EmmcInfo struct {
	CID     string `json:"cid"`
	Name    string `json:"name"`
	Sectors int64  `json:"sectors"`
	Locked  string `json:"lock"`
}

// Emmc finds the built-in eMMC: switched on (Kernel Tweaks > Built-in eMMC)
// and marked by the kernel as the console's own (it has andr36oid_unlock).
func (c *Config) Emmc() (*EmmcInfo, error) {
	lock, err := readTrim(filepath.Join(c.EmmcSys, "andr36oid_unlock"))
	if err != nil {
		return nil, errors.New("no built-in eMMC: switch it on in Kernel Tweaks (Built-in eMMC) and restart")
	}
	cid, err := readTrim(filepath.Join(c.EmmcSys, "device/cid"))
	if err != nil || len(cid) != 32 {
		return nil, fmt.Errorf("built-in eMMC: can't read its CID (%q)", cid)
	}
	if t, _ := readTrim(filepath.Join(c.EmmcSys, "device/type")); t != "MMC" {
		return nil, fmt.Errorf("built-in eMMC: type %q, not MMC", t)
	}
	s, _ := readTrim(filepath.Join(c.EmmcSys, "size"))
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil || n <= 0 {
		return nil, fmt.Errorf("built-in eMMC: size %q", s)
	}
	name, _ := readTrim(filepath.Join(c.EmmcSys, "device/name"))
	return &EmmcInfo{CID: strings.ToLower(cid), Name: name, Sectors: n, Locked: lock}, nil
}

// Lock locks (unlock=false) or unlocks the eMMC for writing: the kernel takes
// "unlock <CID>" only for this chip's CID and only while it is read-only
// (started from an SD card).
func (c *Config) Lock(cid string, unlock bool) error {
	if c.lock != nil {
		return c.lock(c, cid, unlock)
	}
	path := filepath.Join(c.EmmcSys, "andr36oid_unlock")
	cmd, want := "lock", "locked"
	if unlock {
		cmd, want = "unlock "+cid, "unlocked"
	}
	if err := os.WriteFile(path, []byte(cmd), 0); err != nil {
		return fmt.Errorf("eMMC %s: %w", want, err)
	}
	if got, _ := readTrim(path); got != want {
		return fmt.Errorf("eMMC still %s after %q", got, cmd)
	}
	return nil
}

// devNums are the "major:minor" of a disk in sysfs and all its partitions.
func devNums(sys string) map[string]string {
	out := map[string]string{}
	if d, err := readTrim(filepath.Join(sys, "dev")); err == nil {
		out[d] = filepath.Base(sys)
	}
	ents, _ := os.ReadDir(sys)
	for _, e := range ents {
		if !strings.HasPrefix(e.Name(), filepath.Base(sys)) {
			continue
		}
		if d, err := readTrim(filepath.Join(sys, e.Name(), "dev")); err == nil {
			out[d] = e.Name()
		}
	}
	return out
}

// Mount is one mount of a disk or partition.
type Mount struct {
	Dev   string `json:"dev"`
	Point string `json:"point"`
	RW    bool   `json:"rw"`
}

// mounts lists the mounts of the disk in sys or its partitions; only=name
// keeps just that partition's.
func (c *Config) mounts(sys, only string) ([]Mount, error) {
	nums := devNums(sys)
	f, err := os.Open(c.Mountinfo)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var out []Mount
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fl := strings.Fields(sc.Text())
		if len(fl) < 6 {
			continue
		}
		name, ok := nums[fl[2]]
		if !ok || (only != "" && name != only) {
			continue
		}
		rw := false
		for _, o := range strings.Split(fl[5], ",") {
			rw = rw || o == "rw"
		}
		out = append(out, Mount{Dev: name, Point: fl[4], RW: rw})
	}
	return out, sc.Err()
}

func (c *Config) emmcUnused() error {
	m, err := c.mounts(c.EmmcSys, "")
	if err != nil {
		return err
	}
	if len(m) > 0 {
		return fmt.Errorf("the eMMC is in use: %s is mounted at %s", m[0].Dev, m[0].Point)
	}
	return nil
}

// Power is the battery and charger.
type Power struct {
	Capacity int  `json:"capacity"`
	Charger  bool `json:"charger"`
}

// Battery reads the battery level and whether a charger is in.
func (c *Config) Battery() Power {
	var p Power
	p.Capacity = -1
	if s, err := readTrim(filepath.Join(c.PowerDir, "battery/capacity")); err == nil {
		p.Capacity, _ = strconv.Atoi(s)
	}
	if s, _ := readTrim(filepath.Join(c.PowerDir, "battery/status")); s == "Charging" || s == "Full" {
		p.Charger = true
	}
	for _, n := range []string{"ac", "usb"} {
		if s, _ := readTrim(filepath.Join(c.PowerDir, n, "online")); s == "1" {
			p.Charger = true
		}
	}
	return p
}

func (c *Config) powerOK() error {
	p := c.Battery()
	if p.Charger || p.Capacity >= 60 {
		return nil
	}
	return fmt.Errorf("battery at %d%% and no charger: connect the charger or charge to 60%% first", p.Capacity)
}

func (c *Config) bootID() string {
	s, _ := readTrim(c.BootID)
	return s
}

// Wake holds (on) or lets go of a wake lock, so the console doesn't sleep
// in the middle of a write.
func (c *Config) Wake(on bool) {
	p := c.WakeUnlk
	if on {
		p = c.WakeLock
	}
	if p != "" {
		os.WriteFile(p, []byte("andr36oid-emmcinstall"), 0)
	}
}
