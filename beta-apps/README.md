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
