#!/bin/sh
# shellcheck disable=SC2154
image_fixture
check python3 "$HERE/tests/boot-images.py" "$MEDIA/UUID/isos"
check image_bootable "$MEDIA/UUID/isos/boot.iso" 1048576
for image_case in data bad-checksum outside; do
    if image_bootable "$MEDIA/UUID/isos/$image_case.iso" 1048576; then
        echo "FAIL: non-bootable $image_case accepted"; exit 1
    fi
done
check scan_images
check test -n "$(sed -n '/boot.iso.*bootable$/p' "$STATE/images")"
check test -z "$(sed -n '/data.iso.*bootable$/p' "$STATE/images")"
