package setup

import (
	"os"
	"path/filepath"
)

// The existing root watcher runs this at startup and every 15 seconds, including
// with no games card mounted. A direct setprop must have the same eviction
// effect as the app toggle. The broker owns all mount/identity/operation locks;
// never unmount anything here or disturb a running guest.
func (s *Setup) evictDisabledPrestage() {
	info, err := os.Stat(filepath.Join(s.BrokerState, "session.mounts"))
	if err != nil || info.Size() == 0 {
		return
	}
	if s.Props.Get("persist.andr36oid.subsystem.prestage") == "1" ||
		s.Props.Get("sys.andr36oid.subsystem.session") == "running" {
		return
	}
	if _, err := s.R.Run("/system/bin/sh", "/system/etc/andr36oid-subsystem.sh", "unstage"); err != nil {
		s.Log("Disabled subsystem prestage: eviction deferred (busy or incomplete): %v", err)
	}
}
