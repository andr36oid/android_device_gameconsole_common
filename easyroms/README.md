Init runs `andr36oid-easyroms.sh` on late-fs, after firstresize has created the
partitions and andr36oid-datacard has selected the data card, before `mount_all --late`.
It follows the userdata link from the active fstab: ff370000.dwmmc on an SD install,
or a36data on an emmcsd install (either slot). A pure emmc install has no EASYROMS.
It checks only partition 7 of that disk in sysfs. Its GPT partition name must be
EASYROMS or absent; the filesystem is not probed. Other cards never enter the choice.

The boot properties are:

- `sys.andr36oid.easyroms.id`: `public:<major>,<minor>`, from the partition's sysfs dev
- `sys.andr36oid.easyroms.dev`: block name, such as mmcblk0p7
- `sys.andr36oid.easyroms.part`: /sys/class/block/<block name>
- `sys.andr36oid.easyroms.ready`: 1 once the decision is published

The first three are empty when this layout has no EASYROMS or its expected partition
cannot be confirmed. Consumers must preserve that absence when ready=1. Only without
ready, and without a value for the property, do they use the legacy fixed paths/id
(older boot paths and host tests). These are sys properties like the existing
sys.andr36oid setup properties, written by init's root helper and read by system apps.
The builds run SELinux permissive; sepolicy-vendor only adds file labels for services.

Host tests: `bash easyroms/tests/run-tests.sh`.
