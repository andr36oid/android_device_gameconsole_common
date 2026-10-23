#!/bin/sh
# shellcheck disable=SC2034,SC2154
# Sourced by the same fake-root fixture as create, repair and delete.
for ext in iso img ima; do
    virtual_fixture
    check image_root
    rename_source='Alpine Linux.iso'
    if [ "$ext" != iso ]; then
        mv "$easy_root/isos/$rename_source" "$easy_root/isos/Original.$ext"
        rename_source=Original.$ext
        if [ "$ext" = ima ]; then /usr/bin/truncate -s 1474560 "$easy_root/isos/$rename_source"; fi
    fi
    setprop "$P.image" "$rename_source"
    setprop "$P.stamp" "$(image_info "$rename_source")"
    setprop "$P.vname" Renamed
    setprop "$P.request" rename
    check virtual_request
    check test "$(prop vresult)" = 'done'
    check test ! -e "$easy_root/isos/$rename_source"
    check test -f "$easy_root/isos/Renamed.$ext"
    check test -z "$(sed -n '/^losetup .*sizelimit/p' "$T/tools.log")"
done
for reason in exists name changed shared unavailable; do
    virtual_fixture
    check image_root
    setprop "$P.vname" Renamed
    setprop "$P.request" rename
    case $reason in
        exists) printf 'keep\n' >"$easy_root/isos/Renamed.iso" ;;
        name) setprop "$P.vname" ../escape ;;
        changed) setprop "$P.stamp" 0:0:1 ;;
        shared) printf '%s\n' "$easy_root/isos/Alpine Linux.iso" >"$T/loop" ;;
        unavailable) printf 'public:179,7 mounted_ro UUID\n' >"$T/volumes" ;;
    esac
    check virtual_request
    check test "$(prop vresult)" = error
    check test "$(prop verror)" = "$reason"
    check test -f "$easy_root/isos/Alpine Linux.iso"
    if [ "$reason" = exists ]; then check test "$(cat "$easy_root/isos/Renamed.iso")" = keep; fi
done
check awk '/^on property:sys.andr36oid.usbdrive.request=rename$/ {rename=1; next}
    rename && /start andr36oid-usb-drive/ {found=1}
    END {exit !found}' "$HERE/../init.rk30board.rc"
