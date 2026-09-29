# Preinstalled emulators

What ships, and how it gets onto the device:

| Component | Version | License | On /system |
|---|---|---|---|
| RetroArch (F-Droid, `com.retroarch`) | 1.22.2 | GPL-3.0-only | 30.9 MB (APK) |
| PPSSPP (F-Droid, `org.ppsspp.ppsspp`) | 1.19.3 | GPL-2.0-or-later | 41.3 MB (APK) |
| libretro cores, FOSS tier (29) | pinned commits | GPL/LGPL/AGPL/MPL/MIT/Zlib/Artistic | 22.6 MB (tar.gz), 93 MB unpacked |
| libretro cores, non-commercial tier (9, opt-in) | pinned commits | non-commercial | 30.0 MB (tar.gz), 128 MB unpacked |

FOSS cores: nestopia + fceumm (NES/FDS), gambatte (GB/GBC), mgba + gpsp (GBA),
mupen64plus_next_gles3 (N64), pcsx_rearmed (PS1), mednafen_pce_fast (PCE/TG16/PCE CD),
mednafen_supergrafx (SuperGrafx), gearsystem (SMS/GG/SG-1000), clownmdemu (MD/Mega CD),
mednafen_ngp (NGP/NGPC), mednafen_wswan (WS/WSC), mednafen_vb (Virtual Boy), pokemini,
gw (Game & Watch), stella2014 (2600), a5200 (5200), prosystem (7800), handy (Lynx),
o2em (Videopac/Odyssey 2), freeintv (Intellivision), bluemsx (MSX/ColecoVision),
fuse (ZX Spectrum), puae2021 (Amiga), dosbox_pure (DOS), prboom (Doom), fake08 (PICO-8),
easyrpg (RPG Maker 2000/2003).

Non-commercial cores (`GAMECONSOLE_EMU_NONCOMMERCIAL_CORES := true`): snes9x2010 and
snes9x (SNES), genesis_plus_gx and picodrive (MD/SMS/GG/Sega CD/32X), fbneo (arcade,
Neo Geo, CPS), mame2003_plus (arcade), neocd (Neo Geo CD; MAME Z80 core), vice_x64 (C64;
built-in Commodore ROMs), vecx (Vectrex; built-in system ROM).

Every core is checked by `scripts/check-armv80.py` when it is built: no ARMv8.1+
instructions (LSE atomics and friends; the Cortex-A35 is ARMv8.0). Compiler
outline-atomics helpers, which test for LSE at run time, are allowed.
`libretro/cores.sha256` pins every core file.

## How it works

Nothing is installed as a system app. Everything lands in `/system/etc/gameconsole/` and
the `gameconsole-emu` service (`seed/`) runs once `sys.boot_completed=1`:

1. `pm install -g` every APK in `preinstall/*.list` as a normal user app (`preinstall.list` is ours, `magisk.list` comes from `../magisk`).
   The APKs are byte-identical to F-Droid's, so F-Droid can update them and users can
   uninstall them. A removed app stays removed, an older installed version gets upgraded
   when the ROM ships a newer one, a failing version isn't retried every boot.
2. Set up RetroArch, see [RetroArch defaults](#retroarch-defaults) below.
3. Unpack `libretro/cores-*.tar.gz` into `/data/user/0/com.retroarch/cores`, which is
   RetroArch's default core dir and what Daijishou's `com.retroarch` player templates
   point at. Cores the user replaced (online updater etc.) are left alone; clearing
   RetroArch's data re-seeds.
4. Make the usual ROM folders (`seed/rom-folders.txt`, ArkOS/dArkOS names) plus a
   `README-andr36oid.txt` on the EASYROMS partition (`public:179,7`). Once per card, and
   only on a card that has none of these folders yet, so existing layouts stay untouched.
5. Point RetroArch's `system_directory` (where the cores look for BIOS files) at the bios
   folder on EASYROMS, `/storage/<uuid>/bios`, like ArkOS's `/roms/bios` (on a `.noroms`
   card: `/storage/emulated/0/bios`). See
   [BIOS folder](#bios-folder) below.
6. Give Daijishou (the home screen) one platform per system in the ROM folders, with the
   folder on EASYROMS as its sync folder and our core or PPSSPP as its player, before
   Daijishou's first start. See [Daijishou platforms](#daijishou-platforms) below.

Log tag: `gameconsole-emu`. State: `/data/misc/gameconsole` and
`/data/user/0/com.retroarch/.gameconsole`.

Before all that, on the very first start only, it installs the first-start launcher
(`../FirstStart`, shipped as `/system/etc/gameconsole/firststart/FirstStart.apk`) and makes
it the home app for the tutorial after the setup wizard. The quick start guide uninstalls it
when the tutorial is done.

The service has no SELinux domain, same as `joyMouse`; it relies on the ROM running
permissive.

## RetroArch defaults

Two files in `seed/retroarch/`. The hotkeys and save locations are the ones ArkOS and
dArkOS (christianhaitian) use, so a card moves between ArkOS and andr36oid with its saves:

- `GO-Super_Gamepad.cfg`: RetroArch profile for the built-in pad (Android name
  "GO-Super Gamepad", 0x484b:0x1100). Every button incl. L2/R2/L3/R3 and both analog
  sticks work without setup; without it RetroArch falls back to its generic "Android
  Gamepad" binds, which swap A/B and X/Y on this pad. Copied to
  `/data/user/0/com.retroarch/autoconfig/android/` on every boot unless the user changed
  it there (tracked like the cores).
- `retroarch.cfg`: a partial config, RetroArch fills in the rest. Written to
  `/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg` (the one the
  RetroArch app and Daijishou's RetroArch players load) **only if there is no
  retroarch.cfg yet**, so an existing or user-changed config is never touched. That also
  means installs that already ran RetroArch keep their settings after an update: to get
  these defaults, delete that retroarch.cfg (or clear RetroArch's data) and reboot.

What the config sets:

| Setting | Value | Why |
|---|---|---|
| saves and states | next to the game (`savefiles_in_content_dir`, `savestates_in_content_dir`, sorting off) | same place as ArkOS: `EASYROMS/gba/game.srm`, `game.state1` |
| hotkeys | hold Select + Start quit (twice), R1/L1 save/load state, Up/Down state slot, X menu, A pause, B reset, Y screenshot, L3 fast forward; L1+R1+Start+Select menu | ArkOS's mapping |
| `autosave_interval` | 10 s (also RetroArch's Android default) | FN (Home) only pauses RetroArch; if Android later closes it in the background, at most 10 s of in-game saving is lost |
| `menu_driver` | `rgui` | ArkOS's menu; readable at 640x480, needs no assets (Android's default Material UI is for touch) |
| `video_threaded` | on | as ArkOS, keeps the Cortex-A35 at full speed |

Left at RetroArch's Android defaults on purpose (they already match ArkOS or the
screen): bilinear filter off, integer scale off, aspect ratio core provided, audio
latency 128 ms, save state auto save/load off.

Saves only carry over when both sides run a core with the same save format. The core is
picked by Daijishou's player, not by retroarch.cfg. Same as ArkOS: gambatte, mGBA,
PCSX ReARMed, Beetle PCE Fast/NeoPop/Cygne, Genesis Plus GX, PicoDrive. Use mGBA rather
than gpSP for GBA, and Genesis Plus GX (non-commercial tier) rather than Gearsystem for
Master System/Game Gear. Save states only load in the same core.

### BIOS folder

The EASYROMS path has the card's UUID in it, so it can't be in the seeded retroarch.cfg.
Instead the service sets `system_directory = "/storage/<uuid>/bios"` on every boot (an
existing `BIOS`/`Bios` folder is used as it is, a missing one is made):

- only while the setting is unset, RetroArch's default (`default`,
  `/storage/emulated/0/RetroArch/system`, which RetroArch writes back on exit) or the
  value the service wrote last time (`/data/misc/gameconsole/retroarch-system-dir`).
  So a new or reformatted card gets its new path, and a folder the user picked in
  RetroArch (Settings > Directory > System/BIOS) is never touched.
- installs from before this change get it too, as long as the setting is still the default.
- if EASYROMS isn't mounted, nothing changes and the next boot tries again.
- a card set up with `.noroms` on BOOT has no EASYROMS partition at all (no
  `/sys/block/mmcblk0/mmcblk0p7`; the first start gives userdata the whole card). Then
  the folder is `bios` on the internal storage, `/storage/emulated/0/bios` (made with the
  owner and mode of `/data/media/0`), with the same rules. Moving between a card with
  and one without EASYROMS updates the path we wrote.

RetroArch 1.22.2 targets SDK 28 with `requestLegacyExternalStorage` and gets the storage
permissions from `pm install -g`, so it reads `/storage/<uuid>` like it reads the games.
Settings > Emulator files checks what is in that folder.

### Core options

`seed/retroarch/retroarch-core-options.cfg` goes to
`Android/data/com.retroarch/files/retroarch-core-options.cfg`, next to retroarch.cfg.
RetroArch (per-core options, its default) reads that file for any core that has no
`config/<core>/<core>.opt` yet and copies the values into that file on first use; after
that the user's own changes win. So it works for existing installs too, for every core
they haven't run yet. The service writes it when missing and updates it with a newer
ROM only while the user hasn't edited it (same manifest as the pad profile).

It only holds values that differ from the core defaults (plus the recompilers, pinned):
PCSX ReARMed simple sound interpolation and frameskip only against crackling;
Mupen64Plus-Next at 320x240 with GLideN64 threaded and no frame buffer copies to N64
memory; Snes9x 2010 threaded renderer and frameskip only against crackling; MAME
2003-Plus no disclaimer screens and 22 kHz sound; PUAE 2021 "compatible" CPU timing
and low resolution; VICE without true drive emulation. The keys and values are checked
against the core sources at the pinned commits.

PPSSPP isn't seeded: it stores its settings (and `controls.ini`) in the memory stick
folder the user picks on its first start, so there is nothing to seed before that.

## Daijishou platforms

Without this a new user has to add every platform in Daijishou by hand (its "Download
platforms" list comes from GitHub, so offline there is nothing to pick) and pick each ROM
folder in Android's file picker. Daijishou 1.5.0 has no intent, deep link or watched
folder for importing platforms, and its import (platform JSON, Pegasus config, backup)
always goes through the file picker; backups don't even keep the sync folders. So we fill
its library the way Daijishou itself stores it, once, before it first runs:

- `daijishou/platforms.txt`: ROM folder(s) -> official platform file -> cores, best first.
  `scripts/gen-daijishou.py` makes `daijishou/daijishou-seed.txt` from it, from the
  official platform files in `daijishou/official/` (github.com/TapiocaFox/Daijishou
  `platforms/` at cde33bb, MIT, `official/LICENSE`), `seed/rom-folders.txt` and
  `cores.lock`. Platforms and players are the official ones (same uniqueId and player
  names), so Daijishou's own "update platforms" keeps working on them. A core the
  official file doesn't list (ClownMDEmu, and MAME 2003-Plus on the FBNeo arcade
  platform) gets a player copied from the platform's first RetroArch player. nds and bios
  get no platform: we ship nothing that plays them.
- `daijishou/schema.sql`: Daijishou's Room schema (version 17, identity hash) exactly as
  its `DaijishouDatabase_Impl.createAllTables` makes it.
- post-fs-data (`gameconsole-emu.sh post-fs-data`, run with `exec_start` before
  system_server starts): Daijishou reads games only through the storage access
  framework, i.e. folders the user picked in the file picker, which Android remembers as
  persisted URI grants in `/data/system/urigrants.xml`. We add the same entry the picker
  would (`content://com.android.externalstorage.documents/tree/<UUID>%3A<folder>`, prefix,
  read+write) for each platform folder. system_server only reads that file when it
  starts, hence post-fs-data; the EASYROMS UUID comes from `blkid`, the tool vold names
  `/storage/<UUID>` with. Existing grants are kept, nothing is doubled.
- boot_completed (step 6): if Daijishou has no `Daijishou.db` yet, is the versionCode in
  `daijishou-seed.txt` (416) and isn't running, write `Daijishou.db` with `sqlite3`: a
  platform for each entry whose folder is on the card and that has at least one player
  here (core in RetroArch's core dir, PPSSPP installed). Plus, only if Daijishou has no
  settings file yet, `sync_entirely_on_start=true`: on its first start Daijishou asks once
  to "Sync entirely" (our text in the dialog) and then finds the games itself.

New games later (copied on a computer, say): Daijishou only looks for games when asked.
On a console where we made the library, each boot compares the game files in the
platform folders (saves, states, pictures, hidden files left out) with the last boot. If
games were added, it sets `sync_entirely_on_start` again ("The games on EASYROMS changed.
New: gba (12), snes (3). Press OK to update the library."), force-stops Daijishou around
the change (it keeps its settings in memory) and brings the home screen back if Daijishou
was in front. Games that were only removed don't count: Daijishou's sync keeps their
entries anyway, so it would read every game for nothing. A whole-library sync reads the
start (up to 4 MB) of every game file for Daijishou's md5, so from 2000 games on
(`DJ_BIG`) the text adds the expected time (1 minute per 600 games) and says to press
Cancel and use each system's own Sync button instead. Off with
`setprop persist.andr36oid.daijishou_autosync 0`.

Never again after it ran once (`/data/misc/gameconsole/daijishou.done`), never on a
Daijishou that has a library, never on a `.noroms` card (no EASYROMS; games are on the
internal storage there). A different Daijishou versionCode skips it with a log line.

After a Daijishou update: run `tests/run-daijishou-tests.sh`. If the schema check
fails, take the new `createAllTables` statements, identity hash and version from the new
APK (jadx, `DaijishouDatabase_Impl`) into `schema.sql`, set `DAIJISHOU_VERSION_CODE` in
the generator and rerun it. Daijishou migrates an older library by itself, so an old
schema is only a problem for consoles that seed after the update.

Tests: `ANDROID_BUILD_TOP=... emulators/tests/run-daijishou-tests.sh` (generator up to date,
schema vs. the APK's dex, the shell steps in a fake /data under bash and busybox ash with
a real sqlite3, checked by `tests/checklib.py`).

## Updating

```bash
# APKs: edit apks.lock (versionCode + sha256 from F-Droid's index), then
emulators/scripts/fetch-apks.sh

# cores: edit commits in cores.lock, then (NDK r27c gets downloaded if $ANDROID_NDK_HOME is unset)
emulators/scripts/build-cores.sh
```

Only cores whose commit changed are rebuilt; the others are taken from the current
archive after a sha256 check (`--rebuild` builds everything; the old binaries are not
byte-reproducible on another machine). EasyRPG builds with `scripts/cores/easyrpg.sh`,
which also builds its libraries (needs `meson`, `ninja`, autotools and ~20 minutes).

Both regenerate the NOTICE files next to the binaries; commit them together.
The `.apk` and `.tar.gz` files go through git LFS.

## Source

Nothing needs uploading with a release. The APKs are unmodified upstream releases and
their NOTICE files point at the upstream repository at the release tag; the cores'
NOTICE lists the repository and commit each one is built from. The NOTICE files show
up in Settings > About > Legal information.
