package main

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"strconv"
	"time"

	"andr36oid/hotfix/internal/runner"

	"andr36oid/hotfix/internal/archive"
)

func main() {
	if len(os.Args) >= 4 && os.Args[1] == "--wait" {
		seconds, err := strconv.Atoi(os.Args[2])
		if err != nil || seconds < 1 || seconds > 60 {
			fmt.Fprintln(os.Stderr, "invalid command deadline")
			os.Exit(1)
		}
		os.Exit(runner.Command(os.Args[3:], time.Duration(seconds)*time.Second, os.Stdout))
	}
	if len(os.Args) == 3 && os.Args[1] == "--check-exfat" {
		if err := checkExfat(os.Args[2]); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	if len(os.Args) == 4 && os.Args[1] == "--run" {
		os.Exit(runner.Run(os.Args[2], os.Args[3], 30*time.Second, os.Stdout))
	}
	if len(os.Args) != 4 {
		fmt.Fprintln(os.Stderr, "usage: andr36oid-hotfix-unpack ZIP NEW-DIRECTORY BYTE-LIMIT")
		os.Exit(1)
	}
	limit, err := strconv.ParseUint(os.Args[3], 10, 64)
	if err == nil {
		var n uint64
		n, err = archive.Unpack(os.Args[1], os.Args[2], limit)
		if err == nil {
			fmt.Println(n)
			return
		}
	}
	fmt.Fprintln(os.Stderr, err)
	os.Exit(1)
}

// Only read the boot sector: a dirty card waits for vold's normal check later.
func checkExfat(file string) error {
	f, err := os.Open(file)
	if err != nil {
		return err
	}
	defer f.Close()
	var boot [512]byte
	if _, err := io.ReadFull(f, boot[:]); err != nil {
		return err
	}
	if !bytes.Equal(boot[3:11], []byte("EXFAT   ")) || boot[510] != 0x55 || boot[511] != 0xaa {
		return errors.New("not an exFAT boot sector")
	}
	if boot[106]&6 != 0 {
		return errors.New("exFAT volume is dirty or has media errors")
	}
	return nil
}
