# Beta apps

Apps for beta testers, in every build whose version doesn't end in `-release`
(beta, dirty, debug). They are not installed by themselves. They sit in
`/system/etc/gameconsole/beta/` with `beta.list`, and the Beta apps app
(`BetaApps/`, also only in those builds) asks the tester which ones they want:

- on start-up, after the setup wizard and after the quick start guide is closed,
  when `beta.list` has an app (or a newer version of one) the tester wasn't asked
  about yet. Apps they were already asked about aren't offered again, so a new
  beta build only asks about its new apps.
- any time from Settings > Beta apps.

The picked apps are installed as normal apps (the tester can uninstall them) with
their own signature. A tester who later flashes a release build over a card keeps
them until they uninstall them.

To add one:

1. Put the APK here.
2. Add a module for it to `Android.mk` (copy one of the `*-beta` blocks, change
   the module name, stem and file) and add the module name to
   `LOCAL_REQUIRED_MODULES` of `andr36oid-beta-apps`.
3. Add its line to `beta.list`: `package versionCode file description`. The
   description is the rest of the line, one short sentence shown under the app's
   name. Name, icon and version are read from the APK. For a plugin, put
   `needs=<package>` before the description: ticking the plugin then ticks the
   app it needs too. List that app above its plugins, apps install in list order.
4. Add it to the table below.

To offer a newer version, replace the APK and raise the versionCode in
`beta.list`. Testers are asked again about that app only.

| APK | Source | sha256 |
|-----|--------|--------|
| TrebleInfo.apk | Treble Info v3.0.2, https://github.com/kevintresuelo/treble/releases/tag/v3.0.2 (GPL-3.0) | 4836066e32bdbc906e4f472d1070265989580f80c983fc3b92133270bf45e2e8 |
| CPU-Z.apk | CPU-Z 1.07 by CPUID (freeware), https://download.cpuid.com/cpu-z/android/cpu-z_1.07.apk, signed by CPUID | b420934c8e838ca95038c8c1b10988e67619e2a85be906a9cf842b8d91040bed |
| UsbDeviceInfo.apk | USB Device Info 3.0.0.81 by alt236 (Apache-2.0), https://f-droid.org/packages/aws.apps.usbDeviceEnumerator/ (F-Droid build, hash matches the F-Droid index) | 07be4a7f425f595479cd2aa0aeb9ecb39276e97b9a81da0b1a8e4fde07381f46 |
| F-Droid.apk | F-Droid 1.23.2 stable (GPL-3.0-or-later), https://f-droid.org/F-Droid.apk (hash matches the F-Droid index) | 985f5181d48bb6bafd54083a048b391271e0ab28385881cc41294fb01a222762 |
| QuickShortcutMaker.apk | QuickShortcutMaker 2.4.0 by sika524 (freeware), from APKMirror (com.sika524.android.quickshortcut_2.4.0-20400), developer certificate SHA-256 F4:10:17:BD:…:2D:33:54 (Tokyo, 2011) | b4d2483bfd54ef65e0ed1745e816b507f9cfba660b5faadbf9803bb7062cff39 |
| Haven.apk | Haven 5.89.14 arm64 "terminal" build (AGPL-3.0), https://github.com/GlassHaven/Haven/releases/tag/v5.89.14 (hash matches the release SHA256SUMS; the full build is 169 MB) | 83063ecfa55cec8c2a93d4f54197cbd7755a97be275ad55470faa2fe5f827fbd |
| Termux.apk | Termux 0.118.3 (GPL-3.0), https://f-droid.org/packages/com.termux/ (F-Droid build, hash matches the F-Droid index; universal APK, 114 MB) | e6265a57eb5ca363808488e3b01955958bed93bc0c8a0d281849b363b11027ec |
| Athena.apk | Athena 2.0.3 by SebaUbuntu (Apache-2.0), https://f-droid.org/packages/dev.sebaubuntu.athena/ (F-Droid build, hash matches the F-Droid index) | 03d7823c598211808f2b81f6dae8ffad62a3c880cea64863fd3cd2084d91d577 |
| TotalCommander.apk | Total Commander 3.62d by Christian Ghisler (freeware), https://www.ghisler.com/tcandroid3.apk, signed by Christian Ghisler | d918ccc0506f6ac3b72fb2cc4dc1eccb3f83eb873077d982a79054d8d65467e4 |
| TotalCommanderLAN.apk | Total Commander LAN plugin 3.60 by Christian Ghisler (freeware), http://totalcommander.ch/aplg/tcandroidlan360.apk, signed by Christian Ghisler | c3091b317e4f4c4a0139a98830cef4269a79fc18bd27dce6c94d10e7bae308e8 |
| TotalCommanderSFTP.apk | Total Commander SFTP plugin 2.94 by Christian Ghisler (freeware), http://totalcommander.ch/aplg/tcandroidsftp294.apk, signed by Christian Ghisler | 10b08c835d4b7d1f65a61a1c027fbe0d87ed5c7a79ea4b54240e43a87d4e2090 |
| FreeOTP.apk | FreeOTP 2.0.6 by Red Hat (Apache-2.0), https://f-droid.org/packages/org.fedorahosted.freeotp (F-Droid build, hash matches the F-Droid index) | 48212d63dd3a3a8a8d6201b8d911f239efb1561f08472057b2c55a65ebb0a94f |
| Obtainium.apk | Obtainium 1.6.17 by ImranR98 (GPL-3.0), https://github.com/ImranR98/Obtainium/releases/tag/v1.6.17 arm64 F-Droid flavour (hash matches the release .sha256 and the F-Droid index) | 8d03b4f02f070d8e64f448ce77ad66daccce9a585e4015b984ee5a7ddee9c29c |
| R1HA.apk | R1HA 2026.09.03.2328 by itskenny0 (Unlicense), https://github.com/itskenny0/R1HA/releases/tag/r1ha-20260903-2328 (F-Droid flavour) | f6641a8f181968b315f99f0c54337842573309d6965fb2281841516549b90fec |
| ActivityLauncher.apk | Activity Launcher 2.5.0 (ISC), https://github.com/ActivityLauncher/ActivityLauncher, F-Droid build (hash matches the F-Droid index; newer than the 2.4.1 GitHub release) | 0c731f01cb359e133b3c2d1d5655ce21a99de9e44a324fcb4dc64e0dc195c644 |
| ScreenStream.apk | ScreenStream 4.4.2 by Dmytro Kryvoruchko (MIT), https://github.com/dkrivoruchko/ScreenStream, F-Droid build (hash matches the F-Droid index; the GitHub release is the Play Store build) | 900de047c912d2659d00ec8c38bf6de2effadcc77787486ec4481b73e4a4f218 |
| NewPipe.apk | NewPipe 0.29.1 by Team NewPipe (GPL-3.0), https://archive.newpipe.net/fdroid/repo/NewPipe_v0.29.1.apk, signed by Christian Schabesberger (hash and signer match the NewPipe F-Droid repo index) | 18447bfb1e06d113edc88df93f471827280de06f6f3d4dc42f56f28b9c1bab79 |
| UniversalInstaller.apk | Universal Installer 1.18.0 by Nguyen Quang Minh (GPL-3.0-only), https://f-droid.org/packages/app.pwhs.universalinstaller/ (F-Droid build, hash matches the F-Droid index) | 01d3b7eb76b1c0c221502004506e5f9eef670157a6d07306661e75271e905840 |
