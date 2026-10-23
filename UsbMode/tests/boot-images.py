#!/usr/bin/env python3
"""Small El Torito fixtures; no mount or host storage device is used."""
from pathlib import Path
import struct
import sys

root = Path(sys.argv[1])
data = bytearray(1048576)
data[16*2048:16*2048+7] = b'\x01CD001\x01'
record = 17*2048
data[record:record+7] = b'\x00CD001\x01'
data[record+7:record+30] = b'EL TORITO SPECIFICATION'
struct.pack_into('<I', data, record+71, 20)
catalog = 20*2048
data[catalog] = 1
data[catalog+30:catalog+32] = b'\x55\xaa'
checksum = (-sum(struct.unpack_from('<16H', data, catalog))) & 65535
struct.pack_into('<H', data, catalog+28, checksum)
data[catalog+32] = 0x88
struct.pack_into('<H', data, catalog+38, 4)
struct.pack_into('<I', data, catalog+40, 24)
(root/'boot.iso').write_bytes(data)
data[catalog+32] = 0
(root/'data.iso').write_bytes(data)
data[catalog+32] = 0x88
data[catalog+28] ^= 1
(root/'bad-checksum.iso').write_bytes(data)
data[catalog+28] ^= 1
struct.pack_into('<I', data, record+71, 2000000)
(root/'outside.iso').write_bytes(data)
