package worker

import (
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func newQueue(t *testing.T) (string, string) {
	d := t.TempDir()
	return d + "/requests", d + "/lock"
}

// Only one worker at a time, and every request runs, when many starts race.
func TestOneWorkerNoLostRequests(t *testing.T) {
	dir, lock := newQueue(t)
	var running, maxRunning, runs int32
	var mu sync.Mutex
	ran := map[string]int{}
	work := func(q *Queue) {
		n := atomic.AddInt32(&running, 1)
		for {
			m := atomic.LoadInt32(&maxRunning)
			if n <= m || atomic.CompareAndSwapInt32(&maxRunning, m, n) {
				break
			}
		}
		for {
			p := q.Pending()
			if len(p) == 0 {
				break
			}
			for _, j := range p {
				q.Take(j)
				time.Sleep(time.Millisecond)
				mu.Lock()
				ran[j]++
				mu.Unlock()
				atomic.AddInt32(&runs, 1)
			}
		}
		atomic.AddInt32(&running, -1)
	}
	var wg sync.WaitGroup
	jobs := []string{"main", "apps", "players"}
	for i := 0; i < 60; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			q := New(dir, lock)
			if _, err := q.Serve(jobs[i%3], work); err != nil {
				t.Error(err)
			}
		}(i)
	}
	wg.Wait()
	if maxRunning != 1 {
		t.Fatalf("%d workers at once", maxRunning)
	}
	for _, j := range jobs {
		if ran[j] == 0 {
			t.Fatalf("%s never ran", j)
		}
	}
	if p := New(dir, lock).Pending(); len(p) != 0 {
		t.Fatalf("left pending: %v", p)
	}
}

// A request made while the worker runs is taken by that worker (the second start just
// leaves).
func TestRequestWhileWorking(t *testing.T) {
	dir, lock := newQueue(t)
	started := make(chan struct{})
	release := make(chan struct{})
	var ran []string
	var mu sync.Mutex
	work := func(q *Queue) {
		for {
			p := q.Pending()
			if len(p) == 0 {
				return
			}
			for _, j := range p {
				q.Take(j)
				mu.Lock()
				ran = append(ran, j)
				first := len(ran) == 1
				mu.Unlock()
				if first {
					close(started)
					<-release
				}
			}
		}
	}
	done := make(chan bool)
	go func() {
		worked, _ := New(dir, lock).Serve("main", work)
		done <- worked
	}()
	<-started
	worked, err := New(dir, lock).Serve("apps", work)
	if err != nil || worked {
		t.Fatalf("second start worked=%v err=%v", worked, err)
	}
	close(release)
	if !<-done {
		t.Fatal("first start didn't work")
	}
	if strings.Join(ran, ",") != "main,apps" {
		t.Fatalf("ran %v", ran)
	}
}

// The kernel drops the lock of a worker that dies: no stale lock.
func TestDeadWorkerFreesLock(t *testing.T) {
	if os.Getenv("WORKER_CHILD") != "" {
		q := New(os.Getenv("WORKER_DIR"), os.Getenv("WORKER_LOCK"))
		if ok, _ := q.TryLock(); !ok {
			os.Exit(3)
		}
		os.WriteFile(os.Getenv("WORKER_DIR")+"/../locked", nil, 0o600)
		time.Sleep(time.Hour)
	}
	dir, lock := newQueue(t)
	must := func(err error) {
		if err != nil {
			t.Fatal(err)
		}
	}
	must(os.MkdirAll(dir, 0o700))
	cmd := exec.Command(os.Args[0], "-test.run", "TestDeadWorkerFreesLock")
	cmd.Env = append(os.Environ(), "WORKER_CHILD=1", "WORKER_DIR="+dir, "WORKER_LOCK="+lock)
	must(cmd.Start())
	locked := filepath.Dir(dir) + "/locked"
	for i := 0; ; i++ {
		if _, err := os.Stat(locked); err == nil {
			break
		}
		if i > 500 {
			t.Fatal("child never locked")
		}
		time.Sleep(10 * time.Millisecond)
	}
	q := New(dir, lock)
	if ok, _ := q.TryLock(); ok {
		t.Fatal("lock taken while the child holds it")
	}
	must(cmd.Process.Kill())
	_ = cmd.Wait()
	if ok, err := q.TryLock(); !ok {
		t.Fatalf("lock not free after the holder died: %v (pid %s)", err, strconv.Itoa(cmd.Process.Pid))
	}
	q.Unlock()
}
