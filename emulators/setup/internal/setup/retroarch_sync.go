package setup

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"andr36oid/gcsetup/internal/fsx"
	"andr36oid/gcsetup/internal/sys"
)

type raSyncValue struct {
	Value string
	Time  int64
}

type raSyncFile struct {
	path    string
	data    []byte
	values  map[string]string
	time    int64
	missing bool
	owner   fsx.Owner
	mode    os.FileMode
}

func raSyncAllowed(key string, core bool) bool {
	// Core options are independent of the input driver too, but never accept a
	// button map even if someone put one in the core options file.
	if strings.HasPrefix(key, "input_") {
		return false
	}
	if core {
		return true
	}
	return key == "video_smooth" || strings.HasPrefix(key, "video_shader") ||
		key == "menu_driver" || key == "aspect_ratio_index"
}

func raSyncRead(path string, core bool) (*raSyncFile, error) {
	f := &raSyncFile{path: path, values: map[string]string{}}
	info, err := os.Lstat(path)
	if os.IsNotExist(err) {
		f.missing, f.mode = true, 0o660
		f.owner, err = fsx.OwnerOf(dirOf(path))
		return f, err
	}
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, fmt.Errorf("not a regular config: %s", path)
	}
	f.time, f.mode = info.ModTime().UnixNano(), info.Mode().Perm()
	f.owner, err = fsx.OwnerOf(path)
	if err != nil {
		return nil, err
	}
	f.data, err = os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	for _, line := range splitLines(string(f.data)) {
		if strings.HasPrefix(strings.TrimSpace(line), "#") {
			continue
		}
		key, rest, ok := cfgKeyOf(line)
		if ok && raSyncAllowed(key, core) {
			f.values[key] = strings.TrimSpace(rest)
		}
	}
	return f, nil
}

func (f *raSyncFile) unchanged() error {
	cur, err := os.ReadFile(f.path)
	if os.IsNotExist(err) && f.missing {
		return nil
	}
	if err != nil || f.missing || !bytes.Equal(cur, f.data) {
		return fmt.Errorf("config changed since read: %s", f.path)
	}
	return nil
}

func (f *raSyncFile) render(values map[string]raSyncValue) []byte {
	var b strings.Builder
	seen := map[string]bool{}
	for _, line := range strings.SplitAfter(string(f.data), "\n") {
		key, _, ok := cfgKeyOf(line)
		value, shared := values[key]
		if ok && shared && !strings.HasPrefix(strings.TrimSpace(line), "#") {
			seen[key] = true
			if f.values[key] != value.Value {
				ending := ""
				if strings.HasSuffix(line, "\n") {
					ending = "\n"
				}
				line = key + " = " + value.Value + ending
			}
		}
		b.WriteString(line)
	}
	var keys []string
	for key := range values {
		if !seen[key] {
			keys = append(keys, key)
		}
	}
	sort.Strings(keys)
	if len(keys) > 0 && b.Len() > 0 && !strings.HasSuffix(b.String(), "\n") {
		b.WriteByte('\n')
	}
	for _, key := range keys {
		b.WriteString(key + " = " + values[key].Value + "\n")
	}
	return []byte(b.String())
}

func (f *raSyncFile) write(values map[string]raSyncValue) error {
	next := f.render(values)
	if bytes.Equal(next, f.data) {
		return nil
	}
	stage, err := os.CreateTemp(dirOf(f.path), ".retroarch-sync.")
	if err != nil {
		return err
	}
	tmp := stage.Name()
	defer os.Remove(tmp)
	_, err = stage.Write(next)
	if cerr := stage.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = fsx.SetOwnerMode(tmp, f.owner, f.mode)
	}
	if err == nil {
		err = f.unchanged()
	}
	if err == nil {
		err = syncRename(tmp, f.path)
	}
	return err
}

// Files only have one modification time. Keep the last merged value and time
// for each key so changing another setting doesn't make every key look newer.
// On the first sync, conflicting keys use the files' times; ties favor Android.
func raSyncMerge(a, b *raSyncFile, before map[string]raSyncValue) map[string]raSyncValue {
	out := map[string]raSyncValue{}
	for _, f := range []*raSyncFile{a, b} {
		for key, value := range f.values {
			v := raSyncValue{value, f.time}
			if old, ok := before[key]; ok && old.Value == value {
				v.Time = old.Time
			}
			if cur, ok := out[key]; !ok || v.Time > cur.Time {
				out[key] = v
			}
		}
	}
	return out
}

func (s *Setup) raSyncDir() string {
	dir := fsx.ReadTrim(s.BrokerState + "/retroarch-sync-dir")
	if dir == "" {
		dir = s.SubsystemRoot + "/home/ark/.config/retroarch"
	}
	// The broker records the last image's writable upper, which survives its
	// unmount. Never follow an arbitrary marker or Linux absolute symlink.
	if dir != s.SubsystemRoot+"/home/ark/.config/retroarch" &&
		(!strings.HasPrefix(dir, s.BrokerState+"/images/") || !strings.HasSuffix(dir, "/upper/home/ark/.config/retroarch")) {
		return ""
	}
	resolved, err := filepath.EvalSymlinks(dir)
	if err != nil || resolved != dir {
		return ""
	}
	return dir
}

// SyncRetroArch shares no drivers, device paths or controller mappings. The
// broker has stopped RetroArch and unmounted its overlay before requesting it.
func (s *Setup) SyncRetroArch() {
	if s.Props.Get("persist.andr36oid.subsystem_autosync") == "0" || !s.syncEnabled("retroarch", false) {
		return
	}
	if s.raSyncRunning() {
		return
	}
	dir := s.raSyncDir()
	if dir == "" || !sys.IsDir(s.raExt()) {
		return
	}
	for _, name := range []string{"retroarch-core-options.cfg", "retroarch.cfg"} {
		if err := s.raSyncConfigs(dir, name); err != nil {
			s.Log("sharing RetroArch %s failed: %v", name, err)
		}
	}
}

func (s *Setup) raSyncRunning() bool {
	mounts, _ := os.ReadFile(s.Mounts)
	for _, line := range splitLines(string(mounts)) {
		f := strings.Fields(line)
		if len(f) > 1 && strings.ReplaceAll(f[1], `\040`, " ") == s.SubsystemRoot {
			return true
		}
	}
	if s.RetroArchRunning() || s.djLibraryESRunning() {
		return true
	}
	_, err := s.R.Run("pidof", "retroarch", "retroarch32", "retroarch64")
	return err == nil
}

func (s *Setup) raSyncConfigs(dir, name string) error {
	core := name == "retroarch-core-options.cfg"
	a, err := raSyncRead(s.raExt()+"/"+name, core)
	if err != nil {
		return err
	}
	b, err := raSyncRead(dir+"/"+name, core)
	if err != nil {
		return err
	}
	state := s.state("retroarch-sync-" + fsx.SHA1Bytes([]byte(dir+"/"+name)) + ".json")
	before := map[string]raSyncValue{}
	data, err := os.ReadFile(state)
	previous := data
	if err == nil {
		if err := json.Unmarshal(data, &before); err != nil {
			return err
		}
	} else if !os.IsNotExist(err) {
		return err
	}
	values := raSyncMerge(a, b, before)
	for _, f := range []*raSyncFile{a, b} {
		if err := f.unchanged(); err != nil {
			return err
		}
	}
	for _, f := range []*raSyncFile{a, b} {
		if s.raSyncRunning() {
			return nil
		}
		changed := !bytes.Equal(f.data, f.render(values))
		if err := f.write(values); err != nil {
			return err
		}
		if f == a && changed {
			s.restorecon(false, f.path)
		}
	}
	data, err = json.Marshal(values)
	if err != nil || bytes.Equal(data, previous) {
		return err
	}
	return fsx.WriteFileAtomic(state, data)
}
