// Package sys is everything the setup asks of Android: commands (pm, settings, am...),
// system properties and the log. Steps only talk to Android through it, so the tests can
// swap in a fake.
package sys

import (
	"bytes"
	"context"
	"errors"
	"os"
	"os/exec"
	"strings"
	"time"
)

// Runner runs a command with stdin from /dev/null and returns its output, stdout and
// stderr together like the shell's $(cmd 2>&1).
type Runner interface {
	Run(name string, args ...string) (string, error)
}

// ExecRunner runs real commands. Every command gets a time limit: a command that hangs
// (a package manager that never answers, a dying card) must not hold the setup forever.
type ExecRunner struct {
	Timeout time.Duration
}

func (r ExecRunner) Run(name string, args ...string) (string, error) {
	timeout := r.Timeout
	if timeout == 0 {
		timeout = 10 * time.Minute
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Stdin = nil // /dev/null
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	cmd.WaitDelay = 5 * time.Second
	err := cmd.Run()
	if ctx.Err() != nil {
		err = errors.Join(err, ctx.Err())
	}
	return strings.TrimRight(out.String(), "\n"), err
}

// Props reads and writes system properties.
type Props interface {
	Get(name string) string
	Set(name, value string) error
}

// CmdProps uses getprop and setprop.
type CmdProps struct{ R Runner }

func (p CmdProps) Get(name string) string {
	out, _ := p.R.Run("getprop", name)
	return strings.TrimSpace(out)
}

func (p CmdProps) Set(name, value string) error {
	_, err := p.R.Run("setprop", name, value)
	return err
}

// Settings is Android's settings provider through the settings command. Unset reads as
// "" (the command prints "null").
type Settings struct{ R Runner }

func (s Settings) Get(namespace, key string) string {
	out, err := s.R.Run("settings", "get", namespace, key)
	out = strings.TrimSpace(out)
	if err != nil || out == "null" {
		return ""
	}
	return out
}

// GetRaw is Get without turning "null" into "": a few callers tell unset from empty.
func (s Settings) GetRaw(namespace, key string) (string, error) {
	out, err := s.R.Run("settings", "get", namespace, key)
	return strings.TrimSpace(out), err
}

func (s Settings) Put(namespace, key, value string) error {
	_, err := s.R.Run("settings", "put", namespace, key, value)
	return err
}

// Exists reports whether a path exists (any type, like test -e: a symlink counts only if
// what it points at exists).
func Exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

// IsDir is test -d.
func IsDir(path string) bool {
	st, err := os.Stat(path)
	return err == nil && st.IsDir()
}

// IsFile is test -f.
func IsFile(path string) bool {
	st, err := os.Stat(path)
	return err == nil && st.Mode().IsRegular()
}
