#!/bin/bash
# Host test of the USB check (UsbTable, UsbScan, UsbCheck; no android.*) on a fake
# /sys/bus/usb/devices, with the real table and the Wi-Fi HAL's ID list.
#   UsbMode/tests/run-tests.sh [usb_support.txt] [wifi_id_list.txt]
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
TABLE=${1:-$HERE/assets/usb_support.txt}
WIFI=${2:-$HERE/../configs/wifi/wifi_id_list.txt}
T=$(mktemp -d)
[ -n "$T" ] && [ -d "$T" ] || exit 1
trap 'rm -rf "$T"' EXIT
# nothing here should sync, but never let a test reach the real one
mkdir -p "$T/bin"
printf '#!/bin/sh\nexit 0\n' >"$T/bin/sync"
chmod +x "$T/bin/sync"
export PATH=$T/bin:$PATH

bash "$HERE/tests/run-controller-tests.sh" "$T/controller"
export USB_DRIVE_CONTROLLER="$T/controller/daemon"

for sh in ${SHELLS:-sh bash mksh}; do
	command -v "$sh" >/dev/null || continue
	rm -rf "$T/root"
	mkdir -p "$T/root"
	"$sh" "$HERE/tests/make-fake-sysfs.sh" "$T/root"
done
SRC=$HERE/src/org/andr36oid/usbmode
mkdir -p "$T/classes"
"$JDK/javac" -encoding UTF-8 -d "$T/classes" "$SRC/UsbTable.java" "$SRC/UsbScan.java" \
	"$SRC/UsbCheck.java" "$SRC/UsbDrive.java" "$HERE/tests/UsbCheckTest.java" \
	"$SRC/VirtualDrive.java" "$HERE/tests/VirtualDriveTest.java" \
	"$HERE/tests/UsbDriveTest.java" "$HERE/tests/UsbDriveImagesTest.java"
"$JDK/java" -cp "$T/classes" org.andr36oid.usbmode.UsbCheckTest "$T/root" "$TABLE" "$WIFI"

"$JDK/java" -cp "$T/classes" org.andr36oid.usbmode.UsbDriveTest "$T/root"
"$JDK/java" -cp "$T/classes" org.andr36oid.usbmode.UsbDriveImagesTest "$T/root"
"$JDK/java" -cp "$T/classes" org.andr36oid.usbmode.VirtualDriveTest "$T/root"

for sh in ${SHELLS:-sh bash mksh}; do
	command -v "$sh" >/dev/null || continue
	"$sh" "$HERE/tests/usb-drive-helper-test.sh" "$T/helper-$sh"
done
bash "$HERE/tests/virtual-drive-real.sh" "$T/real"
