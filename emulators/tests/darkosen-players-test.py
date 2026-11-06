#!/usr/bin/env python3
"""Generated alternates keep the shipped player order and Android defaults."""
import importlib.util
from pathlib import Path
import sqlite3
import sys

sys.dont_write_bytecode = True

emu = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('gen_daijishou', emu / 'scripts/gen-daijishou.py')
gen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen)
coremap = dict(gen.read_words(emu / 'daijishou/darkosen-cores.txt'))
platforms = gen.read_words(emu / 'daijishou/platforms.txt')
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE player (unique_id, player, name, description, args, kill, warning, regex, extra)')
players = {}
for line in gen.generate().splitlines():
    fields = line.split('\t', 3)
    if fields[0] != 'player':
        continue
    db.execute('DELETE FROM player')
    db.execute('INSERT INTO player VALUES (' + fields[3] + ')')
    players.setdefault(int(fields[1]), []).append((fields[2], db.execute('SELECT * FROM player').fetchone()))
count = 0
for index, (_, _, needs) in enumerate(platforms, 1):
    original = [p for p in players[index] if p[1][2] != 'RetroArch (dArkOSen)']
    assert [p[0].removeprefix('core:') for p in original] == needs.split(',')
    assert players[index][:len(original)] == original
    supported = [core for core in needs.split(',') if core in coremap]
    alternates = players[index][len(original):]
    assert len(alternates) == bool(supported)
    if alternates:
        need, row = alternates[0]
        assert need == 'core:' + supported[0]
        assert row[0].endswith('.andr36oid.darkosen')
        assert row[4] == ('-n org.andr36oid.subsystemsetup/.RetroArchActivity\n'
                          ' -e ROM {file.path}\n -e CORE ' + coremap[supported[0]])
        assert not row[5] and not row[6]
        assert row[7] == next(p[1][7] for p in original if p[0] == need)
        count += 1
assert coremap['mupen64plus_next_gles3'] == 'mupen64plus_next'
assert 'nxengine' not in coremap and 'virtualjaguar' not in coremap
print(f'{count} dArkOSen alternates; original player order and defaults preserved')
