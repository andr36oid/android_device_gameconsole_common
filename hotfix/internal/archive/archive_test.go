package archive

import (
	"archive/zip"
	"bytes"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type entry struct {
	name, data string
	mode       os.FileMode
}

func packed(t *testing.T, entries []entry) string {
	t.Helper()
	file := filepath.Join(t.TempDir(), "test.fix.zip")
	var b bytes.Buffer
	z := zip.NewWriter(&b)
	for _, e := range entries {
		h := &zip.FileHeader{Name: e.name, Method: zip.Deflate}
		if e.mode != 0 {
			h.SetMode(e.mode)
		}
		w, err := z.CreateHeader(h)
		if err != nil {
			t.Fatal(err)
		}
		w.Write([]byte(e.data))
	}
	if err := z.Close(); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(file, b.Bytes(), 0600); err != nil {
		t.Fatal(err)
	}
	return file
}

func base() []entry {
	return []entry{{"manifest.txt", "name=test\r\ndescription=a test\r\nbuild=v1|123\r\n", 0},
		{"hotfix.sh", "exit 0\n", 0}, {"files/replacement", "payload", 0}}
}

func TestUnpack(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "new")
	n, err := Unpack(packed(t, base()), dir, MaxSize)
	if err != nil || n == 0 {
		t.Fatal(n, err)
	}
	b, err := os.ReadFile(filepath.Join(dir, "files/replacement"))
	if err != nil || string(b) != "payload" {
		t.Fatal(string(b), err)
	}
	b, _ = os.ReadFile(filepath.Join(dir, "manifest.txt"))
	if strings.Contains(string(b), "\r") {
		t.Fatal("manifest not normalized")
	}
	if _, err := Unpack(packed(t, base()), dir, MaxSize); err == nil {
		t.Fatal("reused directory")
	}
}

func TestPackedLimit(t *testing.T) {
	file := filepath.Join(t.TempDir(), "large.fix.zip")
	f, err := os.Create(file)
	if err != nil {
		t.Fatal(err)
	}
	// A sparse prefix checks the packed limit without a large payload or allocation.
	if _, err := f.Seek(64<<20, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	z := zip.NewWriter(f)
	z.SetOffset(64 << 20)
	for _, e := range base() {
		w, err := z.Create(e.name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := io.WriteString(w, e.data); err != nil {
			t.Fatal(err)
		}
	}
	if err := z.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := Unpack(file, filepath.Join(t.TempDir(), "new"), MaxSize); err != nil {
		t.Fatal(err)
	}
	if err := os.Truncate(file, MaxZip+1); err != nil {
		t.Fatal(err)
	}
	if _, err := Unpack(file, filepath.Join(t.TempDir(), "new"), MaxSize); err == nil {
		t.Fatal("accepted an archive above EASYROMS' packed limit")
	}
}

func TestRejectEntries(t *testing.T) {
	for _, e := range []entry{
		{"../escape", "x", 0}, {"/absolute", "x", 0}, {"files/../escape", "x", 0},
		{"files/./x", "x", 0}, {"files//x", "x", 0}, {"files/..", "x", 0},
		{"files\\escape", "x", 0}, {"files/line\nbreak", "x", 0},
		{"files/link", "/system", os.ModeSymlink | 0777},
		{"files/fifo", "", os.ModeNamedPipe | 0600}, {"hotfix.sh", "exit 1", 0},
		{"unexpected", "x", 0}, {"hotfix.sh/", "", os.ModeDir | 0700},
	} {
		t.Run(e.name, func(t *testing.T) {
			dir := filepath.Join(t.TempDir(), "new")
			if _, err := Unpack(packed(t, append(base(), e)), dir, MaxSize); err == nil {
				t.Fatal("accepted unsafe zip")
			}
			if _, err := os.Stat(dir); !os.IsNotExist(err) {
				t.Fatal("left output behind")
			}
		})
	}
}

func TestLimitsAndManifest(t *testing.T) {
	bad := []string{"name=test\ndescription=x\n", "name=../test\ndescription=x\nbuild=v1|123\n",
		"name=test\nname=other\ndescription=x\nbuild=v1|123\n",
		"name=test\ndescription=x\tbad\nbuild=v1|123\n", strings.Repeat("x", 8193)}
	for _, m := range bad {
		e := base()
		e[0].data = m
		if _, err := Unpack(packed(t, e), filepath.Join(t.TempDir(), "new"), MaxSize); err == nil {
			t.Fatal("accepted bad manifest")
		}
	}
	if _, err := Unpack(packed(t, base()), filepath.Join(t.TempDir(), "new"), 1); err == nil {
		t.Fatal("ignored budget")
	}
	e := base()
	for i := 0; i < 513; i++ {
		e = append(e, entry{"files/" + strings.Repeat("x", i+1), "", 0})
	}
	if _, err := Unpack(packed(t, e), filepath.Join(t.TempDir(), "new"), MaxSize); err == nil {
		t.Fatal("ignored count")
	}
}

func TestCorruption(t *testing.T) {
	file := packed(t, base())
	b, _ := os.ReadFile(file)
	// Corrupt a local record's data; the central directory still parses.
	n := bytes.Index(b, []byte("manifest.txt")) + len("manifest.txt")
	b[n] ^= 0xff
	os.WriteFile(file, b, 0600)
	dir := filepath.Join(t.TempDir(), "new")
	if _, err := Unpack(file, dir, MaxSize); err == nil {
		t.Fatal("accepted corrupt data")
	}
	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatal("left corrupt output")
	}
}
