#!/usr/bin/env python3
"""Put overlay/linuxrc into prebuilt/Image-resizing without rebuilding anything.

The kernel carries the resizer's root filesystem as one gzip'd cpio. This
swaps the linuxrc file in it, recompresses with pigz -11 (zopfli, smaller
than gzip -9) and writes it back in place, padded with zeros, which the
kernel skips while unpacking. Fails if the new archive does not fit.
Usage: swap-linuxrc.py [Image-resizing] [linuxrc]
"""
import os, subprocess, sys, zlib

here = os.path.dirname(os.path.abspath(__file__))
image = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, '..', 'prebuilt', 'Image-resizing')
script = sys.argv[2] if len(sys.argv) > 2 else os.path.join(here, 'overlay', 'linuxrc')

d = bytearray(open(image, 'rb').read())
off = 0
while True:
    off = d.find(b'\x1f\x8b\x08', off)
    if off < 0:
        sys.exit('no initramfs found')
    try:
        z = zlib.decompressobj(31)
        cpio = z.decompress(bytes(d[off:]))
        if cpio[:6] == b'070701':
            break
    except zlib.error:
        pass
    off += 1
# __initramfs_size sits after the archive (and its padding) and gives the room we have
end = off + len(d) - off - len(z.unused_data)
while d[end] == 0:
    end += 1
size = int.from_bytes(d[end:end + 4], 'little')
if size < end - off - 8 or size > end - off:
    sys.exit('could not find __initramfs_size')

new = open(script, 'rb').read()
out = bytearray()
i = 0
replaced = False
while True:
    h = cpio[i:i + 110]
    if h[:6] != b'070701':
        sys.exit('bad cpio header at %d' % i)
    f = [int(h[6 + 8 * k:14 + 8 * k], 16) for k in range(13)]
    name = cpio[i + 110:i + 110 + f[11] - 1]
    data_at = (i + 110 + f[11] + 3) & ~3
    data = cpio[data_at:data_at + f[6]]
    i = (data_at + f[6] + 3) & ~3
    if name == b'linuxrc':
        data = new
        f[6] = len(new)
        replaced = True
    out += b'070701' + b''.join(b'%08X' % v for v in f) + name + b'\0'
    out += b'\0' * (-len(out) % 4)
    out += data
    out += b'\0' * (-len(out) % 4)
    if name == b'TRAILER!!!':
        out += cpio[i:]
        break
if not replaced:
    sys.exit('no linuxrc in the archive')

gz = subprocess.run(['pigz', '-11', '-n', '-c'], input=bytes(out),
                    stdout=subprocess.PIPE, check=True).stdout
if len(gz) > size:
    sys.exit('new archive is %d bytes, only %d fit' % (len(gz), size))
d[off:off + size] = gz + b'\0' * (size - len(gz))
open(image, 'wb').write(d)
print('linuxrc swapped, archive %d of %d bytes' % (len(gz), size))
