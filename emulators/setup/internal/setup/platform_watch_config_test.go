package setup

import (
	"bytes"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestNativeWatchConfigAndEveryShippedExtension(t *testing.T) {
	f := newFake()
	s, _ := newSetup(t, f)
	s.Base = emu // djSeedLines reads daijishou/daijishou-seed.txt
	var config bytes.Buffer
	must(t, s.NativeWatchConfig(&config))
	if !strings.HasPrefix(config.String(), "a36-platform-watch\t1\n") {
		t.Fatal("missing version")
	}
	// Compile the real native filter and feed it one batch of canonical seed
	// fixtures. Go's actual djGameName/regexp remains the reference.
	matcher := filepath.Join(t.TempDir(), "match")
	cmd := exec.Command("cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", filepath.Join(emu, "platform-watch/match-test.c"), "-o", matcher)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("native matcher: %v %s", err, out)
	}
	var input, expected strings.Builder
	cases := 0
	folders := 0
	for _, line := range strings.Split(config.String(), "\n")[1:] {
		if line == "" {
			continue
		}
		pair := strings.SplitN(line, "\t", 2)
		if len(pair) != 2 {
			t.Fatal(line)
		}
		folders++
		pattern := djGamePattern(s.djExts()[pair[0]])
		var names []string
		for _, ext := range strings.Split(pair[1], "|") {
			names = append(names, "Game."+ext, "Game."+strings.ToUpper(ext), ".hidden."+ext)
		}
		names = append(names, "Game.pure.zip", "Game.PURE.ZIP", "README.txt", "Game", "Game.")
		for _, name := range names {
			input.WriteString(pair[1] + "\t" + name + "\n")
			if djGameName(name, pattern) {
				expected.WriteString("1\n")
			} else {
				expected.WriteString("0\n")
			}
			cases++
		}
	}
	cmd = exec.Command(matcher)
	cmd.Stdin = strings.NewReader(input.String())
	out, err := cmd.CombinedOutput()
	must(t, err)
	if string(out) != expected.String() {
		t.Fatal("native suffix decisions differ from Go")
	}
	if folders < 70 || cases < 1000 {
		t.Fatal("shipped seed not exercised", folders, cases)
	}
	t.Logf("%d folders/%d ASCII filename decisions + native overflow parser match actual Go seed", folders, cases)
}

func TestNativeWatchConfigRejectsNonliteralBeforeOutput(t *testing.T) {
	f := newFake()
	s, _ := newSetup(t, f)
	write(t, s.Base+"/daijishou/daijishou-seed.txt", "platform\t1\tnes\t'x'\nplayer\t1\tx\t'(?:nes|x[12])$'\n")
	var out bytes.Buffer
	if s.NativeWatchConfig(&out) == nil || out.Len() != 0 {
		t.Fatal("unsupported regex did not select original Go watcher")
	}
}
