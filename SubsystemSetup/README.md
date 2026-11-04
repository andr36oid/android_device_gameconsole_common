# Subsystem Setup

Platform-signed, system-UID Settings page under Settings > System for the
**andr36oid Subsystem for Linux** - a chroot/overlay that boots a Linux rootfs
(dArkOSen's EmulationStation, PortMaster, or a port) with the display handed off
from Android, then restores Android when it exits. The APK is the front end only:
it never performs the root handoff. A root init service (the broker,
`andr36oid-subsystem.sh`) does that; the APK drives it through system properties.

## Surfaces

- **Wizard** (first run / "set up"): an in-house setup flow styled like the eMMC
  install wizard and QuickStart - explains the subsystem, detects the rootfs
  image (and, when online, offers to download dArkOSen), and finishes at a launch
  screen. Gamepad-navigable (D-pad / A / B).
- **Home**: launch EmulationStation or PortMaster. The in-app Settings page points
  to the boot controls in Android Settings > System.
- **Boot into the subsystem by default**: a separate System settings entry, shown
  only when the broker finds an image. Sets the default OS and Linux app (ES or
  PM, default ES), with a hint explaining how START overrides the selected OS.
- **Advanced mode**: pick any eligible rootfs image; choose "full handoff" vs
  "keep Android running"; in keep-Android mode, a live boot-log console tails the
  broker's `boot.log` as the rootfs init runs.

## Property contract (the broker matches these exactly)

| Property | Meaning |
|---|---|
| `sys.andr36oid.subsystem.port` | `ES` \| `PM` \| port name/path to launch |
| `sys.andr36oid.subsystem.rootfs` | absolute image path, empty = detected dArkOSen |
| `sys.andr36oid.subsystem.handoff` | `1` (default, hand over the screen) \| `0` (keep Android up) |
| `sys.andr36oid.subsystem.request` | `run` \| `scan` \| `stop` (the trigger; broker clears it) |
| `sys.andr36oid.subsystem.session` | `running` \| `ended` (broker output) |
| `sys.andr36oid.subsystem.image` | `1` installed \| `0` not found (broker output) |
| `persist.andr36oid.subsystem.default` | `android` (default) \| `subsystem` |
| `persist.andr36oid.subsystem.default_target` | `ES` (default) \| `PM` |
| `persist.andr36oid.subsystem.booted` | `1` after setup and the first running EmulationStation frontend; enables Sync |

Launch sequence: set `rootfs`, `handoff`, `port`, then `request=run`. The activity
persists a `launch_pending` flag before requesting a full handoff so it can resume
its state after Android is restored.

Boot selection is handled by the broker's `boot-decision` step (fired on
`sys.boot_completed`), which reads the two `persist.*` props and the physical
START key (via the `andr36oid-keyheld` helper) to pick which OS boots.

Status reads `sys.andr36oid.subsystem.image`: `1` means installed, an empty value
means not scanned yet; the page re-scans while it is empty.

Boot receivers update the boot and Sync Settings activity components. The broker
also requests a refresh after image scans and session teardown so visibility can
change without another console restart.

### Optional Daijishou Linux cores

In the subsystem Configuration page, enable **Allow Daijishou dArkOSen cores**, then
select **RetroArch (dArkOSen)** in the platform's Daijishou player settings. Android
RetroArch stays the default. The switch is
`persist.andr36oid.subsystem.daijishou_cores` (unset/0 = off, 1 = on).
The first-start seed always includes eligible alternates; changing the switch needs
no seed regeneration or library rewrite. Existing populated Daijishou libraries
are left alone by the seeder; add a player manually if upgrading an existing library:

```
-n org.andr36oid.subsystemsetup/.RetroArchActivity
-e ROM {file.path}
-e CORE <Linux core basename from emulators/daijishou/darkosen-cores.txt>
```

Daijishou 1.5.0 checks the package against enabled `MAIN`/`LAUNCHER` activities
before starting an explicit player component. Both Linux players now target
`org.andr36oid.subsystemsetup`: `.RetroArchActivity` for cores and
`.LaunchPortActivity` for Ports. The runtime-enabled `.EmulationStationLauncher`
is their single shared package match and the only subsystem launcher icon.
It requires a ready image and `persist.andr36oid.subsystem.es_icon=1`.
Turning the ES entry off also removes both players' preflight package match;
Settings discovery and a headless activity do not satisfy this check.

The redundant Subsystem and PortMaster aliases are removed. SubsystemBridge
remains a headless compatibility endpoint. Every boot's Ports reconciliation
repoints the retained `ports.andr36oid.subsystem` player from the bridge to
SubsystemSetup using the validated database-copy transaction, including when
no game rows change. Port launches forward the parsed port spec into the same
branded Progress screen as ES/RA/RACORE, with the Android shutdown warning and
performance note visible before the broker trigger.

The trigger forwards to SubsystemSetupActivity's busy screen and uses the existing
REQUEST=run mechanism. PORT=`RACORE:<32 lowercase hex id>` refers to a private file
`/data/user/0/org.andr36oid.subsystemsetup/files/ra-<id>.request` containing two UTF-8
lines: Linux core basename and Android ROM path. The backend publishes the file
atomically and the broker consumes it once. This avoids Android's property value
limit for long ROM filenames. The broker looks for `<core>_libretro.so` in
`/home/ark/.config/retroarch/cores` and then `/opt/retroarch/cores` in the supplied
guest image. Android `_libretro_android.so` libraries are never used.

ROM paths are resolved against the actual session storage binds: the selected
EASYROMS volume is `/roms`, other mounted cards are `/roms2`, `/roms3`, etc. Only
when EASYROMS is absent does `/data/media/0` (Android `/storage/emulated/0`) become
`/roms`. Internal storage ROMs are not implicitly mapped onto an existing games
card. Unsupported storage, missing ROMs, or missing cores fail before the display
handoff, with `sys.andr36oid.subsystem.launch_error` and boot-log diagnostics shown
on the health page. Choose the Android player to run a game there instead.
