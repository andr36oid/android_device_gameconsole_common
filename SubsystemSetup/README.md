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
- **Settings**: launch EmulationStation or PortMaster; "boot into the subsystem
  by default" toggle; "default boot target" (ES or PM, default ES); and an
  explainer that holding START at boot always boots the *other* OS.
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

Launch sequence: set `rootfs`, `handoff`, `port`, then `request=run`. The activity
persists a `launch_pending` flag before requesting a full handoff so it can resume
its state after Android is restored.

Boot selection is handled by the broker's `boot-decision` step (fired on
`sys.boot_completed`), which reads the two `persist.*` props and the physical
START key (via the `andr36oid-keyheld` helper) to pick which OS boots.

Status reads `sys.andr36oid.subsystem.image`: `1` means installed, an empty value
means not scanned yet; the page re-scans while it is empty.
