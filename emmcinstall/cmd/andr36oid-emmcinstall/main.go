// andr36oid-emmcinstall installs andr36oid from the SD card it runs from onto
// the console's built-in eMMC, in steps that each prove the next one safe
// (Settings > System > eMMC Installation drives it):
//
//	inspect                 what is there; writes nothing
//	status                  the installer's records
//	backup <folder>         step 0: the whole stock eMMC into <folder>/eMMC backup/<eMMC>/
//	blank-loader            step 1: clear the eMMC's loader, then restart
//	check-loader            after that restart: the SD card's loader started it
//	write-os                step 2: andr36oid onto the eMMC, test start set up
//	start-test, cancel-test set up or take away the test start
//	check-test              after the test start: did the eMMC system come up?
//	confirm                 run by the eMMC system at every start
//	write-loader <code>     step 3: the SD card's loader onto the eMMC
//	update-uboot            after step 3: the SD card's U-Boot onto the eMMC
//	update-emmc             after step 3: the SD card's BOOT, system, vendor and
//	                        U-Boot onto the eMMC, apps and data kept
//	restore <backup folder> the stock eMMC back
//	write-image <file>      advanced: any SD card image (.img, .img.gz, .img.xz, .zip) to the eMMC
//	leave                   forget a stop or a cut-off write, write nothing
//	request                 run what the state folder's request file asks
//	                        (cmd=..., arg=...), the result to result.json
//
// --progress <file> (default in the state folder) gets JSON progress lines.
// The last line on stdout is {"ok":...}; the exit code is 1 on any refusal or
// failure. Any check that fails stops everything: nothing is written after it.
//
// Settings runs it as root with ctl.start andr36oid-emmcinstall, after writing
// /data/misc/andr36oid-emmcinstall/request; it reads progress.jsonl and
// result.json from there.
package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"

	"andr36oid/emmcinstall/internal/emmc"
)

func main() {
	emmc.ProcessSetup()
	args := os.Args[1:]
	cfg := emmc.DefaultConfig()
	progress := filepath.Join(cfg.StateDir, "progress.jsonl")
	if len(args) >= 2 && args[0] == "--progress" {
		progress, args = args[1], args[2:]
	}
	if len(args) == 0 {
		usage()
	}
	cmd, args := args[0], args[1:]
	if cmd == "request" {
		cmd, args = request(cfg)
	}
	arg := func() string {
		if len(args) != 1 {
			usage()
		}
		return args[0]
	}

	var result any
	var err error
	switch cmd {
	case "inspect":
		print(cfg.Inspect())
		return
	case "status":
		st, err := cfg.LoadState()
		if err != nil {
			fail(err)
		}
		print(st)
		return
	}

	writes := map[string]bool{"backup": true, "blank-loader": true, "write-os": true,
		"write-loader": true, "update-uboot": true, "update-emmc": true, "restore": true, "write-image": true}
	if writes[cmd] {
		// a stray signal must not cut a write short
		signal.Ignore(syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP)
		cfg.Wake(true)
		release = func() { cfg.Wake(false) }
	}
	if cmd == "confirm" {
		// for the eMMC system's Settings: did the test start get confirmed?
		progress = ""
		resultFile, requestCmd = filepath.Join(cfg.StateDir, "confirm.json"), cmd
	} else if err := os.MkdirAll(cfg.StateDir, 0770); err != nil {
		fail(err)
	}
	p, err := emmc.NewProgress(progress, cmd)
	if err != nil {
		fail(err)
	}

	switch cmd {
	case "backup":
		result, err = cfg.Backup(p, arg())
	case "blank-loader":
		err = cfg.BlankLoader(p)
	case "check-loader":
		err = cfg.CheckLoader(p)
	case "write-os":
		err = cfg.WriteOS(p)
	case "start-test":
		err = cfg.StartTest(p)
	case "cancel-test":
		err = cfg.CancelTest(p)
	case "check-test":
		result, err = cfg.CheckTest(p)
	case "confirm":
		result, err = cfg.Confirm(p)
	case "write-loader":
		err = cfg.WriteLoader(p, arg())
	case "update-uboot":
		err = cfg.UpdateUboot(p)
	case "update-emmc":
		err = cfg.UpdateEmmc(p)
	case "restore":
		err = cfg.Restore(p, arg())
	case "write-image":
		err = cfg.WriteImage(p, arg())
	case "leave":
		err = cfg.Leave(p)
	default:
		usage()
	}
	if err != nil {
		p.Logf("error: %v", err)
		var ne *emmc.NotExactError
		if errors.As(err, &ne) {
			// written back and checked, but the boot areas differ: the
			// wizard says so instead of "stopped"
			print(map[string]any{"ok": false, "error": err.Error(), "not_exact": true})
			release()
			os.Exit(1)
		}
		fail(err)
	}
	print(map[string]any{"ok": true, "result": result})
	release()
}

// resultFile, when set, gets what is printed last (request mode).
var resultFile, requestCmd string

// release lets go of the wake lock, also on the way out after a failure.
var release = func() {}

// request reads the request file Settings wrote and takes it away.
func request(cfg *emmc.Config) (string, []string) {
	path := filepath.Join(cfg.StateDir, "request")
	resultFile = filepath.Join(cfg.StateDir, "result.json")
	os.Remove(resultFile)
	f, err := os.Open(path)
	if err != nil {
		fail(err)
	}
	var cmd string
	var args []string
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		k, v, _ := strings.Cut(sc.Text(), "=")
		switch k {
		case "cmd":
			cmd = v
		case "arg":
			args = append(args, v)
		}
	}
	f.Close()
	os.Remove(path)
	if cmd == "" || cmd == "request" {
		fail(fmt.Errorf("request without a command"))
	}
	requestCmd = cmd
	return cmd, args
}

func print(v any) {
	b, _ := json.MarshalIndent(v, "", "  ")
	fmt.Println(string(b))
	if resultFile != "" {
		if m, ok := v.(map[string]any); ok {
			m["cmd"] = requestCmd
			b, _ = json.MarshalIndent(m, "", "  ")
		} else {
			b, _ = json.MarshalIndent(map[string]any{"cmd": requestCmd, "ok": true, "result": v}, "", "  ")
		}
		os.WriteFile(resultFile+".tmp", b, 0644)
		os.Rename(resultFile+".tmp", resultFile)
	}
}

func fail(err error) {
	print(map[string]any{"ok": false, "error": err.Error()})
	release()
	os.Exit(1)
}

func usage() {
	if resultFile != "" {
		fail(fmt.Errorf("request %q not understood", requestCmd))
	}
	fmt.Fprintln(os.Stderr, "usage: andr36oid-emmcinstall [--progress file] inspect|status|backup <folder>|blank-loader|check-loader|write-os|start-test|cancel-test|check-test|confirm|write-loader <code>|update-uboot|update-emmc|restore <folder>|leave")
	os.Exit(2)
}
