# Extra FOSS apps

Free and open source apps that are useful on the console but are not
emulators for RetroArch/Daijishou's core set: game streaming, a video player,
stand-alone emulators. They are in every build (release too).

## AUTOAPK

They are installed the same way as RetroArch and PPSSPP (see
`../emulators/README.md`): the APK sits unmodified (except Music, below) on the
AUTOAPK partition, and the `gameconsole-emu` service installs it as a normal app
with `pm install -g`. So the user can uninstall it, and a removed app stays
removed.

AUTOAPK is a 2 GiB FAT32 partition in the image right after system, made by
`mkimg.sh` (device/gameconsole/r36s) from `$OUT/autoapk`, where these modules
install. People can copy their own APKs onto it from a PC before the first start.
The first start (`../firstresize`) moves it behind userdata as exFAT and mounts it
read-only at `/mnt/andr36oid-apps`. Once the installer is done with it (every app
installed, left out or failed for good, and the first-start settings written), it
leaves `/metadata/andr36oid/apps-done`, and the next start gives the space to
userdata.

Each APK has a `<Name>.yml` next to it:

    package: org.example.app       the package, for the installer
    version_code: 123
    checked: false                 unticked on the setup's app page (default: ticked)
    required: true                 always installed, not on the page (RetroArch, Magisk)
    needs: org.example.other       ticking this one ticks that one too (plugins)
    author: github:someone         credit line "by someone", with GitHub's icon for
                                   a github: account; plain text otherwise (VideoLAN)
    description:                   the page's second line, per language; English is
      en: "What it does"           used when the user's language is missing
      de: "Was es macht"
    warning:                       shown in amber under it, per language
      en: "Needs mouse mode"

The setup wizard has a page to pick which of these get installed
(`../QuickStart`, SetupAppsActivity); the installs are offline, from the card.
APKs without a `.yml` (someone's own) are shown unticked. Until the page was left
with Next the service keeps the apps back, except the required ones. The unticked
ones are treated like apps the user uninstalled: never installed later.

Every app has its own folder, so each one can be added or dropped alone:

    <App>/app.lock            what to download, pinned by sha256
    <App>/<App>.apk           the upstream APK, byte for byte (git LFS)
    <App>/NOTICE.txt          license, download and source link (Settings > About > Legal)
    <App>/NOTICE.extra.txt    optional additional attribution or asset licenses
    <App>/<App>.yml           package, versionCode and the setup page's texts
    <App>/Android.mk          the ETC modules
    <App>/product.mk          adds them to PRODUCT_PACKAGES

`foss-apps.mk` pulls in every `*/product.mk`. To drop an app, delete its
folder. To leave all of them out, set `GAMECONSOLE_FOSS_APPS := false` before
`foss-apps.mk` is inherited.

## Updating or adding an app

Edit `app.lock` (version, versionCode, URL, sha256), then run

    foss-apps/fetch.sh foss-apps/<App>

It downloads the APK, checks the sha256, writes NOTICE.txt again and the package
and version_code in `<App>.yml`. Commit them together. Take the sha256 from the upstream's own
checksum (F-Droid index, GitHub release digest, `.sha256` file) and check the
APK with `aapt2 dump badging` (package, versionCode, minSdk <= 30, has
arm64-v8a or armeabi-v7a; the console supports both).

Apps from F-Droid keep F-Droid's signature, so F-Droid can update them. Apps
from their own project site keep the project's signature; F-Droid can't
update those, the project's own releases can.

Two exceptions. D-PAD Player is the console's Music app. `DPadPlayer/rename.py`
renames it at build time ("DPAD Music" becomes "Music") and the build signs
it with the ROM's key (`DPadPlayer/Android.mk`), so only our builds can update
it. Its developer, jbriones95, is credited in the Credits app, and its
NOTICE.txt says what was changed (`modified=` in `app.lock`). It starts with
the Retro accent (`DPadPlayer/firstrun`).

The other one: TetrisLite comes from andr36oid's fork
(github.com/andr36oid/TetrisLite, branch `andr36oid`), with a start screen that
fits the console's screen and gamepad buttons in the game. Its APK is the fork's
unsigned release build, signed with the ROM's key by `TetrisLite/Android.mk`;
`TetrisLite/app.lock` says how to build it (fetch.sh can't fetch it).

Nothing needs uploading with a release: the APKs are upstream builds (Music
renamed by a script in this repo, TetrisLite built from our public fork) and NOTICE.txt points at the source for
that version.

## First-start settings (button maps, hotkeys)

Some apps get settings for the built-in pad before they run for the first
time: a button map and, where the app allows it, the same Select hotkeys as
RetroArch and PPSSPP (Select + Start quit, Select + R1/L1 save/load state, ...).
They are only written while the app has never run, so a user's own settings
are never touched. Clearing the app's data and rebooting writes them again.

    <App>/firstrun/<name>.list     what goes where (read by the gameconsole-emu service)
    <App>/firstrun/<name>/<file>   the files themselves

The list, one instruction per line:

    package <package>
    unless data|ext <path>   skip the list while this exists (the app has run)
    data <file> <path>       copy firstrun/<name>/<file> to <path> in /data/user/0/<package>
    ext <file> <path>        the same into Android/data/<package>/files on the internal storage
    appop <op> <mode>        e.g. REQUEST_INSTALL_PACKAGES allow: a permission the app would
                             ask for on its first start; once per app and op, so one the
                             user takes away stays away
    extzip <zip> <dir>       unpack firstrun/<name>/<zip> into <dir> in Android/data/<package>/files,
                             once per app and zip (also after the app has run), keeping
                             folders that are there; Luanti's games come this way

They land in AUTOAPK's `.andr36oid/firstrun/`. Only missing files are
written, in list order; a list stops at its first failure. So the file the app
itself uses to tell whether it ran before (usually its shared_prefs) goes last.
`<path>` may contain spaces. Files get the app's owner (group ext_data_rw on
the internal storage) and SELinux label, like the RetroArch and PPSSPP seeding
in `../emulators`.

Flycast and Mupen64Plus AE also get a few speed settings this way (Flycast's
`emu.cfg`, a Mupen emulation profile made the default). Only values that
differ from the app's own defaults are written. The comments in those files
say what each value does. Everything else stays at the app's default; those
were checked against the RK3326 and kept on purpose.
