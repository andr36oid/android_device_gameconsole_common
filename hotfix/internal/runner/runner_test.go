package runner

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

func prepare(t *testing.T, script string) string {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("HOTFIX_DIR", dir)
	if err := os.WriteFile(filepath.Join(dir, "hotfix.sh"), []byte(script), 0600); err != nil {
		t.Fatal(err)
	}
	lib := filepath.Join(dir, "lib.sh")
	if err := os.WriteFile(lib, []byte("hotfix_log() { echo \"$*\"; }\n"), 0600); err != nil {
		t.Fatal(err)
	}
	return lib
}

func TestResultsAndOutput(t *testing.T) {
	for _, code := range []int{0, 1, 42} {
		lib := prepare(t, fmt.Sprintf("hotfix_log test; exit %d", code))
		var b bytes.Buffer
		got := Run("/bin/sh", lib, time.Second, &b)
		if (got == 0) != (code == 0) || !strings.Contains(b.String(), "test") {
			t.Fatal(got, b.String())
		}
	}
	lib := prepare(t, "yes output | head -c 200000")
	var b bytes.Buffer
	if Run("/bin/sh", lib, time.Second, &b) != 0 || b.Len() != 65536 {
		t.Fatal(b.Len())
	}
}

func TestDeadlineKillsChildren(t *testing.T) {
	lib := prepare(t, "trap '' TERM; sleep 20 &\necho $! >child\nwait")
	var b bytes.Buffer
	start := time.Now()
	if code := Run("/bin/sh", lib, 100*time.Millisecond, &b); code != 124 {
		t.Fatal(code)
	}
	if time.Since(start) > 3*time.Second {
		t.Fatal("deadline did not bound wait")
	}
	pidBytes, err := os.ReadFile(filepath.Join(os.Getenv("HOTFIX_DIR"), "child"))
	if err != nil {
		t.Fatal(err)
	}
	pid, _ := strconv.Atoi(strings.TrimSpace(string(pidBytes)))
	// A killed child may be a zombie until the host's PID 1 reaps it.
	stat, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err == nil && !strings.Contains(string(stat), ") Z ") && syscall.Kill(pid, 0) == nil {
		t.Fatal("child survived")
	}
}

func TestCommand(t *testing.T) {
	var b bytes.Buffer
	if code := Command([]string{"/bin/sh", "-c", "echo mounted"}, time.Second, &b); code != 0 || b.String() != "mounted\n" {
		t.Fatal(code, b.String())
	}
	if code := Command([]string{"/bin/sh", "-c", "exit 32"}, time.Second, &b); code == 0 {
		t.Fatal("ignored command failure")
	}
	start := time.Now()
	if code := Command([]string{"/bin/sh", "-c", "trap '' TERM; sleep 20 & wait"}, 100*time.Millisecond, &b); code != 124 {
		t.Fatal(code)
	}
	if time.Since(start) > 3*time.Second {
		t.Fatal("command blocked past its deadline")
	}
}
