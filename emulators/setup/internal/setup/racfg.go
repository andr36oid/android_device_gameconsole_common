package setup

import (
	"bufio"
	"bytes"
	"errors"
	"os"
	"strings"

	"andr36oid/gcsetup/internal/fsx"
)

// retroarch.cfg lines are `key = "value"` (RetroArch writes them so; people sometimes
// leave the quotes out or add spaces).

// cfgKeyOf is the key of a `key = value` line, "" if the line isn't one.
func cfgKeyOf(line string) (key, rest string, ok bool) {
	t := strings.TrimLeft(line, " \t")
	i := strings.IndexAny(t, " \t=")
	if i <= 0 {
		return "", "", false
	}
	key = t[:i]
	r := strings.TrimLeft(t[i:], " \t")
	if !strings.HasPrefix(r, "=") {
		return "", "", false
	}
	return key, r[1:], true
}

// cfgGet is a setting's value without quotes; the last one wins; "" if unset.
func cfgGet(path, key string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	val := ""
	sc := bufio.NewScanner(bytes.NewReader(b))
	sc.Buffer(make([]byte, 64*1024), 4<<20)
	for sc.Scan() {
		k, rest, ok := cfgKeyOf(sc.Text())
		if !ok || k != key {
			continue
		}
		v := strings.Trim(rest, " \t\r")
		// the script's sed took an optional quote on each side and no quote inside
		v = strings.TrimPrefix(v, `"`)
		v = strings.TrimSuffix(v, `"`)
		if strings.Contains(v, `"`) {
			continue
		}
		val = v
	}
	return val
}

// cfgSet sets key in a retroarch.cfg in place, so the file keeps its owner, mode and the
// ACL vold gave it. Every old line but the key's stays, the key goes last. A copy of the
// old file (<file>.bak) is kept until the new text is in; if writing fails (full /data),
// the old text is put back, or stays in <file>.bak if even that fails.
// Only one setup process runs at a time (see the worker), so there is no lock: RetroArch
// itself isn't running when this matters (it writes the whole file back when it quits).
func (s *Setup) cfgSet(path, key, value string) error {
	old, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var out bytes.Buffer
	for _, line := range splitLines(string(old)) {
		if k, _, ok := cfgKeyOf(line); ok && k == key {
			continue
		}
		out.WriteString(line)
		out.WriteByte('\n')
	}
	out.WriteString(key + ` = "` + value + `"` + "\n")

	bak := path + ".bak"
	if err := fsx.WriteFileAtomic(bak, old); err != nil {
		return err
	}
	if err := writeInPlace(path, out.Bytes()); err == nil {
		_ = os.Remove(bak)
		return nil
	}
	if err := writeInPlace(path, old); err == nil {
		_ = os.Remove(bak)
		s.Log("writing %s failed, the old one is back", path)
	} else {
		if o, err := fsx.OwnerOf(dirOf(path)); err == nil {
			_ = fsx.Chown(bak, o)
		}
		s.Log("writing %s failed, the old one is in %s", path, bak)
	}
	return errors.New("writing " + path + " failed")
}

// writeInPlace truncates and rewrites the file (same inode), and syncs it.
func writeInPlace(path string, data []byte) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_TRUNC, 0)
	if err != nil {
		return err
	}
	_, err = f.Write(data)
	if err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	return err
}

// splitLines splits text into lines without their newlines; a last line without a
// newline counts, an empty text has no lines.
func splitLines(text string) []string {
	if text == "" {
		return nil
	}
	text = strings.TrimSuffix(text, "\n")
	return strings.Split(text, "\n")
}

// manifest is RetroArch's .gameconsole/seeded.sha1: "<sha1> <key>" per file we put there,
// so a newer image may update a file the user didn't change, and a file the user removed
// stays removed. Keys are file names, look/<path> or games/<core>/<ROM name>.opt (spaces
// and brackets: compared as plain text).
type manifest struct{ path string }

func (m manifest) lines() []string {
	b, err := os.ReadFile(m.path)
	if err != nil {
		return nil
	}
	return splitLines(string(b))
}

func keyOfLine(l string) string {
	if i := strings.IndexByte(l, ' '); i >= 0 {
		return l[i+1:]
	}
	return ""
}

// has: the exact line "<sum> <key>" is there.
func (m manifest) has(sum, key string) bool {
	want := sum + " " + key
	for _, l := range m.lines() {
		if l == want {
			return true
		}
	}
	return false
}

// hasKey: some line is for key.
func (m manifest) hasKey(key string) bool {
	for _, l := range m.lines() {
		if keyOfLine(l) == key {
			return true
		}
	}
	return false
}

// set replaces key's line with "<sum> <key>".
func (m manifest) set(sum, key string) error {
	var out []string
	for _, l := range m.lines() {
		if keyOfLine(l) != key {
			out = append(out, l)
		}
	}
	out = append(out, sum+" "+key)
	return fsx.WriteFileAtomic(m.path, []byte(strings.Join(out, "\n")+"\n"))
}

// dropContaining removes every line with sub in it (grep -v).
func (m manifest) dropContaining(sub string) error {
	var out []string
	for _, l := range m.lines() {
		if !strings.Contains(l, sub) {
			out = append(out, l)
		}
	}
	text := strings.Join(out, "\n")
	if len(out) > 0 {
		text += "\n"
	}
	return fsx.WriteFileAtomic(m.path, []byte(text))
}

func (m manifest) touch() { _ = fsx.Touch(m.path) }
