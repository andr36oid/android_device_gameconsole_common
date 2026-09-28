# Beta apps

Apps for beta testers, in every build whose version doesn't end in `-release`
(beta, dirty, debug). They are installed as normal apps on first boot by the
emulator preinstaller, from `beta.list`. A tester who later flashes a release
build over a card keeps them until they uninstall them.

To add one: put the APK here, add a module for it to `Android.mk` and to
`LOCAL_REQUIRED_MODULES` of `andr36oid-beta-apps`, and add its line
(`package versionCode file`) to `beta.list`.

| APK | Source | sha256 |
|-----|--------|--------|
| TrebleInfo.apk | Treble Info v3.0.2, https://github.com/kevintresuelo/treble/releases/tag/v3.0.2 (GPL-3.0) | 4836066e32bdbc906e4f472d1070265989580f80c983fc3b92133270bf45e2e8 |
| CPU-Z.apk | CPU-Z 1.07 by CPUID (freeware), https://download.cpuid.com/cpu-z/android/cpu-z_1.07.apk, signed by CPUID | b420934c8e838ca95038c8c1b10988e67619e2a85be906a9cf842b8d91040bed |
| UsbDeviceInfo.apk | USB Device Info 3.0.0.81 by alt236 (Apache-2.0), https://f-droid.org/packages/aws.apps.usbDeviceEnumerator/ (F-Droid build, hash matches the F-Droid index) | 07be4a7f425f595479cd2aa0aeb9ecb39276e97b9a81da0b1a8e4fde07381f46 |
| F-Droid.apk | F-Droid 2.0.0 (GPL-3.0-or-later), https://f-droid.org/repo/org.fdroid.fdroid_2000050.apk (hash matches the F-Droid index) | 94938d324b03755fb324f143229240c75640e809cdd3a90cc0a545b5d0a61931 |
| QuickShortcutMaker.apk | QuickShortcutMaker 2.4.0 by sika524 (freeware), from APKMirror (com.sika524.android.quickshortcut_2.4.0-20400), developer certificate SHA-256 F4:10:17:BD:…:2D:33:54 (Tokyo, 2011) | b4d2483bfd54ef65e0ed1745e816b507f9cfba660b5faadbf9803bb7062cff39 |
| Haven.apk | Haven 5.89.14 arm64 "terminal" build (AGPL-3.0), https://github.com/GlassHaven/Haven/releases/tag/v5.89.14 (hash matches the release SHA256SUMS; the full build is 169 MB) | 83063ecfa55cec8c2a93d4f54197cbd7755a97be275ad55470faa2fe5f827fbd |
| Termux.apk | Termux 0.118.3 (GPL-3.0), https://f-droid.org/packages/com.termux/ (F-Droid build, hash matches the F-Droid index; universal APK, 114 MB) | e6265a57eb5ca363808488e3b01955958bed93bc0c8a0d281849b363b11027ec |
| Athena.apk | Athena 2.0.3 by SebaUbuntu (Apache-2.0), https://f-droid.org/packages/dev.sebaubuntu.athena/ (F-Droid build, hash matches the F-Droid index) | 03d7823c598211808f2b81f6dae8ffad62a3c880cea64863fd3cd2084d91d577 |
| TotalCommander.apk | Total Commander 3.62d by Christian Ghisler (freeware), https://www.ghisler.com/tcandroid3.apk, signed by Christian Ghisler | d918ccc0506f6ac3b72fb2cc4dc1eccb3f83eb873077d982a79054d8d65467e4 |
| TotalCommanderLAN.apk | Total Commander LAN plugin 3.60 by Christian Ghisler (freeware), http://totalcommander.ch/aplg/tcandroidlan360.apk, signed by Christian Ghisler | c3091b317e4f4c4a0139a98830cef4269a79fc18bd27dce6c94d10e7bae308e8 |
| TotalCommanderSFTP.apk | Total Commander SFTP plugin 2.94 by Christian Ghisler (freeware), http://totalcommander.ch/aplg/tcandroidsftp294.apk, signed by Christian Ghisler | 10b08c835d4b7d1f65a61a1c027fbe0d87ed5c7a79ea4b54240e43a87d4e2090 |
| FreeOTP.apk | FreeOTP 2.0.6 by Red Hat (Apache-2.0), https://f-droid.org/packages/org.fedorahosted.freeotp (F-Droid build, hash matches the F-Droid index) | 48212d63dd3a3a8a8d6201b8d911f239efb1561f08472057b2c55a65ebb0a94f |
