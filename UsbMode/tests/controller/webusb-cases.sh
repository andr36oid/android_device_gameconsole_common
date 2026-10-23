# WebUSB follows the shared gate/journal, independently of the function mask.
# shellcheck shell=sh
# shellcheck disable=SC2154,SC2317,SC2034
controller_fixture xinput
setprop "$P.webusb" 0
check start_drive
check test "$(cat "$G/webusb/use")" = 0
check test "$(cat "$G/bcdUSB")" = saved-bcdUSB
check test "$(cat "$G/webusb/landingPage")" = saved-webusb/landingPage
check test "$(cat "$G/os_desc/b_vendor_code")" = 0x1
check cleanup
check_restored
# A subsequent enabled session must work after configfs rebinds the same gadget.
setprop "$P.webusb" 1
setprop "$P.request" start
check start_drive
check_webusb
check test "$(cat "$G/os_desc/use")" = 1
check test "$(cat "$G/os_desc/b_vendor_code")" = 0x1
check test -s "$STATE/controller-webusb_landingPage"
# Recovery runs in a fresh shell, using the saved values rather than properties.
check recover
check_restored
# Failures partway through WebUSB setup must restore every saved attribute.
for attr in bcdUSB webusb/bcdVersion webusb/bVendorCode webusb/landingPage webusb/use; do
    controller_fixture keyboard
    fail_attr=$attr
    if start_drive; then echo "FAIL: WebUSB write failure ignored: $attr"; exit 1; fi
    fail_attr=
    check recover
    check_restored
    check test "$(cat "$G/UDC")" != ff300000.usb
    check test -z "$(sed -n '/^write .*\/UDC ff300000.usb$/p' "$T/log")"
done
# Preserve a pre-existing WebUSB configuration, including an empty landing page.
controller_fixture keyboard
printf '\n' >"$G/webusb/landingPage"
printf '0\n' >"$G/webusb/use"
printf '0x0200\n' >"$G/bcdUSB"
check start_drive
check_webusb
check cleanup
check test -z "$(cat "$G/webusb/landingPage")"
check test "$(cat "$G/webusb/use")" = 0
check test "$(cat "$G/bcdUSB")" = 0x0200
