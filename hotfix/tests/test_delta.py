#!/usr/bin/env python3
"""Delta planning, real compressed EROFS/ext4/FAT images and generated mksh scripts."""

import argparse
import contextlib
import copy
import gzip
import io
import lzma
import os
from pathlib import Path
import shutil
import stat
import struct
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

from test_generator import image, tool

delta = tool.delta
LABEL = {"security.selinux": b"u:object_r:system_file:s0\0"}


def entry(kind=stat.S_IFREG, content="old", **kw):
    values = dict(mode=kind | (0o755 if kind == stat.S_IFDIR else 0o644), uid=0, gid=0,
                  size=10 if kind == stat.S_IFREG else 0, content=content if kind == stat.S_IFREG else "",
                  attrs=LABEL.copy())
    values.update(kw)
    return delta.Entry(**values)


class PlanTest(unittest.TestCase):
    def setUp(self):
        self.old = {"/system": entry(stat.S_IFDIR), "/system/etc": entry(stat.S_IFDIR),
                    "/system/etc/old": entry(), "/system/lib": entry(stat.S_IFDIR),
                    "/system/lib/test.so": entry()}
        self.new = copy.deepcopy(self.old)

    def plan(self, size=1024, count=256):
        return delta.plan(self.old, self.new, size, count)

    def test_content_and_metadata(self):
        for attr, value in (("content", "new"), ("mode", stat.S_IFREG | 0o755), ("uid", 1234),
                            ("gid", 2345), ("attrs", {"security.selinux": b"u:object_r:vendor_file:s0"})):
            with self.subTest(attr=attr):
                self.new = copy.deepcopy(self.old)
                setattr(self.new["/system/lib/test.so"], attr, value)
                _, files, dirs, excluded = self.plan()
                self.assertEqual(files, ["/system/lib/test.so"])
                self.assertEqual((dirs, excluded), ([], {}))

    def test_add_delete_symlink_and_limits(self):
        del self.new["/system/etc/old"]
        self.new["/system/etc/added"] = entry(content="new")
        self.new["/system/etc/link"] = entry(stat.S_IFLNK, link="added")
        _, files, dirs, excluded = self.plan()
        self.assertEqual((files, dirs, excluded), ([], ["/system/etc"], {}))
        for size, count in ((1, 256), (1024, 1)):
            _, files, dirs, excluded = self.plan(size, count)
            self.assertEqual((files, dirs), ([], []))
            self.assertEqual(len(excluded), 3)
            self.assertTrue(all("limit" in why for why in excluded.values()))

    def test_smallest_existing_parent_and_overlapping_copies(self):
        self.old["/system/etc/sub"] = entry(stat.S_IFDIR)
        self.new["/system/etc/sub"] = entry(stat.S_IFDIR)
        self.new["/system/etc/sub/new"] = entry()
        self.assertEqual(self.plan()[2], ["/system/etc/sub"])
        self.new["/system/etc/new-dir"] = entry(stat.S_IFDIR)
        self.new["/system/etc/new-dir/child"] = entry()
        self.assertEqual(self.plan()[2], ["/system/etc"])

    def test_unpatchable_files_and_unchanged_capabilities(self):
        for path in ("/system/lib/module.ko", "/system/etc/init/test.rc", "/vendor/build.prop",
                     "/system/etc/selinux/policy", "/system/apex/test.apex", "/init.rc"):
            self.new[path] = entry()
        self.old["/system/etc/old"].attrs["security.capability"] = b"capability"
        self.new["/system/etc/old"].attrs["security.capability"] = b"capability"
        self.new["/system/etc/added"] = entry()
        _, files, dirs, excluded = self.plan()
        self.assertEqual((files, dirs), ([], []))
        self.assertEqual(len(excluded), 7)
        self.assertIn("full update", excluded["/system/etc/added"])
        del self.new["/system/etc/init/test.rc"]
        del self.new["/system/etc/selinux/policy"]
        self.assertIn("unsupported metadata", self.plan()[3]["/system/etc/added"])

    def test_regular_files_still_work_when_parent_is_too_large(self):
        self.new["/system/etc/old"].content = "new"
        self.new["/system/etc/added"] = entry()
        _, files, dirs, excluded = self.plan(size=1)
        self.assertEqual(files, ["/system/etc/old"])
        self.assertEqual(dirs, [])
        self.assertEqual(list(excluded), ["/system/etc/added"])

    def test_small_child_still_works_when_parent_is_too_large(self):
        self.old["/system/etc/big"] = entry(size=2048)
        self.new["/system/etc/big"] = entry(size=2048)
        self.old["/system/etc/small"] = entry(stat.S_IFDIR)
        self.new["/system/etc/small"] = entry(stat.S_IFDIR)
        self.new["/system/etc/added"] = entry()
        self.new["/system/etc/small/added"] = entry()
        _, files, dirs, excluded = self.plan(size=1024)
        self.assertEqual(files, [])
        self.assertEqual(dirs, ["/system/etc/small"])
        self.assertEqual(list(excluded), ["/system/etc/added"])

    def test_directory_metadata_symlink_and_type_changes(self):
        self.old["/system/etc/link"] = entry(stat.S_IFLNK, link="old")
        self.new["/system/etc/link"] = entry(stat.S_IFLNK, link="new")
        self.new["/system/etc/old"] = entry(stat.S_IFDIR)
        self.new["/system/etc/old/child"] = entry()
        self.new["/system/etc"].mode = stat.S_IFDIR | 0o750
        self.assertEqual(self.plan()[1:], ([], ["/system/etc"], {}))


class ImageDeltaTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        self.old = self.dir / "old.img"
        self.new = self.dir / "new.img"
        self.output = self.dir / "delta.fix.zip"

    def args(self, **kw):
        values = dict(image=str(self.old), delta=str(self.new), name="delta", description=None,
                      replace=[], script=None, no_check=False, output=str(self.output),
                      max_dir_bytes=8 << 20, max_dir_entries=256, warn_bytes=16 << 20)
        values.update(kw)
        return argparse.Namespace(**values)

    def run_generator(self, **kw):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            tool.create(self.args(**kw))
        self.assertEqual(out.getvalue(), self.output.with_name("delta.report.txt").read_text())
        return out.getvalue()

    def prepare(self):
        try:
            for env, name in (("MKFS_EROFS", "mkfs.erofs"), ("FSCK_EROFS", "fsck.erofs"),
                              ("MKE2FS", "mke2fs"), ("DEBUGFS", "debugfs")):
                delta.find_tool(env, name)
        except ValueError as e:
            self.skipTest(str(e))
        contexts = self.dir / "contexts"
        contexts.write_text("/.* u:object_r:system_file:s0\n")
        for number, img in enumerate((self.old, self.new)):
            root = self.dir / ("tree" + str(number))
            (root / "system/etc/config").mkdir(parents=True)
            (root / "system/lib64").mkdir(parents=True)
            (root / "system/etc/init").mkdir(parents=True)
            (root / "system/etc/init/test.rc").write_text("init " + str(number))
            (root / "system/lib64/test.so").write_bytes((b"new" if number else b"old") * 10000)
            (root / "system/lib64/test.so").chmod(0o755 if number else 0o644)
            (root / "system/etc/config/stays").write_text("same")
            (root / "system/etc/config/.hidden").write_text("same hidden file")
            (root / "system/etc/config" / ("added" if number else "removed")).write_text("payload")
            (root / "system/etc/config/link").symlink_to("added" if number else "removed")
            (root / "system/etc/config/dash-link").symlink_to("-target" if number else "removed")
            erofs = self.dir / ("system" + str(number) + ".erofs")
            subprocess.run([delta.find_tool("MKFS_EROFS", "mkfs.erofs"), "-zlz4hc", "-x0",
                            "--force-uid=1234", "--force-gid=2345", "--file-contexts=" + str(contexts),
                            str(erofs), str(root)], check=True, capture_output=True)
            vendor = self.dir / ("vendor" + str(number))
            (vendor / "etc/config").mkdir(parents=True)
            (vendor / "lib64").mkdir()
            (vendor / "lib64/module.ko").write_text("module " + str(number))
            (vendor / "lib64/test.so").write_text("library " + str(number))
            (vendor / "etc/config" / ("added" if number else "removed")).write_text("vendor payload")
            (vendor / "etc/config/link").symlink_to("added" if number else "removed")
            (vendor / "etc/config/long-link").symlink_to("../" * 30 + ("added" if number else "removed"))
            raw = self.dir / ("vendor" + str(number) + ".ext4")
            with raw.open("wb") as f:
                f.truncate(8 << 20)
            subprocess.run([delta.find_tool("MKE2FS", "mke2fs"), "-q", "-t", "ext4", "-d", str(vendor), str(raw)],
                           check=True, capture_output=True)
            cmds = [f"ea_set {p} security.selinux u:object_r:vendor_file:s0"
                    for p in ("/", "/etc", "/etc/config", "/lib64", "/lib64/test.so",
                              "/etc/config/link", "/etc/config/long-link", "/etc/config/" + ("added" if number else "removed"))]
            commands = self.dir / "labels.commands"
            commands.write_text("\n".join(cmds) + "\n")
            subprocess.run([delta.find_tool("DEBUGFS", "debugfs"), "-w", "-f", str(commands), str(raw)],
                           check=True, capture_output=True)
            image(img, system=erofs.read_bytes(), vendor=raw.read_bytes(), build=b"67890" if number else b"12345")
        return self.dir / "tree0", self.dir / "vendor0"

    def test_real_images_archive_report_and_compression(self):
        self.prepare()
        compressed = self.dir / "old.img.xz"
        with self.old.open("rb") as src, lzma.open(compressed, "wb") as dst:
            shutil.copyfileobj(src, dst, 65536)
        compressed_new = self.dir / "new.img.gz"
        with self.new.open("rb") as src, gzip.open(compressed_new, "wb") as dst:
            shutil.copyfileobj(src, dst, 65536)
        report = self.run_generator(image=str(compressed), delta=str(compressed_new), warn_bytes=1)
        self.assertIn("BOOT/andr36oid-build.txt", report)
        self.assertIn("/system/etc/init/test.rc", report)
        self.assertIn("/vendor/lib64/module.ko", report)
        self.assertIn("WARNING: archive", report)
        self.assertIn("/system/etc/config: rebuilt", report)
        self.assertIn("/vendor/etc/config: rebuilt", report)
        with zipfile.ZipFile(self.output) as z:
            manifest = z.read("manifest.txt")
            self.assertIn(b"build=v1-test|12345\n", manifest)
            self.assertIn(b"Delta to v1-test|67890", manifest)
            script = z.read("hotfix.sh")
            self.assertIn(b"hotfix_bind files/0 /system/lib64/test.so", script)
            self.assertIn(b"chown 1234:2345", script)
            self.assertIn(b"chmod 0755", script)
            self.assertIn(b"u:object_r:vendor_file:s0", script)
            self.assertNotIn(b"/etc/init/test.rc", script)
            self.assertNotIn(b"module.ko", script)
            self.assertIn(b"removed", script)
            self.assertEqual(len(z.namelist()), 6)
        if os.environ.get("HOTFIX_UNPACK"):
            subprocess.run([os.environ["HOTFIX_UNPACK"], str(self.output), str(self.dir / "unpacked"), "268435456"],
                           check=True, capture_output=True)

    def test_generated_script_in_mksh(self):
        root, vendor = self.prepare()
        self.run_generator()
        (root / "vendor").mkdir()
        shutil.copytree(vendor, root / "vendor", dirs_exist_ok=True, symlinks=True)
        unpacked, store, commands = self.dir / "unpacked", self.dir / "bind", self.dir / "bin"
        store.mkdir()
        commands.mkdir()
        with zipfile.ZipFile(self.output) as z:
            z.extractall(unpacked)
        toybox = Path("/andr36oid/src/prebuilts/build-tools/linux-x86/bin/toybox")
        if toybox.is_file():
            for cmd in ("cp", "rm", "mkdir", "ln", "readlink", "mktemp", "chmod"):
                (commands / cmd).symlink_to(toybox)
        scripts = {
            "mount": '#!/bin/sh\n[ "$1:$2" = "-o:bind" ] || exit 1\nprintf "%s\\t%s\\n" "$3" "$4" >>"$TRACE"\n',
            "chown": '#!/bin/sh\nexit 0\n',
            "chcon": '#!/bin/sh\n[ "$1" != -h ] || shift\n[ -e "$2" ] || [ -L "$2" ] || exit 1\n',
            "log": '#!/bin/sh\nexit 0\n',
            "stat": '#!/bin/sh\nif [ "$2" = %C ]; then echo u:object_r:system_file:s0; else exec /usr/bin/stat "$@"; fi\n',
        }
        for name, text in scripts.items():
            (commands / name).write_text(text)
            (commands / name).chmod(0o755)
        env = {**os.environ, "PATH": str(commands) + ":" + os.environ["PATH"], "HOTFIX_DIR": str(unpacked),
               "HOTFIX_STORE": str(store), "HOTFIX_ROOT": str(root), "TRACE": str(self.dir / "trace")}
        lib = Path(__file__).resolve().parents[1] / "helper/andr36oid-hotfix-lib.sh"
        shell = shutil.which("mksh")
        if not shell:
            self.skipTest("mksh unavailable")
        subprocess.run([shell, "-c", '. "$1"; . ./hotfix.sh', "test", str(lib)], cwd=unpacked, env=env,
                       check=True, capture_output=True)
        mounts = dict(line.split("\t")[::-1] for line in (self.dir / "trace").read_text().splitlines())
        for part in ("system", "vendor"):
            rebuilt = Path(mounts[str(root / part / "etc/config")])
            self.assertEqual((rebuilt / "added").read_text(), "payload" if part == "system" else "vendor payload")
            self.assertFalse((rebuilt / "removed").exists())
            self.assertEqual(os.readlink(rebuilt / "link"), "added")
            self.assertTrue((root / part / "etc/config/removed").exists())
        rebuilt = Path(mounts[str(root / "system/etc/config")])
        self.assertEqual((rebuilt / ".hidden").read_text(), "same hidden file")
        self.assertEqual((rebuilt / "stays").read_text(), "same")
        self.assertEqual(os.readlink(rebuilt / "dash-link"), "-target")
        vendor_copy = Path(mounts[str(root / "vendor/etc/config")])
        self.assertEqual(os.readlink(vendor_copy / "long-link"), "../" * 30 + "added")

    def test_boot_space_uses_easyroms(self):
        self.prepare()
        with mock.patch.object(delta.Fat, "free", return_value=512):
            self.run_generator()
        self.assertTrue(self.output.exists())
        self.assertIn("place this hotfix in EASYROMS/hotfix", self.output.with_name("delta.report.txt").read_text())

    def test_both_size_limits_fail_with_report(self):
        self.prepare()
        with mock.patch.object(tool, "EASYROMS_MAX_ZIP", 1):
            with self.assertRaisesRegex(ValueError, "exceeds both packed limits"):
                self.run_generator()
        self.assertFalse(self.output.exists())
        self.assertIn("FAILED:", self.output.with_name("delta.report.txt").read_text())

    def test_without_root(self):
        if os.geteuid() != 0:
            self.prepare()
            self.run_generator()
            return
        self.prepare()
        # Enter scratch before dropping privileges; /root itself stays private.
        self.dir.chmod(0o777)
        for parent, dirs, files in os.walk(self.dir):
            for name in dirs:
                (Path(parent) / name).chmod(0o755)
            for name in files:
                path = Path(parent) / name
                if not path.is_symlink():
                    path.chmod(0o644)
        script = Path(tool.__file__).resolve()
        driver = ("import os,runpy,sys,tempfile; "
                  "os.setgroups([]); os.setgid(65534); os.setuid(65534); tempfile.tempdir='.'; "
                  "mkdtemp=tempfile.mkdtemp; tempfile.mkdtemp=lambda *a,**k: os.path.relpath(mkdtemp(*a,**k)); "
                  f"sys.path.insert(0, {str(script.parent)!r}); "
                  f"sys.argv=[{str(script)!r},'old.img','--delta','new.img','--name','delta']; "
                  f"runpy.run_path({str(script)!r},run_name='__main__')")
        result = subprocess.run([os.sys.executable, "-c", driver], cwd=self.dir, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(self.output.is_file())

    def test_real_fat16_nested_boot_changes(self):
        try:
            mkfs = delta.find_tool("MKFS_FAT", "mkfs.fat")
            mcopy = delta.find_tool("MCOPY", "mcopy")
        except ValueError as e:
            self.skipTest(str(e))
        for number, img in enumerate((self.old, self.new)):
            tree = self.dir / ("boot" + str(number))
            (tree / "Profiles/Test").mkdir(parents=True)
            (tree / "Profiles/Test/boot.ini").write_text("boot config " + str(number))
            (tree / "Image").write_text("kernel " + str(number))
            (tree / "ramdisk.uimg").write_text("ramdisk " + str(number))
            (tree / "andr36oid-build.txt").write_text("format=1\nro.andr36oid.version=test\nro.product.build.version.incremental=1\n")
            fat = self.dir / ("boot" + str(number) + ".fat")
            with fat.open("wb") as dst:
                dst.truncate(8 << 20)
            subprocess.run([mkfs, "-F", "16", "-s", "1", str(fat)], check=True, capture_output=True)
            subprocess.run([mcopy, "-s", "-i", str(fat), *[str(p) for p in tree.iterdir()], "::/"], check=True, capture_output=True)
            image(img, boot=fat.read_bytes())
        with self.old.open("rb") as a, self.new.open("rb") as b:
            old, new = tool.Image(a), tool.Image(b)
            self.assertEqual(old.build(), "test|1")
            self.assertGreater(delta.Fat(old).free(), 7 << 20)
            report = "\n".join(delta.image_changes(old, new))
        for path in ("BOOT/Image", "BOOT/ramdisk.uimg", "BOOT/Profiles/Test/boot.ini"):
            self.assertIn(path, report)

    def test_partition_and_bootloader_changes(self):
        image(self.old)
        image(self.new)
        with self.old.open("rb") as a, self.new.open("rb") as b:
            old, new = tool.Image(a), tool.Image(b)
            new.layout = []
            old.parts["uboot"] = (0, 512)
            new.parts["uboot"] = (512, 512)
            report = "\n".join(delta.image_changes(old, new))
        self.assertIn("partition table", report)
        self.assertIn("partition uboot", report)

    def test_reserved_loader_changes(self):
        image(self.old)
        image(self.new)
        with self.new.open("r+b") as f:
            f.seek(40 * 512)
            f.write(b"changed loader")
        with self.old.open("rb") as a, self.new.open("rb") as b:
            report = "\n".join(delta.image_changes(tool.Image(a), tool.Image(b)))
        self.assertIn("bootloader/unpartitioned data changed", report)

    def test_bad_fat_chain_and_stream_copy_bound(self):
        image(self.old)
        with self.old.open("rb") as f:
            fat = delta.Fat(tool.Image(f))
            table = bytearray(fat.table)
            struct.pack_into("<I", table, 3 * 4, 3)
            fat.table = table
            with self.assertRaisesRegex(ValueError, "invalid FAT chain"):
                list(fat.chain(3))
        compressed = self.dir / "old.img.xz"
        compressed.write_bytes(lzma.compress(self.old.read_bytes()))
        with mock.patch.object(tool.shutil, "copyfileobj", wraps=shutil.copyfileobj) as copyfile:
            with tool.open_image(compressed, self.dir) as img:
                self.assertEqual(img.build(), "v1-test|12345")
            self.assertEqual(copyfile.call_args.args[2], 1048576)


if __name__ == "__main__":
    unittest.main()
