#!/usr/bin/env python3
"""Real uinput/evdev on a disposable kernel VM; never opens host input devices."""
from pathlib import Path
import gzip
import os
import stat
import subprocess
import tempfile

kernel = os.environ.get('A36_TEST_KERNEL')
guest = os.environ.get('A36_TEST_GUEST')
if not kernel or not guest:
    raise SystemExit('Set A36_TEST_KERNEL and A36_TEST_GUEST to the kernel and static ARM64 Android helper.')
with tempfile.TemporaryDirectory(prefix='a36-input-vm-') as temp:
    root = Path(temp)
    source = Path(__file__).parent / 'fixtures/input-hotplug/integration.c'
    init = root / 'init'
    subprocess.run(['aarch64-linux-gnu-gcc', '-O2', '-static', '-Wall', '-Wextra', '-Werror', str(source), '-o', str(init)], check=True)
    ramdisk = bytearray()

    def add(name, data, mode, major=0, minor=0):
        path = name.encode() + b'\0'
        fields = [1, mode, 0, 0, 1, 0, len(data), 0, 0, major, minor, len(path), 0]
        ramdisk.extend(b'070701' + b''.join(f'{x:08x}'.encode() for x in fields) + path)
        ramdisk.extend(b'\0' * (-len(ramdisk) % 4))
        ramdisk.extend(data)
        ramdisk.extend(b'\0' * (-len(ramdisk) % 4))

    add('dev', b'', stat.S_IFDIR | 0o755)
    add('dev/console', b'', stat.S_IFCHR | 0o600, 5, 1)
    add('init', init.read_bytes(), stat.S_IFREG | 0o755)
    add('guest', Path(guest).read_bytes(), stat.S_IFREG | 0o755)
    add('TRAILER!!!', b'', 0)
    archive = root / 'initramfs.gz'
    archive.write_bytes(gzip.compress(ramdisk, mtime=0))
    console = Path(os.environ.get('A36_TEST_VM_LOG', str(root / 'console.log')))
    with console.open('wb') as log:
        subprocess.run(['qemu-system-aarch64', '-machine', 'virt,gic-version=2', '-cpu', 'cortex-a35',
                        '-smp', '4', '-m', '1024', '-nographic', '-nic', 'none', '-no-reboot', '-monitor', 'none',
                        '-kernel', kernel, '-initrd', str(archive), '-append',
                        'console=ttyAMA0 rdinit=/init loglevel=5 panic=-1 nokaslr initcall_blacklist=system_heap_create,rockchip_drm_init'],
                       stdout=log, stderr=subprocess.STDOUT, timeout=180, check=True)
    output = console.read_text(errors='backslashreplace')
    print(output)
    assert 'INPUT_HOTPLUG_INTEGRATION_PASS' in output and 'INPUT_HOTPLUG_FAIL' not in output
    assert 'guest input hotplug: kernel uevents + 50 ms sysfs probe' in output
    assert 'guest input hotplug: kernel input uevents received' in output
    assert 'guest input hotplug: 50 ms sysfs probe' in output
    assert 'INPUT_MODE_PASS netlink' in output and 'INPUT_MODE_PASS fallback' in output
    print('PASS: real kernel netlink delivery, forced fallback, 0666 despite 077, udev add/remove, and keyboard input through the new node')
