package setup

import (
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"os"
	"strings"

	"andr36oid/gcsetup/internal/fsx"
)

// Only TV-channel publication is suppressed. Playback, controllers, widgets,
// install/migration receivers and user-requested background work remain intact.
var consoleBootPolicies = []struct{ name, pkg, receiver string }{
	{"vlc", "org.videolan.vlc", "org.videolan.vlc.TvReceiver"},
	{"mupen", "org.mupen64plusae.v3.alpha", "paulscode.android.mupen64plusae.task.SyncProgramsJobService$StartupIntentReceiver"},
}

type componentOverrides struct {
	XMLName  xml.Name `xml:"package-restrictions"`
	Packages []struct {
		Name    string `xml:"name,attr"`
		Enabled []struct {
			Name string `xml:"name,attr"`
		} `xml:"enabled-components>item"`
		Disabled []struct {
			Name string `xml:"name,attr"`
		} `xml:"disabled-components>item"`
	} `xml:"pkg"`
}

func (p componentOverrides) state(pkg, component string) (int, bool) {
	for _, item := range p.Packages {
		if item.Name != pkg {
			continue
		}
		for _, v := range item.Disabled {
			if v.Name == component {
				return 2, true
			}
		}
		for _, v := range item.Enabled {
			if v.Name == component {
				return 1, true
			}
		}
		return 0, true
	}
	return 0, false
}

// The previous override is saved before asking PM to change anything. A crash
// before/after the binder call can be retried without losing the opt-out state.
type bootPolicyRecord struct {
	Version   int    `json:"version"`
	Component string `json:"component"`
	Previous  int    `json:"previous"`
}

func (s *Setup) syncBootPolicyDir() error {
	d, err := os.Open(s.State)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}

// ApplyBootPolicies runs after installs, both at boot and from the wizard. PM
// persists these component overrides, so subsequent boots do not start either
// app. The first upgraded boot may already have delivered BOOT_COMPLETED.
// Set persist.andr36oid.ram.boot_vlc/mupen=0 and reboot to restore exactly the
// previous component state. These packages have separate UIDs; never use pm's
// kill-capable shell command on our shared-system-UID helpers.
func (s *Setup) ApplyBootPolicies() {
	if s.PackageRestrictions == "" {
		return
	}
	b, err := os.ReadFile(s.PackageRestrictions)
	var overrides componentOverrides
	if err == nil {
		err = xml.Unmarshal(b, &overrides)
	}
	if err != nil {
		s.Log("boot policies: package state unavailable: %v", err)
		return
	}
	for _, policy := range consoleBootPolicies {
		component := policy.pkg + "/" + policy.receiver
		state, installed := overrides.state(policy.pkg, policy.receiver)
		if !installed {
			continue
		} // no override for a future/optional package
		marker := s.state("boot-policy-" + policy.name + ".json")
		var record bootPolicyRecord
		data, readErr := os.ReadFile(marker)
		owned := readErr == nil
		if owned {
			if json.Unmarshal(data, &record) != nil || record.Version != 1 ||
				record.Component != component || record.Previous < 0 || record.Previous > 1 {
				s.Log("boot policies: invalid saved state for %s, leaving unchanged", policy.name)
				continue
			}
		} else if !errors.Is(readErr, os.ErrNotExist) {
			s.Log("boot policies: cannot read %s: %v", policy.name, readErr)
			continue
		}
		managed := s.Props.Get("persist.andr36oid.ram.boot_"+policy.name) != "0"
		if !managed && !owned {
			continue
		}
		if managed && !owned {
			if state == 2 {
				continue
			} // already disabled by the user: do not take ownership
			record = bootPolicyRecord{1, component, state}
			data, _ = json.Marshal(record)
			if err := fsx.WriteFileAtomic(marker, data); err != nil {
				s.Log("boot policies: save %s: %v", policy.name, err)
				continue
			}
			if err := s.syncBootPolicyDir(); err != nil {
				s.Log("boot policies: sync %s: %v", policy.name, err)
				continue
			}
		}
		wanted, verb := 2, "disable"
		if !managed {
			wanted, verb = record.Previous, "default-state"
			if wanted == 1 {
				verb = "enable"
			}
		}
		if state != wanted {
			out, err := s.R.Run("pm", verb, "--user", "0", component)
			// Some PM shell failures print Error while returning status zero.
			if err != nil || strings.Contains(out, "Error") || strings.Contains(out, "Exception") {
				s.Log("boot policies: %s %s failed: %v %s", verb, policy.name, err, out)
				continue
			}
			s.Log("boot policies: %s %s", verb, policy.name)
		}
		if !managed {
			if err := os.Remove(marker); err != nil {
				s.Log("boot policies: %v", err)
				continue
			}
			if err := s.syncBootPolicyDir(); err != nil {
				s.Log("boot policies: %v", fmt.Errorf("sync restore: %w", err))
			}
		}
	}
}
