#!/usr/bin/env python3
"""Bound allocator overhead without losing exact size or monotonic progress."""
import ast
from pathlib import Path
import sys

sizes = []
for line in Path(sys.argv[1]).read_text().splitlines():
    if line.startswith('truncate '):
        args = ast.literal_eval(line.split(' ', 1)[1])
        assert args[0] == '-s'
        sizes.append(int(args[1]))
assert 1 < len(sizes) <= 100
assert sizes == sorted(set(sizes))
assert sizes[-1] == 1099511627776
phase = ''
progress = []
for line in Path(sys.argv[2]).read_text().splitlines():
    if line.startswith('prop sys.andr36oid.usbdrive.vstage '):
        phase = line.split()[-1]
    if phase == 'allocate' and line.startswith('prop sys.andr36oid.usbdrive.vpercent '):
        progress.append(int(line.split()[-1]))
# The fixture's earlier requests may also be in the log. Isolate the last allocation.
start = max(i for i, value in enumerate(progress) if value == 0)
progress = progress[start:]
assert progress[0] == 0 and progress[-1] == 100
assert progress == sorted(progress) and all(0 <= p <= 100 for p in progress)
assert len(progress) == len(sizes) + 1
print(f'{len(sizes)} allocation steps reach exactly 1 TiB with monotonic 0–100% progress')
