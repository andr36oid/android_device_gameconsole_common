package emmc

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
)

// Found is andr36oid on the eMMC that this SD card has no records of: it was
// installed from another card. Adopt takes it over.
type Found struct {
	// Datacard is the data card the eMMC's datacard.txt names ("" without one)
	Datacard string `json:"datacard,omitempty"`
	// Backup is the stock eMMC's backup on this card's storage ("" if none)
	Backup string `json:"backup,omitempty"`
}

// foundOnEmmc says whether the eMMC holds andr36oid: its BOOT has
// boot.ini-android, which no stock system has. nil without it.
func (c *Config) foundOnEmmc() (*Found, error) {
	e, err := OpenDisk(c.EmmcDev, false, c.Direct)
	if err != nil {
		return nil, err
	}
	parts, err := ReadGPT(e)
	e.Close()
	if err != nil {
		return nil, nil // no GPT: not andr36oid's layout
	}
	boot, ok := Find(parts, "BOOT")
	if !ok {
		return nil, nil
	}
	var f *Found
	err = c.fsops().mount(c, c.EmmcDev, spanOf(boot), "vfat", false, func(dir string) error {
		if _, err := os.Stat(filepath.Join(dir, "boot.ini-android")); err != nil {
			return nil
		}
		f = &Found{}
		if b, err := os.ReadFile(filepath.Join(dir, DatacardFile)); err == nil {
			f.Datacard = strings.ToLower(parseTest(b)["datacard"])
		}
		return nil
	})
	return f, err
}

// hasRecords: this card installed on (or wrote to) the eMMC it has records of.
func (st *State) hasRecords() bool {
	return st.Backup != nil || st.Step1 != "" || st.Step3 != "" || st.Custom != nil
}

// Adopt takes over andr36oid installed on the eMMC from another SD card. This
// card gets the records as if it had installed it: steps 1 to 3 done, the
// eMMC's partition table and loader, its data card, and the stock eMMC's
// backup if this card's storage has one. Then the wizard can update the eMMC
// or put the stock system back. Writes nothing to the eMMC.
func (c *Config) Adopt(p *Progress) error {
	r, err := c.begin(p, "adopt")
	if err != nil {
		return err
	}
	if r.st.hasRecords() {
		return errors.New("this SD card already has records of the eMMC")
	}
	f, err := c.foundOnEmmc()
	if err != nil {
		return err
	}
	if f == nil {
		return errors.New("the eMMC holds no andr36oid")
	}
	e, err := r.openEmmc()
	if err != nil {
		return err
	}
	parts, err := ReadGPT(e)
	if err != nil {
		e.Close()
		return err
	}
	sha, err := e.HashRange(spanLoader.off(), spanLoader.n(), nil)
	e.Close()
	if err != nil {
		return err
	}
	st := State{CID: r.info.CID, Sectors: r.info.Sectors, LoaderSHA: sha,
		Step1: "passed", Step2: "passed", Plan: parts, Step3: "done"}
	if dir := c.findBackup(nil, r.info.CID); dir != "" {
		if info, err := loadBackup(dir); err == nil {
			st.Backup = &Backup{Dir: dir, SHA: info.SHA, Sectors: info.Sectors}
			p.Logf("the stock eMMC's backup: %s", dir)
		}
	}
	if f.Datacard != "" {
		st.Datacard = &Datacard{Kind: "new", Stage: "done", UUID: f.Datacard}
		p.Logf("its data card: %s", f.Datacard)
	} else {
		st.Datacard = &Datacard{Kind: "emmc", Stage: "done"}
	}
	*r.st = st
	p.Logf("andr36oid on the eMMC, installed from another SD card, taken over")
	return c.Save(r.st)
}
