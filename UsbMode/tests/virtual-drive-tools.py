#!/usr/bin/env python3
"""Fake Android tools. Only regular files below the host fixture are touched."""
import os
from pathlib import Path
import sys

t = Path(os.environ["VIRTUAL_TEST_ROOT"])
tool = Path(sys.argv[0]).name
args = sys.argv[1:]
with (t / "tools.log").open("a") as log:
    log.write(tool + " " + repr(args) + "\n")
if tool == os.environ.get("VIRTUAL_CANCEL_TOOL"):
    (t / "props/sys.andr36oid.usbdrive.request").write_text("stop\n")
    sys.exit(1)
if tool == os.environ.get("VIRTUAL_FAIL_TOOL"):
    print(tool + ": simulated operation failed", file=sys.stderr)
    sys.exit(4)
if tool == "fsync":
    sys.exit(0)
if tool == "df":
    assert args[0] == "-Pk"
    print("Filesystem 1024-blocks Used Available Capacity Mounted on")
    print("fake 999999999 1 " + os.environ.get("VIRTUAL_FREE_KB", "900000000") + " 1% /easyroms")
    sys.exit(0)
loop = t / "loop"
if tool == "losetup":
    if args == ["-f"]:
        print("/dev/block/loop7")
    elif args[0] == "-j":
        if loop.exists() and loop.read_text().strip() == args[1]:
            print("/dev/block/loop7: [1]:2 (" + args[1] + ")")
    elif args[0] == "-d":
        if os.environ.get("VIRTUAL_FAIL_DETACH"):
            sys.exit(1)
        loop.unlink(missing_ok=True)
    else:
        assert args[0:3] == ["-o", "1048576", "--sizelimit"]
        assert args[4] == "/dev/block/loop7"
        image = Path(args[5]).resolve()
        assert image.is_relative_to(t.resolve())
        assert image.is_file()
        assert int(args[3]) == image.stat().st_size - 1048576
        loop.write_text(str(image))
    sys.exit(0)
if tool in ("truncate", "fallocate"):
    image = Path(args[-1]).resolve()
    assert image.is_relative_to(t.resolve())
    image.touch(exist_ok=True)
    with image.open("r+b") as f:
        f.truncate(int(args[1]))
    sys.exit(0)
if tool in ("newfs_msdos", "mkfs.exfat", "mkfs.ntfs", "mke2fs"):
    assert args[-1] == "/dev/block/loop7"
    assert "-L" in args
    if tool == "newfs_msdos":
        assert args[:3] == ["-F", "32", "-c"]
        assert args[3] in ("8", "32", "64")
        assert args[4:10] == ["-h", "255", "-u", "63", "-o", "2048"]
    elif tool == "mkfs.ntfs":
        assert args[:3] == ["-f", "-p", "2048"]
    elif tool == "mke2fs":
        assert args[:2] == ["-t", "ext4"]
    image = Path(loop.read_text().strip())
    assert image.is_relative_to(t.resolve())
    offset, signature = {
        "newfs_msdos": (1048576 + 82, b"FAT32   "),
        "mkfs.exfat": (1048576 + 3, b"EXFAT   "),
        "mkfs.ntfs": (1048576 + 3, b"NTFS    "),
        "mke2fs": (1048576 + 1080, b"\x53\xef"),
    }[tool]
    with image.open("r+b") as f:
        f.seek(offset)
        f.write(signature)
    sys.exit(0)
if tool in ("fsck_msdos", "fsck.exfat", "fsck.ntfs", "e2fsck"):
    assert Path(loop.read_text().strip()).is_relative_to(t.resolve())
    sys.exit(int(os.environ.get("VIRTUAL_FSCK_EXIT", "0")))
raise AssertionError(tool)
