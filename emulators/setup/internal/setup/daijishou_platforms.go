package setup

import (
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// Empty mainstream systems remain discoverable. Ports is a utility exception:
// its synthetic PortMaster entry is useful before any port has been installed.
var djMainstream = map[string]bool{
	"nes": true, "snes": true, "n64": true,
	"gb": true, "gbc": true, "gba": true,
	"genesis": true, "psx": true, "psp": true,
	"ports": true,
}

func djPlatformID(line seedLine) string {
	// The build-generated seed starts with a quoted, comma-free unique_id.
	id, _, ok := strings.Cut(line.vals, ",")
	if !ok {
		return ""
	}
	return strings.Trim(strings.TrimSpace(id), "'")
}

func djGamePattern(exts string) *regexp.Regexp {
	if exts == "" {
		return nil
	}
	re, err := regexp.Compile(`(?i)\.(` + exts + `)$`)
	if err != nil {
		return nil
	}
	return re
}

func djGameName(name string, re *regexp.Regexp) bool {
	return re != nil && !strings.HasPrefix(name, ".") &&
		!strings.HasSuffix(strings.ToLower(name), ".pure.zip") && re.MatchString(name)
}

// Find one recognized game without opening its contents. Hidden trees, saves,
// art, symlinks and directories named like games do not make a system nonempty.
// An unreadable directory is an error, not evidence that its games disappeared.
func djFolderHasGames(dir string, re *regexp.Regexp) (bool, error) {
	if re == nil {
		return false, nil
	}
	info, err := os.Lstat(dir)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if !info.IsDir() {
		return false, nil
	}
	found := false
	err = filepath.WalkDir(dir, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if path != dir && strings.HasPrefix(entry.Name(), ".") {
			if entry.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if entry.Type().IsRegular() && djGameName(entry.Name(), re) {
			found = true
			return filepath.SkipAll
		}
		return nil
	})
	return found, err
}

func (s *Setup) djGameFolders(root string) (map[string]bool, error) {
	found := map[string]bool{}
	for folder, exts := range s.djExts() {
		has, err := djFolderHasGames(filepath.Join(root, folder), djGamePattern(exts))
		if err != nil {
			return nil, err
		}
		found[folder] = has
	}
	return found, nil
}

func djWantedPlatform(line seedLine, games map[string]bool) bool {
	if djMainstream[djPlatformID(line)] {
		return true
	}
	for _, folder := range strings.Split(line.arg, ",") {
		if games[folder] {
			return true
		}
	}
	return false
}

// On an existing card, MakeRomFolders deliberately preserves its layout. A
// fresh library still needs a usable sync folder for every mainstream system.
func (s *Setup) djMainstreamFolders(root string) {
	for _, line := range s.djSeedLines() {
		if line.kind != "platform" || !djMainstream[djPlatformID(line)] {
			continue
		}
		folders := strings.Split(line.arg, ",")
		exists := false
		for _, folder := range folders {
			if info, err := os.Stat(filepath.Join(root, folder)); err == nil && info.IsDir() {
				exists = true
			}
		}
		if !exists && len(folders) > 0 && folders[0] != "" {
			if err := os.MkdirAll(filepath.Join(root, folders[0]), 0775); err != nil {
				s.Log("creating mainstream ROM folder %s failed: %v", folders[0], err)
			}
		}
	}
}
