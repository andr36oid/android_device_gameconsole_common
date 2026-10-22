package emmc

import (
	"fmt"
	"path/filepath"
	"sort"
)

// ForRecovery keeps the rescue independent of Android's userdata and records.
// Restore still checks the start device, mounts, CID and every backup hash.
func (c *Config) ForRecovery() *Config {
	r := *c
	r.StateDir = "/tmp/andr36oid-emmcinstall"
	r.BootMnt = filepath.Join(r.StateDir, "boot")
	return &r
}

// RecoveryInfo is the small read-only inspection recovery needs. Finding a
// backup checks its CID and file sizes; Restore checks its hashes before writing.
type RecoveryInfo struct {
	Emmc       *EmmcInfo `json:"emmc"`
	BootedFrom string    `json:"booted_from"`
	Backup     string    `json:"backup"`
	Problem    string    `json:"problem,omitempty"`
}

func (c *Config) InspectRecovery(roots []string) (*RecoveryInfo, error) {
	info, err := c.Emmc()
	if err != nil {
		return nil, err
	}
	in := &RecoveryInfo{Emmc: info, BootedFrom: c.BootedFrom()}
	if err := c.needSD(); err != nil {
		in.Problem = err.Error()
	} else if err := c.emmcUnused(); err != nil {
		in.Problem = err.Error()
	}
	var found []string
	for _, root := range roots {
		search := *c
		search.BackupGlob = filepath.Join(root, "eMMC backup", "*", "stock-*")
		if dir := search.findBackup(nil, info.CID); dir != "" {
			b, err := loadBackup(dir)
			if err == nil && b.Sectors == info.Sectors {
				found = append(found, dir)
			}
		}
	}
	// Each stock folder is named for its time, regardless of its mount point.
	sort.Slice(found, func(i, j int) bool {
		return filepath.Base(found[i]) > filepath.Base(found[j])
	})
	if len(found) > 0 {
		in.Backup = found[0]
	}
	return in, nil
}

// RestoreRecovery also binds the confirmation to the chip the menu inspected.
func (c *Config) RestoreRecovery(p *Progress, cid, dir string) error {
	info, err := c.Emmc()
	if err != nil {
		return err
	}
	if cid != info.CID {
		return fmt.Errorf("the backup is of another eMMC (CID %s)", cid)
	}
	return c.Restore(p, dir)
}
