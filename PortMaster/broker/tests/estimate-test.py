"""Allocation-based estimates, including the shipped ARM64 helper when available.

No mounts or image builds: a 128 GiB sparse tree uses only a few MiB on disk.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

tools = Path(__file__).resolve().parents[2] / 'erofs-tools'
with tempfile.TemporaryDirectory(prefix='a36-estimate-') as tmp:
    base = Path(tmp)
    native = base / 'support'
    subprocess.run(['cc', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                    str(tools / 'andr36oid-erofs-support.c'), '-o', str(native)], check=True)
    helpers = [('native', [str(native)])]
    if shutil.which('qemu-aarch64'):
        helpers.append(('prebuilt', [shutil.which('qemu-aarch64'), str(tools / 'andr36oid-erofs-support')]))
    else:
        print('SKIP: ARM64 prebuilt execution (qemu-aarch64 unavailable)')
    tree = base / 'tree'
    tree.mkdir()
    sparse = tree / 'sparse'
    with sparse.open('wb') as f:
        f.truncate(64 * 1024**3)
        f.seek(8 * 1024**3)
        f.write(b'data' * 1024)
        f.flush()
        os.fsync(f.fileno())
    dense = tree / 'dense'
    dense.write_bytes(os.urandom(1024**2))
    os.setxattr(dense, 'user.estimate-test', b'x' * 3000)
    with dense.open('rb') as f:
        os.fsync(f.fileno())
    (tree / 'link').symlink_to(sparse.name)
    entries = [tree, sparse, dense, tree / 'link']
    assert sparse.stat().st_blocks * 512 < sparse.stat().st_size // 1000

    for label, helper in helpers:
        # Each implementation sees the same initial and extended sparse file.
        with sparse.open('r+b') as f:
            f.truncate(64 * 1024**3)
            os.fsync(f.fileno())

        def estimate():
            fields = dict(line.split('=', 1) for line in subprocess.check_output(
                helper + ['estimate', str(tree), str(base)], text=True).splitlines())
            assert {'required', 'output_bound', 'available', 'file_limit', 'cluster',
                    'apparent_bytes', 'allocated_bytes'} <= fields.keys()
            result = {key: int(value) for key, value in fields.items()}
            cluster = max(4096, result['cluster'])
            def rounded(n):
                return (n + cluster - 1) // cluster * cluster
            regular = [sparse, dense]
            apparent = sum(p.stat().st_size for p in regular)
            allocated = sum(p.stat().st_blocks * 512 for p in regular)
            budget = len(entries) * rounded(8192)
            budget += sum(rounded(p.stat().st_blocks * 512) for p in regular)
            for p in entries:
                attrs = os.listxattr(p, follow_symlinks=False)
                if attrs:
                    budget += rounded(sum(len(os.fsencode(a)) + 1 + len(os.getxattr(
                        p, a, follow_symlinks=False)) for a in attrs))
            assert result['apparent_bytes'] == apparent, result
            assert result['allocated_bytes'] == allocated, result
            assert result['output_bound'] == budget + (budget + 99) // 100 * 15, result
            assert result['required'] == result['output_bound'] + 512 * 1024**2, result
            assert result['output_bound'] < apparent // 1000, result
            return result

        initial = estimate()
        with sparse.open('r+b') as f:
            f.truncate(128 * 1024**3)
            os.fsync(f.fileno())
        extended = estimate()
        assert extended['apparent_bytes'] == initial['apparent_bytes'] + 64 * 1024**3
        for key in ['allocated_bytes', 'output_bound', 'required']:
            assert extended[key] == initial[key], (key, initial, extended)
        print('PASS:', label, 'sparse extension leaves allocation/output/required unchanged;',
              'inode/xattr overhead, 15% margin, 512 MiB reserve, wire fields;', initial)
