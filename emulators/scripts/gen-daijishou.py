#!/usr/bin/env python3
"""Makes emulators/daijishou/daijishou-seed.txt, the Daijishou platforms and players the
gameconsole-emu service puts into Daijishou's library on the first start.

Input:
  daijishou/platforms.txt   ROM folder(s) -> official platform file -> cores/apps, best first
  daijishou/official/*.json the platform files from github.com/TapiocaFox/Daijishou (MIT)
  seed/rom-folders.txt      the ROM folders we make on EASYROMS
  cores.lock                the libretro cores we ship

Output (tab separated, one record per line, values already written as SQL):
  daijishou  <versionCode>                         the Daijishou the schema is for
  platform   <n>  <folders>  <SQL values>          PlatformEntity without id, default
                                                   player, player list and sync folders
  player     <n>  <needs>    <SQL values>          PlayerEntity without id; needs is
                                                   core:<name> or app:<package>

Run with --check to only compare with the file in the tree (used by the host tests).
"""

import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
EMU = os.path.dirname(HERE)
DJ = os.path.join(EMU, "daijishou")

# Daijishou shipped in apps/Daijishou (versionCode). The schema in daijishou/schema.sql
# is for this version, the seed script checks it on the console.
DAIJISHOU_VERSION_CODE = 416

PLATFORM_COLUMNS = [
    # (column, key in the platform file, kind)
    ("unique_id", "uniqueId", "text"),
    ("name", "name", "text"),
    ("shortname", "shortname", "text"),
    ("description", "description", "text"),
    ("is_visible", None, 1),
    ("scrape_preview_media", None, 1),
    ("wallpaper_path", None, None),
    ("thumbnail_path", None, None),
    ("color_theme", None, None),
    ("color_on_theme", None, None),
    ("accept_filename_regex", "acceptedFilenameRegex", "text"),
    ("scraper_source_list", "scraperSourceList", "json"),
    ("box_art_aspect_ratio_id", "boxArtAspectRatioId", "int"),
    ("use_custom_box_art_aspect_ratio", "useCustomBoxArtAspectRatio", "bool"),
    ("custom_box_art_aspect_ratio", "customBoxArtAspectRatio", "text"),
    ("screen_aspect_ratio_id", "screenAspectRatioId", "int"),
    ("use_custom_screen_aspect_ratio", "useCustomScreenAspectRatio", "bool"),
    ("custom_screen_aspect_ratio", "customScreenAspectRatio", "text"),
    ("retro_achievements_console_id_list", "retroAchievementsConsoleIdList", "json"),
    ("extra", "extra", "text"),
]

PLAYER_COLUMNS = [
    ("unique_id", "uniqueId", "text"),
    ("player", None, "player"),
    ("name", "name", "text"),
    ("description", "description", "text"),
    ("am_start_arguments", "amStartArguments", "text"),
    ("kill_package_processes", "killPackageProcesses", "bool"),
    ("kill_package_processes_warning", "killPackageProcessesWarning", "bool"),
    ("accepted_filename_regex", "acceptedFilenameRegex", "text"),
    ("extra", "extra", "text"),
]

RETROARCH = "-n com.retroarch/"


def sql_text(s):
    """A one-line SQL expression for the string s (NULL for None)."""
    if s is None:
        return "NULL"
    if "\t" in s or "\r" in s:
        raise ValueError("tab or CR in %r" % s)
    parts = ["'" + p.replace("'", "''") + "'" for p in s.split("\n")]
    return " || char(10) || ".join(parts)


def sql_value(obj, key, kind):
    if key is None:
        return "NULL" if kind is None else str(kind)
    v = obj.get(key)
    if kind == "text":
        return sql_text(v)
    if kind == "json":
        # kotlinx.serialization's compact form, as Daijishou's type converters write it
        return sql_text(json.dumps(v if v is not None else [], separators=(",", ":"),
                                   ensure_ascii=False))
    if kind == "int":
        return str(int(v))
    if kind == "bool":
        return "1" if v else "0"
    raise ValueError(kind)


def player_values(p):
    vals = []
    for col, key, kind in PLAYER_COLUMNS:
        if kind == "player":
            # what Daijishou's import sets: 2 = launches an Android activity
            vals.append("2" if "{android.activity}" in p["amStartArguments"] else "1")
        else:
            vals.append(sql_value(p, key, kind))
    return ", ".join(vals)


def platform_values(p):
    return ", ".join(sql_value(p, key, kind) for _, key, kind in PLATFORM_COLUMNS)


def read_words(path):
    """Non-comment lines of a whitespace separated file, as lists of words."""
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                out.append(line.split())
    return out


def ra_core(p):
    """The core a com.retroarch player starts, None for any other player."""
    a = p["amStartArguments"]
    if RETROARCH not in a:
        return None
    m = re.search(r"-e LIBRETRO (\S+)", a)
    return m.group(1) if m else None


def find_player(players, need):
    kind, _, name = need.partition(":")
    for p in players:
        if kind == "core" and ra_core(p) == name:
            return p
        if kind == "app" and ("-n %s/" % name) in p["amStartArguments"]:
            return p
    return None


def synth_player(platform, players, core):
    """A player for a core the official file doesn't list, made from its first
    com.retroarch player with the core swapped."""
    base = next((p for p in players if ra_core(p)), None)
    if base is None:
        raise SystemExit("%s: no RetroArch player to copy for %s"
                         % (platform["uniqueId"], core))
    p = dict(base)
    p["name"] = "%s - RetroArch - %s" % (platform["shortname"], core)
    p["uniqueId"] = "%s.andr36oid.%s" % (platform["uniqueId"], core)
    p["description"] = "Added by andr36oid."
    p["amStartArguments"] = re.sub(r"(-e LIBRETRO )\S+", r"\g<1>" + core,
                                   base["amStartArguments"])
    return p


def generate():
    rom_folders = {w[0] for w in read_words(os.path.join(EMU, "seed", "rom-folders.txt"))}
    cores = {w[0] for w in read_words(os.path.join(EMU, "cores.lock"))}

    lines = [
        "# Made by scripts/gen-daijishou.py from daijishou/platforms.txt and the official",
        "# Daijishou platform files (github.com/TapiocaFox/Daijishou, MIT, see",
        "# official/LICENSE). Don't edit, run the script.",
        "daijishou\t%d" % DAIJISHOU_VERSION_CODE,
    ]
    seen_ids, seen_folders = set(), set()
    for n, words in enumerate(read_words(os.path.join(DJ, "platforms.txt")), 1):
        if len(words) != 3:
            raise SystemExit("platforms.txt: bad line %r" % " ".join(words))
        folders, fname, needs = words[0].split(","), words[1], words[2].split(",")
        for folder in folders:
            if folder not in rom_folders:
                raise SystemExit("%s: folder %s is not in rom-folders.txt" % (fname, folder))
            if folder in seen_folders:
                raise SystemExit("%s: folder %s is used twice" % (fname, folder))
            seen_folders.add(folder)
        with open(os.path.join(DJ, "official", fname), encoding="utf-8") as f:
            data = json.load(f)
        platform, players = data["platform"], data["playerList"]
        if platform["uniqueId"] in seen_ids:
            raise SystemExit("%s: platform %s twice" % (fname, platform["uniqueId"]))
        seen_ids.add(platform["uniqueId"])

        lines.append("platform\t%d\t%s\t%s" % (n, ",".join(folders), platform_values(platform)))
        for need in needs:
            if need.startswith("app:"):
                want = need
            else:
                if need not in cores:
                    raise SystemExit("%s: core %s is not in cores.lock" % (fname, need))
                want = "core:" + need
            p = find_player(players, want)
            if p is None:
                if not want.startswith("core:"):
                    raise SystemExit("%s: no player for %s" % (fname, need))
                p = synth_player(platform, players, need)
            lines.append("player\t%d\t%s\t%s" % (n, want, player_values(p)))
    return "\n".join(lines) + "\n"


def main():
    out = os.path.join(DJ, "daijishou-seed.txt")
    text = generate()
    if "--check" in sys.argv[1:]:
        with open(out, encoding="utf-8") as f:
            if f.read() != text:
                print("daijishou-seed.txt is out of date: run scripts/gen-daijishou.py")
                return 1
        print("daijishou-seed.txt is up to date")
        return 0
    with open(out, "w", encoding="utf-8") as f:
        f.write(text)
    print("wrote " + os.path.relpath(out, EMU))
    return 0


if __name__ == "__main__":
    sys.exit(main())
