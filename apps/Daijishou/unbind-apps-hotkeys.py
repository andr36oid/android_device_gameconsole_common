#!/usr/bin/env python3
"""Stops Daijishou's app list from using R2 and Y.

On the "Apps" page R2 (Daijishou's "switching next" hotkey) toggles the Only games filter
and Y (its "first additional" hotkey) opens the sort menu. Both are pressed by accident a
lot. Daijishou has no setting for this page alone, and the same buttons switch platforms
and more in the game lists, so the page's own hotkey handler
(AppsViewPagerFragment.onHotKeyPressed) gets its two checks for them turned into nops:
the keys then fall through to "not handled". L2 (back) and Select/Start (options) stay.

The APK is re-signed with the platform key at build time anyway. The build's speed
profile names each dex by its CRC32 in the APK, so the patched dex's CRC is put into it too
(otherwise the build drops classes4 from the profile without a word).
After updating Daijishou.apk and its profile, run this again; it refuses an APK that
doesn't have the expected code at the expected place.

    unbind-apps-hotkeys.py ../../dexpreopt-profiles/Daijishou.prof Daijishou.apk
"""

import hashlib
import os
import struct
import sys
import tempfile
import zipfile
import zlib

DEX = "classes4.dex"
# file offset in the dex, the instruction there, what it does
PATCHES = [
    (0x3C7096, bytes.fromhex("32147e00"), "if-eq v4, v1 (hotkey 1, R2): Only games filter"),
    (0x3C70A2, bytes.fromhex("32244f00"), "if-eq v4, v2 (hotkey 3, Y): sort menu"),
]
NOPS = bytes(4)
# a few bytes before each, so a different build of Daijishou is never patched blindly
CONTEXT = [
    (0x3C7094, bytes.fromhex("1211")),  # const/4 v1, 1
    (0x3C70A0, bytes.fromhex("1232")),  # const/4 v2, 3
]


def fix_header(dex):
    # signature: SHA-1 of everything after it; checksum: adler32 of everything after it
    dex[12:32] = hashlib.sha1(dex[32:]).digest()
    dex[8:12] = struct.pack("<I", zlib.adler32(bytes(dex[12:])) & 0xFFFFFFFF)


def fix_profile(path, key, old, new):
    """Swaps one dex's checksum (its zip CRC32) in a binary ART profile (version 010)."""
    with open(path, "rb") as f:
        data = bytearray(f.read())
    if data[:8] != b"pro\0" b"010\0":
        sys.exit("%s: not a version 010 ART profile" % path)
    # dex count (u8), data size (u32), compressed size (u32), then the zlib data
    count, size, csize = struct.unpack_from("<BII", data, 8)
    body = bytearray(zlib.decompress(bytes(data[17:17 + csize])))
    if len(body) != size or len(data) != 17 + csize:
        sys.exit("%s: unexpected profile layout" % path)
    # one line header per dex first: key size (u16), classes (u16), methods size (u32),
    # checksum (u32), method ids (u32), then the key ("classes4.dex")
    pos = 0
    for _ in range(count):
        key_size = struct.unpack_from("<H", body, pos)[0]
        name = bytes(body[pos + 16:pos + 16 + key_size]).decode()
        if name == key or name.endswith("!" + key):
            if body[pos + 8:pos + 12] == new:
                return "already done"
            if body[pos + 8:pos + 12] != old:
                sys.exit("%s: %s has another checksum than the APK's" % (path, name))
            body[pos + 8:pos + 12] = new
            packed = zlib.compress(bytes(body), 9)
            with open(path, "wb") as f:
                f.write(bytes(data[:9]) + struct.pack("<II", len(body), len(packed)) + packed)
            return "checksum updated"
        pos += 16 + key_size
    sys.exit("%s: no %s in it" % (path, key))


def main():
    profile, apk = sys.argv[1], sys.argv[2]
    with zipfile.ZipFile(apk) as z:
        dex = bytearray(z.read(DEX))
    if all(dex[o:o + 4] == NOPS for o, _, _ in PATCHES):
        print("already patched")
        return
    old_crc = struct.pack("<I", zlib.crc32(dex))
    for off, want in CONTEXT:
        if dex[off:off + len(want)] != want:
            sys.exit("%s: unexpected code at 0x%x, not the Daijishou build this was made for"
                     % (DEX, off))
    for off, want, what in PATCHES:
        if dex[off:off + 4] != want:
            sys.exit("%s: unexpected code at 0x%x (%s)" % (DEX, off, what))
        dex[off:off + 4] = NOPS
        print("unbound:", what)
    fix_header(dex)
    print("profile:", fix_profile(profile, DEX, old_crc, struct.pack("<I", zlib.crc32(dex))))

    # rewrite the APK with every other entry as it was (stored ones stay stored)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(os.path.abspath(apk)), suffix=".apk")
    os.close(fd)
    try:
        with zipfile.ZipFile(apk) as src, zipfile.ZipFile(tmp, "w") as out:
            for info in src.infolist():
                data = bytes(dex) if info.filename == DEX else src.read(info)
                out.writestr(info, data, compress_type=info.compress_type)
        os.replace(tmp, apk)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


if __name__ == "__main__":
    main()
