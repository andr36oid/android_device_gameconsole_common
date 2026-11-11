package romwatch

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func event(t *testing.T, w *Watcher) {
	t.Helper()
	select {
	case _, ok := <-w.Events():
		if !ok {
			t.Fatal("watcher stopped")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("no ROM event")
	}
}
func drain(w *Watcher) {
	for {
		select {
		case _, ok := <-w.Events():
			if !ok {
				return
			}
		default:
			return
		}
	}
}
func quiet(t *testing.T, w *Watcher) {
	t.Helper()
	select {
	case <-w.Events():
		t.Fatal("unrelated file produced ROM event")
	case <-time.After(100 * time.Millisecond):
	}
}
func TestWatchCopyMoveDeleteAndNested(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "fds")
	if err := os.Mkdir(dir, 0755); err != nil {
		t.Fatal(err)
	}
	w, err := New(root, []string{"fds"}, func(rel string) bool { return strings.HasSuffix(strings.ToLower(rel), ".fds") })
	if err != nil {
		t.Fatal(err)
	}
	defer w.Close()
	if err = os.WriteFile(filepath.Join(dir, "README.txt"), nil, 0644); err != nil {
		t.Fatal(err)
	}
	quiet(t, w)
	if err = os.WriteFile(filepath.Join(dir, "Game.FDS"), []byte("game"), 0644); err != nil {
		t.Fatal(err)
	}
	event(t, w)
	drain(w)
	if err = os.Remove(filepath.Join(dir, "Game.FDS")); err != nil {
		t.Fatal(err)
	}
	event(t, w)
	drain(w)
	if err = os.Mkdir(filepath.Join(dir, "sub"), 0755); err != nil {
		t.Fatal(err)
	}
	event(t, w)
	drain(w)
	if err = os.WriteFile(filepath.Join(dir, "sub", "New.fds"), nil, 0644); err != nil {
		t.Fatal(err)
	}
	event(t, w)
	drain(w)
	outside := t.TempDir()
	if err = os.Mkdir(filepath.Join(outside, "tree"), 0755); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(outside, "tree", "Existing.fds"), nil, 0644); err != nil {
		t.Fatal(err)
	}
	if err = os.Rename(filepath.Join(outside, "tree"), filepath.Join(dir, "moved")); err != nil {
		t.Fatal(err)
	}
	event(t, w)
	drain(w)
	if err = os.WriteFile(filepath.Join(dir, "moved", "Later.fds"), nil, 0644); err != nil {
		t.Fatal(err)
	}
	event(t, w)
}
func TestWatchHiddenSymlinkAndClose(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "fds")
	_ = os.Mkdir(dir, 0755)
	_ = os.Mkdir(filepath.Join(dir, ".hidden"), 0755)
	outside := t.TempDir()
	_ = os.Symlink(outside, filepath.Join(dir, "link"))
	w, err := New(root, []string{"fds"}, func(rel string) bool { return strings.HasSuffix(rel, ".fds") })
	if err != nil {
		t.Fatal(err)
	}
	_ = os.WriteFile(filepath.Join(dir, ".secret.fds"), nil, 0644)
	_ = os.WriteFile(filepath.Join(dir, ".hidden", "Game.fds"), nil, 0644)
	_ = os.WriteFile(filepath.Join(outside, "Elsewhere.fds"), nil, 0644)
	quiet(t, w)
	if err = w.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case _, ok := <-w.Events():
		if ok {
			t.Fatal("unexpected event")
		}
	case <-time.After(time.Second):
		t.Fatal("Close did not unblock reader")
	}
}
func TestWatchNewFolderAndRootMove(t *testing.T) {
	root := t.TempDir()
	w, err := New(root, []string{"fds"}, func(string) bool { return true })
	if err != nil {
		t.Fatal(err)
	}
	defer w.Close()
	_ = os.Mkdir(filepath.Join(root, "fds"), 0755)
	event(t, w)
	drain(w)
	moved := root + "-moved"
	if err = os.Rename(root, moved); err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(moved)
	select {
	case <-w.Events():
	case <-time.After(time.Second):
		t.Fatal("root removal not reported")
	}
}
