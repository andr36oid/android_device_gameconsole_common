#!/bin/sh
# No USB/sysfs writes: exercise the real admission and release code with a held
# storage lease, then stub only hardware cleanup/start to observe lock lifetime.
set -eu
repo=$(CDPATH='' cd -- "$(dirname "$0")/../../.." && pwd)
T=$(mktemp -d);trap 'rm -rf "$T"' EXIT
USB_DRIVE_TEST=1 USB_DRIVE_STATE=$T/usb USB_DRIVE_STORAGE_LOCK=$T/storage.lock
USB_DRIVE_VIRTUAL=$repo/UsbMode/andr36oid-virtual-drive.sh
export USB_DRIVE_TEST USB_DRIVE_STATE USB_DRIVE_STORAGE_LOCK USB_DRIVE_VIRTUAL
. "$repo/UsbMode/andr36oid-usb-drive.sh"
mkdir -p "$STATE" "$T/props"
log() { :; }
getprop() { cat "$T/props/$1" 2>/dev/null || true; }
setprop() { printf '%s' "$2" > "$T/props/$1"; }
# Lock held by a different file description, as the subsystem would hold it.
exec 6>"$USB_DRIVE_STORAGE_LOCK";flock -n 6 6>&6
handle_request || exit 1
[ "$(prop error)" = storage_busy ]
[ ! -e "$STATE/owned" ]
virtual_request || exit 1
[ "$(prop verror)" = shared ]
[ ! -e "$STATE/virtual" ]
exec 6>&-
virtual_recover() { return 0; }
cleanup() {
  # Cleanup must still own the lease until backing files are closed.
  if (exec 7>&-;exec 6>"$USB_DRIVE_STORAGE_LOCK";flock -n 6 6>&6); then echo "lease lost during cleanup" >&2;exit 90;fi
}
start_drive() { return 1; }
handle_request || exit 1
(exec 6>"$USB_DRIVE_STORAGE_LOCK";flock -n 6 6>&6)
echo 'PASS: USB sharing/virtual-image requests reject an active subsystem lease; cleanup holds the lease and releases it afterwards'
