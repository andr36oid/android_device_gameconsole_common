package main

import (
	"encoding/json"
	"errors"
	"io"
	"os/signal"
	"syscall"

	"andr36oid/emmcinstall/internal/emmc"
)

// --recovery inspect <storage roots>... and --recovery restore <CID> <folder>
// speak one JSON object per line. Only these commands are available here:
// no request files, Android services or persistent userdata are needed.
func recovery(cfg *emmc.Config, args []string, out io.Writer) error {
	var result any
	var err error
	if len(args) == 0 {
		err = syscall.EINVAL
	} else {
		switch args[0] {
		case "inspect":
			result, err = cfg.InspectRecovery(args[1:])
		case "restore":
			if len(args) != 3 {
				err = syscall.EINVAL
				break
			}
			// A stray signal must not cut the restore short.
			signal.Ignore(syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP)
			cfg.Wake(true)
			defer cfg.Wake(false)
			p, _ := emmc.NewProgress("", "restore")
			p.AddWriter(out)
			err = cfg.RestoreRecovery(p, args[1], args[2])
		default:
			err = syscall.EINVAL
		}
	}
	r := map[string]any{"ok": err == nil, "result": result}
	if err != nil {
		r["error"] = err.Error()
		var ne *emmc.NotExactError
		if errors.As(err, &ne) {
			r["not_exact"] = true
		}
	}
	if e := json.NewEncoder(out).Encode(r); err == nil {
		err = e
	}
	return err
}
