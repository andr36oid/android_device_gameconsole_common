package setup

import (
	"context"
	"os"
	"testing"
	"time"
)

func TestDaijishouWatchDebouncesAndCancels(t *testing.T) {
	c := newDJ(t, "", "")
	write(t, c.s.Mounts, "/dev/block/vold/public:179,7 "+c.s.easyroms+" exfat rw 0 0\n")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	requests := make(chan struct{}, 10)
	done := make(chan struct{})
	go func() { c.s.WatchPlatforms(ctx, func() { requests <- struct{}{} }); close(done) }()
	next := func() {
		t.Helper()
		select {
		case <-requests:
		case <-time.After(6 * time.Second):
			t.Fatal("rescan not dispatched")
		}
	}
	next() // initial mounted-card discovery
	for i := 0; i < 4; i++ {
		write(t, c.s.easyroms+"/fds/Game.fds", "changed")
		time.Sleep(40 * time.Millisecond)
	}
	next()
	select {
	case <-requests:
		t.Fatal("burst caused repeated rescans")
	case <-time.After(200 * time.Millisecond):
	}
	write(t, c.s.easyroms+"/fds/README.txt", "not a game")
	select {
	case <-requests:
		t.Fatal("README caused rescan")
	case <-time.After(200 * time.Millisecond):
	}
	must(t, os.Remove(c.s.easyroms+"/fds/Game.fds"))
	next()
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("watcher did not cancel")
	}
}
