package main

import (
	"fmt"
	"time"
)

// waiter is the wait for the data card: the loop main runs, with everything it
// touches (the clock, sysfs, init's properties, the power-off) replaceable, so
// the host tests can run it through the three minutes and the power-off.
type waiter struct {
	want     string // the PARTUUID as boot.ini passed it, for the log
	guidErr  error  // want didn't parse
	recovery bool
	timeout  time.Duration
	poll     time.Duration
	retry    time.Duration // between power-off attempts that didn't turn the console off

	now   func() time.Time
	sleep func(time.Duration)
	// find is the card's partition node ("" while the card isn't there) and link
	// points /dev/block/by-name/a36data at it
	find    func() (string, error)
	link    func(dev string) error
	setprop func(name, value string)
	// powerOff turns the console off. On a console it never returns once the
	// power-off happened; it returns an error when it didn't.
	powerOff func() error
	logf     func(format string, args ...any)
}

// What run ended with.
const (
	linked     = iota // the card is there and linked: init mounts /data from it
	gaveUp            // recovery, no card after the timeout: recovery stays usable
	poweredOff        // the console was turned off (only the tests see this)
)

// run waits for the card and links it. Without the card in time, the console
// is turned off (recovery: left on), because Android must not go on without
// its /data: init would try to mount a link that isn't there and the console
// would sit at the boot animation with no way to tell what is wrong.
func (w *waiter) run() int {
	if w.guidErr != nil {
		if w.recovery {
			w.logf("%v", w.guidErr)
			return gaveUp
		}
		// a broken datacard.txt: the eMMC's own userdata would be mounted instead of
		// the user's data, so better not start at all
		w.logf("%v, turning off", w.guidErr)
		w.off()
		return poweredOff
	}
	start := w.now()
	shown := -1
	for {
		dev, err := w.find()
		if err != nil {
			w.logf("looking for the card: %v", err)
		}
		if dev != "" {
			if err := w.link(dev); err != nil {
				w.logf("linking %s: %v", dev, err)
			} else {
				w.logf("data card %s found after %.1f s", dev, w.now().Sub(start).Seconds())
				if shown >= 0 {
					w.setprop(waitProp, "0")
				}
				return linked
			}
		}
		left := int((w.timeout - w.now().Sub(start) + time.Second - 1) / time.Second)
		if left <= 0 && w.recovery {
			w.logf("no data card (%s) after %v", w.want, w.timeout)
			return gaveUp
		}
		if left <= 0 {
			w.logf("no data card (%s) after %v, turning off", w.want, w.timeout)
			w.setprop(waitProp, "0")
			w.off()
			return poweredOff
		}
		// the first look is right away: a card that is in shows nothing at all
		if left != shown && w.now().Sub(start) >= w.poll && !w.recovery {
			if shown < 0 {
				w.logf("waiting for the data card %s", w.want)
			}
			shown = left
			w.setprop(waitProp, fmt.Sprint(left))
		}
		w.sleep(w.poll)
	}
}

// off turns the console off and keeps at it until it is off: returning would
// let init go on with the start, to a /data that can't be mounted.
func (w *waiter) off() {
	for {
		err := w.powerOff()
		if err == nil {
			return
		}
		w.logf("the console didn't turn off (%v), trying again in %v", err, w.retry)
		w.sleep(w.retry)
	}
}
