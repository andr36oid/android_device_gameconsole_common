#!/usr/bin/env python3
"""Small GPT/FAT release fixtures, with real EROFS target checks when available."""
import argparse
import contextlib
import gzip
import importlib.util
import io
import lzma
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
spec = importlib.util.spec_from_file_location("make_hotfix", Path(__file__).resolve().parents[1] / "tools/make-hotfix.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


def image(path, system=b"", vendor=b"", build=b"12345", boot=None):
    # FAT32 BOOT with a long-name metadata file, plus two optional filesystems.
    fat = bytearray(128 * 512)
    fat[0:3] = b"\xeb\x58\x90"
    struct.pack_into("<H", fat, 11, 512)
    fat[13], fat[16] = 1, 1
    struct.pack_into("<H", fat, 14, 1)
    struct.pack_into("<I", fat, 32, 128)
    struct.pack_into("<I", fat, 36, 1)
    struct.pack_into("<I", fat, 44, 2)
    fat[510:512] = b"\x55\xaa"
    for i in range(4):
        struct.pack_into("<I", fat, 512 + i * 4, 0x0fffffff)
    name = "andr36oid-build.txt".encode("utf-16le") + b"\0\0"
    name += b"\xff\xff" * ((52 - len(name)) // 2)
    short = b"ANDR36~1TXT"
    checksum = 0
    for b in short:
        checksum = (((checksum & 1) << 7) + (checksum >> 1) + b) & 255
    for pos, number in enumerate((2, 1)):
        e = bytearray(32)
        e[0], e[11], e[13] = number | (0x40 if number == 2 else 0), 15, checksum
        chars = name[(number - 1) * 26:number * 26]
        e[1:11], e[14:26], e[28:32] = chars[:10], chars[10:22], chars[22:26]
        fat[1024 + pos * 32:1024 + (pos + 1) * 32] = e
    metadata = b"format=1\nro.andr36oid.version=v1-test\nro.product.build.version.incremental=" + build + b"\n"
    e = bytearray(32)
    e[:11], e[11] = short, 32
    struct.pack_into("<H", e, 26, 3)
    struct.pack_into("<I", e, 28, len(metadata))
    fat[1088:1120] = e
    fat[1536:1536 + len(metadata)] = metadata
    fat = bytes(fat) if boot is None else boot
    system_start = max(256, 64 + (len(fat) + 511) // 512)
    vendor_start = max(4096, system_start + max(128, (len(system) + 511) // 512))
    data = bytearray(max(4 * 1048576, (vendor_start + max(128, (len(vendor) + 511) // 512) + 64) * 512))
    table = bytearray(128 * 128)
    parts = [("BOOT", 64, fat), ("system", system_start, system), ("vendor", vendor_start, vendor)]
    for i, (name, start, content) in enumerate(parts):
        e = memoryview(table)[i * 128:(i + 1) * 128]
        e[:16] = b"\1" * 16
        e[16:32] = bytes([i + 2]) * 16
        size = max(128, (len(content) + 511) // 512)
        struct.pack_into("<QQ", e, 32, start, start + size - 1)
        n = name.encode("utf-16le")
        e[56:56 + len(n)] = n
        data[start * 512:start * 512 + len(content)] = content
    h = bytearray(512)
    h[:8] = b"EFI PART"
    struct.pack_into("<II", h, 8, 0x10000, 92)
    struct.pack_into("<QIII", h, 72, 2, 128, 128, zlib.crc32(table))
    struct.pack_into("<I", h, 16, zlib.crc32(h[:92]))
    data[512:1024], data[1024:1024 + len(table)] = h, table
    path.write_bytes(data)


class GeneratorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        self.img = self.dir / "release.img"
        image(self.img)
        self.file = self.dir / "replacement.so"
        self.file.write_bytes(b"replacement")

    def args(self, **kw):
        values = dict(image=str(self.img), name="test", description="A test fix", script=None,
                      replace=[f"/system/lib64/libtest.so={self.file}"], no_check=True,
                      output=str(self.dir / "test.fix.zip"))
        values.update(kw)
        return argparse.Namespace(**values)

    def test_compressed_and_raw(self):
        for ext, compress in (("", lambda b: b), (".xz", lzma.compress), (".gz", gzip.compress)):
            file = self.dir / ("image.img" + ext)
            file.write_bytes(compress(self.img.read_bytes()))
            args = self.args(image=str(file), output=str(self.dir / ("test" + ext + ".fix.zip")))
            err = io.StringIO()
            with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
                tool.create(args)
            self.assertIn("WARNING", err.getvalue())
            with zipfile.ZipFile(args.output) as z:
                self.assertIn(b"build=v1-test|12345\n", z.read("manifest.txt"))
                self.assertIn(b"hotfix_bind files/0 /system/lib64/libtest.so", z.read("hotfix.sh"))
                self.assertEqual(z.read("files/0"), b"replacement")
            if os.environ.get("HOTFIX_UNPACK"):
                dest = self.dir / ("unpacked" + ext)
                subprocess.run([os.environ["HOTFIX_UNPACK"], args.output, str(dest), "268435456"],
                               check=True, capture_output=True)
                self.assertEqual((dest / "files/0").read_bytes(), b"replacement")

    def test_custom_script(self):
        script = self.dir / "hotfix.sh"
        script.write_text("hotfix_log custom\n")
        args = self.args(script=str(script), replace=[])
        with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
            tool.create(args)
        with zipfile.ZipFile(args.output) as z:
            self.assertEqual(z.read("hotfix.sh"), script.read_bytes())

    def test_build_any(self):
        # --build any writes a build-agnostic manifest (applier applies to every build)
        # and does not read the image's own build identity.
        script = self.dir / "hotfix.sh"
        script.write_text("hotfix_log any\n")
        args = self.args(script=str(script), replace=[], build="any")
        with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
            tool.create(args)
        with zipfile.ZipFile(args.output) as z:
            self.assertIn(b"build=any\n", z.read("manifest.txt"))
        # a control character in --build is rejected
        with self.assertRaises(ValueError):
            tool.create(self.args(script=str(script), replace=[], build="bad\x01",
                                  output=str(self.dir / "bad.fix.zip")))

    def test_packed_destinations_and_limits(self):
        manifest = "name=test\ndescription=A test\nbuild=v1|123\n"
        first = self.dir / "first.fix.zip"
        size, _, warnings = tool.pack(self.dir, first, manifest, b"exit 0\n", {}, 1 << 20, 512, 1 << 20)
        self.assertEqual(warnings, [])
        needed = ((size + 511) // 512 + 2) * 512
        for free, boot_limit, easy_limit, recommend in (
                (needed, size, size, False), (needed - 1, size, size, True),
                (1 << 20, size - 1, size, True)):
            out = self.dir / "next.fix.zip"
            with mock.patch.object(tool, "BOOT_MAX_ZIP", boot_limit), mock.patch.object(tool, "EASYROMS_MAX_ZIP", easy_limit):
                _, _, warnings = tool.pack(self.dir, out, manifest, b"exit 0\n", {}, free, 512, 1 << 20)
            self.assertEqual(any("EASYROMS/hotfix" in w for w in warnings), recommend)
            self.assertTrue(out.exists())
            out.unlink()
        out = self.dir / "too-large.fix.zip"
        with mock.patch.object(tool, "BOOT_MAX_ZIP", size - 1), mock.patch.object(tool, "EASYROMS_MAX_ZIP", size - 1):
            with self.assertRaisesRegex(ValueError, "exceeds both packed limits"):
                tool.pack(self.dir, out, manifest, b"exit 0\n", {}, 0, 512, 1 << 20)
        self.assertFalse(out.exists())

    def test_normal_mode_recommends_easyroms(self):
        err = io.StringIO()
        with mock.patch.object(tool.delta.Fat, "free", return_value=0):
            with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
                tool.create(self.args())
        self.assertTrue(Path(self.args().output).exists())
        self.assertIn("place this hotfix in EASYROMS/hotfix", err.getvalue())

    def test_bad_metadata_and_gpt(self):
        b = self.img.read_bytes()
        for bad in (b[:512] + b"bad" + b[515:],
                    b.replace(b"ro.andr36oid.version=", b"xx.andr36oid.version="),
                    b.replace(b"format=1", b"format=2")):
            self.img.write_bytes(bad)
            with self.assertRaises(ValueError):
                with self.img.open("rb") as f:
                    tool.Image(f).build()

    def test_bad_paths(self):
        for target in ("/data/file", "/system/../escape", "/system//lib/x", "/system/./x", "/system/a\nb"):
            with self.assertRaises(ValueError):
                tool.create(self.args(replace=[target + "=" + str(self.file)]))

    def test_target_checks(self):
        if not shutil.which("mkfs.erofs") or not shutil.which("dump.erofs"):
            self.skipTest("erofs-utils unavailable")
        tree = self.dir / "tree"
        (tree / "system/lib64").mkdir(parents=True)
        (tree / "system/lib64/libtest.so").write_bytes(b"old")
        raw = self.dir / "system.erofs"
        subprocess.run(["mkfs.erofs", str(raw), str(tree)], check=True, capture_output=True)
        image(self.img, system=raw.read_bytes())
        with contextlib.redirect_stdout(io.StringIO()):
            tool.create(self.args(no_check=False))
        self.assertTrue((self.dir / "test.fix.zip").is_file())
        (self.dir / "test.fix.zip").unlink()
        with self.assertRaisesRegex(ValueError, "target is missing"):
            tool.create(self.args(no_check=False, replace=[f"/system/lib64/missing.so={self.file}"]))
        # Directories must also be rejected by the host check.
        with self.assertRaisesRegex(ValueError, "not a regular file"):
            tool.create(self.args(no_check=False, replace=[f"/system/lib64={self.file}"]))


    def test_ext4_vendor_checks(self):
        mkfs = shutil.which("mke2fs") or ("/usr/sbin/mke2fs" if Path("/usr/sbin/mke2fs").exists() else None)
        if not mkfs or not (shutil.which("debugfs") or shutil.which("debugfs", path="/usr/sbin:/sbin")):
            self.skipTest("e2fsprogs unavailable")
        tree = self.dir / "vendor-tree"
        (tree / "lib64").mkdir(parents=True)
        (tree / "lib64/libtest.so").write_bytes(b"old")
        raw = self.dir / "vendor.ext4"
        # A small plain ext4 filesystem, no mount or root required.
        with raw.open("wb") as f:
            f.truncate(1048576)
        subprocess.run([mkfs, "-q", "-t", "ext4", "-d", str(tree), str(raw)],
                       check=True, capture_output=True)
        image(self.img, vendor=raw.read_bytes())
        with contextlib.redirect_stdout(io.StringIO()):
            tool.create(self.args(no_check=False, replace=[f"/vendor/lib64/libtest.so={self.file}"]))
        (self.dir / "test.fix.zip").unlink()
        with self.assertRaisesRegex(ValueError, "target is missing"):
            tool.create(self.args(no_check=False, replace=[f"/vendor/lib64/missing.so={self.file}"]))


if __name__ == "__main__":
    unittest.main()
