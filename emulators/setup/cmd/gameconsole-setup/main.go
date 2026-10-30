// gameconsole-setup is andr36oid's first-start and every-start setup (it was
// gameconsole-emu.sh). init starts it:
//
//	gameconsole-setup early     post-fs-data, before system_server: Daijishou's folder access
//	gameconsole-setup main      boot_completed: everything else
//	gameconsole-setup apps      the setup's app page was left with Next
//	gameconsole-setup players   Settings > Controllers changed who is player 1 in RetroArch
//	gameconsole-setup reconcile-ports  refresh just the Ports library after PortMaster
//	gameconsole-setup sync-library     merge ES/Daijishou library state after a subsystem
//	gameconsole-setup sync-retroarch   share core options and video settings after a subsystem
//	gameconsole-setup magisk    the boot animation is up: root as new as the Magisk app
//
// All but early go through one worker (package worker): a start that finds another one
// working leaves its request and exits, and the worker runs it next. So no two of them
// ever work at the same time, whatever the card's speed.
package main

import (
	"fmt"
	"os"
	"time"

	"andr36oid/gcsetup/internal/magisk"
	"andr36oid/gcsetup/internal/setup"
	"andr36oid/gcsetup/internal/worker"
)

const (
	queueDir  = "/dev/gameconsole-setup"
	queueLock = "/dev/gameconsole-setup.lock"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: gameconsole-setup early|main|apps|players|magisk|reconcile-ports|sync-library|sync-retroarch")
		os.Exit(2)
	}
	s := setup.New()
	job := os.Args[1]
	switch job {
	case "early":
		s.Early()
		return
	case "magisk":
		// the boot animation also runs while the console shuts down: only while it starts
		if s.Props.Get("sys.boot_completed") == "1" || s.Props.Get("sys.powerctl") != "" {
			return
		}
		done, err := magisk.New(s.Log, s.Props.Set).Check()
		if err != nil {
			s.Log("magisk: rebuild failed, root stays as it was: %v", err)
		}
		if done {
			// the new root only starts with a new start
			s.Props.Set("sys.powerctl", "reboot,magisk")
		}
		return
	case "main", "apps", "reconcile-ports", "sync-library", "sync-retroarch":
	case "players":
		if s.Props.Get("sys.boot_completed") != "1" {
			return
		}
		// RetroArch writes its old values back when it quits: wait for that outside the
		// worker, so nothing else waits on it
		for s.RetroArchRunning() {
			time.Sleep(5 * time.Second)
		}
	default:
		fmt.Fprintln(os.Stderr, "unknown job", job)
		os.Exit(2)
	}
	q := worker.New(queueDir, queueLock)
	if _, err := q.Serve(job, func(q *worker.Queue) { Work(s, q) }); err != nil {
		s.Log("setup worker: %v", err)
		os.Exit(1)
	}
}

// Work runs every requested job, the main one first, until none is left. Between long
// steps (and while waiting for EASYROMS) it takes the requests that came in meanwhile, so
// the app page's installs don't wait for the cores or the cheats.
func Work(s *setup.Setup, q *worker.Queue) {
	s.Prepare()
	pmUp := false
	ready := func() bool {
		if !pmUp {
			pmUp = s.WaitPackageManager()
		}
		return pmUp
	}
	inline := false
	side := func() {
		if inline {
			return
		}
		inline = true
		defer func() { inline = false }()
		for _, job := range []string{"apps", "players"} {
			if !q.Has(job) {
				continue
			}
			q.Take(job)
			if !ready() {
				continue
			}
			run(s, job)
		}
	}
	s.Poll = side
	for {
		pending := q.Pending()
		if len(pending) == 0 {
			break
		}
		if q.Has("main") {
			q.Take("main")
			if ready() {
				s.Main()
				if s.EasyromsLate() {
					s.RunEasyromsLate()
				}
			}
			continue
		}
		for _, job := range pending {
			q.Take(job)
			if ready() {
				run(s, job)
			}
		}
	}
	s.Poll = nil
}

func run(s *setup.Setup, job string) {
	switch job {
	case "apps":
		s.AppsJob()
	case "players":
		s.Players()
	case "reconcile-ports":
		s.ReconcilePorts()
	case "sync-library":
		s.SyncLibrary()
	case "sync-retroarch":
		s.SyncRetroArch()
	}
}
