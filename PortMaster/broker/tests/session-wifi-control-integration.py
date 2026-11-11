#!/usr/bin/env python3
"""Verify the packaged Android helper's supplicant transport in a disposable VM."""
import gzip
import os
from pathlib import Path
import stat
import subprocess
import tempfile

kernel = os.environ['A36_TEST_KERNEL']
guest = Path(os.environ['A36_TEST_GUEST'])
fixture = Path(__file__).parent / 'fixtures/session-wifi/control.c'
with tempfile.TemporaryDirectory(prefix='session-wifi-control-') as temporary:
    root = Path(temporary)
    init = root / 'init'
    subprocess.run(['aarch64-linux-gnu-gcc', '-O2', '-static', '-Wall', '-Wextra', '-Werror',
                    str(fixture), '-o', str(init)], check=True)
    data = bytearray()

    def add(name, payload, mode, major=0, minor=0):
        path = name.encode() + b'\0'
        fields = [1, mode, 0, 0, 1, 0, len(payload), 0, 0, major, minor, len(path), 0]
        data.extend(b'070701' + b''.join(f'{field:08x}'.encode() for field in fields) + path)
        data.extend(b'\0' * (-len(data) % 4))
        data.extend(payload)
        data.extend(b'\0' * (-len(data) % 4))

    add('dev', b'', stat.S_IFDIR | 0o755)
    add('dev/console', b'', stat.S_IFCHR | 0o600, 5, 1)
    add('dev/null', b'', stat.S_IFCHR | 0o666, 1, 3)
    add('dev/urandom', b'', stat.S_IFCHR | 0o666, 1, 9)
    add('init', init.read_bytes(), stat.S_IFREG | 0o755)
    add('guest', guest.read_bytes(), stat.S_IFREG | 0o755)
    add('TRAILER!!!', b'', 0)
    ramdisk = root / 'initramfs.gz'
    ramdisk.write_bytes(gzip.compress(data, mtime=0))
    boot = 'console=ttyAMA0 rdinit=/init loglevel=4 panic=-1 nokaslr'
    boot += ' initcall_blacklist=system_heap_create,rockchip_drm_init'
    run = subprocess.run(
        ['qemu-system-aarch64', '-machine', 'virt,gic-version=2', '-cpu', 'cortex-a35',
         '-smp', '4', '-m', '1024', '-nographic', '-nic', 'none', '-no-reboot', '-monitor', 'none',
         '-kernel', kernel, '-initrd', str(ramdisk), '-append', boot],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace', timeout=90,
        check=True)
    print(run.stdout, end='')
    assert 'SESSION_WIFI_CONTROL_PASS' in run.stdout and 'SESSION_WIFI_CONTROL_FAIL' not in run.stdout
