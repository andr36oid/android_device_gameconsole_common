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
2. Unpack `libretro/cores-*.tar.gz` into `/data/user/0/com.retroarch/cores`, which is
   RetroArch's default core dir and what Daijishou's `com.retroarch` player templates
   point at. Cores the user replaced (online updater etc.) are left alone; clearing
   RetroArch's data re-seeds.

Log tag: `gameconsole-emu`. State: `/data/misc/gameconsole` and
`/data/user/0/com.retroarch/.gameconsole`.

The service has no SELinux domain, same as `joyMouse`; it relies on the ROM running
permissive.

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
