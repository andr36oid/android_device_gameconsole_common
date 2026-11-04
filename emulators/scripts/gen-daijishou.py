#!/usr/bin/env python3
"""Makes emulators/daijishou/daijishou-seed.txt, the Daijishou platforms and players the
gameconsole-emu service puts into Daijishou's library on the first start.

Input:
  daijishou/platforms.txt   ROM folder(s) -> official platform file -> cores/apps, best first
  daijishou/official/*.json the platform files from github.com/TapiocaFox/Daijishou (MIT)
  seed/rom-folders.txt      the ROM folders we make on EASYROMS
  cores.lock                the libretro cores we ship
  daijishou/darkosen-cores.txt Android -> reference Linux core name map

Output (tab separated, one record per line, values already written as SQL):
  daijishou  <versionCode>                         the Daijishou the schema is for
  platform   <n>  <folders>  <SQL values>          PlatformEntity without id, default
                                                   player, player list and sync folders
  player     <n>  <needs>    <SQL values>          PlayerEntity without id; needs is
                                                   core:<name> or app:<package>
  art        <n>  <file>                           the platform's picture in
                                                   daijishou/art/ (wallpaper and
                                                   thumbnail), see fetch-daijishou-art.py

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

# The folders RetroArch's own launcher hands to RetroActivityFuture
# (MainMenuActivity.startRetroActivity). Without DATADIR RetroArch has no default
# folders, so a core given by name ("-e LIBRETRO gambatte") is only found once
# retroarch.cfg has libretro_directory, which RetroArch writes the first time it is
# opened itself: until then every game from Daijishou hung RetroArch. EXTERNAL and
# SDCARD keep saves and states where they are when RetroArch is opened itself.
RETROARCH_EXTRAS = (
    " -e DATADIR /data/user/0/com.retroarch",
    " -e EXTERNAL /storage/emulated/0/Android/data/com.retroarch/files",
    " -e SDCARD /storage/emulated/0",
)


# Native extensions from the dArkOSen reference core info (Phase 2). The official
# catalog sometimes omits formats or names another core's formats. Apply this to
# both official and synthesized players; keep the cached JSONs byte-for-byte.
# RetroArch can extract zip and 7z for cores without block_extract. Geolith is
# used for carts here; NeoCD retains its existing players. Directory launching
# is not a filename extension, so it is omitted here.
CORE_EXTENSIONS = {
    'hatarib': 'st|msa|dim|stx|ipf|ctr|m3u|m3u8|zip|zst|gz|acsi|ahd|vhd|scsi|shd|ide|gem|7z',
    'mednafen_pcfx': 'cue|ccd|toc|chd',
    'np2kai': 'd98|zip|98d|fdi|fdd|2hd|tfd|d88|88d|hdm|xdf|dup|cmd|hdi|thd|nhd|hdd|hdn',
    'quasi88': 'd88|u88|m3u',
    'tic80': 'tic',
    'wasm4': 'wasm',
    'vircon32': 'v32',
    'uzem': 'uze|zip|7z',
    'ecwolf': 'wl6|n3d|sod|sdm|wl1|pk3|exe',
    'potator': 'bin|sv|zip|7z',
    'freechaf': 'bin|chf|zip|7z',
    'minivmac': 'dsk|img|zip|hvf|cmd|7z',
    'emuscv': 'cart|bin|rom|0|zip',
    'x1': 'dx1|zip|2d|2hd|tfd|d88|88d|hdm|xdf|dup|tap|cmd|7z',
    'geolith': 'neo',
    'onsyuri': 'txt|dat|___|nt2|nt3|ons',
    'puzzlescript': 'pz|pzp',
    'vice_x64sc': 'd64|d71|d80|d81|d82|g64|g41|x64|t64|tap|prg|p00|crt|bin|zip|gz|d6z|d7z|d8z|g6z|g4z|x6z|cmd|m3u|vfl|vsf|nib|nbz|d2m|d4m',
    'vice_xscpu64': 'd64|d71|d80|d81|d82|g64|g41|x64|t64|tap|prg|p00|crt|bin|zip|gz|d6z|d7z|d8z|g6z|g4z|x6z|cmd|m3u|vfl|vsf|nib|nbz|d2m|d4m',
    'vice_xcbm2': 'd64|d71|d80|d81|d82|g64|g41|x64|t64|tap|prg|p00|crt|bin|zip|gz|d6z|d7z|d8z|g6z|g4z|x6z|cmd|m3u|vfl|vsf|nib|nbz|d2m|d4m',
    'vice_xcbm5x0': 'd64|d71|d80|d81|d82|g64|g41|x64|t64|tap|prg|p00|crt|bin|zip|gz|d6z|d7z|d8z|g6z|g4z|x6z|cmd|m3u|vfl|vsf|nib|nbz|d2m|d4m',
    'snes9x2005': 'smc|fig|sfc|gd3|gd7|dx2|bsx|swc|zip|7z',
    'quicknes': 'nes|zip|7z',
    'sameboy': 'gb|gbc|zip|7z',
    'fmsx': 'rom|mx1|mx2|dsk|fdi|cas|m3u|zip|7z',
    'mednafen_lynx': 'lnx|lyx|bll|o|zip|7z',
    'ardens': 'hex|arduboy|zip|7z',
    'arduous': 'hex|zip|7z',
    'b2': 'ssd|dsd|zip|7z',
    'bk': 'bin|zip|7z',
    'sameduck': 'bin|duck|zip|7z',
    '81': 'p|tzx|t81|zip|7z',
}


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


def art_file(index, platform):
    """The picture Daijishou's own import gives this platform from the COLORFUL pack
    (unique id, then short name, then the pack's default), as our file name in
    daijishou/art/. Same rules as scripts/fetch-daijishou-art.py."""
    name = None
    for key, field in (("matchPlatformUniqueId", "uniqueId"),
                       ("matchPlatformShortname", "shortname")):
        for e in index["wallpaperList"]:
            if name is None and e.get(key) and e[key] == platform.get(field):
                name = e["filename"]
    if name is None and index.get("hasDefaultWallpaper"):
        name = index["defaultWallpaperFilename"]
    if name is None:
        return None
    ours = re.sub(r"[^a-z0-9]+", "-", os.path.splitext(name)[0].lower()).strip("-") + ".jpg"
    if not os.path.isfile(os.path.join(DJ, "art", ours)):
        raise SystemExit("%s: no daijishou/art/%s, run scripts/fetch-daijishou-art.py"
                         % (platform["uniqueId"], ours))
    return ours


def generate():
    rom_folders = {w[0] for w in read_words(os.path.join(EMU, "seed", "rom-folders.txt"))}
    cores = {w[0] for w in read_words(os.path.join(EMU, "cores.lock"))}
    darkosen = dict(read_words(os.path.join(DJ, "darkosen-cores.txt")))
    if not darkosen.keys() <= cores:
        raise SystemExit("darkosen-cores.txt contains a core absent from cores.lock")
    with open(os.path.join(DJ, "art", "index.json"), encoding="utf-8") as f:
        art_index = json.load(f)

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
        if platform["uniqueId"] == "ports":
            # Ports and RACORE must share the setup package's single ES launcher
            # entry: Daijishou's package preflight uses MAIN/LAUNCHER matches.
            target = "-n org.andr36oid.subsystemsetup/.LaunchPortActivity"
            if (needs != ["app:org.andr36oid.subsystemsetup"] or
                    not all(p["amStartArguments"].startswith(target + "\n") for p in players)):
                raise SystemExit("Ports must launch via SubsystemSetup's ES package")
        if platform["uniqueId"] in seen_ids:
            raise SystemExit("%s: platform %s twice" % (fname, platform["uniqueId"]))
        seen_ids.add(platform["uniqueId"])

        lines.append("platform\t%d\t%s\t%s" % (n, ",".join(folders), platform_values(platform)))
        art = art_file(art_index, platform)
        if art:
            lines.append("art\t%d\t%s" % (n, art))
        alternate = None
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
            if ra_core(p):
                p = dict(p)
                extensions = CORE_EXTENSIONS.get(ra_core(p))
                if extensions:
                    p["acceptedFilenameRegex"] = (
                        r"(?i)^(?!(?:\._|\.).*).*\.(?:" + extensions + r")$")
                p["amStartArguments"] = "\n".join(
                    [p["amStartArguments"].rstrip("\n")] + list(RETROARCH_EXTRAS))
            lines.append("player\t%d\t%s\t%s" % (n, want, player_values(p)))
            # One alternate per platform, using its first reference-supported core.
            # Append after every original player so their default ordering is unchanged.
            if alternate is None and ra_core(p) in darkosen:
                alternate = dict(p)
                core = darkosen[ra_core(p)]
                alternate["uniqueId"] = platform["uniqueId"] + ".andr36oid.darkosen"
                alternate["name"] = "RetroArch (dArkOSen)"
                alternate["description"] = (
                    "Linux core: " + core + ". Enable Daijishou dArkOSen cores in "
                    "subsystem settings, then select this player. Exit to return to Android.")
                alternate["amStartArguments"] = (
                    "-n org.andr36oid.subsystemsetup/.RetroArchActivity\n"
                    " -e ROM {file.path}\n -e CORE " + core)
                alternate["killPackageProcesses"] = False
                alternate["killPackageProcessesWarning"] = False
                alternate_need = want
        if alternate is not None:
            # Same prerequisite as its Android counterpart; no change to setup's seed format.
            lines.append("player\t%d\t%s\t%s" % (n, alternate_need, player_values(alternate)))
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
