# Sourced by usb-drive-helper-test.sh, reusing its fake storage, gadget and props.
# shellcheck shell=sh
# shellcheck disable=SC2154,SC2317,SC2034
setprop() {
    printf '%s\n' "$2" >"$T/props/$1"
    printf 'prop %s %s\n' "$1" "$2" >>"$T/log"
    case $1:$2 in
    sys.usb.configfs:2)
        [ "${fail_gate:-0}" = 1 ] || printf '1\n' >"$T/props/$P.gated" ;;
    ctl.stop:joyMouse) printf 'stopped\n' >"$T/props/init.svc.joyMouse" ;;
    ctl.start:joyMouse) printf 'running\n' >"$T/props/init.svc.joyMouse" ;;
    ctl.start:andr36oid-controller)
        if [ "${fail_daemon:-0}" = 0 ]; then
            printf '1\n' >"$T/props/$P.ready"
            printf 'running\n' >"$T/props/init.svc.andr36oid-controller"
        fi ;;
    ctl.stop:andr36oid-controller)
        if [ "${fail_stop:-0}" = 0 ]; then
            printf 'stopped\n' >"$T/props/init.svc.andr36oid-controller"
        fi ;;
    esac
    return 0
}
mount() {
    printf 'mount %s\n' "$*" >>"$T/log"
    [ "${fail_mount:-0}" = 0 ] || return 1
    printf '8 1 0:1 / %s rw - functionfs xinput rw\n' "$FFS" >>"$MOUNTS"
}
umount() {
    printf 'umount %s\n' "$*" >>"$T/log"
    [ "${fail_umount:-0}" = 0 ] || return 1
    awk -v path="$FFS" '$5!=path' "$MOUNTS" >"$T/mounts-new"
    mv "$T/mounts-new" "$MOUNTS"
}
rmdir() {
    printf 'rmdir %s\n' "$1" >>"$T/log"
    [ "${fail_close:-0}" = 0 ] || return 1
    case $1 in
    */hid.andr36oid|*/hid.keyboard|*/hid.mouse) command rm -f "$1"/subclass "$1"/protocol "$1"/report_length "$1"/report_desc "$1"/no_out_endpoint ;;
    esac
    if [ "$1" = "$F" ]; then command rm -rf "$F/lun.0"; fi
    command rm -f "$1"/file "$1"/ro "$1"/removable "$1"/cdrom
    command rmdir "$1"
}
controller_fixture() {
    reset_fixture
    command rm -rf "$DT"
    FFS=$T/ffs
    command mkdir -p "$G/strings/0x409" "$FFS"
    printf 'host\n' >"$OTG"
    printf 'ff300000.usb\n' >"$G/UDC"
    for attr in idVendor bDeviceClass bDeviceSubClass bDeviceProtocol \
        strings/0x409/product strings/0x409/manufacturer configs/b.1/bmAttributes \
        configs/b.1/MaxPower configs/b.1/strings/0x409/configuration \
        os_desc/b_vendor_code os_desc/qw_sign; do
        printf 'saved-%s\n' "$attr" >"$G/$attr"
    done
    setprop init.svc.joyMouse running
    setprop "$P.target" "$1"
    setprop sys.usb.config mtp,adb
    setprop persist.sys.usb.config mtp,adb
    fail_daemon=0 fail_mount=0 fail_umount=0 fail_stop=0
}
check_restored() {
    check test ! -e "$STATE/owned"
    check test ! -e "$STATE/controller"
    check test "$(cat "$OTG")" = host
    check test "$(getprop sys.usb.configfs)" = 1
    check test "$(getprop sys.usb.config)" = mtp,adb
    check test "$(getprop init.svc.joyMouse)" = running
    check test "$(cat "$G/idProduct")" = 0x4ee2
    check test "$(cat "$G/os_desc/use")" = 1
    for attr in idVendor bDeviceClass bDeviceSubClass bDeviceProtocol \
        strings/0x409/product strings/0x409/manufacturer configs/b.1/bmAttributes \
        configs/b.1/MaxPower configs/b.1/strings/0x409/configuration \
        os_desc/b_vendor_code os_desc/qw_sign; do
        check test "$(cat "$G/$attr")" = "saved-$attr"
    done
}
for mode in xinput gamepad keyboard; do
    controller_fixture "$mode"
    check start_drive
    check test "$(prop state)" = sharing
    check test "$(cat "$G/idVendor")" = 0x18d1
    check test "$(getprop init.svc.joyMouse)" = stopped
    check test "$(prop ready)" = 1
    check test ! -d "$F"
    check test ! -e "$STATE/restore"
    check test -z "$(sed -n '/^sm unmount /p' "$T/log")"
    if [ "$mode" = xinput ]; then
        check test "$(cat "$G/strings/0x409/product")" = 'andr36oid XInput Device'
        check test "$(cat "$G/os_desc/use")" = 1
        check test -L "$G/configs/b.1/f2"
    else
        check test "$(cat "$G/os_desc/use")" = 0
        if [ "$mode" = keyboard ]; then
            check test "$(cat "$G/functions/hid.keyboard/no_out_endpoint")" = 1
            check test "$(cat "$G/functions/hid.keyboard/report_length")" = 8
            check test -s "$G/functions/hid.keyboard/report_desc"
            check test -s "$G/functions/hid.mouse/report_desc"
            check test "$(cat "$G/functions/hid.keyboard/subclass")" = 1
            check test "$(cat "$G/functions/hid.keyboard/protocol")" = 1
            check test "$(cat "$G/functions/hid.mouse/subclass")" = 1
            check test "$(cat "$G/functions/hid.mouse/protocol")" = 2
        else
            check test -s "$G/functions/hid.andr36oid/report_desc"
            check test "$(cat "$G/functions/hid.andr36oid/no_out_endpoint")" = 1
        fi
    fi
    # The real shared sequence must stay ordered from host role through readback.
    check awk '/prop sys.usb.configfs 2/ {gate=NR}
        /write .*\/UDC none/ {unbind=NR}
        /prop ctl.start andr36oid-controller/ {daemon=NR}
        /sleep 0.2/ {if (!disconnect) disconnect=NR}
        /write .*\/otg peripheral/ {role=NR}
        /sleep 0.5/ {settle=NR}
        /write .*\/UDC ff300000.usb/ {bind=NR}
        END {exit !(gate<unbind && unbind<daemon && daemon<disconnect && disconnect<role && role<settle && settle<bind)}' "$T/log"
    check cleanup
    check_restored
    check test "$(getprop init.svc.andr36oid-controller)" = stopped
    check test ! -d "$G/functions/ffs.xinput"
    check test ! -d "$G/functions/hid.andr36oid"
done
for failure in gate role bind daemon mount; do
    controller_fixture xinput
    case $failure in gate) fail_gate=1 ;; role) fail_role=1 ;; bind) fail_bind=1 ;;
        daemon) fail_daemon=1 ;; mount) fail_mount=1 ;; esac
    check handle_request
    check test "$(prop state)" = error
    check test "$(prop request)" = stop
    check_restored
done
# A daemon crash and loss of readiness must detach before Android can bind.
controller_fixture xinput
check start_drive
setprop "$P.ready" 0
if bind_drive; then echo 'FAIL: dead controller stayed bound'; exit 1; fi
check cleanup
check_restored
# Keep the gate and saved settings when the daemon or FunctionFS cannot close.
for failure in stop umount; do
    controller_fixture xinput
    check start_drive
    case $failure in stop) fail_stop=1 ;; umount) fail_umount=1 ;; esac
    if cleanup; then echo 'FAIL: controller close failure lost its journal'; exit 1; fi
    check test -e "$STATE/owned"
    check test "$(getprop sys.usb.configfs)" = 2
    fail_stop=0 fail_umount=0
    check cleanup
    check_restored
done
# A helper restart uses the same journal, even if the target property was changed.
controller_fixture xinput
check start_drive
setprop "$P.target" keyboard
check recover
check_restored
controller_fixture gamepad
setprop "$P.owner" '../1'
if validate; then echo 'FAIL: malformed controller lease accepted'; exit 1; fi
controller_fixture keyboard
printf '1\n' >"$POWER/ac/online"
if validate; then echo 'FAIL: controller started while charging'; exit 1; fi
printf '0\n' >"$POWER/ac/online"

# A crash/error after journaling but before taking the gate leaves Android bound.
controller_fixture xinput
check save_controller
check cleanup
check test "$(cat "$G/UDC")" = ff300000.usb
check test -z "$(sed -n '/^write .*\/UDC none$/p' "$T/log")"
check_restored
