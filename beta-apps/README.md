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
