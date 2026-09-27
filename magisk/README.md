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

`Magisk.apk` is the unmodified Magisk v29.0 release
(https://github.com/topjohnwu/Magisk/releases/tag/v29.0, sha256
`99d40df1a68a05a5e78452a9cd4f2d753434d7622baeeb44ea14ae8238c1a9ca`), GPLv3.
To update, replace it with a newer release and bump the versionCode in
`magisk.list`. The same file is installed as a normal app on first boot, by the
emulator preinstaller (a system app would get no native libs extracted).
`magiskboot` runs from the APK's x86_64 build on the build machine (arm64
through qemu-aarch64 on other hosts). Without the APK the image builds with the
stock ramdisk and no root.
