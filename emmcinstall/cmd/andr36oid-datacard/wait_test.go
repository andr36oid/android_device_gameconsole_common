package main

import (
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"andr36oid/emmcinstall/internal/datacard"
)

// fakeConsole runs the waiter against a clock that only moves when it sleeps.
type fakeConsole struct {
	t       *testing.T
	clock   time.Time
	slept   time.Duration
	dev     string // what find returns
	findAt  time.Duration
	linked  []string
	props   []string // "name=value" in order
	offs    int      // power-off attempts
	offFail int      // how many of them don't turn the console off
	log     []string
}

func newConsole(t *testing.T) *fakeConsole {
	return &fakeConsole{t: t, clock: time.Date(2026, 11, 6, 8, 0, 0, 0, time.UTC)}
}

func (f *fakeConsole) waiter(recovery bool, want string) *waiter {
	_, err := datacard.GUIDBytes(want)
	return &waiter{
		want:     want,
		guidErr:  err,
		recovery: recovery,
		timeout:  timeout,
		poll:     pollEvery,
		retry:    retryOff,
		now:      func() time.Time { return f.clock },
		sleep: func(d time.Duration) {
			f.clock = f.clock.Add(d)
			f.slept += d
		},
		find: func() (string, error) {
			if f.dev != "" && f.slept >= f.findAt {
				return f.dev, nil
			}
			return "", nil
		},
		link: func(dev string) error {
			f.linked = append(f.linked, dev)
			return nil
		},
		setprop: func(name, value string) { f.props = append(f.props, name+"="+value) },
		powerOff: func() error {
			f.offs++
			if f.offs <= f.offFail {
				return errors.New("still on")
			}
			return nil
		},
		logf: func(format string, args ...any) { f.log = append(f.log, fmt.Sprintf(format, args...)) },
	}
}

func (f *fakeConsole) logHas(sub string) bool {
	for _, l := range f.log {
		if strings.Contains(l, sub) {
			return true
		}
	}
	return false
}

const cardUUID = "0f3d7c6a-1b2c-4d5e-8f90-123456789abc"

func TestCardFoundLater(t *testing.T) {
	f := newConsole(t)
	f.dev, f.findAt = "/dev/block/mmcblk1p6", 4*time.Second
	w := f.waiter(false, cardUUID)
	if got := w.run(); got != linked {
		t.Fatalf("run = %d, want linked", got)
	}
	if len(f.linked) != 1 || f.linked[0] != f.dev {
		t.Fatalf("linked %v", f.linked)
	}
	if f.offs != 0 {
		t.Fatal("turned off with the card in")
	}
	// the countdown: not at the first look, then every second, 180 down, and 0
	// once the card is there
	if len(f.props) < 4 || f.props[0] != waitProp+"=180" || f.props[len(f.props)-1] != waitProp+"=0" {
		t.Fatalf("countdown %v", f.props)
	}
	for i := 1; i < len(f.props)-1; i++ {
		if f.props[i] != fmt.Sprintf("%s=%d", waitProp, 180-i) {
			t.Fatalf("countdown %v", f.props)
		}
	}
	if !f.logHas("waiting for the data card "+cardUUID) || !f.logHas("found after 4.0 s") {
		t.Fatalf("log %v", f.log)
	}
}

func TestCardInFromTheStart(t *testing.T) {
	f := newConsole(t)
	f.dev = "/dev/block/mmcblk0p6"
	if got := f.waiter(false, cardUUID).run(); got != linked {
		t.Fatalf("run = %d", got)
	}
	// nothing shown for a card that is in: the boot animation stays as it is
	if len(f.props) != 0 || f.slept != 0 {
		t.Fatalf("props %v, slept %v", f.props, f.slept)
	}
}

func TestNoCardTurnsOff(t *testing.T) {
	f := newConsole(t)
	f.offFail = 2 // init doesn't act, the first direct power-off fails too
	w := f.waiter(false, cardUUID)
	if got := w.run(); got != poweredOff {
		t.Fatalf("run = %d, want poweredOff", got)
	}
	if f.offs != 3 {
		t.Fatalf("%d power-off attempts, want 3 (two that didn't work, then one)", f.offs)
	}
	if len(f.linked) != 0 {
		t.Fatal("linked without a card")
	}
	// the countdown ran down to 1 and the power-off was announced with 0
	if f.props[0] != waitProp+"=180" || f.props[len(f.props)-2] != waitProp+"=1" || f.props[len(f.props)-1] != waitProp+"=0" {
		t.Fatalf("countdown %v ... %v", f.props[:3], f.props[len(f.props)-3:])
	}
	if !f.logHas("no data card ("+cardUUID+") after 3m0s, turning off") || !f.logHas("didn't turn off (still on), trying again in 10s") {
		t.Fatalf("log %v", f.log)
	}
	// the three minutes of looks, then the two retries
	if want := timeout + 2*retryOff; f.slept < want || f.slept > want+pollEvery {
		t.Fatalf("slept %v, want about %v", f.slept, want)
	}
}

func TestRecoveryGivesUp(t *testing.T) {
	f := newConsole(t)
	w := f.waiter(true, cardUUID)
	if got := w.run(); got != gaveUp {
		t.Fatalf("run = %d, want gaveUp", got)
	}
	if f.offs != 0 || len(f.props) != 0 {
		t.Fatalf("recovery turned off (%d) or showed a countdown (%v)", f.offs, f.props)
	}
	if !f.logHas("no data card ("+cardUUID+") after 3m0s") || f.logHas("turning off") {
		t.Fatalf("log %v", f.log)
	}
	// with the card, recovery links it like the system does
	f = newConsole(t)
	f.dev, f.findAt = "/dev/block/mmcblk1p6", time.Second
	if got := f.waiter(true, cardUUID).run(); got != linked || len(f.linked) != 1 || len(f.props) != 0 {
		t.Fatalf("recovery with the card: %d, linked %v, props %v", got, f.linked, f.props)
	}
}

func TestBrokenDatacardFile(t *testing.T) {
	// the system doesn't start on the eMMC's own userdata in place of the user's
	// data: it turns off
	f := newConsole(t)
	f.dev = "/dev/block/mmcblk1p6"
	if got := f.waiter(false, "not-a-guid").run(); got != poweredOff || f.offs != 1 || len(f.linked) != 0 {
		t.Fatalf("broken GUID: %d, offs %d, linked %v", got, f.offs, f.linked)
	}
	if !f.logHas(`bad GUID "not-a-guid", turning off`) {
		t.Fatalf("log %v", f.log)
	}
	// recovery stays on
	f = newConsole(t)
	if got := f.waiter(true, "not-a-guid").run(); got != gaveUp || f.offs != 0 {
		t.Fatalf("broken GUID in recovery: %d, offs %d", got, f.offs)
	}
}
