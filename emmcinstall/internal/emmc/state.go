package emmc

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// State is what the installer has done on this console, kept on the SD card's
// userdata, written before and after every write to the eMMC.
type State struct {
	CID     string `json:"cid,omitempty"`
	Sectors int64  `json:"sectors,omitempty"`

	Backup *Backup `json:"backup,omitempty"`

	// Step 1: the eMMC's loader area blanked, so the console starts from the
	// SD card's loader alone. LoaderSHA is the SD card's loader area then.
	LoaderSHA   string `json:"loader_sha256,omitempty"`
	Step1       string `json:"step1,omitempty"` // blanked, passed
	Step1BootID string `json:"step1_boot_id,omitempty"`

	// Step 2: andr36oid copied to the eMMC and started from there once.
	Step2    string            `json:"step2,omitempty"` // written, deferred, passed, failed
	Plan     []Part            `json:"plan,omitempty"`
	Copied   map[string]string `json:"copied_sha256,omitempty"`
	CopiedLn map[string]int64  `json:"copied_bytes,omitempty"`

	// Step 3: the SD card's loader on the eMMC.
	Step3 string `json:"step3,omitempty"` // done

	// Custom is an SD card image the advanced mode wrote in place of
	// andr36oid (the steps above are then all clear).
	Custom *CustomImage `json:"custom,omitempty"`

	// Incomplete is an update or restore that stopped or was cut off and was
	// then left as it is: the eMMC is half old, half new until that command
	// runs again and finishes.
	Incomplete string `json:"incomplete,omitempty"` // update-emmc, update-uboot, restore, write-image

	// Running is the write under way: still set at the next start means it
	// was cut off (power lost).
	Running string `json:"running,omitempty"`
	Failed  string `json:"failed,omitempty"`
}

// Backup is the stock eMMC's copy.
type Backup struct {
	Dir      string `json:"dir"`
	SHA      string `json:"sha256"`
	Sectors  int64  `json:"sectors"`
	Verified bool   `json:"verified"`
}

// The records are kept per eMMC, in state-<CID>.json, so one SD card can
// install on several consoles: each console's eMMC only ever sees its own.

// stateCID is the built-in eMMC's CID, which names its records.
func (c *Config) stateCID() (string, error) {
	cid, err := readTrim(filepath.Join(c.EmmcSys, "device/cid"))
	if err != nil || len(cid) != 32 {
		return "", fmt.Errorf("built-in eMMC: can't read its CID (%q), so there are no records for it", cid)
	}
	return strings.ToLower(cid), nil
}

func (c *Config) statePath(cid string) string {
	return filepath.Join(c.StateDir, "state-"+cid+".json")
}

// legacyStatePath is where the records were before they were kept per eMMC.
func (c *Config) legacyStatePath() string { return filepath.Join(c.StateDir, "state.json") }

func readState(path string) (*State, error) {
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var s State
	if err := json.Unmarshal(b, &s); err != nil {
		return nil, fmt.Errorf("state file %s broken: %w", filepath.Base(path), err)
	}
	return &s, nil
}

// LoadState reads this eMMC's records; none yet is an empty one. Records from
// before they were kept per eMMC are taken over when they are this eMMC's.
func (c *Config) LoadState() (*State, error) {
	cid, err := c.stateCID()
	if err != nil {
		return nil, err
	}
	st, err := readState(c.statePath(cid))
	if st != nil || err != nil {
		return st, err
	}
	old, err := readState(c.legacyStatePath())
	if err != nil || old == nil || old.CID != cid {
		// another console's, or broken: left alone
		return &State{}, nil
	}
	if err := c.Save(old); err != nil {
		return nil, err
	}
	if err := os.Rename(c.legacyStatePath(), c.legacyStatePath()+".migrated"); err != nil {
		return nil, err
	}
	return old, nil
}

// Save writes this eMMC's records so that a power cut leaves the old or the
// new ones.
func (c *Config) Save(s *State) error {
	cid, err := c.stateCID()
	if err != nil {
		return err
	}
	if s.CID != "" && s.CID != cid {
		return fmt.Errorf("records of eMMC %s not saved over those of eMMC %s", s.CID, cid)
	}
	if err := os.MkdirAll(c.StateDir, 0770); err != nil {
		return err
	}
	b, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return writeFileSync(c.statePath(cid), b)
}

func writeFileSync(path string, b []byte) error {
	tmp := path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0644)
	if err != nil {
		return err
	}
	if _, err := f.Write(b); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		return err
	}
	return syncDir(filepath.Dir(path))
}

func syncDir(dir string) error {
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}
