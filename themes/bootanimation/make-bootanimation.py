#!/usr/bin/env python3
"""Draws the retro andr36oid boot animation (original pixel art) and packs
bootanimation-retro.zip next to this script.

The frames are a 480x144 strip; Android centres it on any screen and keeps the
rest black, so one zip fits 640x480 and 720x720. 12 fps, pixels drawn 6x.
  part0 (plays once, always to the end): the name is typed out behind a cursor
  part1 (loops until Android is up): the cursor blinks, a block bounces below
Needs Pillow.  Run from anywhere:  python3 make-bootanimation.py
"""
import io
import os
import zipfile

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
UNIT = 6
LW, LH = 80, 24             # frame size in art pixels (480x144 on screen)
FPS = 12
TEXT = 'andr36oid'
INK = (232, 232, 236)
TRACK = (40, 40, 48)
BLOCK = (200, 162, 240)      # R36S clear purple accent, dark theme

FONT = {  # 5x7
    'a': ('00000', '00000', '01110', '00001', '01111', '10001', '01111'),
    'n': ('00000', '00000', '10110', '11001', '10001', '10001', '10001'),
    'd': ('00001', '00001', '01101', '10011', '10001', '10011', '01101'),
    'r': ('00000', '00000', '10110', '11001', '10000', '10000', '10000'),
    '3': ('11110', '00001', '00001', '01110', '00001', '00001', '11110'),
    '6': ('00110', '01000', '10000', '11110', '10001', '10001', '01110'),
    'o': ('00000', '00000', '01110', '10001', '10001', '10001', '01110'),
    'i': ('00100', '00000', '01100', '00100', '00100', '00100', '01110'),
}
TX = (LW - len(TEXT) * 6 - 6) // 2   # text plus the cursor, centred
TY = 5


def glyph(d, x, y, ch, colour):
    for r, row in enumerate(FONT[ch]):
        for c, bit in enumerate(row):
            if bit == '1':
                d.point((x + c, y + r), fill=colour)


def frame(letters, cursor, block):
    img = Image.new('RGB', (LW, LH))
    d = ImageDraw.Draw(img)
    for i, ch in enumerate(TEXT[:letters]):
        glyph(d, TX + i * 6, TY, ch, INK)
    if cursor:
        cx = TX + letters * 6
        d.rectangle([cx, TY, cx + 4, TY + 6], fill=BLOCK)
    if block is not None:
        y = TY + 12
        d.rectangle([TX, y, TX + 53, y + 1], fill=TRACK)
        bx = TX + block * 6
        d.rectangle([bx, y - 1, bx + 5, y + 2], fill=BLOCK)
    return img.resize((LW * UNIT, LH * UNIT), Image.NEAREST)


def png(img):
    buf = io.BytesIO()
    img.quantize(colors=8, dither=Image.Dither.NONE).save(buf, 'PNG', optimize=True)
    return buf.getvalue()


def main():
    part0 = [frame(0, n % 6 < 3, None) for n in range(6)]              # cursor blinks
    part0 += [frame(n, True, None) for n in range(1, len(TEXT) + 1)]    # typing
    part0 += [frame(len(TEXT), True, None)] * 3
    bounce = list(range(9)) + list(range(7, 0, -1))                     # 16 frames
    part1 = [frame(len(TEXT), (n // 4) % 2 == 0, b) for n, b in enumerate(bounce)]
    desc = '%d %d %d\nc 1 0 part0\np 0 0 part1\n' % (LW * UNIT, LH * UNIT, FPS)
    out = os.path.join(HERE, 'bootanimation-retro.zip')
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_STORED) as z:
        def add(name, data):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            z.writestr(info, data)
        add('desc.txt', desc)
        for part, frames in (('part0', part0), ('part1', part1)):
            for i, img in enumerate(frames):
                add('%s/%03d.png' % (part, i), png(img))
    print(out, os.path.getsize(out))


if __name__ == '__main__':
    main()
