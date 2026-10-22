"""Read release filesystems and plan changes which can survive a bind mount."""

from dataclasses import dataclass, field
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import stat
import struct
import subprocess
import unicodedata


HOST_BIN = "/andr36oid/src/out/host/linux-x86/bin"


def find_tool(env, name):
    tool = os.environ.get(env, name)
    resolved = shutil.which(tool, path=os.environ.get("PATH", "") + ":/usr/sbin:/sbin:" + HOST_BIN)
    if not resolved:
        raise ValueError(tool + " is required for delta mode")
    return resolved


def digest(file):
    h = hashlib.sha256()
    with file.open("rb") as src:
        for block in iter(lambda: src.read(1048576), b""):
            h.update(block)
    return h.hexdigest()


class Fat:
    def __init__(self, image):
        self.image = image
        b = image.part_read("BOOT", 0, 512)
        self.sector = struct.unpack_from("<H", b, 11)[0]
        cluster = b[13]
        self.reserved = struct.unpack_from("<H", b, 14)[0]
        fats = b[16]
        self.roots, small_total, fat_size = (struct.unpack_from("<H", b, p)[0] for p in (17, 19, 22))
        total = small_total or struct.unpack_from("<I", b, 32)[0]
        self.fat32 = fat_size == 0
        fat_size = fat_size or struct.unpack_from("<I", b, 36)[0]
        if (b[510:512] != b"\x55\xaa" or self.sector not in (512, 1024, 2048, 4096)
                or not cluster or cluster & (cluster - 1) or cluster > 128
                or not self.reserved or not fats or not fat_size
                or total * self.sector > image.parts["BOOT"][1]):
            raise ValueError("invalid FAT BOOT")
        self.root_offset = (self.reserved + fats * fat_size) * self.sector
        self.data = self.root_offset + ((self.roots * 32 + self.sector - 1) // self.sector) * self.sector
        self.unit = self.sector * cluster
        self.clusters = (total * self.sector - self.data) // self.unit
        self.width = 4 if self.fat32 else 2
        if (self.clusters <= 0 or (not self.fat32 and self.clusters < 4085)
                or (self.clusters + 2) * self.width > fat_size * self.sector):
            raise ValueError("BOOT must use valid FAT16 or FAT32")
        self.table = image.part_read("BOOT", self.reserved * self.sector, fat_size * self.sector)
        self.root_cluster = struct.unpack_from("<I", b, 44)[0] if self.fat32 else 0

    def next(self, cluster):
        n = int.from_bytes(self.table[cluster * self.width:(cluster + 1) * self.width], "little")
        return n & (0x0fffffff if self.fat32 else 0xffff)

    def chain(self, first):
        seen = set()
        while first < (0x0ffffff8 if self.fat32 else 0xfff8):
            if not 2 <= first < self.clusters + 2 or first in seen:
                raise ValueError("invalid FAT chain")
            seen.add(first)
            yield self.image.part_read("BOOT", self.data + (first - 2) * self.unit, self.unit)
            first = self.next(first)

    def directory(self, cluster):
        if cluster == 0 and not self.fat32:
            blocks = [self.image.part_read("BOOT", self.root_offset, self.roots * 32)]
        else:
            blocks = self.chain(cluster)
        long = {}
        checksum = None
        for block in blocks:
            for pos in range(0, len(block), 32):
                e = block[pos:pos + 32]
                if e[0] == 0:
                    return
                if e[0] == 0xe5:
                    long = {}
                    continue
                if e[11] == 15:
                    if e[0] & 0x40:
                        long = {}
                        checksum = e[13]
                    long[e[0] & 31] = e[1:11] + e[14:26] + e[28:32]
                    continue
                short = e[:8].decode("cp437").rstrip()
                ext = e[8:11].decode("cp437").rstrip()
                if e[12] & 8:
                    short = short.lower()
                if e[12] & 16:
                    ext = ext.lower()
                short += "." + ext if ext else ""
                check = 0
                for byte in e[:11]:
                    check = (((check & 1) << 7) + (check >> 1) + byte) & 255
                name = short
                if long and check == checksum and sorted(long) == list(range(1, len(long) + 1)):
                    name = b"".join(long[i] for i in sorted(long)).decode("utf-16le").split("\0", 1)[0]
                long = {}
                if e[11] & 8 or name in (".", ".."):
                    continue
                if not name or any(c in name for c in "/\\\0"):
                    raise ValueError("invalid BOOT filename")
                first = struct.unpack_from("<H", e, 26)[0]
                if self.fat32:
                    first |= struct.unpack_from("<H", e, 20)[0] << 16
                yield name, bool(e[11] & 16), first, struct.unpack_from("<I", e, 28)[0]

    def content(self, first, size):
        remaining = size
        if size:
            for block in self.chain(first):
                take = min(remaining, len(block))
                if take:
                    yield block[:take]
                remaining -= take
                if not remaining:
                    return
        if remaining:
            raise ValueError("truncated BOOT file")

    def file(self, name):
        for full, directory, first, size in self.directory(self.root_cluster):
            if full.casefold() == name.casefold():
                if directory or not 0 < size <= 65536:
                    raise ValueError("invalid build metadata file")
                return b"".join(self.content(first, size))
        raise ValueError("BOOT/" + name + " is missing")

    def tree(self):
        files = {}
        seen = set()

        def walk(cluster, parent):
            if cluster in seen:
                raise ValueError("cycle in BOOT directories")
            seen.add(cluster)
            for name, directory, first, size in self.directory(cluster):
                path = parent + "/" + name
                if path in files:
                    raise ValueError("duplicate BOOT filename")
                if directory:
                    files[path] = ("directory", "")
                    walk(first, path)
                else:
                    h = hashlib.sha256()
                    for block in self.content(first, size):
                        h.update(block)
                    files[path] = ("file", h.hexdigest())

        walk(self.root_cluster, "")
        return files

    def free(self):
        return sum(self.next(i) == 0 for i in range(2, self.clusters + 2)) * self.unit


@dataclass
class Entry:
    mode: int
    uid: int
    gid: int
    size: int = 0
    content: str = ""
    link: str = ""
    attrs: dict = field(default_factory=dict)
    source: Path = None

    def signature(self):
        return self.mode, self.uid, self.gid, self.content, self.link, self.attrs


def safe_path(path):
    return (path.startswith("/") and str(PurePosixPath(path)) == path
            and ".." not in path.split("/") and "\\" not in path
            and not any(unicodedata.category(c) == "Cc" for c in path))


class Erofs:
    # Metadata uses the same on-disk layout as erofs/erofs-ls.py in the board overlay.
    def __init__(self, file):
        self.file = file
        b = self.read(1024, 128)
        if b[:4] != b"\xe2\xe1\xf5\xe0" or not 9 <= b[12] <= 16:
            raise ValueError("invalid EROFS superblock")
        self.bs = 1 << b[12]
        self.root = struct.unpack_from("<H", b, 14)[0]
        self.meta, self.xattr = struct.unpack_from("<II", b, 40)

    def read(self, offset, size):
        if offset < 0 or size < 0:
            raise ValueError("invalid EROFS offset")
        self.file.seek(offset)
        data = self.file.read(size)
        if len(data) != size:
            raise ValueError("truncated EROFS metadata")
        return data

    def xentry(self, offset):
        length, index, size = struct.unpack("<BBH", self.read(offset, 4))
        prefixes = {1: "user.", 2: "system.posix_acl_access", 3: "system.posix_acl_default",
                    4: "trusted.", 6: "security."}
        if index not in prefixes:
            raise ValueError("unsupported EROFS xattr prefix; use the release's erofs-utils format")
        b = self.read(offset + 4, length + size)
        return prefixes[index] + b[:length].decode(), b[length:], (4 + length + size + 3) & ~3

    def inode(self, nid):
        offset = self.meta * self.bs + nid * 32
        b = self.read(offset, 64)
        fmt, count, mode = struct.unpack_from("<HHH", b)
        extended = fmt & 1
        size = struct.unpack_from("<Q" if extended else "<I", b, 8)[0]
        raw = struct.unpack_from("<I", b, 16)[0]
        uid, gid = struct.unpack_from("<II" if extended else "<HH", b, 24)
        inode_size = 64 if extended else 32
        xsize = 12 + (count - 1) * 4 if count else 0
        attrs = {}
        if xsize:
            p = offset + inode_size
            shared = self.read(p + 4, 1)[0]
            q = p + 12
            if q + shared * 4 > p + xsize:
                raise ValueError("invalid EROFS shared xattrs")
            for i in range(shared):
                sid = struct.unpack("<I", self.read(q, 4))[0]
                q += 4
                name, value, _ = self.xentry(self.xattr * self.bs + sid * 4)
                attrs[name] = value
            while q < p + xsize:
                name, value, length = self.xentry(q)
                attrs[name] = value
                q += length
            if q != p + xsize:
                raise ValueError("invalid EROFS inline xattrs")
        return Entry(mode, uid, gid, size, attrs=attrs), (fmt >> 1) & 7, raw, offset + inode_size + xsize

    def data(self, entry, layout, raw, inline):
        if layout == 0:
            return self.read(raw * self.bs, entry.size)
        if layout == 2:
            full = entry.size // self.bs * self.bs
            return self.read(raw * self.bs, full) + self.read(inline, entry.size - full)
        raise ValueError("unsupported compressed EROFS directory or symlink")

    def tree(self, extracted):
        entries = {}
        ancestors = set()

        def walk(nid, path):
            entry, layout, raw, inline = self.inode(nid)
            entries[path or "/"] = entry
            if stat.S_ISDIR(entry.mode):
                if nid in ancestors:
                    raise ValueError("cycle in EROFS directories")
                ancestors.add(nid)
                data = self.data(entry, layout, raw, inline)
                for start in range(0, len(data), self.bs):
                    block = data[start:start + self.bs]
                    count = struct.unpack_from("<H", block, 8)[0] // 12
                    offsets = [struct.unpack_from("<QHBB", block, i * 12) for i in range(count)]
                    for i, (child, offset, _, _) in enumerate(offsets):
                        end = offsets[i + 1][1] if i + 1 < count else len(block)
                        name = block[offset:end].split(b"\0", 1)[0].decode()
                        if name in (".", ".."):
                            continue
                        target = path + "/" + name
                        if "/" in name or not safe_path(target) or target in entries:
                            raise ValueError("unsafe or duplicate EROFS path: " + repr(target))
                        walk(child, target)
                ancestors.remove(nid)
            elif stat.S_ISREG(entry.mode):
                entry.source = extracted / path.lstrip("/")
                if entry.source.is_symlink() or not entry.source.is_file():
                    raise ValueError("EROFS extraction did not produce " + path)
                entry.source.chmod(0o600)
                entry.content = digest(entry.source)
                if entry.source.stat().st_size != entry.size:
                    raise ValueError("EROFS extraction size mismatch: " + path)
            elif stat.S_ISLNK(entry.mode):
                entry.link = self.data(entry, layout, raw, inline).decode()

        walk(self.root, "")
        return entries


def debugfs(raw, work, commands):
    batch = work / "debugfs.commands"
    batch.write_text("\n".join(commands) + "\n")
    result = subprocess.run([find_tool("DEBUGFS", "debugfs"), "-f", str(batch), str(raw)],
                            capture_output=True, text=True, timeout=600, env={**os.environ, "LC_ALL": "C"})
    errors = [line for line in result.stderr.splitlines() if not line.startswith("debugfs ")]
    if result.returncode or errors:
        raise ValueError("debugfs failed: " + "\n".join(errors)[-2000:])
    sections = []
    for line in result.stdout.splitlines():
        if line.startswith("debugfs: "):
            sections.append([])
        elif sections:
            sections[-1].append(line)
    if len(sections) != len(commands):
        raise ValueError("incomplete debugfs output")
    return ["\n".join(lines) for lines in sections]


def xvalue(value, size):
    if value.startswith('"') and value.endswith('"'):
        text = value[1:-1]
        data = bytearray()
        i = 0
        while i < len(text):
            if text[i] == "\\" and re.fullmatch(r"[0-7]{3}", text[i + 1:i + 4]):
                data.append(int(text[i + 1:i + 4], 8))
                i += 4
            elif text[i] == "\\" and i + 1 < len(text):
                data.extend(text[i + 1].encode())
                i += 2
            else:
                data.extend(text[i].encode())
                i += 1
        data = bytes(data)
    else:
        data = bytes.fromhex(value)
    if len(data) != size:
        raise ValueError("debugfs xattr size mismatch")
    return data


def ext4_tree(raw, work):
    paths = {"/": 2}
    directories = [("/", 2)]
    visited = {2}
    while directories:
        outputs = debugfs(raw, work, [f"ls -p <{nid}>" for _, nid in directories])
        following = []
        for (parent, _), output in zip(directories, outputs):
            for line in output.splitlines():
                if not line:
                    continue
                fields = line.split("/")
                if len(fields) != 8 or not fields[1].isdigit():
                    raise ValueError("unreadable ext4 directory entry: " + repr(line))
                nid, mode = int(fields[1]), int(fields[2], 8)
                name = fields[5]
                if not nid or name in (".", ".."):
                    continue
                path = parent.rstrip("/") + "/" + name
                if not safe_path(path) or path in paths:
                    raise ValueError("unsafe or duplicate ext4 path: " + repr(path))
                paths[path] = nid
                if stat.S_ISDIR(mode):
                    if nid in visited:
                        raise ValueError("cycle in ext4 directories")
                    visited.add(nid)
                    following.append((path, nid))
        directories = following
    ids = sorted(set(paths.values()))
    commands = [command for nid in ids for command in (f"stat <{nid}>", f"ea_list <{nid}>")]
    outputs = debugfs(raw, work, commands)
    inodes = {}
    dumps = []
    for i, nid in enumerate(ids):
        info, attrs = outputs[i * 2:i * 2 + 2]
        mode = re.search(r"Type: (.*?)\s+Mode:\s+([0-7]+)", info)
        owner = re.search(r"User:\s+(\d+)\s+Group:\s+(\d+)", info)
        size = re.search(r"\bSize:\s+(\d+)", info)
        kinds = {"regular": stat.S_IFREG, "directory": stat.S_IFDIR, "symlink": stat.S_IFLNK,
                 "character special": stat.S_IFCHR, "block special": stat.S_IFBLK, "FIFO": stat.S_IFIFO,
                 "socket": stat.S_IFSOCK}
        if not mode or not owner or not size or mode[1] not in kinds:
            raise ValueError("unreadable ext4 inode " + str(nid))
        entry = Entry(kinds[mode[1]] | int(mode[2], 8), int(owner[1]), int(owner[2]), int(size[1]))
        for line in attrs.splitlines():
            m = re.fullmatch(r"\s+(\S+) \((\d+)\) = (.*)", line)
            if m:
                entry.attrs[m[1]] = xvalue(m[3], int(m[2]))
            elif line.strip() and line != "Extended attributes:":
                raise ValueError("unreadable ext4 xattrs: " + line)
        if stat.S_ISREG(entry.mode):
            entry.source = work / ("inode-" + str(nid))
            dumps.append(f'dump <{nid}> "{entry.source}"')
        elif stat.S_ISLNK(entry.mode):
            link = re.search(r'^Fast link dest: "(.*)"$', info, re.M)
            if link:
                entry.link = link[1]
            else:
                # Long symlinks use data blocks; debugfs cat reads those bytes.
                link = debugfs(raw, work, [f"cat <{nid}>"])[0]
                entry.link = link
            if len(entry.link.encode()) != entry.size:
                raise ValueError("unreadable ext4 symlink " + str(nid))
        inodes[nid] = entry
    if dumps:
        debugfs(raw, work, dumps)
    for entry in inodes.values():
        if entry.source:
            if not entry.source.is_file() or entry.source.stat().st_size != entry.size:
                raise ValueError("ext4 extraction size mismatch")
            entry.content = digest(entry.source)
    return {path: inodes[nid] for path, nid in paths.items()}


def read_tree(image, part, work):
    work.mkdir()
    raw = work / (part + ".img")
    start, length = image.parts[part]
    with raw.open("wb") as out:
        for off in range(0, length, 1048576):
            out.write(image.read(start + off, min(1048576, length - off)))
    if image.part_read(part, 1024, 4) == b"\xe2\xe1\xf5\xe0":
        extracted = work / "tree"
        result = subprocess.run([find_tool("FSCK_EROFS", "fsck.erofs"), "--no-preserve",
                                 "--extract=" + str(extracted), str(raw)], capture_output=True, text=True, timeout=600)
        if result.returncode:
            raise ValueError("fsck.erofs extraction failed: " + (result.stdout + result.stderr)[-2000:])
        with raw.open("rb") as file:
            entries = Erofs(file).tree(extracted)
    elif image.part_read(part, 1080, 2) == b"\x53\xef":
        entries = ext4_tree(raw, work)
    else:
        raise ValueError("unknown filesystem on " + part)
    raw.unlink()
    if part == "vendor":
        return {"/vendor" + (p if p != "/" else ""): e for p, e in entries.items()}
    return entries


def reason(path, old, new):
    if path in ("/system", "/vendor") or not path.startswith(("/system/", "/vendor/")):
        return "system root or ramdisk/init path"
    if path.endswith(".ko") or "/lib/modules/" in path or path.endswith("/lib/modules"):
        return "kernel module changes need the matching kernel and a full update"
    if (re.search(r"/(etc/)?init(/|\.|$)", path) or "/etc/selinux" in path
            or "/apex/" in path or path.endswith("/apex")
            or re.search(r"/(fstab[^/]*|ueventd[^/]*|build\.prop|prop\.default)$", path)):
        return "init, properties, SELinux policy or APEX already loaded before hotfixes"
    for entry in (old, new):
        if entry and not (stat.S_ISREG(entry.mode) or stat.S_ISDIR(entry.mode) or stat.S_ISLNK(entry.mode)):
            return "special file cannot be rebuilt safely"
        if entry and set(entry.attrs) - {"security.selinux"}:
            return "extended attributes other than SELinux (including capabilities) cannot be preserved"
    return ""


def differences(old, new):
    return [p for p in sorted(old.keys() | new.keys())
            if p not in old or p not in new or old[p].signature() != new[p].signature()]


def under(path, directory):
    return path == directory or path.startswith(directory + "/")


def plan(old, new, max_bytes, max_entries):
    changes = differences(old, new)
    excluded = {p: why for p in changes if (why := reason(p, old.get(p), new.get(p)))}
    candidates = set()
    enclosing = {}
    for path in changes:
        if path in excluded:
            continue
        a, b = old.get(path), new.get(path)
        if a and b and stat.S_ISREG(a.mode) and stat.S_ISREG(b.mode):
            continue
        parent = path if a and b and stat.S_ISDIR(a.mode) and stat.S_ISDIR(b.mode) else str(PurePosixPath(path).parent)
        while parent not in ("/", "/system", "/vendor"):
            a, b = old.get(parent), new.get(parent)
            if a and b and stat.S_ISDIR(a.mode) and stat.S_ISDIR(b.mode):
                break
            parent = str(PurePosixPath(parent).parent)
        if parent in ("/", "/system", "/vendor"):
            excluded[path] = "no enclosing directory below /system or /vendor exists in both releases"
        else:
            candidates.add(parent)
            enclosing[path] = parent
    # An ancestor overlay covers any descendant overlay, so build it only once.
    directories = []
    for parent in sorted(candidates, key=lambda p: (p.count("/"), p)):
        if any(under(parent, d) for d in directories):
            continue
        a = {p: e for p, e in old.items() if under(p, parent)}
        b = {p: e for p, e in new.items() if under(p, parent)}
        size = max(sum(e.size for e in t.values() if stat.S_ISREG(e.mode)) for t in (a, b))
        count = max(len(a), len(b))
        why = ""
        if size > max_bytes or count > max_entries:
            why = f"directory {parent} exceeds rebuild limit ({size} bytes, {count} entries)"
        elif any(under(p, parent) for p in excluded):
            why = "directory " + parent + " contains a change requiring a full update"
        elif any(reason(p, e, new.get(p)) for p, e in a.items()):
            why = "directory " + parent + " contains early-boot files or unsupported metadata"
        elif any(reason(p, old.get(p), e) for p, e in b.items()):
            why = "directory " + parent + " contains early-boot files or unsupported metadata"
        if why:
            for path, required in enclosing.items():
                if required == parent:
                    excluded[path] = why
        else:
            directories.append(parent)
    files = [p for p in changes if p not in excluded and not any(under(p, d) for d in directories)]
    return changes, files, directories, excluded


DIRECTORY_HELPERS = '''
# The old release's library only needs hotfix_bind; directory support travels here.
hotfix_delta_dir() {
\tlocal target r=${HOTFIX_ROOT:-}
\t[ ! -L "$r$1" ] && [ -d "$r$1" ] || return 1
\ttarget=$(readlink -f "$r$1") || return 1
\tcase $target in "$r"/system/*|"$r"/vendor/*) ;; *) return 1 ;; esac
\tdelta_dir=$(mktemp -d "$HOTFIX_STORE/dir.XXXXXX") || return 1
\tcp -a "$target/." "$delta_dir/" || return 1
}
hotfix_delta_mount() {
\tmount -o bind "$delta_dir" "${HOTFIX_ROOT:-}$1"
}
'''


def metadata(entry, dest, fallback):
    label = entry.attrs.get("security.selinux", b"").rstrip(b"\0").decode()
    link = stat.S_ISLNK(entry.mode)
    lines = ["chown " + ("-h " if link else "") + f"{entry.uid}:{entry.gid} " + dest]
    if not link:
        lines.append(f"chmod {stat.S_IMODE(entry.mode):04o} " + dest)
    context = shlex.quote(label) if label else '"$(stat -c %C ' + fallback + ')"'
    lines.append("chcon " + ("-h " if link else "") + context + " " + dest)
    return lines


def script(old, new, files, directories):
    lines = ["#!/system/bin/sh", "# Reapplied on every start. Keep this script idempotent.", "set -e"]
    payloads = {}
    warnings = []

    def payload(entry):
        name = "files/" + str(len(payloads))
        payloads[name] = entry.source
        return name

    for path in files:
        lines.append("hotfix_bind " + payload(new[path]) + " " + shlex.quote(path))
        dest = '"${HOTFIX_ROOT:-}"' + shlex.quote(path)
        lines.extend(metadata(new[path], dest, dest))
    if directories:
        lines.append(DIRECTORY_HELPERS.strip())
    for parent in directories:
        lines.append("hotfix_delta_dir " + shlex.quote(parent))
        a = {p: e for p, e in old.items() if under(p, parent)}
        b = {p: e for p, e in new.items() if under(p, parent)}

        def dest(path):
            return '"$delta_dir"' + shlex.quote(path[len(parent):])

        changed = differences(a, b)
        for path in sorted(changed, key=lambda p: (-p.count("/"), p)):
            x, y = a.get(path), b.get(path)
            if path != parent and x and (not y or stat.S_IFMT(x.mode) != stat.S_IFMT(y.mode) or not stat.S_ISDIR(x.mode)):
                lines.append("rm -rf " + dest(path))
        for path, entry in sorted(b.items(), key=lambda item: (item[0].count("/"), item[0])):
            if path not in changed:
                continue
            if stat.S_ISDIR(entry.mode):
                lines.append("mkdir -p " + dest(path))
            elif stat.S_ISREG(entry.mode):
                lines.append("cp " + payload(entry) + " " + dest(path))
            elif stat.S_ISLNK(entry.mode):
                lines.append("ln -s -- " + shlex.quote(entry.link) + " " + dest(path))
        for path, entry in sorted(b.items(), key=lambda item: (-item[0].count("/"), item[0])):
            fallback = path if path in a and stat.S_IFMT(a[path].mode) == stat.S_IFMT(entry.mode) else parent
            original = '"${HOTFIX_ROOT:-}"' + shlex.quote(fallback)
            lines.extend(metadata(entry, dest(path), original))
            if "security.selinux" not in entry.attrs and path not in a:
                warnings.append("SELinux label unreadable for " + path + "; inherits enclosing directory label")
        lines.append("hotfix_delta_mount " + shlex.quote(parent))
    return ("\n".join(lines) + "\n").encode(), payloads, warnings


def range_hash(image, start, length):
    h = hashlib.sha256()
    for offset in range(0, length, 1048576):
        h.update(image.read(start + offset, min(1048576, length - offset)))
    return h.digest()


def partition_hash(image, part):
    return range_hash(image, *image.parts[part])


def image_changes(old, new):
    omitted = []
    if old.layout != new.layout:
        omitted.append("partition table: layout/type/attributes changed; needs a full update")
    if old.read(0, 440) != new.read(0, 440):
        omitted.append("MBR bootloader code changed; needs a full update")
    # GPT occupies the first 34 and last 33 sectors; other gaps can contain loaders.
    if old.layout == new.layout and old.size == new.size:
        end = 34 * 512
        for start, length in sorted(old.parts.values()) + [(old.size - 33 * 512, 0)]:
            if start > end and range_hash(old, end, start - end) != range_hash(new, end, start - end):
                omitted.append(f"reserved image bytes {end}..{start - 1}: bootloader/unpartitioned data changed; needs a full update")
            end = max(end, start + length)
    for part in sorted(old.parts.keys() | new.parts.keys()):
        if part in ("BOOT", "system", "vendor"):
            continue
        if part not in old.parts or part not in new.parts or partition_hash(old, part) != partition_hash(new, part):
            omitted.append("partition " + part + ": raw content changed (bootloader or non-system data); needs a full update")
    a, b = Fat(old).tree(), Fat(new).tree()
    for path in sorted(a.keys() | b.keys()):
        if a.get(path) != b.get(path):
            kind = "added" if path not in a else "deleted" if path not in b else "changed"
            omitted.append("BOOT" + path + ": " + kind + "; BOOT/kernel/DTB/ramdisk/profile changes need a full update")
    return omitted
