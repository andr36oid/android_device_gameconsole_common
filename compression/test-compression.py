#!/usr/bin/env python3
"""Fresh-format guard + real shipped host make_f2fs/fsck, RAM-only files."""
import hashlib
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest

TOP = Path(__file__).resolve().parents[4]

def sha(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").digest()


class FreshTest(unittest.TestCase):
    def test_blank_only_name_capability_and_tools(self):
        with tempfile.TemporaryDirectory(prefix="userdata-compression.") as tmp:
            root = Path(tmp)
            binary = root / "helper"
            subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-DA36_COMPRESSION_TEST",
                            str(Path(__file__).with_name("userdata-compression.c")), "-o", str(binary)], check=True)
            image = root / "data.img"
            with image.open("wb") as f:
                f.truncate(256 << 20)
            feature, uevent = root / "feature", root / "uevent"
            feature.write_text("supported\n")
            uevent.write_text("PARTNAME=vendor\n")
            env = {**os.environ, "A36_COMPRESSION_FEATURE": str(feature),
                   "A36_COMPRESSION_UEVENT": str(uevent),
                   "A36_COMPRESSION_MKFS": str(TOP / "out/host/linux-x86/bin/make_f2fs")}
            def run():
                return subprocess.run([str(binary), "format-blank", str(image)], env=env,
                                      stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            self.assertNotEqual(run().returncode, 0)  # never format vendor, irrespective of number
            self.assertEqual(image.read_bytes()[:8192], b"\0" * 8192)
            uevent.write_text("PARTNAME=userdata\n")
            feature.write_text("unsupported\n")
            self.assertNotEqual(run().returncode, 0)
            feature.write_text("supported\n")
            with image.open("r+b") as f:
                f.seek(4096)
                f.write(b"existing filesystem or foreign data")
            before = sha(image)
            self.assertEqual(run().returncode, 0)
            self.assertEqual(sha(image), before)
            # A buggy tool which formats only one usable superblock must not
            # be accepted as a completed fresh compression format.
            broken_mkfs = root / "broken-mkfs"
            broken_mkfs.write_text("#!/usr/bin/python3\n"
                "import os,subprocess,sys\n"
                "subprocess.run([os.environ['A36_REAL_MKFS']]+sys.argv[1:],check=True)\n"
                "with open(sys.argv[-1],'r+b') as f:\n"
                " f.seek(int(os.environ['A36_BAD_SB'])+2180);f.write(bytes(4))\n")
            broken_mkfs.chmod(0o755)
            env["A36_REAL_MKFS"] = env["A36_COMPRESSION_MKFS"]
            env["A36_COMPRESSION_MKFS"] = str(broken_mkfs)
            for base in (1024, 5120):
                with image.open("r+b") as f:
                    f.write(b"\0" * 1048576)
                env["A36_BAD_SB"] = str(base)
                failed = run()
                self.assertNotEqual(failed.returncode, 0)
                self.assertIn(b"formatted filesystem lacks compression feature", failed.stdout)
            env["A36_COMPRESSION_MKFS"] = env.pop("A36_REAL_MKFS")
            with image.open("r+b") as f:
                f.write(b"\0" * 1048576)
            result = run()
            self.assertEqual(result.returncode, 0, result.stdout.decode())
            with image.open("rb") as f:
                for base in (1024, 5120):
                    f.seek(base)
                    self.assertEqual(f.read(4), bytes.fromhex("1020f5f2"))
                    f.seek(base + 2180)
                    self.assertEqual(struct.unpack("<I", f.read(4))[0] & 0x2008, 0x2008)
            subprocess.run([str(TOP / "out/host/linux-x86/bin/fsck.f2fs"), "-f", str(image)],
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            before = sha(image)
            self.assertEqual(run().returncode, 0)
            self.assertEqual(sha(image), before)


if __name__ == "__main__":
    unittest.main()
