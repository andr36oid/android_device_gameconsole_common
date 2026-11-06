// andr36oid-datacard: an eMMC install that keeps Android's data on an SD card waits
// here for that card before /data is mounted.
//
// init.rk30board.rc runs it (exec, so init waits) on late-fs, just before
// mount_all --late. Without androidboot.datacard (ro.boot.datacard) it exits at once.
// With it, it looks at both card slots twice a second for the partition with that GPT
// unique GUID and points /dev/block/by-name/a36data at it, which fstab.emmcsd mounts
// as /data. While the card is missing, sys.andr36oid.datacard_wait holds the seconds
// left, and the boot animation shows "Waiting for storage SD card ..." with them.
// After 3 minutes the console turns off: nothing is mounted writable yet. It never
// exits without the card: init would go on to a /data it can't mount, and the console
// would sit at the boot animation with nothing to say why.
//
// With --recovery (init.recovery.common.rc) it only links the card: recovery has no
// boot animation, and without the card it gives up after the 3 minutes and leaves
// the console on, so recovery stays usable.
//
// The wait itself is waiter (wait.go), which the host tests run with a fake clock.
package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"os/exec"
	"strings"
	"syscall"
	"time"

	"andr36oid/emmcinstall/internal/datacard"
)

const (
	link      = "/dev/block/by-name/a36data"
	waitProp  = "sys.andr36oid.datacard_wait"
	timeout   = 3 * time.Minute
	pollEvery = 500 * time.Millisecond
	// retryOff is between power-off attempts that didn't turn the console off
	retryOff = 10 * time.Second
	// initOff is how long init gets for sys.powerctl before the power-off is
	// done here directly
	initOff = 20 * time.Second
	// propTimeout bounds a setprop: init's property service must not keep the
	// power-off from happening
	propTimeout = 5 * time.Second
)

func main() {
	log.SetFlags(0)
	log.SetPrefix("andr36oid-datacard: ")
	if w, err := os.OpenFile("/dev/kmsg", os.O_WRONLY, 0); err == nil {
		log.SetOutput(w)
	}
	want := strings.TrimSpace(getprop("ro.boot.datacard"))
	if want == "" {
		return
	}
	guid, err := datacard.GUIDBytes(want)
	w := &waiter{
		want:     want,
		guidErr:  err,
		recovery: len(os.Args) > 1 && os.Args[1] == "--recovery",
		timeout:  timeout,
		poll:     pollEvery,
		retry:    retryOff,
		now:      time.Now,
		sleep:    time.Sleep,
		find:     func() (string, error) { return datacard.Find("/sys/block", "/dev/block", guid) },
		link:     func(dev string) error { return datacard.Link(dev, link) },
		setprop:  setprop,
		powerOff: powerOff,
		logf:     log.Printf,
	}
	if w.run() == poweredOff {
		os.Exit(1)
	}
}

func getprop(name string) string {
	out, err := exec.Command("/system/bin/getprop", name).Output()
	if err != nil {
		return ""
	}
	return string(out)
}

// setprop sets an init property, giving up after propTimeout.
func setprop(name, value string) {
	ctx, cancel := context.WithTimeout(context.Background(), propTimeout)
	defer cancel()
	if err := exec.CommandContext(ctx, "/system/bin/setprop", name, value).Run(); err != nil {
		log.Printf("setprop %s: %v", name, err)
	}
}

// powerOff asks init to shut down (init handles sys.powerctl right away, also
// while it waits for this exec); if the console is still on after initOff (init
// busy, or the property never got through), it turns off itself. Only /metadata
// and /cache are mounted at this point. It returns only when neither worked.
func powerOff() error {
	setprop("sys.powerctl", "shutdown")
	time.Sleep(initOff)
	syscall.Sync()
	err := syscall.Reboot(syscall.LINUX_REBOOT_CMD_POWER_OFF)
	if err == nil {
		// the kernel is turning off; don't let the caller take this for a failure
		time.Sleep(initOff)
		err = errors.New("reboot(POWER_OFF) returned")
	}
	return fmt.Errorf("sys.powerctl and reboot(POWER_OFF) both left the console on: %w", err)
}
