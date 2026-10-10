#!/usr/bin/env python3
"""Writes tests/boards.txt for test-dtbhint.sh from the built DTBs: one line per
BOOT folder with the board wiring the test needs to fake /proc and /sys.

    gen-boards.py <dir with rk3326-r36s-*.dtb> <device/gameconsole/r36s/experimental-dtbs/list.txt>

Line: folder|model|joypad amux-en pin|button pins|volume keys ("adc" or pins)
Pins are written as <bank><letter><n>, e.g. 3C6 = GPIO3_C6."""
import os
import subprocess
import sys


def fdt(dtb, *args):
    r = subprocess.run(['fdtget'] + list(args[:-2]) + [dtb] + list(args[-2:]),
                       capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else None


def banks(dtb):
    out = {}
    nodes = subprocess.run(['fdtget', '-l', dtb, '/pinctrl'], capture_output=True, text=True).stdout.split()
    for n in nodes:
        if n.startswith('gpio') and '@' in n:
            ph = fdt(dtb, '/pinctrl/' + n, 'phandle')
            if ph:
                out[int(ph)] = int(n[4])
    return out


def pin(bk, cells):
    ph, num = int(cells[0]), int(cells[1])
    return '%d%s%d' % (bk[ph], 'ABCD'[num // 8], num % 8)


def main():
    d, lst = sys.argv[1], sys.argv[2]
    boards = [('android-panel%d' % i, 'Panels/Panel%d' % i) for i in range(7)]
    boards.append(('splus', 'Panels/R36S-Plus'))
    for line in open(lst):
        if line.startswith('#') or not line.split():
            continue
        n, f = line.split()[:2]
        boards.append(('clone-' + n, 'Experimental/' + f))
    for n, folder in boards:
        dtb = os.path.join(d, 'rk3326-r36splus-android.dtb' if n == 'splus' else 'rk3326-r36s-%s.dtb' % n)
        bk = banks(dtb)
        model = fdt(dtb, '-ts', '/', 'model')
        jp = '/odroidgo3-joypad'
        en = fdt(dtb, jp, 'amux-en-gpios')
        en = pin(bk, en.split()) if en else '-'
        btn = []
        for c in subprocess.run(['fdtget', '-l', dtb, jp], capture_output=True, text=True).stdout.split():
            g = fdt(dtb, jp + '/' + c, 'gpios')
            if g:
                btn.append(pin(bk, g.split()))
        vol = []
        for k in ('/odroidgo3-keys', '/gpio-keys', '/adc-keys'):
            kids = subprocess.run(['fdtget', '-l', dtb, k], capture_output=True, text=True)
            if kids.returncode != 0:
                continue
            if k == '/adc-keys':
                vol = ['adc']
                break
            for c in kids.stdout.split():
                g = fdt(dtb, k + '/' + c, 'gpios')
                if g:
                    vol.append(pin(bk, g.split()))
        print('%s|%s|%s|%s|%s' % (folder, model, en, ' '.join(btn), ' '.join(vol) or '-'))


main()
