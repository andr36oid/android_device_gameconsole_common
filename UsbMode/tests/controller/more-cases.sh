# Additional functions reuse the fake gadget, leases, detach and recovery checks.
# shellcheck shell=sh
# shellcheck disable=SC2154,SC2317,SC2034
mkdir() {
    command mkdir "$@" || return 1
    if [ "$#" = 1 ]; then
        if [ "$1" = "$F" ]; then command mkdir "$F/lun.0"; fi
        if [ "$1" = "$G/functions/rndis.link" ]; then command mkdir -p "$1/os_desc/interface.rndis"; fi
    fi
}
ndc() { printf 'ndc %s\n' "$*" >>"$T/log"; }
for mask in 64 128 256 512 1024 2048 4096 8192 16384 32768 88 280 3096 1049 32792 33032; do
    image_fixture
    setprop "$P.functions" "$mask"
    setprop "$P.target" none
    if [ "$((mask & 1))" != 0 ]; then setprop "$P.target" file; fi
    check start_drive
    identity=$("$CONTROLLER" --policy "$mask")
    check test "$(cat "$G/idVendor")" = "${identity% *}"
    check test "$(cat "$G/idProduct")" = "${identity#* }"
    for pair in '64 hid.media 7' '128 hid.andr36oid 8' '256 midi.pads 9' '512 acm.echo 10' '1024 uac2.speaker 11' '2048 ncm.link 12' '32768 rndis.link 13'; do
        # shellcheck disable=SC2086
        set -- $pair
        if [ "$((mask & $1))" != 0 ]; then
            check test "$(readlink "$G/configs/b.1/f$3")" = "$G/functions/$2"
        else
            check test ! -L "$G/configs/b.1/f$3"
        fi
    done
    if [ "$((mask & 28674))" != 0 ]; then
        check test "$(readlink "$G/configs/b.1/f2")" = "$G/functions/ffs.xinput"
    fi
    if [ "$mask" = 128 ]; then check test "$(cat "$G/functions/hid.andr36oid/no_out_endpoint")" = 0; fi
    if [ "$((mask & 256))" != 0 ]; then check test "$(cat "$G/functions/midi.pads/id")" = ar36midi; fi
    if [ "$((mask & 1024))" != 0 ]; then
        check test "$(cat "$G/functions/uac2.speaker/p_chmask")" = 0
        check test "$(cat "$G/functions/uac2.speaker/c_chmask")" = 3
        check test "$(cat "$G/functions/uac2.speaker/c_srate")" = 48000
        check test "$(cat "$G/functions/uac2.speaker/c_ssize")" = 2
    fi
    if [ "$((mask & 2048))" != 0 ]; then check test "$(cat "$G/functions/ncm.link/ifname")" = 'ar36n%d'; fi
    if [ "$((mask & 32768))" != 0 ]; then check test "$(cat "$G/functions/rndis.link/ifname")" = 'ar36r%d'; fi
    check cleanup
    for function in hid.media hid.andr36oid midi.pads acm.echo uac2.speaker ncm.link rndis.link ffs.xinput; do
        check test ! -d "$G/functions/$function"
    done
    check test "$(getprop sys.usb.configfs)" = 1
done
for mask in 192 2176 6144 34816 2593 66 320; do
    image_fixture
    setprop "$P.target" none
    setprop "$P.functions" "$mask"
    if validate; then echo "FAIL: invalid new set $mask accepted"; exit 1; fi
    check test ! -e "$STATE/owned"
done
# Errors after creating all functions must remove them through the same journal.
for mask in 64 128 256 512 1024 2048 4096 8192 16384 32768; do
    image_fixture
    setprop "$P.target" none
    setprop "$P.functions" "$mask"
    fail_bind=1 auto_stop=1
    check handle_request
    check test "$(prop state)" = error
    fail_bind=0
    check cleanup
    check test "$(getprop sys.usb.configfs)" = 1
done
