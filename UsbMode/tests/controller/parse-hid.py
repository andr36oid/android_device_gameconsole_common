#!/usr/bin/env python3
# Parse HID short items, global state and collection/report sizes independently.
import subprocess
import sys

for mode, expected in [('gamepad', {0: (88, 0)}), ('keyboard', {0: (64, 8)}), ('mouse', {0: (32, 0)}), ('boot-mouse', {0: (24, 0)})]:
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
# HID 1.11 Appendix E reference bytes, independent of the production builder.
keyboard = bytes.fromhex('05 01 09 06 a1 01 05 07 19 e0 29 e7 15 00 25 01 75 01 95 08 81 02 95 01 75 08 81 01 95 05 75 01 05 08 19 01 29 05 91 02 95 01 75 03 91 01 95 06 75 08 15 00 25 65 05 07 19 00 29 65 81 00 c0')
mouse = bytes.fromhex('05 01 09 02 a1 01 09 01 a1 00 05 09 19 01 29 03 15 00 25 01 95 03 75 01 81 02 95 01 75 05 81 01 05 01 09 30 09 31 15 81 25 7f 75 08 95 02 81 06 c0 c0')
assert subprocess.check_output([sys.argv[1], '--descriptor', 'keyboard']) == keyboard
assert subprocess.check_output([sys.argv[1], '--descriptor', 'boot-mouse']) == mouse
wheel = mouse[:38] + bytes.fromhex('09 38') + mouse[38:]
wheel = wheel[:-5] + bytes.fromhex('03') + wheel[-4:]
assert subprocess.check_output([sys.argv[1], '--descriptor', 'mouse']) == wheel
print('Byte-exact boot HID descriptors: keyboard 8 bytes, mouse 3 boot/4 report bytes, LED output 1 byte passed')
