#!/usr/bin/env python3
"""Generate the optional Orbit, Signal, Cartridge and handheld XP animations.

Needs Pillow. Original animations use the bundled Roboto font. XP uses the
maintainer-supplied XDA archive; see NOTICE-xp.txt for its source and credits.
All archives are deterministic and store PNGs without ZIP compression, as
Android's boot animation player requires. No frame forces boot to wait.
"""
import hashlib
import io
import math
from pathlib import Path
import zipfile

from PIL import Image, ImageDraw, ImageEnhance, ImageFont

HERE = Path(__file__).resolve().parent
PREVIEWS = HERE.parents[1] / 'ConsoleLook/res/drawable-nodpi'
SCALE = 3
WIDTH, HEIGHT = 480, 192
INK = (232, 236, 236)
TEAL = (128, 203, 196)
TRACK = (28, 48, 47)
AMBER = (204, 172, 116)
FONT = ImageFont.truetype(str(HERE / 'fonts/Roboto-Medium.ttf'), 52 * SCALE)
XP_SHA256 = 'ba53805fbaf3447b95187d8a3416c88bb587d619ec0b64e40cb774ff27baeebb'


def point(x, y):
    return round(x * SCALE), round(y * SCALE)


def box(x0, y0, x1, y1):
    return tuple(round(v * SCALE) for v in (x0, y0, x1, y1))


def frame(style, n, count=36):
    image = Image.new('RGB', (WIDTH * SCALE, HEIGHT * SCALE))
    draw = ImageDraw.Draw(image)
    phase = 2 * math.pi * n / count
    title_y = 130 if style == 'cartridge' else 72
    draw.text(point(240, title_y), 'andr36oid', font=FONT, fill=INK, anchor='mm')
    if style == 'orbit':
        cx, cy, radius = 240, 136, 16
        draw.ellipse(box(cx - radius, cy - radius, cx + radius, cy + radius),
                     outline=TRACK, width=2 * SCALE)
        for lag in range(5, -1, -1):
            angle = phase - lag * 0.13 - math.pi / 2
            x, y = cx + radius * math.cos(angle), cy + radius * math.sin(angle)
            colour = tuple(round(c * (1 - lag * 0.13)) for c in TEAL)
            draw.ellipse(box(x - 2, y - 2, x + 2, y + 2), fill=colour)
    elif style == 'signal':
        for i, x in enumerate((220, 240, 260)):
            height = 8 + 13 * (1 + math.sin(phase - i * 0.7)) / 2
            draw.rounded_rectangle(box(x - 3, 146 - height, x + 3, 146),
                                   radius=3 * SCALE, fill=TEAL)
    elif style == 'cartridge':
        outline = [(214, 28), (257, 28), (266, 37), (266, 76), (214, 76), (214, 28)]
        draw.line([point(x, y) for x, y in outline], fill=TEAL, width=2 * SCALE)
        draw.rounded_rectangle(box(222, 37, 257, 58), radius=2 * SCALE, fill=TRACK)
        draw.line([point(227, 44), point(252, 44)], fill=TEAL, width=SCALE)
        draw.line([point(227, 50), point(245, 50)], fill=TEAL, width=SCALE)
        for i, x in enumerate((230, 240, 250)):
            strength = 0.4 + 0.6 * (1 + math.cos(phase - i * 0.9)) / 2
            colour = tuple(round(c * strength) for c in AMBER)
            draw.rectangle(box(x - 2, 67, x + 2, 74), fill=colour)
    else:
        raise ValueError(style)
    return image.resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS)


def png(image):
    stream = io.BytesIO()
    image.save(stream, 'PNG', optimize=True)
    return stream.getvalue()


def archive(name, width, height, fps, parts):
    path = HERE / ('bootanimation-' + name + '.zip')
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_STORED) as output:
        def add(name, data):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = 0o100644 << 16
            output.writestr(info, data)
        description = f'{width} {height} {fps}\n'
        for index, (repeat, images) in enumerate(parts):
            description += f'p {repeat} 0 part{index}\n'
            for number, image in enumerate(images):
                assert image.size == (width, height) and image.mode == 'RGB'
                add(f'part{index}/{number:03d}.png', png(image))
        add('desc.txt', description.encode('ascii'))
    print(f'{path.name}: {path.stat().st_size} bytes')


def preview(name, image):
    canvas = Image.new('RGB', (640, 480))
    canvas.paste(image, ((640 - image.width) // 2, (480 - image.height) // 2))
    canvas.resize((256, 192), Image.Resampling.LANCZOS).save(
        PREVIEWS / ('bootanim_' + name + '.png'))


def originals():
    for style in ('orbit', 'signal', 'cartridge'):
        first = frame(style, 0)
        intro = [ImageEnhance.Brightness(first).enhance(n / 7) for n in range(8)]
        loop = [frame(style, n) for n in range(36)]
        archive(style, WIDTH, HEIGHT, 12, [(1, intro), (0, loop)])
        preview(style, loop[6])


def windows_xp():
    source = HERE / 'sources/windows-xp.zip'
    assert hashlib.sha256(source.read_bytes()).hexdigest() == XP_SHA256
    images = []
    with zipfile.ZipFile(source) as original:
        # The portrait source has a static logo, an animated loading bar and a
        # footer. Reposition these intact on a 4:3 canvas, never stretch them.
        for name in sorted(n for n in original.namelist()
                           if n.startswith('part0/') and n.endswith('.png')):
            with Image.open(io.BytesIO(original.read(name))) as source_frame:
                assert source_frame.size == (1080, 2400)
                source_frame = source_frame.convert('RGB')
                logo = source_frame.crop((345, 996, 777, 1302))
                bar = source_frame.crop((421, 1376, 652, 1419))
                footer = source_frame.crop((28, 2312, 1053, 2375))
            logo = logo.resize((302, 214), Image.Resampling.LANCZOS)
            bar = bar.resize((162, 30), Image.Resampling.LANCZOS)
            footer = footer.resize((574, 35), Image.Resampling.LANCZOS)
            image = Image.new('RGB', (640, 480))
            image.paste(logo, ((640 - logo.width) // 2, 90))
            image.paste(bar, ((640 - bar.width) // 2, 344))
            image.paste(footer, ((640 - footer.width) // 2, 428))
            images.append(image)
    assert len(images) == 18
    # Source's trailing part1 does not exist. Keep just the real, interruptible
    # 10 fps loop so the animation never holds up an otherwise completed boot.
    archive('xp', 640, 480, 10, [(0, images)])
    preview('xp', images[9])


if __name__ == '__main__':
    PREVIEWS.mkdir(parents=True, exist_ok=True)
    originals()
    windows_xp()
