# Hotfixes

Put maintainer-provided `<name>.fix.zip` files in `BOOT/hotfix` or
`EASYROMS/hotfix`. The folder name accepts any case, such as `Hotfix` or `HOTFIX`.
Android reads BOOT first, then EASYROMS, in filename order within each folder.
BOOT wins if both have the same archive filename (ignoring case) or manifest
name; the EASYROMS copy is logged as skipped. This also applies if the BOOT copy
is disabled or for another build. Only a fix for the running build runs. Files
for other builds stay on the card and are logged as skipped. Removing a zip stops
applying it on the next start. Settings > System > Hotfixes shows each fix's
source and results from the last start, and switches hotfixes on or off for the
next start. Cards without EASYROMS (.noroms or pure eMMC) use only BOOT.

After vold mounts EASYROMS, the Go setup service creates its hotfix folder and
two-line README on fresh and existing cards, including cards with an existing ROM
layout. It reuses any case of the folder name and never overwrites an existing
README. A separate marker in /data records success once per card UUID: edited or
deleted instructions stay that way, and an interrupted or failed setup is retried.
Deleting the whole folder also leaves it deleted after successful setup. This
work runs in both the normal and late EASYROMS jobs, independently of ROM seeding.
The early hotfix runner never creates folders or writes anything on either card.

Hotfix scripts run as root. Distribute and install only trusted archives. These
are release patches, without a signature or download mechanism.

## Make a hotfix

The host generator needs Python 3. It reads the image's GPT and FAT BOOT directly,
without root or mounts. `.img`, `.img.xz` and `.img.gz` are accepted. Compressed
images are decompressed into a temporary directory; allow enough disk space for
the image, the partitions being checked and the zip. `TMPDIR` selects that space.

```
python3 hotfix/tools/make-hotfix.py release.img.xz \
    --name audio-fix --description 'Fix audio after waking from sleep' \
    --replace /system/lib64/libfoo.so=./libfoo.so \
    --replace /vendor/lib64/libbar.so=./libbar.so
```

This writes `audio-fix.fix.zip` in the current directory and refuses to overwrite
an existing output. `--output /path/audio-fix.fix.zip` selects another path.
Each target must be an existing regular file in that release. The generator uses
`dump.erofs --path` for EROFS and `debugfs -R stat` for ext4, without mounting or
extracting the filesystems. Android 11's system image contains `/system`; vendor
paths are relative to its partition root. Install erofs-utils and e2fsprogs as
needed, or select binaries with `DUMP_EROFS` and `DEBUGFS`. `--no-check` skips target
checks with a warning; the device still checks targets before binding.

`--script ./hotfix.sh` supplies a custom script, with or without `--replace`.
Replacement payloads are named `files/0`, `files/1`, etc., in argument order. The
generator uses BOOT/andr36oid-build.txt from the release image, even when a custom
script is supplied. It requires metadata format 1. It does not use the BOOT file
on a running console, which the flasher may remove.

## Make a delta between releases

```
python3 hotfix/tools/make-hotfix.py old-release.img.xz \
    --delta new-release.img.xz --name release-delta
```

Both arguments accept `.img`, `.img.xz` and `.img.gz`. The manifest matches the
OLD image's build identity. The description names the new build and summarizes
the included changes; `--description` adds a prefix. The generator writes
`release-delta.fix.zip` and prints and saves `release-delta.report.txt` beside it.
`--output /path/name.fix.zip` also puts `name.report.txt` there. Existing archives
or reports are never overwritten. Delta mode cannot use `--replace`, `--script`
or `--no-check`. Review the report before distributing an archive: it can contain
only part of the new release, or no applicable changes.

The generator streams decompression to disk in 1 MiB chunks. It reads GPT and
FAT16/FAT32 itself, extracts system with `fsck.erofs --extract --no-preserve`, and
reads ext4 vendor with read-only `debugfs`. EROFS inode and xattr metadata are
read directly, using the format supported by this board's EROFS tools. No root,
loop device or mount is used. Host tools, `/usr/sbin`, `/sbin` and
`/andr36oid/src/out/host/linux-x86/bin` are searched. `FSCK_EROFS` and `DEBUGFS`
select other binaries. Allow disk space for both decompressed releases, raw
partitions and extracted content; `TMPDIR` selects it. A missing tool, unreadable
filesystem or unsupported metadata format stops generation.

System and vendor are compared by SHA-256 content, numeric owner and mode,
symlink target and readable extended attributes, including SELinux labels.
Timestamps and random GPT GUIDs are ignored. Existing regular files go in
`files/` and use `hotfix_bind`, then receive the new owner, mode and SELinux label.
Metadata-only changes are included too. The original build properties remain
unchanged because they were loaded before hotfixes.

Adding or deleting a file, changing a symlink or changing a file's type needs a
directory bind. The generator finds the smallest enclosing directory which
already exists in both releases, below `/system` or `/vendor`. On each start its
script copies that OLD directory to private storage on /data, applies changes,
sets the new tree's owners, modes and labels, and binds the rebuilt directory
over the existing one. Unchanged and hidden files are retained. New symlinks are
created by the script, never stored as links in the zip. Parent and child
overlays are combined so one mount cannot hide another. These functions travel
inside `hotfix.sh`, so the OLD release only needs the original `hotfix_bind`
helper. Removing the archive restores the original tree on the next start.

`--max-dir-bytes` defaults to 8388608 (8 MiB), measured on the larger of the old
and new directory trees. `--max-dir-entries` defaults to 256 and counts all nodes,
including directories and symlinks. Both limits apply to each rebuilt directory.
Oversized copies and additions directly under a partition root are left out and
explained in the report; eligible regular file replacements still work. If the
new image has no readable label, existing entries copy their current label.
New entries inherit the enclosing directory's label with a report warning;
verify them under enforcing SELinux on hardware.

All BOOT differences, including Image, DTBs, ramdisks, boot.ini, profile folders
and build metadata, are reported as needing a full update. BOOT is never written;
there is no `--include-boot` option. Partition layouts, MBR boot code, reserved
image gaps and other raw partitions (including bootloaders) are checked and
reported too. Kernel
modules, init files, fstab/ueventd files, loaded properties, SELinux policies and
already mounted APEXes cannot be replaced safely at this stage. Files with
extended attributes beyond SELinux, such as capabilities or ACLs, are left out.
A directory containing such files or a change requiring a full update is also
left out, to avoid silently replacing protected content during a directory copy.

`--warn-bytes` defaults to 16777216 (16 MiB packed). Both modes warn above it and
enforce the runner's 128 MiB unpacked and 512-entry limits. Packed limits are
67108864 bytes (64 MiB) on BOOT and 134217728 bytes (128 MiB) on EASYROMS. BOOT is
256 MiB in mkimg.sh (sectors 32768 through 557055). The actual OLD image's FAT
allocation table supplies free space; a BOOT zip must fit with cluster rounding
and two clusters reserved for directory growth. When a zip exceeds that space or
BOOT's packed limit, the generator still writes it and says to put it in
EASYROMS/hotfix. It does not estimate EASYROMS free space: firstresize grows it
to the card, so check the actual card. A zip over both packed limits, or over an
unpacked/entry limit, fails and leaves no zip;
delta mode still prints and saves its report. User files and other hotfixes can
leave less space on a console than in the release image.

The script still has the runner's 30 second deadline. Directory copies need
space on /data and run on every start. Previously loaded code, running services,
other mount points and other hotfixes can affect the result. A delta does not
change the running build identity or replace a full release update. Validate it
on the target hardware, including disabling it and booting without it.

## Archive and script format

An archive contains `manifest.txt`, `hotfix.sh`, and optionally regular files and
directories under `files/`. The manifest is UTF-8, one key=value per line:

```
name=audio-fix
description=Fix audio after waking from sleep
build=1.2-beta|1234567890
```

`build` is exactly `ro.andr36oid.version`, a literal `|`, then
`ro.product.build.version.incremental`. Both properties must exist and both must
match. These are the two properties mkimg.sh writes in the release metadata.
Names use 1-64 ASCII letters, digits, dots, underscores or hyphens, beginning
with a letter or digit. All three values are required. Values have no control
characters; description and build are at most 1024 UTF-8 bytes each. CRLF is
accepted. The manifest is parsed as data, never sourced as shell code.

The runner sources `/system/etc/andr36oid-hotfix-lib.sh` before the script and
sets the current directory to `HOTFIX_DIR`. Available helpers and variables:

* `HOTFIX_DIR`: this archive's freshly unpacked private directory on /data.
* `HOTFIX_NAME`: the manifest's name.
* `hotfix_log "message"`: writes to logcat and this start's bounded script log.
* `hotfix_bind files/0 /system/lib64/libfoo.so`: copies a regular file from inside
  `HOTFIX_DIR` to private stable storage on /data, copies the target's numeric
  owner, mode and SELinux context, and bind mounts it over the target. The target
  must already exist as a regular file under /system or /vendor. A final symlink
  is rejected; its resolved parent path must also remain under those directories.
  There is no remount or write to the release partitions. Failures return nonzero.

```
#!/system/bin/sh
# Reapplied on every start. Keep this script idempotent.
set -e
hotfix_bind files/0 /system/lib64/libfoo.so
hotfix_log 'audio fix applied'
```

Scripts must be idempotent: they run on EVERY boot because bind mounts do not
survive a restart. Prefer the bind helper to edits of persistent data. Use Android
11 mksh and Toybox syntax. Do not background jobs, change mount namespaces or
start daemons. The 30 second deadline kills the script's process group, including
children; ordinary completion also cleans up its background processes. Output is
capped at 64 KiB per script. A failure is recorded and the runner continues with
the next zip. Changes made before a failure are not rolled back during that start.

## Boot ordering and protection

The board's `on load_persist_props_action` follows the core init action of the
same name: /system, /vendor, /data, runtime APEXes and persistent properties are
ready. The blocking `exec` finishes before the queued `zygote-start` event and
before class main/late_start. This is deliberately later than post-fs-data,
which precedes loading persistent properties in this Android 11 tree.

The checked source is system/core/rootdir/init.rc and init/service.cpp plus
init/service_utils.cpp: exec creates a temporary service with namespace flags 0;
EnterNamespaces does not create a mount namespace without CLONE_NEWNS. By this
stage init has entered the default namespace, and the runtime APEXes are ready,
so the service does not switch back to the bootstrap namespace. The Go helper
creates a process group, without unsharing namespaces. Later zygote and services
inherit the bind mounts. Processes already running earlier in boot may have
loaded the original files; hotfixes cannot replace already loaded code.

BOOT is mounted read-only, under the same /dev/andr36oid-boot.lock directory lock
as the other helpers. `ro.boot.boot_devices` selects the system disk, including
eMMC (ff390000.dwmmc), even when that install's /data lives on an SD card. It does
not search by filesystem label or pick the data card's BOOT.

The shared EASYROMS lookup runs in late-fs after firstresize and data-card
selection, before mount_all --late. Its ready/dev properties select the one
partition; an empty published device means no EASYROMS, with no legacy fallback
or label search. The hotfix runner runs later, before zygote/system_server can
ask vold to mount public volumes. It reads the exFAT boot sector without writing
it, skipping dirty/error flags or unreadable headers. It does not run fsck or
repair anything. Dirty cards wait for vold's normal check later.

The checked kernel config has CONFIG_EXFAT_FS=y. system/vold/fs/Exfat.cpp uses
the in-kernel `exfat` type, not FUSE or `sdfat`. The private mount at
/mnt/andr36oid-hotfix-roms uses ro,nodev,nosuid,noexec,noatime and vold's
uid=0,gid=1023,fmask=0007,dmask=0007. vold also uses MS_DIRSYNC; Toybox mount has
no dirsync flag and this read-only reader has no directory writes to flush.
The header check and mount each have a 5 second deadline, with KILL after one
more second. The Go helper bounds the final wait too: Android 11's Toybox timeout
can keep waiting for a killed command stuck in kernel I/O. Failed mounts are
logged and leave BOOT fixes usable. Archive copies
have a 10 second deadline each. Both sources are staged into private /data work
storage, then unmounted before any script runs. An EASYROMS unmount has a 5 second
deadline and a bounded lazy-detach fallback; if both fail no scripts run. Staging
is limited to 256 MiB packed across both sources, with 16 archives attempted.

While each matching script runs, sys.andr36oid.hotfix.applying=1 lets the boot
animation show "Applying hotfixes ..." in the same grey line under the logo as
the resize notice. Resizing takes priority; the two notices never overlap. The
runner clears the property on completion, failure, timeout and trapped signals;
init clears it again after exec, including an unexpected runner exit. Boots
without applicable hotfix scripts never set it. Bootanim is already started in
late-fs, so it can normally show the notice; very fast scripts or a slow animation
startup may finish before a frame shows it. Hotfixes never wait for bootanim.

Before the first matching script runs, `persist.andr36oid.hotfix.tries` increases
once for that boot. Init resets it on `sys.boot_completed=1`. After two starts
with hotfixes have failed to finish, the next boot sets
`persist.andr36oid.hotfix.paused=crash` and skips scripts. `paused=1` is the user's
off switch, `0` or unset means enabled. Settings clears the counter and pause
when re-enabling. Disabled, mismatched and safe starts do not increment it.
Start+Select sets `androidboot.andr36oid_safe=1` in boot.ini and skips hotfixes for
that start. Updating an older eMMC install must update its boot.ini to get this
safe-start flag; removing zips or the Settings switch still works on old scripts.

The unpacker rejects absolute paths, `.`/`..` components, duplicate names,
backslashes, control characters, links and special files. Limits are 64 MiB packed
per BOOT zip, 128 MiB packed per EASYROMS zip, 128 MiB unpacked per zip, 512 entries
per zip, 256 MiB unpacked across the boot, and 16 archives attempted per boot.
Extraction has a 10 second deadline, using the same Go supervisor.
An archive must unpack completely and validate its manifest before it can run.
Each boot recreates /data/misc/andr36oid-hotfix/work and bind; bind copies stay
there for the rest of the boot. A boot-id marker prevents a second invocation
from removing live bind sources during the same boot.

`/data/misc/andr36oid-hotfix/status` is TSV: name, ran/skipped/failed, reason code,
description, source (BOOT or EASYROMS). It records all discovered zips, including
invalid and disabled ones. The parent is root:system 0750 and status/log are
root:system 0640, overriding
init's umask 077. Unpacked files and bind storage remain private. Settings reads
this snapshot, so files added to either card later appear after a restart. Older
four-column snapshots are shown as BOOT. Normal problem reports and failed-start
reports include status and bounded script output
in summary.txt, through the report's existing scrubbing.

## Build and test the unpacker

Android 11's external/toybox has no unzip. Its chcon also has no --reference;
`stat -c %C` followed by `chcon CONTEXT FILE` copies the label. Its timeout signals
only the direct child, so the Go helper also supervises scripts and kills their
process groups. There are no third-party Go dependencies or network downloads.

```
hotfix/build.sh
hotfix/build.sh --check
hotfix/tests/run-tests.sh /usr/bin/mksh
```

Like emulators/setup and emmcinstall, build.sh uses a current host Go to run vet
and unit tests and produce the static arm64 prebuilt, with trimmed paths and no
build id. The source hash and Go version are recorded beside it. Set TMPDIR and
GOCACHE for host scratch storage. The test runner builds a host binary, runs the
Go tests, exercises the real scripts in mksh with fake Android commands and
Toybox, and tests the generator with tiny GPT/FAT images. Delta tests create two
compressed EROFS/ext4 release fixtures, exercise FAT16 profile directories and
the generated directory script, and check metadata, exclusions, size limits and
the report. Image tests skip with a reason when their host tools are unavailable;
comparison tests still run. A privilege-drop test checks image reading as an
ordinary user when tests run as root. No AOSP build or device access is needed.

## Update preservation still needed outside these worktrees

The BOOT image ships hotfix/README.txt only; setup creates EASYROMS/hotfix later.
mkimg.sh discards any local test payloads from the image's BOOT staging copy
before packaging it.

Two external update paths need maintainer changes; neither is edited here:

* /andr36oid/tooling/patch-remote.sh: add `hotfix` to KEEP (currently near line 72).
  Its tar save/restore already handles directories recursively.
* /andr36oid/work/forks/andr36oid_sdflasher: internal/firmware/boot.go KeptFiles
  currently lists only kernel-tweaks.txt. Adding `hotfix` alone is insufficient:
  internal/flash/plan.go reads each kept name as a file with a 64 KiB cap, and
  PrepareBoot writes that same flat list. Add recursive collection and restoration
  of hotfix/ (including nested files and larger zip payloads), create parent FAT
  directories before writing, and preserve only on updates. Add update tests
  proving multiple archives and subdirectories survive byte for byte, while fresh
  installs use the image's instructions. Keep reasonable aggregate size limits.

Until both paths preserve hotfix/, an update through either can discard the
user's hotfixes. Older-build archives should be preserved too; build matching
prevents them from running on a different release.
