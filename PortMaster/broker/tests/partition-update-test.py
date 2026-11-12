#!/usr/bin/env python3
"""Real EROFS/GPT update commands on sparse files, NEVER host block devices.

The production build must reject the exact same fake disk; only the second
binary uses A36_PARTITION_FIXTURE. No host mount, global sync or /sys writes.
"""
from pathlib import Path
import copy
import hashlib
import json
import shutil
import struct
import subprocess
import tempfile
import zlib
import runpy

common = Path(__file__).resolve().parents[3]
source = common / "PortMaster/erofs-tools/andr36oid-erofs-support.c"
with tempfile.TemporaryDirectory(prefix="a36-partition-update-", dir="/ramdisk") as tmp:
    base = Path(tmp)
    fixture, production = base / "fixture", base / "production"
    for target, flags in [(fixture, ["-DA36_PARTITION_FIXTURE"]), (production, [])]:
        subprocess.run(["cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", *flags,
                        str(source), "-o", str(target)], check=True)
    disk, part, sys, cache, boot = [base / n for n in ["mmcblk9", "mmcblk9p14", "sys", "cache", "BOOT"]]
    cache.mkdir()
    boot.mkdir()
    node = sys / part.name
    node.mkdir(parents=True)
    sectors = 16777216
    first = (sectors - 33 - 16384) // 2048 * 2048
    last = sectors - 34
    capacity = (last - first + 1) * 512
    for key, value in [("partition", 14), ("start", first), ("size", last - first + 1)]:
        (node / key).write_text(str(value) + "\n")
    entries = bytearray(16384)
    for index, name, start, end in [(0, "BOOT", 32768, 557055), (1, "system", 1081344, 5701008),
                                    (5, "userdata", 5701632, first - 1), (13, "DARKOS", first, last)]:
        row = bytearray(128)
        row[:16] = bytes.fromhex("af3dc60f838472478e793d69d8477de4")
        row[16:32] = hashlib.md5(name.encode()).digest()
        struct.pack_into("<QQ", row, 32, start, end)
        row[56:56 + len(name) * 2] = name.encode("utf-16le")
        entries[index * 128:(index + 1) * 128] = row
    header = bytearray(512)
    header[:8] = b"EFI PART"
    struct.pack_into("<II", header, 8, 0x10000, 92)
    struct.pack_into("<QQQQ", header, 24, 1, sectors - 1, 34, sectors - 34)
    header[56:72] = bytes(range(1, 17))
    struct.pack_into("<QIII", header, 72, 2, 128, 128, zlib.crc32(entries))
    struct.pack_into("<I", header, 16, zlib.crc32(header[:92]))
    with disk.open("wb") as out:
        out.truncate(sectors * 512)
        out.seek(512)
        out.write(header + entries)
    frozen_gpt = disk.open("rb").read(32768)

    tree = base / "tree"
    (tree / "etc").mkdir(parents=True)
    qemu = shutil.which("qemu-aarch64") or shutil.which("qemu-aarch64-static")
    assert qemu
    images = []
    for revision in range(2):
        (tree / "etc/version").write_text("revision " + str(revision))
        image = base / ("old.erofs.img" if revision == 0 else "new.erofs.img")
        subprocess.run([qemu, str(common / "PortMaster/erofs-tools/mkfs.erofs"), "--quiet", "-b4096",
                        "-U11111111-2222-3333-4444-555555555555", "-zlz4hc,9", "-E^xattr-name-filter",
                        str(image), str(tree)], check=True)
        images.append(image)
    old, new = [p.read_bytes() for p in images]
    assert old[1072:1088] == new[1072:1088] and old != new
    part.write_bytes(old + b"X" * (capacity - len(old)))
    original_part = part.read_bytes()
    manifest = base / "old.json"
    manifest.write_text(json.dumps(dict(format=1, darkosen="10112026", revision=1, minimum_andr36oid="",
        image=dict(name=images[0].name, size=len(old), sha256=hashlib.sha256(old).hexdigest()))))
    (boot / "andr36oid-darkosen.json").write_bytes(manifest.read_bytes())
    request = base / "request.json"
    payload = dict(format=1, job="0123456789abcdef", darkosen="11112026", revision=1, minimum_andr36oid="",
        image=dict(name=images[1].name, size=len(new), sha256=hashlib.sha256(new).hexdigest()),
        download=dict(compression="xz", size=1, sha256="0" * 64, parts=[dict(name="part.xz", size=1, sha256="0" * 64)]))
    request.write_text(json.dumps(payload))
    meta = Path(str(images[1]) + ".meta")
    meta.write_bytes(subprocess.check_output([str(fixture), "release-record", str(request), payload["job"], "aaaa-1111", str(images[1])]))
    record_hash = hashlib.sha256(meta.read_bytes()).hexdigest()
    original_record = meta.read_bytes()
    pending = base / "new.json"
    progress = base / "progress"
    progress.touch()

    def run(command, *args, ok=True, helper=fixture):
        result = subprocess.run([str(helper), command, *map(str, args)], text=True, capture_output=True, timeout=15)
        assert (result.returncode == 0) == ok, (command, result)
        return result

    def info(which=manifest, ok=True):
        return run("partition-info", which, disk, part, sys, cache, "eng.test.20261112.010000", progress, ok=ok)

    first_info = dict(line.split("=", 1) for line in info().stdout.splitlines())
    family = first_info["source_id"]
    plan_args = (images[1], "aaaa-1111", disk, part, sys, "eng.test.20261112.010000", pending)
    run("partition-plan", *plan_args, ok=False, helper=production)
    assert part.read_bytes() == original_part
    plan = dict(line.split("=", 1) for line in run("partition-plan", *plan_args).stdout.splitlines())
    assert plan["fits"] == "1" and plan["source_id"] == family
    held_args = (pending, disk, part, sys, cache)
    copy_args = (pending, images[1], "aaaa-1111", disk, part, sys, cache, progress)
    run("partition-copy", *copy_args, ok=False)
    assert part.read_bytes() == original_part
    run("partition-hold", *held_args)
    assert "update pending" in info(ok=False).stderr
    # Even with unchanged UUID and a valid old cache, neither manifest is eligible.
    assert "update pending" in info(pending, ok=False).stderr
    run("partition-publish", *held_args, boot, ok=False)
    assert (boot / "andr36oid-darkosen.json").read_bytes() == manifest.read_bytes()
    # Full SHA happens BEFORE target writes. Header+UUID are unchanged by this tamper.
    damaged = bytearray(new)
    damaged[-1] ^= 1
    images[1].write_bytes(damaged)
    run("partition-copy", *copy_args, ok=False)
    assert part.read_bytes() == original_part
    images[1].write_bytes(new)
    # A killed copy's partial prefix also stays fail-closed until a complete retry.
    with part.open("r+b") as out:
        out.write(new[:2048])
    assert "update pending" in info(ok=False).stderr
    result = run("partition-copy", *copy_args)
    assert "verified=1" in result.stdout
    assert part.read_bytes() == new + original_part[len(new):]
    assert (boot / "andr36oid-darkosen.json").read_bytes() == manifest.read_bytes()
    assert "update pending" in info(pending, ok=False).stderr
    hidden = base / ".new.old"
    hidden_meta = base / ".new.old.meta"
    images[1].rename(hidden)
    meta.rename(hidden_meta)
    run("release-backup-info", hidden, hidden_meta, "aaaa-1111", images[1].name, plan["generation"], record_hash)
    run("release-backup-info", hidden, hidden_meta, "aaaa-1111", images[1].name, "0" * 64, record_hash, ok=False)
    hidden.rename(images[1])
    hidden_meta.rename(meta)
    run("partition-publish", *held_args, boot)
    assert (boot / "andr36oid-darkosen.json").read_bytes() == pending.read_bytes()
    assert "update pending" in info(pending, ok=False).stderr
    # Caller commits durable selection before this activation boundary.
    run("partition-activate", *held_args)
    ready = dict(line.split("=", 1) for line in info(pending).stdout.splitlines())
    assert ready["generation"] == plan["generation"] and ready["cache_hit"] == "1"
    target = dict(line.split("=", 1) for line in run("partition-target", disk, part, sys).stdout.splitlines())
    assert target["source_id"] == family and int(target["capacity"]) == capacity
    recheck = (pending, disk, part, sys, cache, "eng.test.20261112.010000", progress)
    run("partition-recheck", *recheck)
    with part.open("r+b") as out:
        out.seek(len(new) - 1)
        out.write(bytes([new[-1] ^ 1]))
    run("partition-recheck", *recheck, ok=False)
    with part.open("r+b") as out:
        out.seek(len(new) - 1)
        out.write(new[-1:])
    partial = base / (".a36-release-" + payload["job"] + ".partial")
    partial.write_bytes(new)
    run("release-partial-info", request, payload["job"], partial)
    run("release-partial-info", request, payload["job"], images[1], ok=False)
    partial.write_bytes(new[:-1])
    run("release-partial-info", request, payload["job"], partial, ok=False)
    assert disk.open("rb").read(32768) == frozen_gpt
    # Cleanup refuses a replaced/edited record and always retains the fallback on failure.
    run("release-remove-image", pending, images[1], "aaaa-1111", "0" * 64, ok=False)
    assert images[1].exists() and meta.exists()
    run("release-remove-image", pending, images[1], "aaaa-1111", record_hash)
    assert not images[1].exists() and not meta.exists()
    # Crash after the image unlink but before its record unlink is resumable;
    # already-absent cleanup is idempotent and still fsyncs the source directory.
    meta.write_bytes(original_record)
    run("release-remove-image", pending, images[1], "aaaa-1111", record_hash)
    run("release-remove-image", pending, images[1], "aaaa-1111", record_hash)
    assert not meta.exists()
    # Oversized releases are a file fallback: plan reports capacity, no guard/write.
    images[1].write_bytes(new + bytes(capacity + 4096 - len(new)))
    payload["image"]["size"] = images[1].stat().st_size
    payload["image"]["sha256"] = hashlib.sha256(images[1].read_bytes()).hexdigest()
    request.write_text(json.dumps(payload))
    meta.write_bytes(subprocess.check_output([str(fixture), "release-record", str(request), payload["job"], "aaaa-1111", str(images[1])]))
    before = part.read_bytes()
    plan = dict(line.split("=", 1) for line in run("partition-plan", *plan_args).stdout.splitlines())
    assert plan["fits"] == "0"
    run("partition-hold", *held_args, ok=False)
    assert part.read_bytes() == before and not (cache / (family + ".updating")).exists()
    print("PASS native DARKOS update: production rejects regular disks; stale-cache guard, full source preflight, partial-copy retry, flushed readback, BOOT publish, activation, bounded tail/GPT, exact cleanup, too-large file fallback")

    runpy.run_path(str(Path(__file__).with_name("partition-update-lifecycle.inc.py")), init_globals=locals())
