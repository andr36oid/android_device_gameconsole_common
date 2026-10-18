// Package worker makes sure only one setup process works at a time, without ever losing a
// job and without lock files that outlive their holder.
//
// Every start of the program (a boot, the setup's app page, Settings > Controllers) leaves
// a request file for its job and then tries to become the worker by taking an flock on the
// lock file. The kernel drops an flock when its holder dies, so a crash or a kill never
// leaves the lock behind, and nobody ever has to guess how long a step may take (a slow SD
// card makes every step slow). Whoever doesn't get the lock just leaves: the worker picks
// the request up. The worker looks for requests between its steps, and once more after it
// gave the lock up: a request written just before that is never lost, because either the
// worker sees it, or its writer's own try for the lock comes after the worker let go and
// succeeds.
package worker

import (
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
)

// Queue is the lock and the request folder, both on /dev (tmpfs): a restart starts clean.
type Queue struct {
	Dir  string // request files, one per job name
	Lock string // the flock file
	fd   *os.File
}

func New(dir, lock string) *Queue { return &Queue{Dir: dir, Lock: lock} }

// Request leaves a request for job. Writing it twice is the same as once.
func (q *Queue) Request(job string) error {
	if err := os.MkdirAll(q.Dir, 0o700); err != nil {
		return err
	}
	f, err := os.OpenFile(filepath.Join(q.Dir, job), os.O_WRONLY|os.O_CREATE, 0o600)
	if err != nil {
		return err
	}
	return f.Close()
}

// TryLock takes the worker lock if nobody holds it. It never waits.
func (q *Queue) TryLock() (bool, error) {
	if q.fd != nil {
		return true, nil
	}
	f, err := os.OpenFile(q.Lock, os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return false, err
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		f.Close()
		if errors.Is(err, syscall.EWOULDBLOCK) {
			return false, nil
		}
		return false, err
	}
	q.fd = f
	return true, nil
}

// Unlock gives the lock up.
func (q *Queue) Unlock() {
	if q.fd != nil {
		_ = syscall.Flock(int(q.fd.Fd()), syscall.LOCK_UN)
		q.fd.Close()
		q.fd = nil
	}
}

// Pending lists the jobs with a request, sorted.
func (q *Queue) Pending() []string {
	ents, err := os.ReadDir(q.Dir)
	if err != nil {
		return nil
	}
	var jobs []string
	for _, e := range ents {
		if !e.IsDir() && !strings.HasPrefix(e.Name(), ".") {
			jobs = append(jobs, e.Name())
		}
	}
	sort.Strings(jobs)
	return jobs
}

// Has reports whether job has a request.
func (q *Queue) Has(job string) bool {
	_, err := os.Stat(filepath.Join(q.Dir, job))
	return err == nil
}

// Take removes job's request before the job runs: a new request that comes in while it
// runs is kept and runs it again.
func (q *Queue) Take(job string) {
	_ = os.Remove(filepath.Join(q.Dir, job))
}

// Serve is the whole client and worker side: leave the request for job, then work while
// holding the lock. work runs with the lock held and must take every request it handles;
// it returns once nothing is left that it can do now. Returns whether this process did
// the work (false: another worker has it).
func (q *Queue) Serve(job string, work func(q *Queue)) (bool, error) {
	if err := q.Request(job); err != nil {
		return false, err
	}
	worked := false
	for {
		ok, err := q.TryLock()
		if err != nil {
			return worked, err
		}
		if !ok {
			return worked, nil
		}
		worked = true
		work(q)
		q.Unlock()
		if len(q.Pending()) == 0 {
			return worked, nil
		}
		// a request came in after work last looked: try to take it on
	}
}
