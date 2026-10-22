#!/usr/bin/env python3
"""Create a build-specific hotfix from a release image without mounting it."""

import argparse
import contextlib
import gzip
import lzma
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import struct
import subprocess
import sys
import tempfile
import unicodedata
import zipfile
import zlib

import hotfix_delta as delta

BOOT_MAX_ZIP = 64 << 20
EASYROMS_MAX_ZIP = 128 << 20


class Image:
    def __init__(self, file):
        self.file = file
        file.seek(0, os.SEEK_END)
        self.size = file.tell()
        header = bytearray(self.read(512, 512))
        if header[:8] != b"EFI PART":
            raise ValueError("image has no GPT")
        size, crc = struct.unpack_from("<II", header, 12)
        if not 92 <= size <= 512:
            raise ValueError("invalid GPT header size")
        header[16:20] = b"\0" * 4
        if zlib.crc32(header[:size]) != crc:
            raise ValueError("GPT header checksum mismatch")
        sector, count, entry_size, crc = struct.unpack_from("<QIII", header, 72)
        if entry_size < 128 or entry_size % 8 or not 0 < count * entry_size <= 1048576:
            raise ValueError("invalid GPT entries")
        table = self.read(sector * 512, count * entry_size)
        if zlib.crc32(table) != crc:
            raise ValueError("GPT table checksum mismatch")
        self.parts = {}
        self.layout = [(sector, count, entry_size, bytes(header[40:56]))]
        for i in range(count):
            e = table[i * entry_size:(i + 1) * entry_size]
            if e[:16] == b"\0" * 16:
                continue
            first, last = struct.unpack_from("<QQ", e, 32)
            name = e[56:128].decode("utf-16le").split("\0", 1)[0]
            if first > last or (last + 1) * 512 > self.size or name in self.parts:
                raise ValueError("invalid or duplicate GPT partition")
            self.parts[name] = (first * 512, (last - first + 1) * 512)
            self.layout.append((i, name, e[:16], first, last, e[48:56]))

    def read(self, offset, size):
        if offset < 0 or size < 0 or offset + size > self.size:
            raise ValueError("image read outside bounds")
        self.file.seek(offset)
        b = self.file.read(size)
        if len(b) != size:
            raise ValueError("truncated image")
        return b

    def part_read(self, part, offset, size):
        start, length = self.parts[part]
        if offset < 0 or offset + size > length:
            raise ValueError("partition read outside bounds")
        return self.read(start + offset, size)

    def boot_file(self, name):
        return delta.Fat(self).file(name)

    def build(self):
        values = {}
        for line in self.boot_file("andr36oid-build.txt").decode("utf-8").splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                if k in values:
                    raise ValueError("duplicate build metadata field")
                values[k] = v
        if values.get("format") != "1":
            raise ValueError("unsupported build metadata format")
        fields = [values.get(k, "") for k in
                  ("ro.andr36oid.version", "ro.product.build.version.incremental")]
        if any(not f or len(f.encode()) > 400 or f != f.strip() or any(unicodedata.category(c) == "Cc" or c == "|" for c in f) for f in fields):
            raise ValueError("missing or invalid running build identity")
        return "|".join(fields)

    def check_targets(self, targets, work):
        for part in sorted({p.split("/")[1] for p in targets}):
            if part not in self.parts:
                raise ValueError("image has no " + part + " partition")
            raw = work / (part + ".img")
            start, length = self.parts[part]
            with raw.open("wb") as out:
                for off in range(0, length, 1048576):
                    out.write(self.read(start + off, min(1048576, length - off)))
            magic = self.part_read(part, 1024, 4)
            if magic == b"\xe2\xe1\xf5\xe0":
                tool = os.environ.get("DUMP_EROFS", "dump.erofs")
                kind = "erofs"
            elif self.part_read(part, 1080, 2) == b"\x53\xef":
                tool = os.environ.get("DEBUGFS", "debugfs")
                kind = "ext4"
            else:
                raise ValueError("unknown filesystem on " + part + "; use --no-check if needed")
            resolved = shutil.which(tool) or shutil.which(tool, path="/usr/sbin:/sbin")
            if not resolved:
                raise ValueError(tool + " is required to check targets; install it or use --no-check")
            tool = resolved
            for target in targets:
                if target.split("/")[1] != part:
                    continue
                # Android 11 system-as-root has /system inside the system image.
                path = target if part == "system" else target[len("/vendor"):]
                args = ([tool, "--path=" + path, str(raw)] if kind == "erofs" else
                        [tool, "-R", 'stat "' + path + '"', str(raw)])
                result = subprocess.run(args, capture_output=True, text=True, timeout=30)
                text = result.stdout + result.stderr
                regular = re.search(r"(Type: regular|Size:.*regular file)", text, re.I)
                if result.returncode or not regular:
                    raise ValueError("target is missing or is not a regular file: " + target)
            raw.unlink()


@contextlib.contextmanager
def open_image(path, work):
    opener = lzma.open if path.suffix == ".xz" else gzip.open if path.suffix == ".gz" else None
    if opener:
        raw = work / "release.img"
        with opener(path, "rb") as src, raw.open("wb") as dst:
            shutil.copyfileobj(src, dst, 1048576)
        path = raw
    with path.open("rb") as file:
        yield Image(file)


def pack(work, out, manifest, script, payloads, free, unit, warn_bytes):
    total = len(manifest.encode()) + len(script) + sum(p.stat().st_size for p in payloads.values())
    if not script or total > 128 << 20 or len(payloads) > 510:
        raise ValueError("hotfix exceeds device limits (128 MiB unpacked, 512 entries)")
    packed = work / "hotfix.zip"
    with zipfile.ZipFile(packed, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.txt", manifest)
        z.writestr("hotfix.sh", script)
        for name, source in payloads.items():
            z.write(source, name)
    size = packed.stat().st_size
    if size > EASYROMS_MAX_ZIP:
        raise ValueError("hotfix exceeds both packed limits (64 MiB BOOT, 128 MiB EASYROMS)")
    # Allow clusters for the archive and growth of the hotfix directory and its LFN.
    needed = ((size + unit - 1) // unit + 2) * unit
    warnings = []
    if size > BOOT_MAX_ZIP or needed > free:
        warnings.append(f"place this hotfix in EASYROMS/hotfix: {size} bytes packed; "
                        f"BOOT allows at most {BOOT_MAX_ZIP} bytes packed and needs {needed} bytes "
                        f"with FAT overhead, {free} bytes free in OLD BOOT; "
                        f"EASYROMS allows {EASYROMS_MAX_ZIP} bytes packed (check the card's free space)")
    if size > warn_bytes:
        warnings.append(f"archive is {size} bytes, above --warn-bytes={warn_bytes}")
    # Finish in scratch first, so a failed check leaves no partial hotfix.
    with out.open("xb") as dst:
        try:
            with packed.open("rb") as src:
                shutil.copyfileobj(src, dst)
        except OSError:
            out.unlink()
            raise
    return size, total, warnings


def create_delta(args, work, out):
    if args.replace or args.script or args.no_check:
        raise ValueError("--delta cannot be combined with --replace, --script or --no-check")
    report = out.with_name(out.name[:-8] + ".report.txt")
    if report.exists():
        raise ValueError("report already exists: " + str(report))
    old_work, new_work = work / "old", work / "new"
    old_work.mkdir()
    new_work.mkdir()
    with open_image(Path(args.image), old_work) as old, open_image(Path(args.delta), new_work) as new:
        build, new_build = old.build(), new.build()
        boot = delta.Fat(old)
        free, unit = boot.free(), boot.unit
        boot_size = old.parts["BOOT"][1]
        omitted = delta.image_changes(old, new)
        a, b = {}, {}
        for part in ("system", "vendor"):
            if part not in old.parts or part not in new.parts:
                raise ValueError("both images must contain " + part)
            print("Comparing " + part + "...", file=sys.stderr)
            a.update(delta.read_tree(old, part, old_work / part))
            b.update(delta.read_tree(new, part, new_work / part))
        changes, files, directories, excluded = delta.plan(a, b, args.max_dir_bytes, args.max_dir_entries)
        script, payloads, warnings = delta.script(a, b, files, directories)
        included = [p for p in changes if p not in excluded]
        summary = (f"Delta to {new_build}: {len(files)} file binds, {len(directories)} directory rebuilds, "
                   f"{len(excluded) + len(omitted)} changes need full update; "
                   + (", ".join(included) if included else "no hotfixable file changes"))
        description = ((args.description + "; ") if args.description else "") + summary
        description = description.encode()[:1021].decode("utf-8", "ignore") + ("..." if len(description.encode()) > 1021 else "")
        manifest = f"name={args.name}\ndescription={description}\nbuild={build}\n"
        lines = [f"Hotfix delta: {args.name}", f"OLD: {args.image} ({build})", f"NEW: {args.delta} ({new_build})",
                 f"BOOT: {boot_size} bytes total, {free} bytes free in OLD image",
                 f"Packed limits: BOOT {BOOT_MAX_ZIP} bytes, EASYROMS {EASYROMS_MAX_ZIP} bytes",
                 f"Directory limit: {args.max_dir_bytes} bytes, {args.max_dir_entries} entries per rebuilt directory",
                 "", "Included:"]
        for path in files:
            lines.append("  " + path + ": regular file bind (content and new metadata)")
        for parent in directories:
            lines.append("  " + parent + ": rebuilt copy on /data, bound over existing directory")
            for path in changes:
                if delta.under(path, parent):
                    kind = "added" if path not in a else "deleted" if path not in b else "changed"
                    lines.append("    " + path + ": " + kind)
        if not files and not directories:
            lines.append("  (none; archive only records the matching build)")
        lines.extend(["", "Left out (full update required):"])
        lines.extend("  " + p + ": " + why for p, why in sorted(excluded.items()))
        lines.extend("  " + item for item in omitted)
        if not excluded and not omitted:
            lines.append("  (none detected)")
        lines.extend(["", "Limits:", "  BOOT is never written; kernel, DTBs, ramdisks, bootloader and partition changes are report-only.",
                      "  Build properties and code/policy loaded before hotfixes keep their old behavior.",
                      "  Directory copies use /data on every start; existing mounts or earlier hotfixes can affect them.",
                      "  Fresh-image BOOT space excludes user files and other installed hotfixes. Check the console's free space.",
                      "  Random GPT disk/partition GUIDs and filesystem timestamps are ignored.",
                      "  Changes require hardware validation; no full-release equivalence is promised."])
        failure = None
        try:
            size, total, size_warnings = pack(work, out, manifest, script, payloads, free, unit, args.warn_bytes)
            warnings.extend(size_warnings)
            lines.extend(["", f"Archive: {out} ({size} bytes packed, {total} bytes unpacked, {len(payloads) + 2} entries)"])
        except ValueError as e:
            failure = e
            lines.extend(["", "FAILED: " + str(e)])
        lines.extend("WARNING: " + warning for warning in warnings)
        text = "\n".join(lines) + "\n"
        with report.open("x") as dst:
            dst.write(text)
        print(text, end="")
        if failure:
            raise failure


def create(args):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", args.name):
        raise ValueError("name must be 1-64 letters, digits, dots, underscores or hyphens")
    if ((not args.description and not getattr(args, "delta", None))
            or (args.description and (len(args.description.encode()) > 1024
                or any(unicodedata.category(c) == "Cc" for c in args.description)))):
        raise ValueError("description must be one nonempty line, at most 1024 bytes")
    out = Path(args.output or args.name + ".fix.zip")
    if not out.name.endswith(".fix.zip"):
        raise ValueError("output must end in .fix.zip")
    if out.exists():
        raise ValueError("output already exists: " + str(out))
    if any(getattr(args, flag, 1) <= 0 for flag in ("warn_bytes", "max_dir_bytes", "max_dir_entries")):
        raise ValueError("size and directory limits must be positive")
    if getattr(args, "delta", None):
        with tempfile.TemporaryDirectory(prefix="make-hotfix-") as temp:
            create_delta(args, Path(temp), out)
        return
    replacements = {}
    for item in args.replace:
        target, sep, source = item.partition("=")
        if (not sep or not re.fullmatch(r"/(system|vendor)/[A-Za-z0-9_./+@-]+", target)
                or str(PurePosixPath(target)) != target or ".." in target.split("/")
                or target in replacements):
            raise ValueError("invalid or duplicate replacement: " + item)
        source = Path(source)
        if not source.is_file():
            raise ValueError("replacement is not a file: " + str(source))
        replacements[target] = source
    if not replacements and not args.script:
        raise ValueError("provide --replace or --script")
    with tempfile.TemporaryDirectory(prefix="make-hotfix-") as temp:
        work = Path(temp)
        with open_image(Path(args.image), work) as image:
            build = image.build()
            boot = delta.Fat(image)
            free, unit = boot.free(), boot.unit
            if args.no_check:
                print("WARNING: replacement targets were not checked in the release image", file=sys.stderr)
            elif replacements:
                image.check_targets(replacements, work)
        manifest = f"name={args.name}\ndescription={args.description}\nbuild={build}\n"
        if args.script:
            script = Path(args.script).read_bytes()
        else:
            lines = ["#!/system/bin/sh", "# Reapplied on every start. Keep this script idempotent.", "set -e"]
            for i, target in enumerate(replacements):
                lines.append("hotfix_bind " + shlex.quote(f"files/{i}") + " " + shlex.quote(target))
            script = ("\n".join(lines) + "\n").encode()
        payloads = {f"files/{i}": p for i, p in enumerate(replacements.values())}
        _, _, warnings = pack(work, out, manifest, script, payloads, free, unit, getattr(args, "warn_bytes", 16 << 20))
        for warning in warnings:
            print("WARNING: " + warning, file=sys.stderr)
        print(f"{out}: build={build}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", help="release .img, .img.xz or .img.gz (OLD in delta mode)")
    parser.add_argument("--delta", metavar="NEW_IMAGE", help="compare OLD image with NEW_IMAGE and write a report")
    parser.add_argument("--name", required=True)
    parser.add_argument("--description", help="required for manual fixes; optional prefix for delta summary")
    parser.add_argument("--replace", action="append", default=[], metavar="TARGET=FILE")
    parser.add_argument("--script", help="custom hotfix.sh (helpers are already sourced)")
    parser.add_argument("--no-check", action="store_true", help="skip target existence checks, with a warning")
    parser.add_argument("--output", help="output <name>.fix.zip (must not exist)")
    parser.add_argument("--max-dir-bytes", type=int, default=8 << 20, help="directory rebuild limit (default: 8388608)")
    parser.add_argument("--max-dir-entries", type=int, default=256, help="directory rebuild entry limit (default: 256)")
    parser.add_argument("--warn-bytes", type=int, default=16 << 20, help="warn above this packed size (default: 16777216)")
    args = parser.parse_args()
    try:
        create(args)
    except (ValueError, OSError, EOFError, KeyError, struct.error, subprocess.SubprocessError) as e:
        parser.exit(1, f"make-hotfix: {e}\n")


if __name__ == "__main__":
    main()
