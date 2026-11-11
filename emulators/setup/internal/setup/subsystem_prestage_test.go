package setup

import (
	"errors"
	"path/filepath"
	"testing"
)

func TestDirectPrestageDisableDelegatesAndRetries(t *testing.T) {
	c := newDJ(t, "", "")
	c.s.BrokerState = t.TempDir()
	const command = "/system/bin/sh /system/etc/andr36oid-subsystem.sh unstage"
	c.f.Set("persist.andr36oid.subsystem.prestage", "0")
	c.s.evictDisabledPrestage()
	if c.f.count(command) != 0 {
		t.Fatal("idle without mounts dispatched eviction")
	}
	write(t, filepath.Join(c.s.BrokerState, "session.mounts"), "/data/local/tmp/darkfull\n")
	c.f.Set("sys.andr36oid.subsystem.session", "running")
	c.s.evictDisabledPrestage()
	if c.f.count(command) != 0 {
		t.Fatal("active session dispatched eviction")
	}
	c.f.Set("sys.andr36oid.subsystem.session", "ended")
	c.f.Set("persist.andr36oid.subsystem.prestage", "1")
	c.s.evictDisabledPrestage()
	if c.f.count(command) != 0 {
		t.Fatal("enabled setting evicted")
	}
	c.f.Set("persist.andr36oid.subsystem.prestage", "0")
	c.f.handlers["/system/bin/sh"] = func([]string) (string, error) { return "", errors.New("busy") }
	c.s.evictDisabledPrestage()
	if c.f.count(command) != 1 {
		t.Fatal("direct property disable did not delegate")
	}
	c.s.evictDisabledPrestage()
	if c.f.count(command) != 2 {
		t.Fatal("failed busy eviction was not retried")
	}
}
