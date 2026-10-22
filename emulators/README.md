# Preinstalled emulators

What ships, and how it gets onto the device:

| Component | Version | License | On /system |
|---|---|---|---|
| RetroArch (F-Droid, `com.retroarch`) | 1.22.2 | GPL-3.0-only | 30.9 MB (APK) |
| PPSSPP (F-Droid, `org.ppsspp.ppsspp`) | 1.19.3 | GPL-2.0-or-later | 41.3 MB (APK) |
| libretro cores, FOSS tier (30) | pinned commits | GPL/LGPL/AGPL/MPL/MIT/Zlib/Artistic | 23.1 MB (tar.gz), 95 MB unpacked |
| libretro cores, non-commercial tier (9, opt-in) | pinned commits | non-commercial | 30.0 MB (tar.gz), 128 MB unpacked |

FOSS cores: nestopia + fceumm (NES/FDS), gambatte (GB/GBC), mgba + gpsp (GBA),
mupen64plus_next_gles3 (N64), pcsx_rearmed (PS1), mednafen_pce_fast (PCE/TG16/PCE CD),
mednafen_supergrafx (SuperGrafx), gearsystem (SMS/GG/SG-1000), clownmdemu (MD/Mega CD),
mednafen_ngp (NGP/NGPC), mednafen_wswan (WS/WSC), mednafen_vb (Virtual Boy), pokemini,
gw (Game & Watch), stella2014 (2600), a5200 (5200), prosystem (7800), handy (Lynx),
o2em (Videopac/Odyssey 2), freeintv (Intellivision), bluemsx (MSX/ColecoVision),
fuse (ZX Spectrum), puae2021 (Amiga), dosbox_pure (DOS), prboom (Doom), fake08 (PICO-8),
easyrpg (RPG Maker 2000/2003), yabasanshiro (Saturn).

Non-commercial cores (`GAMECONSOLE_EMU_NONCOMMERCIAL_CORES := true`): snes9x2010 and
snes9x (SNES), genesis_plus_gx and picodrive (MD/SMS/GG/Sega CD/32X), fbneo (arcade,
Neo Geo, CPS), mame2003_plus (arcade), neocd (Neo Geo CD; MAME Z80 core), vice_x64 (C64;
built-in Commodore ROMs), vecx (Vectrex; built-in system ROM).

yabasanshiro is pirry's Yaba Sanshiro for the R36S
([pirrypirrypirry/yabasanshiro-pirry](https://github.com/pirrypirrypirry/yabasanshiro-pirry),
made for ArkOS, based on devMiyax's Yaba Sanshiro), built as a libretro core from our fork
[andr36oid/yabasanshiro-pirry](https://github.com/andr36oid/yabasanshiro-pirry), branch
`andr36oid`: pirry's 1.6.0 plus the fixes its libretro build needed (bundled libchdr for
CHD, GLES 3.1 constants, glue for pirry's core changes). The SH-2 dynarec (devMiyax's
AArch64 one) is the default. It needs a real Saturn BIOS (`saturn_bios.bin`,
`sega_101.bin` or `mpr-17933.bin` in the bios folder); without one it won't start a game.
RetroArch's own core info (`yabasanshiro_libretro.info`) names it, so it shows up as
"Sega - Saturn (YabaSanshiro)". A Yaba Sanshiro the user downloads with RetroArch's online
updater replaces ours like any other core (same file name).

Every core is checked by `scripts/check-armv80.py` when it is built: no ARMv8.1+
instructions (LSE atomics and friends; the Cortex-A35 is ARMv8.0). Compiler
outline-atomics helpers, which test for LSE at run time, are allowed.
`libretro/cores.sha256` pins every core file.

## How it works

Nothing is installed as a system app. The APKs go on the AUTOAPK partition with the other
bundled apps (see `../foss-apps/README.md`), the rest lands in `/system/etc/gameconsole/`, and
the `gameconsole-emu` service (`seed/`) runs once `sys.boot_completed=1`:

1. `pm install -g` the APKs as normal user apps, each described by its `.yml` in `preinstall/`.
   RetroArch (`required: true`) always, PPSSPP when it's ticked on the setup's app page (it
   is at first). The APKs are byte-identical to F-Droid's, so F-Droid can update them and
   users can uninstall them. A removed app stays removed, a failing version isn't retried
   every boot.
2. Set up RetroArch, see [RetroArch defaults](#retroarch-defaults) below, and on a fresh
   PPSSPP install its data folder and settings, see [PPSSPP defaults](#ppsspp-defaults).
3. Unpack `libretro/cores-*.tar.gz` into `/data/user/0/com.retroarch/cores`, which is
   RetroArch's default core dir and what Daijishou's `com.retroarch` player templates
   point at. Cores the user replaced (online updater etc.) are left alone; clearing
   RetroArch's data re-seeds.
4. Make the usual ROM folders (`seed/rom-folders.txt`, ArkOS/dArkOS names) plus a
   `README-andr36oid.txt` on the EASYROMS partition (selected by [init](../easyroms/README.md)). Once per card, and
   only on a card that has none of these folders yet, so existing layouts stay untouched.
   Every ROM folder that exists (ours or the user's) gets an empty `.nomedia` once per card
   (`rom_nomedia`, list in `/data/misc/gameconsole/rom-nomedia.<uuid>`). Android 11's media
   scanner still walks those folders at every mount, but files the pictures, music and
   videos in them as plain files, so Gallery and Eleven don't list box art, EasyRPG/DOS game
   files or PICO-8 carts, and the scanner doesn't read their tags. Daijishou, RetroArch and
   PPSSPP don't use the media store for games. A `.nomedia` the user deletes stays deleted.
5. Point RetroArch's `system_directory` (where the cores look for BIOS files) at the bios
   folder on EASYROMS, `/storage/<uuid>/bios`, like ArkOS's `/roms/bios` (on a `.noroms`
   card: `/storage/emulated/0/bios`). See
   [BIOS folder](#bios-folder) below.
6. Give Daijishou (the home screen) one platform per system in the ROM folders, with the
   folder on EASYROMS as its sync folder and our core or PPSSPP as its player, before
   Daijishou's first start. See [Daijishou platforms](#daijishou-platforms) below.
7. Keep the built-in pad player 1 in RetroArch and let every controller open the menu
   and quit, see [Controllers and players](#controllers-and-players) below.
8. PPSSPP: the PSP face buttons by position on the built-in pad, once PPSSPP has made its
   controls.ini, see the end of [BIOS folder](#bios-folder).

Log tag: `gameconsole-emu`. State: `/data/misc/gameconsole` and
`/data/user/0/com.retroarch/.gameconsole`.

Before all that, on the very first start only, it installs the first-start launcher
(`../FirstStart`, shipped as `/system/etc/gameconsole/firststart/FirstStart.apk`) and makes
it the home app for the tutorial after the setup wizard. The quick start guide uninstalls it
when the tutorial is done.

The service has no SELinux domain, same as `joyMouse`; it relies on the ROM running
permissive.

The service also sets the refresh rate. RetroArch on Android assumes a 60 Hz screen (`video_refresh_rate = 60`) and never asks
the system, and Android itself only knows the rate rounded to whole Hz. RetroArch uses
the value to resample the sound so that a game makes exactly one frame per screen
refresh. So the service works out the real rate from the panel timing the kernel uses
(`/proc/device-tree/dsi@*/panel@*/display-timings`, native mode: pixel clock in whole
kHz / (htotal * vtotal); Panel 4 gives 59.935683) and sets `video_refresh_rate` on
every boot, with the same rules as the BIOS folder: only while it is unset, RetroArch's
default 60, or the value written last time (`/data/misc/gameconsole/retroarch-refresh-rate`).
Switching to another panel's DTB updates it; a rate the user set in RetroArch
(Settings > Video > Output > Vertical Refresh Rate) is kept.

## PPSSPP defaults

PPSSPP 1.19.3 targets Android 15, so on Android 11 it runs with scoped storage: on its
first start it asks where to keep its data (the "memory stick"), and a game started from
Daijishou opens that question instead of the game. `seed_ppsspp` answers it once, right
after the APKs are installed, but only while PPSSPP has no `files/memstick_dir.txt`
(it never ran, or its data was cleared):

- memory stick = PPSSPP's own folder,
  `/storage/emulated/0/Android/data/org.ppsspp.ppsspp/files`. This is what the
  "Skip (Use App Private Data)" button on that screen picks. It is the one folder PPSSPP
  can use without the file picker. Save backup, Storage care and Continue playing
  already look there. Uninstalling PPSSPP deletes it (Save backup keeps copies).
  To keep PSP data on EASYROMS instead: PPSSPP > Settings > System > Memory stick
  folder > Create or choose a PSP folder; PPSSPP offers to move the data.
- `PSP/SYSTEM/ppsspp.ini` and `PSP/SYSTEM/controls.ini` from `seed/ppsspp/`, each only
  if it isn't there. PPSSPP fills in every setting not listed and writes both back on
  exit. A folder the user picked is never touched.

What `ppsspp.ini` sets (every key checked against 1.19.3's `Core/Config.cpp`):

| Setting | Value | Why |
|---|---|---|
| `GraphicsBackend` | OpenGL | PPSSPP's Android default is Vulkan, which this Mali driver doesn't have |
| `InternalResolution`, `AndroidHwScale` | 1x, native | PSP's 480x272; anything more is too much for the Mali-G31 |
| `AutoFrameSkip`, `FrameSkip` | on, up to 2 | keeps speed and sound in heavy scenes (ArkOS, ROCKNIX RK3326) |
| `SkipBufferEffects` | off | buffered rendering; auto frameskip needs it, many games break without it |
| `SkipGPUReadbackMode` | skip | GPU readbacks stall the Mali badly (ROCKNIX RK3326) |
| `TextureBackoffCache` | on | "lazy texture caching" speedhack (ArkOS, ROCKNIX) |
| `TexScalingLevel`, `AnisotropyLevel`, `TextureShader` | off, 0, off | no texture upscaling or extra filtering (ArkOS) |
| `SplineBezierQuality` | low | curved surfaces are CPU heavy (ArkOS, ROCKNIX) |
| `HardwareTransform`, `SoftwareSkinning` | on, on | PPSSPP defaults, stated so a changed default can't slow us down |
| `CPUCore`, `IOTimingMethod`, `SeparateSASThread` | JIT, fast, on | PPSSPP defaults (fastest) |
| `iShowStatusFlags` | 0 | no FPS counter |
| `ImmersiveMode` | on | full screen without system bars |
| `ShowTouchControls`, `ShowTouchPause` | off | no touch screen; PPSSPP would draw its touch pad over the game |
| `UIScaleFactor` | +2 (1.19x) | menus are readable at 640x480 / 178 dpi |
| `CheckForNewVersion` | off | offline console; F-Droid does updates |
| `AutoLoadSaveState`, `EnableStateUndo`, `RewindSnapshotInterval` | off | like RetroArch here; states are 20-40 MB each |
| `AllowMappingCombos` | on | so the hotkeys below show up and can be changed in Control mapping |

Left at PPSSPP's default on purpose (checked against 1.19.3, see
reports/overnight2/ppsspp-cpu.md): `DisableRangeCulling` off (culling runs in the
vertex shader and saves GPU work), `DepthRasterMode` (only on for the few games PPSSPP
flags, where it replaces a slower readback), `InflightFrames` 3, `UberShader*` on,
`ShaderCache` on, `BloomHack` (does nothing at 1x), `RenderDuplicateFrames` off, no
post shader, OpenGL's own render thread (always on for GL; `MultiThreading` is
Vulkan only). Skipping readbacks breaks a few games that PPSSPP flags
RequireBlockTransfer; `compat.ini` below turns readbacks back on for exactly those.

`controls.ini` is PPSSPP's own Android pad map (face buttons, D-pad, stick, L2 = menu,
R2 = fast forward) plus hotkeys like RetroArch's, hold Select and press:
Start quit (asks first if the game wasn't saved for a minute), R1/L1 save/load state,
Up/Down state slot, X menu, A pause, B reset, Y screenshot, L3 fast forward on/off.
The face buttons are PPSSPP's default, so the controllers step (if present) still finds
and moves them.

## RetroArch defaults

Two files in `seed/retroarch/`. The hotkeys and save locations are the ones ArkOS and
dArkOS (christianhaitian) use, so a card moves between ArkOS and andr36oid with its saves:

- `GO-Super_Gamepad.cfg`: RetroArch profile for the built-in pad (Android name
  "GO-Super Gamepad", 0x484b:0x1100). Every button incl. L2/R2/L3/R3 and both analog
  sticks work without setup; without it RetroArch falls back to its generic "Android
  Gamepad" binds, which swap A/B and X/Y on this pad. Copied to
  `/data/user/0/com.retroarch/autoconfig/android/` unless the user changed it there
  (tracked like the cores), whenever the ROM's profiles differ from the last boot's.
- `autoconfig/`: RetroArch profiles for external controllers, seeded into the same folder
  (only when the ROM brings other profiles than last time; a profile the user changed there
  is kept). RetroArch unpacks its own profile set only when it is opened from its icon,
  never when Daijishou starts a game, so without these an external pad got the generic
  "Android Gamepad" binds (no right stick on many pads). Two kinds:
  - `andr36oid_*.cfg`: Xbox (USB and Bluetooth), DualShock 4, DualSense and Switch
    (Pro Controller, SNES, 8BitDo in Switch mode) as Android shows them here, with the
    kernel drivers from night/usb-peripherals and the key layouts in `../gamepads/keylayout`.
    Nintendo pads send their printed letters like the built-in pad (A right), so RetroArch's
    own Pro Controller profile would swap A/B and X/Y. The Guide/PS/Capture button opens
    RetroArch's menu, on any player. The matches sit in the alternative slots 3-9 of each
    file: RetroArch adds the slot number to the match score, so these always win over
    RetroArch's own profiles for the same pad (slots 0-2), whatever the folder order.
  - `upstream/`: the android set of
    [retroarch-joypad-autoconfig](https://github.com/libretro/retroarch-joypad-autoconfig)
    at 3579625f (MIT, `upstream/COPYING`), 217 files, spaces and brackets in the file names
    turned into `_` for the build. Covers 8BitDo, Logitech, generic USB SNES pads and more.
- `retroarch.cfg`: a partial config, RetroArch fills in the rest. Written to
  `/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg` (the one the
  RetroArch app and Daijishou's RetroArch players load) **only if there is no
  retroarch.cfg yet**, so an existing or user-changed config is never touched. That also
  means installs that already ran RetroArch keep their settings after an update: to get
  these defaults, delete that retroarch.cfg (or clear RetroArch's data) and reboot.

CPU and GPU cost: checked against RetroArch 1.22.2's source, dArkOS and ROCKNIX
(RK3326) for settings that cost CPU or GPU on the Cortex-A35 / Mali-G31 for little gain. Only one needed a
change; the rest are already cheap or cost nothing while a game runs (details and
estimates in the ra-cpu report, O2-106):

| Setting | Value | Why |
|---|---|---|
| `input_overlay_enable` | off (seeded) | RetroArch's Android default is on unless the model name is a known console. No touch screen here; after one start from the RetroArch icon the touch gamepad would load and draw over every game |
| `audio_resampler`, `_quality` | sinc, lower (default) | about 0.3-0.5 % of one core (estimate). The NEON code is not built into the arm64 APK, and "lowest" (4 taps) is no faster than "lower" there. Nearest would save about 0.3 % and alias audibly. dArkOS uses normal (twice the cost), ROCKNIX lower |
| `rewind_enable`, `run_ahead_enabled` | off (default) | a save state every frame, too heavy for most cores on the A35 |
| `video_shared_context` | off (default) | only for GL cores; on would switch EGL contexts twice a frame |
| `menu_enable_widgets` | on (default) | a few GL calls per frame when nothing is shown |
| `savestate_file_compression` | on (default) | compresses on RetroArch's task thread, writes a smaller file to the SD card |
| `log_verbosity`, `log_to_file` | off (default) | nothing is logged; turn on only for tests |
| `content_runtime_log`, `history_list_enable` | on (default) | one small file each when a game starts or ends |

What the config sets:

| Setting | Value | Why |
|---|---|---|
| saves and states | next to the game (`savefiles_in_content_dir`, `savestates_in_content_dir`, sorting off) | same place as ArkOS: `EASYROMS/gba/game.srm`, `game.state1` |
| hotkeys | hold Select + Start quit (twice), R1/L1 save/load state, Up/Down state slot, X menu, A pause, B reset, Y screenshot, L3 fast forward (R2, or Right, on a console without L3, see below); L1+R1+Start+Select menu | ArkOS's mapping |
| `autosave_interval` | 10 s (also RetroArch's Android default) | FN (Home) only pauses RetroArch; if Android later closes it in the background, at most 10 s of in-game saving is lost |
| `menu_driver` | `rgui` | ArkOS's menu; readable at 640x480, needs no assets (Android's default Material UI is for touch) |
| `video_threaded` | on | as ArkOS, keeps the Cortex-A35 at full speed |
| `audio_latency` | 64 ms (Android default 128) | less sound lag, see Latency below |

Left at RetroArch's Android defaults on purpose (they already match ArkOS or the
screen): bilinear filter off, integer scale off, aspect ratio core provided, save
state auto save/load off.

### How games look

The fresh-install retroarch.cfg also turns shaders on and sets RetroArch's config and
shader folders to `Android/data/com.retroarch/files/config` and `.../files/shaders`.
Daijishou's players pass no folders to RetroArch, so without this RetroArch would use
one config folder when started from Daijishou and another when started from its own
icon. The service then copies `seed/retroarch/look/` there (`seed_look`):

| File | What it does |
|---|---|
| `config/global.glslp` | sharp bilinear (`shaders/sharp-bilinear-simple.glsl`) for every core: each game pixel is scaled by the largest whole number that fits, only the rest is smoothed. Uneven factors (GBA 2.67x, SNES 2.14x at 480 lines, 720x720 clones) then give even pixels with next to no blur. At a whole-number scale it looks exactly like no shader |
| `config/Gambatte/Gambatte.cfg` | core override for Game Boy / Color: integer scaling in "smart" mode, so 3x (480x432) on 640x480 and 4x (640x576) on 720x720, one frame of run-ahead (see Latency), and no "override loaded" message |
| `config/Nestopia/Nestopia.cfg`, `config/FCEUmm/FCEUmm.cfg` | core overrides for NES / FDS: one frame of run-ahead (see Latency), no "override loaded" message |
| `shaders/*.glslp` | presets to pick in Quick Menu > Shaders > Load: sharp-bilinear-simple, zfast-crt (scanlines), zfast-lcd (LCD grid, best at whole-number scales, e.g. Game Boy) |

The F-Droid RetroArch APK has no shaders at all and the online updater needs Wi-Fi, so
the three shader files ship here: `glsl-shaders` (libretro) at f8e23ff;
sharp-bilinear-simple (rsn8887, after Themaister) public domain, zfast_crt and
zfast_lcd (Greg Hogan, SoltanGris42) GPL-2.0+. All three are one texture read per
pixel, made for a Raspberry Pi 3 at 1080p; at 640x480 the Mali-G31 does a seventh of
that work. They compile as GLSL ES 1.00 (what RetroArch's `gl` driver uses here).

Only installs whose retroarch.cfg the service wrote get these files (`.gameconsole/look`
marks them). An existing retroarch.cfg means the user's own picture settings stay as
they are; to switch, delete retroarch.cfg and reboot. A newer ROM updates a file only
while it is unchanged (same manifest as the cores); a file the user changed or
removed (for example with Quick Menu > Shaders > Remove Global Preset) stays that way.
Game or folder presets and overrides the user saves win over these.

Saves only carry over when both sides run a core with the same save format. The core is
picked by Daijishou's player, not by retroarch.cfg. Same as ArkOS: gambatte, mGBA,
PCSX ReARMed, Beetle PCE Fast/NeoPop/Cygne, Genesis Plus GX, PicoDrive. Use mGBA rather
than gpSP for GBA, and Genesis Plus GX (non-commercial tier) rather than Gearsystem for
Master System/Game Gear. Save states only load in the same core.

### Controllers and players

Step 7 (`retroarch_players`) keeps the built-in pad player 1 and puts external pads on
player 2 and up, in the order their first button is pressed. Without it RetroArch gives
player 1 to whichever pad sends input first, which on Android can be an external pad.
It uses RetroArch's device reservation (Settings > Input > Port 1 Controls > Device
Reservation Type in RetroArch): `input_player1_device_reservation_type = "2"`
(reserved) and `input_player1_reserved_device = "484b:1100"`, so player 1 stays free for
the built-in pad even while an external pad is used first.

Settings > Controllers can turn it round (`persist.andr36oid.ra_player1=external`): then
player 2 is reserved for the built-in pad and the first external pad is player 1, for
someone who plays alone on a bigger pad. The `gameconsole-ra-players` service writes the
change once RetroArch is closed (RetroArch writes its old values back when it quits).

Also for every controller, not only player 1's:
- `all_users_control_menu = "true"`: every player can use the menu.
- `input_quit_gamepad_combo = "4"`: Start+Select (twice) quits.
- L1+R1+Start+Select opens the menu (already in the seeded config), and so does a pad's
  Guide / PS / Capture button (the `andr36oid_*.cfg` profiles bind it).

The Select hotkeys (save/load state, fast forward...) only work on player 1's pad:
RetroArch 1.22 reads hotkeys from player 1 only (`input_keys_pressed`, "Hotkeys are only
relevant for the first user"). Whoever wants them on the big pad makes it player 1.

On a console without L3 (Settings.Global `andr36oid_missing_buttons`, written by Quick
start and Settings > Controls, or before that the `andr36oid,missing-buttons` hint in the
joypad's device tree node) the fast forward hotkey moves from Select + L3 to Select + R2,
or Select + Right when R2 is missing too (`ra_hotkeys`). It runs at every boot, so it
covers the freshly seeded retroarch.cfg and a later change of the buttons check (applied
at the next boot), and it moves back to L3 when the console turns out to have it.

These settings are changed at boot only while they are unset, RetroArch's default or
what the service wrote last time (`/data/misc/gameconsole/retroarch-managed/<key>`), so
changes made in RetroArch stay; only a change in Settings > Controllers overrides the
two player settings.

### Settings for single games (game-compat)

A few popular games need their own setting on this chip. Most don't: the cores already
fix many games by themselves, by disc ID or ROM checksum (PCSX ReARMed's
`libpcsxcore/database.c`, Mupen64Plus-Next's `mupen64plus.ini` and GLideN64's
custom ini, PPSSPP's built-in `compat.ini`). Only what those don't do goes here, with
its source; the list and reasons are in reports/overnight2/game-compat.md.

- RetroArch: `seed/retroarch/game-options.txt` lists games by ROM file name (No-Intro /
  Redump names) under their core. On a fresh install (the retroarch.cfg we wrote),
  `seed_game_options` writes one `config/<core>/<ROM name>.opt` per name. RetroArch uses
  that file instead of the core's options for that game only (Quick Menu > Core Options
  shows it as game options). Options missing from it would get the core's own defaults,
  so each file starts with our defaults for that core from
  `retroarch-core-options.cfg`, then the game's lines. A file the user changed or
  deleted (Manage Core Options > Delete Game Options) stays so; same manifest as the
  look files. A ROM with another file name simply gets the normal settings.
- PPSSPP: `seed/ppsspp/compat.ini` goes to `PSP/SYSTEM/compat.ini` with the other two
  files. PPSSPP reads it after its own compat.ini, by disc ID, so file names don't
  matter. It only holds compat flags: God of War at 30 fps, and GPU readbacks back
  on for PPSSPP's RequireBlockTransfer games (`ForceEnableGPUReadback`). Per-game `<ID>_ppsspp.ini` files are not
  seeded on purpose: loading one also replaces the whole button map with its own, so
  our hotkeys would be lost in that game unless the file carried a copy.
- `seed/game-tips.txt` becomes "Game tips.txt" on a fresh EASYROMS, next to the README:
  which heavy games need a setting and which are too slow. English, Spanish and
  Brazilian Portuguese stacked in one file (UTF-8 with BOM, 76 columns): it is written
  on the first start, before the setup wizard, so the language isn't known yet.

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
- a card set up with `.noroms` on BOOT has no EASYROMS partition at all (init publishes
  an empty `sys.andr36oid.easyroms.part`; the first start gives userdata the whole card). Then
  the folder is `bios` on the internal storage, `/storage/emulated/0/bios` (made with the
  owner and mode of `/data/media/0`), with the same rules. Moving between a card with
  and one without EASYROMS updates the path we wrote.

### Latency

RetroArch 1.22.2 for Android (F-Droid) is built with OpenSL ES as its only sound
driver (no AAudio, no tinyalsa). The audio HAL has no MMAP path and no fast mixer, so
AAudio in other apps goes through the same AudioTrack path anyway. The seeded
`audio_latency` of 64 ms makes RetroArch's own queue 3 blocks of 1024 frames at 48 kHz
(RetroArch sets `audio_block_frames` itself from Android's mixer period). The layers
below it (AudioTrack, mixer, driver) keep at least 190 ms more and cover a slow frame.

Left at RetroArch's defaults on purpose (the same values as ArkOS, dArkOS and ROCKNIX
on this chip):

| Setting | Default | Why not changed |
|---|---|---|
| `video_hard_sync` | off | GLES 3 has the fences, but waiting for the Mali GPU after each frame costs speed, and Android's compositor keeps its own buffers after the swap anyway |
| `video_frame_delay`, `video_frame_delay_auto` | 0, off | with threaded video the main loop runs at the pace of the sound, not the screen, so a delay after the frame is not tied to the refresh and gains nothing; the A35 has no time to spare for it in most cores |
| `video_max_swapchain_images` | 3 | only read by the Vulkan, KMS and D3D drivers, not by the Android GL context |
| `input_poll_type_behavior` | 2 (late) | reads the pad when the core asks, the lowest-lag mode already |
| `run_ahead_enabled`, `preemptive_frames_enable` | off | a second run of the core each frame is too heavy for most cores on the A35; on for NES and Game Boy only, below |
| `video_threaded` | on (seeded) | costs up to one frame of lag, but SNES, GBA and PS1 need it for full speed |

**Run-ahead for NES and Game Boy.** Many games react to a button one or two frames
late on the real console too. Run-ahead hides one of those frames. The overrides for
Nestopia, FCEUmm and Gambatte (in `seed/retroarch/look/config/`, seeded by
`seed_look` with the same rules as the picture settings) turn on "Preemptive Frames"
with 1 frame, not classic run-ahead:

- classic run-ahead (single instance) runs the core twice every frame and saves and
  loads a state each time: always about twice the CPU. A second instance costs even a
  bit more;
- preemptive frames saves a state every frame (tens of KB for these cores, well under
  1 ms) and only goes back and runs the frame again when a
  button changed. That is a few times a second, so on average it costs 10-20 % more
  CPU, and a frame with a button change costs about twice as much;
- a NES or Game Boy frame takes an estimated 3-7 ms on the A35, so even the doubled
  frame fits in 16.7 ms. SNES (Snes9x 2010, 8-12 ms and more with Super FX), Genesis,
  GBA and PS1 don't fit and stay off. Snes9x 2010 also uses frameskip "auto", which
  doesn't mix well with going back a frame.

To turn it off: Quick Menu > Overrides > Remove Core Overrides (the file then stays
removed; for Game Boy that also drops the 3x picture), or set Settings > Latency >
Run-Ahead to off and save the core overrides. A setting changed only in the menu is
lost when the game is closed while an override is active (RetroArch reloads
retroarch.cfg before it saves, `config_unload_override`). That already holds for
Game Boy games because of the 3x override; with these files it holds for NES games too.

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

PPSSPP's memory stick folder and settings: see [PPSSPP defaults](#ppsspp-defaults).
Step 8 (`ppsspp_controls`) fixes its buttons afterwards: PPSSPP's Android default maps
Cross to BUTTON_A, the bottom button on Xbox/PlayStation pads but the right one on the
built-in pad (and Switch pads), so Cross/Circle/Square/Triangle sat on right/bottom/top/
left. On the first boot after PPSSPP wrote `PSP/SYSTEM/controls.ini` (it does on quit),
and only if the four face buttons are still PPSSPP's pad default (`10-96/97/99/100`), they
become Cross = B, Circle = A, Square = Y, Triangle = X. The memory stick folder comes from
`files/memstick_dir.txt` (a path or a file-picker content URI, internal storage or SD
card). Once per controls.ini (`/data/misc/gameconsole/ppsspp-controls`), so a later
mapping of the user's stays. PPSSPP treats every connected controller as the one player,
so on an Xbox or PlayStation pad the face buttons are then turned by one position; remap
in PPSSPP (Settings > Controls > Control mapping) if that pad is the main one.

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
- post-fs-data (`gameconsole-setup early`, run with `exec_start` before
  system_server starts): Daijishou reads games only through the storage access
  framework, i.e. folders the user picked in the file picker, which Android remembers as
  persisted URI grants in `/data/system/urigrants.xml`. We add the same entry the picker
  would (`content://com.android.externalstorage.documents/tree/<UUID>%3A<folder>`, prefix,
  read+write) for each platform folder. system_server only reads that file when it
  starts, hence post-fs-data; the EASYROMS UUID comes from the exFAT boot sector's
  serial, formatted as vold names `/storage/<UUID>`. Existing grants are kept,
  nothing is doubled.
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
Daijishou whose library has a system or a game, never on a `.noroms` card (no EASYROMS; games are on the
internal storage there). A different Daijishou versionCode skips it with a log line.

Daijishou can start before EASYROMS is mounted on a first start (the card check takes a
while) and then makes an empty library. That one is replaced like a missing one, with
Daijishou force-stopped before and after, and its own settings file gets the "look for
games" question added. Cards from older images that had marked such a library as done
are filled on their next start.

Which files count as games for that: only files with an ending one of the platform's
players accepts (their `accepted_filename_regex` in `daijishou-seed.txt`). So the saves,
states and NVRAM files cores write next to the games (`.srm`, `.state`, `.nv`, `.hi`,
EasyRPG `.lsd`, PrBoom `.dsg`) don't count; nor do hidden files and DOSBox Pure's
`.pure.zip` saves.

After a Daijishou update: run `tests/run-daijishou-tests.sh`. If the schema check
fails, take the new `createAllTables` statements, identity hash and version from the new
APK (jadx, `DaijishouDatabase_Impl`) into `schema.sql`, set `DAIJISHOU_VERSION_CODE` in
the generator and rerun it. Daijishou migrates an older library by itself, so an old
schema is only a problem for consoles that seed after the update.

Pictures: each platform gets the picture Daijishou's own import would download, from
the COLORFUL pack (by viking, edited by iShade, indexed by TapiocaFox), as its wallpaper
and thumbnail. `scripts/fetch-daijishou-art.py` fetches the ones our platforms use into
`daijishou/art/` (shrunk to the screen, about 660 KB; credits in `daijishou/art/README`),
the generator adds an `art` line per platform, and the seed copies them to
`files/platform_wallpapers/<id>.png` and `files/platform_thumbnails/<id>.png`, where
Daijishou keeps the ones it downloads. After changing `platforms.txt`, run the fetch
script before the generator.

Widgets: Daijishou's home page starts with four app shortcuts, RetroArch, Files, VLC and
Settings, in that order (`djWidgets` in `setup/internal/setup/daijishou.go`; an app that isn't installed is left
out). The service writes them into a new `Widget.db`, built from
`daijishou/widget-schema.sql` (Daijishou's own widget schema, checked against the APK by
the host test). A `Widget.db` that already has widgets, or can't be read, is left alone.

Tests: `ANDROID_BUILD_TOP=... emulators/tests/run-daijishou-tests.sh` (generator up to date,
schema vs. the APK's dex, the setup's Daijishou steps in a fake /data with a real sqlite3,
checked by `tests/checklib.py`).

## The setup service

`gameconsole-setup` (`setup/`, Go) does all of the above on the console. It was the shell
script `seed/gameconsole-emu.sh`; it keeps the same marker files, so a console set up by
the script carries on where it was. init starts it from `seed/gameconsole-emu.rc`:
`early` at post-fs-data, `main` at boot_completed, `apps` when the setup's app page is
left, `players` when Settings > Controllers changes player 1.

Only one of them works at a time: each start leaves a request in `/dev/gameconsole-setup/`
and takes an flock on `/dev/gameconsole-setup.lock`; one that doesn't get it exits, and the
worker runs its request between its own steps. The kernel drops the lock when its holder
dies, so there are no stale locks and no time limits, on a slow card or a fast one.

While the first setup runs it tells the setup screen how far it is, in system properties:
`sys.andr36oid.setup_stage`, `setup_app`, `setup_pct`, `setup_eta` (seconds, -1 while
unknown) and `setup_stats` (card read speed, MB written, apps, time, free space).

Android's Go is too old for it, so the binary is built on the host and committed:
`setup/build.sh` runs the tests and builds `setup/prebuilt/gameconsole-setup` (static,
arm64, reproducible); `setup/build.sh --check` (run by build-image.sh) fails when the
sources changed since. Tests: `cd setup && go test ./...`.

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

## Cheats

RetroArch's cheat files come from its online updater (Online Updater > Update Cheats),
which needs Wi-Fi. So `libretro/cheats.tar.gz` ships the `.cht` files of
[libretro-database](https://github.com/libretro/libretro-database) (CC BY-SA 4.0,
`libretro/NOTICE-cheats.txt`) for the 34 systems we have a working core for: 20,360
files, 54 MB, 9.3 MB packed. The service, after the BIOS folder:

- sets `cheat_database_path = "/storage/emulated/0/Android/data/com.retroarch/files/cheats"`
  while it is unset, `default`, or RetroArch's own default after an icon start
  (`/storage/emulated/0/RetroArch/cheats`) with no cheat files in that folder. A folder the
  user picked is kept, and then nothing is unpacked.
- unpacks the archive there once per archive (stamp
  `/data/user/0/com.retroarch/.gameconsole/cheats.sha1`), if /data has 400 MB free. A new
  archive unpacks over the old files; files the user added or saved stay. About 130 MB
  on f2fs (20k small files).

In a game: Quick Menu > Cheats > Load Cheat File (Replace) > system folder > game.
RetroArch saves what the user turned on per game in `cheats/<core name>/<game>.cht` and
loads it at the next start; "Auto-Apply Cheats During Game Load" makes it apply too.

To update the cheats, change `COMMIT` (and the system list, if needed) in
`scripts/build-cheats.sh` and run it. It writes the archive and its NOTICE; the same
commit always gives the same bytes.

## Source

The APKs are unmodified upstream releases and
their NOTICE files point at the upstream repository at the release tag; the cores'
NOTICE lists the repository and commit each one is built from. The NOTICE files show
up in Settings > About > Legal information.

One exception: yabasanshiro is built from our fork, so before a release that ships it,
github.com/andr36oid/yabasanshiro-pirry has to be public with the commit in `cores.lock`
(branch `andr36oid`). `build-cores.sh` also fetches it from there.

## RetroArch menu languages

RetroArch's menu (RGUI) needs font files for some languages. Without them it
shows English and says "Missing fonts for selected language". The F-Droid APK
doesn't have them, and the online updater needs Wi-Fi. So the ROM ships the 7
RGUI font files from libretro retroarch-assets (`seed/retroarch/rgui-font/`,
247 KB, licences in its `NOTICE`, shown in Settings > About > Legal
information). The `gameconsole-emu` service copies them into RetroArch's own
assets folder, `/data/user/0/com.retroarch/assets/rgui/font`, on every boot
(tracked like the cores: a file the user replaced is kept).

| System language | Before | After | Font files it loads |
|---|---|---|---|
| English, Español, Português (BR), Deutsch, Français | own language | same | none (built in) |
| Bahasa Indonesia | English (Android says `in`, RetroArch knows only `id`) | Indonesian where RetroArch has it (about a quarter), English for the rest | none |
| Русский | English | Russian | `bitmap10x10_eng.bin`, `bitmap10x10_rus.bin` |
| 中文 (简体) | English | Chinese | `bitmap10x10_eng/chn/jpn/kor.bin` (RetroArch wants all four) |
| Polski, Türkçe | English | Polish, Turkish | `bitmap6x10_eng.bin`, `bitmap6x10_lse.bin` |

Two settings in retroarch.cfg go with it, set by the service, not by the seeded file:

- `assets_directory = "/data/user/0/com.retroarch/assets"`, only while it is unset or
  `default`. Started from Daijishou, RetroArch gets no folders and would not find the
  fonts. This is the folder it uses itself when opened from its icon. A folder the user
  picked is kept.
- `user_language`, once per RetroArch install, only for the languages in the table that
  need it (ru, zh, pl, tr, in), and only while it is unset or English (`0`). RetroArch
  takes the system language on its first start by itself, but saves English for good
  when the fonts were missing, and never picks Indonesian. The seeded file can't do it:
  it is written on the first boot, before the setup wizard asks for the language.
  English picked in RetroArch's menu after that stays.

Material UI, Ozone and XMB (not used here) take TrueType fonts instead: Ozone and XMB
need assets from the online updater anyway, and Material UI's own `glui/font.ttf`
(M+ 1p, in the APK) already covers Cyrillic, Polish and Turkish. Only Material UI and
Ozone in Chinese would need `pkg/chinese-fallback-font.ttf`; Android's Noto CJK can't
stand in, because RetroArch's font renderer on Android (stb_truetype 1.06) can't read
its CFF outlines.
