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
//	list-images <folder>... the images write-image takes, in the folders and on BOOT
//	write-image <file>      advanced: any SD card image (.img, .img.gz, .img.xz, .zip)
//	                        to the eMMC; sdboot:<name> for one on BOOT
//	leave                   forget a stop or a cut-off write, write nothing
//	datacard-options        where Android's data can go: this card, the card in
//	                        slot 2 and what each choice needs; writes nothing
//	datacard-choose <emmc|original|new> [A|B|C] [autoapk]
//	                        after the backup: record where Android's data goes
//	datacard-prepare        after step 3: make the card in slot 2 a data card,
//	                        its userdata copied at the next start (from the eMMC)
//	datacard-copy           on the eMMC system: that copy
//	datacard-finish         last: datacard.txt on the eMMC's BOOT; the original
//	                        card made unable to start
//	datacard-check          on an SD start: is this the eMMC's data card made
//	                        bootable again (by the flasher)?
//	datacard-return         then: update-emmc, and the card a data card again
//	datacard-keep           or not: the card stays a normal SD card, not asked again
//	datacard-back           on the eMMC system with a data card: the card made
//	                        to start by itself, to put the stock eMMC back from it
//	datacard-revive [forget] after a restore: the data card starts the console
//	                        again, its data kept (forget: leave it, stop offering)
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
	"os/exec"
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
	case "datacard-options":
		o, err := cfg.DatacardOptions()
		if err != nil {
			fail(err)
		}
		print(map[string]any{"ok": true, "result": o})
		return
	case "datacard-check":
		r, err := cfg.DatacardCheck()
		if err != nil {
			fail(err)
		}
		print(map[string]any{"ok": true, "result": r})
		return
	}

	writes := map[string]bool{"backup": true, "blank-loader": true, "write-os": true,
		"write-loader": true, "update-uboot": true, "update-emmc": true, "restore": true, "write-image": true,
		"datacard-prepare": true, "datacard-copy": true, "datacard-finish": true, "datacard-return": true, "datacard-back": true, "datacard-keep": true,
		"datacard-revive": true}
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
	case "adopt":
		err = cfg.Adopt(p)
	case "list-images":
		result = cfg.ListImages(args)
	case "write-image":
		err = cfg.WriteImage(p, arg())
	case "leave":
		err = cfg.Leave(p)
	case "datacard-choose":
		if len(args) < 1 || len(args) > 3 {
			usage()
		}
		option, autoapk := "", false
		if len(args) > 1 {
			option = args[1]
		}
		if len(args) > 2 {
			autoapk = args[2] == "autoapk"
		}
		err = cfg.DatacardChoose(p, args[0], option, autoapk)
	case "datacard-prepare":
		err = cfg.DatacardPrepare(p)
	case "datacard-copy":
		result, err = cfg.DatacardCopy(p)
	case "datacard-finish":
		err = cfg.DatacardFinish(p)
	case "datacard-revive":
		result, err = cfg.DatacardRevive(p, len(args) == 1 && args[0] == "forget")
	case "datacard-return":
		err = cfg.DatacardReturn(p)
	case "datacard-back":
		result, err = cfg.DatacardBack(p)
	case "datacard-keep":
		err = cfg.DatacardKeep(p)
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
			errDetect(err)
			os.Exit(1)
		}
		fail(err)
	}
	out := map[string]any{"ok": true, "result": result}
	if cmd == "confirm" && result == "confirmed" && cfg.DatacardCopyPending() {
		// the eMMC system's Settings copies userdata to the new data card now
		out["datacard_copy"] = true
	}
	print(out)
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
	out := map[string]any{"ok": false, "error": err.Error()}
	var we *emmc.WroteError
	if errors.As(err, &we) {
		out["emmc_written"] = true
	}
	var lb *emmc.LowBatteryError
	if errors.As(err, &lb) {
		out["low_battery"] = true
	}
	print(out)
	release()
	errDetect(err)
	os.Exit(1)
}

// errDetect has the problem report service save a report named -ERRDETECT
// after a command the wizard ran failed, unless a report is being made already.
func errDetect(err error) {
	if resultFile == "" || requestCmd == "" {
		return // run by hand, or the request itself was broken
	}
	if out, _ := exec.Command("/system/bin/getprop", "init.svc.andr36oid-report").Output(); strings.TrimSpace(string(out)) == "running" {
		return
	}
	const dir = "/data/misc/andr36oid-report"
	msg := fmt.Sprintf("eMMC installer, %s: %v\n", requestCmd, err)
	if os.WriteFile(dir+"/errdetect.tmp", []byte(msg), 0644) != nil ||
		os.Rename(dir+"/errdetect.tmp", dir+"/errdetect") != nil {
		return
	}
	exec.Command("/system/bin/setprop", "ctl.start", "andr36oid-report").Run()
}

func usage() {
	if resultFile != "" {
		fail(fmt.Errorf("request %q not understood", requestCmd))
	}
	fmt.Fprintln(os.Stderr, "usage: andr36oid-emmcinstall [--progress file] inspect|status|backup <folder>|blank-loader|check-loader|write-os|start-test|cancel-test|check-test|confirm|write-loader <code>|update-uboot|update-emmc|restore <folder>|leave|datacard-options|datacard-choose <kind> [A|B|C] [autoapk]|datacard-prepare|datacard-copy|datacard-finish|datacard-check|datacard-return|datacard-keep|datacard-back|datacard-revive [forget]")
	os.Exit(2)
}
