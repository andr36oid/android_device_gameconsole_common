#!/system/bin/sh
# SPDX-License-Identifier: MIT
# Template only. Deliberately refuses launch until hardware facts are supplied.
# Root-owned reviewed hooks implement these operations idempotently. Never source
# a config or executable from writable EASYROMS as the Android-side root manager.
set -eu
operation=${1:?operation required}
rootfs=${2:?rootfs required}
case "$operation" in
    preflight)
        # TODO G1/G2: pinned rootfs/ELF and blob versions, enforcing domain,
        # fixed mount targets with no symlinks, selected device node manifest,
        # DRM/fd inventory, storage lock, free RAM/disk, battery and recovery chord.
        # TODO: confirm unshare/mount command support and private propagation.
        printf 'REFUSED: supply reviewed hardware-specific hooks for %s\n' "$rootfs" >&2
        exit 1 ;;
    quiesce)
        # TODO: save mixer/VT/backlight/power state before changing it, mute,
        # prevent suspend, prepare independent input recovery. Must roll back
        # partial failure in restore. No guessed mixer control writes.
        exit 1 ;;
    mount)
        # Runs in a private mount namespace after propagation becomes private.
        # TODO: minimal /dev bind list, proc, selected sysfs, /roms data,
        # DNS, disk-backed bounded /tmp. Log each successful mount for unmount.
        # TODO: install the reviewed fixed /usr/local/sbin/portmaster-enter
        # helper for dropping uid/capabilities and entering the guest domain.
        exit 1 ;;
    unmount)
        # TODO: unmount only recorded mounts, reverse order; never umount -l.
        exit 1 ;;
    restore)
        # TODO: restore journaled mixer/VT/backlight/power state. Idempotent.
        exit 1 ;;
    *) exit 2 ;;
esac
