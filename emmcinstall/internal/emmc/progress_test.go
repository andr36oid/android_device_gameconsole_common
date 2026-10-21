package emmc

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The last run's progress goes to past/, the new run starts empty, and only the
// newest pastRuns files are kept.
func TestProgressArchivesLastRun(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "progress.jsonl")
	p, err := NewProgress(path, "restore")
	if err != nil {
		t.Fatal(err)
	}
	p.Logf("blank the loader")
	b, _ := os.ReadFile(path)
	if !strings.Contains(string(b), `"step":"restore"`) {
		t.Fatalf("no restore event: %s", b)
	}
	if _, err := NewProgress(path, "backup"); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(path); len(b) != 0 {
		t.Fatalf("new run's file isn't empty: %s", b)
	}
	past, _ := os.ReadDir(filepath.Join(dir, "past"))
	if len(past) != 1 || !strings.HasSuffix(past[0].Name(), "-restore.jsonl") {
		t.Fatalf("past: %v", past)
	}
	// empty files aren't archived; many runs keep only the newest pastRuns
	for i := 0; i < pastRuns+5; i++ {
		os.WriteFile(filepath.Join(dir, "past", strings.Repeat("0", 3)+string(rune('a'+i))+"-x.jsonl"), []byte("{}\n"), 0644)
	}
	q, _ := NewProgress(path, "backup")
	q.Logf("x")
	if _, err := NewProgress(path, "blank-loader"); err != nil {
		t.Fatal(err)
	}
	past, _ = os.ReadDir(filepath.Join(dir, "past"))
	if len(past) != pastRuns {
		t.Fatalf("kept %d, want %d", len(past), pastRuns)
	}
	if !strings.HasSuffix(past[len(past)-1].Name(), "-backup.jsonl") {
		t.Fatalf("newest isn't the backup run: %s", past[len(past)-1].Name())
	}
}
