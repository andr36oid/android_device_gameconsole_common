package main

import (
	"testing"

	"andr36oid/gcsetup/internal/worker"
)

func newTestQueue(t *testing.T) *worker.Queue {
	t.Helper()
	d := t.TempDir()
	return worker.New(d+"/requests", d+"/lock")
}

// drain and sidePoll must take a request only once the package manager is up, so a
// request made during a still-degraded boot stays pending for a later retry instead of
// being taken and silently dropped.
func TestWorkTakesOnlyWhenReady(t *testing.T) {
	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}

	t.Run("main left pending while pm is down", func(t *testing.T) {
		q := newTestQueue(t)
		must(q.Request("main"))
		var mains, jobs int
		drain(q, func() bool { return false }, func() { mains++ }, func(string) { jobs++ })
		if mains != 0 || jobs != 0 {
			t.Fatalf("ran work while the package manager was down: mains=%d jobs=%d", mains, jobs)
		}
		if !q.Has("main") {
			t.Fatal("main request dropped while the package manager was down")
		}
		// Once it comes up, the run happens and the request is taken.
		drain(q, func() bool { return true }, func() { mains++ }, func(string) { jobs++ })
		if mains != 1 || q.Has("main") {
			t.Fatalf("ready main not run/taken: mains=%d pending=%v", mains, q.Pending())
		}
	})

	t.Run("job left pending while pm is down", func(t *testing.T) {
		q := newTestQueue(t)
		must(q.Request("reconcile-ports"))
		var ran []string
		drain(q, func() bool { return false }, func() { ran = append(ran, "main") }, func(j string) { ran = append(ran, j) })
		if len(ran) != 0 || !q.Has("reconcile-ports") {
			t.Fatalf("not-ready job ran or dropped: ran=%v pending=%v", ran, q.Pending())
		}
		drain(q, func() bool { return true }, func() { ran = append(ran, "main") }, func(j string) { ran = append(ran, j) })
		if len(ran) != 1 || ran[0] != "reconcile-ports" || q.Has("reconcile-ports") {
			t.Fatalf("ready job not run/taken: ran=%v pending=%v", ran, q.Pending())
		}
	})

	t.Run("sidePoll leaves apps/players while pm is down", func(t *testing.T) {
		q := newTestQueue(t)
		must(q.Request("apps"))
		var ran []string
		sidePoll(q, func() bool { return false }, func(j string) { ran = append(ran, j) })
		if len(ran) != 0 || !q.Has("apps") {
			t.Fatalf("not-ready side job ran or dropped: ran=%v pending=%v", ran, q.Pending())
		}
		sidePoll(q, func() bool { return true }, func(j string) { ran = append(ran, j) })
		if len(ran) != 1 || ran[0] != "apps" || q.Has("apps") {
			t.Fatalf("ready side job not run/taken: ran=%v pending=%v", ran, q.Pending())
		}
	})
}
