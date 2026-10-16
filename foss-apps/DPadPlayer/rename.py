#!/usr/bin/env python3
"""Names D-PAD Player just "Music" on the console.

Its launcher name ("DPAD Music") and its title ("DPad Player") are two strings in
resources.arsc's global string pool (UTF-8, one config only). Each is overwritten in
place with the shorter "Music": the length bytes are set to 5 and the rest of the old
text zeroed, so no offset in the file moves. The other entries are copied as they
are (stored ones stay stored); the build signs the result (Android.mk).

    rename.py DPadPlayer.apk out-unsigned.apk
"""

import struct
import sys
import zipfile

NEW = b"Music"
OLD = [b"DPAD Music", b"DPad Player"]


def patch(arsc):
    arsc = bytearray(arsc)
    # table header, then the global string pool: its flags say UTF-8
    header = struct.unpack_from("<H", arsc, 2)[0]
    pool_type, flags = struct.unpack_from("<H", arsc, header)[0], \
        struct.unpack_from("<I", arsc, header + 16)[0]
    if pool_type != 0x0001 or not flags & 0x100:
        sys.exit("resources.arsc: no UTF-8 global string pool where expected")
    for old in OLD:
        # UTF-8 pool entry: length in characters, length in bytes, the text, a NUL
        entry = bytes([len(old), len(old)]) + old + b"\0"
        if arsc.count(entry) != 1:
            sys.exit("resources.arsc: %r not found once" % old)
        at = arsc.find(entry)
        new = bytes([len(NEW), len(NEW)]) + NEW + b"\0"
        arsc[at:at + len(entry)] = new + bytes(len(entry) - len(new))
    return bytes(arsc)


def main():
    src, dst = sys.argv[1], sys.argv[2]
    with zipfile.ZipFile(src) as z, zipfile.ZipFile(dst, "w") as out:
        for info in z.infolist():
            data = z.read(info)
            if info.filename == "resources.arsc":
                data = patch(data)
            out.writestr(info, data, compress_type=info.compress_type)


if __name__ == "__main__":
    main()
