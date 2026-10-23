#!/bin/bash
# Real filesystems inside regular files only. No loop devices or global sync.
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
T=$1
mkdir -p "$T/bin"
printf '#!/bin/sh\nexit 97\n' >"$T/bin/sync"
chmod +x "$T/bin/sync"
export PATH=$T/bin:/usr/sbin:/sbin:$PATH
# shellcheck disable=SC1091
. "$HERE/andr36oid-virtual-drive.sh"
for fs in fat32 exfat ntfs ext4; do
	case $fs in fat32) tool=mkfs.vfat ;; exfat) tool=mkfs.exfat ;; ntfs) tool=mkntfs ;; ext4) tool=mke2fs ;; esac
	if ! command -v "$tool" >/dev/null; then echo "SKIP: real $fs ($tool missing)"; continue; fi
	image=$T/$fs.img
	truncate -s 1073741824 "$image"
	virtual_mbr 1073741824 "$fs" >"$T/mbr"
	dd if="$T/mbr" of="$image" bs=512 count=1 conv=notrunc status=none
	case $fs in
	fat32) mkfs.vfat -F 32 -s 8 -h 2048 --offset=2048 -n HOSTTEST "$image" 1047552 >"$T/$fs.format.log" 2>&1 ;;
	ext4) mke2fs -t ext4 -b 4096 -E offset=1048576 -L HOSTTEST "$image" 261888 >"$T/$fs.format.log" 2>&1 ;;
	*)
		# These tools lack an offset option. Format the bounded partition file,
		# then copy it sparsely into exactly the helper MBR partition extent.
		truncate -s 1072693248 "$T/partition"
		if [ "$fs" = exfat ]; then mkfs.exfat -L HOSTTEST "$T/partition" >"$T/$fs.format.log" 2>&1
		else mkntfs -F -f -s 512 -p 2048 -L HOSTTEST "$T/partition" >"$T/$fs.format.log" 2>&1; fi
		dd if="$T/partition" of="$image" bs=1M seek=1 conv=notrunc,sparse status=none
		rm "$T/partition" ;;
	esac
	python3 "$HERE/tests/virtual-drive-mbr.py" "$image" "$fs" 1073741824
	test "$(virtual_probe "$image" 1073741824)" = "$fs"
	fdisk -l "$image" >"$T/$fs.fdisk.log"
	blkid -p -O 1048576 "$image" >"$T/$fs.blkid.log"
	file "$image" >"$T/$fs.file.log"
	case $fs in fat32) expected=vfat ;; *) expected=$fs ;; esac
	rg -q "TYPE=\"$expected\"" "$T/$fs.blkid.log"
	echo "Real $fs: MBR and filesystem passed"
done
