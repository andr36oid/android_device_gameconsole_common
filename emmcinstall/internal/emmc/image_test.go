package emmc

import (
	"archive/zip"
	"bytes"
	"compress/gzip"
	"encoding/binary"
	"math/rand"
	"os"
	"path/filepath"
	"syscall"
	"testing"

	"github.com/ulikunitz/xz"
)

// testImage is an SD card image of n bytes with a loader of its own: a
// random MiB over and over (so xz packs it quickly), each sector numbered.
func testImage(n int64) []byte {
	r := randBytes(rand.New(rand.NewSource(n)), 1<<20)
	b := make([]byte, n)
	for i := int64(0); i < n; i += 1 << 20 {
		copy(b[i:], r)
	}
	for i := int64(0); i+8 <= n; i += Sector {
		binary.LittleEndian.PutUint64(b[i:], uint64(i/Sector))
	}
	copy(b[LoaderStart*Sector:], []byte{0x3b, 0x8c, 0xdc, 0xfc, 'C', 'U', 'S', 'T'})
	return b
}

// saveImage writes img as name in the fake's folder, packed as its name says.
func saveImage(t *testing.T, f *fake, name string, img []byte, more ...string) string {
	t.Helper()
	path := filepath.Join(f.dir, "images", name)
	must(t, os.MkdirAll(filepath.Dir(path), 0755))
	var b bytes.Buffer
	switch filepath.Ext(name) {
	case ".img":
		b.Write(img)
	case ".gz":
		z := gzip.NewWriter(&b)
		z.Write(img)
		must(t, z.Close())
	case ".xz":
		z, err := xz.NewWriter(&b)
		must(t, err)
		z.Write(img)
		must(t, z.Close())
	case ".zip":
		z := zip.NewWriter(&b)
		w, err := z.Create("readme.txt")
		must(t, err)
		w.Write([]byte("an image"))
		for _, n := range append([]string{"console.img"}, more...) {
			w, err := z.Create(n)
			must(t, err)
			w.Write(img)
		}
		must(t, z.Close())
	}
	must(t, os.WriteFile(path, b.Bytes(), 0644))
	return path
}

func padded(b []byte) []byte {
	return append(append([]byte{}, b...), make([]byte, (Sector-len(b)%Sector)%Sector)...)
}

func TestWriteImage(t *testing.T) {
	roms := func(f *fake) string { return filepath.Join(f.dir, "roms") }
	img := testImage(mib(40)*Sector + 300) // a last sector to pad
	for _, name := range []string{"console.img", "console.img.gz", "console.img.xz", "console.zip"} {
		t.Run(name, func(t *testing.T) {
			f := newFake(t)
			path := saveImage(t, f, name, img)
			mustFail(t, f.cfg.WriteImage(f.progress(), path), "no checked backup")
			dir, err := f.cfg.Backup(f.progress(), roms(f))
			must(t, err)
			f.writes = nil
			must(t, f.cfg.WriteImage(f.progress(), path))
			want := padded(img)
			if !bytes.Equal(f.emmc(0, int64(len(want))/Sector), want) {
				t.Fatal("the image isn't on the eMMC")
			}
			if last := f.writes[len(f.writes)-1]; last != LoaderStart*Sector {
				t.Fatalf("the loader's header wasn't written last (sector %d)", last/Sector)
			}
			if !bytes.Equal(f.emmc(mib(64)-33, 33), make([]byte, 33*Sector)) {
				t.Fatal("the old partition table's copy at the end is still there")
			}
			st := f.state()
			if st.Custom == nil || st.Custom.Name != name || st.Custom.Size != int64(len(want)) ||
				st.Running != "" || st.Step1 != "" || st.Step3 != "" {
				t.Fatalf("after writing the image: %+v %+v", st, st.Custom)
			}
			mustFail(t, f.cfg.UpdateEmmc(f.progress()), "holds "+name+", not andr36oid")
			must(t, f.cfg.Restore(f.progress(), dir))
			if !bytes.Equal(f.emmc(0, mib(64)), f.stock) {
				t.Fatal("restore didn't bring the stock eMMC back")
			}
			if st := f.state(); st.Custom != nil {
				t.Fatalf("after the restore: %+v", st)
			}
		})
	}
	t.Run("loader area blanked first", func(t *testing.T) {
		f := newFake(t)
		path := saveImage(t, f, "console.img", img)
		_, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		w := testHooks.write
		testHooks.write = func(d *Disk, b []byte, off int64) ([]byte, error) {
			if d.Path == f.cfg.EmmcDev && off == mib(24)*Sector {
				return nil, syscall.EIO // cut off in the middle
			}
			return w(d, b, off)
		}
		mustFail(t, f.cfg.WriteImage(f.progress(), path), "input/output error")
		testHooks.write = w
		if !bytes.Equal(f.emmc(LoaderStart, LoaderEnd-LoaderStart), make([]byte, (LoaderEnd-LoaderStart)*Sector)) {
			t.Fatal("a cut-off write left a loader")
		}
		must(t, f.cfg.Leave(f.progress()))
		if st := f.state(); st.Incomplete != "write-image" || st.Custom != nil {
			t.Fatalf("after leaving: %+v", st)
		}
		mustFail(t, f.cfg.BlankLoader(f.progress()), "write-image didn't finish")
		must(t, f.cfg.WriteImage(f.progress(), path))
		if st := f.state(); st.Incomplete != "" || st.Custom == nil {
			t.Fatalf("after writing it again: %+v", st)
		}
	})
	t.Run("too big", func(t *testing.T) {
		f := newFake(t)
		path := saveImage(t, f, "big.img.gz", testImage(mib(64)*Sector+Sector))
		_, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		f.writes = nil
		mustFail(t, f.cfg.WriteImage(f.progress(), path), "the eMMC only 64 MiB")
		if len(f.writes) != 0 || f.state().Running != "" {
			t.Fatal("wrote an image that doesn't fit")
		}
	})
	t.Run("empty, two images, unknown kind", func(t *testing.T) {
		f := newFake(t)
		_, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		f.writes = nil
		mustFail(t, f.cfg.WriteImage(f.progress(), saveImage(t, f, "empty.img", nil)), "empty.img is empty")
		mustFail(t, f.cfg.WriteImage(f.progress(), saveImage(t, f, "two.zip", img[:mib(1)*Sector], "b.IMG")), "holds 2 .img files")
		mustFail(t, f.cfg.WriteImage(f.progress(), saveImage(t, f, "x.tar", img[:Sector])), "not an .img")
		if len(f.writes) != 0 {
			t.Fatal("wrote something")
		}
	})
	t.Run("reads back different", func(t *testing.T) {
		f := newFake(t)
		path := saveImage(t, f, "console.img.xz", img)
		_, err := f.cfg.Backup(f.progress(), roms(f))
		must(t, err)
		w := testHooks.write
		testHooks.write = func(d *Disk, b []byte, off int64) ([]byte, error) {
			b, err := w(d, b, off)
			if d.Path == f.cfg.EmmcDev && off == mib(20)*Sector && err == nil {
				b = append([]byte{}, b...)
				b[100] ^= 1
			}
			return b, err
		}
		defer func() { testHooks.write = w }()
		mustFail(t, f.cfg.WriteImage(f.progress(), path), "reads back different from the eMMC")
		if st := f.state(); st.Custom != nil || st.Running != "write-image" || st.Failed == "" {
			t.Fatalf("after a bad readback: %+v", st)
		}
	})
}

func TestListImages(t *testing.T) {
	f := newFake(t)
	img := testImage(mib(2) * Sector)
	saveImage(t, f, "a.img", img)
	saveImage(t, f, "b.img.xz", img)
	saveImage(t, f, "notes.txt", img)
	must(t, os.MkdirAll(filepath.Join(f.dir, "images", "sub.img"), 0755))
	writeFile(t, filepath.Join(f.cfg.SDBootDir, "boot.zip"), "zip")
	got := f.cfg.ListImages([]string{filepath.Join(f.dir, "images"), filepath.Join(f.dir, "none")})
	paths := map[string]string{}
	for _, i := range got {
		paths[i.Name] = i.Path
	}
	if len(got) != 3 || paths["a.img"] != filepath.Join(f.dir, "images", "a.img") ||
		paths["b.img.xz"] == "" || paths["boot.zip"] != "sdboot:boot.zip" || got[0].Name != "a.img" {
		t.Fatalf("listed %+v", got)
	}
	// an image on BOOT
	_, err := f.cfg.Backup(f.progress(), filepath.Join(f.dir, "roms"))
	must(t, err)
	must(t, os.WriteFile(filepath.Join(f.cfg.SDBootDir, "boot.img"), img, 0644))
	mustFail(t, f.cfg.WriteImage(f.progress(), "sdboot:../boot.img"), "isn't an image on BOOT")
	must(t, f.cfg.WriteImage(f.progress(), "sdboot:boot.img"))
	if !bytes.Equal(f.emmc(0, mib(2)), img) {
		t.Fatal("the image from BOOT isn't on the eMMC")
	}
}
