# Launcher startup profiles

`Daijishou.prof` and `Andr36oidLauncher.prof` are binary ART baseline profiles
from their respective APKs. `PRODUCT_DEX_PREOPT_PROFILE_DIR` selects them by
module name. The Android 11 build validates their dex checksums, updates their
profile keys, and compiles the matching code with `speed-profile` on the host.
Do not set `speed-profile` globally without supplying matching profiles: an
empty profile does not provide compiled startup methods.

Refresh a profile whenever its APK changes, from the repository root:

```sh
unzip -p apps/Andr36oidLauncher/Andr36oidLauncher.apk assets/dexopt/baseline.prof > dexpreopt-profiles/Andr36oidLauncher.prof
```

For Daijishou, extract `assets/dexopt/baseline.prof` to `Daijishou.prof`, then
follow the hotkey/profile checksum update instructions beside
`PRODUCT_DEX_PREOPT_PROFILE_DIR` in `device.mk`.

Validate using this tree's host `profman`, from the Android source root. The
reference output must exist before calling the tool:

```sh
profile_output=$(mktemp)
out/soong/host/linux-x86/bin/profman --copy-and-update-profile-key \
    --profile-file=device/gameconsole/common/dexpreopt-profiles/Andr36oidLauncher.prof \
    --apk=device/gameconsole/common/apps/Andr36oidLauncher/Andr36oidLauncher.apk \
    --dex-location=/system/app/Andr36oidLauncher/Andr36oidLauncher.apk \
    --reference-profile-file="$profile_output"
rm -f "$profile_output"
```

Require exit status 0 and a nonempty output. The build's `Profile out of date`
message is a fallback, not evidence of a warm native-code cache. For a launcher
update without a compatible profile, remove its stale profile and measure the
fallback before shipping. The boot image uses the framework's existing generic
profile; do not replace it with a launcher profile.
