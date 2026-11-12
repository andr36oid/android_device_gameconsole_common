package setup

import (
	"context"
	"errors"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

// Optional cross-language event parity fixture. It runs the ORIGINAL Go loop,
// with only Android properties and the mount worker replaced by fixture files.
type watchFixture struct{ root string }

func (f watchFixture) Get(key string) string {
	b, _ := os.ReadFile(filepath.Join(f.root, "properties", key))
	return strings.TrimSpace(string(b))
}
func (f watchFixture) Set(string, string) error {
	return errors.New("reference must not set properties")
}
func (f watchFixture) append(line string) {
	p, err := os.OpenFile(filepath.Join(f.root, "events"), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0600)
	if err == nil {
		_, _ = p.WriteString(line + "\n")
		_ = p.Close()
	}
}
func (f watchFixture) Run(name string, args ...string) (string, error) {
	if name == "/system/bin/sh" && len(args) == 2 && args[1] == "unstage" {
		f.append("unstage")
		if _, err := os.Stat(filepath.Join(f.root, "busy")); err == nil {
			return "", errors.New("busy")
		}
		return "", os.Remove(filepath.Join(f.root, "data/local/tmp/portmaster/session.mounts"))
	}
	return "", errors.New("unexpected reference command")
}
func TestOriginalGoWatcherReferenceProcess(t *testing.T) {
	root := os.Getenv("A36_WATCH_REFERENCE_ROOT")
	if root == "" {
		t.Skip("event parity subprocess only")
	}
	f := watchFixture{root}
	s := &Setup{Paths: Paths{Base: root + "/system/etc/gameconsole", State: root + "/data/misc/gameconsole", Mounts: root + "/proc/mounts", MediaRW: root + "/mnt/media_rw", BrokerState: root + "/data/local/tmp/portmaster"}, Props: f, R: f, Now: time.Now}
	config, err := os.Create(root + "/watch-config")
	must(t, err)
	must(t, s.NativeWatchConfig(config))
	must(t, config.Close())
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	defer cancel()
	s.WatchPlatforms(ctx, func() { f.append("scan") })
}
