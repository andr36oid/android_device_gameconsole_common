package setup

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"

	"andr36oid/gcsetup/internal/fsx"
)

const (
	repairRequest = "sys.andr36oid.ra_repair_request"
	repairResult  = "sys.andr36oid.ra_repair_result"
	repairAll     = 31
	repairLimit   = 4 << 20
)

var repairNames = []string{
	"retroarch.cfg",
	"config/Gambatte/Gambatte.cfg",
	"config/Nestopia/Nestopia.cfg",
	"config/FCEUmm/FCEUmm.cfg",
}

var repairKeys = []string{"video_shader_enable", "preemptive_frames_enable"}

// Keep the original backup representation: a JSON array, with base64 file bytes.
// Name is relative to raExt, never an arbitrary destination from the document.
type repairFile struct {
	Name   string
	Before []byte
	After  []byte
}

func (s *Setup) repairBackup() string {
	return s.state("retroarch-performance-backup.json")
}

// repairRead rejects links, special files, oversized inputs and linked parents.
// O_NONBLOCK also prevents a raced replacement with a FIFO from hanging the worker.
func repairRead(path string, limit int64) ([]byte, error) {
	parent := filepath.Dir(path)
	resolved, err := filepath.EvalSymlinks(parent)
	if err != nil {
		return nil, err
	}
	if resolved != filepath.Clean(parent) {
		return nil, fmt.Errorf("invalid config directory: %s", parent)
	}
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() > limit {
		return nil, fmt.Errorf("invalid config file: %s", path)
	}
	f, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_NONBLOCK, 0)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	opened, err := f.Stat()
	if err != nil || !opened.Mode().IsRegular() || !os.SameFile(info, opened) {
		return nil, fmt.Errorf("config replaced: %s", path)
	}
	data, err := io.ReadAll(io.LimitReader(f, limit+1))
	if err == nil && int64(len(data)) > limit {
		err = fmt.Errorf("config too large: %s", path)
	}
	return data, err
}

func repairValue(rest string) string {
	rest = strings.TrimSpace(rest)
	if strings.HasPrefix(rest, `"`) {
		end := strings.IndexByte(rest[1:], '"')
		if end < 0 {
			return ""
		}
		tail := strings.TrimSpace(rest[end+2:])
		if tail != "" && !strings.HasPrefix(tail, "#") {
			return ""
		}
		return rest[1 : end+1]
	}
	if fields := strings.Fields(rest); len(fields) > 0 {
		return fields[0]
	}
	return ""
}

func repairGet(data []byte, key string) string {
	value := ""
	for _, line := range splitLines(string(bytes.TrimPrefix(data, []byte("\xef\xbb\xbf")))) {
		if k, rest, ok := cfgKeyOf(line); ok && k == key {
			value = repairValue(rest)
		}
	}
	return value
}

// Only selected keys change. Preserve BOM, line endings, comments and every
// unrelated byte; all occurrences of selected keys are made false.
func repairRender(data []byte, keys []string) []byte {
	bom := []byte("\xef\xbb\xbf")
	body := bytes.TrimPrefix(data, bom)
	var out bytes.Buffer
	if len(body) != len(data) {
		out.Write(bom)
	}
	ending := "\n"
	if bytes.Contains(body, []byte("\r\n")) {
		ending = "\r\n"
	}
	seen := make(map[string]bool)
	for _, line := range strings.SplitAfter(string(body), "\n") {
		key, rest, ok := cfgKeyOf(line)
		selected := false
		for _, k := range keys {
			selected = selected || key == k
		}
		if ok && selected {
			seen[key] = true
			if repairValue(rest) != "false" {
				eol := ""
				if strings.HasSuffix(line, "\r\n") {
					eol = "\r\n"
				} else if strings.HasSuffix(line, "\n") {
					eol = "\n"
				}
				line = key + ` = "false"` + eol
			}
		}
		out.WriteString(line)
	}
	for _, key := range keys {
		if seen[key] {
			continue
		}
		if out.Len() > 0 && out.Bytes()[out.Len()-1] != '\n' && !bytes.Equal(out.Bytes(), bom) {
			out.WriteString(ending)
		}
		out.WriteString(key + ` = "false"` + ending)
	}
	return out.Bytes()
}

func (s *Setup) repairRunning() bool {
	if s.RetroArchRunning() {
		return true
	}
	_, err := s.R.Run("pidof", "retroarch", "retroarch32", "retroarch64")
	return err == nil
}

func (s *Setup) repairPlan(selection int) ([]repairFile, error) {
	if selection < 1 || selection > repairAll {
		return nil, errors.New("invalid repair selection")
	}
	global, err := repairRead(s.raCfg(), repairLimit)
	if err != nil {
		return nil, err
	}
	dir := repairGet(global, "rgui_config_directory")
	if dir != "" && dir != "default" && dir != raExtPublic+"/config" && dir != s.raExt()+"/config" {
		return nil, errors.New("custom override directory is unsupported")
	}
	var files []repairFile
	for i, name := range repairNames {
		var keys []string
		if i == 0 {
			for bit, key := range repairKeys {
				if selection&(1<<bit) != 0 {
					keys = append(keys, key)
				}
			}
		} else if selection&(1<<(i+1)) != 0 {
			keys = repairKeys
		}
		if len(keys) == 0 {
			continue
		}
		before := global
		if i != 0 {
			before, err = repairRead(filepath.Join(s.raExt(), name), repairLimit)
			if os.IsNotExist(err) {
				continue // Missing core overrides stay missing.
			}
			if err != nil {
				return nil, err
			}
		}
		after := repairRender(before, keys)
		if !bytes.Equal(before, after) {
			files = append(files, repairFile{name, before, after})
		}
	}
	return files, nil
}

func (s *Setup) repairUnchanged(name string, expected []byte) error {
	data, err := repairRead(filepath.Join(s.raExt(), name), repairLimit)
	if err != nil {
		return err
	}
	if !bytes.Equal(data, expected) {
		return fmt.Errorf("config changed: %s", name)
	}
	return nil
}

// On an ordinary write failure, try to undo our writes. If RetroArch starts,
// stop writing altogether and leave the durable backup for recovery.
func (s *Setup) repairWrite(files []repairFile) (string, error) {
	for _, file := range files {
		if err := s.repairUnchanged(file.Name, file.Before); err != nil {
			return "error", err
		}
	}
	var attempted []repairFile
	for _, file := range files {
		if s.repairRunning() {
			return "busy", errors.New("RetroArch started during repair")
		}
		err := s.repairUnchanged(file.Name, file.Before)
		if err == nil {
			attempted = append(attempted, file)
			err = writeInPlace(filepath.Join(s.raExt(), file.Name), file.After)
		}
		if err != nil {
			for i := len(attempted) - 1; i >= 0; i-- {
				if s.repairRunning() {
					return "busy", err
				}
				previous := attempted[i]
				// Recheck file type/links before touching a potentially partial write.
				if _, readErr := repairRead(filepath.Join(s.raExt(), previous.Name), repairLimit); readErr != nil {
					s.Log("repair rollback %s: %v", previous.Name, readErr)
					continue
				}
				if rollbackErr := writeInPlace(filepath.Join(s.raExt(), previous.Name), previous.Before); rollbackErr != nil {
					s.Log("repair rollback %s: %v", previous.Name, rollbackErr)
				}
			}
			return "error", err
		}
	}
	return "", nil
}

func validRepairBackup(files []repairFile) bool {
	if len(files) == 0 || len(files) > len(repairNames) {
		return false
	}
	seen := map[string]bool{}
	for _, file := range files {
		index := -1
		for i, name := range repairNames {
			if file.Name == name {
				index = i
			}
		}
		if index < 0 || seen[file.Name] || len(file.Before) > repairLimit || len(file.After) > repairLimit || bytes.Equal(file.Before, file.After) {
			return false
		}
		seen[file.Name] = true
		valid := bytes.Equal(file.After, repairRender(file.Before, repairKeys))
		if index == 0 {
			for _, key := range repairKeys {
				valid = valid || bytes.Equal(file.After, repairRender(file.Before, []string{key}))
			}
		}
		if !valid {
			return false
		}
	}
	return true
}

func (s *Setup) restoreRetroArchRepair() (string, error) {
	data, err := repairRead(s.repairBackup(), 48<<20)
	if err != nil {
		return "error", err
	}
	var files []repairFile
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&files); err != nil {
		return "error", err
	}
	if err := decoder.Decode(new(any)); err != io.EOF || !validRepairBackup(files) {
		return "error", errors.New("invalid repair backup")
	}
	var restore []repairFile
	for _, file := range files {
		current, err := repairRead(filepath.Join(s.raExt(), file.Name), repairLimit)
		if err != nil {
			return "error", err
		}
		if bytes.Equal(current, file.Before) {
			continue // Already rolled back, or restored before an interruption.
		}
		if !bytes.Equal(current, file.After) {
			return "error", fmt.Errorf("config edited after repair: %s", file.Name)
		}
		restore = append(restore, repairFile{file.Name, current, file.Before})
	}
	if code, err := s.repairWrite(restore); err != nil {
		return code, err
	}
	if err := os.Remove(s.repairBackup()); err != nil {
		return "error", err
	}
	return "restored", nil
}

// RepairRetroArch runs only as an explicit serialized worker job. It neither
// enables automatic sync nor stops a running game.
func (s *Setup) RepairRetroArch(action string, selection int) (string, error) {
	if action != "preview" && action != "apply" && action != "restore" {
		return "error", errors.New("invalid repair action")
	}
	if action != "restore" && (selection < 1 || selection > repairAll) {
		return "error", errors.New("invalid repair selection")
	}
	if s.repairRunning() {
		return "busy", errors.New("RetroArch is running")
	}
	if action == "restore" {
		return s.restoreRetroArchRepair()
	}
	files, err := s.repairPlan(selection)
	if err != nil {
		return "error", err
	}
	if action == "preview" {
		return "preview:" + strconv.Itoa(len(files)), nil
	}
	if len(files) == 0 {
		return "unchanged", nil
	}
	if _, err := os.Lstat(s.repairBackup()); !os.IsNotExist(err) {
		if err != nil {
			return "error", err
		}
		return "backup", errors.New("a repair backup already exists")
	}
	if s.repairRunning() {
		return "busy", errors.New("RetroArch started before backup")
	}
	data, err := json.Marshal(files)
	if err != nil {
		return "error", err
	}
	if err := fsx.WriteFileAtomic(s.repairBackup(), data); err != nil {
		return "error", err
	}
	if directory, err := os.Open(s.State); err != nil {
		return "error", err
	} else {
		err = directory.Sync()
		closeErr := directory.Close()
		if err != nil || closeErr != nil {
			return "error", errors.Join(err, closeErr)
		}
	}
	if code, err := s.repairWrite(files); err != nil {
		return code, err
	}
	return "applied", nil
}

func validRepairToken(token string) bool {
	if len(token) == 0 || len(token) > 40 {
		return false
	}
	for _, c := range token {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '-' || c == '_') {
			return false
		}
	}
	return true
}

// Wire protocol used by GameComfort: token:preview:mask, token:apply:mask,
// token:restore. A result always echoes the token; stale results cannot satisfy
// a later UI request. Legacy blanket preview/apply requests are rejected.
func (s *Setup) RepairRetroArchJob() {
	request := strings.Split(s.Props.Get(repairRequest), ":")
	if len(request) < 2 || !validRepairToken(request[0]) {
		s.Log("invalid RetroArch repair request")
		return
	}
	selection := 0
	valid := len(request) == 2 && request[1] == "restore"
	if len(request) == 3 && (request[1] == "preview" || request[1] == "apply") {
		mask, err := strconv.Atoi(request[2])
		valid = err == nil && mask >= 1 && mask <= repairAll && strconv.Itoa(mask) == request[2]
		selection = mask
	}
	code := "error"
	if valid {
		var err error
		code, err = s.RepairRetroArch(request[1], selection)
		if err != nil {
			s.Log("RetroArch repair %s: %v", request[1], err)
		}
	}
	if err := s.Props.Set(repairResult, request[0]+":"+code); err != nil {
		s.Log("RetroArch repair result: %v", err)
	}
}
