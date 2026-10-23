# Standalone Flipper app hook

The emulator now lives at `/andr36oid/work/flipper-app`, an independent local
Git repository. This directory only packages its R36S APK. The old `FlipperMode`
Soong library is superseded and removed. No ROM module depends on it.

Build with JDK 17 or 21, Gradle wrapper 8.14.3, Android SDK platform 35 and build
tools 35.0.0. The project pins Android Gradle plugin 8.13.0 and Kotlin 2.2.20.
From the standalone project:

```sh
ANDROID_HOME=/opt/android-sdk ./gradlew --offline :app:assembleR36sRelease
cp app/build/outputs/apk/r36s/release/app-r36s-release-unsigned.apk \
  /andr36oid/work/wt/flipper/FlipperApp/prebuilt/FlipperEmulator-r36s.apk
```

Omit `--offline` on a separately provisioned build host if dependencies have not
been cached. No firmware is bundled. `prebuilt/*.apk` is ignored: the binary is
a local build artifact, to be supplied by the maintainer when packaging the ROM.
The conditional module and product include both omit it when absent. A supplied
release APK is signed with the ROM platform certificate by `BUILD_PREBUILT`.
It does not request a shared UID, root, or privileged Android APIs.

The R36S manifest contributes a Connected devices Settings tile using the same
`IA_SETTINGS` metadata as the existing apps. `UsbMode` has a Flipper entry in its
USB flow, disabled if the R36S package is absent. The app opens the existing
`org.andr36oid.usbmode.UsbModeActivity` for role selection and its existing
charger/Wi-Fi checks. Installing the generic flavor adds no Settings tile.

## USB boundary

The only gadget owner is still `UsbMode/andr36oid-usb-drive.sh`, launched by
`init.rk30board.rc` using `sys.andr36oid.usbdrive.*`. The controller-mode rounds
extend this same helper (`night/controller-mode`), with a journal, app/helper
leases, a configfs gate and cleanup. They are not present in this branch. Do not
copy that helper into the app or introduce a second gadget service.

**Firmware USB bridging is not implemented.** M1 has no STM32 USB/PMA model;
neither this branch's storage helper nor the controller keyboard channel offers
arbitrary guest endpoint transfers. A Settings link is not a firmware bridge.
The app explicitly reports this and never sends an unsupported helper target.

TODO: implement the guest USB model, then extend the shared owner with an
authenticated endpoint transport and capability handshake. Keep descriptor and
endpoint ownership in the ROM helper, including reset/setup/IN/OUT/stall events,
backpressure, app death and detach/restoration. Expose a narrowly scoped API to
the independently signed app, or document a platform-signature permission.
Update the R36S adapter only after that ROM contract exists. CDC/HID enumeration,
transfers, role recovery and handheld behavior still need hardware validation.
