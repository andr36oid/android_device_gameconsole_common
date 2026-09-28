# Extra FOSS apps

Free and open source apps that are useful on the console but are not
emulators for RetroArch/Daijishou's core set: game streaming, a video player,
stand-alone emulators. They are in every build (release too).

They are installed the same way as RetroArch and PPSSPP (see
`../emulators/README.md`): the APK sits unmodified in
`/system/etc/gameconsole/preinstall/`, and the `gameconsole-emu` service
installs it on boot as a normal app with `pm install -g`. So the user can
uninstall it, a removed app stays removed, and a newer ROM upgrades an older
install. The list files are called `z-foss-*.list` so they sort after
`preinstall.list`: RetroArch and PPSSPP get installed first.

Every app has its own folder, so each one can be added or dropped alone:

    <App>/app.lock            what to download, pinned by sha256
    <App>/<App>.apk           the upstream APK, byte for byte (git LFS)
    <App>/NOTICE.txt          license, download and source link (Settings > About > Legal)
    <App>/z-foss-<app>.list   package, versionCode, file for the installer
    <App>/Android.mk          the two ETC modules
    <App>/product.mk          adds them to PRODUCT_PACKAGES

`foss-apps.mk` pulls in every `*/product.mk`. To drop an app, delete its
folder. To leave all of them out, set `GAMECONSOLE_FOSS_APPS := false` before
`foss-apps.mk` is inherited.

## Updating or adding an app

Edit `app.lock` (version, versionCode, URL, sha256), then run

    foss-apps/fetch.sh foss-apps/<App>

It downloads the APK, checks the sha256 and writes NOTICE.txt and the list
file again. Commit them together. Take the sha256 from the upstream's own
checksum (F-Droid index, GitHub release digest, `.sha256` file) and check the
APK with `aapt2 dump badging` (package, versionCode, minSdk <= 30, has
arm64-v8a).

Apps from F-Droid keep F-Droid's signature, so F-Droid can update them. Apps
from their own project site keep the project's signature; F-Droid can't
update those, the project's own releases can.

Nothing needs uploading with a release: the APKs are unmodified upstream
builds and NOTICE.txt points at the source for that version.
