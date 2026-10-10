#!/usr/bin/env python3
"""Fetches the picture of each system for Daijishou: the COLORFUL pack, Daijishou's own
default when a platform is added by hand (by viking, from LaunchBox's "Colorful
resources", edited by iShade; indexed for Daijishou by TapiocaFox). Only the pictures
our platforms use, shrunk for the 640x480 screen, into daijishou/art/ with the pack's
index. The wallpaper and thumbnail packs are the same pictures, so one set does both.

Needs Pillow and the network; run it again after changing platforms.txt, then
scripts/gen-daijishou.py.
"""

import io
import json
import os
import re
import sys
import urllib.request

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
EMU = os.path.dirname(HERE)
DJ = os.path.join(EMU, "daijishou")
ART = os.path.join(DJ, "art")

# github.com/TapiocaFox/Daijishou, the commit the pictures are from
COMMIT = "cde33bb64af2eb1cc56965c8a68702b3485a6d73"
PACK = ("https://raw.githubusercontent.com/TapiocaFox/Daijishou/%s/themes/"
        "platform_wallpapers_packs/colorful/" % COMMIT)
# Daijishou shrinks them too; this is the screen's height, at most 16:9 wide
MAX_SIZE = (854, 480)
QUALITY = 85


def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url.replace(" ", "%20")),
                                timeout=60) as r:
        return r.read()


def slug(filename):
    """Our file name for a picture of the pack: lower case, no spaces."""
    base = os.path.splitext(filename)[0]
    return re.sub(r"[^a-z0-9]+", "-", base.lower()).strip("-") + ".jpg"


def pick(index, platform):
    """The picture Daijishou's own import gives this platform: unique id, then short
    name, then the pack's default."""
    for key, field in (("matchPlatformUniqueId", "uniqueId"),
                       ("matchPlatformShortname", "shortname")):
        for e in index["wallpaperList"]:
            if e.get(key) and e[key] == platform.get(field):
                return e["filename"]
    if index.get("hasDefaultWallpaper"):
        return index["defaultWallpaperFilename"]
    return None


def platform_files():
    sys.path.insert(0, HERE)
    from importlib import import_module
    gen = import_module("gen-daijishou")
    for words in gen.read_words(os.path.join(DJ, "platforms.txt")):
        with open(os.path.join(DJ, "official", words[1]), encoding="utf-8") as f:
            yield json.load(f)["platform"]


def main():
    index = json.loads(fetch(PACK + "index.json"))
    wanted = sorted({p for p in (pick(index, pl) for pl in platform_files()) if p})
    os.makedirs(ART, exist_ok=True)
    for old in os.listdir(ART):
        if old.endswith(".jpg"):
            os.remove(os.path.join(ART, old))
    for name in wanted:
        img = Image.open(io.BytesIO(fetch(PACK + name))).convert("RGB")
        img.thumbnail(MAX_SIZE, Image.LANCZOS)
        img.save(os.path.join(ART, slug(name)), "JPEG", quality=QUALITY, optimize=True,
                 progressive=False)
        print("%-28s -> %s %dx%d" % (name, slug(name), img.width, img.height))
    with open(os.path.join(ART, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, indent=2, ensure_ascii=False)
        f.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
