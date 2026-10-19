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

`Magisk.apk` is the unmodified Magisk v31.0 release
(https://github.com/topjohnwu/Magisk/releases/tag/v31.0, sha256
`2c8a488b9a5293e578e95ae4f07e3c57aba4feec4a52ca4dd852a2692d6dd4e8`), GPLv3.
To update, replace it with a newer release and bump the version_code in
`Magisk.yml`. The same file is installed as a normal app on first boot, by the
emulator preinstaller (a system app would get no native libs extracted).
`magiskboot` runs from the APK's x86_64 build on the build machine (arm64
through qemu-aarch64 on other hosts). Without the APK the image builds with the
stock ramdisk and no root.
