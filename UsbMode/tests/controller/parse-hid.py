#!/usr/bin/env python3
# Parse HID short items, global state and collection/report sizes independently.
import subprocess
import sys

for mode, expected in [('gamepad', {0: (88, 0)}), ('keyboard', {1: (64, 8), 2: (32, 0)})]:
    data = subprocess.check_output([sys.argv[1], '--descriptor', mode])
    size = count = report = depth = 0
    sizes = {}
    pos = 0
    usages = []
    while pos < len(data):
        prefix = data[pos]
        pos += 1
        assert prefix != 0xfe
        length = [0, 1, 2, 4][prefix & 3]
        assert pos + length <= len(data)
        value = int.from_bytes(data[pos:pos + length], 'little')
        pos += length
        kind, tag = (prefix >> 2) & 3, prefix >> 4
        if kind == 1:
            if tag == 7: size = value
            if tag == 8: report = value
            if tag == 9: count = value
        if kind == 2 and tag == 0: usages.append(value)
        if kind == 0:
            if tag == 10: depth += 1
            if tag == 12: depth -= 1
            assert depth >= 0
            if tag in (8, 9):
                bits = sizes.setdefault(report, [0, 0])
                bits[0 if tag == 8 else 1] += size * count
    assert depth == 0
    assert {key: tuple(val) for key, val in sizes.items()} == expected, sizes
    if mode == 'gamepad': assert all(x in usages for x in (5, 0x30, 0x31, 0x33, 0x34, 0x39))
print('HID parser: gamepad 11 bytes; keyboard 9 and mouse 5 bytes, LED output 2 bytes passed')
