#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys

here = Path(__file__).parent
for name in ('switch', 'ps3', 'gamecube', 'media', 'ps4'):
    reference = bytes.fromhex((here / (name + '.hex')).read_text())
    actual = subprocess.check_output([sys.argv[1], '--descriptor', name])
    assert actual == reference, name
    assert len(actual) == {'switch': 86, 'ps3': 148, 'gamecube': 214, 'media': 102, 'ps4': 481}[name], (name, len(actual))
print('Switch-Fightstick, DS3, WUP-028 and consumer/presenter descriptor bytes match the frozen references')
