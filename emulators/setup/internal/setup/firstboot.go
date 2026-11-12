package setup

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

// Uses the existing BOOT mkdir/pid lock; /dev is cleared at reboot. A killed
// helper's stale mount is removed only after its lock is safely taken over.
func (s *Setup) firstBootLock() (func(), error) {
	if s.BootLock == "" {
		return func() {}, nil
	}
	for n := 0; n < 150; n++ {
		if err := os.Mkdir(s.BootLock, 0700); err == nil {
			pid := strconv.Itoa(os.Getpid())
			if err = os.WriteFile(filepath.Join(s.BootLock, "pid"), []byte(pid+"\n"), 0600); err != nil {
				_ = os.Remove(s.BootLock)
				return nil, err
			}
			return func() {
				if fsx.ReadTrim(filepath.Join(s.BootLock, "pid")) == pid {
					_ = os.RemoveAll(s.BootLock)
				}
			}, nil
		} else if !os.IsExist(err) {
			return nil, err
		}
		old := fsx.ReadTrim(filepath.Join(s.BootLock, "pid"))
		pid, err := strconv.Atoi(old)
		if err == nil && pid > 1 && syscall.Kill(pid, 0) == syscall.ESRCH && fsx.ReadTrim(filepath.Join(s.BootLock, "pid")) == old {
			_ = os.RemoveAll(s.BootLock)
			continue
		}
		s.Sleep(200 * time.Millisecond)
	}
	return nil, errors.New("BOOT busy")
}
func (s *Setup) bootSystem() (string, error) {
	path := s.SystemDev
	if path == "/dev/block/by-name/system" {
		controller := s.Props.Get("ro.boot.boot_devices")
		if controller != "ff370000.dwmmc" && controller != "ff390000.dwmmc" {
			return "", errors.New("unsupported boot controller")
		}
		path = "/dev/block/platform/" + controller + "/by-name/system"
	}
	system, err := filepath.EvalSymlinks(path)
	if err != nil || !strings.HasSuffix(system, "p2") {
		return "", errors.New("cannot identify boot disk system partition")
	}
	return system, nil
}
func (s *Setup) withFirstBoot(fn func(string) error) error {
	unlock, err := s.firstBootLock()
	if err != nil {
		return err
	}
	defer unlock()
	if s.BootMount == "" {
		return fn(s.BootDev)
	}
	// Same abandoned-mount cleanup as the existing shell BOOT helpers.
	if raw, err := os.ReadFile(s.Mounts); err == nil {
		for _, line := range strings.Split(string(raw), "\n") {
			f := strings.Fields(line)
			if len(f) < 2 {
				continue
			}
			p := f[1]
			if strings.HasPrefix(p, "/mnt/andr36oid-") && strings.HasSuffix(p, "-boot") {
				if _, err = s.R.Run("umount", p); err != nil {
					return err
				}
			}
		}
	}
	if err = os.MkdirAll(s.BootMount, 0700); err != nil {
		return err
	}
	system, err := s.bootSystem()
	if err != nil {
		return err
	}
	bootdev := strings.TrimSuffix(system, "p2") + "p1"
	if _, err = s.R.Run("mount", "-t", "vfat", "-o", "rw,nosuid,nodev,noexec,noatime", bootdev, s.BootMount); err != nil {
		return err
	}
	defer func() {
		if _, e := s.R.Run("umount", s.BootMount); e != nil {
			s.Log("firstboot BOOT unmount: %v", e)
		}
	}()
	return fn(s.BootMount)
}
func keyValues(raw string) map[string]string {
	result := map[string]string{}
	for _, line := range strings.Split(raw, "\n") {
		if k, v, ok := strings.Cut(line, "="); ok {
			result[k] = v
		}
	}
	return result
}
func hex64(value string) bool {
	if len(value) != 64 {
		return false
	}
	for _, c := range value {
		if c < '0' || c > '9' {
			if c < 'a' || c > 'f' {
				return false
			}
		}
	}
	return true
}
func (s *Setup) preparedGuestReady(boot string) bool {
	manifest := filepath.Join(boot, "andr36oid-darkosen.json")
	if _, err := os.Lstat(manifest); os.IsNotExist(err) {
		// A damaged prepared card must not turn a required guest into an
		// optional no-guest install merely because its JSON disappeared.
		for _, line := range strings.Split(fsx.ReadTrim(filepath.Join(boot, "andr36oid-layout.txt")), "\n") {
			f := strings.Fields(line)
			if len(f) > 0 && f[0] == "DARKOS" {
				return false
			}
		}
		if system, e := s.bootSystem(); e == nil {
			part := strings.TrimSuffix(system, "p2") + "p14"
			if keyValues(fsx.ReadTrim(filepath.Join(s.BlockSys, filepath.Base(part), "uevent")))["PARTNAME"] == "DARKOS" {
				return false
			}
		}
		return true
	}
	system, err := s.bootSystem()
	if err != nil {
		return false
	}
	disk := strings.TrimSuffix(system, "p2")
	part := disk + "p14"
	out, err := s.R.Run(s.ErofsSupport, "partition-cached", manifest, disk, part, s.BlockSys, filepath.Join(s.SubsystemControl, "prepared/cache"), s.Props.Get("ro.build.version.incremental"), "-")
	if err != nil {
		s.Log("firstboot waits for validated DARKOS: %s", out)
		return false
	}
	info := keyValues(out)
	family, generation := info["source_id"], info["generation"]
	if info["origin"] != "release" || info["transport"] != "partition" || info["source_path"] != part || !hex64(family) || !hex64(generation) {
		return false
	}
	ready := filepath.Join(s.SubsystemControl, "ready", family+"-"+generation+"-native1")
	return sys.IsFile(ready) && fsx.ReadTrim(ready) == part
}

// Called at the existing library/apps gate and queued again after broker SETUP.
// A ready property from another image is insufficient: the same-card manifest,
// validated partition cache and durable generation ready file must all agree.
func (s *Setup) FirstBootRecord() {
	if s.BootDev == "" || s.Settings.Get("global", "andr36oid_daijishou_ready") != "1" || !sys.Exists(s.state("preparation-library-ready")) {
		return
	}
	if (sys.Exists(s.AutoapkDev) || len(s.apksOnAutoapk()) > 0) && !sys.Exists(s.AppsDoneFile) {
		return
	}
	version, incremental := s.Props.Get("ro.andr36oid.version"), s.Props.Get("ro.product.build.version.incremental")
	if incremental == "" {
		incremental = s.Props.Get("ro.build.version.incremental")
	}
	if version == "" || incremental == "" || strings.ContainsAny(version+incremental, "\r\n|") {
		return
	}
	err := s.withFirstBoot(func(boot string) error {
		if !s.preparedGuestReady(boot) {
			return errors.New("required in-wizard DARKOS setup is not complete")
		}
		layout := "legacy"
		marker := filepath.Join(boot, "andr36oid-layout.txt")
		if _, err := os.Lstat(marker); err == nil {
			first, _, _ := strings.Cut(fsx.ReadTrim(marker), "\n")
			if first != "prepared-1" {
				return errors.New("unknown prepared layout marker")
			}
			layout = "prepared-1"
		} else if !os.IsNotExist(err) {
			return err
		}
		path := filepath.Join(boot, "andr36oid-firstboot.txt")
		content := fmt.Sprintf("done=1\nbuild=%s|%s\nlayout=%s\n", version, incremental, layout)
		if fsx.ReadTrim(path) == strings.TrimSuffix(content, "\n") {
			return nil
		}
		if err := fsx.WriteFileAtomic(path, []byte(content)); err != nil {
			return err
		}
		dir, err := os.Open(boot)
		if err != nil {
			return err
		}
		defer dir.Close()
		return dir.Sync()
	})
	if err != nil {
		s.Log("firstboot completion deferred: %v", err)
		return
	}
	_ = s.Props.Set("sys.andr36oid.firstboot.done", "1")
}
