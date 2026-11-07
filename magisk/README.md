# Magisk

The console boots U-Boot → `boot.ini` → `booti` with a kernel, a ramdisk and a
DTB from the FAT BOOT partition. There is no `boot.img`, so the Magisk app has
nothing to patch. Instead the device `mkimg.sh` puts Magisk into the ramdisk
while it builds the image, with `inject_magisk.sh`:

* `/init` becomes `magiskinit`, the payload goes to `overlay.d/sbin/*.xz` and
  the config to `.backup/.magisk`, the same steps as Magisk's own
  `assets/boot_patch.sh`;
* `PREINITDEVICE=metadata`, so Magisk has its storage from the first boot;
* the ramdisk lives on the BOOT partition, so root stays when a GSI is
  flashed to the system partition.

`mkimg.sh` also puts the unpatched ramdisk on the BOOT partition as
`ramdisk-stock.uimg`. If Magisk ever keeps the console from booting, rename it
to `ramdisk.uimg` on a PC.

`Magisk.apk` is the unmodified Magisk v31.1 release
(https://github.com/topjohnwu/Magisk/releases/tag/v31.1, sha256
`89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7`), GPLv3.
To update, replace it with a newer release and bump the version_code in
`Magisk.yml`. The same file is installed as a normal app on first boot, by the
emulator preinstaller (a system app would get no native libs extracted).

Magisk is PINNED: only a new andr36oid image updates it. The preinstaller
(`emulators/setup` apps.go) reverts any installed Magisk that drifts from this
baked version (reinstalling this APK, downgrading if needed) and turns off the
app's self-update check, and `internal/magisk` pins root to the baked ramdisk
rather than rebuilding it to match a newer app. A root repatch bricks these
SD-card uImage consoles, so an app update must never drive one. Bumping the APK
+ version_code here is the only supported way to move Magisk forward.
`magiskboot` runs from the APK's x86_64 build on the build machine (arm64
through qemu-aarch64 on other hosts). Without the APK the image builds with the
stock ramdisk and no root.
