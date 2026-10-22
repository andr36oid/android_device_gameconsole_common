package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"andr36oid/emmcinstall/internal/emmc"
)

func TestRecoveryCommands(t *testing.T) {
	c := emmc.DefaultConfig().ForRecovery()
	c.EmmcSys = filepath.Join(t.TempDir(), "mmcblk2")
	for _, args := range [][]string{nil, {"request"}, {"backup", "/data"}, {"restore"}, {"restore", "cid"}, {"restore", "cid", "dir", "extra"}} {
		var out bytes.Buffer
		if err := recovery(c, args, &out); err == nil {
			t.Fatalf("accepted %v", args)
		}
		var result map[string]any
		if err := json.Unmarshal(out.Bytes(), &result); err != nil || result["ok"] != false {
			t.Fatalf("invalid failure for %v: %s", args, &out)
		}
	}
	for path, value := range map[string]string{
		"andr36oid_unlock": "locked", "device/cid": "150100503142454c0000aabbccdd0e00",
		"device/type": "MMC", "device/name": "P1J95K", "size": "131072",
	} {
		p := filepath.Join(c.EmmcSys, path)
		if err := os.MkdirAll(filepath.Dir(p), 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(value), 0644); err != nil {
			t.Fatal(err)
		}
	}
	var out bytes.Buffer
	if err := recovery(c, []string{"inspect"}, &out); err != nil {
		t.Fatal(err)
	}
	var result map[string]any
	if err := json.Unmarshal(out.Bytes(), &result); err != nil || result["ok"] != true {
		t.Fatalf("invalid inspection: %s", &out)
	}
}
