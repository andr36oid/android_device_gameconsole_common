package setup

import (
	"fmt"
	"io"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

var nativeWatchExtension = regexp.MustCompile(`^[a-zA-Z0-9_]+$`)

// NativeWatchConfig keeps the seed/Android provisioning engine authoritative.
// Native code handles only the literal suffixes every shipped player uses. A
// future nonliteral expression fails this command and restores the Go watcher.
func (s *Setup) NativeWatchConfig(out io.Writer) error {
	extensions := s.djExts()
	var folders []string
	for folder, exts := range extensions {
		if folder == "" || strings.HasPrefix(folder, ".") || filepath.Base(folder) != folder || strings.ContainsAny(folder, "\t\r\n") {
			return fmt.Errorf("invalid watch folder %q", folder)
		}
		for _, ext := range strings.Split(exts, "|") {
			if !nativeWatchExtension.MatchString(ext) {
				return fmt.Errorf("nonliteral watch extension %q", ext)
			}
		}
		folders = append(folders, folder)
	}
	sort.Strings(folders)
	if _, err := fmt.Fprintln(out, "a36-platform-watch\t1"); err != nil {
		return err
	}
	for _, folder := range folders {
		seen := map[string]bool{}
		var values []string
		for _, v := range strings.Split(extensions[folder], "|") {
			v = strings.ToLower(v)
			if !seen[v] {
				seen[v] = true
				values = append(values, v)
			}
		}
		sort.Strings(values)
		if _, err := fmt.Fprintf(out, "%s\t%s\n", folder, strings.Join(values, "|")); err != nil {
			return err
		}
	}
	return nil
}
