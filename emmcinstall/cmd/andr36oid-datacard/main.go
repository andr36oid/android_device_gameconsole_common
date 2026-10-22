// andr36oid-datacard: an eMMC install that keeps Android's data on an SD card waits
// here for that card before /data is mounted.
//
// init.rk30board.rc runs it (exec, so init waits) on late-fs, just before
// mount_all --late. Without androidboot.datacard (ro.boot.datacard) it exits at once.
// With it, it looks at both card slots twice a second for the partition with that GPT
// unique GUID and points /dev/block/by-name/a36data at it, which fstab.emmcsd mounts
// as /data. While the card is missing, sys.andr36oid.datacard_wait holds the seconds
// left, and the boot animation shows "Waiting for storage SD card ..." with them.
// After 3 minutes the console turns off: nothing is mounted writable yet.
//
// With --recovery (init.recovery.common.rc) it only links the card: recovery has no
// boot animation, and without the card it gives up after the 3 minutes and leaves
// the console on, so recovery stays usable.
package main

import (
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
	recovery := len(os.Args) > 1 && os.Args[1] == "--recovery"
	guid, err := datacard.GUIDBytes(want)
	if err != nil && recovery {
		log.Printf("%v", err)
		return
	}
	if err != nil {
		// a broken datacard.txt: the eMMC's own userdata would be mounted instead of
		// the user's data, so better not start at all
		log.Printf("%v, turning off", err)
		powerOff()
	}
	start := time.Now()
	shown := -1
	for {
		dev, err := datacard.Find("/sys/block", "/dev/block", guid)
		if err != nil {
			log.Printf("looking for the card: %v", err)
		}
		if dev != "" {
			if err := datacard.Link(dev, link); err != nil {
				log.Printf("linking %s: %v", dev, err)
			} else {
				log.Printf("data card %s found after %.1f s", dev, time.Since(start).Seconds())
				if shown >= 0 {
					setprop(waitProp, "0")
				}
				return
			}
		}
		left := int((timeout - time.Since(start) + time.Second - 1) / time.Second)
		if left <= 0 && recovery {
			log.Printf("no data card (%s) after %v", want, timeout)
			return
		}
		if left <= 0 {
			log.Printf("no data card (%s) after %v, turning off", want, timeout)
			setprop(waitProp, "0")
			powerOff()
		}
		// the first look is right away: a card that is in shows nothing at all
		if left != shown && time.Since(start) >= pollEvery && !recovery {
			if shown < 0 {
				log.Printf("waiting for the data card %s", want)
			}
			shown = left
			setprop(waitProp, fmt.Sprint(left))
		}
		time.Sleep(pollEvery)
	}
}

func getprop(name string) string {
	out, err := exec.Command("/system/bin/getprop", name).Output()
	if err != nil {
		return ""
	}
	return string(out)
}

func setprop(name, value string) {
	if err := exec.Command("/system/bin/setprop", name, value).Run(); err != nil {
		log.Printf("setprop %s: %v", name, err)
	}
}

// powerOff asks init to shut down; if that hasn't happened after 20 s (init busy),
// it turns off itself. Only /metadata and /cache are mounted at this point.
func powerOff() {
	setprop("sys.powerctl", "shutdown")
	time.Sleep(20 * time.Second)
	syscall.Sync()
	syscall.Reboot(syscall.LINUX_REBOOT_CMD_POWER_OFF)
	os.Exit(1)
}
