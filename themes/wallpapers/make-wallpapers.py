#!/usr/bin/env python3
"""Draws the andr36oid wallpapers (original art, no third-party images).

Every picture is drawn as pixel art at a quarter of the screen size and scaled
up 4x with hard edges, so it stays sharp and the PNGs stay small. Two sizes:
640x480 (res/drawable-nodpi) and 720x720 for the square clones
(res/drawable-h600dp-nodpi: 720x720 at 178 dpi is ~620 dp high, 640x480 is 431).
Thumbnails for the picker are 160x120 (<name>_small).

Needs Pillow. Run from anywhere:  python3 make-wallpapers.py
"""
import math
import os
import random

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
SCALE = 4
BAYER = [[0, 2], [3, 1]]


def hexrgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def bands(img, y0, y1, colours, x0=0, x1=None):
    """Vertical gradient in flat colour bands, 2x2 dither where two bands meet."""
    px = img.load()
    x1 = img.width if x1 is None else x1
    n = len(colours)
    for y in range(y0, y1):
        t = (y - y0) / max(1, y1 - y0) * (n - 1)
        i = min(int(t), n - 2)
        f = t - i
        for x in range(x0, x1):
            # 4 dither levels between neighbouring bands
            px[x, y] = colours[i + 1] if f * 4 > BAYER[y % 2][x % 2] + 0.5 else colours[i]


def stars(img, rng, count, y_max, colours):
    px = img.load()
    for _ in range(count):
        x, y = rng.randrange(img.width), rng.randrange(max(1, y_max))
        px[x, y] = rng.choice(colours)


def scanlines(img, strength=0.12):
    """Darkens every other screen row, like a CRT (applied at full size)."""
    px = img.load()
    k = 1 - strength
    for y in range(1, img.height, 2):
        for x in range(img.width):
            r, g, b = px[x, y]
            px[x, y] = (int(r * k), int(g * k), int(b * k))


# ---------------------------------------------------------------- pictures

def sunset(w, h, rng):
    img = Image.new('RGB', (w, h))
    horizon = int(h * 0.62) if w > h else int(h * 0.60)
    sky = [hexrgb(c) for c in ('#140B2B', '#221040', '#3A1650', '#5A1E5C',
                               '#852A5E', '#B8435A', '#E0703A')]
    bands(img, 0, horizon, sky)
    stars(img, rng, w * horizon // 180, horizon // 2,
          [hexrgb('#6E5A96'), hexrgb('#9A86C2')])
    d = ImageDraw.Draw(img)
    # sun with the classic cut stripes in its lower half
    r = int(min(w, h) * 0.2)
    cx, cy = w // 2, horizon - r // 3
    gaps, gy, g = set(), 2, 1
    while gy < r:
        gaps.update(range(gy, gy + g))
        gy += g + 4 - min(3, g)
        g += 1 if g < 3 else 0
    sun = [hexrgb(c) for c in ('#FFE08A', '#FFC46B', '#FF9E5E', '#FF7A6B', '#F2587A')]
    for y in range(cy - r, horizon):
        dy = y - cy
        if dy * dy > r * r:
            continue
        rel = (y - (cy - r)) / (2 * r)
        if y - cy in gaps:
            continue  # stripe gap
        half = int(math.sqrt(r * r - dy * dy))
        col = sun[min(len(sun) - 1, int(rel * len(sun)))]
        d.line([(cx - half, y), (cx + half, y)], fill=col)
    # ground and perspective grid
    ground, grid = hexrgb('#120822'), hexrgb('#4B2270')
    d.rectangle([0, horizon, w, h], fill=ground)
    d.line([(0, horizon), (w, horizon)], fill=hexrgb('#F2587A'))
    y, step = horizon + 2, 1.0
    while y < h:
        d.line([(0, int(y)), (w, int(y))], fill=grid)
        step *= 1.45
        y += step
    for i in range(-12, 13):
        d.line([(cx + i * 3, horizon), (cx + i * w // 8, h)], fill=grid)
    return img, True


def mountains(w, h, rng):
    img = Image.new('RGB', (w, h))
    sky = [hexrgb(c) for c in ('#070B1C', '#0B1330', '#111D42', '#1A2A55', '#243766')]
    bands(img, 0, h, sky)
    stars(img, rng, w * h // 140, int(h * 0.6),
          [hexrgb('#3E4E80'), hexrgb('#6E80B8'), hexrgb('#C8D2F0')])
    d = ImageDraw.Draw(img)
    # crescent moon
    mx, my, mr = int(w * 0.76), int(h * 0.2), max(5, min(w, h) // 16)
    d.ellipse([mx - mr, my - mr, mx + mr, my + mr], fill=hexrgb('#E8E4C8'))
    d.ellipse([mx - mr + mr // 2, my - mr - 1, mx + mr + mr // 2, my + mr - 1],
              fill=img.getpixel((mx + mr + 2, my)))
    layers = [('#1E2A57', 0.52, 0.10), ('#151F45', 0.64, 0.08), ('#0C1331', 0.78, 0.06)]
    for colour, base, rough in layers:
        pts, x = [], -rng.randrange(10, 30)
        while x < w + 40:
            pts.append((x, int(h * base - rng.uniform(0.2, 1.0) * rough * h * 1.6)))
            x += rng.randrange(14, 34)
            pts.append((x, int(h * base + rng.uniform(0, 0.4) * rough * h)))
            x += rng.randrange(10, 26)
        d.polygon(pts + [(w + 40, h), (-40, h)], fill=hexrgb(colour))
    return img, False


def console(w, h, rng):
    """A handheld drawn as a thin outline, low contrast so icons stay readable."""
    img = Image.new('RGB', (w, h), hexrgb('#0F1317'))
    d = ImageDraw.Draw(img)
    dot = hexrgb('#161B21')
    for y in range(2, h, 5):
        for x in range(2, w, 5):
            d.point((x, y), fill=dot)
    line, fill = hexrgb('#2E3845'), hexrgb('#131820')
    bh = int(h * 0.78)
    bw = int(bh * 0.62)
    x0, y0 = (w - bw) // 2, (h - bh) // 2
    d.rounded_rectangle([x0, y0, x0 + bw, y0 + bh], radius=bw // 8, fill=fill, outline=line)
    # screen
    sw = int(bw * 0.78)
    sh = sw * 3 // 4
    sx, sy = x0 + (bw - sw) // 2, y0 + bw // 10
    d.rounded_rectangle([sx - 2, sy - 2, sx + sw + 2, sy + sh + 2], radius=2, outline=line)
    d.rectangle([sx, sy, sx + sw, sy + sh], fill=hexrgb('#0B0E12'))
    ys, ye = sy + sh + 3, y0 + bh - 3
    u = max(3, bw // 18)
    # d-pad left, A B X Y right
    cy = ys + (ye - ys) * 3 // 10
    dx = x0 + bw // 4
    d.rectangle([dx - u, cy - 3 * u, dx + u, cy + 3 * u], outline=line)
    d.rectangle([dx - 3 * u, cy - u, dx + 3 * u, cy + u], outline=line)
    d.rectangle([dx - u + 1, cy - u, dx + u - 1, cy + u], fill=fill)
    d.rectangle([dx - u, cy - u + 1, dx + u, cy + u - 1], fill=fill)
    bx = x0 + bw * 3 // 4
    for ox, oy in ((0, -2), (2, 0), (0, 2), (-2, 0)):
        px, py = bx + ox * u, cy + oy * u
        d.ellipse([px - u + 1, py - u + 1, px + u - 1, py + u - 1], outline=line)
    # two sticks, then select and start at the bottom
    sy2 = ys + (ye - ys) * 7 // 10
    for sxx in (x0 + bw * 3 // 8, x0 + bw * 5 // 8):
        d.ellipse([sxx - 3 * u // 2, sy2 - 3 * u // 2, sxx + 3 * u // 2, sy2 + 3 * u // 2],
                  outline=line)
    py = ye - u
    for sxx in (x0 + bw // 2 - 3 * u, x0 + bw // 2 + u):
        d.line([(sxx, py), (sxx + 2 * u, py)], fill=line)
    return img, False


def pattern(w, h, rng):
    """Wrapping-paper tiles of controller bits."""
    img = Image.new('RGB', (w, h), hexrgb('#141920'))
    d = ImageDraw.Draw(img)
    ink = hexrgb('#1F2630')
    step = 20
    for row, y in enumerate(range(-step, h + step, step)):
        for col, x in enumerate(range(-step, w + step, step)):
            x2 = x + (step // 2 if row % 2 else 0)
            kind = (row * 3 + col) % 4
            if kind == 0:   # d-pad
                d.rectangle([x2 - 1, y - 4, x2 + 1, y + 4], fill=ink)
                d.rectangle([x2 - 4, y - 1, x2 + 4, y + 1], fill=ink)
            elif kind == 1:  # face button
                d.ellipse([x2 - 3, y - 3, x2 + 3, y + 3], outline=ink)
            elif kind == 2:  # four buttons
                for ox, oy in ((0, -3), (3, 0), (0, 3), (-3, 0)):
                    d.point((x2 + ox, y + oy), fill=ink)
            else:            # start/select
                d.rounded_rectangle([x2 - 4, y - 1, x2 + 4, y + 1], radius=1, fill=ink)
    return img, False


def clearshell(w, h, rng):
    """Looking through a clear purple shell at the circuit board."""
    img = Image.new('RGB', (w, h))
    bands(img, 0, h, [hexrgb(c) for c in ('#1C0E30', '#241339', '#2C1843', '#341D4E')])
    d = ImageDraw.Draw(img)
    trace, pad, chip = hexrgb('#4A2C72'), hexrgb('#6A44A0'), hexrgb('#1A0D2A')
    for _ in range(w * h // 700):
        x, y = rng.randrange(w), rng.randrange(h)
        dx, dy = rng.choice(((1, 0), (-1, 0), (0, 1), (0, -1)))
        for _seg in range(rng.randrange(2, 5)):
            n = rng.randrange(5, 25)
            for _ in range(n):
                if 0 <= x < w and 0 <= y < h:
                    d.point((x, y), fill=trace)
                x, y = x + dx, y + dy
            # 45 degree jog, then carry on straight
            jx, jy = (dx or rng.choice((-1, 1))), (dy or rng.choice((-1, 1)))
            for _ in range(rng.randrange(2, 5)):
                x, y = x + jx, y + jy
                if 0 <= x < w and 0 <= y < h:
                    d.point((x, y), fill=trace)
        d.ellipse([x - 1, y - 1, x + 1, y + 1], outline=pad)
    placed = []
    for _ in range(40):
        if len(placed) == (3 if w * h < 20000 else 4):
            break
        cw, ch = rng.randrange(14, 26), rng.randrange(10, 18)
        cx, cy = rng.randrange(8, w - cw - 8), rng.randrange(8, h - ch - 8)
        if any(cx < a + c + 6 and a < cx + cw + 6 and cy < b + e + 6 and b < cy + ch + 6
               for a, b, c, e in placed):
            continue
        placed.append((cx, cy, cw, ch))
        d.rectangle([cx, cy, cx + cw, cy + ch], fill=chip, outline=trace)
        for px in range(cx + 2, cx + cw - 1, 3):
            d.point((px, cy - 1), fill=pad)
            d.point((px, cy + ch + 1), fill=pad)
    return img, False


FONT = {  # 3x5 pixel font, rows top to bottom
    'a': ('010', '101', '111', '101', '101'), 'n': ('110', '101', '101', '101', '101'),
    'd': ('110', '101', '101', '101', '110'), 'r': ('110', '101', '110', '101', '101'),
    '3': ('111', '001', '011', '001', '111'), '6': ('111', '100', '111', '101', '111'),
    'o': ('111', '101', '101', '101', '111'), 'i': ('111', '010', '010', '010', '111'),
    '>': ('100', '010', '001', '010', '100'), ' ': ('000',) * 5,
    '_': ('000', '000', '000', '000', '111'),
}


def text(d, x, y, s, colour):
    for ch in s:
        for r, row in enumerate(FONT[ch]):
            for c, bit in enumerate(row):
                if bit == '1':
                    d.point((x + c, y + r), fill=colour)
        x += 4


def phosphor(w, h, rng):
    """A dark green monitor with a prompt, vignette and scanlines."""
    img = Image.new('RGB', (w, h))
    px = img.load()
    cx, cy = w / 2, h / 2
    for y in range(h):
        for x in range(w):
            r = math.hypot((x - cx) / cx, (y - cy) / cy)
            k = max(0.0, 1 - 0.55 * r * r)
            px[x, y] = (int(4 + 6 * k), int(10 + 22 * k), int(6 + 10 * k))
    d = ImageDraw.Draw(img)
    text(d, 8, h - 14, '> andr36oid_', hexrgb('#2F8F4A'))
    return img, True


PICTURES = [
    ('andr36oid_sunset', sunset),
    ('andr36oid_hills', mountains),
    ('andr36oid_console', console),
    ('andr36oid_pattern', pattern),
    ('andr36oid_clearshell', clearshell),
    ('andr36oid_phosphor', phosphor),
]


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img = img.quantize(colors=64, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    img.save(path, optimize=True)


def main():
    for w, h, folder in ((640, 480, 'drawable-nodpi'), (720, 720, 'drawable-h600dp-nodpi')):
        for name, draw in PICTURES:
            rng = random.Random(name)  # same picture on every run
            small, lines = draw(w // SCALE, h // SCALE, rng)
            big = small.resize((w, h), Image.NEAREST)
            if lines:
                scanlines(big)
            save(big, os.path.join(HERE, 'res', folder, name + '.png'))
            if folder == 'drawable-nodpi':
                thumb = big.resize((160, 120), Image.LANCZOS)
                save(thumb, os.path.join(HERE, 'res', folder, name + '_small.png'))


if __name__ == '__main__':
    main()
