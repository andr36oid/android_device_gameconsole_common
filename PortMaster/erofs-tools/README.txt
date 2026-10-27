erofs-utils 1.8.10: mkfs.erofs and fsck.erofs
Target: aarch64 (ARM64, ARMv8-A), fully static and stripped.
Compression: LZ4 and LZ4HC enabled (liblz4 1.10.0); zlib 1.3.2 enabled; LZMA and ZSTD disabled.
How built: Cross-built local LZ4/zlib static archives and the provided erofs-utils source with aarch64-linux-gnu-gcc 13.3.0 (-O2 -march=armv8-a), --disable-shared --enable-static, LDFLAGS=-static and libtool -all-static; stripped with aarch64-linux-gnu-strip.
Build date: 2026-10-27 (UTC).
Runtime: GNU libc is linked statically; no Android/bionic libc or other shared libraries are required. ELF reports Linux 3.7.0 minimum.
Verified: no ELF interpreter or dynamic section; QEMU version checks and LZ4/LZ4HC/deflate image creation, fsck, and extraction round trips passed.
Caveat: execution on the physical RK3326 Android device has not been tested.

Integration for the image-selection/optimization draft:
- Android.bp installs the supplied mkfs.erofs/fsck.erofs and the support helper as
  ARM64 static executables in /system/bin. No formatter/checker was rebuilt here.
- Run ./build-support.sh to rebuild only andr36oid-erofs-support with the same
  aarch64-linux-gnu-gcc static-glibc approach (-O2 -march=armv8-a, 64-bit offsets).
  CC/STRIP can select an equivalent cross toolchain. No copyright/license header
  is added to the helper. No runtime tool download or host binary is used.
- The supplied formatter has no pthread compression support: it uses one worker
  and rejects --workers. Use -b4096 -zlz4 -E^xattr-name-filter --preserve-mtime
  --quiet. TODO: measure plain LZ4 against LZ4HC on RK3326 before choosing HC.
- This explicitly follows the task's static-glibc tool requirement rather than
  the design document's original proposal to port erofs-utils to bionic.

Support utility protocol (nonzero exit on invalid input or I/O failure):
  id UUID BASENAME             selection ID; shared vectors: selection-vectors.tsv
  list CANONICAL_DIRECTORY     byte-sorted immediate, regular UTF-8 .img filenames
  candidate DIRECTORY BASENAME / probe FILE
                              bounded, no-follow MBR/btrfs or raw EROFS probe;
                              key=value fields, 64-bit geometry and generation
  record IMAGE UUID            validate IMAGE.meta plus EROFS size/UUID/features
  orphan IMAGE UUID JOB        same record validation for a proven job with no
                              final image; returns the record's device/inode
  estimate ROOT DESTINATION    conservative logical bytes + entries + xattrs,
                              15 percent + 512 MiB reserve, statvfs cluster rounding
  space DESTINATION            available/cluster/file_limit, rejecting ro mounts
  metadata ROOT               chrooted representative metadata/path checks for
                              owners, modes, links, timestamps, ACLs and xattrs
  overlay-safe UPPER           reject legacy OverlayFS references/metacopy/index
  reserve FILE                exclusive creation; returns device/inode
  fsync FILE_OR_DIRECTORY     targeted flush, never a system-wide sync
  publish STAGE FINAL DEV INO  same-directory fsync + renameat2(RENAME_NOREPLACE)
                              + directory fsync; no overwriting fallback
  remove FILE DEV INO          remove only the matching regular no-follow inode
  loop-owned / detach-owned LOOP DEV INO OFFSET
                              verify read-only loop backing identity via ioctl
  namespace PROGRAM ARGS...    private mount namespace + private propagation
  child PROGRAM ARGS...        parent-death SIGKILL before exec; stops orphan tools

Job IDs are exactly 16 lowercase hexadecimal characters.
Record version 1 has exactly 13 unique keys: version, job, source_name_hex,
source_id, generation, offset, sectors, source_size, card_uuid, tool, options,
output_size, output_uuid. It is data, never shell code. source_name_hex encodes
exact UTF-8 bytes; source_id is independently verified against the card UUID.
Generation hashes size, nanosecond mtime, partition geometry, btrfs UUID and
superblock generation. It is cache invalidation, not an integrity hash. A record
with no image is incomplete. A completed pair can be renamed together; family
identity still refers to its original source. Unsupported EROFS incompatibility
bits (anything except 0x1) and non-4096-byte blocks are rejected.

Host smoke checks ran the supplied ARM64 tools under QEMU on small fixtures:
LZ4 + fsck, numeric ownership, modes, nanosecond mtimes, symlinks, hardlinks, FIFO,
ACLs, user xattrs and capabilities; helper checks included sparse 9 GiB geometry,
8 GiB tree accounting, bad features/records, no-replace collisions and fsync.
The container could not create device nodes, so those remain a device check.
fsck extraction sets xattrs before chown, which clears security.capability on the
extracted host file; the capability check therefore used --no-preserve-owner in
a second extraction. On-device conversion verifies metadata on the mounted EROFS
itself and performs no extraction. No host-wide sync or real image mount ran.
