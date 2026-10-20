package emmc

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
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
	Step2    string            `json:"step2,omitempty"` // written, passed, failed
	Plan     []Part            `json:"plan,omitempty"`
	Copied   map[string]string `json:"copied_sha256,omitempty"`
	CopiedLn map[string]int64  `json:"copied_bytes,omitempty"`

	// Step 3: the SD card's loader on the eMMC.
	Step3 string `json:"step3,omitempty"` // done

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

func (c *Config) statePath() string { return filepath.Join(c.StateDir, "state.json") }

// LoadState reads the state; none yet is an empty one.
func (c *Config) LoadState() (*State, error) {
	b, err := os.ReadFile(c.statePath())
	if errors.Is(err, os.ErrNotExist) {
		return &State{}, nil
	}
	if err != nil {
		return nil, err
	}
	var s State
	if err := json.Unmarshal(b, &s); err != nil {
		return nil, fmt.Errorf("state file broken: %w", err)
	}
	return &s, nil
}

// Save writes the state so that a power cut leaves the old or the new one.
func (c *Config) Save(s *State) error {
	if err := os.MkdirAll(c.StateDir, 0770); err != nil {
		return err
	}
	b, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return writeFileSync(c.statePath(), b)
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
