Kernel Tweaks profiles are UTF-8 text files, at most 64 KiB each. A single text file
is easier to inspect, edit and send to maintainers than a zip containing just settings.
The first comment explains what it is. No serial numbers, accounts, filenames from
games, or other personal data are collected.

Export saves nonempty persistent choices, including the helper's knobs exposed in
other settings pages. It omits unset choices, temporary tweaks, and the pause and failed
start counters. Import only accepts persistent tweaks listed in Tweaks.java and their
allowed values; it reports unknown keys and invalid values before applying. Missing
choices keep their current settings. An imported explicit choice is kept even when it
equals this console's default, so exporting it again preserves the shared choice.
The FUSE driver stays on while the saved storage layer is FUSE, as in the normal page.

Files go in `andr36oid-ktprofiles/` on BOOT, then EASYROMS, then internal Download.
Import looks in all three places, including read-only BOOT. Put shared `.txt` files
in those folders from a PC. The helper stages up to 100 files as bounded copies; the
app validates and previews those copies, then changes properties only after Apply.
Duplicate keys or a missing/unknown format marker invalidate the whole file.

Identity includes the device tree model and compatible list, the first line of
BOOT/andr36oid-profile.txt, ro.andr36oid.version, and andr36oid.info.panel (the installed
DTB folder reported by DTB hint), falling back to the panel node's compatible list.
This describes the configured panel; these screens have no readable hardware ID.
Unavailable identity fields are empty. A different version, model, compatible list,
BOOT profile, or panel produces a warning. Absolute stick axis flags are exported,
so check them carefully when importing to a different console or BOOT profile.

Maintainers can install profiles as `/system/etc/andr36oid-ktprofiles/<name>.txt`,
for example with a device product's `PRODUCT_COPY_FILES`. No profiles ship yet.
The same format has two optional selectors: `match.model` is an exact DT model;
`match.compatible` is one exact token from the DT compatible list. A built-in needs
at least one selector, and both must match if both are present. Use a clone-specific
compatible, rather than a generic SoC such as rockchip,rk3326. Matching built-ins
appear as "Recommended for this console" in Import; they are never applied at boot
or without confirmation. No selector is used to auto-apply exported files either.

Example only (replace identity and selectors with the target console's actual values):

```text
# andr36oid Kernel Tweaks profile: saved choices and console identity only.
format=andr36oid-ktprofile-1
model=Example EE clone
compatible=example,ee-clone rockchip,rk3326
profile=Experimental/EE-V12-Var1-Panel8
version=example-version
panel=example,panel8
match.model=Example EE clone
match.compatible=example,ee-clone
persist.andr36oid.kt.sleep_type=s2idle
persist.andr36oid.kt.stick_invert=0 0 1 0
persist.andr36oid.kt.cpu_idle=off
```

New tweak values belong in Tweaks.java, not the profile parser. The root helper
copies data but never evaluates or applies imported file contents. Boot options
still go through the existing apply service and BOOT/kernel-tweaks.txt; the normal
restart prompt handles them. Import preserves the Use tweaks switch, including a
pause after failed starts.
