#!/usr/bin/env python3
"""Compare the actual MBR against independent literal partition fields."""
from pathlib import Path
import struct
import sys

image, fs, size = sys.argv[1:]
with Path(image).open('rb') as f:
    mbr = f.read(512)
assert len(mbr) == 512
assert mbr[:446] == bytes(446)
assert mbr[446:454] == bytes([0, 32, 33, 0, {'fat32': 12, 'exfat': 7, 'ntfs': 7, 'ext4': 131}[fs], 254, 255, 255])
assert struct.unpack('<II', mbr[454:462]) == (2048, int(size) // 512 - 2048)
assert mbr[462:510] == bytes(48)
assert mbr[510:512] == b'\x55\xaa'
