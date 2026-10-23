# Shared fake configfs checks, including every supported function mask.
# shellcheck shell=sh
# shellcheck disable=SC2154,SC2317,SC2034
for mask in 1 2 3 4 5 8 9 10 11 12 13 16 17 18 19 20 21 24 25 26 27 28 29 32 33 34 35 36 37 40 41 42 43 44 45 48 49 50 51 52 53 56 57 58 59 60 61; do
    image_fixture
    setprop "$P.functions" "$mask"
    setprop persist.sys.usb.config mtp,adb
    setprop sys.usb.ffs.ready 1
    if [ "$((mask & 1))" = 0 ]; then setprop "$P.target" none; fi
    ln -s "$G/configs/b.1" "$G/os_desc/b.1"
    check start_drive
    check test "$(cat "$G/idProduct")" = "$(printf '0x%04x' "$((54272 + mask))")"
    check test "$(prop active_functions)" = "$mask"
    for pair in '1 mass_storage.andr36oid 1' '2 ffs.xinput 2' '4 hid.andr36oid 3' '8 hid.keyboard 4' '16 hid.mouse 5' '32 ffs.adb 6'; do
        # shellcheck disable=SC2086
        set -- $pair
        if [ "$((mask & $1))" != 0 ]; then
            check test "$(readlink "$G/configs/b.1/f$3")" = "$G/functions/$2"
        else
            check test ! -L "$G/configs/b.1/f$3"
        fi
    done
    check test -L "$G/os_desc/b.1"
    if [ "$((mask & 2))" != 0 ]; then
        check test "$(cat "$G/os_desc/use")" = 1
        check test "$(cat "$G/os_desc/qw_sign")" = MSFT100
    else
        check test "$(cat "$G/os_desc/use")" = 0
    fi
    if [ "$((mask & 1))" != 0 ]; then
        check test "$(cat "$F/lun.0/file")" = "$MEDIA/UUID/isos/Alpine Linux.iso"
        check test "$(cat "$F/lun.0/cdrom")" = 1
    fi
    check cleanup
    check test ! -d "$F"
    for function in ffs.xinput hid.andr36oid hid.keyboard hid.mouse; do
        check test ! -d "$G/functions/$function"
    done
    check test -L "$G/os_desc/b.1"
    check test "$(getprop sys.usb.configfs)" = 1
    check test -z "$(sed -n '/^sm unmount /p;/^blockdev /p' "$T/log")"
done
for invalid in 0 6 7 14 31 63 65536 08 '1;reboot' -1; do
    image_fixture
    setprop "$P.functions" "$invalid"
    if validate; then echo "FAIL: invalid mask $invalid accepted"; exit 1; fi
    check test ! -e "$STATE/owned"
done
image_fixture
setprop "$P.functions" 8
if validate; then echo 'FAIL: a storage target was smuggled into keyboard-only mode'; exit 1; fi
image_fixture
setprop "$P.functions" 57
if validate; then echo 'FAIL: adb enabled without developer USB debugging'; exit 1; fi
# Error cleanup closes the image and every HID function; retry keeps the original journal.
for failure in bind file close; do
    image_fixture
    setprop "$P.functions" 29
    case $failure in bind) fail_bind=1 ;; file) fail_file=1 ;; close) fail_close=1 ;; esac
    auto_stop=1
    check handle_request
    check test "$(prop state)" = error
    check test "$(prop request)" = stop
    fail_bind=0 fail_file=0 fail_close=0
    check cleanup
    check test ! -d "$F"
    check test ! -d "$G/functions/hid.keyboard"
    check test ! -d "$G/functions/hid.mouse"
    check test "$(getprop sys.usb.configfs)" = 1
done
# The shell watchdog restores the daemon's brightness journal even after detach fails.
image_fixture
setprop "$P.functions" 5
command mkdir -p "$USB_DRIVE_BACKLIGHT/lcd"
printf '211\n' >"$USB_DRIVE_BACKLIGHT/lcd/brightness"
check start_drive
printf '0\n' >"$USB_DRIVE_BACKLIGHT/lcd/brightness"
printf '173\n' >"$STATE/backlight-lcd"
fail_unbind=1
if cleanup; then echo 'FAIL: detach failure lost'; exit 1; fi
check test "$(cat "$USB_DRIVE_BACKLIGHT/lcd/brightness")" = 211
check test ! -e "$STATE/backlight-lcd"
check test -e "$STATE/owned"
fail_unbind=0
check recover
check test "$(getprop sys.usb.configfs)" = 1
check test ! -e "$STATE/level-backlight-lcd"
