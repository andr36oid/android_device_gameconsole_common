# shellcheck shell=sh
# shellcheck disable=SC2154,SC2317,SC2034
for mask in 65536 132096 132112; do
    image_fixture
    setprop "$P.functions" "$mask"
    setprop "$P.target" none
    check start_drive
    check_webusb
    if [ "$mask" = 65536 ]; then
        check test "$(cat "$G/idVendor")" = 0x054c
        check test "$(cat "$G/idProduct")" = 0x09cc
        check test -L "$G/configs/b.1/f2"
    else
        check test "$(cat "$G/functions/uac2.speaker/p_chmask")" = 3
        check test "$(cat "$G/functions/uac2.speaker/p_srate")" = 48000
        check test "$(cat "$G/functions/uac2.speaker/p_ssize")" = 2
    fi
    check cleanup
done
for size in 737280 1474560 2949120; do
    image_fixture
    for ext in img ima vfd; do
        truncate -s "$size" "$MEDIA/UUID/isos/Floppy.$ext"
        check image_info "Floppy.$ext" >/dev/null
        setprop "$P.image" "Floppy.$ext"
        setprop "$P.stamp" "$(stat -c '%d:%i:%s' "$MEDIA/UUID/isos/Floppy.$ext")"
        setprop "$P.cdrom" 0
        check validate
        check floppy_image
        setprop "$P.cdrom" 1
        if validate; then echo 'FAIL: floppy accepted as CD'; exit 1; fi
    done
done
image_fixture
for ext in ima vfd; do
    truncate -s 1474561 "$MEDIA/UUID/isos/Bad.$ext"
    if image_info "Bad.$ext"; then echo 'FAIL: wrong floppy size accepted'; exit 1; fi
done
for forced in 0 1; do
    image_fixture
    setprop "$P.ro" 1
    check start_drive
    check_webusb
    if [ "$forced" = 1 ]; then : >"$F/lun.0/forced_eject"; fi
    truncate -s 2097152 "$MEDIA/UUID/isos/Second.iso"
    setprop "$P.next_image" Second.iso
    setprop "$P.next_stamp" "$(stat -c '%d:%i:%s' "$MEDIA/UUID/isos/Second.iso")"
    setprop "$P.change" start
    check change_disc
    check test "$(prop change)" = 'done'
    check test "$(cat "$F/lun.0/file")" = "$MEDIA/UUID/isos/Second.iso"
    check test "$(prop name)" = Second.iso
    check lease
    if [ "$forced" = 1 ]; then check test "$(cat "$F/lun.0/forced_eject")" = 1; fi
    setprop "$P.next_image" ../outside.iso
    setprop "$P.change" start
    check change_disc
    check test "$(prop change)" = error
    check test "$(cat "$F/lun.0/file")" = "$MEDIA/UUID/isos/Second.iso"
    setprop "$P.next_image" 'Alpine Linux.iso'
    setprop "$P.next_stamp" "$(stat -c '%d:%i:%s' "$MEDIA/UUID/isos/Alpine Linux.iso")"
    # Replace the candidate during the eject delay: validation must run again.
    sleep() { truncate -s 17 "$MEDIA/UUID/isos/Alpine Linux.iso"; }
    setprop "$P.change" start
    check change_disc
    check test "$(prop change)" = error
    check test "$(cat "$F/lun.0/file")" = "$MEDIA/UUID/isos/Second.iso"
    check test "$image" = Second.iso
    check lease
    # Restore the fixture's nonblocking sleep stub.
    sleep() { :; }
    check cleanup
done
