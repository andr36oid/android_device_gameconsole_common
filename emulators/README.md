# Preinstalled emulators

What ships, and how it gets onto the device:

| Component | Version | License | On /system |
|---|---|---|---|
| RetroArch (F-Droid, `com.retroarch`) | 1.22.2 | GPL-3.0-only | 30.9 MB (APK) |
| PPSSPP (F-Droid, `org.ppsspp.ppsspp`) | 1.19.3 | GPL-2.0-or-later | 41.3 MB (APK) |
| libretro cores, FOSS tier (13) | pinned commits | GPL/AGPL/MPL/Zlib | 4.1 MB (tar.gz) |
| libretro cores, non-commercial tier (3, opt-in) | pinned commits | non-commercial | 2.5 MB (tar.gz) |

FOSS cores: fceumm (NES), gambatte (GB/GBC), mgba + gpsp (GBA), pcsx_rearmed (PS1),
mednafen_pce_fast (PCE/TG16), gearsystem (SMS/GG/SG-1000), clownmdemu (MD/Mega CD),
mednafen_ngp (NGP/NGPC), mednafen_wswan (WS/WSC), stella2014 (2600), prosystem (7800),
handy (Lynx).

Non-commercial cores (`GAMECONSOLE_EMU_NONCOMMERCIAL_CORES := true`): snes9x2010 (SNES),
genesis_plus_gx and picodrive (MD/SMS/GG/Sega CD/32X).

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
   folder on EASYROMS, `/storage/<uuid>/bios`, like ArkOS's `/roms/bios`. See
   [BIOS folder](#bios-folder) below.

Log tag: `gameconsole-emu`. State: `/data/misc/gameconsole` and
`/data/user/0/com.retroarch/.gameconsole`.

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

RetroArch 1.22.2 targets SDK 28 with `requestLegacyExternalStorage` and gets the storage
permissions from `pm install -g`, so it reads `/storage/<uuid>` like it reads the games.
Settings > Emulator files checks what is in that folder.

PPSSPP isn't seeded: it stores its settings (and `controls.ini`) in the memory stick
folder the user picks on its first start, so there is nothing to seed before that.

## Updating

```bash
# APKs: edit apks.lock (versionCode + sha256 from F-Droid's index), then
emulators/scripts/fetch-apks.sh

# cores: edit commits in cores.lock, then (NDK r27c gets downloaded if $ANDROID_NDK_HOME is unset)
emulators/scripts/build-cores.sh
```

Both regenerate the NOTICE files next to the binaries; commit them together.
The `.apk` and `.tar.gz` files go through git LFS.

## Source

Nothing needs uploading with a release. The APKs are unmodified upstream releases and
their NOTICE files point at the upstream repository at the release tag; the cores'
NOTICE lists the repository and commit each one is built from. The NOTICE files show
up in Settings > About > Legal information.
