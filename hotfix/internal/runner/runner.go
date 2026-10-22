// Package runner bounds script time and output and reaps its process group.
package runner

import (
	"io"
	"os"
	"os/exec"
	"sync"
	"syscall"
	"time"
)

type output struct {
	sync.Mutex
	w    io.Writer
	left int
}

func (w *output) Write(b []byte) (int, error) {
	w.Lock()
	defer w.Unlock()
	n := len(b)
	if len(b) > w.left {
		b = b[:w.left]
	}
	w.left -= len(b)
	_, err := w.w.Write(b)
	return n, err
}

// Run keeps init's mount namespace. A separate process group lets the deadline
// stop children too; Android 11's toybox timeout signals only its direct child.
func Run(shell, lib string, limit time.Duration, w io.Writer) int {
	cmd := exec.Command(shell, "-c", `. "$1"; cd "$HOTFIX_DIR" || exit 1; . ./hotfix.sh`, "hotfix", lib)
	return run(cmd, limit, w, nil)
}

// Command also bounds waiting after KILL, including a mount stuck in kernel I/O.
func Command(args []string, limit time.Duration, w io.Writer) int {
	return run(exec.Command(args[0], args[1:]...), limit, w, os.Stderr)
}

func run(cmd *exec.Cmd, limit time.Duration, w, stderr io.Writer) int {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	out := &output{w: w, left: 65536}
	cmd.Stdout, cmd.Stderr = out, out
	if stderr != nil {
		cmd.Stderr = &output{w: stderr, left: 65536}
	}
	cmd.WaitDelay = time.Second
	if cmd.Start() != nil {
		return 1
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	timer := time.NewTimer(limit)
	defer timer.Stop()
	select {
	case err := <-done:
		syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		if err != nil {
			return 1
		}
		return 0
	case <-timer.C:
		syscall.Kill(-cmd.Process.Pid, syscall.SIGTERM)
		// Kill the whole group even if the shell exits on TERM first.
		time.Sleep(time.Second)
		syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		// A child stuck in kernel I/O must not keep init waiting after KILL.
		select {
		case <-done:
		case <-time.After(100 * time.Millisecond):
		}
		return 124
	}
}
